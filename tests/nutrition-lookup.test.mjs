import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {EventEmitter} from 'node:events';
import {Store} from '../companion/store.mjs';
import {FoodGraph,NUTRIENTS} from '../companion/graph.mjs';
import {Jobs} from '../companion/jobs.mjs';
import {recordCapture,recordInterpretation} from '../companion/capture.mjs';
import {normalizeUSDA,normalizeOFF,scoreCandidate,chooseCandidates,lookupProduct,productsAtHome,recordLookup,searchUSDA,searchOFF,productLookupPrompt} from '../companion/nutrition-lookup.mjs';
const load=name=>JSON.parse(fs.readFileSync(new URL(`./fixtures/nutrition/${name}.json`,import.meta.url)));
const usda=load('usda'),off=load('off');
function fixture(t){const root=fs.mkdtempSync(path.join(os.tmpdir(),'nutrition-test-')),store=new Store(path.join(root,'.runtime/garden.sqlite'));const graph=new FoodGraph(store,{root});t.after(()=>{store.close();fs.rmSync(root,{recursive:true,force:true});});return {root,store,graph};}
function product(graph,input={}){return graph.upsertProduct({name:'Quaker quick oats',brand:'Quaker',kind:'packaged',base_unit:'g',...input},{idempotencyKey:crypto.randomUUID()});}
const candidate=()=>({name:'Quick oats',brand:'Quaker',barcode:'1234',nutrition:{nutrition_source:'usda_fdc',nutrition_basis:'per_100g',nutrition_url:'https://fdc.nal.usda.gov/food-details/1/nutrients',serving_g:40,kcal:375,protein_g:12.5,carbs_g:67.5,fat_g:7.5,fiber_g:10,sodium_mg:0,sat_fat_g:1,added_sugar_g:0}});
class Provider extends EventEmitter{constructor(){super();this.responses=[];}async thread(...args){this.threadArgs=args;return 'lookup-thread';}async start(...args){this.startArgs=args;return {turn:{id:'lookup-turn'}};}respond(id,result){this.responses.push(result);}reject(id,message){this.rejected=message;}async cancel(){}}
const waitFor=async fn=>{for(let i=0;i<200&&!fn();i++)await new Promise(r=>setTimeout(r,5));assert.ok(fn());};

