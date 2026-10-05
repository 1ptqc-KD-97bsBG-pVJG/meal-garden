import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {EventEmitter} from 'node:events';
import {Store} from '../companion/store.mjs';
import {FoodGraph,NUTRIENTS} from '../companion/graph.mjs';
import {atomic} from '../companion/domain.mjs';
import {foodLogPacket,planningPacket,cookPacket,foodLog} from '../companion/context.mjs';
import {recordCapture,recordInterpretation,captureView} from '../companion/capture.mjs';
import {Jobs} from '../companion/jobs.mjs';
import {toolsForTask} from '../companion/agent-contract.mjs';
import {reflectPrompt} from '../companion/learning.mjs';

function fixture(){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-context-'));
 atomic(path.join(root,'household.json'),{schema_version:1,id:'home',name:'Test kitchen',person:{id:'owner',name:'You'},timezone:'America/Vancouver'});
 const store=new Store(path.join(root,'.runtime/garden.sqlite')),graph=new FoodGraph(store,{root});
 const values={kcal:380,protein_g:13,carbs_g:68,fat_g:7,fiber_g:10,sodium_mg:0,sat_fat_g:1,added_sugar_g:0};
 graph.upsertProduct({id:'quick-oats',name:'Quaker quick oats',brand:'Quaker',kind:'packaged',base_unit:'g',storage:'pantry',nutrition_basis:'per_100g',nutrition_source:'label_photo',...values},{idempotencyKey:'oats'});
 const lot=graph.addPantryItem({productId:'quick-oats'},{idempotencyKey:'lot'});
 graph.countPantryItem(lot.id,1000,'known','fixture',{idempotencyKey:'count'});
 graph.setPreference({kind:'constraint',subject:'bell peppers',statement:'Avoid bell peppers',stance:'never',isHard:true,source:'stated'},{idempotencyKey:'constraint'});
 graph.setPreference({kind:'taste',subject:'tofu',statement:'Prefer tofu',stance:'like',source:'stated'},{idempotencyKey:'taste'});
 graph.setPreference({kind:'process',subject:'appliance',statement:'Use the oven when effort is similar',source:'stated'},{idempotencyKey:'process'});
 const capturedAt='2026-10-04T08:00:00Z'; // October 4, 1 a.m. in the fixture timezone.
 const batch=graph.recordBatch({id:'oats-batch',title:'Overnight oats',recipeId:'overnight-oats',madeAt:'2026-10-04T02:00:00Z',yieldG:1000,yieldBasis:'measured',portions:5,recordedFrom:'chat',ingredients:[{productId:'quick-oats',pantryItemId:lot.id,name:'Quick oats',amount:500,grams:500,confidence:'known'}]},{idempotencyKey:'batch'});
 atomic(path.join(root,'recipes/overnight-oats/recipe.json'),{id:'overnight-oats',title:'Overnight oats',revision:1,readiness:'ready',ingredients:[{name:'Quick oats',amount:500,unit:'g'}],steps:[{title:'Mix',text:'Mix the oats.'}]});
 const id=crypto.randomUUID();recordCapture(store,path.join(root,'.runtime'),{id,kind:'meal',note:"404 grams of last night's overnight oats",phoneTime:capturedAt});
 return {root,store,graph,batch,id,capturedAt,values,close(){store.close();fs.rmSync(root,{recursive:true,force:true});}};
}
const interpretation=id=>({captureId:id,category:'meal',title:'Overnight oats',items:[{name:'Overnight oats',portion:'404 g',confidence:'high'}],nutrition:{calories:{low:500,high:700},protein_g:{low:12,high:30},carbs_g:{low:60,high:100},fat_g:{low:10,high:25},fiber_g:{low:4,high:12}},matchedRecipeId:'overnight-oats',overallConfidence:'high',questions:[],notes:''});

const waitFor=async predicate=>{for(let i=0;i<200&&!predicate();i++)await new Promise(r=>setTimeout(r,10));assert.ok(predicate(),'provider did not start');};

class FakeProvider extends EventEmitter{
 constructor(){super();this.responses=[];this.threadModels=new Map();}
 async thread(...args){this.threadArgs=args;return 'context-thread';}
 async start(...args){this.startArgs=args;return {turn:{id:'context-turn'}};}
 respond(id,result){this.responses.push({id,result});}
 reject(){}
 async cancel(){}
}

