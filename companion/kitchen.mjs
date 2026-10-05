import {loadHousehold,androidActor} from './household.mjs';
import {householdPath,read,files,localDate} from './domain.mjs';

// Deterministic kitchen-reset view: what was bought or reported recently, how old it is, what the person last reported,
// and which planned recipes were never reported cooked. No inference; typical shelf lives are rough planning defaults.

const BRANDS=/^(simple truth organic|simple truth|kroger|private selection|bob's red mill|kellogg's|nancy's|barilla|bush's|fischer's|graza sizzle|graza|bragg organic|bragg|blue diamond|mahatma|general mills|pillsbury|power up|r\.w\. knudsen|morton|kikkoman|trader joe[’']s|quaker|fresh organic|fresh large|fresh)\s+/i;
const NOT_FOOD=/deodorant|peeler|detergent|soap|foil|plastic wrap|ziploc|trash bag|sponge|paper towel/i;

// Typical refrigerated life in days from purchase, first match wins. `null` means shelf-stable or frozen.
const SHELF=[
 [/frozen|mukimame|edamame|strudel|berry medley/i,null],
 [/spinach|lettuce|greens|arugula|basil|cilantro|parsley|dill/i,5],
 [/strawberr|raspberr|blackberr|blueberr/i,5],
 [/chicken|turkey|rotisserie/i,4],[/salmon|cod|fish|shrimp/i,2],
 [/mushroom/i,7],[/green onion|scallion/i,7],[/broccoli|cauliflower|gai lan|bok choy|zucchini/i,7],
 [/plum|mango|peach|banana|pear|grape/i,7],
 [/tofu/i,21],[/yogurt/i,21],[/milk|almondmilk/i,21],[/ginger/i,21],[/lime|lemon/i,21],[/gnocchi/i,30],
 [/egg/i,28],[/carrot/i,28],[/cabbage/i,30],[/potato|onion/i,30],[/garlic/i,60],
 [/sauce|vinegar|honey|oil|cereal|bran|rice|beans|lentil|penne|spaghetti|pasta|seed|almond|walnut|nut|trail mix|flax|hemp|salt|pepper|oat|juice/i,null],
];

export const shortName=name=>name.replace(BRANDS,'').replace(BRANDS,'').replace(/\s*-\s*\d.*$/,'').trim();
export const itemKey=name=>shortName(name).toLowerCase().replace(/[^a-z ]+/g,' ').replace(/\s+/g,' ').trim();
const typicalDays=name=>{for(const [re,days] of SHELF)if(re.test(name))return days;return 14};
const isoDate=value=>/^\d{4}-\d{2}-\d{2}/.test(value||'')?value.slice(0,10):null;
const daysBetween=(from,to)=>Math.round((Date.parse(to)-Date.parse(from))/86400000);
const householdDate=(root,iso)=>localDate(root,iso);
export const LEFTOVER_STATES=['still_have','eaten','discarded','frozen'];