test('recorded USDA response normalizes per-100g, servings, barcode and source without making missing values zero',()=>{
 const food=usda.foods.find(f=>f.brandName==="NANCY'S"),c=normalizeUSDA(food);
 assert.equal(c.nutrition.protein_g,food.foodNutrients.find(n=>n.nutrientId===1003).value);
 assert.equal(c.nutrition.nutrition_basis,'per_100g');assert.equal(c.nutrition.serving_g,food.servingSize);assert.equal(c.barcode,food.gtinUpc);
 assert.ok(c.nutrition.nutrition_url.includes(String(food.fdcId)));assert.equal(c.per_serving.protein_g,Math.round(c.nutrition.protein_g*food.servingSize/100*1e4)/1e4);
 assert.equal(normalizeUSDA({fdcId:1,description:'Empty'}).nutrition.fiber_g,null);
});
test('recorded OFF label normalizes sodium to mg, added sugar independently, and respects liquid basis',()=>{
 const c=normalizeOFF(off.product),n=off.product.nutriments;
 assert.equal(c.nutrition.kcal,n['energy-kcal_100g']);assert.equal(c.nutrition.sodium_mg,Math.round(n.sodium_100g*1000*1e4)/1e4);assert.equal(c.barcode,off.code);assert.equal(c.nutrition.serving_g,40);
 const liquid=normalizeOFF({code:'1',serving_size:'240 ml',nutriments:{'energy-kcal_100g':20,sugars_100g:5,sodium_100g:0.1}});
 assert.equal(liquid.nutrition.nutrition_basis,'per_100ml');assert.equal(liquid.nutrition.serving_g,null);assert.equal(liquid.nutrition.added_sugar_g,null);
});
test('scoring requires brand/name/variety, rewards package size, and returns five ambiguous candidates',()=>{
 const p={name:'Quaker quick oats',brand:'Quaker',base_unit:'g',size_amount:500},c=candidate();assert.ok(scoreCandidate(p,c)>=85);
 assert.ok(scoreCandidate(p,{...c,size_g:500})>scoreCandidate(p,{...c,size_g:1000}));
 assert.ok(scoreCandidate(p,{...c,brand:'Other'})<85);assert.ok(scoreCandidate(p,{...c,name:'Old fashioned oats'})<85);
 assert.equal(scoreCandidate({...p,barcode:'0099'},{...c,barcode:'99'}),100);
 assert.equal(chooseCandidates(p,[c]).status,'matched');
 const many=Array.from({length:8},(_,i)=>({...c,barcode:String(i),nutrition:{...c.nutrition,kcal:370+i}}));const r=chooseCandidates(p,many);assert.equal(r.status,'ambiguous');assert.equal(r.candidates.length,5);
 assert.equal(scoreCandidate({...p,name:'Sauce (exact variety unknown)'},c),0);
});
test('HTTP adapters use the supplied fixtures, required queries/key and custom OFF user-agent',async()=>{
 const calls=[],fetchImpl=async(url,opts)=>{calls.push({url,opts});return {ok:true,json:async()=>calls.length===1?usda:{products:[off.product]}};};
 const p={name:'Quick oats',brand:'Quaker'};await searchUSDA(p,{fetchImpl,apiKey:'fixture-key'});await searchOFF(p,{fetchImpl});
 assert.equal(calls[0].url.searchParams.get('api_key'),'fixture-key');assert.equal(calls[0].url.searchParams.get('dataType'),'Branded');assert.equal(calls[1].url.searchParams.get('search_terms'),'Quaker Quick oats');assert.ok(calls[1].opts.headers['User-Agent'].startsWith('MealGarden/'));
});
test('generic skip is based only on kind, saves estimate without values, and spends zero tokens',async t=>{
 const {graph,store}=fixture(t),p=product(graph,{kind:'generic'});let calls=0;
 const r=await lookupProduct(graph,p.id,{searchUSDA:()=>{calls++;}});assert.equal(r.status,'skipped');assert.equal(calls,0);assert.equal(r.product.nutrition_source,'estimate');assert.ok(NUTRIENTS.every(k=>r.product[k]===null));
 const record=store.get('SELECT * FROM product_lookups');assert.equal(record.tokens,0);assert.equal(record.step,'estimate');assert.ok(record.seconds>=0);assert.equal(store.get("SELECT count(*) n FROM events WHERE type='product_lookup'").n,1);
});
function label(store,graph,runtime,p,{multi=false,explicit=false,category='nutrition_label'}={}){
 const id=crypto.randomUUID(),jpeg=Buffer.from([0xff,0xd8,0xff,0xd9]).toString('base64');recordCapture(store,runtime,{id,kind:'food',phoneTime:'2026-10-04T20:00:00Z',note:'label',photos:multi?[{imageData:jpeg},{imageData:jpeg}]:[]});
 const interpretation={captureId:id,category,title:p.name,items:[{name:p.name,portion:'40 g',confidence:'high'}],nutrition:{calories:{low:150,high:150},protein_g:{low:5,high:5},carbs_g:{low:27,high:27},fat_g:{low:3,high:3},fiber_g:{low:4,high:4}},overallConfidence:'high',notes:'Label',questions:[]};
 if(explicit)interpretation.productNutrition=[{productId:p.id,name:p.name,nutrition:{nutrition_basis:'per_100g',serving_g:40,kcal:375,protein_g:12.5}}];
 return recordInterpretation(store,id,interpretation,'label-test');
}
test('single label transcription wins over databases and never deducts pantry',async t=>{
 const {graph,store,root}=fixture(t),p=product(graph);label(store,graph,path.join(root,'.runtime'),p);const before=store.get('SELECT count(*) n FROM pantry_movements').n;
 const r=await lookupProduct(graph,p.id,{searchUSDA:()=>{throw Error('must not search');}});assert.equal(r.product.nutrition_source,'label_photo');assert.equal(r.product.kcal,375);assert.equal(r.product.serving_g,40);assert.equal(r.product.nutrition_evidence.tokens,0);assert.equal(r.product.nutrition_evidence.detail.perServing.kcal,150);assert.equal(store.get('SELECT count(*) n FROM pantry_movements').n,before);
});
test('multi-photo explicit label wins; multi-photo meal totals alone cannot become product facts',async t=>{
 const {graph,store,root}=fixture(t),p=product(graph);label(store,graph,path.join(root,'.runtime'),p,{multi:true,explicit:true,category:'meal'});
 const r=await lookupProduct(graph,p.id,{searchUSDA:()=>{throw Error('must not search');}});assert.equal(r.product.nutrition_source,'label_photo');assert.equal(r.product.protein_g,12.5);
 const p2=product(graph,{name:'Other oats',brand:'Other'});label(store,graph,path.join(root,'.runtime'),p2,{multi:true,category:'meal'});let calls=0;
 const result=await lookupProduct(graph,p2.id,{searchUSDA:async()=>{calls++;return [];},searchOFF:async()=>[]});assert.equal(calls,1);assert.equal(result.status,'missing');assert.equal(graph.getProduct(p2.id).kcal,null);
});
test('database success writes nutrition, serving evidence and one cost event; rereading uses the saved product',async t=>{
 const {graph,store}=fixture(t),p=product(graph),c=candidate();let calls=0;
 const r=await lookupProduct(graph,p.id,{searchUSDA:async()=>{calls++;return [c];},searchOFF:()=>{throw Error('not needed');}});assert.equal(r.product.kcal,375);assert.equal(r.product.barcode,'1234');assert.equal(r.product.nutrition_evidence.detail.perServing.kcal,150);
 await lookupProduct(graph,p.id,{searchUSDA:()=>{calls++;}});assert.equal(calls,1);assert.equal(store.get('SELECT count(*) n FROM product_lookups').n,1);
 recordLookup(graph,p.id,{lookupId:'once',step:'none',tokens:0});recordLookup(graph,p.id,{lookupId:'once',step:'none',tokens:0});assert.equal(store.get('SELECT count(*) n FROM product_lookups WHERE id=?','once').n,1);
});
test('home trigger includes uncertain quantities and use-soon, skips gone, frozen, homemade and completed lookups',t=>{
 const {graph}=fixture(t);for(const [name,location,amount]of [['At home','pantry',null],['Gone','pantry',0],['Frozen','freezer',10]]){const r=graph.importReceipt({fingerprint:name,items:[{name,product:{kind:'packaged',storage:location}}]});graph.countPantryItem(r.pantryItems[0].id,amount,'known','test',{idempotencyKey:name});}
 assert.deepEqual(productsAtHome(graph).map(p=>p.name),['At home']);
});
test('receipt hook fires after commit once, including when importer is retried',async t=>{
 const {store}=fixture(t);const calls=[],graph=new FoodGraph(store,{onProductsCreated:ids=>calls.push(ids)}),r={fingerprint:'new',items:[{name:'New packaged food',kind:'packaged'}]};const result=graph.importReceipt(r);graph.importReceipt(r);await new Promise(r=>setImmediate(r));assert.deepEqual(calls,[result.products.map(p=>p.id)]);
});
test('background cap is ten; foreground jobs take priority; internal lookup tools are scoped and cost uses last cumulative report',async t=>{
 const {root,store,graph}=fixture(t),provider=new Provider(),jobs=new Jobs(store,provider,root);t.after(()=>jobs.close());jobs.active={id:'block-pump'};
 const ids=Array.from({length:12},(_,i)=>product(graph,{name:`Food ${i}`,brand:'Brand'}).id);
 const run=await jobs.lookupProducts(ids,{searchUSDA:async()=>[],searchOFF:async()=>[]});assert.equal(run.modelJobs,10);assert.equal(run.results.filter(r=>r.status==='deferred').length,2);
 const chat=jobs.enqueue({kind:'chat',requestKey:'foreground',text:'Hello'});jobs.active=null;await jobs.pump();assert.equal(jobs.active.id,chat.id);jobs.finish('completed');await waitFor(()=>jobs.active?.kind==='product_lookup'&&jobs.active.turnId);
 assert.equal(provider.threadArgs[5],'read-only');assert.equal(provider.threadArgs[1].length,2);const job=jobs.active;
 await jobs.request({id:1,method:'item/tool/call',params:{tool:'meal_garden_set_product_nutrition',arguments:{id:'wrong-product',nutrition:{nutrition_source:'none',nutrition_basis:'per_100g'},idempotencyKey:'wrong'}}});assert.equal(provider.responses.at(-1).success,false);
 await jobs.request({id:2,method:'item/tool/call',params:{tool:'meal_garden_set_product_nutrition',arguments:{id:job.input.productId,nutrition:{nutrition_source:'none',nutrition_basis:'per_100g'},idempotencyKey:'no-result'}}});assert.equal(provider.responses.at(-1).success,true);
 for(const count of [100,150])jobs.event({method:'thread/tokenUsage/updated',params:{threadId:job.threadId,tokenUsage:{total:{totalTokens:count,inputTokens:count-10,outputTokens:10}}}});
 jobs.finish('completed');jobs.close();const cost=store.get('SELECT * FROM product_lookups WHERE id=?',`job:${job.id}`);assert.equal(cost.tokens,150);assert.equal(cost.step,'none');assert.equal(cost.succeeded,0);assert.ok(cost.seconds>=0);assert.equal(store.conversations().some(c=>c.source==='product_lookup'),false);
});

