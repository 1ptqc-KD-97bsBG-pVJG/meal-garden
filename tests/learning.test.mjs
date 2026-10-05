import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {atomic} from '../companion/domain.mjs';
import {Store} from '../companion/store.mjs';
import {recordLearning,currentLearning,learningContext,reflectContext} from '../companion/learning.mjs';

test('learned notes are append-only; the newest note per subject is what agents see',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'garden-learn-')),store=new Store(path.join(dir,'garden.sqlite'));
 try{
  recordLearning(store,{area:'kitchen',subject:'Electric hob · millet simmer',note:'Level 6',evidence:'recipe guess'});
  recordLearning(store,{area:'kitchen',subject:'Electric hob · millet simmer',note:'Level 4 keeps a steady simmer; 6 bubbles too quickly',evidence:'Fictional trial: millet simmered evenly at level 4'});
  recordLearning(store,{area:'app',subject:'Chat',note:'Planning dinner took too long',evidence:'field note'});
  assert.equal(store.all("SELECT * FROM events WHERE type='learned'").length,3);
  assert.equal(currentLearning(store).length,2);
  const context=learningContext(store);
  assert.match(context,/Level 4 keeps a steady simmer/);
  assert.doesNotMatch(context,/Level 6$/m);
  assert.doesNotMatch(context,/Planning dinner took too long/,'app lessons are for developers, not cooking prompts');
  assert.throws(()=>recordLearning(store,{area:'medical',subject:'x',note:'y',evidence:'z'}),/area must be/);
  assert.throws(()=>recordLearning(store,{area:'kitchen',subject:'x',note:'y'}),/Evidence/);
 }finally{store.close();fs.rmSync(dir,{recursive:true,force:true})}
});

test('reflection context gathers the recipe, cooking reports and that day\'s field notes',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-reflect-'));
 try{
  atomic(path.join(root,'household.json'),{schema_version:1,id:'home',name:'Test kitchen',person:{id:'owner',name:'You'},timezone:'America/Vancouver'});
  atomic(path.join(root,'recipes/millet/recipe.json'),{id:'millet',title:'Millet',revision:1,steps:[{title:'Boil',text:'Boil water',equipment:'Electric hob · Level 6'}]});
  atomic(path.join(root,'data/app-feedback/a.json'),{id:'a',text:'Level six made the pot boil too quickly',createdAt:'2031-05-11T05:22:00Z',phoneTime:'2031-05-10T22:22:00-07:00',screen:'Recipe: Millet',recipeId:'millet'});
  atomic(path.join(root,'data/app-feedback/b.json'),{id:'b',text:'unrelated older note',createdAt:'2031-05-05T05:00:00Z',phoneTime:'2031-05-04T22:00:00-07:00',screen:'Recipe: Other',recipeId:'other'});
  const context=reflectContext(root,'millet','2031-05-10');
  assert.match(context,/Electric hob · Level 6/);
  assert.match(context,/Level six made the pot boil too quickly/);
  assert.doesNotMatch(context,/unrelated older note/);
  assert.throws(()=>reflectContext(root,'missing','2031-05-10'),/Unknown recipe/);
 }finally{fs.rmSync(root,{recursive:true,force:true})}
});
