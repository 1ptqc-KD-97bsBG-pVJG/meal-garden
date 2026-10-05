import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {Store} from '../companion/store.mjs';
import {FoodGraph,NUTRIENTS,convertQuantity} from '../companion/graph.mjs';

function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-graph-'));
 const store=new Store(path.join(root,'garden.sqlite'));store.db.exec('PRAGMA foreign_keys=ON');
 t.after(()=>{store.close();fs.rmSync(root,{recursive:true,force:true});});
 return {store,graph:new FoodGraph(store),root};
}
const key=idempotencyKey=>({idempotencyKey});
function purchase(graph,name,size=245,extra={}) {
 return graph.importReceipt({fingerprint:name,date:'2026-09-14',store:'Test',items:[{name,packages:1,product:{base_unit:'g',size_amount:size,...extra}}]});
}
const fullNutrition={nutrition_basis:'per_100g',nutrition_source:'label_photo',...Object.fromEntries(NUTRIENTS.map(n=>[n,n==='kcal'?100:n==='protein_g'?10:2]))};

test('schema follows DBML; all enums reject invalid values; recipes and captures remain file/event backed',t=>{
 const {store}=fixture(t);
 assert.equal(store.get('SELECT household_id FROM persons WHERE id=?','owner').household_id,'home');
 assert.equal(store.get("SELECT count(*) AS n FROM sqlite_master WHERE type='table' AND name IN ('recipes','captures')").n,0);
 assert.throws(()=>store.run("INSERT INTO products(id,name,kind,base_unit) VALUES('bad','bad','invalid','g')"),/CHECK/);
 assert.throws(()=>store.run("INSERT INTO products(id,name,kind,base_unit) VALUES('bad','bad','generic','kg')"),/CHECK/);
});

test('mushroom ledger: unknown use, absolute count, recipe use ends at zero',t=>{
 const {graph,store}=fixture(t);const receipt=purchase(graph,'Crimini mushrooms'),item=receipt.pantryItems[0];
 assert.deepEqual(graph.balance(item.id),{amount:245,basis:'known'});
 graph.addAssumption({statement:'Some mushrooms went into the bowl',movements:[{pantryItemId:item.id,amount:null}]},{...key('bowl'),occurredAt:'2026-09-26T21:00:00Z'});
 assert.deepEqual(graph.balance(item.id),{amount:null,basis:'assumed'});
 graph.countPantryItem(item.id,110,'assumed','owner',{...key('count'),occurredAt:'2026-10-02T21:42:00Z'});
 graph.recordBatch({title:'Mushroom pasta',madeAt:'2026-10-02T22:00:00Z',ingredients:[{name:'Crimini mushrooms',productId:item.product_id,pantryItemId:item.id,amount:110,confidence:'assumed'}]},key('batch'));
 assert.deepEqual(graph.balance(item.id),{amount:0,basis:'assumed'});
 assert.ok(store.all('SELECT * FROM pantry_movements').every(m=>m.event_id&&m.confidence));
 assert.deepEqual(store.all('PRAGMA foreign_key_check'),[]);
});

test('unknown opening amount stays unknown until a count; zero is an observed count',t=>{
 const {graph}=fixture(t);const item=purchase(graph,'Unknown-sized spinach',null).pantryItems[0];
 assert.deepEqual(graph.balance(item.id),{amount:null,basis:'unknown'});
 graph.countPantryItem(item.id,60,'known','owner',key('spinach-count'));
 assert.deepEqual(graph.balance(item.id),{amount:60,basis:'known'});
 graph.addAssumption({statement:'Unknown use',movements:[{pantryItemId:item.id,amount:null}]},key('use'));
 assert.equal(graph.balance(item.id).amount,null);
 graph.countPantryItem(item.id,0,'known','owner',key('gone'));
 assert.deepEqual(graph.balance(item.id),{amount:0,basis:'known'});
});

