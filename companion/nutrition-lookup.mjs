import {id,now} from './domain.mjs';
import {NUTRIENTS,convertQuantity} from './graph.mjs';

export const MATCH_THRESHOLD=85;
const words=s=>String(s||'').toLowerCase().normalize('NFKD').replace(/[’']/g,'').replace(/[^a-z0-9]+/g,' ').trim().split(/\s+/).filter(Boolean);
const numeric=v=>v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v))&&Number(v)>=0?Number(v):null;
const round=v=>v==null?null:Math.round(v*10000)/10000;
const gramSize=s=>{const m=String(s||'').match(/(\d+(?:\.\d+)?)\s*(kg|grams?|g|oz|lbs?)\b/i);return m?convertQuantity(Number(m[1]),m[2].toLowerCase().replace(/grams?/,'g'),'g'):null;};
const packageSize=text=>{const m=String(text||'').match(/(\d+(?:\.\d+)?)\s*(fl\s*oz|kg|grams?|g|oz|lbs?|ml|liters?|l|ct|count)\b/i);if(!m)return {};const unit=m[2].toLowerCase().replace(/grams?/,'g').replace(/liters?/,'l').replace(/\s+/g,' '),base=['ml','l','fl oz'].includes(unit)?'ml':['ct','count'].includes(unit)?'count':'g';return {size_amount:convertQuantity(Number(m[1]),unit,base),base_unit:base};};
const perServing=(values,grams)=>Object.fromEntries(NUTRIENTS.map(k=>[k,grams==null||values[k]==null?null:round(values[k]*grams/100)]));
const EMPTY=()=>Object.fromEntries(NUTRIENTS.map(k=>[k,null]));

