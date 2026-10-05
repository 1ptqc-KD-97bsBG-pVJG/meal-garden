import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {Store} from '../companion/store.mjs';
import {recordCapture,recordInterpretation,captureView,interpretationPrompt} from '../companion/capture.mjs';
import {validateHealthPreferences,healthPreferences,saveHealthPreferences} from '../companion/health.mjs';

test('health preferences keep personal targets unset and reject invalid or ambiguous numbers',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'garden-health-')),store=new Store(path.join(dir,'garden.sqlite'));
 try {
  assert.deepEqual(healthPreferences(store).targets,{});
  assert.deepEqual(healthPreferences(store).priorities,['longevity','energy']);
  const preferences={priorities:['longevity','muscle_gain'],targets:{protein_g:110,fiber_g:30,added_sugar_g:0}};
  assert.deepEqual(saveHealthPreferences(store,preferences).targets,preferences.targets);
  assert.deepEqual(healthPreferences(store).priorities,preferences.priorities);
  for(const targets of [{protein_g:'110'},{calories:NaN},{fiber_g:-5},{sodium_mg:100000},{unknown:12}]) assert.throws(()=>validateHealthPreferences({...preferences,targets}),/Invalid/);
  assert.throws(()=>validateHealthPreferences({...preferences,priorities:['longevity','longevity']}),/distinct/);
  assert.equal(store.events('health:preferences').at(-1).type,'health_preferences_changed');
 } finally {store.close();fs.rmSync(dir,{recursive:true,force:true})}
});

test('health nutrients preserve small quantities, unknowns and immutable previous readings',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'garden-health-')),store=new Store(path.join(dir,'garden.sqlite'));
 try {
  const id=crypto.randomUUID();recordCapture(store,dir,{id,kind:'meal',note:'tofu bowl',phoneTime:'2026-10-03T12:00:00-07:00'});
  const reading={captureId:id,category:'meal',title:'Tofu bowl',items:[],overallConfidence:'medium',nutrition:{sodium_mg:{low:650,high:1100},saturated_fat_g:{low:0.3,high:1.7},added_sugar_g:null}};
  recordInterpretation(store,id,reading,'health-job');
  const n=captureView(store,id).interpretation.nutrition;
  assert.deepEqual(n.saturated_fat_g,{low:0.3,high:1.7});
  assert.equal(n.added_sugar_g,null);assert.equal(n.fiber_g,null);
  for(const range of [{low:null,high:10},{low:'0',high:10},{low:5,high:1},{low:0,high:Infinity}]) assert.throws(()=>recordInterpretation(store,id,{...reading,nutrition:{sodium_mg:range}},'invalid'),/ranges/);
  recordInterpretation(store,id,{...reading,nutrition:null},'correction');
  assert.equal(store.events(`capture:${id}`).filter(e=>e.type==='interpreted').length,2);
  assert.equal(captureView(store,id).interpretation.nutrition,null);
  assert.match(interpretationPrompt(captureView(store,id)),/Total sugar is not added sugar/);
 } finally {store.close();fs.rmSync(dir,{recursive:true,force:true})}
});