test('receipt fingerprint and caller keys deduplicate; package weights convert to base units',t=>{
 const {graph,store}=fixture(t);
 const input={fingerprint:'original',store:'Test',date:'2026-09-14',items:[{name:'Spaghetti - 1 lb',quantity:'2'},{name:'Paper towels',quantity:'1'}]};
 const first=graph.importReceipt(input,key('a'));const events=store.get('SELECT count(*) n FROM events').n;
 assert.deepEqual(graph.importReceipt(input,key('a')),first);
 assert.deepEqual(graph.importReceipt(input,key('another caller')),first);
 assert.equal(store.get('SELECT count(*) n FROM events').n,events);
 assert.equal(store.get('SELECT count(*) n FROM purchases').n,1);
 assert.equal(first.failedLines[0].reason,'non-food');
 assert.equal(graph.balance(first.pantryItems[0].id).amount,907.18474);
 assert.equal(convertQuantity(1,'cup','g'),null);
});

test('writes, originating event and idempotency result roll back together',t=>{
 const {graph,store}=fixture(t);
 const n=store.get('SELECT count(*) n FROM events').n;
 assert.throws(()=>graph.importReceipt({fingerprint:'bad',items:[{name:'good',packages:1},{name:'bad',packages:-1}]},key('bad')),/nonnegative/);
 for(const table of ['products','receipts','purchases','pantry_items','pantry_movements','idempotency_keys'])assert.equal(store.get(`SELECT count(*) n FROM ${table}`).n,0);
 assert.equal(store.get('SELECT count(*) n FROM events').n,n);
});

test('batch picks the oldest active lot and writes assumptions, ingredient use and nutrition snapshot',t=>{
 const {graph,store}=fixture(t);const r=purchase(graph,'Tofu',500,fullNutrition);
 const batch=graph.recordBatch({title:'Tofu bowl',madeAt:'2026-10-02T22:00:00Z',yieldG:800,yieldBasis:'measured',portions:4,ingredients:[{name:'Tofu',productId:r.products[0].id,amount:200,grams:200,confidence:'assumed'}]},key('tofu-batch'));
 assert.equal(batch.assumptions.length,1);
 assert.equal(batch.movements[0].reason,'assumed_use');
 assert.equal(batch.ingredients[0].pantry_item_id,r.pantryItems[0].id);
 assert.equal(graph.balance(r.pantryItems[0].id).amount,300);
 assert.deepEqual(batch.nutrition_total.kcal,{low:160,high:240});
 assert.equal(batch.product.kcal,25);
 graph.setProductNutrition(r.products[0].id,{protein_g:20},key('new-label'));
 assert.deepEqual(graph._get('batches',batch.batch.id).nutrition_total.protein_g,{low:16,high:24});
 assert.deepEqual(graph.computeBatchNutrition(batch.batch.id,key('recompute')).protein_g,{low:32,high:48});
 assert.throws(()=>graph.setProductNutrition(r.products[0].id,{base_unit:'ml'},key('unit-change')),/base_unit/);
 assert.throws(()=>graph.upsertProduct({...r.products[0],base_unit:'count'},key('unit-change-2')),/base_unit/);
 assert.deepEqual(store.all('PRAGMA foreign_key_check'),[]);
});

test('missing ingredient nutrition yields null for that nutrient, and ml requires density',t=>{
 const {graph}=fixture(t);
 const a=purchase(graph,'Beans',200,fullNutrition);const b=purchase(graph,'Unknown carrots',200,{...fullNutrition,protein_g:null});
 const batch=graph.recordBatch({title:'Soup',yieldG:600,ingredients:[a,b].map(r=>({name:r.products[0].name,productId:r.products[0].id,pantryItemId:r.pantryItems[0].id,amount:100,confidence:'known'}))},key('soup'));
 assert.equal(batch.nutrition_total.protein_g,null);assert.deepEqual(batch.nutrition_total.kcal,{low:200,high:200});
 const p=graph.upsertProduct({name:'Milk',base_unit:'ml',nutrition_basis:'per_100ml',kcal:50},key('milk'));
 const logged=graph.recordIntake({captureId:'milk-capture',components:[{productId:p.id,name:'Milk',amount:100,confidence:'known'}]},key('milk-intake'));
 assert.equal(logged.intake.nutrition.kcal,null);
});

