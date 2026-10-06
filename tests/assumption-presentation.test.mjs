import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {Store} from '../companion/store.mjs';
import {FoodGraph} from '../companion/graph.mjs';

function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-presentation-'));
 const store=new Store(path.join(root,'garden.sqlite'));
 t.after(()=>{store.close();fs.rmSync(root,{recursive:true,force:true});});
 return {store,graph:new FoodGraph(store)};
}
const key=idempotencyKey=>({idempotencyKey});

test('assumption rows name canonical ingredients, group one cook and preserve unknown amounts without writes',t=>{
 const {store,graph}=fixture(t);
 const product=graph.upsertProduct({name:'Orbit lentils',baseUnit:'g'},key('product'));
 const lot=graph.observePantry({productId:product.id,amount:300,location:'pantry'},key('lot')).pantryItem;
 const batch=graph.recordBatch({title:'Orbit bowl',recipeId:'orbit-bowl',ingredients:[
  {name:'Orbit lentils',productId:product.id,pantryItemId:lot.id,amount:null},
  {name:'Lunar greens',amount:null},
  {name:'Comet seeds',amount:20,confidence:'known',pantryItemId:null},
 ]},key('cook'));
 const events=store.get('SELECT count(*) n FROM events').n;
 const movements=store.all('SELECT * FROM pantry_movements');
 const rows=graph.openAssumptions();
 assert.equal(rows.length,3);
 assert.deepEqual(rows.map(r=>r.foodName),['Orbit lentils','Lunar greens','Comet seeds']);
 assert.ok(rows.every(r=>r.source.id===batch.batch.id && r.source.kind==='recipe' && r.source.title==='Orbit bowl'));
 assert.ok(rows.every(r=>r.source.recipeId==='orbit-bowl' && r.productId));
 assert.equal(graph.balance(lot.id).amount,null);
 assert.equal(store.get('SELECT count(*) n FROM events').n,events);
 assert.deepEqual(store.all('SELECT * FROM pantry_movements'),movements);
});

test('unlinked text does not invent a food or source and preference history keeps contradictory evidence',t=>{
 const {graph,store}=fixture(t);
 graph.addAssumption({statement:'A made-up food was definitely eaten.',evidence:['unknown-evidence']},key('unlinked'));
 const row=graph.openAssumptions().find(r=>r.evidence.includes('unknown-evidence'));
 assert.equal(row.foodName,null);assert.equal(row.productId,null);assert.equal(row.source,null);
 const earlier=graph.setPreference({kind:'taste',subject:'orbit greens',stance:'love',statement:'Love these greens.',source:'imported',confidence:'assumed',evidence:['earlier-note']},key('old-pref'));
 const current=graph.setPreference({kind:'taste',subject:'orbit greens',stance:'avoid',statement:'Avoid these greens now.',source:'stated',confidence:'known',evidence:['later-note']},key('new-pref'));
 const eventCount=store.get('SELECT count(*) n FROM events').n;
 const preferences=graph.currentPreferences();
 assert.equal(preferences.length,1);
 assert.equal(preferences[0].id,current.id);assert.equal(preferences[0].stance,'avoid');
 assert.deepEqual(preferences[0].evidence,['later-note']);
 assert.equal(preferences[0].history[0].id,earlier.id);
 assert.equal(preferences[0].history[0].stance,'love');
 assert.equal(preferences[0].history[0].source,'imported');
 assert.equal(preferences[0].history[0].confidence,'assumed');
 assert.deepEqual(preferences[0].history[0].evidence,['earlier-note']);
 assert.equal(store.get('SELECT count(*) n FROM events').n,eventCount);
});

test('photo and receipt reasons require explicit canonical evidence, while words stay food-log evidence',t=>{
 const {graph,store}=fixture(t);
 const product=graph.upsertProduct({name:'Nebula fruit',baseUnit:'g'},key('fruit'));
 store.record('captured','capture:photo-fixture',{captureId:'photo-fixture',media:{sha256:'fixture-image'},mediaList:[{sha256:'fixture-image'}]});
 const photo=graph.recordIntake({captureId:'photo-fixture',title:'Nebula snack',components:[{name:'Nebula fruit',productId:product.id,amount:10}]},key('photo-intake'));
 const words=graph.recordIntake({captureId:'words-fixture',title:'Nebula words',components:[{name:'Nebula fruit',productId:product.id,amount:10}]},key('words-intake'));
 const receipt=graph.importReceipt({fingerprint:'receipt-fixture',store:'Orbit market',items:[{name:'Comet greens',packages:1}]},key('receipt'));
 graph.addAssumption({statement:'Review this purchase.',evidence:[receipt.receipt.id]},key('receipt-question'));
 const rows=graph.openAssumptions();
 assert.equal(rows.find(r=>r.evidence.includes(photo.components[0].id)).source.kind,'photo');
 assert.equal(rows.find(r=>r.evidence.includes(words.components[0].id)).source.kind,'intake');
 assert.equal(rows.find(r=>r.evidence.includes(receipt.receipt.id)).source.kind,'receipt');
 assert.equal(rows.find(r=>r.evidence.includes(receipt.receipt.id)).foodName,null);
});
