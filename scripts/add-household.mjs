#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {fileURLToPath} from 'node:url';
import {householdIdPattern,loadHousehold} from '../companion/household.mjs';

export function addHousehold(id,name,personName,{dataHome=process.env.MEAL_HOME||path.join(os.homedir(),'MealGardenData'),sampleRoot=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../sample-household')}={}){
 if(typeof id!=='string'||!householdIdPattern.test(id))throw new Error('Household id must use lowercase letters, numbers, hyphens or underscores (1–64 characters)');
 for(const [label,value] of [['Kitchen name',name],['Person name',personName]])if(typeof value!=='string'||!value.trim()||value.length>200)throw new Error(`${label} must be 1–200 characters`);
 const households=path.resolve(dataHome,'households'),root=path.join(households,id);
 fs.mkdirSync(households,{recursive:true,mode:0o700});
 // Exclusive creation also refuses an existing empty directory or symlink.
 fs.mkdirSync(root,{mode:0o700});
 try{
  fs.cpSync(sampleRoot,root,{recursive:true});
  fs.rmSync(path.join(root,'.runtime'),{recursive:true,force:true});
  for(const directory of ['data','generated'])fs.rmSync(path.join(root,directory),{recursive:true,force:true});
  fs.mkdirSync(path.join(root,'data/inventory'),{recursive:true});
  fs.writeFileSync(path.join(root,'data/inventory/current.json'),JSON.stringify({schema_version:1,status:'not_inventoried',lots:[]},null,2)+'\n');
  fs.mkdirSync(path.join(root,'data/plans'),{recursive:true});
  const household={schema_version:1,id,name:name.trim(),person:{id:'owner',name:personName.trim()},timezone:'UTC'};
  fs.writeFileSync(path.join(root,'household.json'),JSON.stringify(household,null,2)+'\n');
  const profileFile=path.join(root,'profile/owner.json');
  const profile={schema_version:1,person_id:'owner',name:personName.trim(),priorities:[],preferences:[],exclude:[],health_targets:'Not specified.'};
  fs.mkdirSync(path.dirname(profileFile),{recursive:true});
  fs.writeFileSync(profileFile,JSON.stringify({...profile,name:personName.trim()},null,2)+'\n');
  fs.writeFileSync(path.join(root,'profile/kitchen.json'),JSON.stringify({schema_version:1,available:[],unconfirmed:[],note:'Kitchen equipment has not been confirmed.'},null,2)+'\n');
  loadHousehold(root);
  return root;
 }catch(error){fs.rmSync(root,{recursive:true,force:true});throw error}
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
 process.umask(0o077);
 try{
  if(process.argv.length!==5)throw new Error('Usage: node scripts/add-household.mjs <id> "<Kitchen name>" "<Person name>"');
  const root=addHousehold(...process.argv.slice(2));
  console.log(`Created household: ${root}\nReview household.json to set timezone, pronouns and optional shopping.\nRestart the companion with MEAL_HOME pointing at the data home.\nOpen http://localhost:${process.env.MEAL_PORT||4783}/setup on the laptop and pair using this kitchen’s code or QR.`);
 }catch(error){console.error(error.message);process.exitCode=1}
}