test('intake reread reverses the old deduction; snapshots and retries stay stable',t=>{
 const {graph,store}=fixture(t);const r=purchase(graph,'Yogurt',1000,fullNutrition),item=r.pantryItems[0];
 const first=graph.recordIntake({captureId:'capture',person:'owner',eatenAt:'2026-10-03T16:26:00Z',title:'Yogurt bowl',components:[{pantryItemId:item.id,name:'Yogurt',amount:178,grams:178,confidence:'known'}]},key('intake-v1'));
 assert.equal(first.intake.method,'computed');assert.equal(graph.balance(item.id).amount,822);
 const correctedInput={captureId:'capture',eatenAt:'2026-10-03T16:26:00Z',components:[{pantryItemId:item.id,name:'Yogurt',amount:100,grams:100,confidence:'known'}]};
 const second=graph.recordIntake(correctedInput,key('intake-v2'));
 assert.equal(graph.balance(item.id).amount,900);
 assert.equal(second.reversals.length,1);assert.equal(second.reversals[0].reverses_movement_id,first.movements[0].id);
 assert.equal(graph._get('intake',first.intake.id).superseded_by_id,second.intake.id);
 const count=store.get('SELECT count(*) n FROM pantry_movements').n;
 assert.deepEqual(graph.recordIntake(correctedInput,key('intake-v2')),second);
 assert.equal(store.get('SELECT count(*) n FROM pantry_movements').n,count);
 graph.setProductNutrition(item.product_id,{kcal:200},key('changed-kcal'));
 assert.deepEqual(graph._get('intake',second.intake.id).nutrition.kcal,{low:100,high:100});
});

test('rereading an unknown-size intake restores knowledge and does not reverse a later stocktake',t=>{
 const {graph}=fixture(t);const item=purchase(graph,'Rice',1000,fullNutrition).pantryItems[0];
 graph.recordIntake({captureId:'unknown',eatenAt:'2026-10-01',components:[{name:'Rice',pantryItemId:item.id}]},key('unknown-intake'));
 assert.equal(graph.balance(item.id).amount,null);
 graph.recordIntake({captureId:'unknown',eatenAt:'2026-10-01',components:[{name:'Rice',pantryItemId:item.id,amount:100,confidence:'known'}]},key('known-intake'));
 assert.equal(graph.balance(item.id).amount,900);
 graph.countPantryItem(item.id,200,'known','owner',{...key('later-count'),occurredAt:'2026-10-02'});
 graph.recordIntake({captureId:'unknown',eatenAt:'2026-10-01',components:[{name:'Rice',pantryItemId:item.id,amount:150,confidence:'known'}]},key('third-read'));
 assert.equal(graph.balance(item.id).amount,200);
});

test('partial transfer uses two linked movements and inherits age; whole-lot move changes location',t=>{
 const {graph,store}=fixture(t);const item=purchase(graph,'Broccoli',1000).pantryItems[0];
 const split=graph.transfer(item.id,400,'freezer',key('freeze-half'));
 assert.equal(split.item.split_from_id,item.id);assert.equal(split.item.created_at,item.created_at);
 assert.equal(graph.balance(item.id).amount,600);assert.equal(split.balance.amount,400);
 assert.equal(new Set(split.movements.map(m=>m.transfer_id)).size,1);
 const n=store.get('SELECT count(*) n FROM pantry_movements').n;
 const whole=graph.transfer(item.id,600,'fridge',key('move-whole'));
 assert.equal(whole.wholeLot,true);assert.equal(whole.item.location,'fridge');
 assert.equal(store.get('SELECT count(*) n FROM pantry_movements').n,n);
 assert.throws(()=>graph.transfer(item.id,1000,'freezer',key('overdraw')),/exceeds/);
});

