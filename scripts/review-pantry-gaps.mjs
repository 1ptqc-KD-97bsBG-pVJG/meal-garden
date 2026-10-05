#!/usr/bin/env node
// Append review questions for previously unallocated intake and negative balances.
// Does not infer amounts, choose lots, confirm assumptions or rerun migrations.
import path from 'node:path';
import {Store} from '../companion/store.mjs';
import {FoodGraph} from '../companion/graph.mjs';
const selected=process.argv[2]||process.env.MEAL_DATA_DIR;
if(!selected)throw new Error('Select a household with MEAL_DATA_DIR or a directory argument; review must never default to the code folder');
const root=path.resolve(selected);
const store=new Store(path.join(root,'.runtime/garden.sqlite'),{root,recoverJobs:false});
try{console.log(JSON.stringify(new FoodGraph(store,{root}).reviewPantryGaps()));}finally{store.close();}
