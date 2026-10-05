import {loadHousehold} from './household.mjs';
import {id, now, hash, requireText, localDate} from './domain.mjs';
import {itemKey, kitchenReset} from './kitchen.mjs';

export const NUTRIENTS = ['kcal','protein_g','carbs_g','fat_g','fiber_g','sodium_mg','sat_fat_g','added_sugar_g'];
const JSON_COLUMNS = new Set(['match_words','nutrition_total','nutrition','value','evidence','aspects','detail','result']);
const decode = row => row && Object.fromEntries(Object.entries(row).map(([k,v]) => [k,JSON_COLUMNS.has(k) && v != null ? JSON.parse(v) : v]));
const words = value => String(value || '').toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g,' ').trim().split(/\s+/).filter(Boolean);
const singular = w => w.length>3&&!w.endsWith('ss')?w.replace(/ies$/,'y').replace(/oes$/,'o').replace(/s$/,''):w;
const matches = (subject, text) => words(subject).map(singular).every(w => words(text).map(singular).includes(w));
const weakest = values => values.includes('unknown') ? 'unknown' : values.includes('assumed') ? 'assumed' : 'known';
const quantity = (value, name, nullable=true) => {
 if (value == null && nullable) return null;
 if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) throw new Error(`${name} must be a nonnegative number`);
 return value;
};
const timestamp = value => { const v=value || now(); if (!Number.isFinite(Date.parse(v))) throw new Error('Invalid time'); return new Date(v).toISOString(); };
const NON_FOOD = /deodorant|peeler|detergent|soap|foil|plastic wrap|ziploc|trash bag|sponge|paper towel/i;
const json = value => value == null ? null : JSON.stringify(value);

// Explicit conversions only. A missing package weight does not establish an opening amount.
export function convertQuantity(amount, unit, baseUnit, gramsPerUnit=null) {
 if (amount == null) return null;
 quantity(amount,'Amount',false);
 const u=String(unit || baseUnit).toLowerCase().replace(/\./g,'');
 const grams={g:1,gram:1,grams:1,kg:1000,oz:28.349523125,ounce:28.349523125,ounces:28.349523125,lb:453.59237,lbs:453.59237};
 const ml={ml:1,l:1000,liter:1000,litre:1000,'fl oz':29.5735295625};
 if (u === baseUnit || (baseUnit === 'count' && ['each','unit','units','ct'].includes(u))) return amount;
 if (baseUnit === 'g' && grams[u]) return amount*grams[u];
 if (baseUnit === 'ml' && ml[u]) return amount*ml[u];
 if (baseUnit === 'g' && ['count','ct','ml'].includes(u) && gramsPerUnit) return amount*gramsPerUnit;
 return null;
}

