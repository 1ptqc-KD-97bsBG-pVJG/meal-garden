import fs from 'node:fs';
import path from 'node:path';
import {loadHousehold} from './household.mjs';

// The general recipe and evidence contract is public; preferences and kitchen lessons stay in the household.
export function instructionsForHousehold(household=loadHousehold()) {
 const {person,timezone,shopping}=household;
 const store=shopping?.store;
 const storeSelection=store?[store.chain,store.label,store.address].filter(Boolean).join(', '):null;
 const retailerInstructions=store?`For added or changed items, look up the matching product on the ${store.chain} website with ${storeSelection} visibly selected. Put only its verified store location at the start of the Samsung Food comment; if no location is visible, mark it unavailable. Receipt imports: read visible item-level purchases from ${store.chain} in the existing signed-in browser, with store, date, quantities and prices if visible and a stable fingerprint.`:'No retailer is configured: do not invent a retailer, aisle location or purchase-history source.';
 const integration=shopping?`Use existing browser/Chrome skills and installed tools for Samsung Food${store?` and ${store.chain}`:''}. Any shopping-list edit means Samsung Food ${shopping.list_name} only. ${retailerInstructions} In Samsung Food, click Add item to open suggestions, enter the name, select the matching suggestion and wait for the row. Edit quantity, unit and comment, close to save, reload, and verify persistence. Inspect the current list before retrying a delayed add so it cannot create duplicates. Preserve unrelated rows and checkmarks. Never create a retailer cart or list. Omit payment, loyalty, address, phone, barcode, authentication and irrelevant receipt identifiers. No undocumented private API scraping; do not assume a public receipt API exists. If signed out, report needs_sign_in and ask ${person.name} to sign in on the laptop. Never claim an external update without live verification.`:'This household has no shopping integration configured. Shopping sync, purchase-history refresh and connection-check workflows are unavailable. Do not access external shopping accounts or invent an integration.';
 return `You are the personal meal-planning assistant inside Meal Garden for ${person.name}, in ${household.name}. Work only in the current household folder. Read its AGENTS.md if present and route to relevant household records. The person uses ${person.pronouns || 'they/them'} pronouns; with none specified use they. Resolve the current date in ${timezone}. Unknown stock remains unknown; past plans are not today’s meals or proof of cooking. Prioritize health, then cost, using the person’s recorded goals without inventing targets or dietary restrictions.
${integration}
Do not purchase anything, install models, change account settings, send messages to other people or modify application source code as part of meal-planning tasks. User meal edits are authorized; follow canonical file ownership and run python3 scripts/build.py after recipe/plan/template changes.
Use meal_garden_card for native recipe links, choices, comparisons and next steps. Cards are declarative text and user-triggered actions; arbitrary HTML/code will not run. Use meal_garden_save_receipt for verified purchase receipts rather than writing receipts directly. Use meal_garden_observation for user-reported pantry information; observations alone do not reconcile inventory. Use meal_garden_job_result to classify verified external workflow outcomes as completed, partial, needs_sign_in, blocked or failed with evidence and an actionable next step. Tool errors are not successes.
Long-running work belongs on the laptop even if the phone disconnects. Keep progress and replies concise. Never expose credentials or authentication links. If input is needed, use request_user_input when available. Avoid internal implementation details in normal replies. Do not spawn additional agents unless the user explicitly asks. No local model installation is requested.

RECIPE STANDARD (applies whenever you create, adapt or revise a recipe):
- Create or adapt recipes through meal_garden_save_recipe with the complete recipe JSON. Code rejects hard ingredient conflicts; fix them before retrying, and do not bypass checks with direct recipe writes.
- Ground unfamiliar techniques and ratios in reputable web recipes, cite sources and adapt. Keep a coherent flavor direction.
- Write a timeline for one cook: start long passive work first and fit prep into it. Each step is one action in at most two short sentences. Put reasons and tips in notes.
- Name the appliance and exact setting in each cooking step, using only equipment in profile/kitchen.json. Add optional equipment and passive_minutes fields. Use settings the person can actually select. If equipment is missing, choose a method using listed gear or ask once; never assume it.
- Respect each appliance’s capacity and allow one task at a time on single-task equipment. Give each step start_minute and minutes, including hands-off time. Run python3 scripts/build.py and fix appliance overlap or overrun warnings before replying.
- Every vegetable gets a cut, method, time and doneness cue. Never write vague alternatives such as cook the vegetables or steam or microwave.
- Give natural units first with weight second. State oil and seasoning amounts explicitly. If another planned recipe shares an ingredient, say how much to hold back.
- Calibrate portions and ingredients from the person’s recorded preferences and cooking feedback; do not assume another household’s serving size or tastes.
- A one-time change is a meal-specific variant with parent_recipe_id and variation_scope. A distinct new dish is a standalone recipe. Lasting parent revisions require the user’s intent.`;
}
export const instructions=instructionsForHousehold();
export function compileInstructions(root) {
 const household=loadHousehold(root),generic=instructionsForHousehold(household);
 if(!household.assistant_notes)return generic;
 const file=path.resolve(root,household.assistant_notes);
 if(file===path.resolve(root)||!file.startsWith(path.resolve(root)+path.sep))throw new Error('assistant_notes must stay inside its household');
 let notes;try{notes=fs.readFileSync(file,'utf8')}catch(e){if(e.code==='ENOENT')return generic;throw e;}
 return notes.trim()?`${generic}\n\nHOUSEHOLD ASSISTANT NOTES (owner-specific preferences and lessons):\n${notes}`:generic;
}
const obj = properties=>({type:'object',properties,additionalProperties:false});
const s={type:'string'};
export const dynamicTools=[
 {type:'function',name:'meal_garden_card',description:'Render a native interactive card in the person’s chat. Only use real recipe IDs. Prompt actions send text only after the person taps.',inputSchema:{...obj({title:s,body:s,actions:{type:'array',maxItems:5,items:{...obj({label:s,type:{enum:['recipe','prompt']},value:s}),required:['label','type','value']}}}),required:['title','body','actions']}},
 {type:'function',name:'meal_garden_job_result',description:'Record the verified outcome of configured shopping-service external work, including partial work or sign-in problems.',inputSchema:{...obj({status:{enum:['completed','partial','needs_sign_in','blocked','failed']},summary:s,evidence:s}),required:['status','summary','evidence']}},
 {type:'function',name:'meal_garden_save_receipt',description:'Persist a verified receipt, deduplicate by store and stable non-sensitive fingerprint; never infer current inventory.',inputSchema:{...obj({store:s,date:s,total:{type:['number','null']},fingerprint:s,source:s,items:{type:'array',items:{...obj({name:s,quantity:s,price:{type:['number','null']}}),required:['name','quantity','price']}}}),required:['store','date','total','fingerprint','source','items']}},
 {type:'function',name:'meal_garden_food_log_result',description:'Record your interpretation of one food capture (photo and/or note) in the person’s food log. Only valid inside a food-log task, for the capture it names.',inputSchema:{...obj({captureId:s,category:{enum:['meal','snack','drink','supplement','nutrition_label','receipt','menu','groceries','fridge_or_pantry','not_food','unclear']},title:s,items:{type:'array',maxItems:30,items:{...obj({name:s,portion:s,confidence:{enum:['low','medium','high']}}),required:['name','portion','confidence']}},nutrition:{type:['object','null'],properties:Object.fromEntries(['calories','protein_g','carbs_g','fat_g','fiber_g','sodium_mg','saturated_fat_g','added_sugar_g'].map(k=>[k,{type:['object','null'],properties:{low:{type:'number'},high:{type:'number'}},required:['low','high'],additionalProperties:false}])),required:['calories','protein_g','carbs_g','fat_g','fiber_g'],additionalProperties:false},batchId:{type:['string','null']},weightG:{type:['number','null'],minimum:0},components:{type:'array',maxItems:30,items:{...obj({productId:s,grams:{type:'number',minimum:0}}),required:['productId','grams']}},productNutrition:{type:'array',maxItems:30,items:{...obj({productId:s,name:s,nutrition:{type:'object',properties:{nutrition_basis:{enum:['per_100g','per_100ml','per_unit']},serving_g:{type:['number','null'],minimum:0},...Object.fromEntries(['kcal','protein_g','carbs_g','fat_g','fiber_g','sodium_mg','sat_fat_g','added_sugar_g'].map(n=>[n,{type:['number','null'],minimum:0}]))},required:['nutrition_basis'],additionalProperties:false}}),required:['productId','name','nutrition']}},matchedRecipeId:{type:['string','null']},overallConfidence:{enum:['low','medium','high']},questions:{type:'array',maxItems:2,items:s},notes:s}),required:['captureId','category','title','items','nutrition','matchedRecipeId','overallConfidence','questions','notes']}},
 {type:'function',name:'meal_garden_learn',description:'Record one durable kitchen, recipe or app lesson from the person’s own reports (use reactions/preferences for tastes, portions, process and routine); legacy preference/routine areas remain readable. Kitchen (appliance calibration, equipment), recipe (corrections for a recipe id), or app (ways the app failed them, for developers). Reuse the same subject to replace an earlier note.',inputSchema:{...obj({area:{enum:['kitchen','preference','routine','recipe','app']},subject:s,note:s,evidence:s}),required:['area','subject','note','evidence']}},
 {type:'function',name:'meal_garden_observation',description:'Record a user-confirmed pantry observation without silently changing inventory.',inputSchema:{...obj({item:s,note:s,location:{enum:['unknown','pantry','fridge','freezer','counter']},quantityState:{enum:['unknown','none','low','enough','exact']},idempotencyKey:s}),required:['item','note','location','quantityState','idempotencyKey']}}
];
const num={type:'number',minimum:0},nullableNum={type:['number','null'],minimum:0};
const confidence={enum:['known','assumed','unknown']};
const evidence={type:'array',items:s};
const graphTool=(name,description,properties,required,write=false)=>({type:'function',name,description,
 inputSchema:{...obj({...properties,...(write?{idempotencyKey:s,evidence}:{})}),required:[...required,...(write?['idempotencyKey']:[])]}});
