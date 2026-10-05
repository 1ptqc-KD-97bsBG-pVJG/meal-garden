import {loadHousehold,androidActor} from './household.mjs';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {householdPath,requireText} from './domain.mjs';
import {FoodGraph,computeIntakeNutrition} from './graph.mjs';

// Food capture: the photo and the person's words are evidence, saved before any inference runs.
export const CAPTURE_KINDS=['meal','snack','drink','supplement','label','receipt','menu','groceries','fridge','other'];
export const FOOD_CATEGORIES=['meal','snack','drink','supplement','nutrition_label','receipt','menu','groceries','fridge_or_pantry','not_food','unclear'];
export const MAX_CAPTURE_PHOTOS=10;
const mediaList=payload=>payload.mediaList??(payload.media?[payload.media]:[]);
const UUID=/^[a-f0-9-]{36}$/;
const short=(value,max)=>typeof value==='string'?value.slice(0,max):'';
const int=value=>Number.isInteger(value)&&value>0&&value<20000?value:null;
const subjectOf=id=>`capture:${id}`;

function requireId(value,name){const id=requireText(value,name,36);if(!UUID.test(id))throw new Error(`Invalid ${name.toLowerCase()}`);return id}

function jpeg(data,sha){
 if(typeof data!=='string'||data.length>11_500_000||!/^[A-Za-z0-9+/]+={0,2}$/.test(data))throw new Error('Photo is missing or too large');
 const bytes=Buffer.from(data,'base64');
 if(bytes.length<4||bytes.length>8*1024*1024||bytes[0]!==0xff||bytes[1]!==0xd8)throw new Error('Use a JPEG photo up to 8 MB');
 const digest=crypto.createHash('sha256').update(bytes).digest('hex');
 if(sha&&sha!==digest)throw new Error('Photo checksum does not match the phone copy');
 return {bytes,sha256:digest};
}

export function recordCapture(store,runtime,input,{actor=androidActor(store.household)}={}){
 runtime=householdPath(store.root||runtime,runtime);
 const id=requireId(input.id,'Capture ID'),subject=subjectOf(id);
 const kind=CAPTURE_KINDS.includes(input.kind)?input.kind:'other',note=short(input.note,4000);
 const capturedAt=requireText(input.phoneTime,'Phone time',45);
 if(input.photos!=null&&(!Array.isArray(input.photos)||input.photos.length>MAX_CAPTURE_PHOTOS))throw new Error('Use up to 10 photos per entry');
 if(input.photos!=null&&input.imageData!=null)throw new Error('Use photos or the legacy photo field, not both');
 const inputs=input.photos??(input.imageData!=null?[input]:[]);
 const photos=inputs.map(p=>{if(!p||typeof p!=='object')throw new Error('Invalid photo');return {...jpeg(p.imageData,p.imageSha256),width:int(p.width),height:int(p.height)}});
 if(photos.reduce((sum,p)=>sum+p.bytes.length,0)>24*1024*1024)throw new Error('Photos must total at most 24 MB per entry');
 if(!photos.length&&!note.trim())throw new Error('Add a photo or a note');
 const existing=store.get("SELECT * FROM events WHERE subject=? AND type='captured'",subject);
 if(existing){
  const p=JSON.parse(existing.payload);
  if(JSON.stringify(mediaList(p).map(m=>m.sha256))!==JSON.stringify(photos.map(p=>p.sha256))||p.note!==note)throw new Error('Capture ID already exists with different content');
  return {capture:captureView(store,id),created:false};
 }
 const savedMedia=photos.map(photo=>{
  const relative=`media/${photo.sha256}.jpg`,file=householdPath(store.root||runtime,runtime,relative);
  if(!fs.existsSync(file)){
   fs.mkdirSync(path.dirname(file),{recursive:true,mode:0o700});
   const tmp=`${file}.${crypto.randomUUID()}.tmp`;
   try{fs.writeFileSync(tmp,photo.bytes,{mode:0o600});fs.renameSync(tmp,file)}finally{if(fs.existsSync(tmp))fs.rmSync(tmp)}
  }
  return {sha256:photo.sha256,path:relative,bytes:photo.bytes.length,width:photo.width,height:photo.height};
 });
 store.db.exec('BEGIN IMMEDIATE');
 try{
  for(const media of savedMedia)store.run('INSERT OR IGNORE INTO media(sha256,path,mime,bytes,width,height,created) VALUES(?,?,?,?,?,?,?)',media.sha256,media.path,'image/jpeg',media.bytes,media.width,media.height,new Date().toISOString());
  store.record('captured',subject,{captureId:id,kind,note,capturedAt,timeZone:short(input.timeZone,60),device:short(input.device,100),appVersion:short(input.appVersion,30),media:savedMedia[0]??null,mediaList:savedMedia},{occurredAt:capturedAt,actor});
  store.db.exec('COMMIT');
 }catch(e){store.db.exec('ROLLBACK');throw e}
 return {capture:captureView(store,id),created:true};
}