test('food-log packet finds matching batch and actual oats, uses local food day and recent recipe titles',()=>{
 const f=fixture();try{
  const c=f.store.conversation('Cooking','android_app');
  const msg=f.store.message(c.id,'user','Let’s make overnight-oats');f.store.run('UPDATE messages SET created=? WHERE id=?',f.capturedAt,msg.id);
  f.graph.recordIntake({captureId:'before',eatenAt:'2026-10-04T07:30:00Z',title:'Early snack',category:'snack',nutrition:{kcal:{low:50,high:60}}},{idempotencyKey:'before'});
  f.graph.recordIntake({captureId:'prior-day',eatenAt:'2026-10-04T06:30:00Z',title:'Previous local day',category:'meal'},{idempotencyKey:'prior-day'});
  f.graph.recordIntake({captureId:'later',eatenAt:'2026-10-04T09:00:00Z',title:'Future snack',category:'snack'},{idempotencyKey:'later'});
  const packet=foodLogPacket(f.store,f.root,{note:'overnight oats, 404 grams',capturedAt:f.capturedAt});
  assert.ok(packet.startsWith('## Hard constraints\n- Avoid bell peppers'));
  for(const value of ['Overnight oats','oats-batch','quick-oats','Quaker quick oats','label_photo','per 100 g','portions left 5','Intake on 2026-10-04','Early snack','Overnight oats [overnight-oats]'])assert.ok(packet.includes(value),value);
  assert.doesNotMatch(packet,/Future snack|Previous local day|Use the oven/);
  assert.match(packet,/Omitted: none/);assert.ok(packet.length<6000);
 }finally{f.close();}
});

test('packets stay bounded and disclose omissions; cooking gets process preferences and planning gets seven days',()=>{
 const f=fixture();try{
  for(let i=0;i<100;i++)f.graph.addAssumption({statement:`Unconfirmed item ${i} ${'detail '.repeat(80)}`},{idempotencyKey:`assume-${i}`});
  const planning=planningPacket(f.store,f.root),cooking=cookPacket(f.store,f.root,'overnight-oats');
  assert.ok(planning.length<=6000);assert.ok(cooking.length<=6000);
  assert.match(planning,/Omitted: Open assumptions: [1-9]/);assert.match(planning,/numeric targets unset/);assert.match(planning,/by urgency/);
  assert.equal((planning.match(/\d{4}-\d{2}-\d{2}: \d+ entries/g)||[]).length,7);
  assert.match(cooking,/Use the oven when effort is similar/);assert.match(cooking,/Quick oats: 500 g/);
  assert.throws(()=>cookPacket(f.store,f.root,'../profile'),/Invalid recipe/);
 }finally{f.close();}
});

test('linked weighed batch replaces model ranges with computed nutrition and rereading deducts only once',()=>{
 const f=fixture();try{
  const result=recordInterpretation(f.store,f.id,{...interpretation(f.id),batchId:'oats-batch',weightG:404},'linked');
  assert.equal(result.method,'computed');assert.equal(result.batchId,'oats-batch');
  assert.deepEqual(result.components,[{productId:'batch-product:oats-batch',grams:404}]);
  for(const key of NUTRIENTS){const publicKey=key==='kcal'?'calories':key==='sat_fat_g'?'saturated_fat_g':key;assert.equal(result.nutrition[publicKey].low,result.nutrition[publicKey].high);}
  assert.deepEqual(result.nutrition.calories,{low:767.6,high:767.6});
  assert.equal(f.graph.balance(f.batch.pantryItem.id).amount,596);
  recordInterpretation(f.store,f.id,{...interpretation(f.id),batchId:'oats-batch',weightG:404},'reread');
  assert.equal(f.graph.balance(f.batch.pantryItem.id).amount,596);
  assert.equal(foodLog(f.store,'2026-10-04').length,1);
  assert.equal(captureView(f.store,f.id).interpretation.intakeId,foodLog(f.store,'2026-10-04')[0].id);
 }finally{f.close();}
});

