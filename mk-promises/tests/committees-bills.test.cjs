'use strict';

const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const scoring=require('../web/promise-scoring.js');
const source=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
const KEY='mk-promises-v1',CMT_KEY='mk-promises-committees-v1',BKEY='mk-promises-bills-v1',IKEY='mk-promises-initiators-v1',LKEY='mk-promises-laws-v1';
const BASE='https://knesset.gov.il/OdataV4/ParliamentInfo/';
const clone=value=>JSON.parse(JSON.stringify(value));
const fixture=()=>({v:1,mks:[{id:'m1',name:'חבר כנסת לבדיקה',faction:'סיעה',knesset:'25',isNew:false,apiPersonId:123,
  promises:[{id:'p1',text:'נגדיל את תקציב החינוך',topic:'חינוך',sourceUrl:'https://example.test/promise',sourceDate:'2022-10-01',status:'partial',note:'',election:'2022',evidence:[]}],background:[]}]});
const reply=(value,status=200)=>({ok:status>=200&&status<300,status,headers:{get(){return null}},text(){return Promise.resolve(typeof value==='string'?value:JSON.stringify(value))}});
const decode=text=>String(text).replace(/&(amp|lt|gt|quot|#39);/g,(_,x)=>({amp:'&',lt:'<',gt:'>',quot:'"','#39':"'"})[x]);
const attrs=text=>Object.fromEntries([...text.matchAll(/([\w-]+)="([^"]*)"/g)].map(x=>[x[1],decode(x[2])]));
const bill=i=>({Id:i,KnessetNum:25,Name:'הצעת חוק '+i,SubTypeDesc:'פרטית'});
const allBills=Array.from({length:250},(_,i)=>bill(i+1));

// Runs the whole inline application against a deterministic DOM, storage and transport harness.
// Nothing here contacts the Knesset; every response comes from the test.
function app(options={}){
  const storage=new Map([[KEY,JSON.stringify(options.initial||fixture())]]);
  for(const [key,value] of Object.entries(options.storage||{}))storage.set(key,typeof value==='string'?value:JSON.stringify(value));
  const state={fetches:[],shares:[],writes:[],tasks:[],timers:new Map(),logs:[]};let timerId=0;
  const listeners={};
  class Element{
    constructor(id){this.id=id;this.style={};this.hidden=false;this.textContent='';this._html='';this.listeners={}}
    get innerHTML(){return this._html}set innerHTML(value){this._html=value}
    addEventListener(name,fn){this.listeners[name]=fn}focus(){}scrollIntoView(){}
  }
  class Form extends Element{
    get innerHTML(){return this._html}
    set innerHTML(value){
      this._html=value;this.elements=Object.create(null);
      for(const match of value.matchAll(/<(input|textarea|select)\b([^>]*)(?:>([\s\S]*?)<\/\1>|>)/g)){
        const a=attrs(match[2]);if(!a.name)continue;let fieldValue=a.value||'';
        if(match[1]==='textarea')fieldValue=decode(match[3]||'');
        if(match[1]==='select'){
          const choices=[...(match[3]||'').matchAll(/<option([^>]*)>([\s\S]*?)<\/option>/g)];const selected=choices.find(o=>/\bselected\b/.test(o[1]))||choices[0];
          if(selected)fieldValue=attrs(selected[1]).value??decode(selected[2]);
        }
        this.elements[a.name]={name:a.name,value:fieldValue,checked:/\bchecked\b/.test(match[2]),focus(){}};
      }
    }
  }
  const nodes=Object.fromEntries(['app','items','title','backBtn','toast','apiFailure','blProg','blItems','iniProg','lwProg','lwItems'].map(id=>[id,new Element(id)]));
  nodes.dlgForm=new Form('dlgForm');nodes.dlg=Object.assign(new Element('dlg'),{open:false,onclose:null,
    showModal(){assert.equal(this.open,false);this.open=true},close(){if(!this.open)return;this.open=false;state.tasks.push(()=>{if(this.onclose)this.onclose()})}});
  const document={activeElement:null,getElementById(id){
    if(['items','apiFailure','blProg','blItems','iniProg'].includes(id)&&!nodes.app.innerHTML.includes('id="'+id+'"'))return null;
    return nodes[id]||null;
  },addEventListener(name,fn){listeners[name]=fn}};
  const window={scrollTo(){},MKPromiseScoring:scoring,Android:options.android===undefined?{shareText(text){state.shares.push(text)}}:options.android};
  const context={document,window,navigator:{},location:{protocol:'file:'},URL,Blob,TextEncoder,TextDecoder,Uint8Array,AbortController,atob,btoa,
    localStorage:{getItem(key){return storage.get(key)??null},setItem(key,value){storage.set(key,value);state.writes.push(key)},removeItem(key){storage.delete(key)}},
    // The 120 ms page delay resolves immediately; all other timers (network timeouts, toast) are inspectable and never fire.
    setTimeout(fn,ms){if(ms===120){Promise.resolve().then(fn);return 0}const id=++timerId;state.timers.set(id,{fn,ms});return id},clearTimeout(id){state.timers.delete(id)},
    fetch(url,config){state.fetches.push(url);return Promise.resolve(options.route?options.route(new URL(url),config,h):reply({value:[]}))},
    console:{log(...args){state.logs.push(args)},warn(...args){state.logs.push(args)},error(...args){state.logs.push(args)}}
  };
  const hook='window.__t={state:function(){return S},normalize:normalizeState,bl:bl,cm:cm,ini:ini,lw:lw,failure:function(){return apiFailure},busy:function(){return blRun||iniRun||lwRun||cmLoading},view:function(){return view}};';
  const instrumented=source.replace(/\}\)\(\);\s*$/,hook+'\n})();');assert.notEqual(instrumented,source);
  vm.runInNewContext(instrumented,context,{filename:'index.html'});
  const h={state,storage,nodes,window,hooks:window.__t,record(){return clone(window.__t.state())},failure(){return clone(window.__t.failure())},html(){return nodes.app.innerHTML}};
  h.flush=async()=>{for(let i=0;i<40;i++){await Promise.resolve();await Promise.resolve();await Promise.resolve();if(state.tasks.length)state.tasks.shift()()}};
  // Waits for a long promise chain such as the $skip loop to finish.
  h.settle=async()=>{for(let i=0;i<500;i++){await new Promise(resolve=>setImmediate(resolve));if(state.tasks.length){state.tasks.shift()();continue}if(!h.hooks.busy()&&!nodes.dlg.open)return}throw new Error('still busy')};
  h.click=(action,data={})=>{const target={tagName:'BUTTON',dataset:{a:action,...data}};target.closest=()=>target;listeners.click({target})};
  h.type=(id,value)=>listeners.input({target:{id,value}});
  h.submit=(values={},button='ok')=>{assert.equal(nodes.dlg.open,true,'a dialog is open');for(const [key,value] of Object.entries(values)){assert.ok(nodes.dlgForm.elements[key],key);if(typeof value==='boolean')nodes.dlgForm.elements[key].checked=value;else nodes.dlgForm.elements[key].value=value}nodes.dlgForm.onsubmit({submitter:{tagName:'BUTTON',value:button},preventDefault(){}})};
  h.written=key=>JSON.parse(storage.get(key));
  return h;
}

/* ---------- election / war fields ---------- */

test('promise election and war markers are optional, validated, and never added to existing records',()=>{
  const h=app();const plain=fixture();delete plain.mks[0].promises[0].election;
  assert.deepEqual(clone(h.hooks.normalize(plain)),plain,'a backup without the new fields round-trips unchanged');
  const marked=fixture();marked.mks[0].promises[0].war=true;
  assert.deepEqual(clone(h.hooks.normalize(marked)),marked);
  const falseWar=fixture();falseWar.mks[0].promises[0].war=false;
  assert.equal('war' in h.hooks.normalize(falseWar).mks[0].promises[0],false);
  for(const election of ['1999',5,{}]){const bad=fixture();bad.mks[0].promises[0].election=election;assert.throws(()=>h.hooks.normalize(bad))}
  for(const war of ['yes',1,{}]){const bad=fixture();bad.mks[0].promises[0].war=war;assert.throws(()=>h.hooks.normalize(bad))}
});

test('the promise form records the election and war marker and the member page shows them without touching scoring',async()=>{
  const h=app();h.click('open',{id:'m1'});h.click('addPromise',{id:'m1'});
  assert.equal(h.nodes.dlgForm.elements.election.value,'2026','new promises default to the 2026 election');
  assert.equal(h.nodes.dlgForm.elements.war.checked,false);
  h.submit({text:'נקצר את התור לרופא',topic:'בריאות',sourceUrl:'https://example.test/second',sourceDate:'2023-11-02',election:'2026',war:true,status:'unchecked'});await h.flush();
  const promise=h.record().mks[0].promises[1];
  assert.equal(promise.election,'2026');assert.equal(promise.war,true);assert.equal(h.written(KEY).mks[0].promises[1].war,true);
  assert.match(h.html(),/הכנסת ה-26/);assert.match(h.html(),/נאמרה אחרי 7\.10\.2023/);assert.match(h.html(),/הושפעה מהמלחמה/);
  assert.equal(scoring.scorePromise(promise).value,null);
  h.click('editPromise',{id:'m1',p:promise.id});assert.equal(h.nodes.dlgForm.elements.election.value,'2026');assert.equal(h.nodes.dlgForm.elements.war.checked,true);
  h.submit({war:false});await h.flush();assert.equal('war' in h.hooks.normalize(h.record()).mks[0].promises[1],false);
});

/* ---------- committees ---------- */

function committeeRoute(log){
  const sessions=[
    {Id:10,CommitteeID:1,StartDate:'2026-05-04T10:00:00Z',TypeDesc:'פתוחה',SessionUrl:'https://main.knesset.gov.il/a'},
    {Id:11,CommitteeID:1,StartDate:'2026-06-01T10:00:00Z',TypeDesc:'סגורה',SessionUrl:null},
    {Id:12,CommitteeID:2,StartDate:'2026-07-01T10:00:00Z',TypeDesc:'פתוחה',SessionUrl:'javascript:alert(1)'}];
  return url=>{
    log.push(url);const entity=url.pathname.split('/').pop();
    if(entity==='KNS_Committee')return reply({value:[{Id:1,Name:'ועדת החינוך',CommitteeTypeDesc:'ועדה קבועה'},{Id:2,Name:'ועדת הכספים',CommitteeTypeDesc:'ועדה קבועה'}]});
    if(entity==='KNS_CommitteeSession'){
      if(url.searchParams.get('$skiptoken')==='2')return reply({value:sessions.slice(2)});
      const next=new URL(url);next.searchParams.set('$skiptoken','2');
      return reply({value:sessions.slice(0,2),'@odata.nextLink':next.href});
    }
    if(entity==='KNS_PersonToPosition')return reply({value:[
      {PersonID:123,CommitteeID:1,DutyDesc:'חבר ועדה',StartDate:'2023-01-02T00:00:00Z',FinishDate:null},
      {PersonID:999,CommitteeID:1,DutyDesc:'יו"ר הוועדה',StartDate:'2023-01-02T00:00:00Z',FinishDate:null},
      {PersonID:123,CommitteeID:2,DutyDesc:'חבר ועדה',StartDate:'2023-01-02T00:00:00Z',FinishDate:'2026-01-01T00:00:00Z'}]});
    return reply({value:[]},404);
  };
}

test('committee loading uses v4 keys and paging, keeps its cache apart from the member data, and shows per-member committees',async()=>{
  const urls=[];const h=app({route:committeeRoute(urls)});
  h.click('openCmts');assert.equal(h.hooks.cm(),null);assert.match(h.html(),/טעינה מהכנסת/);assert.equal(h.state.fetches.length,0,'opening the screen never fetches');
  h.click('loadCmts');await h.settle();
  const by=name=>urls.filter(u=>u.pathname.endsWith('/'+name));
  assert.equal(by('KNS_Committee')[0].searchParams.get('$select'),'Id,Name,CommitteeTypeDesc');
  assert.equal(by('KNS_CommitteeSession')[0].searchParams.get('$select'),'Id,CommitteeID,StartDate,TypeDesc,SessionUrl');
  assert.equal(by('KNS_CommitteeSession').length,2,'the @odata.nextLink page is followed');
  assert.match(by('KNS_CommitteeSession')[0].searchParams.get('$filter'),/StartDate ge 2026-04-27T00:00:00Z and StartDate le 2026-10-27T23:59:59Z/);
  assert.match(by('KNS_PersonToPosition')[0].searchParams.get('$filter'),/CommitteeID ne null/);
  for(const u of urls){assert.equal(u.searchParams.get('$top'),null,'no $top, which would cap the whole result');assert.ok(!u.href.includes(';'))}
  const cached=h.written(CMT_KEY);assert.equal(cached.committees.length,2);assert.equal(cached.sessions.length,3);assert.equal(cached.roles.length,3);
  assert.deepEqual(h.record(),fixture(),'member data is untouched');assert.deepEqual(h.state.writes,[CMT_KEY],'only the committee cache is written');
  assert.match(h.html(),/ועדת החינוך/);assert.match(h.html(),/2 ישיבות · 2 ח"כים/);assert.match(h.html(),/1 ישיבות · 0 ח"כים/);
  h.click('openCmt',{id:'1'});assert.match(h.nodes.title.textContent,/ועדת החינוך/);
  assert.ok(h.html().indexOf('יו"ר הוועדה')<h.html().indexOf('חבר כנסת לבדיקה'),'the chair is listed first');
  assert.match(h.html(),/ח&quot;כ #999/);assert.ok(!h.html().includes('javascript:'),'unsafe session links are not rendered');
  h.window.__back();assert.equal(h.hooks.view().name,'cmts');h.window.__back();assert.equal(h.hooks.view().name,'list');
  h.click('open',{id:'m1'});assert.match(h.html(),/ועדת החינוך/);assert.ok(!h.html().includes('ועדת הכספים'),'a role that ended before the window is excluded');
  assert.match(h.html(),/הצעה לפי נושא: ועדת החינוך/);assert.match(h.html(),/אלה ישיבות הוועדה ולא נוכחות אישית/);
});

test('assigning a committee to a promise adds linked context evidence only and never changes the status or score',async()=>{
  const h=app({route:committeeRoute([])});h.click('loadCmts');await h.settle();h.click('open',{id:'m1'});
  h.click('cmtToPromise',{id:'m1',p:'p1'});assert.equal(h.nodes.dlgForm.elements.effect.value,'context');
  h.submit({c:'1'});await h.flush();
  const p=h.record().mks[0].promises[0];assert.equal(p.evidence.length,1);
  assert.deepEqual({type:p.evidence[0].type,effect:p.evidence[0].effect,url:p.evidence[0].url,title:p.evidence[0].title},{type:'ועדה',effect:'context',url:BASE+'KNS_Committee(1)',title:'ועדה: ועדת החינוך'});
  assert.equal(p.status,'partial');assert.equal(scoring.scorePromise(p).value,null);
  assert.doesNotThrow(()=>h.hooks.normalize(h.written(KEY)),'the saved state is valid for import and backup');
});

test('the committee cache survives a reload without any request and is ignored when malformed',async()=>{
  const urls=[];const first=app({route:committeeRoute(urls)});first.click('loadCmts');await first.settle();
  const second=app({storage:{[CMT_KEY]:first.storage.get(CMT_KEY)}});second.click('open',{id:'m1'});
  assert.match(second.html(),/ועדת החינוך/);assert.equal(second.state.fetches.length,0);
  const broken=app({storage:{[CMT_KEY]:'{"committees":"x"}'}});assert.equal(broken.hooks.cm(),null);
  const unrelated=app({storage:{[CMT_KEY]:fixture()}});assert.equal(unrelated.hooks.cm(),null);
});

test('committee window changes reload with the chosen dates and reject reversed ones',async()=>{
  const urls=[];const h=app({route:committeeRoute(urls)});h.click('openCmts');h.click('cmtWindow');
  assert.equal(h.nodes.dlgForm.elements.from.value,'2026-04-27');
  h.submit({from:'2026-09-01',to:'2026-08-01'});assert.equal(h.nodes.dlg.open,true);assert.match(h.nodes.toast.textContent,/מאוחר/);assert.equal(h.state.fetches.length,0);
  h.submit({from:'2026-05-01',to:'2026-06-30'});await h.settle();
  assert.match(urls.find(u=>u.pathname.endsWith('KNS_CommitteeSession')).searchParams.get('$filter'),/ge 2026-05-01T00:00:00Z and StartDate le 2026-06-30T23:59:59Z/);
  assert.deepEqual(h.written(CMT_KEY).win,{from:'2026-05-01',to:'2026-06-30'});
});

test('committee failures reach the persistent diagnostics card, keep saved data, and retry succeeds',async()=>{
  let ok=false;const urls=[];
  const h=app({route:(url,config)=>ok?committeeRoute(urls)(url):reply({error:{message:"Could not find a property named 'CommitteeID' on type 'KNS_Committee'."}},400)});
  h.click('loadCmts');await h.settle();
  assert.equal(h.failure().action,'committees');assert.equal(h.failure().status,400);assert.match(h.failure().detail,/Could not find a property/);
  assert.match(h.html(),/טעינת הוועדות נכשלה/);assert.match(h.html(),/id="apiFailure"/);
  assert.equal(h.storage.has(CMT_KEY),false);assert.deepEqual(h.state.writes,[]);assert.deepEqual(h.record(),fixture());
  ok=true;h.click('retryApi');await h.settle();
  assert.equal(h.failure(),null);assert.equal(h.written(CMT_KEY).sessions.length,3);
});

test('malformed committee rows are rejected instead of cached',async()=>{
  const h=app({route:url=>url.pathname.endsWith('KNS_Committee')?reply({value:[{Id:'x',Name:'לא תקין'}]}):reply({value:[]})});
  h.click('loadCmts');await h.settle();assert.equal(h.failure().action,'committees');assert.match(h.failure().message,/ועדה/);assert.equal(h.storage.has(CMT_KEY),false);
});

/* ---------- all bills: browser $skip loop ---------- */

function billsRoute(log,options={}){
  return (url,config,h)=>{
    const q=Object.fromEntries(url.searchParams.entries());log.push({skip:Number(q.$skip),q,href:url.href});
    assert.ok(url.pathname.endsWith('/KNS_Bill'));
    if(options.rejectOrderby&&q.$orderby)return reply({error:{message:"The query option '$orderby' is not supported."}},400);
    if(options.beforePage)options.beforePage(Number(q.$skip),h);
    if(options.failAt===Number(q.$skip)&&!options.failed){options.failed=true;return reply({error:{message:'Internal error'}},500)}
    const rows=allBills.filter(b=>!q.$filter||q.$filter==='KnessetNum eq '+b.KnessetNum);
    return reply({value:rows.slice(Number(q.$skip),Number(q.$skip)+Number(q.$top))});
  };
}
const startAll=(h,k='25')=>{h.click('openBills');h.click('loadAllBills');h.submit({k})};

test('the bills loop pages with $skip 0,100,200,... until an empty page using v4 field names',async()=>{
  const log=[];const h=app({route:billsRoute(log)});
  h.click('openBills');assert.match(h.html(),/עדיין לא נטענו הצעות חוק/);assert.equal(h.state.fetches.length,0,'opening the screen never fetches');
  h.click('loadAllBills');assert.equal(h.nodes.dlgForm.elements.k.value,'25');h.submit({k:'25'});await h.settle();
  assert.deepEqual(log.map(x=>x.skip),[0,100,200,300]);
  for(const x of log){assert.equal(x.q.$top,'100');assert.equal(x.q.$orderby,'Id');assert.equal(x.q.$filter,'KnessetNum eq 25');assert.equal(x.q.$select,'Id,KnessetNum,Name,SubTypeDesc');assert.ok(!x.href.includes(';'))}
  const b=h.hooks.bl();assert.equal(b.rows.length,250);assert.equal(b.done,true);assert.equal(b.nextSkip,300);assert.equal(b.knesset,25);
  assert.deepEqual(h.written(BKEY).rows.length,250);assert.deepEqual(h.record(),fixture());assert.ok(!h.state.writes.includes(KEY));
  assert.match(h.nodes.blItems.innerHTML,/250 תוצאות/);assert.match(h.nodes.blItems.innerHTML,/מוצגות 50 הראשונות/);
  h.type('bq','חוק 25');assert.match(h.nodes.blItems.innerHTML,/2 תוצאות/);
});

test('a bill is assigned to a promise as linked context evidence',async()=>{
  const h=app({route:billsRoute([])});startAll(h);await h.settle();
  h.click('billAssign',{b:'25'});assert.equal(h.nodes.dlgForm.elements.effect.value,'context');h.submit({p:'m1|p1'});await h.flush();
  const p=h.record().mks[0].promises[0];
  assert.deepEqual({title:p.evidence[0].title,type:p.evidence[0].type,effect:p.evidence[0].effect,url:p.evidence[0].url},{title:'הצעת חוק 25',type:'הצעת חוק',effect:'context',url:BASE+'KNS_Bill(25)'});
  assert.doesNotThrow(()=>h.hooks.normalize(h.written(KEY)));
});

test('stopping keeps the progress and resuming continues from the saved $skip without duplicates',async()=>{
  const log=[];const h=app({route:billsRoute(log,{beforePage:(skip,h)=>{if(skip===100)h.click('stopBills')}})});
  startAll(h);await h.settle();
  assert.equal(h.hooks.bl().rows.length,200);assert.equal(h.hooks.bl().nextSkip,200);assert.equal(h.hooks.bl().done,false);
  assert.match(h.nodes.toast.textContent,/נעצרה/);assert.match(h.html(),/נקודת המשך/);assert.match(h.html(),/המשך טעינה/);
  log.length=0;h.click('loadAllBills');await h.settle();
  assert.deepEqual(log.map(x=>x.skip),[200,300]);assert.equal(h.hooks.bl().rows.length,250);assert.equal(h.hooks.bl().done,true);
  assert.equal(new Set(h.hooks.bl().rows.map(r=>r.i)).size,250);
});

test('a failing page stops the loop with diagnostics, keeps loaded rows, and retry resumes at the failed page',async()=>{
  const log=[];const h=app({route:billsRoute(log,{failAt:200})});startAll(h);await h.settle();
  assert.equal(h.failure().action,'allbills');assert.equal(h.failure().status,500);assert.equal(h.failure().entity,'KNS_Bill');
  assert.match(h.html(),/טעינת כל הצעות החוק נכשלה/);assert.equal(h.hooks.bl().rows.length,200);assert.equal(h.hooks.bl().nextSkip,200);
  assert.equal(log.filter(x=>x.skip===200).length,1,'a server error is not retried in a tight loop');
  assert.equal(h.written(BKEY).rows.length,200,'the partial result is saved');
  h.click('retryApi');await h.settle();
  assert.equal(h.failure(),null);assert.equal(h.hooks.bl().rows.length,250);assert.deepEqual(log.slice(3).map(x=>x.skip),[200,300]);
});

test('a server that rejects $orderby is retried once without it from the first page only',async()=>{
  const log=[];const h=app({route:billsRoute(log,{rejectOrderby:true})});startAll(h);await h.settle();
  assert.equal(log[0].q.$orderby,'Id');assert.equal(log[1].q.$orderby,undefined);assert.equal(h.hooks.bl().rows.length,250);assert.equal(h.failure(),null);
});

test('an unparseable or geo-blocked page produces a diagnostic, not an endless loop',async()=>{
  const h=app({route:()=>reply('<html>The site is temporarily inaccessible from your location</html>')});startAll(h);await h.settle();
  assert.equal(h.failure().action,'allbills');assert.match(h.failure().detail,/JSON/);assert.equal(h.state.fetches.length,1,'an HTTP 200 with a non-JSON body is a block page, not an unsupported $orderby');
  assert.equal(h.hooks.bl().rows.length,0);
});

test('a bill without a valid Id is rejected rather than stored',async()=>{
  const h=app({route:()=>reply({value:[{Id:'abc',Name:'x'}]})});startAll(h);await h.settle();
  assert.equal(h.failure().action,'allbills');assert.equal(h.hooks.bl().rows.length,0);
});

test('stored bills are validated on load and the stored copy never enters the member backup',async()=>{
  const bad=app({storage:{[BKEY]:fixture()}});assert.equal(bad.hooks.bl(),null);
  const good=app({storage:{[BKEY]:{knesset:25,rows:[{i:7,k:25,n:'הצעה',t:'פרטית'}],nextSkip:100,done:false,loadedAt:''}}});
  assert.equal(good.hooks.bl().rows.length,1);
  good.click('menu');good.submit({act:'export'});await good.flush();
  assert.ok(!good.state.shares.at(-1).includes('הצעה'),'bills stay out of the exported backup');assert.deepEqual(JSON.parse(good.state.shares.at(-1)),fixture());
});

/* ---------- all bills: Android loop in Java ---------- */

function androidBills(){
  const calls={start:[],stop:0,wipe:0};const pages={};let meta='';let running=false;
  return {calls,pages,setMeta(value){meta=value},setRunning(value){running=value},api:{
    shareText(){},startBills(k,fresh){calls.start.push([k,fresh]);return !running},stopBills(){calls.stop++},billsMeta(){return meta},billsPage(skip){return pages[skip]||''},wipeBills(){calls.wipe++}}};
}
const page=(from,count)=>JSON.stringify({value:Array.from({length:count},(_,i)=>bill(from+i))});

test('on Android the page delegates the loop to Java and reads the saved pages through the bridge',async()=>{
  const n=androidBills();const h=app({android:n.api});
  startAll(h);await h.flush();assert.deepEqual(n.calls.start,[[25,true]]);assert.equal(h.state.fetches.length,0,'the page never fetches itself on Android');
  assert.match(h.html(),/עצירה/);
  n.pages[0]=page(1,100);h.window.__billsNative('progress',0,'');assert.equal(h.hooks.bl().rows.length,100);assert.equal(h.hooks.bl().nextSkip,100);
  n.pages[100]=page(101,50);h.window.__billsNative('progress',100,'');assert.equal(h.hooks.bl().rows.length,150);
  n.setMeta(JSON.stringify({knesset:25,nextSkip:200,done:true,loadedAt:'2026-10-28T10:00:00Z'}));h.window.__billsNative('done',200,'');
  assert.equal(h.hooks.bl().rows.length,150);assert.equal(h.hooks.bl().done,true);assert.equal(h.hooks.busy(),false);assert.match(h.nodes.toast.textContent,/150/);
  assert.equal(h.storage.has(BKEY),false,'Java keeps the pages; nothing large goes to localStorage');
  h.click('stopBills');assert.equal(n.calls.stop,1);
});

test('on Android a loop that is already running is not started twice and a Java error is shown with diagnostics',async()=>{
  const n=androidBills();n.setRunning(true);const h=app({android:n.api});
  startAll(h);await h.flush();assert.match(h.nodes.toast.textContent,/כבר רצה/);assert.equal(h.hooks.busy(),false);
  n.setRunning(false);h.click('loadAllBills');h.submit({k:'25'});await h.flush();
  n.setMeta(JSON.stringify({knesset:25,nextSkip:0,done:false,loadedAt:''}));h.window.__billsNative('error',0,'HTTP 503');
  assert.equal(h.failure().action,'allbills');assert.match(h.failure().message,/503/);assert.equal(h.hooks.busy(),false);assert.match(h.html(),/טעינת כל הצעות החוק נכשלה/);
});

test('on Android a saved run is reloaded from the bridge when the screen opens, not at startup',async()=>{
  const n=androidBills();n.pages[0]=page(1,100);n.pages[100]=page(101,20);
  n.setMeta(JSON.stringify({knesset:25,nextSkip:200,done:true,loadedAt:'2026-10-28T10:00:00Z'}));
  const reads=[];const api={...n.api,billsMeta(){reads.push('meta');return JSON.stringify({knesset:25,nextSkip:200,done:true,loadedAt:'2026-10-28T10:00:00Z'})}};
  const h=app({android:api});assert.equal(reads.length,0);h.click('openBills');assert.equal(h.hooks.bl().rows.length,120);assert.match(h.html(),/120 הצעות/);
});

/* ---------- all initiators: Android loop in Java ---------- */

function androidInitiators(){
  const calls={start:[],stop:0,wipe:0};const pages={};let meta='';let running=false;
  return {calls,pages,setMeta(value){meta=value},setRunning(value){running=value},api:{
    startInitiators(fresh){calls.start.push([fresh]);return !running},stopInitiators(){calls.stop++},initiatorsMeta(){return meta},initiatorsPage(skip){return pages[skip]||''},wipeInitiators(){calls.wipe++}}};
}
const initiatorRow=(id,billId,personId)=>({Id:id,BillID:billId,PersonID:personId,IsInitiator:true,Ordinal:1,LastUpdatedDate:'2026-01-01T00:00:00'});
const iniPage=(from,count)=>JSON.stringify({value:Array.from({length:count},(_,i)=>initiatorRow(from+i,from+1000+i,123))});

test('on Android the initiators loop delegates to Java and reads saved pages through the bridge',async()=>{
  const nb=androidBills();const ni=androidInitiators();
  const h=app({android:{...nb.api,...ni.api,shareText(t){}}});
  h.click('openBills');
  h.click('loadAllInitiators');await h.flush();
  assert.deepEqual(ni.calls.start,[[true]]);
  ni.pages[0]=iniPage(1,100);h.window.__initiatorsNative('progress',0,'');
  assert.equal(h.hooks.ini().rows.length,100);assert.equal(h.hooks.ini().nextSkip,100);
  ni.pages[100]=iniPage(101,50);h.window.__initiatorsNative('progress',100,'');
  assert.equal(h.hooks.ini().rows.length,150);
  ni.setMeta(JSON.stringify({nextSkip:200,done:true,loadedAt:'2026-10-28T10:00:00Z'}));h.window.__initiatorsNative('done',200,'');
  assert.equal(h.hooks.ini().rows.length,150);assert.equal(h.hooks.ini().done,true);
  assert.match(h.nodes.toast.textContent,/150/);
  assert.equal(h.storage.has(IKEY),false,'Java keeps the pages; nothing large goes to localStorage');
  h.click('stopInitiators');assert.equal(ni.calls.stop,1);
});

test('on Android an initiators error is shown with diagnostics',async()=>{
  const nb=androidBills();const ni=androidInitiators();
  ni.setRunning(true);
  const h=app({android:{...nb.api,...ni.api,shareText(t){}}});
  h.click('openBills');h.click('loadAllInitiators');await h.flush();
  assert.match(h.nodes.toast.textContent,/כבר רצה/);
  ni.setRunning(false);h.click('loadAllInitiators');await h.flush();
  ni.setMeta(JSON.stringify({nextSkip:0,done:false,loadedAt:''}));h.window.__initiatorsNative('error',0,'HTTP 503');
  assert.equal(h.failure().action,'initiators');assert.match(h.failure().message,/503/);
});

/* ---------- all laws: Android loop in Java ---------- */

function androidLaws(){
  const calls={start:[],stop:0,wipe:0};const pages={};let meta='';let running=false;
  return {calls,pages,setMeta(value){meta=value},setRunning(value){running=value},api:{
    startLaws(knesset,fresh){calls.start.push([knesset,fresh]);return !running},stopLaws(){calls.stop++},lawsMeta(){return meta},lawsPage(skip){return pages[skip]||''},wipeLaws(){calls.wipe++}}};
}
const lawRow=(id,knesset,name,basic,budget)=>({Id:id,KnessetNum:knesset,Name:name||'חוק '+id,IsBasicLaw:!!basic,IsBudgetLaw:!!budget,PublicationDate:'2024-01-01T00:00:00',LawValidityDesc:'תקף'});
const lwPage=(from,count)=>JSON.stringify({value:Array.from({length:count},(_,i)=>lawRow(from+i,25))});

test('on Android the laws loop delegates to Java and reads saved pages through the bridge',async()=>{
  const nb=androidBills();const nl=androidLaws();
  const h=app({android:{...nb.api,...nl.api,shareText(t){}}});
  h.click('openLaws');
  h.click('loadAllLaws');await h.flush();h.submit({k:'25'});await h.flush();
  assert.deepEqual(nl.calls.start,[[25,true]]);
  nl.pages[0]=lwPage(1,100);h.window.__lawsNative('progress',0,'');
  assert.equal(h.hooks.lw().rows.length,100);assert.equal(h.hooks.lw().nextSkip,100);
  nl.pages[100]=lwPage(101,50);h.window.__lawsNative('progress',100,'');
  assert.equal(h.hooks.lw().rows.length,150);
  nl.setMeta(JSON.stringify({knesset:25,nextSkip:200,done:true,loadedAt:'2026-10-28T10:00:00Z'}));h.window.__lawsNative('done',200,'');
  assert.equal(h.hooks.lw().rows.length,150);assert.equal(h.hooks.lw().done,true);
  assert.match(h.nodes.toast.textContent,/150/);
  assert.equal(h.storage.has(LKEY),false,'Java keeps the pages; nothing large goes to localStorage');
  h.click('stopLaws');assert.equal(nl.calls.stop,1);
});

test('on Android a laws error is shown with diagnostics',async()=>{
  const nb=androidBills();const nl=androidLaws();
  nl.setRunning(true);
  const h=app({android:{...nb.api,...nl.api,shareText(t){}}});
  h.click('openLaws');h.click('loadAllLaws');await h.flush();h.submit({k:'25'});await h.flush();
  assert.match(h.nodes.toast.textContent,/כבר רצה/);
  nl.setRunning(false);h.click('loadAllLaws');await h.flush();h.submit({k:'25'});await h.flush();
  nl.setMeta(JSON.stringify({knesset:25,nextSkip:0,done:false,loadedAt:''}));h.window.__lawsNative('error',0,'HTTP 503');
  assert.equal(h.failure().action,'laws');assert.match(h.failure().message,/503/);
});

/* ---------- wipe and navigation ---------- */

test('wiping all data also clears the local bills and committee caches, including Java storage',async()=>{
  const nb=androidBills();const ni=androidInitiators();const nl=androidLaws();
  const h=app({android:{...nb.api,...ni.api,...nl.api,shareText(t){}},storage:{[CMT_KEY]:{win:{from:'2026-04-27',to:'2026-10-27'},committees:[],sessions:[],roles:[]},[BKEY]:{knesset:25,rows:[],nextSkip:0,done:false,loadedAt:''}}});
  h.click('menu');h.submit({act:'wipe'});await h.flush();h.submit({});await h.flush();
  assert.deepEqual(h.record(),{v:1,mks:[]});assert.equal(h.storage.has(CMT_KEY),false);assert.equal(h.storage.has(BKEY),false);assert.equal(h.storage.has(IKEY),false);assert.equal(h.storage.has(LKEY),false);assert.equal(nb.calls.wipe,1);assert.equal(ni.calls.wipe,1);assert.equal(nl.calls.wipe,1);
  assert.equal(h.hooks.cm(),null);assert.equal(h.hooks.bl(),null);assert.equal(h.hooks.ini(),null);assert.equal(h.hooks.lw(),null);
});

test('the back button leaves the committee, committees, bills and laws screens one level at a time',()=>{
  const h=app({route:committeeRoute([])});
  h.click('openBills');assert.equal(h.hooks.view().name,'bills');h.window.__back();assert.equal(h.hooks.view().name,'list');
  assert.equal(h.nodes.backBtn.hidden,true);
  h.click('openLaws');assert.equal(h.hooks.view().name,'laws');h.window.__back();assert.equal(h.hooks.view().name,'list');
  h.click('openCmts');assert.equal(h.nodes.backBtn.hidden,false);h.nodes.backBtn.listeners.click();assert.equal(h.hooks.view().name,'list');
});