export function addCaptureDetail(store,input,{actor=androidActor(store.household)}={}){
 const id=requireId(input.id,'Capture ID'),detailId=requireId(input.detailId,'Detail ID');
 const text=requireText(input.text,'Detail',4000),subject=subjectOf(id);
 if(!store.get("SELECT 1 FROM events WHERE subject=? AND type='captured'",subject))throw new Error('Unknown capture');
 const prior=store.events(subject).find(e=>e.type==='detail_added'&&e.payload.detailId===detailId);
 if(prior){if(prior.payload.text!==text)throw new Error('Detail ID already exists with different text');return {capture:captureView(store,id),created:false}}
 store.record('detail_added',subject,{captureId:id,detailId,text},{occurredAt:requireText(input.phoneTime,'Phone time',45),actor});
 return {capture:captureView(store,id),created:true};
}

const range=(value,max)=>{
 if(value==null)return null;
 const low=value.low,high=value.high;
 if(typeof low!=='number'||typeof high!=='number'||!Number.isFinite(low)||!Number.isFinite(high)||low<0||high<low||high>max)throw new Error('Nutrition ranges need 0 ≤ low ≤ high');
 return {low:Math.round(low*10)/10,high:Math.round(high*10)/10};
};

export function recordInterpretation(store,expectedCaptureId,input,jobId){
 if(input.captureId!==expectedCaptureId)throw new Error('This task can only record the capture it was given');
 const category=FOOD_CATEGORIES.includes(input.category)?input.category:'unclear';
 const items=(Array.isArray(input.items)?input.items:[]).slice(0,30).map(x=>({name:requireText(x.name,'Item name',150),portion:short(x.portion,150),confidence:['low','medium','high'].includes(x.confidence)?x.confidence:'low'}));
 const n=input.nutrition||null;
 let nutrition=n?{calories:range(n.calories,6000),protein_g:range(n.protein_g,400),carbs_g:range(n.carbs_g,800),fat_g:range(n.fat_g,400),fiber_g:range(n.fiber_g,150),sodium_mg:range(n.sodium_mg,15000),saturated_fat_g:range(n.saturated_fat_g,200),added_sugar_g:range(n.added_sugar_g,400)}:null;
 const payload={captureId:expectedCaptureId,jobId,category,title:requireText(input.title,'Title',120),items,nutrition,
  matchedRecipeId:typeof input.matchedRecipeId==='string'&&/^[a-z0-9-]{1,100}$/.test(input.matchedRecipeId)?input.matchedRecipeId:null,
  confidence:['low','medium','high'].includes(input.overallConfidence)?input.overallConfidence:'low',
  questions:(Array.isArray(input.questions)?input.questions:[]).slice(0,2).map(q=>requireText(q,'Question',300)),notes:short(input.notes,2000)};
 const captured=store.events(subjectOf(expectedCaptureId)).find(e=>e.type==='captured');
 if(!captured)throw new Error('Unknown capture');
 const graph=new FoodGraph(store),actor=`agent:job:${jobId}`;
 if(input.batchId&&input.components?.length)throw new Error('Link a batch or product components, not both');
 const weightG=input.weightG??null;
 if(weightG!=null&&(typeof weightG!=='number'||!Number.isFinite(weightG)||weightG<0))throw new Error('Weight must be a nonnegative number');
 const confidence=payload.confidence==='high'?'known':'assumed';
 if(input.components!=null&&(!Array.isArray(input.components)||input.components.length>30))throw new Error('Use up to 30 product components');
 const pickLot=productId=>{
  // A reread keeps its original lot even when the first reading consumed all of it.
  const previous=store.get('SELECT c.pantry_item_id FROM intake_components c JOIN intake i ON i.id=c.intake_id WHERE i.capture_id=? AND i.superseded_by_id IS NULL AND c.product_id=? AND c.pantry_item_id IS NOT NULL',expectedCaptureId,productId);
  if(previous)return previous.pantry_item_id;
  const lots=store.all('SELECT id FROM pantry_items WHERE product_id=? AND household_id=? AND created_at<=?',productId,graph.household,new Date(captured.occurred_at).toISOString());

  const available=lots.filter(p=>graph.balance(p.id,captured.occurred_at).amount==null||graph.balance(p.id,captured.occurred_at).amount>0);
  return available.length===1?available[0].id:null;
 };
 const components=(input.components||[]).map(c=>{
  const product=graph.getProduct(c.productId);
  if(typeof c.grams!=='number'||!Number.isFinite(c.grams)||c.grams<0)throw new Error('Component grams must be a nonnegative number');
  return {productId:product.id,name:product.name,grams:c.grams,confidence,pantryItemId:pickLot(product.id)};
 });
 if(input.batchId){
  if(weightG==null)throw new Error('A batch link requires weightG');
  const product=store.get('SELECT p.id,p.name,b.made_at FROM products p JOIN batches b ON b.id=p.batch_id WHERE p.batch_id=? AND b.household_id=?',input.batchId,graph.household);
  if(!product)throw new Error('Unknown batch');
  if(Date.parse(product.made_at)>Date.parse(captured.occurred_at))throw new Error('The batch was made after this capture');
  components.push({productId:product.id,name:product.name,grams:weightG,confidence,pantryItemId:pickLot(product.id)});
 }
 const consumed=['meal','snack','drink','supplement'].includes(category);
 if(!consumed&&components.length)throw new Error('Only consumed food may link intake components');
 const names={calories:'kcal',saturated_fat_g:'sat_fat_g'};
 const graphNutrition=nutrition?Object.fromEntries(Object.entries(nutrition).map(([k,v])=>[names[k]||k,v])):null;
 // Commit the linked intake, its ledger movements and the interpretation together.
 store.db.exec('BEGIN IMMEDIATE');
 try{
  const result=graph.recordIntake({captureId:expectedCaptureId,eatenAt:captured.occurred_at,title:payload.title,category,components:consumed?components:[],nutrition:graphNutrition,confidence},{idempotencyKey:`interpretation:${jobId}:${expectedCaptureId}`,actor,evidence:[captured.id]});
  const computed=computeIntakeNutrition(store,result.intake.id);
  if(computed.complete){
   nutrition=Object.fromEntries(Object.entries(computed.values).map(([k,v])=>[k==='kcal'?'calories':k==='sat_fat_g'?'saturated_fat_g':k,v==null?null:{low:v,high:v}]));
  }
  payload.nutrition=nutrition;payload.method=computed.complete?'computed':'estimated';
  payload.intakeId=result.intake.id;payload.batchId=input.batchId||null;payload.weightG=weightG;
  payload.components=components.map(c=>({productId:c.productId,grams:c.grams}));
  if(input.productNutrition?.length){
   if(category!=='nutrition_label'&&(captured.payload.mediaList||[]).length<2)throw new Error('Product label facts require a label entry or multiple photos');
   payload.productNutrition=input.productNutrition.slice(0,30).map(item=>{graph.getProduct(item.productId);const n=item.nutrition;if(!['per_100g','per_100ml','per_unit'].includes(n?.nutrition_basis))throw new Error('Label facts need a nutrition basis');for(const k of ['kcal','protein_g','carbs_g','fat_g','fiber_g','sodium_mg','sat_fat_g','added_sugar_g','serving_g'])if(n[k]!=null&&(typeof n[k]!=='number'||!Number.isFinite(n[k])||n[k]<0))throw new Error('Invalid label value');return {productId:item.productId,name:requireText(item.name,'Label product name',150),nutrition:n};});
  }
  // Incomplete links remain visible without replacing the model's ranges.
  store.run('UPDATE intake SET method=?,nutrition=? WHERE id=?',payload.method,JSON.stringify(computed.complete?computed.nutrition:graphNutrition?{...graphNutrition,sources:computed.nutrition?.sources||[]}:null),result.intake.id);
  store.record('interpreted',subjectOf(expectedCaptureId),payload,{actor});
  store.db.exec('COMMIT');
  return payload;
 }catch(e){store.db.exec('ROLLBACK');throw e;}

}

