const test=require('node:test');
const assert=require('node:assert/strict');
const scoring=require('../web/promise-scoring.js');
const sourceUrl='https://example.org/promise';
function promise(status,effect){return{status,sourceUrl,text:'הבטחה לדוגמה',evidence:[{title:'פעילות מתועדת',url:'https://example.org/evidence',effect}]}}

test('reviewed promises with relevant linked evidence map to the full 0–255 range',()=>{
  assert.equal(scoring.scorePromise(promise('kept','support')).value,255);
  assert.equal(scoring.scorePromise(promise('partial','support')).value,128);
  assert.equal(scoring.scorePromise(promise('partial','contradict')).value,128);
  assert.equal(scoring.scorePromise(promise('broken','contradict')).value,0);
});
test('unchecked and unmeasurable promises do not turn into zero scores',()=>{
  for(const status of ['unchecked','unmeasurable','coalition','constructor',undefined])assert.equal(scoring.scorePromise(promise(status,'support')).value,null);
  assert.equal(scoring.scoreMember({promises:[]}).value,null);
});
test('missing, context-only, wrong-direction or unsafe evidence cannot create a fulfillment score',()=>{
  const cases=[{...promise('kept','support'),sourceUrl:''},promise('kept','context'),promise('kept','contradict'),promise('broken','support'),{...promise('partial','support'),evidence:[]}];
  for(const p of cases)assert.equal(scoring.scorePromise(p).value,null);
  for(const url of ['javascript:alert(1)','https://','file:///tmp/proof','https://example .org']){
    const p=promise('kept','support');p.evidence[0].url=url;
    assert.equal(scoring.scorePromise(p).value,null);
  }
});
test('aggregate uses equal promise weights and discloses coverage',()=>{
  const m={promises:[promise('kept','support'),promise('partial','support'),promise('broken','contradict'),promise('unchecked','support'),promise('coalition','support')]};
  assert.deepEqual(scoring.scoreMember(m),{value:128,scored:3,total:5,excluded:2,coverage:60,methodVersion:'reviewed-v1'});
  const before=JSON.stringify(m);m.promises[0].evidence.push(...Array(10).fill(m.promises[0].evidence[0]));
  assert.equal(scoring.scoreMember(m).value,128);
  assert.equal(scoring.scoreMember(JSON.parse(before)).value,128);
});
test('topic predictions and matching scores never substitute for fulfillment evidence or mutate records',()=>{
  const p={...promise('unchecked','support'),topicAnalysis:{engine:'llm',topics:[{id:'housing',score:1}]}};
  const before=JSON.stringify(p);
  assert.equal(scoring.scorePromise(p).value,null);
  assert.equal(JSON.stringify(p),before);
});