test('correcting assumptions appends reversals once; a later count is authoritative',t=>{
 const {graph,store}=fixture(t);const item=purchase(graph,'Mushrooms',245).pantryItems[0];
 const a=graph.addAssumption({statement:'Mushrooms used',movements:[{pantryItemId:item.id,amount:100}]},{...key('assume'),occurredAt:'2026-09-26'});
 assert.equal(graph.balance(item.id).amount,145);
 const correction=graph.resolveAssumptions([a.id],'corrected',key('correct'));
 assert.equal(correction.reversals.length,1);assert.equal(graph.balance(item.id).amount,245);
 graph.resolveAssumptions([a.id],'corrected',key('correct-again'));
 assert.equal(store.get("SELECT count(*) n FROM pantry_movements WHERE reason='reversal'").n,1);
 const b=graph.addAssumption({statement:'Unknown later use',movements:[{pantryItemId:item.id,amount:null}]},{...key('assume2'),occurredAt:'2026-09-27'});
 graph.countPantryItem(item.id,20,'known','owner',{...key('stocktake'),occurredAt:'2026-09-28'});
 graph.resolveAssumptions([b.id],'corrected',key('correct2'));
 assert.deepEqual(graph.balance(item.id),{amount:20,basis:'known'});
 assert.equal(graph.openAssumptions().length,0);
});

test('preferences supersede; only hard never/avoid word matches are violations',t=>{
 const {graph}=fixture(t);
 const first=graph.setPreference({kind:'constraint',subject:'bell pepper',stance:'never',isHard:true,statement:'No bell pepper',source:'imported',confidence:'assumed'},key('pepper'));
 assert.equal(graph.openAssumptions().length,1);
 assert.equal(graph.violations('owner',['red bell pepper','peppercorns']).length,1);
 assert.equal(graph.violations('owner',['green bell peppers']).length,1);
 assert.equal(graph.violations('owner',['peppercorns']).length,0);
 const second=graph.setPreference({kind:'constraint',subject:'Bell Pepper',stance:'like',statement:'Bell pepper is fine'},key('pepper-change'));
 assert.equal(graph._get('preferences',first.id).superseded_by_id,second.id);
 assert.equal(graph.currentPreferences('owner').length,1);assert.deepEqual(graph.violations('owner',['bell pepper']),[]);
 graph.setPreference({kind:'taste',subject:'tofu',stance:'love',statement:'Prefers tofu'},key('tofu-pref'));
 assert.equal(graph.findProducts('tofu').length,0);
 const p=graph.upsertProduct({name:'Plain yogurt',brand:"Nancy's",kind:'packaged',matchWords:['probiotic']},key('nancy'));
 assert.equal(graph.findProducts('nancy probiotic')[0].id,p.id);assert.deepEqual(graph.findProducts('yog'),[]);
});

test('tossing an unknown lot records waste and a zero count; batch ranges survive linked intake',t=>{
 const {graph}=fixture(t);const unknown=purchase(graph,'Unknown spinach',null).pantryItems[0];
 assert.equal(graph.tossPantryItem(unknown.id,key('toss')).balance.amount,0);
 const tofu=purchase(graph,'Tofu for ranges',500,fullNutrition);
 const b=graph.recordBatch({title:'Tofu batch',yieldG:400,yieldBasis:'measured',ingredients:[{productId:tofu.products[0].id,name:'Tofu for ranges',amount:200,confidence:'assumed'}]},key('ranged-batch'));
 const intake=graph.recordIntake({captureId:'batch-capture',components:[{name:'Tofu batch',pantryItemId:b.pantryItem.id,grams:100,confidence:'known'}]},key('batch-intake'));
 assert.deepEqual(intake.intake.nutrition.kcal,{low:40,high:60});
 assert.equal(graph.balance(b.pantryItem.id).amount,300);
});

// Integration tests remain confined to synthetic files and temporary databases.
import {EventEmitter} from 'node:events';
import {atomic,hash} from '../companion/domain.mjs';
import {createCompanion} from '../companion/server.mjs';