dynamicTools.push(
 graphTool('meal_garden_search_pantry','Find actual products and pantry lots whose words match a query; includes nutrition, its source and amounts left.',{query:s},['query']),
 graphTool('meal_garden_get_product','Read one actual product by id, including nutrition and source.',{id:s},['id']),
 graphTool('meal_garden_recent_batches','Read batches made in the last seven days, with ingredients, weight and amounts left.',{},[]),
 graphTool('meal_garden_food_log','Read current intake and product links for a local food day.',{day:s},['day']),
 graphTool('meal_garden_record_batch','Record something actually made, including actual ingredients and yield. Unknown amounts stay null. Do not duplicate an existing batch. Ingredient amounts use the product base unit, grams are for nutrition.',{
  id:s,title:s,recipeId:s,recipeRevision:num,madeAt:s,recordedFrom:{enum:['cook_mode','report','chat','food_log']},yieldG:nullableNum,yieldBasis:{enum:['measured','estimated','unknown']},portions:nullableNum,location:{enum:['fridge','freezer','pantry','counter','unknown']},confidence,
  ingredients:{type:'array',items:{...obj({name:s,productId:s,pantryItemId:s,amount:nullableNum,grams:nullableNum,amountText:s,baseUnit:{enum:['g','ml','count']},confidence,assumption:s}),required:['name']}}
 },['title','madeAt','recordedFrom','ingredients'],true),
 graphTool('meal_garden_count_pantry','Record a stated count in the lot product base unit. Null resets the balance to unknown; zero means gone.',{itemId:s,amount:nullableNum,confidence},['itemId','amount','confidence'],true),
 graphTool('meal_garden_set_product_nutrition','Set product nutrition with its source, basis and evidence; never fabricate label values.',{id:s,nutrition:{...obj({nutrition_basis:{enum:['per_100g','per_100ml','per_unit']},nutrition_source:{enum:['label_photo','usda_fdc','open_food_facts','web','estimate','none']},nutrition_url:s,serving_g:nullableNum,barcode:s,per_serving:{type:'object',properties:Object.fromEntries(['kcal','protein_g','carbs_g','fat_g','fiber_g','sodium_mg','sat_fat_g','added_sugar_g'].map(n=>[n,nullableNum])),additionalProperties:false},grams_per_unit:nullableNum,...Object.fromEntries(['kcal','protein_g','carbs_g','fat_g','fiber_g','sodium_mg','sat_fat_g','added_sugar_g'].map(n=>[n,nullableNum]))}),required:['nutrition_basis','nutrition_source']}},['id','nutrition'],true),
 graphTool('meal_garden_assume','Record an explicit assumption and optional pantry uses. Changes are positive use amounts in product base units, null for unknown use; corrections reverse these movements.',{statement:s,changes:{type:'array',items:{...obj({pantryItemId:s,amount:nullableNum}),required:['pantryItemId','amount']}}},['statement','evidence','changes'],true),
 graphTool('meal_garden_record_reaction','Record the person’s actual rating, words and aspects as evidence, linked to recipe, batch or intake.',{person:s,batchId:s,recipeId:s,intakeId:s,rating:{type:['integer','null'],minimum:1,maximum:10},aspects:{type:'object',additionalProperties:{type:'string'}},notes:s},['notes'],true),
 graphTool('meal_garden_set_preference','Record an evidence-backed durable conclusion; reusing kind and subject supersedes the old preference.',{person:s,kind:{enum:['constraint','taste','portion','process','routine','goal']},subject:s,productId:s,recipeId:s,stance:{enum:['never','avoid','neutral','like','love']},value:{type:['string','number','object','array','boolean','null']},isHard:{type:'boolean'},statement:s,source:{enum:['stated','inferred','imported']},confidence},['kind','subject','statement','source','confidence','evidence'],true),
 graphTool('meal_garden_save_recipe','Create or adapt a complete canonical recipe. Code checks hard constraints before writing; fix all conflicts returned as errors. Use intended revisions and scope; then rebuild cards.',{recipeJson:s},['recipeJson'])
);

