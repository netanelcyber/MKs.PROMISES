'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../web/index.html'), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const clone = value => JSON.parse(JSON.stringify(value));
const fixture = {v:1,mks:[{
  id:'m1',name:'חבר כנסת לבדיקה',faction:'סיעה',knesset:'25',isNew:false,apiPersonId:123,
  promises:[{id:'p1',text:'הבטחה קיימת',topic:'חינוך',sourceUrl:'https://example.test/promise',sourceDate:'',status:'partial',note:'',
    evidence:[{id:'e1',title:'ראיה קיימת',type:'הצעת חוק',effect:'support',date:'',url:'https://example.test/evidence'}]}],background:[]
}]};
const seed = JSON.stringify(fixture);
const failedResponse = () => ({ok:false,status:400,text(){return Promise.resolve(JSON.stringify({error:{message:'The node count limit of 100 has been exceeded.'}}))}});
const emptyResponse = () => ({ok:true,status:200,text(){return Promise.resolve('{"value":[]}')}});

// The real inline application runs against a deterministic DOM/transport harness.
// These checks do not contact the Knesset API or claim browser rendering coverage.
function app(){
  const state={raw:seed,writes:[],fetches:[],shares:[],logs:[],timers:new Map(),closeTasks:[]};
  let timerId=0;
  const listeners={};
  const nodes=Object.fromEntries(['app','items','backBtn','title','toast','apiFailure'].map(id=>[id,{id,innerHTML:'',textContent:'',style:{},hidden:false,addEventListener(name,fn){this[name]=fn},scrollIntoView(){state.scrolled=true}}]));
  nodes.dlgForm={innerHTML:'',elements:{act:{value:'export'}},onsubmit:null};
  nodes.dlg={open:false,onclose:null,showModal(){this.open=true},close(){this.open=false;state.closeTasks.push(()=>{if(this.onclose)this.onclose()})}};
  const document={activeElement:null,getElementById(id){
    if(id==='items'&&!nodes.app.innerHTML.includes('id="items"'))return null;
    if(id==='apiFailure'&&!nodes.app.innerHTML.includes('id="apiFailure"'))return null;
    return nodes[id]||null;
  },addEventListener(name,fn){listeners[name]=fn}};
  const window={scrollTo(){},Android:{shareText(text){state.shares.push(text)}}};
  const context={document,window,navigator:{},location:{protocol:'file:'},URL,TextDecoder,Uint8Array,AbortController,atob,
    localStorage:{getItem(){return state.raw},setItem(key,value){state.writes.push([key,value]);state.raw=value}},
    setTimeout(fn,ms){const id=++timerId;state.timers.set(id,{fn,ms});return id},clearTimeout(id){state.timers.delete(id)},
    fetch(url,options){state.fetches.push({url,options});return Promise.resolve(state.nextResponse?state.nextResponse(url):failedResponse())},
    console:{log(...args){state.logs.push(args)},error(...args){state.logs.push(args)},warn(...args){state.logs.push(args)}}
  };
  const hook='window.__apiTest={report:reportApiError,render:render,toast:toast,state:function(){return S},replaceState:function(value){S=normalizeState(value);render()},failure:function(){return apiFailure},loadMembers:loadMembers,loadBills:loadBills};';
  const instrumented=source.replace(/\}\)\(\);\s*$/,hook+'\n})();');assert.notEqual(instrumented,source);
  vm.runInNewContext(instrumented,context,{filename:'index.html'});
  const h={state,nodes,window,hooks:window.__apiTest,record(){return clone(window.__apiTest.state())},failure(){return clone(window.__apiTest.failure())}};
  h.click=(action,data={})=>{const target={tagName:'BUTTON',dataset:{a:action,...data}};target.closest=()=>target;listeners.click({target})};
  h.flush=async()=>{for(let i=0;i<30;i++){await Promise.resolve();await Promise.resolve();await Promise.resolve();if(state.closeTasks.length)state.closeTasks.shift()()}};
  h.export=async()=>{h.click('menu');nodes.dlgForm.onsubmit({submitter:{value:'ok'},preventDefault(){}});await h.flush();return state.shares.at(-1)};
  h.expireToast=()=>{for(const [id,timer] of state.timers){if(timer.ms===3500){state.timers.delete(id);timer.fn()}}};
  return h;
}

test('API error remains visible after toast timeout and across list/detail navigation until dismissed',()=>{
  const h=app();h.hooks.report('members',{status:400,entity:'KNS_Person',message:'HTTP 400',detail:'The node count limit of 100 has been exceeded.',url:'https://knesset.gov.il/OdataV4/ParliamentInfo/KNS_Person?$filter=Id%20eq%201'},{k:25});
  assert.match(h.nodes.app.innerHTML,/role="alert"/);assert.match(h.nodes.app.innerHTML,/HTTP 400/);assert.match(h.nodes.app.innerHTML,/KNS_Person/);assert.match(h.nodes.app.innerHTML,/node count limit/);
  h.hooks.toast('temporary');h.expireToast();assert.equal(h.nodes.toast.style.display,'none');assert.match(h.nodes.app.innerHTML,/id="apiFailure"/);
  h.click('open',{id:'m1'});assert.match(h.nodes.app.innerHTML,/id="apiFailure"/);assert.equal(h.nodes.title.textContent,fixture.mks[0].name);
  h.window.__back();assert.match(h.nodes.app.innerHTML,/id="apiFailure"/);h.click('dismissApi');assert.ok(!h.nodes.app.innerHTML.includes('id="apiFailure"'));assert.equal(h.failure(),null);
  assert.deepEqual(h.record(),fixture);assert.equal(h.state.raw,seed);assert.equal(h.state.writes.length,0);assert.equal(h.state.fetches.length,0);
});

