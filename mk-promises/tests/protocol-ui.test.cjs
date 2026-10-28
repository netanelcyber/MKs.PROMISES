'use strict';

const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const scoring=require('../web/promise-scoring.js');
const source=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
const KEY='mk-promises-v1',SETTINGS_KEY='mk-promises-protocol-settings-v1';
const PAGE='https://main.knesset.gov.il/Activity/committees/Pages/AllCommitteesAgenda.aspx?Tab=3&ItemID=123';
const DOC='https://fs.knesset.gov.il/25/Committees/25_ptv_example.pdf';
const QUOTE='יש להגדיל את תקציב החינוך.';
const clone=value=>JSON.parse(JSON.stringify(value));
const fixture=()=>({v:1,mks:[{id:'m1',name:'חבר כנסת לבדיקה',faction:'סיעה',knesset:'25',isNew:false,apiPersonId:123,
  promises:[{id:'p1',text:'נגדיל את תקציב החינוך',topic:'חינוך',sourceUrl:'https://example.test/promise',sourceDate:'',status:'partial',note:'',evidence:[]}],background:[]}]});
const protocol=(url=DOC)=>({sourceUrl:url,finalUrl:url,title:'פרוטוקול ועדת החינוך',committee:'ועדת החינוך',date:'2026-10-28',
  text:'פרוטוקול מס׳ 14\nועדת החינוך\nחברי הוועדה:\nדיון בתקציב\nדובר: '+QUOTE+'\nהישיבה ננעלה.',kind:'pdf',isProtocol:true,truncated:false,
  retrievedAt:'2026-10-28T10:00:00+00:00',documents:[],warnings:[]});
