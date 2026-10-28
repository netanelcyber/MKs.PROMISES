const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../web/sw.js'),'utf8');

function worker(options={}){
  const handlers={},calls={added:[],deleted:[],put:[],claimed:0,skipped:0};
  const cache={addAll:async files=>calls.added.push(...files),put:async(...args)=>calls.put.push(args)};
  const self={addEventListener:(name,fn)=>handlers[name]=fn,skipWaiting:async()=>calls.skipped++,clients:{claim:async()=>calls.claimed++}};
  const context=vm.createContext({self,URL,Response,location:{origin:'https://example.org'},fetch:options.fetch||(async()=>new Response('ok')),caches:{
    open:async()=>cache,keys:async()=>options.keys||[],delete:async key=>{calls.deleted.push(key);return true},match:async key=>options.match&&options.match(key)
  }});
  vm.runInContext(source,context);
  async function fire(name,request){
    const waits=[];let response;
    handlers[name]({request,waitUntil:p=>waits.push(p),respondWith:p=>response=p});
    const result=response&&await response;
    await Promise.all(waits);
    return result;
  }
  return{calls,fire};
}

test('installation precaches the app and fulfillment scorer before activation',async()=>{
  const w=worker();await w.fire('install');
  for(const file of ['./index.html','./promise-scoring.js'])assert.ok(w.calls.added.includes(file));
  assert.equal(w.calls.skipped,1);
  for(const script of ['promise-scoring.js'])assert.ok(fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8').includes('src="'+script+'"'));
});
test('activation deletes only obsolete caches owned by this app',async()=>{
  const w=worker({keys:['mk-promises-v1','mk-promises-v3-protocols','unrelated-app-v1']});await w.fire('activate');
  assert.deepEqual(w.calls.deleted,['mk-promises-v1']);assert.equal(w.calls.claimed,1);
});
test('failed server responses cannot replace working offline assets',async()=>{
  const w=worker({fetch:async()=>new Response('error',{status:500})});
  const response=await w.fire('fetch',{url:'https://example.org/promise-scoring.js',method:'GET',mode:'cors'});
  assert.equal(response.status,500);assert.equal(w.calls.put.length,0);
});
test('offline navigation falls back to the cached app and missing assets fail honestly',async()=>{
  const w=worker({fetch:async()=>{throw Error('offline')},match:key=>key==='./index.html'?new Response('app shell'):undefined});
  const response=await w.fire('fetch',{url:'https://example.org/',method:'GET',mode:'navigate'});
  assert.equal(await response.text(),'app shell');
  const missing=await w.fire('fetch',{url:'https://example.org/missing.js',method:'GET',mode:'cors'});
  assert.equal(missing.type,'error');
});
test('Protocol POST bodies and cross-origin requests never enter the app cache',async()=>{
  let requests=0;const w=worker({fetch:async()=>{requests++;return new Response('ok')}});
  assert.equal(await w.fire('fetch',{url:'https://example.org/api/protocols',method:'POST'}),undefined);
  assert.equal(await w.fire('fetch',{url:'https://other.example.org/data',method:'GET'}),undefined);
  assert.equal(requests,0);assert.equal(w.calls.put.length,0);
});
