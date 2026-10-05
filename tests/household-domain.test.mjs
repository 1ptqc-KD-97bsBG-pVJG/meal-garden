import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {EventEmitter} from 'node:events';
import {Store} from '../companion/store.mjs';
import {FoodGraph} from '../companion/graph.mjs';
import {loadHousehold,profilePath,androidActor} from '../companion/household.mjs';
import {atomic,localDate,snapshot,recordObservation} from '../companion/domain.mjs';
import {compileInstructions,instructionsForHousehold,jobPromptsForHousehold,interviewPrompt,toolsForTask} from '../companion/agent-contract.mjs';
import {recordCapture,addCaptureDetail,recordInterpretation,interpretationPrompt} from '../companion/capture.mjs';
import {foodLog,foodDay} from '../companion/context.mjs';
import {saveHealthPreferences,healthPreferences} from '../companion/health.mjs';
import {Jobs,shoppingIntent} from '../companion/jobs.mjs';

const configured={schema_version:1,id:'orchard',name:'Orchard kitchen',person:{id:'cook',name:'Alex',pronouns:'she/her'},timezone:'Asia/Tokyo'};
const shopping={list_service:'samsung_food',list_name:'Weekly supplies',store:{chain:'Market',label:'Downtown',address:'1 Example Way'}};
function fixture(config=configured){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-household-domain-'));
 if(config)atomic(path.join(root,'household.json'),config);
 fs.mkdirSync(path.join(root,'recipes'),{recursive:true});
 const file=path.join(root,'.runtime/garden.sqlite'),store=new Store(file),household=loadHousehold(root);
 return {root,file,store,household,close(){store.close();fs.rmSync(root,{recursive:true,force:true});}};
}
class FakeProvider extends EventEmitter {
 async thread(...args){this.threadArgs=args;return 'household-thread';}
 async start(...args){this.startArgs=args;return {turn:{id:'household-turn'}};}
 respond(id,result){this.result=result;}
 reject(){}
}
const waitFor=async predicate=>{for(let n=0;n<200&&!predicate();n++)await new Promise(resolve=>setTimeout(resolve,5));assert.ok(predicate(),'job did not reach provider');};

test('bare roots seed neutral defaults and configured household identities flow to graph and snapshot',()=>{
 const bare=fixture(null),custom=fixture();try{
  assert.equal(bare.household.person.id,'owner');assert.equal(bare.household.person.name,'You');assert.equal(bare.household.timezone,'UTC');assert.deepEqual(healthPreferences(bare.store).priorities,['longevity','energy']);
  assert.equal(bare.store.get('SELECT name FROM persons WHERE id=?','owner').name,'You');
  assert.equal(custom.store.get('SELECT name FROM households WHERE id=?','orchard').name,'Orchard kitchen');
  assert.equal(custom.store.get('SELECT household_id FROM persons WHERE id=?','cook').household_id,'orchard');
  assert.equal(custom.store.get('SELECT COUNT(*) n FROM persons').n,1);
  const graph=new FoodGraph(custom.store);assert.equal(graph.person,'cook');assert.equal(graph.household,'orchard');assert.equal(graph.root,custom.root);
  atomic(profilePath(custom.root),{display_name:'Alex',goals:[]});
  const view=snapshot(custom.root);assert.equal(view.profile.display_name,'Alex');assert.equal(view.household.name,'Orchard kitchen');assert.equal(view.household.person.pronouns,'she/her');assert.equal(view.household.shopping,null);
 }finally{bare.close();custom.close();}
});