const response=(value,status=200)=>({ok:status>=200&&status<300,status,headers:{get(){return null}},text(){return Promise.resolve(JSON.stringify(value))}});
const decode=text=>String(text).replace(/&(amp|lt|gt|quot|#39);/g,(_,x)=>({amp:'&',lt:'<',gt:'>',quot:'"','#39':"'"})[x]);
const attrs=text=>Object.fromEntries([...text.matchAll(/([\w-]+)="([^"]*)"/g)].map(x=>[x[1],decode(x[2])]));

// Execute the full inline application with queued dialog-close events. All HTTP
// and Android interactions are mocks; no Selenium server or public API is used.
function app(initial=fixture(),options={}){
  const storage=new Map([[KEY,JSON.stringify(initial)]]);
  if(options.endpoint)storage.set(SETTINGS_KEY,JSON.stringify({endpoint:options.endpoint}));
  const state={requests:[],nativeCalls:[],shares:[],writes:[],tasks:[],timers:new Map(),logs:[]};let timerId=0;
  const listeners={};
  class Element{
    constructor(id){this.id=id;this.style={};this.hidden=false;this.textContent='';this._html='';this.listeners={}}
    get innerHTML(){return this._html}set innerHTML(value){this._html=value}
    addEventListener(name,fn){this.listeners[name]=fn}focus(){state.focus=this}scrollIntoView(){}
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
        this.elements[a.name]={name:a.name,value:fieldValue,checked:/\bchecked\b/.test(match[2]),focus(){state.focus=this}};
      }
    }
  }
  const nodes=Object.fromEntries(['app','items','title','backBtn','toast','apiFailure','protocolPreview'].map(id=>[id,new Element(id)]));
  nodes.dlgForm=new Form('dlgForm');nodes.dlg=Object.assign(new Element('dlg'),{open:false,onclose:null,
    showModal(){assert.equal(this.open,false);this.open=true},close(){if(!this.open)return;this.open=false;state.tasks.push(()=>{if(this.onclose)this.onclose()})}});
  const document={activeElement:null,getElementById(id){
    if(id==='items'&&!nodes.app.innerHTML.includes('id="items"'))return null;
    if(id==='protocolPreview'&&!nodes.dlgForm.innerHTML.includes('id="protocolPreview"'))return null;
    if(id==='apiFailure'&&!nodes.app.innerHTML.includes('id="apiFailure"'))return null;
    return nodes[id]||null;
  },addEventListener(name,fn){listeners[name]=fn}};
  const window={scrollTo(){},Android:{shareText(text){state.shares.push(text)}},MKPromiseScoring:scoring};
  if(options.native)window.Android.httpReadProtocol=(...args)=>state.nativeCalls.push(args);
  const context={document,window,navigator:{},location:{protocol:'file:'},URL,Blob,TextEncoder,TextDecoder,Uint8Array,AbortController,atob,btoa,
    localStorage:{getItem(key){return storage.get(key)??null},setItem(key,value){storage.set(key,value);state.writes.push([key,value])}},
    setTimeout(fn,ms){const id=++timerId;state.timers.set(id,{fn,ms});return id},clearTimeout(id){state.timers.delete(id)},
    fetch(url,config){state.requests.push({url,config});return state.fetchImpl?state.fetchImpl(url,config):Promise.resolve(response(protocol(JSON.parse(config.body).url)))},
    console:{log(...args){state.logs.push(args)},warn(...args){state.logs.push(args)},error(...args){state.logs.push(args)}}
  };
  const hook='window.__protocolTest={state:function(){return S},replaceState:function(value){S=normalizeState(value);resetView();render()},inspect:function(){return {settings:protocolSettings,session:protocolSession?{loading:protocolSession.loading,error:protocolSession.error,result:protocolSession.result}:null,nativePending:Object.keys(protocolNativePending).length}},official:officialProtocolUrl,service:protocolServiceUrl,normalize:normalizeState,validate:validateProtocolResult,request:requestProtocol,reset:resetView};';
  const instrumented=source.replace(/\}\)\(\);\s*$/,hook+'\n})();');assert.notEqual(instrumented,source);vm.runInNewContext(instrumented,context,{filename:'index.html'});
  const h={state,storage,nodes,window,hooks:window.__protocolTest,record(){return clone(window.__protocolTest.state())},inspect(){return clone(window.__protocolTest.inspect())},
    expire(ms){const entry=[...state.timers.entries()].find(([,t])=>t.ms===ms);assert.ok(entry,'timeout exists');state.timers.delete(entry[0]);entry[1].fn()}};
  h.flush=async()=>{for(let i=0;i<30;i++){await Promise.resolve();await Promise.resolve();await Promise.resolve();if(state.tasks.length)state.tasks.shift()()}};
  h.click=(action,data={})=>{const target={tagName:'BUTTON',dataset:{a:action,...data}};target.closest=()=>target;listeners.click({target})};
  h.submit=(values={},button='ok')=>{assert.equal(nodes.dlg.open,true);for(const [key,value] of Object.entries(values)){assert.ok(nodes.dlgForm.elements[key],key);if(typeof value==='boolean')nodes.dlgForm.elements[key].checked=value;else nodes.dlgForm.elements[key].value=value}nodes.dlgForm.onsubmit({submitter:{tagName:'BUTTON',value:button},preventDefault(){}})};
  h.configure=async(endpoint,token='')=>{h.click('protocolSettings');h.submit({endpoint,token});await h.flush()};
  h.open=()=>h.click('open',{id:'m1'});
  h.read=async(url=DOC)=>{h.open();h.click('readProtocol',{id:'m1'});h.submit({p:'p1',url});await h.flush()};
  h.export=async()=>{h.click('menu');h.submit({act:'export'});await h.flush();return state.shares.at(-1)};
  return h;
}

test('scoring is visible with coverage and reader settings never trigger automatic requests',async()=>{
  const h=app();assert.match(h.nodes.items.innerHTML,/אין עדיין ציון מבוסס ראיות/);assert.match(h.nodes.items.innerHTML,/כיסוי 0\/1/);h.open();assert.match(h.nodes.app.innerHTML,/קריאת פרוטוקול/);
  await h.configure('https://reader.example','session-secret');assert.equal(h.state.requests.length,0);assert.deepEqual(JSON.parse(h.storage.get(SETTINGS_KEY)),{endpoint:'https://reader.example'});
  assert.ok(![...h.storage.values()].some(value=>value.includes('session-secret')));assert.deepEqual(h.record(),fixture());assert.equal(h.state.logs.length,0);
});

test('source URL validation accepts only official HTTPS domains, default port and bounded canonical URLs',()=>{
  const h=app();for(const url of [PAGE,DOC,'https://knesset.gov.il:443/test#section'])assert.ok(h.hooks.official(url));
  for(const url of ['http://main.knesset.gov.il/test','https://knesset.gov.il.evil.test/a','https://evil.test/knesset.gov.il','https://user:pass@knesset.gov.il/a','https://knesset.gov.il:8443/a','file:///tmp/document','https://127.0.0.1/a','https://fs.knesset.gov.il/'+'א'.repeat(1000)])assert.throws(()=>h.hooks.official(url));
  for(const endpoint of ['https://reader.example/base/','http://localhost:8787','http://127.0.0.1:8787','http://[::1]:8787'])assert.ok(h.hooks.service(endpoint));
  for(const endpoint of ['http://remote.example','https://user:pass@reader.example','https://reader.example?token=secret','https://reader.example/#key','/api/protocols'])assert.throws(()=>h.hooks.service(endpoint));
});