test('model choice cannot change supplied values and preserves candidate barcode and serving metadata',async t=>{
 const {root,store,graph}=fixture(t),provider=new Provider(),jobs=new Jobs(store,provider,root);t.after(()=>jobs.close());const p=product(graph),c=candidate();
 const j=jobs.enqueue({kind:'product_lookup',requestKey:'choice',productId:p.id,candidates:[c],text:productLookupPrompt(p,[c])},{internal:true});await waitFor(()=>jobs.active?.turnId);
 const call=async(n,id)=>jobs.request({id,method:'item/tool/call',params:{tool:'meal_garden_set_product_nutrition',arguments:{id:p.id,nutrition:n,evidence:[c.nutrition.nutrition_url],idempotencyKey:`choice-${id}`}}});
 await call({...c.nutrition,kcal:380},1);assert.equal(provider.responses.at(-1).success,false);assert.equal(graph.getProduct(p.id).kcal,null);
 await call(c.nutrition,2);assert.equal(provider.responses.at(-1).success,true);assert.equal(graph.getProduct(p.id).barcode,c.barcode);
 jobs.event({method:'thread/tokenUsage/updated',params:{threadId:'lookup-thread',tokenUsage:{total:{totalTokens:300}}}});jobs.finish('completed');jobs.close();
 const saved=graph.getProduct(p.id);assert.equal(saved.nutrition_evidence.step,'model_choice');assert.equal(saved.nutrition_evidence.detail.perServing.kcal,150);assert.equal(saved.nutrition_evidence.tokens,300);
});

