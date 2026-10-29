'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const html=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8');
const src=html.slice(html.indexOf('/* ---------- stance model'),html.indexOf('/* ---------- views'));
const ctx={TextEncoder,atob,btoa,Math,Float32Array,Int32Array,Uint8Array,Uint16Array,Object,Array,String,Error,Set,Number,Promise,setTimeout,toast(){},window:{}};
vm.createContext(ctx);vm.runInContext(src,ctx);

test('analytic gradients match finite differences',()=>{
  const m=ctx.lightInit({arch:'light',buckets:64,dim:6,hidden:5},3);
  const row={promise:'להגדיל את תקציב הביטחון',utterance:'אני תומך בהגדלת התקציב של הביטחון'};
  const y=1,w=1.7;
  const loss=()=>{const p=ctx.stanceScore(m,row.promise,row.utterance);return -Math.log(p[y])*w};
  const g=ctx.newGrads(m.W);
  ctx.lightBackward(m,ctx.lightEmbed(m,row.promise,row.utterance),y,w,g);
  // use float64 copies so finite differences are not swamped by float32 rounding
  Object.keys(m.W).forEach(k=>{m.W[k]=Float64Array.from(m.W[k])});
  const probe=(name,idx,analytic)=>{
    const eps=1e-3,old=m.W[name][idx];
    m.W[name][idx]=old+eps;const a=loss();m.W[name][idx]=old-eps;const b=loss();m.W[name][idx]=old;
    const num=(a-b)/(2*eps);
    assert.ok(Math.abs(num-analytic)<5e-3*Math.max(1,Math.abs(num)),`${name}[${idx}] numeric ${num} analytic ${analytic}`);
  };
  for(const name of ['h2.weight','h2.bias','h1.weight','h1.bias','att.w','att.b','seg']){
    for(const idx of [0,1,3].filter(i=>i<g[name].length))probe(name,idx,g[name][idx]);
  }
  const ids=Object.keys(g.emb).slice(0,4);
  assert.ok(ids.length>0);
  for(const id of ids)for(const d of [0,2])probe('emb',Number(id)*6+d,g.emb[id][d]);
});

test('training learns a separable toy task, held-out groups, and survives a JSON round trip',async()=>{
  const rows=[];
  const sup=['אני תומך בהגדלת התקציב','נצביע בעד ההצעה','אפעל לקידום החוק'],con=['אני מתנגד להגדלת התקציב','נצביע נגד ההצעה','אעצור את החוק'],cx=['נעבור לסעיף הבא','תודה רבה לכולם','הישיבה נסגרה'];
  for(let i=0;i<10;i++){
    sup.forEach(u=>rows.push({promise:'להגדיל תקציב',utterance:u,label:'support',group:'g'+i}));
    con.forEach(u=>rows.push({promise:'להגדיל תקציב',utterance:u,label:'contradict',group:'g'+i}));
    cx.forEach(u=>rows.push({promise:'להגדיל תקציב',utterance:u,label:'context',group:'g'+i}));
  }
  const t=ctx.trainLight(rows,{seed:2,epochs:25});
  const groupsT=new Set(t.train.map(r=>r.group)),groupsV=new Set(t.val.map(r=>r.group));
  assert.ok(t.val.length>0);for(const g of groupsV)assert.ok(!groupsT.has(g),'a group is on both sides of the split');
  const r=await t.run();
  assert.ok(r.metrics.accuracy>0.9,'val accuracy '+r.metrics.accuracy);
  const json=ctx.stanceToJson(r.model,r.metrics);
  const back=ctx.stanceLoad(JSON.parse(json));
  const a=ctx.stanceScore(r.model,'להגדיל תקציב','אני תומך בהגדלת התקציב'),b=ctx.stanceScore(back,'להגדיל תקציב','אני תומך בהגדלת התקציב');
  a.forEach((v,i)=>assert.ok(Math.abs(v-b[i])<1e-6));
  assert.ok(a[0]>a[1]&&a[0]>a[2]);
});
