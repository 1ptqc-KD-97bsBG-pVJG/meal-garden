import {loadHousehold} from './household.mjs';
import fs from 'node:fs';
import {FoodGraph,NUTRIENTS} from './graph.mjs';
import {householdPath,files,read,localDate} from './domain.mjs';
import {healthPreferences} from './health.mjs';
import {currentLearning} from './learning.mjs';

const LIMIT=6000;
const compact=(value,max=350)=>String(value??'unknown').replace(/\s+/g,' ').slice(0,max);
const number=value=>value==null?'unknown':String(Math.round(value*100)/100);
export const foodDay=(iso,household=null)=>localDate(household,iso);
const tokens=query=>String(query||'').toLowerCase().match(/[a-z]+/g)?.filter(w=>w.length>2&&!['the','and','with','from','last','night','nights','tonight','yesterday','grams','gram','about','had','ate','for','some','this','that','made'].includes(w))||[];

export function searchPantry(store,root,query){
 const graph=new FoodGraph(store,{root});
 const products=[...new Map(tokens(query).flatMap(word=>graph.findProducts(word)).map(p=>[p.id,p])).values()];
 const ids=new Set(products.map(p=>p.id));
 return {products,pantry:graph.pantryView().filter(p=>ids.has(p.product_id))};
}

export function foodLog(store,day=localDate(store.household),person=store.household?.person.id||'owner'){
 const identity=store.get('SELECT household_id FROM persons WHERE id=?',person);
 if(!identity||identity.household_id!==(store.household?.id||'home'))throw new Error('Person is outside this household');
 if(!/^\d{4}-\d{2}-\d{2}$/.test(day)||!Number.isFinite(Date.parse(day)))throw new Error('Use a food-log day as YYYY-MM-DD');
 return store.all('SELECT * FROM intake WHERE person_id=? AND superseded_by_id IS NULL ORDER BY eaten_at,rowid',person)
  .filter(i=>foodDay(i.eaten_at,store.household)===day).map(i=>({...i,nutrition:i.nutrition?JSON.parse(i.nutrition):null,
   components:store.all('SELECT c.*,p.batch_id FROM intake_components c LEFT JOIN products p ON p.id=c.product_id WHERE c.intake_id=? ORDER BY c.rowid',i.id)}));
}

const nutrientText=(p)=>`${p.nutrition_basis||'basis unknown'}: ${NUTRIENTS.map(n=>`${n} ${number(p[n])}`).join(', ')}; source ${p.nutrition_source||'none'}${p.nutrition_url?` (${compact(p.nutrition_url,180)})`:''}`;
const span=r=>r==null?'unknown':r.low===r.high?number(r.low):`${number(r.low)}–${number(r.high)}`;
const totalsText=n=>NUTRIENTS.map(k=>`${k} ${span(n?.[k])}`).join(', ');
const pantryLine=p=>`${compact(p.name,100)} [lot ${p.id}; product ${p.product_id}]: ${number(p.balance)} ${p.base_unit} left (${p.basis}), ${p.location}, ${p.urgency}${p.condition?`, marked ${p.condition}`:''}; age ${number(p.ageDays)} days`;
function batchLine(b){
 const portions=b.yield_g>0&&b.portions_made!=null&&b.balance.amount!=null?b.portions_made*b.balance.amount/b.yield_g:null;
 const per100=Object.fromEntries(NUTRIENTS.map(n=>[n,b.yield_g>0&&b.nutrition_total?.[n]?{low:b.nutrition_total[n].low/b.yield_g*100,high:b.nutrition_total[n].high/b.yield_g*100}:null]));
 return `${compact(b.title,100)} [batch ${b.id}; product ${b.product_id}; lot ${b.pantry_item_id}]: made ${b.made_at}; portions left ${number(portions)}; weight left ${number(b.balance.amount)} g (${b.balance.basis}); total weight ${number(b.yield_g)} g (${b.yield_basis}); per 100 g ${totalsText(per100)}; ingredients ${b.ingredients.map(i=>`${compact(i.name,60)} [${i.product_id}] ${number(i.grams)} g`).join(', ')||'unknown'}`;
}
export function recentBatches(store,root,{days=7,at=new Date().toISOString()}={}){
 const graph=new FoodGraph(store,{root});
 return graph.recentBatches(days,at).filter(b=>Date.parse(b.made_at)<=Date.parse(at)).map(b=>({...b,ingredients:store.all('SELECT * FROM batch_ingredients WHERE batch_id=? ORDER BY rowid',b.id)}));
}