test('diagnostic fields are escaped, bounded and never automatically logged or exported',async()=>{
  const h=app(),injection='<img src=x onerror=alert(1)>';h.hooks.report('members',{
    status:400,entity:injection,message:injection,detail:injection,responseBody:injection+'x'.repeat(9000),
    url:'https://user:password@knesset.gov.il/OdataV4/ParliamentInfo/KNS_Person?token=very-secret&$filter=Id%20eq%201'
  },{k:25});
  const markup=h.nodes.app.innerHTML;assert.ok(!markup.includes('<img'));assert.match(markup,/&lt;img/);assert.ok(!markup.includes('very-secret'));assert.ok(!markup.includes('user:password'));
  assert.ok(h.failure().responseBody.length<=8192);assert.match(markup,/<details>/);assert.match(markup,/<pre>/);assert.equal(h.state.logs.length,0);
  assert.deepEqual(JSON.parse(await h.export()),fixture);assert.equal(h.state.raw,seed);assert.equal(h.state.writes.length,0);assert.equal(h.state.fetches.length,0);
  h.hooks.report('members',{status:Infinity,entity:'x'.repeat(1000),message:'x'.repeat(2000),detail:'x'.repeat(4000),responseBody:'x'.repeat(20000)});
  const bounded=h.failure();assert.equal(bounded.status,0);assert.ok(bounded.entity.length<=100);assert.ok(bounded.message.length<=600);assert.ok(bounded.detail.length<=1500);assert.ok(bounded.responseBody.length<=8192);
});

test('member-load HTTP errors populate persistent diagnostics and a failed retry replaces the card without modifying saved data',async()=>{
  const h=app();await h.hooks.loadMembers(25);assert.equal(h.failure().action,'members');assert.equal(h.failure().status,400);assert.equal(h.failure().entity,'KNS_PersonToPosition');assert.match(h.failure().responseBody,/node count/);
  assert.match(h.nodes.app.innerHTML,/טעינת חברי הכנסת נכשלה/);h.click('retryApi');assert.equal(h.failure(),null);await h.flush();assert.equal(h.failure().status,400);assert.equal(h.state.fetches.length,2);
  h.state.nextResponse=emptyResponse;h.click('retryApi');await h.flush();assert.equal(h.failure(),null);assert.equal(h.state.fetches.length,3);assert.ok(h.state.fetches[2].url.includes('KnessetNum%20eq%2025'));
  assert.deepEqual(h.record(),fixture);assert.equal(h.state.raw,seed);assert.equal(h.state.writes.length,0);
});

test('bill-load diagnostics retry the same member from either view and do not enter backups',async()=>{
  const h=app();h.click('open',{id:'m1'});await h.hooks.loadBills(h.hooks.state().mks[0]);
  assert.equal(h.failure().action,'bills');assert.equal(h.failure().context.id,'m1');assert.equal(h.failure().entity,'KNS_BillInitiator');assert.match(h.nodes.app.innerHTML,/טעינת הצעות החוק נכשלה/);
  h.window.__back();assert.match(h.nodes.app.innerHTML,/id="apiFailure"/);h.state.nextResponse=emptyResponse;h.click('retryApi');await h.flush();
  assert.equal(h.failure(),null);assert.ok(h.state.fetches.at(-1).url.includes('PersonID%20eq%20123'));assert.deepEqual(JSON.parse(await h.export()),fixture);assert.equal(h.state.raw,seed);assert.equal(h.state.writes.length,0);
});

test('the regular load action also clears the previous diagnostic before its new attempt',async()=>{
  const h=app();h.hooks.report('members',{status:400,message:'old error'},{k:25});h.state.nextResponse=emptyResponse;h.click('loadMembers');assert.equal(h.failure(),null);await h.flush();assert.equal(h.failure(),null);assert.ok(!h.nodes.app.innerHTML.includes('old error'));
  h.hooks.report('bills',{status:400,message:'old bill error'},{id:'m1'});h.click('loadBills',{id:'m1'});assert.equal(h.failure(),null);await h.flush();assert.equal(h.failure(),null);assert.deepEqual(h.record(),fixture);
});

test('failed requests from a replaced dataset do not display stale diagnostics',async()=>{
  for(const action of ['members','bills']){
    const h=app();let deliver;h.state.nextResponse=()=>new Promise(resolve=>{deliver=resolve});
    const pending=action==='members'?h.hooks.loadMembers(25):h.hooks.loadBills(h.hooks.state().mks[0]);await h.flush();assert.equal(typeof deliver,'function');
    h.hooks.replaceState({v:1,mks:[]});deliver(failedResponse());await pending;
    assert.equal(h.failure(),null);assert.ok(!h.nodes.app.innerHTML.includes('id="apiFailure"'));assert.deepEqual(h.record(),{v:1,mks:[]});assert.equal(h.state.writes.length,0);
  }
});
