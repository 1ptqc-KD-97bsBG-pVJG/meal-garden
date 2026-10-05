import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {Store} from '../companion/store.mjs';
import {FoodGraph} from '../companion/graph.mjs';
import {atomic,hash,now} from '../companion/domain.mjs';
import {reviewShopping,reviewedShopping,tripView} from '../companion/shopping.mjs';
import {createCompanion} from '../companion/server.mjs';
import {EventEmitter} from 'node:events';
const key=idempotencyKey=>({idempotencyKey});
function fixture(t){const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-release-'));const store=new Store(path.join(root,'garden.sqlite'));const graph=new FoodGraph(store);t.after(()=>{store.close();fs.rmSync(root,{recursive:true,force:true});});return {root,store,graph};}
function receipt(graph,fingerprint){return graph.importReceipt({fingerprint,date:'2026-09-14',items:[{name:'Yogurt',packages:1,product:{size_amount:500,base_unit:'g'}}]});}
test('observed food uses the shared ledger, preserves unknown amounts and retries once',t=>{
 const {graph,store}=fixture(t);
 const a=graph.observePantry({name:'Beans',baseUnit:'g',location:'pantry',amount:null},key('observe'));
 assert.deepEqual(graph.observePantry({name:'Beans',baseUnit:'g',location:'pantry',amount:null},key('observe')),a);
 assert.deepEqual(graph.balance(a.pantryItem.id),{amount:null,basis:'unknown'});
 assert.equal(a.pantryItem.condition,null); // Presence/amount do not establish food condition.
 const b=graph.observePantry({productId:a.product.id,location:'fridge',amount:120},key('weighed'));
 assert.deepEqual(graph.balance(b.pantryItem.id),{amount:120,basis:'known'});
 assert.equal(graph.findProducts('Beans').length,1);
 assert.equal(store.get('SELECT count(*) n FROM receipts').n,0);
 assert.deepEqual(store.all('PRAGMA foreign_key_check'),[]);
});
test('backfilled purchase amounts are estimates until an actual count, even after a condition mark',t=>{
 const {graph}=fixture(t);
 const a=graph.importReceipt({fingerprint:'backfilled',date:'2026-09-14',items:[{name:'Mushrooms',packages:1,product:{size_amount:245}}]},{actor:'migration:food-graph'});
 const lot=a.pantryItems[0].id;
 assert.deepEqual(graph.balance(lot),{amount:245,basis:'assumed'});
 graph.setCondition(lot,'fine',key('fine'));
 assert.equal(graph.balance(lot).basis,'assumed');
 graph.countPantryItem(lot,110,'known','owner',key('count'));
 assert.deepEqual(graph.balance(lot),{amount:110,basis:'known'});
});
test('ambiguous intake creates a visible correction; allocation, rereading and later counts stay sound',t=>{
 const {graph,store}=fixture(t);const a=receipt(graph,'one'),b=receipt(graph,'two');
 const input={captureId:'yogurt',eatenAt:'2026-10-04T15:00:00Z',components:[{productId:a.products[0].id,grams:178,confidence:'known'}]};
 const intake=graph.recordIntake(input,key('eat'));
 assert.equal(intake.movements.length,0);assert.equal(intake.assumptions.length,1);
 const visible=graph.openAssumptions()[0];assert.equal(visible.kind,'pantry');assert.equal(visible.componentId,intake.components[0].id);
 graph.countPantryItem(a.pantryItems[0].id,250,'known','owner',{...key('recount'),occurredAt:'2026-10-04T18:00:00Z'});
 const allocation=graph.allocateIntake(visible.componentId,a.pantryItems[0].id,key('allocate'));
 assert.equal(allocation.balance.amount,250); // historical intake cannot reduce a later physical count
 assert.deepEqual(graph.allocateIntake(visible.componentId,a.pantryItems[0].id,key('allocate')),allocation);
 assert.equal(graph.balance(b.pantryItems[0].id).amount,500);
 assert.throws(()=>graph.allocateIntake(visible.componentId,b.pantryItems[0].id,key('wrong')),/cannot be allocated/);
 const reread=graph.recordIntake({...input,components:[{productId:a.products[0].id,pantryItemId:a.pantryItems[0].id,grams:200,confidence:'known'}]},key('reread'));
 assert.equal(reread.reversals.length,1);assert.equal(graph.balance(a.pantryItems[0].id).amount,250);
 assert.deepEqual(store.all('PRAGMA foreign_key_check'),[]);
});
test('overdraw retains the discrepancy with a reversible assumption and authoritative recount',t=>{
 const {graph}=fixture(t);const a=receipt(graph,'small'),lot=a.pantryItems[0].id;
 const eat=graph.recordIntake({captureId:'overdraw',components:[{productId:a.products[0].id,pantryItemId:lot,grams:600,confidence:'known'}]},key('overdraw'));
 assert.equal(graph.balance(lot).amount,-100);assert.equal(eat.assumptions.length,1);
 graph.resolveAssumptions(eat.assumptions.map(a=>a.id),'corrected',key('undo'));
 assert.equal(graph.balance(lot).amount,500);
 graph.countPantryItem(lot,30,'known','owner',key('real-count'));
 assert.equal(graph.balance(lot).amount,30);
});
test('conditional shopping requires explicit decisions; excludes enough-at-home rows and rejects stale reviews',t=>{
 const {root,store}=fixture(t);
 atomic(path.join(root,'data/plans/plan.json'),{id:'current',status:'active',meals:[{date:'9999-01-01'}]});
 const trip={id:'trip',plan_id:'current',buy:[{name:'Beans',quantity:2,unit:'cans',purchase_condition:'if short',comment:'Recipe use: bowls'},{name:'Rice',quantity:1,unit:'bag',purchase_condition:'if missing'}]};
 atomic(path.join(root,'data/shopping/current-trip.json'),trip);
 assert.throws(()=>reviewedShopping(root),/Choose Buy/);
 const input={...key('review'),reviewKey:tripView(root).reviewKey,decisions:['buy','have']};
 const saved=reviewShopping(root,store,input);
 assert.deepEqual(reviewShopping(root,store,input),saved);
 const reviewed=reviewedShopping(root);assert.equal(reviewed.buy.length,1);assert.equal(reviewed.buy[0].comment,trip.buy[0].comment);
 assert.throws(()=>reviewShopping(root,store,{...input,idempotencyKey:'stale'}),/changed/);
 atomic(path.join(root,'data/plans/plan.json'),{id:'other',status:'active',meals:[{date:'9999-01-01'}]});
 assert.throws(()=>reviewedShopping(root),/current plan/);
});
test('authenticated add-food and allocation routes reject anonymous writes and share the canonical snapshot',async t=>{
 const {root}=fixture(t);atomic(path.join(root,'household.json'),{schema_version:1,id:'home',name:'Test kitchen',person:{id:'owner',name:'You'},timezone:'UTC',shopping:{list_service:'samsung_food',list_name:'Sample List',store:{chain:'Sample Market'}}});fs.mkdirSync(path.join(root,'recipes'),{recursive:true});
 const provider=new EventEmitter();provider.close=()=>{};
 const app=createCompanion({root,provider});t.after(()=>app.close());app.jobs.lookupProducts=async()=>({});
 app.store.run('INSERT INTO devices VALUES(?,?,?)',hash('test-only'),'Fixture',now());
 await new Promise(r=>app.server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${app.server.address().port}`;
 const data={name:'Lentils',location:'pantry',amount:60,baseUnit:'g',idempotencyKey:'api-add'};
 assert.equal((await fetch(base+'/api/pantry/add',{method:'POST',body:JSON.stringify(data)})).status,401);
 const headers={Authorization:'Bearer test-only','Content-Type':'application/json'};
 const first=await (await fetch(base+'/api/pantry/add',{method:'POST',headers,body:JSON.stringify(data)})).json();
 const repeat=await (await fetch(base+'/api/pantry/add',{method:'POST',headers,body:JSON.stringify(data)})).json();assert.deepEqual(first,repeat);
 const snap=await (await fetch(base+'/api/snapshot',{headers})).json();assert.equal(snap.pantry[0].balance,60);assert.equal(snap.pantry[0].basis,'known');
});
test('historical gap review adds only questions, keeps ledger values, and is repeatable',t=>{
 const {graph,store}=fixture(t);const a=receipt(graph,'historic');
 const intake=graph.recordIntake({captureId:'old',eatenAt:'2026-10-02T15:00:00Z',components:[{name:'Yogurt',productId:a.products[0].id,grams:178}]},key('old'));
 // Simulate an earlier implementation's unallocated, unaudited component.
 store.run('UPDATE intake_components SET pantry_item_id=NULL WHERE id=?',intake.components[0].id);
 const before=store.all('SELECT * FROM pantry_movements');
 assert.deepEqual(graph.reviewPantryGaps(),{added:1});
 assert.deepEqual(graph.reviewPantryGaps(),{added:0});
 assert.deepEqual(store.all('SELECT * FROM pantry_movements'),before);
 assert.equal(graph.openAssumptions().filter(a=>a.componentId===intake.components[0].id).length,1);
});
test('unreviewed and queued-stale shopping never reaches a provider call',async t=>{
 const {root}=fixture(t);atomic(path.join(root,'household.json'),{schema_version:1,id:'home',name:'Test kitchen',person:{id:'owner',name:'You'},timezone:'UTC',shopping:{list_service:'samsung_food',list_name:'Sample List',store:{chain:'Sample Market'}}});fs.mkdirSync(path.join(root,'recipes'),{recursive:true});
 atomic(path.join(root,'data/plans/current.json'),{id:'current',status:'active',meals:[{date:'9999-01-01'}]});
 const file=path.join(root,'data/shopping/current-trip.json');
 atomic(file,{id:'trip',plan_id:'current',buy:[{name:'Beans',purchase_condition:'if short'}]});
 const provider=new EventEmitter();provider.close=()=>{};provider.thread=async()=>{throw new Error('Provider must not be called');};
 const app=createCompanion({root,provider});t.after(()=>app.close());
 assert.throws(()=>app.jobs.enqueue({kind:'shopping',requestKey:'unreviewed'}),/Choose Buy/);
 atomic(file,{id:'trip',plan_id:'current',buy:[{name:'Beans',purchase_condition:'if short',purchase_decision:'buy'}]});
 const job=app.jobs.enqueue({kind:'shopping',requestKey:'stale'});
 atomic(file,{id:'changed',plan_id:'current',buy:[]});
 await new Promise(r=>setTimeout(r,30));
 const saved=app.store.get('SELECT * FROM jobs WHERE id=?',job.id);
 assert.equal(saved.status,'failed');assert.match(saved.error,/changed while queued/);assert.equal(saved.thread_id,null);
});
test('shopping review recovers a committed file if its database transaction was interrupted',t=>{
 const {root,store}=fixture(t);
 const file=path.join(root,'data/shopping/current-trip.json');
 atomic(file,{id:'trip',plan_id:'current',buy:[{name:'Beans',purchase_condition:'if short'}]});
 const input={...key('recover-review'),reviewKey:tripView(root).reviewKey,decisions:['buy']};
 const saved=reviewShopping(root,store,input);
 store.run('DELETE FROM idempotency_keys WHERE key=?',input.idempotencyKey); // simulate a lost database commit
 assert.deepEqual(reviewShopping(root,store,input),saved);
 assert.equal(tripView(root).buy[0].purchase_decision,'buy');
});