// Bound every section separately so a large pantry cannot crowd out intake or assumptions.
function packet(sections){
 const omitted=[];
 const rendered=sections.map(([title,rows,budget])=>{
  const lines=[];let size=title.length+5,skipped=0,shortened=0;
  for(const row of rows){
   const max=Math.min(800,budget-title.length-30),value=compact(row,max),trimmed=String(row).length>max;
   const line=`- ${value}${trimmed?' …':''}\n`;
   if(size+line.length<=budget){lines.push(line);size+=line.length;if(trimmed)shortened++;}else skipped++;
  }
  if(skipped||shortened)omitted.push(`${title}: ${skipped} records, ${shortened} shortened`);
  return `## ${title}\n${lines.length?lines.join(''):'- None recorded in this section.\n'}`;
 });
 const footer=`\nOmitted: ${omitted.length?omitted.join('; ')+'. Use lookup tools for details.':'none.'}`;
 const body=rendered.join('\n');
 return body.length+footer.length<=LIMIT?body+footer:body.slice(0,LIMIT-Math.min(footer.length,650)-50)+`\nPacket text shortened. ${footer.slice(0,650)}`;
}

function preferences(graph,task){
 const prefs=graph.currentPreferences();
 const kinds=task==='cook'?['process','portion']:task==='plan'?['taste','portion','routine','goal']:['taste','portion'];
 return [
  ['Hard constraints',prefs.filter(p=>p.is_hard).map(p=>`${p.statement} (${p.confidence}; ${p.id})`),1700],
  ['Relevant preferences',prefs.filter(p=>!p.is_hard&&kinds.includes(p.kind)).map(p=>`${p.kind}: ${p.statement} (${p.confidence})`),700],
 ];
}
function intakeLine(i){return `${i.title||i.category} [intake ${i.id}; capture ${i.capture_id}] at ${i.eaten_at}: ${totalsText(i.nutrition)}; ${i.method}; ${i.components.map(c=>`${c.product_id||c.name} ${number(c.grams)} g`).join(', ')}`;}
function recipesMentioned(store,root,at){
 const since=new Date(Date.parse(at)-2*86400000).toISOString();
 const recent=store.all("SELECT m.text,m.panels FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE c.source NOT IN ('food_log','reflection') AND m.created>=? AND m.created<=?",since,at);
 const reports=files(householdPath(root,'data/cooking'),root).filter(r=>Date.parse(r.createdAt)>=Date.parse(since)&&Date.parse(r.createdAt)<=Date.parse(at));
 const batches=new FoodGraph(store,{root}).recentBatches(2,at).filter(b=>Date.parse(b.made_at)<=Date.parse(at));
 const cooked=new Set([...reports.map(r=>r.recipeId),...batches.map(b=>b.recipe_id)]);
 return fsRecipes(root).filter(r=>cooked.has(r.id)||recent.some(m=>`${m.text} ${m.panels}`.toLowerCase().includes(r.id)||m.text.toLowerCase().includes(r.title.toLowerCase()))).map(r=>`${r.title} [${r.id}]`);
}
const fsRecipes=root=>fs.existsSync(householdPath(root,'recipes'))?fs.readdirSync(householdPath(root,'recipes')).map(name=>read(householdPath(root,'recipes',name,'recipe.json'))).filter(r=>r?.id&&r?.title):[];