test('explicit profile goals preserve priority order until saved app preferences supersede them',()=>{
 const f=fixture();try{
  atomic(profilePath(f.root),{health_goals:{priority_order:['longevity','weight_loss','muscle_gain'],supporting_goals:['steadier_energy'],numeric_targets:null}});
  const goals=healthPreferences(f.store);assert.deepEqual(goals.priorities,['longevity','weight_loss','muscle_gain','energy']);assert.deepEqual(goals.targets,{});assert.match(goals.source,/profile\/cook\.json/);
  assert.equal(f.store.get("SELECT value FROM settings WHERE key='health_preferences'"),undefined);
  saveHealthPreferences(f.store,{priorities:['energy'],targets:{protein_g:100}});
  assert.deepEqual(healthPreferences(f.store),{version:1,priorities:['energy'],targets:{protein_g:100}});
 }finally{f.close();}
});

test('opening a configured store preserves existing household and person rows',()=>{
 const f=fixture();try{
  f.store.run('UPDATE households SET name=? WHERE id=?','Preserved name','orchard');
  f.store.run('UPDATE persons SET name=? WHERE id=?','Preserved person','cook');
  const reopened=new Store(f.file,{recoverJobs:false});try{
   assert.equal(reopened.get('SELECT name FROM households WHERE id=?','orchard').name,'Preserved name');
   assert.equal(reopened.get('SELECT name FROM persons WHERE id=?','cook').name,'Preserved person');
   assert.equal(reopened.get('SELECT COUNT(*) n FROM persons').n,1);
  }finally{reopened.close();}
 }finally{f.close();}
});

test('household validation rejects unsafe identities, invalid zones and escaping notes including symlinks',()=>{
 const f=fixture(),outside=fs.mkdtempSync(path.join(os.tmpdir(),'garden-household-notes-'));try{
  for(const change of [{id:'../another'},{person:{id:'../other',name:'Alex'}},{timezone:'Invalid/Zone'},{shopping:{...shopping,list_service:'invented'}},{assistant_notes:'../notes.md'}]){
   atomic(path.join(f.root,'household.json'),{...configured,...change});assert.throws(()=>loadHousehold(f.root),/Invalid|Unsupported|inside/);
  }
  fs.writeFileSync(path.join(outside,'notes.md'),'Other household notes');fs.symlinkSync(path.join(outside,'notes.md'),path.join(f.root,'notes.md'));
  atomic(path.join(f.root,'household.json'),{...configured,assistant_notes:'notes.md'});assert.throws(()=>compileInstructions(f.root),/inside/);
  atomic(path.join(f.root,'household.json'),{...configured,person:{id:'cook',name:'Alex'}});assert.equal(loadHousehold(f.root).person.pronouns,'they/them');
 }finally{f.close();fs.rmSync(outside,{recursive:true,force:true});}
});

test('compiled instructions append only this household notes and honor shopping and personal settings',()=>{
 const f=fixture({...configured,shopping,assistant_notes:'notes.md'});try{
  const notes='Keep the pot handles away from the edge.\n';fs.writeFileSync(path.join(f.root,'notes.md'),notes);
  const contract=compileInstructions(f.root);for(const text of ['Alex','she/her','Asia/Tokyo','Orchard kitchen','Weekly supplies','Downtown','1 Example Way',notes])assert.ok(contract.includes(text),text);
  assert.ok(contract.indexOf('RECIPE STANDARD')<contract.indexOf(notes));assert.match(interviewPrompt({},f.household),/profile\/cook\.json/);
  fs.unlinkSync(path.join(f.root,'notes.md'));assert.doesNotMatch(compileInstructions(f.root),/Keep the pot handles/);
  assert.match(instructionsForHousehold(loadHousehold()),/they\/them/);assert.match(instructionsForHousehold(loadHousehold()),/no shopping integration/);
  assert.ok(shoppingIntent('Please sync Weekly supplies',f.household));
  const prompts=jobPromptsForHousehold(f.household);assert.match(prompts.shopping,/Weekly supplies/);assert.match(prompts.purchases,/Market purchases/);assert.match(prompts.connection,/Market purchase history/);
  const listOnly={...configured,shopping:{list_service:'samsung_food',list_name:'Weekly supplies'}};
  assert.match(instructionsForHousehold(listOnly),/No retailer is configured/);assert.match(jobPromptsForHousehold(listOnly).shopping,/never invent a store location/);assert.equal(jobPromptsForHousehold(listOnly).purchases,undefined);
 }finally{f.close();}
});