const graphReads=['meal_garden_search_pantry','meal_garden_get_product','meal_garden_recent_batches','meal_garden_food_log'];
export function toolsForTask(kind,household=loadHousehold()){
 const allowed=kind==='log_food'?[...graphReads,'meal_garden_record_batch','meal_garden_food_log_result']:
  kind==='product_lookup'?['meal_garden_get_product','meal_garden_set_product_nutrition']:
  kind==='reflect'?[...graphReads,'meal_garden_record_reaction','meal_garden_set_preference','meal_garden_learn']:null;
 const tools=allowed?dynamicTools.filter(t=>allowed.includes(t.name)):dynamicTools;
 return household.shopping?tools:tools.filter(t=>!['meal_garden_save_receipt','meal_garden_job_result'].includes(t.name));
}

export function jobPromptsForHousehold(household=loadHousehold()) {
 const s=household.shopping;if(!s)return {};
 const retailer=s.store,label=retailer?[retailer.chain,retailer.label].filter(Boolean).join(' '):null;
 const store=retailer?[retailer.chain,retailer.label,retailer.address].filter(Boolean).join(', '):null;
 const location=retailer?`For EACH buy item, browse the ${retailer.chain} WEBSITE with the store visibly set to ${store}. Open the matching product and read its exact location text. Match product and size; never infer an aisle from category, a different store, search snippets or guessed layouts. Put a verified location at the START of the Samsung Food comment, e.g. '${label}: Aisle 8. Used for: ...'. Preserve the full recipe-use comment. If no verifiable location exists, put '${label}: location unavailable', continue and report the limitation.`:'No retailer is configured. Keep recipe-use comments and never invent a store location.';
 return {
 shopping:`Sync ${household.person.name}'s explicitly requested purchases to Samsung Food ${s.list_name}, and nowhere else. Read docs/samsung-food.md if present, the current plan, data/shopping/current-trip.json, generated recipe demand and LIVE list rows first. If the trip matches the active plan and has a buy array, sync only the code-reviewed purchase rows in this prompt; conditional rows excluded by code must never be added; full recipe demand is not a purchase list. The person's Sync action authorizes this current trip. ${location} Never create or edit a retailer list/cart. Read live ${s.list_name} rows before writing, preserve unrelated rows and checkmarks, use natural purchase units, and verify every changed row and comment. If the active plan is past or trip absent/mismatched, clarify before writing. Report partial failures honestly with meal_garden_job_result.`,
 ...(retailer?{purchases:`Refresh recent ${retailer.chain} purchases from the signed-in laptop browser (at most the 10 most recent receipts). Read visible item-level receipts and save each through meal_garden_save_receipt. Skip imported fingerprints. Do not infer current stock or buy anything. If sign-in is required, report needs_sign_in with a clear next step. Record the verified outcome with meal_garden_job_result.`}:{}),
 connection:`Check whether the laptop browser can access Samsung Food ${s.list_name}${retailer?` and ${retailer.chain} purchase history`:''}. Read only; change nothing. Report each configured site's actual access state with meal_garden_job_result.`
 };
}
export const jobPrompts=jobPromptsForHousehold();