export function foodLogPacket(store,root,{note='',capturedAt=new Date().toISOString()}={}){
 const graph=new FoodGraph(store,{root}),matching=searchPantry(store,root,note),day=foodDay(capturedAt,store.household||loadHousehold(root));
 return packet([...preferences(graph,'food'),
  ['Recent batches (7 days)',recentBatches(store,root,{at:capturedAt}).map(batchLine),1100],
  ['Matching pantry and products',[
   ...matching.products.map(p=>`${compact(p.name,100)} [${p.id}]: ${nutrientText(p)}`),...matching.pantry.map(pantryLine)],1100],
  [`Intake on ${day} so far`,foodLog(store,day).filter(i=>['meal','snack','drink','supplement'].includes(i.category)&&Date.parse(i.eaten_at)<=Date.parse(capturedAt)).map(intakeLine),650],
  ['Recipes cooked or mentioned (2 days)',recipesMentioned(store,root,capturedAt),400],
 ]);
}

export function planningPacket(store,root){
 const graph=new FoodGraph(store,{root}),day=localDate(store.household||loadHousehold(root));
 const order={past:0,soon:1,unknown:2,fresh:3,stable:4,resolved:5};
 const days=Array.from({length:7},(_,i)=>new Date(Date.parse(`${day}T12:00:00Z`)-i*86400000).toISOString().slice(0,10));
 const goals=healthPreferences(store);
 const summaries=days.map(d=>{
  const entries=foodLog(store,d).filter(i=>['meal','snack','drink','supplement'].includes(i.category));
  const keys=[...new Set(['kcal','protein_g','fiber_g',...Object.keys(goals.targets).map(k=>k==='calories'?'kcal':k==='saturated_fat_g'?'sat_fat_g':k)])];
  return `${d}: ${entries.length} entries; ${keys.map(k=>{
   const known=entries.map(i=>i.nutrition?.[k]).filter(Boolean);
   const total=known.length?{low:known.reduce((s,n)=>s+n.low,0),high:known.reduce((s,n)=>s+n.high,0)}:null;
   const target=goals.targets[k==='kcal'?'calories':k==='sat_fat_g'?'saturated_fat_g':k];
   return `${k} ${span(total)}${known.length<entries.length?' + unknown':''}${target!=null?` / target ${target}`:''}`;
  }).join(', ')}`;
 });
 return packet([...preferences(graph,'plan'),
  ['Pantry by urgency',graph.pantryView(day).filter(p=>p.balance!==0).sort((a,b)=>order[a.urgency]-order[b.urgency]).map(pantryLine),1050],
  ['Batches and leftovers',recentBatches(store,root,{days:36500}).filter(b=>b.balance.amount!==0).map(batchLine),600],
  ['Health preferences and intake (7 days)',[`Priorities ${goals.priorities.join(' > ')}; daily targets ${JSON.stringify(goals.targets)}${Object.keys(goals.targets).length?'':' (numeric targets unset)'}; logged subtotals only, missing meals unknown`, ...summaries],1100],
  ['Open assumptions',graph.openAssumptions().map(a=>`${a.statement} [${a.id}]; evidence ${JSON.stringify(a.evidence)}`),500],
 ]);
}

export function cookPacket(store,root,recipeId){
 if(!/^[a-z0-9-]{1,100}$/.test(recipeId))throw new Error('Invalid recipe ID');
 const recipe=read(householdPath(root,'recipes',recipeId,'recipe.json'));if(!recipe)throw new Error('Unknown recipe');
 const graph=new FoodGraph(store,{root}),matching=searchPantry(store,root,recipe.ingredients?.map(i=>i.name).join(' '));
 const kitchen=read(householdPath(root,'profile/kitchen.json'));
 return packet([...preferences(graph,'cook'),
  ['Recipe', [`${recipe.title} [${recipeId}; revision ${recipe.revision}]`,...(recipe.ingredients||[]).map(i=>`${i.name}: ${i.amount} ${i.unit}; ${i.detail||''}`),...(recipe.steps||[]).map((s,i)=>`${i+1}. ${s.title}: ${s.text} (${s.equipment||''})`)],1600],
  ['Pantry for this recipe',matching.pantry.map(pantryLine),700],
  ['Recorded batches (no cook in progress inferred)',recentBatches(store,root).filter(b=>b.recipe_id===recipeId).map(batchLine),400],
  ['Appliances and kitchen notes',[JSON.stringify(kitchen||{}),...currentLearning(store).filter(n=>n.area==='kitchen'||n.area==='recipe'&&n.subject===recipeId).map(n=>`${n.subject}: ${n.note}`)],650],
 ]);
}