test('lookup entry point is laptop-only and shares the companion queue',async t=>{
 const {createCompanion}=await import('../companion/server.mjs');const root=fs.mkdtempSync(path.join(os.tmpdir(),'nutrition-http-')),provider=new Provider(),app=createCompanion({root,provider});
 await new Promise(resolve=>app.server.listen(0,'127.0.0.1',resolve));t.after(async()=>{app.jobs.close();await new Promise(resolve=>app.server.close(resolve));app.store.close();fs.rmSync(root,{recursive:true,force:true});});
 app.jobs.lookupProducts=async ids=>({results:[],modelJobs:0,ids});const url=`http://127.0.0.1:${app.server.address().port}/internal/product-lookups`;
 const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({productIds:['x']})});assert.equal(r.status,200);assert.deepEqual((await r.json()).ids,['x']);
 const foreign=await fetch(url,{method:'POST',headers:{Origin:'https://unrelated.invalid'},body:'{}'});assert.equal(foreign.status,403);
});

test('shared words cannot turn bars, Greek yogurt, mixed greens or Crunch cereal into the requested product',()=>{
 const pairs=[
  ['General Mills Cinnamon Toast Crunch Cereal','General Mills',"GENERAL MILLS, CINNAMON TOAST CRUNCH, MILK N CEREAL BARS, CINNAMON TOAST CRUNCH, CINNAMON TOAST CRUNCH"],
  ["Nancy's Plain Probiotic Nonfat Yogurt","Nancy's","NANCY'S, PROBIOTIC PLAIN GREEK NONFAT YOGURT"],
  ['Simple Truth Organic Baby Spinach','Simple Truth Organic','Simple Truth Organic power greens, baby spinach mizuna chard kale'],
  ["Kellogg's Raisin Bran Original","Kellogg's",'Original Raisin Bran Crunch'],
  ["Bob's Red Mill Flaxseed Meal","Bob's Red Mill","Bob's Red Mill Golden Flaxseed Meal"]
 ];
 for(const [name,brand,target] of pairs)assert.ok(scoreCandidate({name,brand},{name:target,brand})<85,`${name} -> ${target}`);
});

test('deferred products reuse database evidence on the next run and corrected products become eligible again',async t=>{
 const {root,graph,store}=fixture(t),jobs=new Jobs(store,new Provider(),root);jobs.active={id:'hold'};t.after(()=>jobs.close());
 const p=product(graph);graph.addPantryItem({productId:p.id},{idempotencyKey:'lot'});recordLookup(graph,p.id,{step:'deferred',seconds:1.2,detail:{candidates:[candidate()],errors:[]}});
 let calls=0;const run=await jobs.lookupProducts(null,{searchUSDA:()=>{calls++;throw Error('no repeat request');}});assert.equal(run.modelJobs,1);assert.equal(calls,0);
 recordLookup(graph,p.id,{step:'correction',nutrition:{nutrition_source:'none',nutrition_basis:'per_100g'}});assert.equal(productsAtHome(graph)[0].id,p.id);
});

test('explicit serving-based label facts normalize to per-100g and retain the transcribed serving',async t=>{
 const {graph,store}=fixture(t),p=product(graph);store.record('captured','capture:serving-label',{mediaList:[{},{}]});store.record('interpreted','capture:serving-label',{category:'meal',items:[{name:p.name}],productNutrition:[{productId:p.id,name:p.name,nutrition:{nutrition_basis:'per_unit',serving_g:40,kcal:150,protein_g:5}}]});
 const r=await lookupProduct(graph,p.id,{searchUSDA:()=>{throw Error('no database lookup');}});assert.equal(r.product.kcal,375);assert.equal(r.product.protein_g,12.5);assert.equal(r.product.nutrition_basis,'per_100g');assert.equal(r.product.nutrition_evidence.detail.perServing.kcal,150);
});