export class FoodGraph {
 constructor(store,{root=store.root||null,household=null,person=null,onProductsCreated=null}={}) { const config=store.household||loadHousehold(root);this.store=store;this.root=root;this.household=household||config.id;this.person=person||config.person.id;this.onProductsCreated=onProductsCreated; }
 _get(table,idValue) { return decode(this.store.get(`SELECT * FROM ${table} WHERE id=?`,idValue)); }
 _require(table,idValue) {
  const row=this._get(table,idValue);if(!row)throw new Error(`Unknown ${table}: ${idValue}`);
  if(row.household_id!=null&&row.household_id!==this.household)throw new Error('Record is outside this household');
  if(row.person_id!=null)this._require('persons',row.person_id);
  const parents={pantry_movements:['pantry_items','pantry_item_id'],intake_components:['intake','intake_id'],batch_ingredients:['batches','batch_id'],purchases:['receipts','receipt_id']};
  if(parents[table]){const [parent,column]=parents[table];this._require(parent,row[column]);}
  if(table==='products'&&row.batch_id)this._require('batches',row.batch_id);
  return row;
 }
 _insert(table,row) {
  const keys=Object.keys(row);this.store.run(`INSERT INTO ${table}(${keys.join(',')}) VALUES(${keys.map(()=>'?').join(',')})`,...keys.map(k=>JSON_COLUMNS.has(k)?json(row[k]):row[k]??null));
  return this._get(table,row.id);
 }
 _write(operation,input,options,work,aliases=[]) {
  const key=requireText(options?.idempotencyKey || input?.idempotencyKey,'Idempotency key',250);
  const nested=this.store.db.isTransaction;
  const commit=nested?'RELEASE graph_write':'COMMIT',rollback=nested?'ROLLBACK TO graph_write; RELEASE graph_write':'ROLLBACK';
  this.store.db.exec(nested?'SAVEPOINT graph_write':'BEGIN IMMEDIATE');
  try {
   for(const k of [key,...aliases]) {
    const prior=this.store.get('SELECT operation,result FROM idempotency_keys WHERE key=?',k);
    if(prior) {
     if(prior.operation!==operation)throw new Error('Idempotency key belongs to another operation');
     this.store.db.exec(commit);return JSON.parse(prior.result);
    }
   }
   const event=this.store.record(`graph.${operation}`,`graph:${operation}`,{idempotencyKey:key,input},{occurredAt:timestamp(options?.occurredAt),actor:options?.actor || input?.actor || 'system',evidence:options?.evidence || input?.evidence || []});
   const result=work(event);
   for(const k of new Set([key,...aliases]))this.store.run('INSERT INTO idempotency_keys(key,operation,result,created_at) VALUES(?,?,?,?)',k,operation,JSON.stringify(result),now());
   this.store.db.exec(commit);return result;
  } catch(e) { this.store.db.exec(rollback);throw e; }
 }
 _movement(event,itemId,amount,reason,confidence='known',extra={}) {
  this._require('pantry_items',itemId);
  return this._insert('pantry_movements',{id:id(),pantry_item_id:itemId,kind:'delta',amount,reason,confidence,event_id:event.id,occurred_at:event.occurred_at,...extra});
 }
 balance(itemId,at=null) {
  this._require('pantry_items',itemId);
  const rows=at?this.store.all('SELECT m.rowid AS sequence,m.*,e.actor AS event_actor FROM pantry_movements m JOIN events e ON e.id=m.event_id WHERE pantry_item_id=? AND m.occurred_at<=? ORDER BY m.occurred_at,m.rowid',itemId,timestamp(at)):this.store.all('SELECT m.rowid AS sequence,m.*,e.actor AS event_actor FROM pantry_movements m JOIN events e ON e.id=m.event_id WHERE pantry_item_id=? ORDER BY m.occurred_at,m.rowid',itemId);
  // Cancellation also works for unknown-size deductions and counts, and never corrupts a later stocktake.
  const reversed=new Set(rows.filter(r=>r.reverses_movement_id).map(r=>r.reverses_movement_id));
  const active=rows.filter(r=>r.reason!=='reversal'&&!reversed.has(r.id));
  const count=active.findLastIndex(r=>r.kind==='count');
  const relevant=count>=0?active.slice(count):active;
  const opening=count>=0 || relevant.some(r=>['purchase','made','transfer'].includes(r.reason) && r.amount!=null && r.amount>=0);
  let amount=opening?0:null;
  for(const r of relevant) { if(r.amount==null)amount=null;else if(r.kind==='count')amount=r.amount;else if(amount!=null)amount+=r.amount; }
  return {amount:amount==null?null:Math.round(amount*1e6)/1e6,basis:relevant.length?weakest(relevant.map(r=>r.kind!=='count'&&r.reason==='purchase'&&r.event_actor==='migration:food-graph'?'assumed':r.confidence)):'unknown'};
 }
 _product(input) {
  const productId=input.id || `product:${hash(`${input.brand||''}|${input.name}|${input.base_unit||input.baseUnit||'g'}`).slice(0,24)}`;
  const current=this._get('products',productId);
  if(current)this._require('products',productId);
  const pick=(snake,camel,fallback=null)=>input[snake]!==undefined?input[snake]:input[camel]!==undefined?input[camel]:current?.[snake]??fallback;
  const base=pick('base_unit','baseUnit','g');
  if(current && base!==current.base_unit && this.store.get('SELECT 1 FROM pantry_movements m JOIN pantry_items i ON i.id=m.pantry_item_id WHERE i.product_id=? LIMIT 1',productId))throw new Error('Cannot change base_unit after movements');
  const row={id:productId,name:requireText(input.name || current?.name,'Product name',250),brand:pick('brand','brand'),kind:pick('kind','kind','generic'),size_amount:quantity(pick('size_amount','sizeAmount'),'Package size'),base_unit:base,barcode:pick('barcode','barcode'),storage:pick('storage','storage'),shelf_life_days:pick('shelf_life_days','shelfLifeDays'),match_words:pick('match_words','matchWords',words(input.name)),nutrition_basis:pick('nutrition_basis','nutritionBasis'),grams_per_unit:quantity(pick('grams_per_unit','gramsPerUnit'),'Grams per unit'),nutrition_source:pick('nutrition_source','nutritionSource','none'),nutrition_url:pick('nutrition_url','nutritionUrl'),serving_g:quantity(pick('serving_g','servingG'),'Serving grams'),batch_id:pick('batch_id','batchId'),created_at:current?.created_at || now()};
  if(row.batch_id)this._require('batches',row.batch_id);
  for(const nutrient of NUTRIENTS)row[nutrient]=quantity(pick(nutrient,nutrient),nutrient);
  if(current) {const keys=Object.keys(row).filter(k=>k!=='id');this.store.run(`UPDATE products SET ${keys.map(k=>`${k}=?`).join(',')} WHERE id=?`,...keys.map(k=>JSON_COLUMNS.has(k)?json(row[k]):row[k]),productId);return this._get('products',productId);}
  return this._insert('products',row);
 }
 upsertProduct(input,options={}) {return this._write('upsertProduct',input,options,()=>this._product(input));}
 getProduct(productId) {const product=this._require('products',productId);const lookup=this.store.get('SELECT * FROM product_lookups WHERE product_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1',productId);return lookup?{...product,nutrition_evidence:{...lookup,detail:JSON.parse(lookup.detail)}}:product;}
 findProducts(query) {
  const q=words(query);if(!q.length)return [];
  return this.store.all('SELECT * FROM products ORDER BY name').map(decode).filter(p=>q.every(w=>words([p.name,p.brand,...(p.match_words||[])].join(' ')).includes(w)));
 }
 setProductNutrition(productId,nutrition,options={}) {
  return this._write('setProductNutrition',{productId,...nutrition},options,()=>{this._require('products',productId);return this._product({...nutrition,id:productId});});
 }
 _item(product,event,extra={}) {
  return this._insert('pantry_items',{id:id(),household_id:this.household,product_id:product.id,purchase_id:null,split_from_id:null,location:product.storage || 'unknown',condition:null,opened_on:null,last_confirmed_at:null,created_at:event.occurred_at,...extra});
 }
 addPantryItem(input,options={}) {
  return this._write('addPantryItem',input,options,event=>{
   const product=this._require('products',input.productId);
   const item=this._item(product,event,{location:input.location||product.storage||'unknown'});
   return {...item,balance:this.balance(item.id)};
  });
 }
 observePantry(input,options={}) {
  return this._write('observePantry',input,options,event=>{
   if(!['fridge','freezer','pantry','counter','unknown'].includes(input.location))throw new Error('Invalid location');
   const product=input.productId?this._require('products',input.productId):this._product({name:input.name,base_unit:input.baseUnit,kind:input.kind||'generic',brand:input.brand});
   const item=this._item(product,event,{location:input.location,last_confirmed_at:event.occurred_at});
   this._movement(event,item.id,quantity(input.amount,'Observed amount'),'stated',input.amount==null?'unknown':'known',{kind:'count'});
   return {product,pantryItem:item,balance:this.balance(item.id)};
  });
 }
 importReceipt(receipt,options={}) {
  const fingerprint=receipt.fingerprint || receipt.id || hash(JSON.stringify(receipt));
  const receiptId=`receipt:${hash(`${receipt.store||''}|${fingerprint}`).slice(0,24)}`;
  const receiptKey=`receipt:${hash(`${receipt.store||''}|${fingerprint}`)}`;
  const prior=this.store.get('SELECT 1 FROM idempotency_keys WHERE key=?',receiptKey);
  const result=this._write('importReceipt',receipt,{...options,idempotencyKey:options.idempotencyKey || receipt.idempotencyKey || receiptKey,occurredAt:options.occurredAt || receipt.purchased_on || receipt.date},event=>{
   const r=this._insert('receipts',{id:receiptId,household_id:this.household,store:receipt.store||null,purchased_on:receipt.purchased_on||receipt.date||null,total:receipt.total??null,source:receipt.source||'manual'});
   const products=[],purchases=[],pantryItems=[],failedLines=[];
   for(const line of receipt.items||receipt.lines||[]) {
    if(!line?.name || NON_FOOD.test(line.name)) {failedLines.push({name:line?.name||null,reason:!line?.name?'missing name':'non-food'});continue;}
    const spec=line.product || {};
    const sizeText=String(line.size || line.packageSize || `${line.quantity||''} ${line.name}`);
    const sizeMatch=sizeText.match(/(?:^|[\s(–-])(\d+(?:\.\d+)?)\s*(fl\s*oz|lbs?|kg|oz|ml|g|ct|count|l)\b/i);
    const sizeUnit=line.size?.unit || line.sizeUnit || sizeMatch?.[2]?.toLowerCase().replace(/\s+/g,' ');
    const base=spec.base_unit||spec.baseUnit||line.base_unit||line.baseUnit||(sizeUnit==='ml'||sizeUnit==='l'||sizeUnit==='fl oz'?'ml':sizeUnit==='count'||sizeUnit==='ct'?'count':'g');
    const size=spec.size_amount??spec.sizeAmount??line.size_amount??(line.size?.amount!=null?convertQuantity(line.size.amount,sizeUnit,base):line.sizeAmount!=null?convertQuantity(line.sizeAmount,sizeUnit,base):sizeMatch?convertQuantity(Number(sizeMatch[1]),sizeUnit,base):null);
    const matchName=line.name.replace(/\s*[-–]\s*\d.*$/,'').trim();
    const p=this._product({name:matchName,kind:line.kind||(/organic|kroger|barilla|nancy|simple truth|prego/i.test(line.name)?'packaged':'generic'),base_unit:base,storage:line.location||'unknown',...spec,size_amount:size});
    const packMatch=String(line.quantity||'').match(/^(\d+(?:\.\d+)?)\s*[×x]/i);
    const packageValue=line.packages??(typeof line.quantity==='number'?line.quantity:packMatch?Number(packMatch[1]):/^\d+(?:\.\d+)?$/.test(String(line.quantity))?Number(line.quantity):null);
    const packages=quantity(packageValue,'Packages');
    const purchase=this._insert('purchases',{id:id(),receipt_id:r.id,product_id:p.id,raw_line:line.raw_line||line.rawLine||line.name,packages,price:line.price??null});
    const item=this._item(p,event,{purchase_id:purchase.id});
    this._movement(event,item.id,size!=null&&packages!=null?size*packages:null,'purchase',size!=null&&packages!=null?'known':'unknown');
    products.push(p);purchases.push(purchase);pantryItems.push({...item,balance:this.balance(item.id)});
   }
   return {receipt:r,products,purchases,pantryItems,failedLines};
  },[receiptKey]);
  if(!prior&&this.onProductsCreated)setImmediate(()=>this.onProductsCreated(result.products.map(p=>p.id)));
  return result;
 }
 countPantryItem(itemId,amount,confidence='known',actor='system',options={}) {
  return this._write('countPantryItem',{itemId,amount,confidence,actor},{...options,actor},event=>{
   quantity(amount,'Count');const movement=this._movement(event,itemId,amount,'stated',amount==null?'unknown':confidence,{kind:'count'});
   this.store.run('UPDATE pantry_items SET last_confirmed_at=? WHERE id=?',event.occurred_at,itemId);
   return {movement,balance:this.balance(itemId)};
  });
 }
 setCondition(itemId,condition,options={}) {
  return this._write('setCondition',{itemId,condition},options,event=>{this._require('pantry_items',itemId);if(!['fine','use_soon'].includes(condition))throw new Error('Choose fine or use_soon');this.store.run('UPDATE pantry_items SET condition=?,last_confirmed_at=? WHERE id=?',condition,event.occurred_at,itemId);return this._get('pantry_items',itemId);});
 }
 tossPantryItem(itemId,options={}) {
  return this._write('tossPantryItem',{itemId,amount:options.amount??null},options,event=>{
   const before=this.balance(itemId);const amount=options.amount==null?(before.amount==null?null:Math.max(0,before.amount)):quantity(options.amount,'Amount',false);
   const movement=this._movement(event,itemId,amount==null?null:-amount,'tossed',options.confidence||before.basis);
   // Tossing the entire lot is also an observation that it is now empty, even if its former amount was unknown.
   if(options.amount==null)this._movement(event,itemId,0,'stated','known',{kind:'count'});
   return {movement,balance:this.balance(itemId)};
  });
 }
 transfer(itemId,amount,toLocation,options={}) {
  return this._write('transfer',{itemId,amount,toLocation},options,event=>{
   const original=this._require('pantry_items',itemId);if(!['fridge','freezer','pantry','counter','unknown'].includes(toLocation))throw new Error('Invalid location');
   const before=this.balance(itemId);
   const whole=amount==null||(before.amount!=null&&amount===before.amount);
   if(whole) {this.store.run('UPDATE pantry_items SET location=? WHERE id=?',toLocation,itemId);return {wholeLot:true,item:this._get('pantry_items',itemId),balance:before};}
   quantity(amount,'Transfer amount',false);if(amount===0 || (before.amount!=null&&amount>before.amount))throw new Error('Transfer exceeds available amount');
   const split=this._item(this._require('products',original.product_id),event,{purchase_id:original.purchase_id,split_from_id:itemId,location:toLocation,condition:original.condition,opened_on:original.opened_on,created_at:original.created_at});
   const transferId=id(),confidence=options.confidence||before.basis;
   const movements=[this._movement(event,itemId,-amount,'transfer',confidence,{transfer_id:transferId}),this._movement(event,split.id,amount,'transfer',confidence,{transfer_id:transferId})];
   return {wholeLot:false,item:split,transferId,movements,fromBalance:this.balance(itemId),balance:this.balance(split.id)};
  });
 }
 _assumption(event,input) {
  return this._insert('assumptions',{id:id(),household_id:this.household,statement:requireText(input.statement,'Assumption',2000),status:'open',evidence:input.evidence||[event.id],created_at:event.recorded_at,resolved_at:null});
 }
 addAssumption(input,options={}) {return this._write('addAssumption',input,options,event=>{
  const a=this._assumption(event,input);
  const movements=(input.movements||[]).map(m=>this._movement(event,m.pantryItemId||m.pantry_item_id,m.amount==null?null:-quantity(m.amount,'Use amount',false),'assumed_use',m.confidence||'assumed',{assumption_id:a.id}));
  return {...a,movements};
 });}
 _reverse(event,movement) {
  if(this.store.get('SELECT 1 FROM pantry_movements WHERE reverses_movement_id=?',movement.id))return null;
  return this._movement(event,movement.pantry_item_id,movement.amount==null?null:-movement.amount,'reversal',movement.confidence,{reverses_movement_id:movement.id});
 }
 resolveAssumptions(ids,status,options={}) {
  return this._write('resolveAssumptions',{ids,status},options,event=>{
   if(!['confirmed','corrected'].includes(status)||!Array.isArray(ids)||!ids.length)throw new Error('Choose assumptions and confirmed or corrected');
   const reversals=[];
   const assumptions=ids.map(assumptionId=>{
    const a=this._require('assumptions',assumptionId);
    if(a.status!=='open'&&a.status!==status)throw new Error('Assumption is already resolved');
    if(status==='corrected')for(const m of this.store.all('SELECT * FROM pantry_movements WHERE assumption_id=? AND reason!=?',assumptionId,'reversal')){const r=this._reverse(event,m);if(r)reversals.push(r);}
    if(a.status==='open')this.store.run('UPDATE assumptions SET status=?,resolved_at=? WHERE id=?',status,event.recorded_at,assumptionId);
    return this._get('assumptions',assumptionId);
   });
   return {assumptions,reversals};
  });
 }
 openAssumptions() {return this.store.all("SELECT * FROM assumptions WHERE household_id=? AND status='open' ORDER BY created_at,rowid",this.household).map(decode).map(a=>{
  const preference=(a.evidence||[]).some(e=>typeof e==='string'&&this.store.get('SELECT 1 FROM preferences WHERE id=?',e));
  const component=(a.evidence||[]).map(e=>typeof e==='string'?this.store.get('SELECT c.* FROM intake_components c JOIN intake i ON i.id=c.intake_id WHERE c.id=? AND i.superseded_by_id IS NULL',e):null).find(Boolean);
  const movement=this.store.get('SELECT * FROM pantry_movements WHERE assumption_id=? LIMIT 1',a.id);
  return {...a,kind:preference?'preferences':component?'pantry':(movement?.batch_ingredient_id||/inferred from recipe/i.test(a.statement))?'cooking':'pantry',componentId:component&&!component.pantry_item_id?component.id:null,productId:component?.product_id||null};
 });}
 reviewPantryGaps() {
  const added=[];
  const components=this.store.all('SELECT c.*,p.name AS product_name FROM intake_components c JOIN intake i ON i.id=c.intake_id JOIN products p ON p.id=c.product_id WHERE i.superseded_by_id IS NULL AND c.pantry_item_id IS NULL AND i.person_id=?',this.person);
  for(const c of components){
   if(this.store.all('SELECT evidence FROM assumptions').some(a=>JSON.parse(a.evidence).includes(c.id)))continue;
   added.push(this.addAssumption({statement:`${c.product_name}: which lot was used? Its pantry amount has not been deducted.`,evidence:[c.id]}, {idempotencyKey:`review-pantry-gap:${c.id}`,actor:'system:release-review'}));
  }
  for(const lot of this.store.all('SELECT id,product_id FROM pantry_items WHERE household_id=?',this.household)){
   const b=this.balance(lot.id);if(b.amount==null||b.amount>=0)continue;
   if(this.openAssumptions().some(a=>a.evidence.includes(lot.id)))continue;
   added.push(this.addAssumption({statement:`${this.getProduct(lot.product_id).name}: recorded uses exceed the opening amount. Count what is here.`,evidence:[lot.id]}, {idempotencyKey:`review-negative:${lot.id}:${hash(JSON.stringify(b))}`,actor:'system:release-review'}));
  }
  return {added:added.length};
 }
 allocateIntake(componentId,itemId,options={}) {
  return this._write('allocateIntake',{componentId,itemId},options,event=>{
   const component=this._require('intake_components',componentId),item=this._require('pantry_items',itemId),intake=this._require('intake',component.intake_id);
   if(intake.superseded_by_id||component.pantry_item_id||component.product_id!==item.product_id||item.household_id!==this.household)throw new Error('This intake cannot be allocated to that lot');
   this.store.run('UPDATE intake_components SET pantry_item_id=? WHERE id=?',itemId,componentId);
   const a=this._assumption(event,{statement:`${component.name}: check the amount left after this use.`,evidence:[componentId,event.id]});
   const movement=this._movement(event,itemId,component.amount==null?null:-component.amount,'intake',component.confidence,{intake_component_id:componentId,assumption_id:a.id,occurred_at:intake.eaten_at});
   for(const prior of this.openAssumptions().filter(priorAssumption=>priorAssumption.id!==a.id && priorAssumption.evidence.includes(componentId)))this.store.run("UPDATE assumptions SET status='confirmed',resolved_at=? WHERE id=?",event.recorded_at,prior.id);
   return {movement,balance:this.balance(itemId)};
  });
 }

 _grams(amount,product) {return amount==null?null:product?.base_unit==='g'?amount:product?.grams_per_unit?amount*product.grams_per_unit:null;}
 recordBatch(input,options={}) {
  return this._write('recordBatch',input,{...options,occurredAt:options.occurredAt||input.madeAt},event=>{
   const batchId=input.id||id();const assumptions=[],movements=[];
   const confidence=input.confidence || weakest((input.ingredients||[]).map(i=>i.confidence||'assumed'));
   const batch=this._insert('batches',{id:batchId,household_id:this.household,recipe_id:input.recipeId||null,recipe_revision:input.recipeRevision??null,title:requireText(input.title,'Batch title',250),made_at:timestamp(input.madeAt),recorded_from:input.recordedFrom||'report',yield_g:quantity(input.yieldG,'Yield'),yield_basis:input.yieldG==null?'unknown':input.yieldBasis||'estimated',portions_made:quantity(input.portions,'Portions'),nutrition_total:null,confidence});
   for(const ingredient of input.ingredients||[]) {
    let item=ingredient.pantryItemId?this._require('pantry_items',ingredient.pantryItemId):null;
    let product=ingredient.productId?this._require('products',ingredient.productId):item?this._require('products',item.product_id):null;
    if(item && product?.id!==item.product_id)throw new Error('Ingredient product does not match pantry item');
    if(!product) {
     const candidates=this.findProducts(ingredient.name);
     if(candidates.length===1)product=candidates[0];
     else product=this._product({name:ingredient.name,kind:'generic',base_unit:ingredient.baseUnit||'g'});
    }
    if(!item) {
     const lots=this.store.all('SELECT * FROM pantry_items WHERE product_id=? AND household_id=? AND created_at<=? ORDER BY created_at,rowid',product.id,this.household,event.occurred_at);
     item=lots.find(lot=>this.balance(lot.id,event.occurred_at).amount==null||this.balance(lot.id,event.occurred_at).amount>0)||null;
    }
    const amount=quantity(ingredient.amount,'Ingredient amount');
    const inferred=!ingredient.pantryItemId||amount==null||(ingredient.confidence||'assumed')!=='known';
    const quality=inferred?'assumed':'known';
    const grams=quantity(ingredient.grams??this._grams(amount,product),'Ingredient grams');
    const row=this._insert('batch_ingredients',{id:id(),batch_id:batchId,pantry_item_id:item?.id||null,product_id:product.id,name:requireText(ingredient.name||product.name,'Ingredient',250),amount,grams,amount_text:ingredient.amountText||null,confidence:quality});
    let assumption=null;
    if(inferred) {assumption=this._assumption(event,{statement:ingredient.assumption||`${row.name} went into ${batch.title}; ${amount==null?'amount unknown':`amount ${amount} ${product.base_unit}`}${item?'; lot selected from purchase history':'; pantry lot unknown'}.`,evidence:input.evidence||[event.id]});assumptions.push(assumption);}
    if(item)movements.push(this._movement(event,item.id,amount==null?null:-amount,inferred?'assumed_use':'batch_use',quality,{batch_ingredient_id:row.id,assumption_id:assumption?.id||null}));
   }
   const homemade=this._product({id:`batch-product:${batchId}`,name:batch.title,kind:'homemade',base_unit:'g',storage:input.location||'fridge',shelf_life_days:4,batch_id:batchId,nutrition_source:'computed'});
   const item=this._item(homemade,event);movements.push(this._movement(event,item.id,batch.yield_g,'made',batch.yield_basis==='measured'?'known':batch.yield_g==null?'unknown':'assumed'));
   const nutrition=this._computeBatchNutrition(batchId);
   return {batch:this._get('batches',batchId),ingredients:this.store.all('SELECT * FROM batch_ingredients WHERE batch_id=? ORDER BY rowid',batchId).map(decode),product:this.getProduct(homemade.id),pantryItem:item,balance:this.balance(item.id),movements,assumptions,nutrition_total:nutrition};
  });
 }
 _nutrition(components) {
  const totals=Object.fromEntries(NUTRIENTS.map(n=>[n,components.length?{low:0,high:0}:null]));
  const sources=[];
  for(const c of components) {
   const p=c.product_id?this._get('products',c.product_id):null;
   const grams=c.grams;
   // Unknown density is never silently treated as water. Count-based nutrition can use a known grams-per-unit.
   const denominator=p?.nutrition_basis==='per_100g'?100:p?.nutrition_basis==='per_100ml'&&p.grams_per_unit?100*p.grams_per_unit:p?.nutrition_basis==='per_unit'?p.grams_per_unit:null;
   const estimated=c.confidence!=='known';
   const low=grams==null?null:grams*(estimated?0.8:1),high=grams==null?null:grams*(estimated?1.2:1);
   sources.push({productId:p?.id||null,name:c.name,grams,basis:c.confidence,nutritionSource:p?.nutrition_source||'none',gramsRange:grams==null?null:{low,high}});
   for(const n of NUTRIENTS) {
    if(grams==null||!denominator||p?.[n]==null)totals[n]=null;
    else if(totals[n]) {totals[n].low+=low/denominator*p[n];totals[n].high+=high/denominator*p[n];}
   }
  }
  for(const n of NUTRIENTS)if(totals[n])totals[n]={low:Math.round(totals[n].low*1000)/1000,high:Math.round(totals[n].high*1000)/1000};
  return {...totals,sources};
 }
 _computeBatchNutrition(batchId) {
  const batch=this._require('batches',batchId);
  const rows=this.store.all('SELECT * FROM batch_ingredients WHERE batch_id=? ORDER BY rowid',batchId);
  const nutrition=this._nutrition(rows);
  this.store.run('UPDATE batches SET nutrition_total=? WHERE id=?',json(nutrition),batchId);
  if(batch.yield_g>0) {
   // Scalar product columns store the midpoint; the batch snapshot preserves uncertainty for intake computations.
   const columns=NUTRIENTS.map(n=>nutrition[n]==null?null:(nutrition[n].low+nutrition[n].high)/2/batch.yield_g*100);
   this.store.run(`UPDATE products SET nutrition_basis='per_100g',nutrition_source='computed',${NUTRIENTS.map(n=>`${n}=?`).join(',')} WHERE batch_id=?`,...columns,batchId);
  }
  return nutrition;
 }
 computeBatchNutrition(batchId,options={}) {return this._write('computeBatchNutrition',{batchId},options,()=>this._computeBatchNutrition(batchId));}
 recordIntake(input,options={}) {
  return this._write('recordIntake',input,{...options,occurredAt:options.occurredAt||input.eatenAt},event=>{
   const person=input.person||this.person;this._require('persons',person);
   const captureId=requireText(input.captureId,'Capture ID',150),intakeId=input.id||id();
   const prior=this.store.get('SELECT * FROM intake WHERE capture_id=? AND superseded_by_id IS NULL',captureId);
   if(prior)this._require('intake',prior.id);
   const reversals=[];
   if(prior)for(const m of this.store.all('SELECT m.* FROM pantry_movements m JOIN intake_components c ON c.id=m.intake_component_id WHERE c.intake_id=? AND m.reason!=?',prior.id,'reversal')){const r=this._reverse(event,m);if(r)reversals.push(r);}
   const row=this._insert('intake',{id:intakeId,capture_id:captureId,person_id:person,eaten_at:timestamp(input.eatenAt),title:input.title||null,category:input.category||'meal',method:'estimated',nutrition:null,confidence:input.confidence||weakest((input.components||[]).map(c=>c.confidence||'assumed')),superseded_by_id:null});
   if(prior){
    this.store.run('UPDATE intake SET superseded_by_id=? WHERE id=?',intakeId,prior.id);
    const previousIds=this.store.all('SELECT id FROM intake_components WHERE intake_id=?',prior.id).map(c=>c.id);
    for(const a of this.openAssumptions().filter(a=>a.evidence.some(e=>previousIds.includes(e))))this.store.run("UPDATE assumptions SET status='corrected',resolved_at=? WHERE id=?",event.recorded_at,a.id);
   }
   const components=[],movements=[],assumptions=[];
   for(const c of input.components||[]) {
    let item=c.pantryItemId?this._require('pantry_items',c.pantryItemId):null;
    const p=c.productId?this._require('products',c.productId):item?this._require('products',item.product_id):null;
    if(item&&p?.id!==item.product_id)throw new Error('Component product does not match pantry item');
    if(!item&&p){
     const available=this.store.all('SELECT * FROM pantry_items WHERE product_id=? AND household_id=? AND created_at<=?',p.id,this.household,event.occurred_at).filter(lot=>{const b=this.balance(lot.id,event.occurred_at);return b.amount==null||b.amount>0;});
     if(available.length===1)item=available[0];
    }
    const amount=quantity(c.amount??(p?.base_unit==='g'?c.grams:null),'Component amount');
    const component=this._insert('intake_components',{id:id(),intake_id:intakeId,pantry_item_id:item?.id||null,product_id:p?.id||null,name:requireText(c.name||p?.name,'Component name',250),amount,grams:quantity(c.grams??this._grams(amount,p),'Component grams'),confidence:c.confidence||'assumed'});
    components.push(component);
    let assumption=null;
    if(p&&!item)assumption=this._assumption(event,{statement:`${p.name}: which lot was used? Its pantry amount has not been deducted.`,evidence:[component.id,event.id]});
    else if(item){const before=this.balance(item.id,event.occurred_at);if(amount==null||before.amount==null||amount>before.amount)assumption=this._assumption(event,{statement:`${p.name}: check the amount left; this use exceeds or cannot resolve the recorded balance.`,evidence:[component.id,event.id]});}
    if(assumption)assumptions.push(assumption);
    if(item)movements.push(this._movement(event,item.id,amount==null?null:-amount,'intake',component.confidence,{intake_component_id:component.id,assumption_id:assumption?.id||null}));
   }
   let nutrition=this._nutrition(components);
   // Carry the homemade batch's bounds forward rather than erasing them with midpoint product values.
   for(const n of NUTRIENTS) {
    let low=0,high=0,complete=components.length>0;
    for(const c of components) {
     const p=c.product_id?this.getProduct(c.product_id):null;
     const b=p?.batch_id?this._get('batches',p.batch_id):null;
     const value=b?.yield_g?b.nutrition_total?.[n]:null;
     if(b) {if(!value||c.grams==null){complete=false;break;}low+=value.low*c.grams/b.yield_g*(c.confidence==='known'?1:0.8);high+=value.high*c.grams/b.yield_g*(c.confidence==='known'?1:1.2);}
     else {const one=this._nutrition([c])[n];if(!one){complete=false;break;}low+=one.low;high+=one.high;}
    }
    nutrition[n]=complete?{low:Math.round(low*1000)/1000,high:Math.round(high*1000)/1000}:null;
   }
   const computed=components.length>0&&components.every(c=>c.grams!=null&&c.product_id&&NUTRIENTS.some(n=>this._nutrition([c])[n]!=null));
   if(!computed&&input.nutrition)nutrition=input.nutrition;
   const method=computed?'computed':input.method==='label'?'label':'estimated';
   this.store.run('UPDATE intake SET method=?,nutrition=? WHERE id=?',method,json(nutrition),intakeId);
   return {intake:this._get('intake',intakeId),components,movements,reversals,assumptions};
  });
 }
 recordReaction(input,options={}) {
  return this._write('recordReaction',input,options,event=>{
   if(input.rating!=null&&(!Number.isInteger(input.rating)||input.rating<1||input.rating>10))throw new Error('Rating must be 1–10');
   this._require('persons',input.person||this.person);
   if(input.batchId)this._require('batches',input.batchId);if(input.intakeId)this._require('intake',input.intakeId);
   return this._insert('reactions',{id:id(),person_id:input.person||this.person,batch_id:input.batchId||null,recipe_id:input.recipeId||null,intake_id:input.intakeId||null,rating:input.rating??null,aspects:input.aspects||{},notes:input.notes||null,event_id:event.id,created_at:event.occurred_at});
  });
 }
 setPreference(input,options={}) {
  return this._write('setPreference',input,options,event=>{
   const person=input.person||this.person;this._require('persons',person);
   const subject=requireText(input.subject,'Preference subject',1000).toLowerCase();
   const previous=this.store.all('SELECT id FROM preferences WHERE person_id=? AND kind=? AND subject=? AND superseded_by_id IS NULL',person,input.kind,subject);
   const p=this._insert('preferences',{id:id(),person_id:person,kind:input.kind,subject,product_id:input.productId||null,recipe_id:input.recipeId||null,stance:input.stance||null,value:input.value??null,is_hard:input.isHard||input.is_hard?1:0,statement:requireText(input.statement,'Preference statement',3000),source:input.source||'stated',confidence:input.confidence||'known',evidence:input.evidence||[event.id],superseded_by_id:null,created_at:event.occurred_at});
   for(const prev of previous)this.store.run('UPDATE preferences SET superseded_by_id=? WHERE id=?',p.id,prev.id);
   let assumption=null;if(input.source==='imported')assumption=this._assumption(event,{statement:`Confirm imported preference: ${p.statement}`,evidence:[p.id,...(input.evidence||[])]});
   return {...p,assumption};
  });
 }
 currentPreferences(person=this.person) {this._require('persons',person);return this.store.all('SELECT * FROM preferences WHERE person_id=? AND superseded_by_id IS NULL ORDER BY kind,subject',person).map(decode);}
 violations(person,ingredientNames) {return this.currentPreferences(person).filter(p=>p.is_hard&&['never','avoid'].includes(p.stance)&&ingredientNames.some(name=>matches(p.subject,name)));}
 pantryView(today=localDate(this.store.household)) {
  // Reuse kitchenReset's existing shelf-life classification for file-backed receipt products.
  const shelf=this.root?new Map(kitchenReset(this.root,today,36500).items.map(i=>[itemKey(i.fullName),i.typicalDays])):new Map();
  return this.store.all('SELECT i.*,p.name AS product_name,p.base_unit,p.kind,p.shelf_life_days,p.batch_id,r.purchased_on FROM pantry_items i JOIN products p ON p.id=i.product_id LEFT JOIN purchases pu ON pu.id=i.purchase_id LEFT JOIN receipts r ON r.id=pu.receipt_id WHERE i.household_id=? ORDER BY i.created_at,i.rowid',this.household).map(row=>{
   const balance=this.balance(row.id),date=row.purchased_on||row.created_at?.slice(0,10);
   const age=date?Math.max(0,Math.floor((Date.parse(today)-Date.parse(date.slice(0,10)))/86400000)):null;
   const days=row.location==='freezer'?null:row.shelf_life_days??(shelf.has(itemKey(row.product_name))?shelf.get(itemKey(row.product_name)):null);
   const urgency=balance.amount===0?'resolved':row.condition==='use_soon'?'soon':days===null?'stable':age===null?'unknown':age>=days?'past':age>=days*0.6?'soon':'fresh';
   return {...row,name:row.product_name,balance:balance.amount,basis:balance.basis,ageDays:age,typicalDays:days,urgency};
  });
 }
 recentBatches(days=14,at=now()) {
  const since=new Date(Date.parse(at)-days*86400000).toISOString();
  return this.store.all('SELECT b.*,p.id AS product_id,i.id AS pantry_item_id,i.location FROM batches b LEFT JOIN products p ON p.batch_id=b.id LEFT JOIN pantry_items i ON i.product_id=p.id WHERE b.household_id=? AND b.made_at>=? ORDER BY b.made_at DESC',this.household,since).map(row=>({...decode(row),balance:row.pantry_item_id?this.balance(row.pantry_item_id):{amount:null,basis:'unknown'}}));
 }
}
export const createGraph = (store,options) => new FoodGraph(store,options);
// Store-first adapters match the existing companion domain modules; the class is convenient for a request/session.
export const balance=(store,...args)=>createGraph(store).balance(...args);
export const upsertProduct=(store,...args)=>createGraph(store).upsertProduct(...args);
export const findProducts=(store,...args)=>createGraph(store).findProducts(...args);
export const setProductNutrition=(store,...args)=>createGraph(store).setProductNutrition(...args);
export const importReceipt=(store,...args)=>createGraph(store).importReceipt(...args);
export const countPantryItem=(store,...args)=>createGraph(store).countPantryItem(...args);
export const setCondition=(store,...args)=>createGraph(store).setCondition(...args);
export const tossPantryItem=(store,...args)=>createGraph(store).tossPantryItem(...args);
export const transfer=(store,...args)=>createGraph(store).transfer(...args);
export const recordBatch=(store,...args)=>createGraph(store).recordBatch(...args);
export const computeBatchNutrition=(store,...args)=>createGraph(store).computeBatchNutrition(...args);
export const recordIntake=(store,...args)=>createGraph(store).recordIntake(...args);
export const recordReaction=(store,...args)=>createGraph(store).recordReaction(...args);
export const setPreference=(store,...args)=>createGraph(store).setPreference(...args);
export const currentPreferences=(store,...args)=>createGraph(store).currentPreferences(...args);
export const violations=(store,...args)=>createGraph(store).violations(...args);
export const addAssumption=(store,...args)=>createGraph(store).addAssumption(...args);
export const resolveAssumptions=(store,...args)=>createGraph(store).resolveAssumptions(...args);
export const openAssumptions=store=>createGraph(store).openAssumptions();
export const pantryView=(store,root,today)=>createGraph(store,{root}).pantryView(today);

// recordIntake owns arithmetic and preserves batch uncertainty. This read helper exposes
// whether its linked snapshot can safely replace a food-log estimate with exact values.
export function computeIntakeNutrition(store,intakeId){
 const row=store.get('SELECT nutrition,method FROM intake WHERE id=?',intakeId);
 if(!row)throw new Error('Unknown intake');
 const nutrition=row.nutrition?JSON.parse(row.nutrition):null;
 const required=['kcal','protein_g','carbs_g','fat_g','fiber_g'];
 const uncertainYield=store.get("SELECT 1 FROM intake_components c JOIN products p ON p.id=c.product_id JOIN batches b ON b.id=p.batch_id WHERE c.intake_id=? AND b.yield_basis!='measured' LIMIT 1",intakeId);
 const complete=!uncertainYield&&row.method==='computed'&&required.every(k=>nutrition?.[k]!=null)&&NUTRIENTS.every(k=>nutrition?.[k]==null||(Number.isFinite(nutrition[k].low)&&nutrition[k].low===nutrition[k].high));
 return {complete,nutrition,values:complete?Object.fromEntries(NUTRIENTS.map(k=>[k,nutrition?.[k]?.low??null])):null};
}
