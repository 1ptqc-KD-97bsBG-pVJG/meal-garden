import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {loadHousehold,profilePath} from './household.mjs';
export const now = () => new Date().toISOString();
export const id = () => crypto.randomUUID();
export const hash = s => crypto.createHash('sha256').update(s).digest('hex');
export function read(file, fallback=null) { try { return JSON.parse(fs.readFileSync(file,'utf8')); } catch(e) { if(e.code==='ENOENT') return fallback; throw e; } }
export function files(dir,root=null) { if(root)dir=householdPath(root,dir);return fs.existsSync(dir) ? fs.readdirSync(dir).filter(n=>n.endsWith('.json')).sort().map(n=>read(root?householdPath(root,dir,n):path.join(dir,n))) : []; }
export function atomic(file, data) { fs.mkdirSync(path.dirname(file),{recursive:true}); const tmp=`${file}.${id()}.tmp`; fs.writeFileSync(tmp,JSON.stringify(data,null,2)+'\n');fs.renameSync(tmp,file); }
// Lexical containment is insufficient when a household directory contains a symlink.
// Check the closest existing ancestor as well so new files cannot be written through an escaping link.
export function householdPath(root,...parts) {
 const base=path.resolve(root),target=path.resolve(base,...parts);
 if(target!==base&&!target.startsWith(base+path.sep))throw new Error('File path must stay inside its household');
 const baseReal=fs.realpathSync(base);let ancestor=target;
 for(;;){try{fs.lstatSync(ancestor);break}catch(e){if(e.code!=='ENOENT')throw e;const parent=path.dirname(ancestor);if(parent===ancestor)throw e;ancestor=parent;}}
 const real=fs.realpathSync(ancestor);
 if(real!==baseReal&&!real.startsWith(baseReal+path.sep))throw new Error('File path must stay inside its household');
 return target;
}
export function localDate(rootOrHousehold=null,at=new Date()) { const household=typeof rootOrHousehold==='string'?loadHousehold(rootOrHousehold):rootOrHousehold;return new Intl.DateTimeFormat('en-CA',{timeZone:household?.timezone||'UTC',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(at)); }
export function requireText(value, name, max=10000) { if(typeof value!=='string'||!value.trim()||value.length>max) throw new Error(`${name} must be nonempty text (up to ${max} characters).`); return value.trim(); }
export function snapshot(root) {
 const household=loadHousehold(root);
 const recipes=(fs.existsSync(householdPath(root,'recipes'))?fs.readdirSync(householdPath(root,'recipes')):[]).map(n=>read(householdPath(root,'recipes',n,'recipe.json'))).filter(Boolean);
 recipes.sort((a,b)=>(a.readiness==='ready'?0:1)-(b.readiness==='ready'?0:1)||a.title.localeCompare(b.title));
 const plans=files(householdPath(root,'data/plans'),root), active=plans.find(p=>p.status==='active');
 const today=localDate(household);
 const trip=read(householdPath(root,'data/shopping/current-trip.json'));
 return {schemaVersion:1, household:{id:household.id,name:household.name,person:household.person,timezone:household.timezone,shopping:household.shopping||null},generatedAt:now(),today,recipes,plans,activePlan:active||null,planIsPast:!!active&&active.meals.every(m=>m.date<today), inventory:read(householdPath(root,'data/inventory/current.json')),shopping:read(householdPath(root,'generated/shopping-demand.json')),shoppingTrip:trip?{...trip,reviewKey:hash(JSON.stringify(trip))}:null,integration:read(householdPath(root,'data/integrations/samsung-food.json')),profile:read(householdPath(root,profilePath(root,household))),kitchen:read(householdPath(root,'profile/kitchen.json')),dining:files(householdPath(root,'data/dining'),root),observations:files(householdPath(root,'data/observations'),root).sort((a,b)=>b.createdAt.localeCompare(a.createdAt)),cooking:files(householdPath(root,'data/cooking'),root).sort((a,b)=>b.createdAt.localeCompare(a.createdAt)),receipts:files(householdPath(root,'data/receipts'),root).sort((a,b)=>b.date.localeCompare(a.date))};
}
export function recordObservation(root, input) {
 const item=requireText(input.item,'Item',150), note=requireText(input.note,'Note',2000);
 const key=requireText(input.idempotencyKey,'Request ID',150);
 const estimate=input.quantityEstimate;
 const quantityEstimate=estimate&&Number.isFinite(estimate.value)&&estimate.value>0&&estimate.value<=10000&&['oz','lb','count','bunch'].includes(estimate.unit)?{value:estimate.value,unit:estimate.unit,certainty:estimate.certainty==='confirmed'?'confirmed':'rough estimate'}:null;
 const bestIfUsedBy=typeof input.bestIfUsedBy==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(input.bestIfUsedBy)?input.bestIfUsedBy:null;
 const preservationNeed=input.preservationNeed==='consider_use_or_freeze'?'consider_use_or_freeze':null;
 const record={schema_version:1,id:hash(key).slice(0,24),item,note,quantityState:['unknown','none','low','enough','exact'].includes(input.quantityState)?input.quantityState:'unknown',quantityEstimate,condition:['unchecked','looks_ok','use_soon','discard'].includes(input.condition)?input.condition:'unchecked',location:['unknown','pantry','fridge','freezer','counter'].includes(input.location)?input.location:'unknown',bestIfUsedBy,preservationNeed:input.condition==='discard'?null:preservationNeed,source:`${loadHousehold(root).person.name} via Meal Garden`,createdAt:now(),status:'reported_observation'};
 const file=householdPath(root,'data/observations',record.id+'.json');if(fs.existsSync(file))return read(file);atomic(file,record);return record;
}
export function recordAppNote(root,input) {
 const id=requireText(input.id,'Note ID',36);
 if(!/^[a-f0-9-]{36}$/.test(id))throw new Error('Invalid note ID');
 const file=householdPath(root,'data/app-feedback',id+'.json');
 if(typeof input.text!=='string'||!input.text.trim()||input.text.length>1000000)throw new Error('Feedback must be nonempty text (up to 1,000,000 characters).');
 if(fs.existsSync(file)){
  const existing=read(file);
  if(existing.text!==input.text||((existing.screenshot?.sha256||null)!==(input.screenshot?.sha256||null))||(existing.apkSha256||'')!==(input.apkSha256||''))throw new Error('Note ID already exists with different content');
  if(existing.screenshot){const savedImage=householdPath(root,existing.screenshot.path);if(!fs.existsSync(savedImage)||hash(fs.readFileSync(savedImage))!==existing.screenshot.sha256)throw new Error('Saved screen image is missing or changed');}
  return existing;
 }
 const phoneTime=requireText(input.phoneTime,'Phone time',40),screen=requireText(input.screen,'Screen',100);
 if(input.apkSha256!=null&&input.apkSha256!==''&&!/^[a-f0-9]{64}$/.test(input.apkSha256))throw new Error('Invalid APK checksum');
 const context=input.screenContext&&typeof input.screenContext==='object'&&!Array.isArray(input.screenContext)?input.screenContext:null;
 const screenContext=context?{
  route:String(context.route||'').slice(0,150),title:String(context.title||'').slice(0,100),
  sourceFile:String(context.sourceFile||'').slice(0,250),capturedAt:String(context.capturedAt||'').slice(0,40),
  tab:String(context.tab||'').slice(0,30),recipeRevision:Number.isInteger(context.recipeRevision)?context.recipeRevision:null,
  appVersionCode:Number.isInteger(context.appVersionCode)?context.appVersionCode:null,
  apkSha256:/^[a-f0-9]{64}$/.test(context.apkSha256||'')?context.apkSha256:null,
  captureStatus:String(context.captureStatus||'').slice(0,40),captureError:String(context.captureError||'').slice(0,200),
 }:null;
 let screenshot=null;
 if(input.screenshot!=null){
  const meta=input.screenshot,data=input.screenshotData;
  if(typeof data!=='string'||data.length>1400000||!/^[A-Za-z0-9+/]+={0,2}$/.test(data))throw new Error('Screen image is missing or too large');
  const bytes=Buffer.from(data,'base64'),digest=hash(bytes);
  if(bytes.length<4||bytes.length>1024*1024||bytes[0]!==0xff||bytes[1]!==0xd8||bytes[bytes.length-2]!==0xff||bytes[bytes.length-1]!==0xd9||digest!==meta.sha256)throw new Error('Screen image checksum or JPEG data is invalid');
  const width=meta.width,height=meta.height;
  if(!Number.isInteger(width)||!Number.isInteger(height)||width<1||height<1||width>4096||height>8192)throw new Error('Screen image dimensions are invalid');
  const relative=`data/app-feedback/screenshots/${id}.jpg`,image=householdPath(root,relative),tmp=`${image}.${crypto.randomUUID()}.tmp`;
  fs.mkdirSync(path.dirname(image),{recursive:true,mode:0o700});
  try{fs.writeFileSync(tmp,bytes,{mode:0o600});fs.renameSync(tmp,image)}finally{if(fs.existsSync(tmp))fs.rmSync(tmp)}
  screenshot={path:relative,sha256:digest,bytes:bytes.length,width,height,capturedAt:String(meta.capturedAt||'').slice(0,40)};
 }
 const record={schema_version:2,id,text:input.text,createdAt:now(),phoneTime,screen,screenContext,screenshot,target:String(input.target||'').slice(0,150),recipeId:String(input.recipeId||'').slice(0,100),position:input.position&&Number.isFinite(input.position.x)&&Number.isFinite(input.position.y)&&input.position.x>=0&&input.position.x<=1&&input.position.y>=0&&input.position.y<=1?{x:input.position.x,y:input.position.y}:null,device:String(input.device||'').slice(0,100),appVersion:String(input.appVersion||'').slice(0,30),appVersionCode:Number.isInteger(input.appVersionCode)?input.appVersionCode:null,apkSha256:input.apkSha256||null,source:`${loadHousehold(root).person.name} via Meal Garden · no model call`};
 atomic(file,record);return record;
}
export function recordCooking(root,input) {
 const recipeId=requireText(input.recipeId,'Recipe',100);if(!/^[a-z0-9-]+$/.test(recipeId))throw new Error('Invalid recipe ID');
 const recipe=read(householdPath(root,'recipes',recipeId,'recipe.json'));if(!recipe||recipe.readiness!=='ready')throw new Error('Only a ready recipe can be recorded as cooked.');
 const key=requireText(input.idempotencyKey,'Request ID',150);
 const record={schema_version:1,id:hash(key).slice(0,24),recipeId,recipeRevision:recipe.revision,createdAt:now(),source:`${loadHousehold(root).person.name} via Meal Garden`,status:'reported_cooked',note:requireText(input.note,'Cooking report',3000),inventoryReconciled:false};
 const file=householdPath(root,'data/cooking',record.id+'.json');if(fs.existsSync(file))return read(file);atomic(file,record);return record;
}
export function saveReceipt(root,input) {
 const store=requireText(input.store,'Store',100),date=requireText(input.date,'Date',10);if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(Date.parse(date)))throw new Error('Invalid receipt date');
 const fingerprint=requireText(input.fingerprint,'Stable receipt fingerprint',250);
 if(!Array.isArray(input.items)||input.items.length<1||input.items.length>300)throw new Error('Receipt needs 1–300 items');
 const items=input.items.map(x=>({name:requireText(x.name,'Item name',200),quantity:String(x.quantity??'unknown').slice(0,100),price:typeof x.price==='number'&&Number.isFinite(x.price)?x.price:null}));
 const record={schema_version:1,id:hash(`${store.toLowerCase()}:${fingerprint}`).slice(0,24),store,date,total:typeof input.total==='number'&&Number.isFinite(input.total)?input.total:null,items,source:requireText(input.source,'Source',500),createdAt:now(),inventoryReconciled:false};
 const file=householdPath(root,'data/receipts',record.id+'.json');if(fs.existsSync(file))return read(file);atomic(file,record);return record;
}
export function validatePanel(input,root) {
 const title=requireText(input.title,'Card title',120), body=requireText(input.body,'Card body',4000);
 const actions=(input.actions||[]).slice(0,5).map(a=>{
  const label=requireText(a.label,'Action label',60);if(a.type==='recipe') {const value=requireText(a.value,'Recipe ID',100);if(!/^[a-z0-9-]+$/.test(value)||!fs.existsSync(householdPath(root,'recipes',value,'recipe.json')))throw new Error('Unknown recipe');return {label,type:'recipe',value};}
  if(a.type==='prompt')return {label,type:'prompt',value:requireText(a.value,'Prompt',2000)};
  throw new Error('Cards allow recipe links and user-triggered prompts only');
 });
 return {id:id(),title,body,actions};
}
