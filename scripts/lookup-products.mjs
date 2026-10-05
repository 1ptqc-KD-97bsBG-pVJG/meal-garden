#!/usr/bin/env node
// Run through the live companion so model work shares its serialized, priority-aware queue.
const url=process.env.MEAL_COMPANION_URL||`http://127.0.0.1:${process.env.MEAL_PORT||4783}`;
try{
 const r=await fetch(new URL('/internal/product-lookups',url),{method:'POST',headers:{'Content-Type':'application/json',...(process.env.MEAL_DEVICE_TOKEN?{Authorization:`Bearer ${process.env.MEAL_DEVICE_TOKEN}`}:{})},body:'{}',signal:AbortSignal.timeout(30*60*1000)});
 const result=await r.json();if(!r.ok)throw new Error(result.error||`HTTP ${r.status}`);
 console.log(JSON.stringify(result,null,2));
}catch(e){console.error('Product lookup failed:',e.message);process.exitCode=1;}