test('service token follows the server visible-ASCII 512-character limit',async()=>{
  for(const token of ['א','x'.repeat(513),'has space']){const h=app();await h.configure('https://reader.example',token);assert.equal(h.nodes.dlg.open,true);assert.equal(h.storage.has(SETTINGS_KEY),false);assert.match(h.nodes.toast.textContent,/512/)}
  const h=app();await h.configure('https://reader.example','x'.repeat(512));assert.equal(h.nodes.dlg.open,false);
});

test('missing service configuration opens settings and then a source form without inventing or calling a server',async()=>{
  const h=app();h.open();h.click('readProtocol',{id:'m1'});assert.ok(h.nodes.dlgForm.elements.endpoint);assert.equal(h.nodes.dlgForm.elements.endpoint.value,'');assert.equal(h.state.requests.length,0);
  h.submit({endpoint:'https://reader.example',token:''});await h.flush();assert.ok(h.nodes.dlgForm.elements.url);assert.equal(h.state.requests.length,0);assert.deepEqual(h.record(),fixture());
});

test('explicit protocol reading sends only the source URL and waits for review before saving evidence',async()=>{
  const h=app();await h.configure('https://reader.example/base','session-secret');await h.read();assert.equal(h.state.requests.length,1);
  const request=h.state.requests[0];assert.equal(request.url,'https://reader.example/base/api/protocols');assert.deepEqual(JSON.parse(request.config.body),{url:DOC});assert.equal(request.config.headers.Authorization,'Bearer session-secret');assert.equal(request.config.credentials,'omit');assert.equal(request.config.redirect,'error');assert.equal(request.config.referrerPolicy,'no-referrer');
  assert.deepEqual(h.record(),fixture());assert.equal(h.nodes.dlgForm.elements.effect.value,'context');assert.equal(h.nodes.dlgForm.elements.reviewed.checked,false);assert.equal(h.nodes.protocolPreview.textContent,protocol().text);
  h.submit({excerpt:QUOTE,reviewed:true});await h.flush();const p=h.record().mks[0].promises[0],e=p.evidence[0];
  assert.equal(e.type,'ועדה');assert.equal(e.effect,'context');assert.equal(e.url,DOC);assert.equal(e.excerpt,QUOTE);assert.equal(e.protocol.sourceUrl,DOC);assert.equal(e.protocol.kind,'pdf');assert.equal(p.status,'partial');assert.equal(scoring.scorePromise(p).value,null);
  const exported=await h.export();assert.ok(!exported.includes('session-secret'));assert.ok(!exported.includes('reader.example'));assert.deepEqual(clone(h.hooks.normalize(JSON.parse(exported))),h.record());
});

test('a discussion page cannot be saved as protocol evidence and document reading requires an explicit second action',async()=>{
  const h=app(fixture(),{endpoint:'https://reader.example'});h.state.fetchImpl=(url,config)=>{
    const requested=JSON.parse(config.body).url;
    return Promise.resolve(response(requested===PAGE?{...protocol(PAGE),kind:'page',isProtocol:false,text:'דף דיון וקישורי מסמכים',documents:[{url:DOC,title:'פרוטוקול הדיון',format:'pdf'}]}:protocol(requested)));
  };
  await h.read(PAGE);assert.equal(h.state.requests.length,1);assert.ok(h.nodes.dlgForm.elements.doc);assert.equal(h.nodes.dlgForm.elements.excerpt,undefined);assert.deepEqual(h.record(),fixture());
  h.submit({},'cancel');await h.flush();assert.equal(h.state.requests.length,1);h.click('reviewProtocol');h.submit({doc:'0'});await h.flush();assert.equal(h.state.requests.length,2);assert.deepEqual(JSON.parse(h.state.requests[1].config.body),{url:DOC});assert.ok(h.nodes.dlgForm.elements.excerpt);assert.deepEqual(h.record(),fixture());
});

