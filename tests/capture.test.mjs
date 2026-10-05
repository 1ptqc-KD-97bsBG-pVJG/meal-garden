import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {EventEmitter} from 'node:events';
import {atomic,hash} from '../companion/domain.mjs';
import {createCompanion} from '../companion/server.mjs';
import {Store} from '../companion/store.mjs';
import {recordCapture,addCaptureDetail,recordInterpretation,captureView,recentCaptures} from '../companion/capture.mjs';

const JPEG=Buffer.from([0xff,0xd8,0xff,0xe0,1,2,3,0xff,0xd9]);
const photo=(bytes=JPEG)=>({imageData:bytes.toString('base64'),imageSha256:crypto.createHash('sha256').update(bytes).digest('hex'),width:1200,height:1600});
const tmp=()=>fs.mkdtempSync(path.join(os.tmpdir(),'garden-capture-'));

class FakeProvider extends EventEmitter{
 constructor(){super();this.threadModels=new Map();this.threads=[]}
 async thread(...args){this.threads.push(args);return 'thread-food'}
 async start(...args){this.startArgs=args;return {turn:{id:'turn-food'}}}
 async cancel(){}
 respond(id,result){this.responses=[...(this.responses||[]),{id,result}]}
 reject(){}
 close(){}
}

const interpretation=captureId=>({captureId,category:'meal',title:'Tofu rice bowl',items:[{name:'tofu',portion:'about 1 cup',confidence:'medium'}],
 nutrition:{calories:{low:550,high:750},protein_g:{low:25,high:35},carbs_g:{low:70,high:95},fat_g:{low:15,high:25},fiber_g:{low:6,high:10}},
 matchedRecipeId:null,overallConfidence:'medium',questions:[],notes:''});

test('captures are saved as evidence, idempotently, before any interpretation',()=>{
 const dir=tmp(),store=new Store(path.join(dir,'garden.sqlite'));
 try{
  const input={id:crypto.randomUUID(),kind:'meal',note:'dinner',phoneTime:'2026-09-29T19:30:00-07:00',...photo()};
  const first=recordCapture(store,dir,input);
  assert.equal(first.created,true);
  assert.equal(first.capture.status,'pending');
  assert.equal(fs.existsSync(path.join(dir,first.capture.media.path)),true);
  assert.equal(recordCapture(store,dir,input).created,false);
  assert.throws(()=>recordCapture(store,dir,{...input,note:'different'}),/different content/);
  assert.throws(()=>recordCapture(store,dir,{...input,id:crypto.randomUUID(),imageSha256:'0'.repeat(64)}),/checksum/);
  assert.throws(()=>recordCapture(store,dir,{id:crypto.randomUUID(),kind:'meal',note:' ',phoneTime:'2026-09-29T19:30:00-07:00'}),/photo or a note/);
  const textOnly=recordCapture(store,dir,{id:crypto.randomUUID(),kind:'snack',note:'handful of almonds',phoneTime:'2026-09-29T15:00:00-07:00'});
  assert.equal(textOnly.capture.media,null);
  assert.deepEqual(recentCaptures(store).map(c=>c.id),[input.id,textOnly.capture.id]);
 }finally{store.close();fs.rmSync(dir,{recursive:true,force:true})}
});

test('one entry preserves all photos, validates the whole set, and reads legacy evidence',()=>{
 const dir=tmp(),store=new Store(path.join(dir,'garden.sqlite'));
 try{
  const second=photo(Buffer.concat([JPEG,Buffer.from([42])])),photos=[photo(),second];
  const input={id:crypto.randomUUID(),kind:'meal',note:'food and label',phoneTime:'2026-10-02T12:00:00-07:00',photos};
  const first=recordCapture(store,dir,input);
  assert.deepEqual(first.capture.mediaList.map(m=>m.sha256),photos.map(p=>p.imageSha256));
  assert.equal(store.get("SELECT COUNT(*) AS n FROM events WHERE type='captured'").n,1);
  assert.equal(store.get('SELECT COUNT(*) AS n FROM media').n,2);
  first.capture.mediaList.forEach(m=>assert.equal(fs.existsSync(path.join(dir,m.path)),true));
  assert.equal(recordCapture(store,dir,input).created,false);
  assert.throws(()=>recordCapture(store,dir,{...input,photos:photos.toReversed()}),/different content/);
  assert.throws(()=>recordCapture(store,dir,{...input,id:crypto.randomUUID(),photos:[photo(),{...second,imageSha256:'bad'}]}),/checksum/);
  assert.throws(()=>recordCapture(store,dir,{...input,id:crypto.randomUUID(),photos:Array(11).fill(photo())}),/10 photos/);
  assert.throws(()=>recordCapture(store,dir,{...input,photos:{}}),/10 photos/);
  assert.throws(()=>recordCapture(store,dir,{...input,...photo()}),/not both/);
  assert.equal(store.get("SELECT COUNT(*) AS n FROM events WHERE type='captured'").n,1);
  const legacyId=crypto.randomUUID();
  store.record('captured',`capture:${legacyId}`,{kind:'meal',note:'old entry',media:first.capture.media},{occurredAt:input.phoneTime});
  assert.deepEqual(captureView(store,legacyId).mediaList,[first.capture.media]);
  assert.equal(recordCapture(store,dir,{id:legacyId,kind:'meal',note:'old entry',phoneTime:input.phoneTime,...photo()}).created,false);
 }finally{store.close();fs.rmSync(dir,{recursive:true,force:true})}
});