export function kitchenReset(root,today=localDate(root),windowDays=35,store=null){
 const items=new Map();
 const upsert=(name,source)=>{
  const key=itemKey(name);if(!key||NOT_FOOD.test(name))return null;
  const item=items.get(key)||{key,name:shortName(name),fullName:name,sources:[],purchasedOn:null,location:'unknown'};
  item.sources.push(source);
  if(source.date&&(!item.purchasedOn||source.date>item.purchasedOn))item.purchasedOn=source.date;
  if(source.location&&source.location!=='unknown')item.location=source.location;
  items.set(key,item);return item;
 };
 for(const r of files(householdPath(root,'data/receipts'),root)){
  if(daysBetween(r.date,today)>windowDays)continue;
  for(const line of r.items)upsert(line.name,{type:'receipt',store:r.store,date:r.date,quantity:line.quantity});
 }
 const inventory=read(householdPath(root,'data/inventory/current.json'),{lots:[]});
 for(const lot of inventory.lots||[])upsert(lot.item,{type:'lot',date:isoDate(lot.purchased_on),location:lot.location,note:typeof lot.condition==='string'?lot.condition:null});
 const latest=new Map();
 for(const o of files(householdPath(root,'data/observations'),root)){const k=itemKey(o.item);if(!latest.has(k)||o.createdAt>latest.get(k).createdAt)latest.set(k,o)}
 for(const [k,o] of latest)if(!items.has(k)&&daysBetween(o.createdAt.slice(0,10),today)<=windowDays)upsert(o.item,{type:'observation',date:null,location:o.location});

 const rows=[...items.values()].map(item=>{
  const o=latest.get(item.key),days=typicalDays(item.fullName+' '+item.name);
  const age=item.purchasedOn?daysBetween(item.purchasedOn,today):null;
  const state=!o?'unchecked':o.condition==='discard'?'discard':o.quantityState==='none'?'gone':o.condition==='looks_ok'||o.condition==='use_soon'?o.condition:'unchecked';
  // Only a check made after the latest purchase counts; a new purchase reopens the item.
  const stale=o&&item.purchasedOn&&o.createdAt.slice(0,10)<item.purchasedOn;
  const effective=stale?'unchecked':state;
  const risk=effective==='gone'||effective==='discard'?'resolved':days===null?'stable':age===null?'unknown':age>=days?'past':age>=days*0.6?'soon':'fresh';
  return {key:item.key,name:item.name,fullName:item.fullName,location:o&&!stale&&o.location!=='unknown'?o.location:item.location,purchasedOn:item.purchasedOn,ageDays:age,typicalDays:days,
   state:effective,checkedAt:stale?null:o?.createdAt||null,note:stale?null:o?.note||null,risk,sources:item.sources};
 });
 const order={past:0,soon:1,unknown:2,fresh:3,stable:4,resolved:5};
 rows.sort((a,b)=>(a.state==='unchecked'?0:1)-(b.state==='unchecked'?0:1)||order[a.risk]-order[b.risk]||(b.ageDays??-1)-(a.ageDays??-1)||a.name.localeCompare(b.name));

 const plan=files(householdPath(root,'data/plans'),root).find(p=>p.status==='active');
 const cooking=files(householdPath(root,'data/cooking'),root);
 const planned=(plan?.meals||[]).map(m=>{
  const recipe=read(householdPath(root,'recipes',m.recipe_id,'recipe.json'));
  return {recipeId:m.recipe_id,title:recipe?.title||m.recipe_id,date:m.date,cooked:cooking.some(c=>c.recipeId===m.recipe_id)};
 });
 // A cook with no final leftover disposition may still have portions in the fridge (3–4 day window) or freezer.
 const settled=d=>/^(discarded|eaten|finished|frozen|none)(_confirmed)?$/.test(d||'');
 // Later leftover updates are events; the newest one wins over the file record.
 const latestUpdate=c=>store?.get("SELECT payload,recorded_at FROM events WHERE type='leftover_updated' AND subject=? ORDER BY recorded_at DESC,rowid DESC LIMIT 1",`cooking:${c.id}`);
 const leftovers=cooking.map(c=>{const u=latestUpdate(c);return {c,cooked:c.cookedDateEstimate?.latest||householdDate(root,c.createdAt),update:u&&JSON.parse(u.payload)}})
  .filter(({c,cooked,update})=>update?update.state==='still_have':!settled(c.leftoverReport?.disposition)&&(c.leftoverReport||daysBetween(cooked,today)<=10))
  .map(({c,cooked,update})=>{
   const recipe=read(householdPath(root,'recipes',c.recipeId,'recipe.json'));
   return {cookingId:c.id,recipeId:c.recipeId,title:recipe?.title||c.recipeId,cookedOn:cooked,ageDays:daysBetween(cooked,today),disposition:update?`still have (${update.location||'fridge'})`:c.leftoverReport?.disposition||'unknown',servings:update?.servings??c.leftoverReport?.servingsApproximate??null};
  });
 const perishable=rows.filter(r=>r.risk!=='stable');
 // Resolved leftovers in the last week stay visible so a mis-tap can be changed (the change is another event).
 const resolvedLeftovers=cooking.map(c=>({c,u:latestUpdate(c)})).filter(({u})=>u&&JSON.parse(u.payload).state!=='still_have'&&daysBetween(u.recorded_at.slice(0,10),today)<=7)
  .map(({c,u})=>({cookingId:c.id,recipeId:c.recipeId,title:read(householdPath(root,'recipes',c.recipeId,'recipe.json'))?.title||c.recipeId,state:JSON.parse(u.payload).state,updatedAt:u.recorded_at}));
 return {today,planId:plan?.id||null,planEnds:plan?.meals?.map(m=>m.date).sort().at(-1)||null,items:rows,planned,leftovers,resolvedLeftovers,
  counts:{toCheck:perishable.filter(r=>r.state==='unchecked').length,checked:perishable.filter(r=>r.state!=='unchecked').length,perishable:perishable.length,stable:rows.length-perishable.length}};
}

