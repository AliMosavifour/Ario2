/* ---------- i18n core: Persian (fa) and English (en) ----------
   LANG is decided once at start-up (profile.lang, or the phone language on a first run) and the app reloads when it changes.
   __("persian text") returns the English text in English mode (dictionary in i18n-en.js) and the Persian text otherwise.
   __("persian text","english") gives the English text inline. Persian mode never touches the strings. */
var LANG=(function(){
  var l='fa';
  try{
    var sv=localStorage.getItem('barnameyar.lang');
    if(sv==='en'||sv==='fa'||sv==='ar')return sv;
    if(localStorage.getItem('barnameyar.v1'))l='fa';                       /* existing users who never chose stay Persian */
    else l=(function(n){return n.indexOf('fa')===0?'fa':n.indexOf('ar')===0?'ar':'en'})(String(navigator.language||'fa').toLowerCase());   /* first run: follow the phone */
    localStorage.setItem('barnameyar.lang',l);
  }catch(e){}
  return l;
})();
var EN=LANG==='en',ARB=LANG==='ar';
function __(s,e){if(ARB){var a_=__AR[s];return a_===undefined?s:a_}if(!EN)return s;if(e!==undefined)return e;var v=__EN[s];return v===undefined?s:v}
/* "3 tasks" / "1 task" (English only; Persian has no plural form) */
function __pl(n,one,many){return n+' '+(n===1?one:many)}
/* translate the static texts of index.html once (text nodes + placeholder / aria-label / title / alt) */
function __dom(root){
  if(ARB&&root){__domAr(root);return}
  if(!EN||!root)return;
  var A=['placeholder','aria-label','title','alt'],tw=document.createTreeWalker(root,NodeFilter.SHOW_TEXT|NodeFilter.SHOW_ELEMENT,null),n,t,i,v,p;
  while((n=tw.nextNode())){
    if(n.nodeType===3){
      p=n.parentNode&&n.parentNode.nodeName;if(p==='STYLE'||p==='SCRIPT')continue;
      t=n.nodeValue.trim();if(t&&__EN[t]!==undefined)n.nodeValue=n.nodeValue.replace(t,__EN[t]);
      n.nodeValue=n.nodeValue.replace(/[۰-۹]/g,function(d){return '۰۱۲۳۴۵۶۷۸۹'.indexOf(d)}).replace(/٪/g,'%');
    }else{
      for(i=0;i<A.length;i++){v=n.getAttribute(A[i]);if(v&&__EN[v.trim()]!==undefined)n.setAttribute(A[i],__EN[v.trim()])}
    }
  }
}
