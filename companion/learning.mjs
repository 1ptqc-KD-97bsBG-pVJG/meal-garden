import {loadHousehold} from './household.mjs';
import {householdPath,files,read,requireText,localDate} from './domain.mjs';

// What Meal Garden has learned about the person, their kitchen and itself. Nothing here is hard-coded: every note is an
// append-only `learned` event with evidence, and the newest note per area+subject is what agents see.
export const LEARN_AREAS=['kitchen','preference','routine','recipe','app'];
const slug=text=>text.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'').slice(0,80);

export function recordLearning(store,input,actor='agent'){
 const area=LEARN_AREAS.includes(input.area)?input.area:null;if(!area)throw new Error(`area must be one of ${LEARN_AREAS.join(', ')}`);
 const subject=requireText(input.subject,'Subject',120),note=requireText(input.note,'Note',600),evidence=requireText(input.evidence,'Evidence',600);
 const payload={area,subject,note,evidence};
 store.record('learned',`learned:${area}:${slug(subject)}`,payload,{actor});
 return payload;
}

export function currentLearning(store){
 const latest=new Map();
 for(const row of store.all("SELECT subject,payload,recorded_at FROM events WHERE type='learned' ORDER BY recorded_at,rowid"))latest.set(row.subject,{...JSON.parse(row.payload),recordedAt:row.recorded_at});
 return [...latest.values()];
}

// Compact block injected into every agent prompt (the `app` area is for developers, not for cooking advice).
export function learningContext(store,limit=60,areas=LEARN_AREAS.filter(a=>a!=='app')){
 const notes=currentLearning(store).filter(n=>areas.includes(n.area)&&n.area!=='app').slice(-limit);
 if(!notes.length)return '';
 const by=area=>notes.filter(n=>n.area===area).map(n=>`- ${n.subject}: ${n.note}`);
 return [`What Meal Garden has learned so far (from ${store.household?.person.name||'the person'}’s own reports; newest wins; treat as their current reality):`,
  ...LEARN_AREAS.filter(a=>a!=='app').flatMap(a=>by(a).length?[`${a[0].toUpperCase()+a.slice(1)}:`,...by(a)]:[])].join('\n');
}

const householdDay=(root,iso)=>localDate(root,iso);

// Deterministic context for the after-cook reflection: the recipe, any cooking report, and the person's field notes from that cook.
export function reflectContext(root,recipeId,day){
 const recipe=read(householdPath(root,'recipes',recipeId,'recipe.json'));if(!recipe)throw new Error('Unknown recipe');
 const reports=files(householdPath(root,'data/cooking'),root).filter(c=>c.recipeId===recipeId&&householdDay(root,c.createdAt)>=day);
 const notes=files(householdPath(root,'data/app-feedback'),root).filter(n=>n.createdAt&&householdDay(root,n.createdAt)===day&&(n.recipeId===recipeId||/chat|recipe/i.test(n.screen||''))).sort((a,b)=>a.createdAt.localeCompare(b.createdAt));
 return [
  `Recipe: ${recipe.title} (${recipeId}, revision ${recipe.revision}), cooked on ${day}.`,
  `Steps as written:\n${(recipe.steps||[]).map((s,i)=>`${i+1}. [${s.equipment||'no equipment label'}] ${s.title}: ${s.text}`).join('\n')}`,
  reports.length?`Cooking report(s):\n${reports.map(r=>`- ${r.note}`).join('\n')}`:'No cooking report was saved for this cook.',
  notes.length?`Field notes during and around the cook (verbatim, voice-transcribed, may contain transcription errors):\n${notes.map(n=>`- [${n.phoneTime?.slice(11,16)||''} · ${n.screen}] ${n.text}`).join('\n')}`:'No field notes.',
 ].join('\n\n');
}

export const reflectPrompt=(context,household=loadHousehold())=>[
 `After-cook reflection for ${household.person.name} (${household.person.pronouns||'they/them'}). Read what happened. Record reactions with meal_garden_record_reaction and durable conclusions with meal_garden_set_preference; use meal_garden_learn for kitchen, recipe and app notes. Keep the response brief; this runs in the background.`,
 context,
 'Record a lesson only when the person’s own words support it; quote or paraphrase them as evidence. Use these areas:',
 '- kitchen: appliance calibration and equipment facts (for example which appliance power level actually held a boil), with the subject naming the appliance and task.',
 '- meal_garden_record_reaction: their rating, words and aspects (flavor, portion, effort), linked to this recipe or batch; these are evidence, not generalized preferences.',
 '- meal_garden_set_preference: taste, portion, process and routine conclusions supported by their words; choose kind and subject, include statement and evidence, source inferred and confidence assumed for an inferred conclusion. Reuse the subject when refining an existing preference.',
 '- recipe: corrections to this recipe for next time (subject = the recipe id).',
 '- app: ways the app failed or slowed them down, for the developers (subject = the screen or flow).',
 'Prefer a few precise notes over many vague ones. If a note refines an earlier one, reuse the same subject so it replaces it. Finish with one sentence listing what you recorded.'
].join('\n\n');