// Compact, deterministic context for the planning task that follows a reset.
export function resetContext(reset){
 const line=r=>`- ${r.name}${r.location!=='unknown'?` (${r.location})`:''}: ${r.state.replace('_',' ')}${r.ageDays!=null?`, bought ${r.ageDays}d ago`:''}${r.typicalDays?`, typical fridge life ~${r.typicalDays}d`:''}${r.note&&r.state!=='unchecked'?` — "${r.note.slice(0,140)}"`:''}`;
 const usable=reset.items.filter(r=>['looks_ok','use_soon'].includes(r.state)&&r.risk!=='stable');
 const unchecked=reset.items.filter(r=>r.state==='unchecked'&&r.risk!=='stable');
 const gone=reset.items.filter(r=>['gone','discard'].includes(r.state));
 const stable=reset.items.filter(r=>r.risk==='stable'&&!['gone','discard'].includes(r.state));
 return [
  `Kitchen reset on ${reset.today}. Active plan ${reset.planId||'none'} ends ${reset.planEnds||'n/a'}.`,
  `Use soon or confirmed usable (${usable.length}):`,...usable.map(line),
  `Not checked yet (${unchecked.length}; treat as unknown, never as available):`,...unchecked.map(line),
  `Gone or discarded (${gone.length}; do not plan with these):`,...gone.map(r=>`- ${r.name}`),
  `Shelf-stable or frozen, bought or reported recently (quantities unknown): ${stable.map(r=>r.name).join('; ')||'none'}`,
  `Planned recipes: ${reset.planned.map(p=>`${p.title} (${p.date}, ${p.cooked?'reported cooked':'not reported cooked'})`).join('; ')||'none'}`,
  `Unresolved leftovers: ${reset.leftovers.map(l=>`${l.title}, cooked ~${l.cookedOn} (${l.ageDays}d ago), ${l.disposition}`).join('; ')||'none'}`,
 ].join('\n');
}

export function recordLeftover(store,root,input){
 const cookingId=typeof input.cookingId==='string'&&/^[a-f0-9]{24}$/.test(input.cookingId)?input.cookingId:null;
 if(!cookingId||!read(householdPath(root,'data/cooking',cookingId+'.json')))throw new Error('Unknown cooking report');
 if(!LEFTOVER_STATES.includes(input.state))throw new Error('Choose still have, eaten, discarded or frozen');
 const key=typeof input.idempotencyKey==='string'&&input.idempotencyKey.length<=150?input.idempotencyKey:null;if(!key)throw new Error('Request ID is required');
 const prior=store.all("SELECT payload FROM events WHERE type='leftover_updated' AND subject=?",`cooking:${cookingId}`).map(r=>JSON.parse(r.payload)).find(p=>p.idempotencyKey===key);
 if(prior)return prior;
 const servings=Number.isFinite(input.servings)&&input.servings>=0&&input.servings<=40?input.servings:null;
 const payload={cookingId,state:input.state,servings,location:['fridge','freezer'].includes(input.location)?input.location:input.state==='frozen'?'freezer':'fridge',idempotencyKey:key};
 store.record('leftover_updated',`cooking:${cookingId}`,payload,{actor:androidActor(store.household||loadHousehold(root))});
 return payload;
}