// Server-built prompts: deterministic context compiled by code, never pasted into the chat box.
export const resetPlanPrompt=(context,note,household=loadHousehold())=>[
 `Kitchen reset follow-up. ${household.person.name} just triaged the kitchen in the app; the compiled state is below. Plan what to cook next from it.`,context,
 note?`The person's note: ${JSON.stringify(note)}`:'',
 'Do this:',
 '1. Briefly name the two or three most perishable confirmed usable items and leftover decisions that matter today. Treat estimated ages and incomplete storage information as uncertain; do not turn a schedule into proof of leftovers.',
 '2. Offer at most three options with meal_garden_card (one card, prompt actions): cook an already planned recipe, adapt it to known stock, or a new web-grounded recipe using the at-risk items. Prefer no shopping or a tiny trip. Give title, why, active minutes and what it uses up.',
 `3. When the person chooses, write or adapt the recipe to the RECIPE STANDARD, add it to a new active dated plan covering the next few days (supersede the old active plan), run python3 scripts/build.py and reply with a native recipe link.${household.shopping?' Ask before any shopping sync.':''}`,
 'Do not treat unchecked items as available. Ask only questions that change the plan. Keep replies short.'
].filter(Boolean).join('\n\n');

export const interviewPrompt=(kitchen,household=loadHousehold())=>[
 `Run a two-minute kitchen interview for ${household.person.name} so future recipes stop assuming equipment or staples.`,
 `Currently recorded kitchen: ${JSON.stringify({available:kitchen?.available||[],not_available:kitchen?.not_available_as_last_reported||[],unconfirmed:kitchen?.unconfirmed||[]})}`,
 'Interview conversationally: send one round at a time as a short numbered list (4–6 quick questions), then wait for the next reply. Accept loose or voice-transcribed answers. At most three rounds. Do not use request_user_input. Cover cookware counts and sizes; knives, boards, measuring tools and kitchen scale; colander, steamer, tofu press, grater, peeler, thermometer, leftover containers and freezer space; usual oils, salt, spices and sauces; typical weeknight cooking time.',
 `Then update profile/kitchen.json (preserve existing fields; add tools, cookware, storage, staples_always_on_hand and last_interview with today's date and source '${household.person.name} via Meal Garden interview'). Add a weeknight time note to profile/${household.person.id}.json meal_rhythm. Unknowns stay unknown. Finish with one short confirmation card.`
].join('\n\n');