export function captureView(store,id){
 const events=store.events(subjectOf(id)),captured=events.find(e=>e.type==='captured');
 if(!captured)return null;
 const last=type=>events.filter(e=>e.type===type).at(-1);
 const interpreted=last('interpreted'),failed=last('interpretation_failed'),requested=last('interpretation_requested');
 const newest=[interpreted,failed,requested].filter(Boolean).sort((a,b)=>a.recorded_at.localeCompare(b.recorded_at)).at(-1);
 let status=!newest?'pending':newest.type==='interpreted'?'interpreted':newest.type==='interpretation_failed'?'failed':'interpreting';
 let error=status==='failed'?failed.payload.error:null;
 if(status==='interpreting'){
  const job=store.get('SELECT status,error FROM jobs WHERE id=?',requested.payload.jobId);
  if(!job||['interrupted','failed','cancelled'].includes(job.status)){status='failed';error=job?.error||'The interpretation task stopped.'}
 }
 return {id,kind:captured.payload.kind,note:captured.payload.note,capturedAt:captured.occurred_at,recordedAt:captured.recorded_at,media:captured.payload.media,mediaList:mediaList(captured.payload),
  details:events.filter(e=>e.type==='detail_added').map(e=>({id:e.payload.detailId,text:e.payload.text,at:e.occurred_at})),
  status,interpretation:interpreted?.payload||null,lastInterpretationAt:interpreted?.recorded_at||null,error};
}