export function normalizeUSDA(food){
 const values=EMPTY(),map={1008:'kcal',2047:'kcal',1003:'protein_g',1005:'carbs_g',1004:'fat_g',1079:'fiber_g',1093:'sodium_mg',1258:'sat_fat_g',1235:'added_sugar_g'};
 for(const n of food.foodNutrients||[]){const key=map[n.nutrientId??n.nutrient?.id];if(key){let v=numeric(n.value??n.amount);const unit=String(n.unitName??n.nutrient?.unitName).toLowerCase();if(key==='sodium_mg'&&unit==='g'&&v!=null)v*=1000;values[key]=v;}}
 const serving=['g','grm'].includes(String(food.servingSizeUnit||'').toLowerCase())?numeric(food.servingSize):gramSize(food.householdServingFullText);
 return {name:food.description,brand:food.brandName||food.brandOwner||'',barcode:food.gtinUpc||null,size_g:gramSize(food.packageWeight),...packageSize(food.packageWeight),nutrition:{nutrition_basis:'per_100g',nutrition_source:'usda_fdc',nutrition_url:`https://fdc.nal.usda.gov/food-details/${food.fdcId}/nutrients`,serving_g:serving,...values},per_serving:perServing(values,serving)};
}
export function normalizeOFF(food){
 const n=food.nutriments||{},map={kcal:'energy-kcal',protein_g:'proteins',carbs_g:'carbohydrates',fat_g:'fat',fiber_g:'fiber',sodium_mg:'sodium',sat_fat_g:'saturated-fat',added_sugar_g:'added-sugars'},values=EMPTY();
 for(const [key,off] of Object.entries(map)){let v=numeric(n[`${off}_100g`]);if(key==='kcal'&&v==null&&numeric(n.energy_100g)!=null&&n.energy_unit==='kJ')v=Number(n.energy_100g)/4.184;if(key==='sodium_mg'&&v!=null)v*=1000;values[key]=round(v);}
 // OFF's _100g fields can be per 100 ml for liquids. Never invent a density.
 const liquid=/\bml\b/i.test(food.serving_size||'')||food.nutrition_data_per==='100ml';
 const serving=liquid?null:gramSize(food.serving_size);
 const provided=Object.fromEntries(NUTRIENTS.map(k=>{let v=numeric(n[`${map[k]}_serving`]);if(k==='sodium_mg'&&v!=null)v*=1000;return [k,v==null?perServing(values,serving)[k]:round(v)];}));
 return {name:food.product_name,brand:food.brands||'',barcode:food.code||null,size_g:gramSize(food.quantity),...packageSize(food.quantity),nutrition:{nutrition_basis:liquid?'per_100ml':'per_100g',nutrition_source:'open_food_facts',nutrition_url:`https://world.openfoodfacts.org/product/${food.code}`,serving_g:serving,...values},per_serving:provided};
}
export function scoreCandidate(product,candidate){
 if(/unknown|unconfirmed/i.test(product.name))return 0;
 if(product.barcode&&candidate.barcode)return String(product.barcode).replace(/^0+/,'')===String(candidate.barcode).replace(/^0+/,'')?100:0;
 const brandWords=words(product.brand),cb=words(candidate.brand),pw=words(product.name),cw=words(candidate.name);
 const brand=brandWords.length?brandWords.every(w=>cb.includes(w)||cw.includes(w)):cb.length&&cb.every(w=>pw.includes(w));
 const ignored=new Set([...brandWords,...(brand&&!brandWords.length?cb:[]),'and','with','the','food','foods']);
 const wanted=pw.filter(w=>!ignored.has(w)),actual=cw.filter(w=>!ignored.has(w));
 const overlap=wanted.filter(w=>actual.includes(w)).length,coverage=wanted.length?overlap/wanted.length:0,precision=actual.length?overlap/actual.length:0;
 if(precision<0.8)return Math.min(60,Math.round(55*coverage));
 const variants=['unsweetened','sweetened','nonfat','low','reduced','sodium','quick','instant','original','vanilla','raspberry','cinnamon','plain','whole','organic','greek'];
 if(variants.some(w=>wanted.includes(w)!==actual.includes(w)))return Math.min(60,Math.round(55*coverage));
 const candidateSize=candidate.base_unit===product.base_unit?candidate.size_amount:product.base_unit==='g'?candidate.size_g:null;
 const size=product.size_amount&&candidateSize?Math.abs(product.size_amount-candidateSize)/product.size_amount<=0.08:null;
 return Math.round((brand?30:0)+55*coverage+(size===true?15:size===false?0:10));
}
const hasValues=n=>NUTRIENTS.some(k=>n?.[k]!=null);
export function chooseCandidates(product,candidates){
 const scored=candidates.filter(c=>c.name&&hasValues(c.nutrition)).map(c=>({...c,score:scoreCandidate(product,c)})).sort((a,b)=>b.score-a.score);
 const best=scored[0],other=scored.find(c=>c!==best&&(!c.barcode||c.barcode!==best.barcode)&&JSON.stringify(NUTRIENTS.map(k=>c.nutrition[k]))!==JSON.stringify(NUTRIENTS.map(k=>best.nutrition[k])));
 return best&&best.score>=MATCH_THRESHOLD&&(!other||best.score-other.score>=5)?{status:'matched',candidate:best,candidates:scored.slice(0,5)}:{status:scored.length?'ambiguous':'missing',candidates:scored.slice(0,5)};
}

export async function searchUSDA(product,{fetchImpl=fetch,apiKey=process.env.FDC_API_KEY||'DEMO_KEY'}={}){
 const url=new URL('https://api.nal.usda.gov/fdc/v1/foods/search');url.search=new URLSearchParams({api_key:apiKey,query:[product.brand,product.name].filter(Boolean).join(' '),dataType:'Branded',pageSize:'25'});
 const r=await fetchImpl(url,{signal:AbortSignal.timeout(15000)});if(!r.ok)throw new Error(`USDA HTTP ${r.status}`);return ((await r.json()).foods||[]).map(normalizeUSDA);
}
export async function searchOFF(product,{fetchImpl=fetch}={}){
 const url=new URL('https://world.openfoodfacts.org/cgi/search.pl');url.search=new URLSearchParams({search_terms:[product.brand,product.name].filter(Boolean).join(' '),search_simple:'1',action:'process',json:'1',page_size:'25',fields:'product_name,brands,code,nutriments,serving_size,quantity,nutrition_data_per'});
 const r=await fetchImpl(url,{headers:{'User-Agent':'MealGarden/0.1 (personal meal planner; local companion)'},signal:AbortSignal.timeout(15000)});if(!r.ok)throw new Error(`Open Food Facts HTTP ${r.status}`);return ((await r.json()).products||[]).map(normalizeOFF);
}