test('unlinked and incomplete linked entries keep ranges; nutrition labels do not consume stock',()=>{
 const f=fixture();try{
  const estimate=interpretation(f.id);
  const unlinked=recordInterpretation(f.store,f.id,estimate,'unlinked');
  assert.equal(unlinked.method,'estimated');assert.deepEqual(unlinked.nutrition.calories,estimate.nutrition.calories);
  f.graph.setProductNutrition('quick-oats',{fiber_g:null},{idempotencyKey:'missing'});
  const incomplete=recordInterpretation(f.store,f.id,{...estimate,components:[{productId:'quick-oats',grams:404}]},'incomplete');
  assert.equal(incomplete.method,'estimated');assert.deepEqual(incomplete.nutrition,unlinked.nutrition);
  const unknown=recordInterpretation(f.store,f.id,{...estimate,nutrition:null},'unknown');assert.equal(unknown.nutrition,null);
  const before=f.graph.pantryView().find(p=>p.product_id==='quick-oats').balance;
  recordInterpretation(f.store,f.id,{...estimate,category:'nutrition_label'},'label');
  assert.equal(f.graph.pantryView().find(p=>p.product_id==='quick-oats').balance,before);
  assert.throws(()=>recordInterpretation(f.store,f.id,{...estimate,category:'nutrition_label',components:[{productId:'quick-oats',grams:100}]},'bad-label'),/Only consumed/);
 }finally{f.close();}
});

test('uncertain batch ingredients preserve model ranges rather than collapsing evidence uncertainty',()=>{
 const f=fixture();try{
  const batch=f.graph.recordBatch({title:'Assumed oats',madeAt:f.capturedAt,recordedFrom:'food_log',yieldG:1000,ingredients:[{productId:'quick-oats',name:'Oats',amount:500,grams:500,confidence:'assumed'}]},{idempotencyKey:'assumed-batch'});
  const result=recordInterpretation(f.store,f.id,{...interpretation(f.id),batchId:batch.batch.id,weightG:404},'assumed');
  assert.equal(result.method,'estimated');assert.deepEqual(result.nutrition.calories,{low:500,high:700});
 }finally{f.close();}
});

test('failed interpretation rolls back graph writes, intake supersession and ledger changes',()=>{
 const f=fixture();try{
  recordInterpretation(f.store,f.id,interpretation(f.id),'original');
  const intake=f.store.get('SELECT id FROM intake'),events=f.store.get('SELECT COUNT(*) n FROM events').n;
  f.store.db.exec("CREATE TRIGGER fail_interpretation BEFORE INSERT ON events WHEN NEW.type='interpreted' BEGIN SELECT RAISE(ABORT,'forced event failure'); END");
  assert.throws(()=>recordInterpretation(f.store,f.id,{...interpretation(f.id),batchId:'oats-batch',weightG:404},'rollback'),/forced event/);
  assert.equal(f.store.get('SELECT COUNT(*) n FROM events').n,events);
  assert.equal(f.store.get('SELECT id FROM intake WHERE superseded_by_id IS NULL').id,intake.id);
  assert.equal(f.graph.balance(f.batch.pantryItem.id).amount,1000);
 }finally{f.close();}
});

test('prompt stores packet; food-log tools are enforced, while reflection records reactions and preferences',async()=>{
 const f=fixture(),provider=new FakeProvider(),jobs=new Jobs(f.store,provider,f.root);
 try{
  const job=jobs.enqueue({kind:'log_food',requestKey:'food',text:'Interpret this note',captureId:f.id},{internal:true});
  await waitFor(()=>provider.startArgs);
  assert.match(provider.startArgs[1],/oats-batch/);assert.equal(f.store.get('SELECT prompt FROM jobs WHERE id=?',job.id).prompt,provider.startArgs[1]);
  const call=(tool,args,id)=>jobs.request({id,method:'item/tool/call',params:{threadId:'context-thread',tool,arguments:args}});
  await call('meal_garden_get_product',{id:'quick-oats'},1);
  await call('meal_garden_count_pantry',{itemId:f.batch.pantryItem.id,amount:0,idempotencyKey:'denied',confidence:'known'},2);
  assert.deepEqual(provider.responses.map(r=>r.result.success),[true,false]);
  assert.equal(f.graph.balance(f.batch.pantryItem.id).amount,1000);
  assert.equal(toolsForTask('log_food').some(t=>t.name==='meal_garden_save_recipe'),false);
  jobs.finish('completed');
  provider.startArgs=null;
  const reflection=jobs.enqueue({kind:'reflect',requestKey:'reflect',text:reflectPrompt('You: tasted good')},{internal:true});
  await waitFor(()=>provider.startArgs);
  await call('meal_garden_record_reaction',{recipeId:'overnight-oats',notes:'Tasted good',idempotencyKey:'reaction'},3);
  await call('meal_garden_set_preference',{kind:'taste',subject:'oats',statement:'Likes oats',source:'inferred',confidence:'assumed',evidence:['reaction'],idempotencyKey:'preference'},4);
  await call('meal_garden_record_batch',{title:'Forbidden',madeAt:f.capturedAt,ingredients:[],idempotencyKey:'denied-reflect'},5);
  assert.deepEqual(provider.responses.slice(-3).map(r=>r.result.success),[true,true,false]);
  assert.equal(f.store.get('SELECT COUNT(*) n FROM reactions').n,1);
  jobs.finish('completed');assert.equal(f.store.get('SELECT status FROM jobs WHERE id=?',reflection.id).status,'completed');
 }finally{jobs.close();f.close();}
});