function dataFixture(root) {
 atomic(path.join(root,'recipes/r/recipe.json'),{id:'r',revision:1,title:'Test bowl',readiness:'ready',ingredients:[{id:'rice',name:'Brown rice',amount:100,unit:'g'}],steps:[{title:'Cook',text:'Cook the rice'}]});
 atomic(path.join(root,'data/receipts/r.json'),{id:'r',store:'Test',date:'2026-09-14',items:[{name:'Brown rice',quantity:'1 × 1 lb'},{name:'Unknown mushroom size',quantity:'1'},{name:'Deodorant',quantity:'1'}]});
 atomic(path.join(root,'data/observations/o.json'),{id:'o',item:'Unknown mushroom size',createdAt:'2026-09-15T20:00:00Z',quantityState:'none',condition:'unchecked',location:'fridge'});
 atomic(path.join(root,'data/cooking/c.json'),{id:'c',recipeId:'r',recipeRevision:1,createdAt:'2026-09-16T20:00:00Z',note:'Enjoyed it'});
 atomic(path.join(root,'profile/owner.json'),{preferences:[{value:'Prefers rice bowls',source:'previous profile/FOOD_PREFERENCES.md'}],exclude:[{value:'Bell peppers',basis:'Historical exclusion; reason not confirmed'}]});
 atomic(path.join(root,'data/feedback/owner-favorites.json'),{records:[{recipe_id:'r',date:'2026-09-16',response:'Yummy',status:'liked'}]});

}

test('phone graph routes require authentication and keys; snapshot preserves old fields and includes shared graph views',async t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-graph-api-'));dataFixture(root);
 class Provider extends EventEmitter {close(){}}
 const app=createCompanion({root,provider:new Provider()});
 t.after(()=>{app.close();fs.rmSync(root,{recursive:true,force:true});});
 app.store.run('INSERT INTO devices(hash,name,created) VALUES(?,?,?)',hash('fixture-only'),'Fixture','now');
 const receipt=purchase(app.graph,'Tofu',500,fullNutrition);const itemId=receipt.pantryItems[0].id;
 await new Promise(resolve=>app.server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${app.server.address().port}`;
 const req=async(route,input,auth=true)=>{
  const response=await fetch(base+route,{method:input==null?'GET':'POST',headers:{...(auth?{Authorization:'Bearer fixture-only'}:{}),'Content-Type':'application/json'},body:input==null?undefined:JSON.stringify(input)});
  return {status:response.status,body:await response.json()};
 };
 const writes=[
  ['/api/pantry/count',{itemId,amount:400}],
  ['/api/pantry/condition',{itemId,condition:'use_soon'}],
  ['/api/pantry/transfer',{itemId,amount:100,toLocation:'freezer'}],
  ['/api/batches',{title:'API bowl',recordedFrom:'cook_mode',yieldG:400,yieldBasis:'measured',ingredients:[{name:'Tofu',pantryItemId:itemId,amount:100,confidence:'known'}]}],
  ['/api/reactions',{notes:'Good',rating:8}],
  ['/api/preferences',{kind:'taste',subject:'tofu',stance:'love',statement:'Likes tofu'}],
  ['/api/pantry/toss',{itemId}],
 ];
 for(const [index,[route,input]] of writes.entries()) {
  assert.equal((await req(route,input,false)).status,401);
  assert.equal((await req(route,input)).status,400);
  const first=await req(route,{...input,idempotencyKey:`phone-${index}`});assert.equal(first.status,201,JSON.stringify(first.body));
  const count=app.store.get('SELECT count(*) n FROM events').n;
  assert.deepEqual(await req(route,{...input,idempotencyKey:`phone-${index}`}),first);
  assert.equal(app.store.get('SELECT count(*) n FROM events').n,count);
 }
 const a=app.graph.addAssumption({statement:'An assumption'},key('phone-assumption'));
 assert.equal((await req('/api/assumptions/resolve',{ids:[a.id],status:'confirmed'},false)).status,401);
 assert.equal((await req('/api/assumptions/resolve',{ids:[a.id],status:'confirmed'})).status,400);
 assert.equal((await req('/api/assumptions/resolve',{ids:[a.id],status:'confirmed',idempotencyKey:'resolve-phone'})).status,201);
 assert.equal((await req('/api/snapshot',null,false)).status,401);
 const snapshot=await req('/api/snapshot');assert.equal(snapshot.status,200);
 for(const field of ['recipes','captures','inventory','kitchenReset','pantry','batches','assumptions','preferences'])assert.ok(field in snapshot.body);
 assert.equal(snapshot.body.pantry.find(i=>i.id===itemId).balance,0);
 assert.equal(snapshot.body.batches.length,1);assert.equal(snapshot.body.preferences[0].subject,'tofu');
});
