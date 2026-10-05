import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import {connectionAddresses,isTailnetAddress,setupPage} from './pairing.mjs';
import {fileURLToPath} from 'node:url';
import {Store} from './store.mjs';
import {reviewShopping} from './shopping.mjs';
import {FoodGraph} from './graph.mjs';
import {CodexProvider} from './providers/codex.mjs';
import {loadHousehold,androidActor} from './household.mjs';
import {Jobs} from './jobs.mjs';
import {householdPath,snapshot,hash,id,now,read,recordCooking,recordObservation,recordAppNote,requireText} from './domain.mjs';
import {recordCapture,addCaptureDetail,captureView,recentCaptures,interpretationPrompt} from './capture.mjs';
import {kitchenReset,resetContext,recordLeftover} from './kitchen.mjs';
import {currentLearning,reflectContext,reflectPrompt} from './learning.mjs';
import {healthPreferences,saveHealthPreferences} from './health.mjs';
import {localDate} from './domain.mjs';
import {resetPlanPrompt,interviewPrompt} from './agent-contract.mjs';
export const ROOT=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
// Source files stay in ROOT. Every mutable record belongs to one household root.
export const DATA_ROOT=process.env.MEAL_DATA_DIR?path.resolve(process.env.MEAL_DATA_DIR):null;
function providerDataHome(root){
 const households=path.dirname(fs.realpathSync(root));
 return path.basename(households)==='households'?path.dirname(households):null;
}
function createGarden(root,{provider,dataHome,isolateExternal=false,runtime=path.join(root,'.runtime')}={}){
 const household=loadHousehold(root);
 runtime=householdPath(root,runtime);
 fs.mkdirSync(householdPath(root,runtime,'uploads'),{recursive:true,mode:0o700});
 provider ||= new CodexProvider(root,{dataHome,codeRoot:ROOT,isolateExternal});
 const store=new Store(path.join(runtime,'garden.sqlite'),{root,household}),jobs=new Jobs(store,provider,root,runtime,ROOT),graph=new FoodGraph(store,{root,onProductsCreated:ids=>jobs.lookupProducts(ids).catch(e=>console.error('product lookup failed',e.message))});
 const reflect=(recipeId,day=localDate(household))=>jobs.enqueue({kind:'reflect',requestKey:`reflect:${recipeId}:${day}:${crypto.randomUUID()}`,text:reflectPrompt(reflectContext(root,recipeId,day),household),recipeId,day,client:{origin:'after_cook'}},{internal:true});
 const settings=()=>({dayStartHour:Number(store.get("SELECT value FROM settings WHERE key='day_start_hour'")?.value??4)});
 const interpret=captureId=>{const view=captureView(store,captureId);const job=jobs.enqueue({kind:'log_food',requestKey:`log_food:${captureId}:${crypto.randomUUID()}`,text:interpretationPrompt(view,household),captureId,mediaPaths:view.mediaList.map(m=>m.path),client:{origin:'food_capture'}},{internal:true});store.record('interpretation_requested',`capture:${captureId}`,{captureId,jobId:job.id});return captureView(store,captureId)};
 return {root,runtime,household,store,jobs,graph,provider,reflect,settings,interpret,pairCode:crypto.randomInt(10000000,99999999).toString(),pairExpires:Date.now()+600000};
}
export function createCompanion(options={}){
 let root=options.root?path.resolve(options.root):DATA_ROOT,dataHome=options.dataHome?path.resolve(options.dataHome):!root&&process.env.MEAL_HOME?path.resolve(process.env.MEAL_HOME):null,sampleRoot=null;
 if(options.root&&options.dataHome)throw new Error('Use root for one household or dataHome for several households');
 if(dataHome&&(options.provider||options.runtime))throw new Error('Multi-household runs require providerFactory; runtime directories are household-specific');
 if(!root&&!dataHome){sampleRoot=fs.mkdtempSync(path.join(os.tmpdir(),'meal-garden-sample-'));fs.cpSync(path.join(ROOT,'sample-household'),sampleRoot,{recursive:true});root=sampleRoot;console.log(`Meal Garden is using a temporary sample household: ${root}`)}
 const roots=dataHome?fs.readdirSync(path.join(dataHome,'households'),{withFileTypes:true}).filter(entry=>entry.isDirectory()).map(entry=>path.join(dataHome,'households',entry.name)).sort():[root];
 if(!roots.length)throw new Error('No households found. Run scripts/add-household.mjs first.');
 const gardens=new Map();
 try{for(const gardenRoot of roots){const household=loadHousehold(gardenRoot);if(dataHome&&household.id!==path.basename(gardenRoot))throw new Error('Household id must match its folder name');if(gardens.has(household.id))throw new Error(`Duplicate household id: ${household.id}`);const garden=createGarden(gardenRoot,{provider:options.provider||options.providerFactory?.(gardenRoot,household),runtime:options.runtime,dataHome:dataHome||providerDataHome(gardenRoot),isolateExternal:roots.length>1});while([...gardens.values()].some(other=>other.pairCode===garden.pairCode))garden.pairCode=crypto.randomInt(10000000,99999999).toString();gardens.set(household.id,garden)}}catch(error){for(const g of gardens.values()){g.jobs.close();g.provider.close();g.store.close()}if(sampleRoot)fs.rmSync(sampleRoot,{recursive:true,force:true});throw error}
 const failures=new Map(),tokenGardens=new Map();
 const authenticate=token=>{
  if(!token)return null;const digest=hash(token),cached=tokenGardens.get(digest);
  if(cached){const device=cached.store.get('SELECT name FROM devices WHERE hash=?',digest);if(device)return {garden:cached,device};tokenGardens.delete(digest)}
  const matches=[...gardens.values()].map(garden=>({garden,device:garden.store.get('SELECT name FROM devices WHERE hash=?',digest)})).filter(match=>match.device);
  if(matches.length!==1)return null;tokenGardens.set(digest,matches[0].garden);return matches[0];
 };
 const json=(res,status,obj)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(obj))};
 const local=req=>['127.0.0.1','::1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
 async function body(req,max=1024*1024){let size=0;const parts=[];for await(const x of req){size+=x.length;if(size>max)throw new Error('Request too large');parts.push(x)}const parsed=JSON.parse(Buffer.concat(parts).toString()||'{}');if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))throw new Error('Request body must be an object');if(['household','householdId','household_id'].some(key=>Object.hasOwn(parsed,key)))throw new Error('Household selection is determined by pairing and the device token');if(req.household&&['person','personId','person_id'].some(key=>Object.hasOwn(parsed,key)&&parsed[key]!==req.household.person.id))throw new Error('This device cannot act for another person');return parsed}
 const server=http.createServer(async(req,res)=>{
  try{
   const url=new URL(req.url,'http://localhost'),route=url.pathname;
   // Native client has no Origin. Browser access is limited to our local setup page.
   if(req.headers.origin){const origin=new URL(req.headers.origin);if(!local(req)||origin.host!==req.headers.host)return json(res,403,{error:'Browser origin not allowed'});}
   if(route==='/internal/product-lookups'&&req.method==='POST'){if(!local(req))return json(res,403,{error:'Laptop-only operation'});const match=authenticate((req.headers.authorization||'').replace(/^Bearer /,'')),garden=match?.garden||(gardens.size===1?[...gardens.values()][0]:null);if(!garden)return json(res,401,{error:'Authenticate a household device for this operation'});const b=await body(req);if(b.productIds!=null&&!Array.isArray(b.productIds))throw new Error('productIds must be an array');return json(res,200,await garden.jobs.lookupProducts(b.productIds||null));}
   if(route==='/health'&&req.method==='GET')return json(res,200,{app:'Meal Garden',version:'0.1.1'});
   if(route==='/setup'&&req.method==='GET'){
    if(!local(req)||!/^((localhost|127\.0\.0\.1)(:\d+)?|\[::1\](:\d+)?)$/.test(req.headers.host||''))return json(res,403,{error:'Open setup on the laptop at localhost.'});
    for(const garden of gardens.values()){if(Date.now()>garden.pairExpires){do{garden.pairCode=crypto.randomInt(10000000,99999999).toString()}while([...gardens.values()].some(other=>other!==garden&&other.pairCode===garden.pairCode));garden.pairExpires=Date.now()+600000}}
    const addresses=connectionAddresses(os.networkInterfaces(),server.address().port);
    res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'"});
    return res.end(await setupPage(addresses,[...gardens.values()].map(g=>({name:g.household.name,code:g.pairCode,expires:g.pairExpires}))));
   }
   if(route==='/download/meal-garden.apk'&&req.method==='GET'){
    if(!local(req)&&!isTailnetAddress(req.socket.remoteAddress)&&!authenticate((req.headers.authorization||'').replace(/^Bearer /,''))&&![...gardens.values()].some(g=>Date.now()<g.pairExpires&&url.searchParams.get('code')===g.pairCode))return json(res,401,{error:'Use the current APK download link from laptop setup.'});
    const f=dataHome?path.join(dataHome,'builds/meal-garden.apk'):path.join(roots[0],'exports/meal-garden.apk');if(!fs.existsSync(f))return json(res,404,{error:'APK has not been built'});
    res.writeHead(200,{'Content-Type':'application/vnd.android.package-archive','Content-Disposition':'attachment; filename="meal-garden.apk"','Content-Length':fs.statSync(f).size,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});return fs.createReadStream(f).pipe(res);
   }
   // Design Lab prototypes contain real personal data: laptop and tailnet only, read-only static files.
   if((route==='/lab'||route.startsWith('/lab/'))&&req.method==='GET'){
    if(!local(req)&&!isTailnetAddress(req.socket.remoteAddress))return json(res,403,{error:'Design Lab is available on this laptop and your tailnet only.'});
    const privateLab=route==='/lab/private'||route.startsWith('/lab/private/');
    if(privateLab&&!dataHome)return json(res,404,{error:'Not found'});
    const labRoot=privateLab?path.join(dataHome,'workspace/design-lab'):path.join(ROOT,'design-lab'),relative=decodeURIComponent(route.slice(privateLab?12:4)).replace(/^\/+/,'')||'index.html';
    let f=path.resolve(labRoot,relative);if(!f.startsWith(labRoot+path.sep))return json(res,404,{error:'Not found'});
    if(fs.existsSync(f)&&fs.statSync(f).isDirectory())f=path.join(f,'index.html');
    if(!fs.existsSync(f)||!fs.realpathSync(f).startsWith(fs.realpathSync(labRoot)+path.sep))return json(res,404,{error:'Not found'});
    const type={'.html':'text/html; charset=utf-8','.json':'application/json','.md':'text/markdown; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg','.css':'text/css','.js':'text/javascript'}[path.extname(f)]||'application/octet-stream';
    res.writeHead(200,{'Content-Type':type,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});return fs.createReadStream(f).pipe(res);
   }
   if(route==='/pair'&&req.method==='POST'){
    const remote=req.socket.remoteAddress;const f=failures.get(remote)||{count:0,until:Date.now()+600000};if(Date.now()>f.until){f.count=0;f.until=Date.now()+600000}if(f.count>=5)return json(res,429,{error:'Too many attempts. Wait ten minutes.'});
    const b=await body(req,2000),garden=[...gardens.values()].find(g=>Date.now()<g.pairExpires&&typeof b.code==='string'&&b.code===g.pairCode);if(!garden){f.count++;failures.set(remote,f);return json(res,401,{error:'Code expired or incorrect. Open localhost setup on the laptop.'})}
    const token=crypto.randomBytes(32).toString('base64url');garden.store.run('INSERT INTO devices VALUES(?,?,?)',hash(token),String(b.name||'Android phone').slice(0,100),now());garden.pairExpires=0;tokenGardens.set(hash(token),garden);return json(res,200,{token,name:garden.household.name,household:garden.household});
   }
   const token=(req.headers.authorization||'').replace(/^Bearer /,''),authenticated=authenticate(token);if(!authenticated)return json(res,401,{error:'Connect this phone to the laptop first.'});
   req.household=authenticated.garden.household;
   const {garden,device}=authenticated,{root,runtime,household,store,jobs,graph,reflect,settings,interpret}=garden,actor=androidActor(household);
   if(route.startsWith('/api/media/')&&req.method==='GET'){
    const sha=route.slice('/api/media/'.length);if(!/^[a-f0-9]{64}$/.test(sha))return json(res,404,{error:'Unknown media'});
    const media=store.get('SELECT path,mime FROM media WHERE sha256=?',sha);
    if(!media)return json(res,404,{error:'Unknown media'});
    const file=path.resolve(runtime,media.path),base=fs.realpathSync(runtime);
    if(!file.startsWith(path.resolve(runtime)+path.sep)||!fs.existsSync(file)||!fs.realpathSync(file).startsWith(base+path.sep))return json(res,404,{error:'Unknown media'});
    res.writeHead(200,{'Content-Type':media.mime,'Content-Length':fs.statSync(file).size,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});return fs.createReadStream(file).pipe(res);
   }
   if(route==='/api/snapshot'&&req.method==='GET')return json(res,200,{...snapshot(root),captures:recentCaptures(store),kitchenReset:kitchenReset(root,undefined,undefined,store),learned:currentLearning(store),settings:settings(),healthPreferences:healthPreferences(store),pantry:graph.pantryView(),batches:graph.recentBatches(),assumptions:graph.openAssumptions(),preferences:graph.currentPreferences()});
   if(route==='/api/shopping/review'&&req.method==='POST')return json(res,201,reviewShopping(root,store,await body(req)));
   const graphRoutes={
    '/api/pantry/add':b=>graph.observePantry(b,{...b,actor}),
    '/api/intake/allocate':b=>graph.allocateIntake(b.componentId,b.itemId,{...b,actor}),
    '/api/pantry/count':b=>graph.countPantryItem(b.itemId,b.amount,b.confidence||'known',actor,b),
    '/api/pantry/condition':b=>graph.setCondition(b.itemId,b.condition,{...b,actor}),
    '/api/pantry/toss':b=>graph.tossPantryItem(b.itemId,{...b,actor}),
    '/api/pantry/transfer':b=>graph.transfer(b.itemId,b.amount??null,b.toLocation,{...b,actor}),
    '/api/batches':b=>graph.recordBatch(b,{...b,actor}),
    '/api/assumptions/resolve':b=>graph.resolveAssumptions(b.ids,b.status,{...b,actor}),
    '/api/reactions':b=>graph.recordReaction(b,{...b,actor}),
    '/api/preferences':b=>graph.setPreference(b,{...b,actor}),
   };
   if(req.method==='POST'&&Object.hasOwn(graphRoutes,route))return json(res,201,graphRoutes[route](await body(req)));
   if(route==='/api/health/preferences'&&req.method==='POST')return json(res,200,saveHealthPreferences(store,await body(req)));
   if(route==='/api/activity'&&req.method==='GET')return json(res,200,{conversations:store.conversations(),jobs:store.jobs(),requests:[...jobs.requests.values()].map(({rpcId,...r})=>r),provider:{name:'Codex · ChatGPT subscription',mainModel:'gpt-6.1-sol',quickModel:'gpt-6.1-sol',effort:'medium'}});
   if(route==='/api/messages'&&req.method==='GET')return json(res,200,{messages:store.messages(url.searchParams.get('conversation')||'')});
   if(route==='/api/jobs'&&req.method==='POST'){
    const b=await body(req);if((b.attachmentIds||[]).some(x=>typeof x!=='string'||!/^[a-f0-9-]{36}$/.test(x)||!fs.existsSync(householdPath(root,runtime,'uploads',x+'.jpg'))))throw new Error('Unknown attachment');
    // Planning and interview prompts are compiled here from current state; the phone only supplies an optional note.
    if(b.kind==='reset_plan'||b.kind==='interview'){
     const note=typeof b.note==='string'?b.note.trim().slice(0,2000):'';
     const text=b.kind==='reset_plan'?resetPlanPrompt(resetContext(kitchenReset(root,undefined,undefined,store)),note,household):interviewPrompt(read(householdPath(root,'profile/kitchen.json')),household);
     return json(res,202,jobs.enqueue({...b,text,note,conversationId:'',deviceName:device.name},{internal:true}));
    }
    return json(res,202,jobs.enqueue({...b,deviceName:device.name}));
   }
   if(route==='/api/cancel'&&req.method==='POST'){await jobs.cancel((await body(req)).jobId);return json(res,200,{ok:true})}
   if(route==='/api/answer'&&req.method==='POST'){const b=await body(req);jobs.answer(b.id,b);return json(res,200,{ok:true})}
   if(route==='/api/observations'&&req.method==='POST')return json(res,201,recordObservation(root,await body(req)));
   if(route==='/api/feedback'&&req.method==='POST')return json(res,201,recordAppNote(root,await body(req,4*1024*1024)));
   if(route==='/api/cooking'&&req.method==='POST'){const r=recordCooking(root,await body(req));try{reflect(r.recipeId)}catch(e){console.error('reflect enqueue failed',e.message)}return json(res,201,r)}
   if(route==='/api/reflect'&&req.method==='POST'){const b=await body(req);if(typeof b.recipeId!=='string'||!/^[a-z0-9-]+$/.test(b.recipeId))throw new Error('Invalid recipe');return json(res,202,reflect(b.recipeId,/^\d{4}-\d{2}-\d{2}$/.test(b.day||'')?b.day:undefined))}
   if(route==='/api/settings'&&req.method==='POST'){const b=await body(req);if(!Number.isInteger(b.dayStartHour)||b.dayStartHour<0||b.dayStartHour>12)throw new Error('dayStartHour must be 0–12');store.run("INSERT INTO settings(key,value) VALUES('day_start_hour',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",String(b.dayStartHour));return json(res,200,settings())}
   // Interaction timing log from the phone (screen changes, taps, timers). Idempotent by event id.
   if(route==='/api/telemetry'&&req.method==='POST'){
    const b=await body(req,2*1024*1024),list=Array.isArray(b.events)?b.events.slice(0,1000):[];let saved=0;
    for(const e of list){if(typeof e?.id!=='string'||!/^[a-f0-9-]{36}$/.test(e.id)||typeof e.name!=='string'||typeof e.at!=='string')continue;
     saved+=store.run('INSERT OR IGNORE INTO ui_events(id,at,name,props,device,app_version,received) VALUES(?,?,?,?,?,?,?)',e.id,e.at.slice(0,45),e.name.slice(0,80),JSON.stringify(e.props||{}).slice(0,4000),String(b.device||'').slice(0,100),String(b.appVersion||'').slice(0,30),now()).changes}
    return json(res,200,{received:list.length,saved});
   }
   if(route==='/api/leftovers'&&req.method==='POST')return json(res,201,recordLeftover(store,root,await body(req)));
   if(['/api/captures','/api/captures/multi'].includes(route)&&req.method==='POST'){const r=recordCapture(store,runtime,await body(req,36*1024*1024),{actor});return json(res,r.created?201:200,r.created?interpret(r.capture.id):r.capture)}
   if(route==='/api/captures/detail'&&req.method==='POST'){const r=addCaptureDetail(store,await body(req),{actor});return json(res,r.created?201:200,r.created?interpret(r.capture.id):r.capture)}
   if(route==='/api/captures/interpret'&&req.method==='POST'){const b=await body(req);const v=typeof b.id==='string'&&captureView(store,b.id);if(!v)throw new Error('Unknown capture');return json(res,202,v.status==='interpreting'?v:interpret(v.id))}
   if(route==='/api/upload'&&req.method==='POST'){
    const b=await body(req,12*1024*1024);const bytes=Buffer.from(requireText(b.data,'Image',12*1024*1024),'base64');if(bytes.length>8*1024*1024||bytes[0]!==255||bytes[1]!==216)throw new Error('Use a JPEG image up to 8 MB');const uid=id();fs.writeFileSync(householdPath(root,runtime,'uploads',uid+'.jpg'),bytes,{mode:0o600});store.run('INSERT INTO attachments(id,created,bytes,sha256,mime,status) VALUES(?,?,?,?,?,?)',uid,now(),bytes.length,crypto.createHash('sha256').update(bytes).digest('hex'),'image/jpeg','uploaded');return json(res,201,{id:uid});
   }
   if(route==='/api/device'&&req.method==='DELETE'){store.run('DELETE FROM devices WHERE hash=?',hash(token));tokenGardens.delete(hash(token));return json(res,200,{ok:true})}
   return json(res,404,{error:'Unknown operation'});
  }catch(e){json(res,400,{error:e.message})}
 });
 const single=gardens.size===1?[...gardens.values()][0]:null;
 return {server,gardens,dataHome,sampleRoot,...(single?{root:single.root,store:single.store,graph:single.graph,jobs:single.jobs,provider:single.provider}:{}),close(){for(const g of gardens.values()){g.jobs.close();g.provider.close();g.store.close()}server.close();if(sampleRoot)fs.rmSync(sampleRoot,{recursive:true,force:true})}};
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
 process.umask(0o077);
 const app=createCompanion();const port=Number(process.env.MEAL_PORT||4783),host=process.env.MEAL_HOST||'127.0.0.1';
 app.server.listen(port,host,()=>{console.log(`Meal Garden companion listening on ${host}:${port}. Pair on this laptop: http://localhost:${port}/setup`);for(const garden of app.gardens.values())garden.jobs.pump()});
 process.on('SIGINT',()=>{app.close();process.exit(0)});process.on('SIGTERM',()=>{app.close();process.exit(0)});
}
