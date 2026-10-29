'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const html=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8');
const a=html.indexOf('/* ---------- articles from Makor Rishon'),b=html.indexOf('/* ---------- views');
const ctx={URL,Promise,String,uid:(()=>{let n=0;return()=>'id'+(++n)})(),EFFECT:{},NETWORK_TIMEOUT:50,nid:0,pending:{},window:{}};
vm.createContext(ctx);vm.runInContext(html.slice(a,b),ctx);
const clone=x=>JSON.parse(JSON.stringify(x));

test('parseArticles keeps only same-site article links, as title and url',()=>{
  const page='<a href="/politics/post-1/?utm=1#c">כותרת &quot;ארוכה&quot; מספיק <b>לבדיקה</b></a>'
   +'<a href="/politics/post-1/">כותרת ארוכה מספיק לבדיקה כפולה</a>'
   +'<a href="/tag/x/">תגית ארוכה מספיק לבדיקה כאן</a>'
   +'<a href="https://evil.example/a/b">כותרת ארוכה מספיק של אתר זר</a>'
   +'<a href="javascript:alert(1)">כותרת ארוכה מספיק של סקריפט</a>'
   +'<a href="/politics/p2">קצר</a>';
  const r=clone(ctx.parseArticles(page));
  assert.deepEqual(r,[{title:'כותרת "ארוכה" מספיק לבדיקה',url:'https://www.makorrishon.co.il/politics/post-1/'}]);
});

test('mrAttach adds evidence or background once and never changes status',()=>{
  const m={promises:[{id:'p',status:'unchecked',evidence:[]}],background:[]};
  const items=[{title:'כתבה',url:'https://www.makorrishon.co.il/a/b'}];
  assert.equal(ctx.mrAttach(m,'p','support',items),1);
  assert.equal(ctx.mrAttach(m,'p','support',items),0);
  assert.equal(m.promises[0].status,'unchecked');
  assert.equal(m.promises[0].evidence[0].type,'כתבה');
  assert.equal(ctx.mrAttach(m,'bg','context',items),1);
  assert.equal(m.background[0].url,items[0].url);
});

test('getPage rejects outside the Android app',async()=>{
  await assert.rejects(ctx.getPage('https://www.makorrishon.co.il/?s=x'),/native-only/);
});

test('mrChoose and suggestStance exist and the page script parses',()=>{
  const scripts=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]);
  scripts.forEach(code=>new vm.Script(code));
});

test('labelRows exports labelled evidence of the 25th Knesset only',()=>{
  const c={};vm.createContext(c);
  const a=html.indexOf('function labelRows'),b=html.indexOf('function shareOut');
  vm.runInContext(html.slice(a,b),c);
  const st={mks:[
    {name:'א',knesset:'25',promises:[{text:'הבטחה',evidence:[{title:'כתבה',effect:'support',url:'https://x'},{title:'',effect:'context'},{title:'t',effect:'bogus'}]}]},
    {name:'ב',knesset:'24',promises:[{text:'הבטחה',evidence:[{title:'כתבה',effect:'contradict'}]}]}]};
  assert.deepEqual(clone(c.labelRows(st,25)),[{promise:'הבטחה',utterance:'כתבה',label:'support',group:'א',source:'https://x'}]);
});