export function recentCaptures(store,limit=80){
 return store.all("SELECT subject FROM events WHERE type='captured' ORDER BY occurred_at DESC LIMIT ?",limit).map(r=>captureView(store,r.subject.slice(8))).filter(Boolean);
}

export function interpretationPrompt(view,household=loadHousehold()){
 const lines=[`Interpret food capture ${view.id} for ${household.person.name}'s food log.`,`Captured at: ${view.capturedAt} (phone time; household timezone ${household.timezone}).`,`Kind chosen: ${view.kind}.`];
 if(view.note)lines.push(`The person's note: ${JSON.stringify(view.note)}`);
 for(const d of view.details)lines.push(`Detail added later: ${JSON.stringify(d.text)}`);
 if(view.interpretation)lines.push(`Your previous interpretation (revise it using the new details): ${JSON.stringify({title:view.interpretation.title,items:view.interpretation.items,nutrition:view.interpretation.nutrition})}`);
 const photos=mediaList(view);
 lines.push(photos.length?`The ${photos.length} attached photo(s) are evidence for ONE entry. Consider them together: a food photo and its package/nutrition labels may show the same components. Use labels to refine ingredients and per-serving nutrition, estimate the amount actually eaten, and do not count the food again for each label or angle. A package serving size does not establish how much the person ate.`:'There is no photo; use the person’s words only and keep confidence low unless they are specific.');
 lines.push(
  'Decide what the capture shows. For food or drink, list the components with estimated portions and give nutrition as honest ranges (calories, protein, carbs, fat, fiber, sodium in mg, saturated fat in g, added sugar in g) that reflect real photo uncertainty; never fake precision. For an entry consisting only of nutrition labels, transcribe the per-serving values and serving size, using equal low and high values. For a receipt or menu, list the food items and leave nutrition null unless a dish is clearly identified.',
  'Leave unknown nutrients null, particularly sodium and added sugar when brands, sauces or amounts are unclear. Total sugar is not added sugar. Never count a label-only entry as food consumed. For meal/snack/drink categories, nutrition refers to the amount eaten or drunk, not the package or batch. If consumption is unclear, use category unclear and ask whether it was eaten.',
  'When labels identify actual products, supply productNutrition with that product id, name, explicit basis, serving grams when visible and transcribed nutrient values. For multi-photo meals, productNutrition is label facts only; the ordinary nutrition field is the meal total. Never turn meal estimates into label facts.',
  'Look at the context packet first and use lookup tools to match actual products and batches by id. Supply batchId and weightG for weighed leftovers, or components with productId and grams for foods eaten directly. Code calculates linked nutrition. Do not invent a product or silently guess missing batch weights. If a note describes making an unrecorded batch, call meal_garden_record_batch with the actual ingredients and evidence before linking it.',
  'If the photo plausibly shows one of the household’s home-cooked recipes, you may read recipes/*/recipe.json and data/plans/ to match it; set matchedRecipeId only when the match is likely. Do not modify any files.',
  'Ask at most two questions, and only when the answer would materially change the estimate, such as which protein it was or how much was eaten. Otherwise leave questions empty; low-value uncertainty stays in the ranges.',
  `Call meal_garden_food_log_result exactly once with captureId ${view.id}, then reply with one short sentence.`);
 return lines.join('\n');
}
