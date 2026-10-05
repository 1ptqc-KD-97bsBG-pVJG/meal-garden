import {androidActor,profilePath} from './household.mjs';
import {householdPath,read} from './domain.mjs';
// Explicit health preferences; no calorie deficit or body-size assumptions.
export const DEFAULT_HEALTH_PREFERENCES={version:1,priorities:['longevity','energy'],targets:{}};
export const HEALTH_TARGET_LIMITS={calories:[800,6000],protein_g:[20,300],fiber_g:[10,70],sodium_mg:[500,5000],saturated_fat_g:[5,60],added_sugar_g:[0,100]};
export function validateHealthPreferences(input){
 if(!input||typeof input!=='object'||Array.isArray(input))throw new Error('Invalid health preferences');
 const allowed=['longevity','weight_loss','muscle_gain','energy'];
 if(!Array.isArray(input.priorities)||input.priorities.length<1||input.priorities.length>4||new Set(input.priorities).size!==input.priorities.length||input.priorities.some(x=>!allowed.includes(x)))throw new Error('Choose distinct health priorities');
 if(!input.targets||typeof input.targets!=='object'||Array.isArray(input.targets))throw new Error('Invalid nutrition targets');
 const targets={};
 for(const [key,value] of Object.entries(input.targets)){
  const limits=HEALTH_TARGET_LIMITS[key];
  if(!limits||typeof value!=='number'||!Number.isFinite(value)||value<limits[0]||value>limits[1])throw new Error(`Invalid ${key} target`);
  targets[key]=value;
 }
 return {version:1,priorities:input.priorities,targets};
}
export function healthPreferences(store){
 const row=store.get("SELECT value FROM settings WHERE key='health_preferences'");
 if(row)return JSON.parse(row.value);
 // Imported profiles carry explicit owner goals; retain them without seeding another household's goals.
 const goals=store.root?read(householdPath(store.root,profilePath(store.root,store.household)))?.health_goals:null;
 if(goals&&typeof goals==='object'){
  const allowed=['longevity','weight_loss','muscle_gain','energy'];
  const priorities=[...new Set((Array.isArray(goals.priority_order)?goals.priority_order:[]).filter(goal=>allowed.includes(goal)))];
  if(Array.isArray(goals.supporting_goals)&&goals.supporting_goals.some(goal=>['energy','steadier_energy'].includes(goal))&&!priorities.includes('energy'))priorities.push('energy');
  const targets={};
  if(goals.numeric_targets&&typeof goals.numeric_targets==='object'&&!Array.isArray(goals.numeric_targets))for(const [key,value] of Object.entries(goals.numeric_targets)){
   const limits=HEALTH_TARGET_LIMITS[key];if(limits&&typeof value==='number'&&Number.isFinite(value)&&value>=limits[0]&&value<=limits[1])targets[key]=value;
  }
  if(priorities.length||Object.keys(targets).length)return {version:1,priorities:priorities.length?priorities:[...DEFAULT_HEALTH_PREFERENCES.priorities],targets,source:`Profile: profile/${store.household.person.id}.json`};
 }
 return {...DEFAULT_HEALTH_PREFERENCES,priorities:[...DEFAULT_HEALTH_PREFERENCES.priorities],targets:{},source:'Default health preferences; numeric targets unset'};
}
export function saveHealthPreferences(store,input){
 const value=validateHealthPreferences(input);
 const current=healthPreferences(store);
 if(JSON.stringify(current.priorities)===JSON.stringify(value.priorities)&&JSON.stringify(current.targets)===JSON.stringify(value.targets))return value;
 store.db.exec('BEGIN IMMEDIATE');
 try{
  store.run("INSERT INTO settings(key,value) VALUES('health_preferences',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",JSON.stringify(value));
  store.record('health_preferences_changed','health:preferences',value,{actor:androidActor(store.household)});
  store.db.exec('COMMIT');
 }catch(e){store.db.exec('ROLLBACK');throw e}
 return value;
}
