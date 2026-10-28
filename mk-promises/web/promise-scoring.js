(function(root,factory){
  if(typeof module==='object'&&module.exports)module.exports=factory();
  else root.MKPromiseScoring=factory();
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  var VALUES={kept:255,partial:128,broken:0};
  function validUrl(value){
    try{if(typeof value!=='string'||!/^https?:\/\//i.test(value)||/\s/.test(value))return false;var u=new URL(value);return /^https?:$/.test(u.protocol)&&!!u.hostname}catch(e){return false}
  }
  function excluded(code,reason){return{value:null,reason:reason,code:code}}
  function scorePromise(p){
    if(!p||!Object.prototype.hasOwnProperty.call(VALUES,p.status))return excluded('unreviewed','ההבטחה טרם סווגה לקיום, קיום חלקי או אי־קיום');
    if(!validUrl(p.sourceUrl))return excluded('missing-source','נדרש קישור למקור ההבטחה');
    var evidence=Array.isArray(p.evidence)?p.evidence:[];
    var support=false,contradict=false;
    evidence.forEach(function(e){
      if(!e||typeof e.title!=='string'||!e.title.trim()||!validUrl(e.url))return;
      if(e.effect==='support')support=true;
      if(e.effect==='contradict')contradict=true;
    });
    if((p.status==='kept'&&!support)||(p.status==='broken'&&!contradict)||(p.status==='partial'&&!support&&!contradict))return excluded('missing-evidence','נדרשת ראיה מקושרת התומכת בסיווג: מקדמת לקיום, סותרת לאי־קיום, או אחת מהן לקיום חלקי');
    return{value:VALUES[p.status],reason:'מבוסס על הסטטוס והראיות שהמשתמש הזין',code:'scored',mixedEvidence:support&&contradict};
  }
  function scoreMember(m){
    var promises=m&&Array.isArray(m.promises)?m.promises:[],sum=0,scored=0;
    promises.forEach(function(p){var r=scorePromise(p);if(r.value!==null){sum+=r.value;scored++}});
    return{value:scored?Math.round(sum/scored):null,scored:scored,total:promises.length,
      excluded:promises.length-scored,coverage:promises.length?Math.round(100*scored/promises.length):0,methodVersion:'reviewed-v1'};
  }
  return{scorePromise:scorePromise,scoreMember:scoreMember,methodVersion:'reviewed-v1',maxScore:255};
});
