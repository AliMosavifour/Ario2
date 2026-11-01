/* ---------- Arabic (ar): screen-text translator ----------
   Most Persian texts are translated where they are written, with __("persian") and the dictionary in i18n-ar.js (__AR).
   Texts built from pieces (numbers, names, "EN?english:persian") and the Konkoria screens are written in Persian in app.js; in Arabic mode every
   known Persian phrase that appears on screen (text, placeholder, aria-label, title, alt and text drawn on canvases) is replaced with the Arabic text.
   Persian mode and English mode never run this code. Add a phrase: put  "persian":"arabic"  in __AR (or __ARK). */
var __arT=function(s){return s};
function __domAr(root){
  var A=['placeholder','aria-label','title','alt'],tw=document.createTreeWalker(root,NodeFilter.SHOW_TEXT|NodeFilter.SHOW_ELEMENT,null),n,t,i,v,p;
  while((n=tw.nextNode())){
    if(n.nodeType===3){
      p=n.parentNode&&n.parentNode.nodeName;if(p==='STYLE'||p==='SCRIPT')continue;
      t=n.nodeValue.trim();if(t&&__AR[t]!==undefined)n.nodeValue=n.nodeValue.replace(t,__AR[t]);
      n.nodeValue=__arT(n.nodeValue);
    }else{
      for(i=0;i<A.length;i++){v=n.getAttribute(A[i]);if(v){t=__AR[v.trim()]!==undefined?__AR[v.trim()]:__arT(v);if(t!==v)n.setAttribute(A[i],t)}}
    }
  }
}
(function(){
  if(typeof ARB==='undefined'||!ARB)return;
  var ALL={},k;for(k in __ARK)ALL[k]=__ARK[k];for(k in __AR)ALL[k]=__AR[k];
  var FA=/[\u0621-\u063A\u0641-\u064A\u0671-\u06D3\u06FA-\u06FF]/,PL=/[\u067E\u0686\u0698\u06AF\u06A9\u06CC]/,PD=/[\u06f0-\u06f9]/,LET='\\u0621-\\u063A\\u0641-\\u064A\\u0671-\\u06D3\\u06FA-\\u06FF\\u200c';
  var keys=Object.keys(ALL).filter(function(x){return x.length>0}).sort(function(a,b){return b.length-a.length});
  function rx(s){return s.replace(/[.*+?^${}()|[\]\\\/]/g,'\\$&')}
  var RE=new RegExp(keys.map(function(k){
    var a=FA.test(k.charAt(0)),b=FA.test(k.charAt(k.length-1));
    return (a?'(?<!['+LET+'])':'')+rx(k)+(b?'(?!['+LET+'])':'');
  }).join('|'),'g');
  var DG='\u06f0\u06f1\u06f2\u06f3\u06f4\u06f5\u06f6\u06f7\u06f8\u06f9',AD='\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669';
  function dig(s){return s.replace(/[\u06f0-\u06f9]/g,function(d){return AD.charAt(DG.indexOf(d))})}
  /* a text is replaced only when no Persian-only letter is left over (names between quotes may stay), so a half-translated sentence is never produced */
  function T(s){
    if(!s)return s;
    if(!FA.test(s))return PD.test(s)?dig(s):s;
    var o=s.replace(RE,function(m){return ALL[m]});
    o=dig(o);
    return PL.test(o.replace(/«[^»]*»|"[^"]*"/g,''))?(PD.test(s)?dig(s):s):o;
  }
  __arT=T;window.__arT=T;
  var AT=['placeholder','aria-label','title','alt'],SKIP='SCRIPT,STYLE,TEXTAREA,INPUT,PRE.rp-form,.rp-form,[data-nokk]';
  var doneT=new WeakMap(),doneA=new WeakMap();
  function skip(n){return n.matches&&n.matches(SKIP)}
  function txt(n){var v=n.nodeValue;if(doneT.get(n)===v)return;var t=T(v);if(t!==v)n.nodeValue=t;doneT.set(n,n.nodeValue)}
  function attrs(el){
    var m=doneA.get(el);if(!m){m={};doneA.set(el,m)}
    for(var i=0;i<AT.length;i++){var v=el.getAttribute(AT[i]);if(v&&m[AT[i]]!==v){var t=T(v);if(t!==v)el.setAttribute(AT[i],t);m[AT[i]]=t}}
  }
  function walk(n){
    if(n.nodeType===3){var p=n.parentNode;if(p&&p.nodeType===1&&p.closest&&p.closest(SKIP))return;txt(n);return}
    if(n.nodeType!==1||skip(n)||(n.closest&&n.closest(SKIP)))return;
    attrs(n);
    var w=document.createTreeWalker(n,5,{acceptNode:function(x){return x.nodeType===1&&skip(x)?2:1}}),c;
    while((c=w.nextNode())){if(c.nodeType===3)txt(c);else attrs(c)}
  }
  function start(){
    if(!document.body)return;
    walk(document.body);
    new MutationObserver(function(ms){
      for(var i=0;i<ms.length;i++){
        var m=ms[i];
        if(m.type==='childList'){for(var j=0;j<m.addedNodes.length;j++)walk(m.addedNodes[j])}
        else if(m.type==='characterData'){var pp=m.target.parentNode;if(!(pp&&pp.closest&&pp.closest(SKIP)))txt(m.target)}
        else if(m.type==='attributes'&&m.target.nodeType===1)attrs(m.target);
      }
    }).observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:AT});
  }
  setTimeout(start,0);
  /* text drawn on canvases (timetable picture, report picture, rank report) */
  var gc=HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext=function(t){
    var c=gc.apply(this,arguments);
    if(c&&t==='2d'&&!c.__ar){
      c.__ar=1;var ft=c.fillText,ms=c.measureText;
      c.fillText=function(s,x,y,w){return w===undefined?ft.call(c,T(String(s)),x,y):ft.call(c,T(String(s)),x,y,w)};
      c.measureText=function(s){return ms.call(c,T(String(s)))};
    }
    return c;
  };
})();
