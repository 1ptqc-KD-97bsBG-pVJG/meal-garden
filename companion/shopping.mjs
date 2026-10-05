import {androidActor} from './household.mjs';
import {householdPath,atomic,files,hash,localDate,now,read,requireText} from './domain.mjs';

export function tripView(root){
 const trip=read(householdPath(root,'data/shopping/current-trip.json'));
 return trip?{...trip,reviewKey:hash(JSON.stringify(trip))}:null;
}
export function reviewedShopping(root){
 const trip=tripView(root),plan=files(householdPath(root,'data/plans'),root).find(p=>p.status==='active');
 if(!trip||!plan||trip.plan_id!==plan.id||!plan.meals?.some(m=>m.date>=localDate(root)))throw new Error('Create a current plan and matching store trip before syncing.');
 if(!Array.isArray(trip.buy)||trip.buy.some(r=>r.purchase_condition&&!['buy','have'].includes(r.purchase_decision)))throw new Error('Choose Buy or Have enough for every conditional item before syncing.');
 return {...trip,buy:trip.buy.filter(r=>!r.purchase_condition||r.purchase_decision==='buy')};
}
export function reviewShopping(root,store,input){
 const key=requireText(input.idempotencyKey,'Idempotency key',250);
 const previous=store.get('SELECT operation,result FROM idempotency_keys WHERE key=?',key);
 if(previous){if(previous.operation!=='reviewShopping')throw new Error('Idempotency key belongs to another operation');return JSON.parse(previous.result);}
 const trip=tripView(root);
 const recovered=trip?.review_idempotency_key===key;
 if(!recovered&&(!trip||trip.reviewKey!==input.reviewKey))throw new Error('Shopping changed. Refresh and review the current trip.');
 if(!recovered&&(!Array.isArray(input.decisions)||input.decisions.length!==trip.buy.length||input.decisions.some(d=>!['buy','have'].includes(d))))throw new Error('Review every purchase row.');
 const {reviewKey,...result}=trip;
 if(!recovered){
  result.buy=trip.buy.map((row,index)=>({...row,purchase_decision:input.decisions[index]}));
  result.reviewed_at=now();result.status='reviewed';result.review_idempotency_key=key;
 }
 store.db.exec('BEGIN IMMEDIATE');
 try{
  // If the process stops between file rename and commit, the saved key lets the outbox retry safely.
  if(!recovered)atomic(householdPath(root,'data/shopping/current-trip.json'),result);
  store.record('shopping_review','shopping:'+trip.id,{idempotencyKey:key,previousReviewKey:input.reviewKey,decisions:result.buy.map(r=>r.purchase_decision),recovered},{actor:androidActor(store.household)});
  store.run('INSERT INTO idempotency_keys(key,operation,result,created_at) VALUES(?,?,?,?)',key,'reviewShopping',JSON.stringify(result),now());
  store.db.exec('COMMIT');return result;
 }catch(e){store.db.exec('ROLLBACK');throw e;}
}