export function labelPhotoNutrition(store,product){
 const rows=store.all("SELECT * FROM events WHERE type='interpreted' ORDER BY recorded_at DESC,rowid DESC");const seen=new Set();
 for(const row of rows){if(seen.has(row.subject))continue;seen.add(row.subject);const p=JSON.parse(row.payload);
  const capture=store.events(row.subject).find(e=>e.type==='captured');const photos=capture?.payload?.mediaList||capture?.payload?.media||[];
  if(p.category!=='nutrition_label'&&(!Array.isArray(photos)||photos.length<2))continue;
  const named=(p.items||[]).filter(i=>words(product.name).every(w=>words(i.name).includes(w)));
  if(!named.length)continue;
  const explicit=(p.productNutrition||[]).find(n=>n.productId===product.id||words(product.name).every(w=>words(n.name).includes(w)));
  if(explicit&&hasValues(explicit.nutrition)){
   let nutrition={...explicit.nutrition,nutrition_source:'label_photo'},per=explicit.perServing||null;
   if(nutrition.nutrition_basis==='per_unit'&&nutrition.serving_g>0){per=Object.fromEntries(NUTRIENTS.map(k=>[k,nutrition[k]??null]));nutrition={...nutrition,nutrition_basis:'per_100g',...Object.fromEntries(NUTRIENTS.map(k=>[k,per[k]==null?null:round(per[k]*100/nutrition.serving_g)]))};}
   return {nutrition,per_serving:per,evidence:[row.id,capture?.id].filter(Boolean)};
  }
  // Meal totals and multiple labels cannot be assigned to a single product.
  if(p.category!=='nutrition_label'||p.items.length!==1||!p.nutrition)continue;
  const serving=gramSize(named[0].portion);if(!serving)continue;
  const per=EMPTY();for(const k of NUTRIENTS){const v=p.nutrition[k==='kcal'?'calories':k==='sat_fat_g'?'saturated_fat_g':k];if(v&&v.low===v.high)per[k]=v.low;}
  if(hasValues(per))return {nutrition:{nutrition_basis:'per_100g',nutrition_source:'label_photo',serving_g:serving,...Object.fromEntries(NUTRIENTS.map(k=>[k,per[k]==null?null:round(per[k]*100/serving)]))},per_serving:per,evidence:[row.id,capture?.id].filter(Boolean)};
 }
 return null;
}
const perServingValues=perServing;
export function recordLookup(graph,productId,{step,nutrition=null,perServing=null,barcode=null,tokens=0,seconds=0,succeeded=hasValues(nutrition),detail={},lookupId=id()}){
 if(perServing==null&&nutrition?.nutrition_basis==='per_100g')perServing=perServingValues(nutrition,nutrition.serving_g);
 const store=graph.store;store.db.exec('BEGIN IMMEDIATE');try{
  if(store.get('SELECT id FROM product_lookups WHERE id=?',lookupId)){store.db.exec('ROLLBACK');return graph.getProduct(productId);}
  const product=graph.getProduct(productId);
  if(nutrition)graph.setProductNutrition(productId,{...EMPTY(),...nutrition,...(barcode?{barcode}:{})},{idempotencyKey:`nutrition:${lookupId}`,actor:'system:product_lookup',evidence:detail.evidence||[]});
  const evidence={...detail,perServing,nutrition,barcode,step,tokens,seconds};
  store.run('INSERT INTO product_lookups(id,product_id,step,succeeded,tokens,seconds,detail,created_at) VALUES(?,?,?,?,?,?,?,?)',lookupId,productId,step,succeeded?1:0,tokens,seconds,JSON.stringify(evidence),now());
  store.record('product_lookup',`product:${productId}`,{productId,name:product.name,lookupId,step,succeeded,tokens,seconds,...evidence},{actor:'system:product_lookup',evidence:detail.evidence||[]});
  store.db.exec('COMMIT');return graph.getProduct(productId);
 }catch(e){store.db.exec('ROLLBACK');throw e;}
}
export async function lookupProduct(graph,productId,options={}){
 const start=Date.now(),product=graph.getProduct(productId),seconds=()=> (Date.now()-start)/1000;
 const label=labelPhotoNutrition(graph.store,product);
 if(label&&product.nutrition_source==='label_photo'&&JSON.stringify(product.nutrition_evidence?.detail?.evidence)===JSON.stringify(label.evidence))return {status:'cached',product};
 if(label)return {status:'resolved',product:recordLookup(graph,productId,{step:'label_photo',nutrition:label.nutrition,perServing:label.per_serving,seconds:seconds(),detail:{evidence:label.evidence}})};
 if(product.kind==='generic'&&product.nutrition_source==='estimate')return {status:'cached',product};
 if(product.kind==='generic')return {status:'skipped',product:recordLookup(graph,productId,{step:'estimate',nutrition:{nutrition_source:'estimate',nutrition_basis:'per_100g'},seconds:seconds(),detail:{reason:'Generic product; estimated at logging time'}})};
 if(product.kind==='homemade'||hasValues(product)||graph.store.get("SELECT 1 FROM product_lookups WHERE product_id=? AND step='none' LIMIT 1",product.id))return {status:'cached',product};
 const candidates=[],errors=[];
 for(const [step,search] of [['usda_fdc',options.searchUSDA||searchUSDA],['open_food_facts',options.searchOFF||searchOFF]]){
  try{const found=await search(product,options);candidates.push(...found);const match=chooseCandidates(product,found);if(match.status==='matched'){const c=match.candidate;return {status:'resolved',product:recordLookup(graph,productId,{step,nutrition:c.nutrition,perServing:c.per_serving,barcode:c.barcode,seconds:seconds(),detail:{score:c.score,name:c.name,brand:c.brand,errors}})};}}
  catch(e){errors.push({step,error:e.message});}
 }
 return {...chooseCandidates(product,candidates),product,errors,elapsedSeconds:seconds()};
}
export function productsAtHome(graph){
 // A known zero count is gone; unresolved lots remain eligible. Unknown amount is not an empty kitchen.
 const ids=new Set(graph.pantryView().filter(i=>i.balance!==0&&i.location!=='freezer'&&i.kind!=='homemade').map(i=>i.product_id));
 return [...ids].map(id=>graph.getProduct(id)).filter(p=>!hasValues(p)&&p.nutrition_source!=='estimate'&&(!p.nutrition_evidence||['deferred','correction'].includes(p.nutrition_evidence.step)));
}
export function productLookupPrompt(product,candidates,errors=[]){return [
 'Resolve nutrition for this ONE packaged product. Use the supplied database candidates first. Choose only the same brand, food and variety; package size alone may differ. If identity is uncertain, use web search for the exact manufacturer or store label. Never invent or borrow values. Unknown nutrients stay null; total sugar is not added sugar; do not assume liquid density.',
 `Product: ${JSON.stringify(Object.fromEntries(['id','name','brand','kind','base_unit','size_amount','barcode'].map(k=>[k,product[k]??null])))}`,`Candidates: ${JSON.stringify(candidates)}`,`Database errors: ${JSON.stringify(errors)}`,
 'Save exactly once with meal_garden_set_product_nutrition for this product id, with nutrition_source, basis, URL and evidence. Candidate values are per their explicit basis. If no reliable exact match exists, save nutrition_source none with all nutrient values null. Do not ask the person questions or read unrelated files. Finish immediately after saving.'
 ].join('\n\n');}