test('ambiguous/background documents remain read-only even when their filename is a PDF',async()=>{
  const h=app(fixture(),{endpoint:'https://reader.example'});h.state.fetchImpl=()=>Promise.resolve(response({...protocol(),isProtocol:false,text:'מסמך רקע כללי',documents:[]}));await h.read();
  assert.equal(h.nodes.dlgForm.elements.excerpt,undefined);assert.match(h.nodes.dlgForm.innerHTML,/לא זוהה כפרוטוקול/);h.submit();await h.flush();assert.deepEqual(h.record(),fixture());assert.equal(scoring.scorePromise(h.record().mks[0].promises[0]).value,null);
});

test('quote acceptance requires a verbatim source substring and explicit human review',async()=>{
  const h=app(fixture(),{endpoint:'https://reader.example'});await h.read();h.submit({excerpt:QUOTE});assert.equal(h.nodes.dlg.open,true);assert.deepEqual(h.record(),fixture());
  h.submit({excerpt:'משפט שלא הופיע במקור',reviewed:true});assert.equal(h.nodes.dlg.open,true);assert.match(h.nodes.toast.textContent,/קטע מדויק/);assert.deepEqual(h.record(),fixture());
  h.submit({excerpt:QUOTE,reviewed:true});await h.flush();assert.equal(h.record().mks[0].promises[0].evidence.length,1);assert.equal(h.record().mks[0].promises[0].status,'partial');
});

test('preview text uses textContent, source markup is never executed, and preview truncation is disclosed',async()=>{
  const injection='<img src=x onerror=alert(1)>',text=injection+'א'.repeat(13000);
  const h=app(fixture(),{endpoint:'https://reader.example'});h.state.fetchImpl=()=>Promise.resolve(response({...protocol(),title:'<script>title</script>',text,truncated:true,warnings:['<img src=x>']}));await h.read();
  assert.equal(Array.from(h.nodes.protocolPreview.textContent).length,12000);assert.equal(h.nodes.protocolPreview.textContent,text.slice(0,12000));assert.ok(!h.nodes.dlgForm.innerHTML.includes('<img'));assert.ok(!h.nodes.dlgForm.innerHTML.includes('<script>'));assert.match(h.nodes.dlgForm.innerHTML,/12,000/);assert.match(h.nodes.dlgForm.innerHTML,/טקסט חלקי/);
  h.submit({excerpt:injection,reviewed:true});await h.flush();assert.ok(!h.nodes.app.innerHTML.includes('<img'));assert.match(h.nodes.app.innerHTML,/&lt;img/);assert.equal(h.record().mks[0].promises[0].evidence[0].excerpt,injection);
});

test('protocol response and imported evidence metadata reject unsafe URLs and malformed or oversized fields',async()=>{
  const h=app(fixture(),{endpoint:'https://reader.example'});for(const change of [
    {sourceUrl:PAGE},{finalUrl:'https://evil.test/protocol.pdf'},{kind:'page',isProtocol:true},{isProtocol:'true'},{truncated:'false'},
    {text:'א'.repeat(100001)},{title:'x'.repeat(301)},{committee:{}},{date:'x'.repeat(81)},{retrievedAt:'not a date'},
    {documents:Array(51).fill({url:DOC,title:'doc',format:'pdf'})},{documents:[{url:'http://fs.knesset.gov.il/a.pdf',title:'doc',format:'pdf'}]},
    {documents:[{url:DOC,title:'doc',format:'zip'}]},{warnings:Array(9).fill('warning')}
  ])assert.throws(()=>h.hooks.validate({...protocol(),...change},DOC));
  await h.read();h.submit({excerpt:QUOTE,reviewed:true});await h.flush();const saved=h.record();assert.deepEqual(clone(h.hooks.normalize(saved)),saved);
  for(const change of [{sourceUrl:PAGE},{sourceUrl:'https://evil.test/a.pdf'},{kind:'page'},{retrievedAt:'unknown'},{committee:42}]){
    const invalid=clone(saved);Object.assign(invalid.mks[0].promises[0].evidence[0].protocol,change);assert.throws(()=>h.hooks.normalize(invalid));
  }
  const longQuote=clone(saved);longQuote.mks[0].promises[0].evidence[0].excerpt='x'.repeat(4001);assert.throws(()=>h.hooks.normalize(longQuote));assert.deepEqual(clone(h.hooks.normalize(fixture())),fixture());
});

test('server errors such as OCR requirements remain readable and do not create evidence',async()=>{
  const h=app(fixture(),{endpoint:'https://reader.example'});h.state.fetchImpl=()=>Promise.resolve(response({error:{code:'unreadable',message:'נדרש OCR כדי לקרוא את המסמך הסרוק'}},422));await h.read();
  assert.match(h.nodes.app.innerHTML,/OCR/);assert.equal(h.nodes.dlg.open,false);assert.deepEqual(h.record(),fixture());assert.equal(h.state.logs.length,0);
});