test('captures, intake, health preferences and observation attribution use the configured person',()=>{
 const f=fixture();try{
  const id=crypto.randomUUID(),detailId=crypto.randomUUID(),phoneTime='2026-10-04T23:30:00Z';
  const view=recordCapture(f.store,path.join(f.root,'.runtime'),{id,kind:'meal',note:'A small bowl of rice',phoneTime}).capture;
  addCaptureDetail(f.store,{id,detailId,text:'No sauce',phoneTime});
  assert.equal(androidActor(f.household),'cook:android');
  assert.deepEqual(f.store.events(`capture:${id}`).map(e=>e.actor),['cook:android','cook:android']);
  const result=recordInterpretation(f.store,id,{captureId:id,category:'meal',title:'Rice',items:[{name:'Rice',portion:'A bowl',confidence:'low'}],nutrition:null,overallConfidence:'low',questions:[],notes:''},'interpretation');
  assert.equal(f.store.get('SELECT person_id FROM intake WHERE id=?',result.intakeId).person_id,'cook');
  assert.equal(foodDay(phoneTime,f.household),'2026-10-05');assert.equal(localDate(f.root,phoneTime),'2026-10-05');assert.equal(foodDay(phoneTime),'2026-10-04');
  assert.equal(foodLog(f.store,'2026-10-05').length,1);assert.equal(foodLog(f.store,'2026-10-04').length,0);
  const prompt=interpretationPrompt(view,f.household);assert.match(prompt,/Alex's food log/);assert.match(prompt,/Asia\/Tokyo/);
  saveHealthPreferences(f.store,{priorities:['energy'],targets:{}});assert.equal(f.store.events('health:preferences')[0].actor,'cook:android');
  assert.equal(recordObservation(f.root,{item:'Rice',note:'One bag',idempotencyKey:'rice'}).source,'Alex via Meal Garden');
 }finally{f.close();}
});

test('unconfigured shopping job kinds reject before creating conversations, jobs or messages',()=>{
 const f=fixture(),provider=new FakeProvider(),jobs=new Jobs(f.store,provider,f.root);try{
  for(const kind of ['shopping','purchases','connection'])assert.throws(()=>jobs.enqueue({kind,requestKey:kind}),/no shopping integration configured/);
  assert.equal(f.store.get('SELECT COUNT(*) n FROM jobs').n,0);assert.equal(f.store.get('SELECT COUNT(*) n FROM conversations').n,0);assert.equal(f.store.get('SELECT COUNT(*) n FROM messages').n,0);
  assert.equal(toolsForTask('chat',f.household).some(t=>t.name==='meal_garden_save_receipt'),false);
 }finally{jobs.close();f.close();}
});

test('job prompts use household time, instructions and notes while tool enforcement respects capabilities',async()=>{
 const f=fixture({...configured,assistant_notes:'notes.md'}),provider=new FakeProvider(),jobs=new Jobs(f.store,provider,f.root,path.join(f.root,'.runtime'),'/tmp/public-code');try{
  fs.writeFileSync(path.join(f.root,'notes.md'),'Prefer a simple one-pot meal.');
  const job=jobs.enqueue({kind:'chat',requestKey:'chat',text:'Help plan dinner'});await waitFor(()=>provider.startArgs);
  assert.match(provider.startArgs[1],/Current local date and time for Alex:/);assert.match(provider.startArgs[1],/Asia\/Tokyo/);
  assert.match(provider.threadArgs[2],/Prefer a simple one-pot meal/);assert.match(provider.threadArgs[2],/she\/her/);
  assert.ok(provider.threadArgs[2].includes(`MEAL_DATA_DIR='${f.root}' python3 '/tmp/public-code/scripts/build.py'`));
  assert.equal(f.store.get('SELECT prompt FROM jobs WHERE id=?',job.id).prompt,provider.startArgs[1]);
  await jobs.request({id:1,method:'item/tool/call',params:{threadId:'household-thread',tool:'meal_garden_save_receipt',arguments:{}}});assert.equal(provider.result.success,false);assert.match(provider.result.contentItems[0].text,/not allowed/);
 }finally{jobs.close();f.close();}
});

test('household file reads and writes reject escaping directory and file symlinks',()=>{
 const f=fixture(),other=fixture({...configured,id:'elsewhere',person:{id:'neighbor',name:'Neighbor'}});try{
  const outside=path.join(other.root,'data/observations');fs.mkdirSync(outside,{recursive:true});
  fs.mkdirSync(path.join(f.root,'data'),{recursive:true});fs.symlinkSync(outside,path.join(f.root,'data/observations'));
  assert.throws(()=>recordObservation(f.root,{item:'Rice',note:'One bag',idempotencyKey:'escaping-write'}),/inside its household/);
  assert.deepEqual(fs.readdirSync(outside),[]);
  fs.unlinkSync(path.join(f.root,'data/observations'));
  atomic(path.join(other.root,'data/plans/secret.json'),{id:'private-plan',status:'active',meals:[]});fs.mkdirSync(path.join(f.root,'data/plans'),{recursive:true});fs.symlinkSync(path.join(other.root,'data/plans/secret.json'),path.join(f.root,'data/plans/leak.json'));
  assert.throws(()=>snapshot(f.root),/inside its household/);fs.unlinkSync(path.join(f.root,'data/plans/leak.json'));
  fs.mkdirSync(path.join(f.root,'profile'),{recursive:true});atomic(profilePath(other.root),{name:'Private neighbor profile'});fs.symlinkSync(profilePath(other.root),profilePath(f.root));
  assert.throws(()=>snapshot(f.root),/inside its household/);assert.throws(()=>healthPreferences(f.store),/inside its household/);
  const media=path.join(other.root,'.runtime/media');fs.mkdirSync(media);fs.symlinkSync(media,path.join(f.root,'.runtime/media'));
  const bytes=Buffer.from([255,216,255,217]);
  assert.throws(()=>recordCapture(f.store,path.join(f.root,'.runtime'),{id:crypto.randomUUID(),kind:'meal',phoneTime:'2026-10-05T12:00:00Z',imageData:bytes.toString('base64')}),/inside its household/);
  assert.deepEqual(fs.readdirSync(media),[]);assert.equal(f.store.get('SELECT COUNT(*) n FROM media').n,0);
 }finally{f.close();other.close();}
});

test('graph person, pantry, batch and linked intake IDs cannot reach another household in preserved rows',()=>{
 const f=fixture();try{
  f.store.run('INSERT INTO households VALUES(?,?)','elsewhere','Another kitchen');f.store.run('INSERT INTO persons VALUES(?,?,?)','neighbor','elsewhere','Neighbor');
  const own=new FoodGraph(f.store),foreign=new FoodGraph(f.store,{household:'elsewhere',person:'neighbor'});
  const product=foreign.upsertProduct({name:'Rice',base_unit:'g',kind:'generic'},{idempotencyKey:'foreign-product'});
  const item=foreign.addPantryItem({productId:product.id},{idempotencyKey:'foreign-lot'});
  const batch=foreign.recordBatch({title:'Neighbor batch',madeAt:'2026-10-05T12:00:00Z',recordedFrom:'report',ingredients:[],yieldG:400},{idempotencyKey:'foreign-batch'});
  const intake=foreign.recordIntake({person:'neighbor',captureId:'foreign-capture',eatenAt:'2026-10-05T12:00:00Z',title:'Neighbor food',components:[]},{idempotencyKey:'foreign-intake'});
  const before=f.store.get('SELECT COUNT(*) n FROM events').n;
  for(const operation of [
   ()=>own.countPantryItem(item.id,0,'known','cook:android',{idempotencyKey:'foreign-count'}),
   ()=>own.recordBatch({title:'Wrong lot',recordedFrom:'report',ingredients:[{pantryItemId:item.id,name:'Rice',amount:1}]},{idempotencyKey:'foreign-ingredient'}),
   ()=>own.recordIntake({person:'neighbor',captureId:'wrong-person',components:[]},{idempotencyKey:'foreign-person'}),
   ()=>own.recordIntake({captureId:'wrong-lot',components:[{pantryItemId:item.id,name:'Rice',amount:1}]},{idempotencyKey:'foreign-component'}),
   ()=>own.recordReaction({person:'neighbor',notes:'Wrong person'},{idempotencyKey:'foreign-reaction-person'}),
   ()=>own.recordReaction({batchId:batch.batch.id,notes:'Wrong batch'},{idempotencyKey:'foreign-reaction-batch'}),
   ()=>own.recordReaction({intakeId:intake.intake.id,notes:'Wrong intake'},{idempotencyKey:'foreign-reaction-intake'}),
   ()=>own.setPreference({person:'neighbor',kind:'taste',subject:'Rice',statement:'Wrong person'},{idempotencyKey:'foreign-preference'}),
   ()=>own.currentPreferences('neighbor'),()=>own.getProduct(batch.product.id),()=>foodLog(f.store,'2026-10-05','neighbor')
  ])assert.throws(operation,/outside this household/);
  assert.equal(f.store.get('SELECT COUNT(*) n FROM events').n,before);assert.equal(foreign.balance(item.id).amount,null);
 }finally{f.close();}
});

test('shopping capabilities are checked before duplicate reuse and again before queued jobs reach a provider',async()=>{
 const f=fixture({...configured,shopping}),provider=new FakeProvider(),jobs=new Jobs(f.store,provider,f.root);try{
  const currentDate=localDate(f.household);atomic(path.join(f.root,'data/plans/current.json'),{id:'current',status:'active',meals:[{date:currentDate}]});atomic(path.join(f.root,'data/shopping/current-trip.json'),{id:'trip',plan_id:'current',buy:[]});
  jobs.active={id:'hold'};const job=jobs.enqueue({kind:'shopping',requestKey:'queued-shopping'});
  provider.externalShoppingUnavailable=true;
  for(const kind of ['shopping','purchases','connection'])assert.throws(()=>jobs.enqueue({kind,requestKey:'queued-shopping'}),/External shopping tools are unavailable/);
  assert.equal(f.store.jobs().length,1);jobs.active=null;await jobs.pump();
  assert.equal(provider.startArgs,undefined);assert.equal(f.store.get('SELECT status FROM jobs WHERE id=?',job.id).status,'failed');assert.match(f.store.get('SELECT error FROM jobs WHERE id=?',job.id).error,/External shopping tools are unavailable/);
  assert.equal(jobs.taskTools('chat').some(tool=>tool.name==='meal_garden_save_receipt'),false);
  provider.externalShoppingUnavailable=false;jobs.household={...f.household,shopping:undefined};
  assert.throws(()=>jobs.enqueue({kind:'shopping',requestKey:'queued-shopping'}),/no shopping integration configured/);
 }finally{jobs.close();f.close();}
});


test('store refuses escaping database sidecars before SQLite opens or creates a file',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-sidecar-')),outside=fs.mkdtempSync(path.join(os.tmpdir(),'garden-sidecar-outside-'));try{
  fs.mkdirSync(path.join(root,'.runtime'));const file=path.join(root,'.runtime/garden.sqlite'),target=path.join(outside,'foreign-wal');fs.writeFileSync(target,'private bytes');fs.symlinkSync(target,file+'-wal');
  assert.throws(()=>new Store(file),/inside its household/);assert.equal(fs.existsSync(file),false);assert.equal(fs.readFileSync(target,'utf8'),'private bytes');
 }finally{fs.rmSync(root,{recursive:true,force:true});fs.rmSync(outside,{recursive:true,force:true});}
});