test('interpretations are validated and scoped to their own capture',()=>{
 const dir=tmp(),store=new Store(path.join(dir,'garden.sqlite'));
 try{
  const id=crypto.randomUUID();recordCapture(store,dir,{id,kind:'meal',note:'',phoneTime:'2026-09-29T19:30:00-07:00',...photo()});
  assert.throws(()=>recordInterpretation(store,id,interpretation(crypto.randomUUID()),'job-1'),/only record the capture/);
  assert.throws(()=>recordInterpretation(store,id,{...interpretation(id),nutrition:{...interpretation(id).nutrition,calories:{low:900,high:100}}},'job-1'),/ranges/);
  recordInterpretation(store,id,interpretation(id),'job-1');
  const view=captureView(store,id);
  assert.equal(view.status,'interpreted');
  assert.equal(view.interpretation.nutrition.calories.high,750);
  const detail={id,detailId:crypto.randomUUID(),text:'It was chicken, not tofu',phoneTime:'2026-09-29T20:00:00-07:00'};
  assert.equal(addCaptureDetail(store,detail).created,true);
  assert.equal(addCaptureDetail(store,detail).created,false);
  assert.equal(captureView(store,id).details.length,1);
 }finally{store.close();fs.rmSync(dir,{recursive:true,force:true})}
});

test('capture API starts a fresh read-only food-log task that can only write its own result',async()=>{
 const root=tmp(),runtime=path.join(root,'.runtime'),provider=new FakeProvider();
 fs.mkdirSync(path.join(root,'recipes'),{recursive:true});fs.mkdirSync(path.join(root,'data/plans'),{recursive:true});
 atomic(path.join(root,'data/plans/p.json'),{id:'p',status:'active',meals:[]});
 const app=createCompanion({root,runtime,provider});
 app.store.run('INSERT INTO devices VALUES(?,?,?)',hash('capture-token'),'Pixel','now');
 await new Promise(resolve=>app.server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${app.server.address().port}`;
 const call=async(route,data)=>{const r=await fetch(base+route,{method:data?'POST':'GET',headers:{Authorization:'Bearer capture-token','Content-Type':'application/json'},body:data?JSON.stringify(data):undefined});return {status:r.status,body:await r.json()}};
 try{
  const id=crypto.randomUUID();
  const photos=[photo(),photo(Buffer.concat([JPEG,Buffer.from([77])]))];
  const saved=await call('/api/captures/multi',{id,kind:'meal',note:'leftover bowl',phoneTime:'2026-09-29T19:30:00-07:00',photos});
  assert.equal(saved.status,201);
  assert.equal(saved.body.status,'interpreting');
  await new Promise(resolve=>setTimeout(resolve,30));
  const [providerId,,, model,,sandbox]=provider.threads[0];
  assert.equal(providerId,null);
  assert.equal(model,'gpt-6.1-sol');
  assert.equal(sandbox,'read-only');
  assert.match(provider.startArgs[3][0],new RegExp(`media/${photo().imageSha256}\\.jpg$`));
  assert.equal(provider.startArgs[3].length,2);
  assert.ok(provider.startArgs[3][1].endsWith(`media/${photos[1].imageSha256}.jpg`));
  assert.match(provider.startArgs[1],/evidence for ONE entry/);
  assert.match(provider.startArgs[1],/do not count the food again/);
  assert.equal((await call('/api/captures',{id,kind:'meal',note:'leftover bowl',phoneTime:'2026-09-29T19:30:00-07:00',photos})).status,200);
  assert.equal(app.store.get("SELECT COUNT(*) AS n FROM jobs WHERE kind='log_food'").n,1);
  const job=app.store.get("SELECT * FROM jobs WHERE kind='log_food'");
  assert.equal((await call('/api/jobs',{requestKey:'sneaky',kind:'log_food',text:'x'})).body.kind,'chat');
  provider.emit('request',{id:7,method:'item/tool/call',params:{threadId:'thread-food',tool:'meal_garden_food_log_result',arguments:interpretation(crypto.randomUUID())}});
  provider.emit('request',{id:8,method:'item/tool/call',params:{threadId:'thread-food',tool:'meal_garden_food_log_result',arguments:interpretation(id)}});
  await new Promise(resolve=>setTimeout(resolve,10));
  assert.deepEqual(provider.responses.map(r=>r.result.success),[false,true]);
  provider.emit('event',{method:'turn/completed',params:{threadId:'thread-food',turn:{id:'turn-food',status:'completed'}}});
  await new Promise(resolve=>setTimeout(resolve,10));
  assert.equal(app.store.get('SELECT status FROM jobs WHERE id=?',job.id).status,'completed');
  const snap=await call('/api/snapshot');
  assert.deepEqual(snap.body.healthPreferences.targets,{});
  assert.equal((await call('/api/health/preferences',{priorities:['longevity','muscle_gain'],targets:{protein_g:120}})).status,200);
  assert.equal((await call('/api/snapshot')).body.healthPreferences.targets.protein_g,120);
  assert.equal((await call('/api/health/preferences',{priorities:['longevity'],targets:{calories:'1200'}})).status,400);
  assert.equal(snap.body.captures[0].status,'interpreted');
  assert.equal(snap.body.captures[0].interpretation.title,'Tofu rice bowl');
  assert.equal((await call('/api/activity')).body.conversations.some(c=>c.source==='food_log'),false);
 }finally{app.close();fs.rmSync(root,{recursive:true,force:true})}
});
