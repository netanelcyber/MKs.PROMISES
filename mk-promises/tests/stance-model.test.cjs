'use strict';
// Compares the in-app JavaScript model with PyTorch. Needs a hashed checkpoint; skipped when torch is missing.
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const vm=require('node:vm');
const {spawnSync}=require('node:child_process');

const html=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8');
const src=html.slice(html.indexOf('/* ---------- stance model'),html.indexOf('/* ---------- views'));
const ctx={TextEncoder,atob,Math,Float32Array,Int32Array,Uint8Array,Uint16Array,Object,Array,String,Error};
vm.createContext(ctx);vm.runInContext(src,ctx);
const ml=path.join(__dirname,'../ml');
const py=spawnSync('python3',['-c','import torch'],{encoding:'utf8'});

test('crc32 matches zlib',()=>{
  assert.equal(ctx.crc32('w:הבטחה'),Number(spawnSync('python3',['-c','import zlib;print(zlib.crc32("w:הבטחה".encode()))'],{encoding:'utf8'}).stdout));
  assert.equal(ctx.crc32('abc'),891568578);
});

test('JS model equals PyTorch on a random hashed model',{skip:py.status!==0&&'torch not installed'},()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'stance-'));
  const code=`
import sys,json,torch
sys.path.insert(0,${JSON.stringify(ml)})
from model import build,save_checkpoint
torch.manual_seed(1)
cfg={"encoder":"hashed","buckets":2048,"dim":16,"lstm_hidden":12,"lstm_layers":2,"dnn":[20,10],"dropout":0.3}
net,coll=build(cfg);net.eval()
save_checkpoint(net,cfg,${JSON.stringify(dir+'/m.pt')})
pairs=[["להגדיל את תקציב הביטחון","השר אמר שהתקציב יוגדל בשנה הבאה"],["להוריד מיסים","בכלל לא נגענו בנושא"]]
out=[]
for p,u in pairs:
    b=coll([{"promise":p,"utterance":u}]);b.pop("y")
    with torch.no_grad(): out.append(torch.softmax(net(b),-1)[0].tolist())
json.dump({"pairs":pairs,"probs":out},open(${JSON.stringify(dir+'/ref.json')},"w"))
`;
  assert.equal(spawnSync('python3',['-c',code],{encoding:'utf8'}).status,0);
  assert.equal(spawnSync('python3',[path.join(ml,'export_js.py'),'--checkpoint',dir+'/m.pt','--out',dir+'/m.json','--fp32'],{encoding:'utf8'}).status,0);
  const model=ctx.stanceLoad(JSON.parse(fs.readFileSync(dir+'/m.json','utf8')));
  const ref=JSON.parse(fs.readFileSync(dir+'/ref.json','utf8'));
  ref.pairs.forEach((pair,i)=>{
    const got=Array.from(ctx.stanceScore(model,pair[0],pair[1]));
    got.forEach((v,k)=>assert.ok(Math.abs(v-ref.probs[i][k])<1e-4,`pair ${i} class ${k}: ${v} vs ${ref.probs[i][k]}`));
  });
});

test('stanceLoad rejects other files',()=>{
  assert.throws(()=>ctx.stanceLoad({format:'x'}));
});