test('browser requests enforce the response-size limit and abort after 90 seconds',async()=>{
  const oversized=app(fixture(),{endpoint:'https://reader.example'});oversized.state.fetchImpl=()=>Promise.resolve(response({...protocol(),extra:'x'.repeat(524288)}));await assert.rejects(oversized.hooks.request(DOC),/גדולה מדי/);
  const h=app(fixture(),{endpoint:'https://reader.example'});let deliver;h.state.fetchImpl=()=>new Promise(resolve=>{deliver=resolve});const pending=h.hooks.request(DOC),rejected=assert.rejects(pending,/זמן ההמתנה/);h.expire(90000);await rejected;assert.equal(h.state.requests[0].config.signal.aborted,true);deliver(response(protocol()));await h.flush();assert.deepEqual(h.record(),fixture());
});

test('native bridge is HTTPS-only, sends URL-only JSON and cleans success/error/timeout callbacks',async()=>{
  const h=app(fixture(),{endpoint:'https://reader.example',native:true});await h.configure('https://reader.example','session-secret');let pending=h.hooks.request(DOC);let [id,url,token,body]=h.state.nativeCalls[0];
  assert.equal(url,'https://reader.example/api/protocols');assert.equal(token,'session-secret');assert.deepEqual(JSON.parse(body),{url:DOC});assert.equal(h.state.requests.length,0);
  h.window.__protocolNativeDone(id,true,200,Buffer.from(JSON.stringify(protocol())).toString('base64'));assert.equal((await pending).isProtocol,true);assert.equal(h.inspect().nativePending,0);
  pending=h.hooks.request(DOC);const rejected=assert.rejects(pending,/OCR/);id=h.state.nativeCalls[1][0];h.window.__protocolNativeDone(id,false,422,Buffer.from(JSON.stringify({error:{message:'נדרש OCR'}})).toString('base64'));await rejected;assert.equal(h.inspect().nativePending,0);
  pending=h.hooks.request(DOC);const timeout=assert.rejects(pending,/זמן ההמתנה/);id=h.state.nativeCalls[2][0];h.expire(90000);await timeout;assert.equal(h.inspect().nativePending,0);h.window.__protocolNativeDone(id,true,200,Buffer.from(JSON.stringify(protocol())).toString('base64'));assert.equal(h.inspect().nativePending,0);
  const http=app(fixture(),{endpoint:'http://localhost:8787',native:true});await assert.rejects(http.hooks.request(DOC),/APK.*HTTPS/);assert.equal(http.state.nativeCalls.length,0);
});

test('responses are discarded when the selected promise is edited or the dataset is replaced',async()=>{
  for(const replace of [false,true]){
    const h=app(fixture(),{endpoint:'https://reader.example'});let deliver;h.state.fetchImpl=()=>new Promise(resolve=>{deliver=resolve});await h.read();assert.equal(h.inspect().session.loading,true);
    if(replace)h.hooks.replaceState({v:1,mks:[]});else{h.click('editPromise',{id:'m1',p:'p1'});h.submit({text:'הבטחה מעודכנת'});await h.flush()}
    deliver(response(protocol()));await h.flush();assert.equal(h.inspect().session,null);assert.equal(h.nodes.dlg.open,false);
    if(replace)assert.deepEqual(h.record(),{v:1,mks:[]});else{assert.equal(h.record().mks[0].promises[0].text,'הבטחה מעודכנת');assert.equal(h.record().mks[0].promises[0].evidence.length,0)}
  }
});

test('cancelled review and an edited promise during review cannot save a quote or alter fulfillment',async()=>{
  const h=app(fixture(),{endpoint:'https://reader.example'});await h.read();h.submit({},'cancel');await h.flush();assert.deepEqual(h.record(),fixture());h.click('reviewProtocol');assert.equal(h.state.requests.length,1);
  h.hooks.state().mks[0].promises[0].text='נוסח שהשתנה לאחר הקריאה';h.submit({excerpt:QUOTE,reviewed:true});await h.flush();assert.equal(h.record().mks[0].promises[0].evidence.length,0);assert.equal(h.record().mks[0].promises[0].status,'partial');assert.equal(scoring.scorePromise(h.record().mks[0].promises[0]).value,null);
});