test('recipe writes reject hard conflicts as tool errors and can be fixed before retrying',async()=>{
 const f=fixture(),provider=new FakeProvider(),jobs=new Jobs(f.store,provider,f.root);
 try{
  jobs.enqueue({kind:'chat',requestKey:'recipe',text:'Make a recipe'});
  await waitFor(()=>provider.startArgs);
  const recipe={id:'new-bowl',title:'Bowl',ingredients:[{name:'Bell peppers',amount:1,unit:'count'}],steps:[{title:'Cut',text:'Cut.'}],readiness:'ready'};
  const call=()=>jobs.request({id:10,method:'item/tool/call',params:{threadId:'context-thread',tool:'meal_garden_save_recipe',arguments:{recipeJson:JSON.stringify(recipe)}}});
  await call();assert.equal(provider.responses.at(-1).result.success,false);assert.match(provider.responses.at(-1).result.contentItems[0].text,/Hard ingredient conflicts/);
  assert.equal(fs.existsSync(path.join(f.root,'recipes/new-bowl/recipe.json')),false);
  recipe.ingredients[0].name='Carrots';await call();assert.equal(provider.responses.at(-1).result.success,true);
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.root,'recipes/new-bowl/recipe.json'))).ingredients[0].name,'Carrots');
 }finally{jobs.close();f.close();}
});

test('weighed actual product components compute all known nutrients and keep their source',()=>{
 const f=fixture();try{
  const result=recordInterpretation(f.store,f.id,{...interpretation(f.id),components:[{productId:'quick-oats',grams:100}],weightG:100},'product-link');
  assert.equal(result.method,'computed');assert.deepEqual(result.nutrition.calories,{low:380,high:380});
  assert.deepEqual(result.nutrition.protein_g,{low:13,high:13});
  const intake=foodLog(f.store,'2026-10-04')[0];assert.equal(intake.components[0].product_id,'quick-oats');
  assert.equal(intake.nutrition.sources[0].nutritionSource,'label_photo');
 }finally{f.close();}
});

test('newest graph preferences supersede legacy preference notes in compiled prompts',async()=>{
 const f=fixture(),provider=new FakeProvider(),jobs=new Jobs(f.store,provider,f.root);
 try{
  f.store.record('learned','learned:preference:tofu',{area:'preference',subject:'tofu',note:'Outdated taste note',evidence:'legacy'});
  jobs.enqueue({kind:'chat',requestKey:'preferences',text:'Plan dinner'});
  await waitFor(()=>provider.startArgs);
  assert.match(provider.startArgs[1],/Prefer tofu/);assert.doesNotMatch(provider.startArgs[1],/Outdated taste note/);
 }finally{jobs.close();f.close();}
});


test('an estimated finished yield does not turn known ingredient weights into exact serving nutrition',()=>{
 const f=fixture();try{
  const batch=f.graph.recordBatch({title:'Estimated yield oats',madeAt:f.capturedAt,recordedFrom:'food_log',yieldG:1000,yieldBasis:'estimated',ingredients:[{productId:'quick-oats',name:'Oats',pantryItemId:f.batch.ingredients[0].pantry_item_id,amount:500,grams:500,confidence:'known'}]},{idempotencyKey:'estimated-yield'});
  const result=recordInterpretation(f.store,f.id,{...interpretation(f.id),batchId:batch.batch.id,weightG:404},'estimated-yield-intake');
  assert.equal(result.method,'estimated');assert.deepEqual(result.nutrition.calories,{low:500,high:700});
 }finally{f.close();}
});
