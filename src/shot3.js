const {chromium}=require('playwright');
(async()=>{
const b=await chromium.launch();
const c=await b.newContext({viewport:{width:412,height:880},deviceScaleFactor:1,locale:'fa-IR',hasTouch:true,isMobile:true});
const p=await c.newPage();
p.on('pageerror',e=>console.log('ERR',e.message.slice(0,200)));
await p.goto('http://localhost:8765/index.html');await p.waitForTimeout(2500);
await p.evaluate(()=>{var T=window.__t,d=T.kcD(),n=Date.now(),a=[];
 for(var i=0;i<40;i++)a.push({r:i%2?'u':'a',t:'پیام شمارهٔ '+i,ts:n-(40-i)*60e3});
 d.ch.plan=a;T.actions.kcAdv()});
await p.waitForTimeout(800);
await p.click('.kc-topic[data-v=plan]');await p.waitForTimeout(1500);
const st=()=>p.evaluate(()=>{var l=document.getElementById('kcxL');return Math.round(l.scrollTop)+'/'+(l.scrollHeight-l.clientHeight)});
console.log('first open',await st());
await p.evaluate(()=>{document.getElementById('kcxL').scrollTop=300});await p.waitForTimeout(900);
console.log('scrolled',await st());
await p.evaluate(()=>__t.actions.kcmBack&&0);await p.click('.kcx-rb[data-act=kcmBack]');await p.waitForTimeout(900);
await p.click('.kc-topic[data-v=plan]');
const seen=[];for(let i=0;i<8;i++){await p.waitForTimeout(60);seen.push(await st())}
console.log('reopen samples',seen.join(' '));
await b.close()})();
