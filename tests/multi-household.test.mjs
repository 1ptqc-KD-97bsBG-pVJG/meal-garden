import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {EventEmitter} from 'node:events';
import {spawnSync} from 'node:child_process';
import {once} from 'node:events';
import {createCompanion} from '../companion/server.mjs';
import {loadHousehold,profilePath,androidActor} from '../companion/household.mjs';
import {atomic,hash} from '../companion/domain.mjs';
import {addHousehold} from '../scripts/add-household.mjs';
import {CodexProvider} from '../companion/providers/codex.mjs';

class Provider extends EventEmitter{
 constructor(root){super();this.root=root;this.threadModels=new Map();this.calls=[]}
 async thread(...args){this.calls.push(args);this.threadModels.set('thread',args[3]);return 'thread'}
 async start(...args){this.startArgs=args;return {turn:{id:'turn'}}}
 async cancel(){this.cancelled=true}
 close(){}
}
const tick=()=>new Promise(resolve=>setTimeout(resolve,30));
function fixture(t){
 const dataHome=fs.mkdtempSync(path.join(os.tmpdir(),'garden-households-'));
 t.after(()=>fs.rmSync(dataHome,{recursive:true,force:true}));
 for(const id of ['alpha','beta']){
  const root=path.join(dataHome,'households',id);
  atomic(path.join(root,'household.json'),{schema_version:1,id,name:`${id} kitchen`,person:{id:`${id}-owner`,name:`${id} owner`},timezone:'UTC',...(id==='alpha'?{shopping:{list_service:'samsung_food',list_name:'Test shopping',store:{chain:'Test market'}}}:{})});
  atomic(path.join(root,'profile',`${id}-owner.json`),{name:`${id} profile`});
  atomic(path.join(root,'recipes',id,'recipe.json'),{id,title:`${id} recipe`,readiness:'ready',revision:1,ingredients:[],steps:[]});
  atomic(path.join(root,'data/plans/current.json'),{id:'current',status:'active',meals:[]});
 }
 return dataHome;
}
test('single-root and MEAL_DATA_DIR runs infer the sibling filesystem boundary while retaining external shopping',t=>{
 const dataHome=fixture(t),root=path.join(dataHome,'households/alpha');
 const app=createCompanion({root});
 try{assert.equal(app.provider.dataHome,fs.realpathSync(dataHome));assert.equal(app.provider.isolateExternal,false);assert.equal(app.provider.externalShoppingUnavailable,false);assert.equal(app.dataHome,null);assert.equal(app.root,root);assert.equal(app.gardens.size,1);assert.equal(app.store.root,root)}finally{app.close()}
 const alias=path.join(dataHome,'household-alias');fs.symlinkSync(root,alias);const aliased=createCompanion({root:alias});
 try{assert.equal(aliased.provider.dataHome,fs.realpathSync(dataHome));assert.equal(aliased.root,alias);assert.equal(aliased.provider.externalShoppingUnavailable,false)}finally{aliased.close()}
 const entry=new URL('../companion/server.mjs',import.meta.url).href;
 const script=`import {createCompanion} from ${JSON.stringify(entry)};const app=createCompanion();console.log(JSON.stringify({boundary:app.provider.dataHome,external:app.provider.externalShoppingUnavailable,root:app.root,size:app.gardens.size,dataHome:app.dataHome}));app.close();`;
 const child=spawnSync(process.execPath,['--input-type=module','-e',script],{env:{...process.env,MEAL_HOME:'',MEAL_DATA_DIR:root},encoding:'utf8',timeout:10000});assert.equal(child.status,0,child.stderr);assert.deepEqual(JSON.parse(child.stdout),{boundary:fs.realpathSync(dataHome),external:false,root,size:1,dataHome:null});
 const bare=fs.mkdtempSync(path.join(os.tmpdir(),'garden-bare-root-'));t.after(()=>fs.rmSync(bare,{recursive:true,force:true}));const original=createCompanion({root:bare});
 try{assert.equal(original.provider.dataHome,null);assert.equal(original.provider.externalShoppingUnavailable,false)}finally{original.close()}
});
async function running(t){
 const dataHome=fixture(t),app=createCompanion({dataHome,providerFactory:root=>new Provider(root)});
 t.after(()=>app.close());await new Promise(resolve=>app.server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${app.server.address().port}`;
 const call=async(route,token,body,method=body?'POST':'GET')=>{const response=await fetch(base+route,{method,headers:{...(token?{Authorization:`Bearer ${token}`} :{}),'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});return {status:response.status,body:await response.json()}};
 const html=await (await fetch(base+'/setup')).text(),codes=[...html.matchAll(/<strong>(\d{8})<\/strong>/g)].map(match=>match[1]);
 assert.equal(codes.length,2);assert.notEqual(codes[0],codes[1]);
 const a=await call('/pair',null,{code:codes[0],name:'phone alpha'}),b=await call('/pair',null,{code:codes[1],name:'phone beta'});
 assert.equal(a.body.name,'alpha kitchen');assert.equal(b.body.name,'beta kitchen');
 assert.equal((await call('/pair',null,{code:codes[0]})).status,401);
 return {app,dataHome,base,call,a:a.body.token,b:b.body.token};
}

test('household loader validates IDs, timezone and note containment, with neutral bare-root defaults',t=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-household-loader-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 assert.deepEqual(loadHousehold(root),loadHousehold());assert.equal(loadHousehold(root).timezone,'UTC');assert.equal(androidActor(root),'owner:android');assert.equal(profilePath(root),path.join(root,'profile/owner.json'));
 const valid={schema_version:1,id:'test',name:'Test kitchen',person:{id:'person',name:'Test person'},timezone:'UTC'};
 for(const update of [{id:'../other'},{person:{id:'../other',name:'Other'}},{timezone:'not-a-timezone'},{assistant_notes:'../private.md'},{shopping:{list_service:'invented',list_name:'Test'}}]){atomic(path.join(root,'household.json'),{...valid,...update});assert.throws(()=>loadHousehold(root))}
 atomic(path.join(root,'household.json'),valid);assert.equal(loadHousehold(root).person.pronouns,'they/them');
 const outside=fs.mkdtempSync(path.join(os.tmpdir(),'garden-notes-outside-'));t.after(()=>fs.rmSync(outside,{recursive:true,force:true}));fs.writeFileSync(path.join(outside,'note.md'),'private');fs.symlinkSync(path.join(outside,'note.md'),path.join(root,'note.md'));
 atomic(path.join(root,'household.json'),{...valid,assistant_notes:'note.md'});assert.throws(()=>loadHousehold(root),/inside/);
});

test('pairing, snapshots, jobs, conversations, messages, cancellation and activity stay inside the token household',async t=>{
 const {app,call,a,b}=await running(t),alpha=app.gardens.get('alpha'),beta=app.gardens.get('beta');
 for(const [token,id] of [[a,'alpha'],[b,'beta']]){const view=await call('/api/snapshot?household=other',token);assert.equal(view.status,200);assert.equal(view.body.household.id,id);assert.equal(view.body.profile.name,`${id} profile`);assert.deepEqual(view.body.recipes.map(recipe=>recipe.id),[id])}
 const first=await call('/api/jobs',a,{requestKey:'same',text:'alpha secret'}),second=await call('/api/jobs',b,{requestKey:'same',text:'beta secret'});await tick();
 assert.equal(first.status,202);assert.equal(second.status,202);assert.notEqual(first.body.id,second.body.id);
 assert.equal(alpha.provider.root,alpha.root);assert.equal(beta.provider.root,beta.root);assert.match(alpha.provider.startArgs[1],/alpha owner/);assert.doesNotMatch(alpha.provider.startArgs[1],/beta owner/);
 assert.equal((await call('/api/messages?conversation='+second.body.conversation_id,a)).body.messages.length,0);
 const activity=(await call('/api/activity',a)).body;assert.deepEqual(activity.jobs.map(job=>job.id),[first.body.id]);assert.ok(JSON.stringify(activity).includes('alpha secret'));assert.ok(!JSON.stringify(activity).includes('beta secret'));
 assert.equal((await call('/api/cancel',a,{jobId:second.body.id})).status,400);assert.equal(beta.provider.cancelled,undefined);
 for(const kind of ['shopping','purchases','connection']){const result=await call('/api/jobs',b,{kind,requestKey:kind});assert.equal(result.status,400);assert.match(result.body.error,/no shopping integration/)}
 assert.equal(beta.store.jobs().length,1);
 for(const field of ['household','householdId','household_id'])assert.equal((await call('/api/jobs',a,{requestKey:field,text:'Attempt to select another kitchen',[field]:'beta'})).status,400);
 assert.equal((await call('/api/pantry/add',a,{person:'beta-owner',name:'Foreign person'})).status,400);
 assert.equal((await call('/api/device',a,null,'DELETE')).status,200);assert.equal((await call('/api/activity',a)).status,401);assert.equal((await call('/api/activity',b)).status,200);
});

test('filesystem symlinks cannot redirect household identity, uploads or runtime into another garden',async t=>{
 const {app,call,a}=await running(t),alpha=app.gardens.get('alpha'),beta=app.gardens.get('beta');
 fs.rmSync(path.join(alpha.runtime,'uploads'),{recursive:true});fs.symlinkSync(path.join(beta.runtime,'uploads'),path.join(alpha.runtime,'uploads'));
 const result=await call('/api/upload',a,{data:Buffer.from([255,216,255,217]).toString('base64')});assert.equal(result.status,400);assert.match(result.body.error,/household/);assert.equal(beta.store.get('SELECT count(*) AS n FROM attachments').n,0);
 const root=path.join(app.dataHome,'outside-test');fs.mkdirSync(root);fs.symlinkSync(path.join(beta.root,'household.json'),path.join(root,'household.json'));assert.throws(()=>loadHousehold(root),/inside/);
 fs.unlinkSync(path.join(root,'household.json'));fs.symlinkSync(beta.runtime,path.join(root,'.runtime'));assert.throws(()=>createCompanion({root,provider:new Provider(root)}),/household/);
});

test('providers refuse foreign saved threads and fail closed when runtime isolation is unconfirmed',async t=>{
 const dataHome=fixture(t),root=path.join(dataHome,'households/alpha'),provider=new CodexProvider(root,{dataHome});
 provider.connect=async()=>{provider.models=[{model:'gpt-6.1-sol'}]};const methods=[];
 provider.loaded.add('foreign');provider.threadPermissions.set('foreign','meal_household_write');
 provider.request=async(method,params)=>{methods.push(method);if(method==='thread/read')return {thread:{cwd:params.threadId==='foreign'?path.join(dataHome,'households/beta'):root,model:'gpt-6.1-sol'}};if(method==='thread/start')return {thread:{id:'own'},cwd:root,approvalPolicy:'never',activePermissionProfile:{id:'meal_household_write'}};if(method==='mcpServerStatus/list')return {data:[]};throw new Error(method)};
 assert.equal(await provider.thread('foreign',[],'Only own kitchen'),'own');assert.ok(!methods.includes('thread/resume'));
 const bad=new CodexProvider(root,{dataHome});bad.connect=async()=>{bad.models=[{model:'gpt-6.1-sol'}]};bad.request=async()=>({thread:{id:'unscoped'},cwd:root,approvalPolicy:'on-request',activePermissionProfile:{id:':danger-full-access'}});
 await assert.rejects(()=>bad.thread(null,[],'Only own kitchen'),/did not confirm/);await assert.rejects(()=>bad.start('unscoped','Hello','gpt-6.1-sol'),/not verified/);
 provider.threadPermissions.set('own','meal_household_write');
 const savedRequest=provider.request;provider.request=(method,params)=>method==='mcpServerStatus/list'?Promise.resolve({data:[{tools:{read_file:{}}}]}):savedRequest(method,params);
 await assert.rejects(()=>provider.thread(null,[],'Reject bypass tool'),/external tool/);assert.equal(provider.threadPermissions.has('own'),false);await assert.rejects(()=>provider.start('own','Hello','gpt-6.1-sol'),/not verified/);
});

test('strict model verification accepts top-level metadata and rejects conflicting runtime evidence',async()=>{
 const provider=new CodexProvider(os.tmpdir());provider.connect=async()=>{provider.models=[{model:'gpt-6.1-sol'}]};
 provider.request=async(method)=>method==='thread/start'?{thread:{id:'top-level'},model:'gpt-6.1-sol'}:method==='thread/read'?{thread:{id:'top-level'},model:'gpt-6.1-sol'}:{turn:{id:'verified-turn'}};
 assert.equal(await provider.thread(null,[],'Verify model','gpt-6.1-sol',true),'top-level');assert.equal(provider.verifiedModels.get('top-level'),'gpt-6.1-sol');assert.equal(provider.threadModels.get('top-level'),'gpt-6.1-sol');assert.equal((await provider.start('top-level','Hello','gpt-6.1-sol',[],true)).turn.id,'verified-turn');
 const bad=new CodexProvider(os.tmpdir());bad.connect=async()=>{bad.models=[{model:'gpt-6.1-sol'}]};bad.request=async()=>({thread:{id:'conflict',model:'gpt-6-luna'},model:'gpt-6.1-sol'});
 await assert.rejects(()=>bad.thread(null,[],'Conflicting model','gpt-6.1-sol',true),/did not confirm/);assert.equal(bad.verifiedModels.has('conflict'),false);
});

const nativeCodex=spawnSync(process.env.MEAL_CODEX_BIN||'codex',['--version'],{encoding:'utf8'});
test('installed Codex enforces native own-root access, sibling and symlink denial, read-only tasks and disabled external tools',{
 skip:!['darwin','linux'].includes(process.platform)||nativeCodex.status!==0?'Codex with a supported native sandbox is not installed':false,
 timeout:30000,
},async t=>{
 const dataHome=fixture(t),root=path.join(dataHome,'households/alpha'),other=path.join(dataHome,'households/beta'),provider=new CodexProvider(root,{dataHome});
 t.after(async()=>{const process=provider.process;provider.close();if(process&&process.exitCode==null)await once(process,'exit')});
 // Exercise native app-server permissions without an account query or a model turn.
 const rpc=provider.request.bind(provider);provider.request=(method,params)=>method==='account/read'?Promise.resolve({account:{type:'chatgpt'}}):method==='model/list'?Promise.resolve({data:[{model:'gpt-6.1-sol'}]}):rpc(method,params);
 const id=await provider.thread(null,[],'Sandbox verification only.','gpt-6.1-sol',true);assert.equal(provider.verifiedModels.get(id),'gpt-6.1-sol');assert.equal(provider.threadPermissions.get(id),'meal_household_write');
 const inventory=await provider.request('mcpServerStatus/list',{threadId:id,detail:'full'});assert.ok(inventory.data.every(server=>Object.keys(server.tools||{}).length===0));
 const execute=(command,permissionProfile='meal_household_write')=>provider.request('command/exec',{command,cwd:root,permissionProfile,timeoutMs:5000});
 const own=await execute(['/bin/cat',path.join(root,'household.json')]);assert.equal(own.exitCode,0);assert.equal(JSON.parse(own.stdout).id,'alpha');
 const code=await execute(['/bin/cat',path.resolve(import.meta.dirname,'../scripts/build.py')]);assert.equal(code.exitCode,0);assert.ok(code.stdout.includes('MEAL_DATA_DIR'));
 fs.symlinkSync(other,path.join(root,'escape'));
 for(const file of [path.join(other,'household.json'),fs.realpathSync(path.join(other,'household.json')),path.join(root,'escape/household.json')]){const denied=await execute(['/bin/cat',file]);assert.notEqual(denied.exitCode,0);assert.equal(denied.stdout,'')}
 // A new sibling is blocked even though it did not exist when the provider connected.
 const future=path.join(dataHome,'households/future');fs.mkdirSync(future);fs.writeFileSync(path.join(future,'secret.txt'),'future household');assert.notEqual((await execute(['/bin/cat',path.join(future,'secret.txt')])).exitCode,0);
 const readonly=await provider.thread(null,[],'Read-only verification.','gpt-6.1-sol',false,'read-only');assert.equal(provider.threadPermissions.get(readonly),'meal_household_read');
 assert.notEqual((await execute(['/bin/sh','-c','printf unexpected > "$1"','probe',path.join(root,'cannot-write.txt')],'meal_household_read')).exitCode,0);assert.equal(fs.existsSync(path.join(root,'cannot-write.txt')),false);
 const one=createCompanion({dataHome: (()=>{const single=path.join(dataHome,'single-home');fs.mkdirSync(path.join(single,'households'),{recursive:true});fs.cpSync(root,path.join(single,'households/alpha'),{recursive:true,dereference:false});fs.rmSync(path.join(single,'households/alpha/.runtime'),{recursive:true,force:true});return single})()});
 try{assert.equal(one.provider.isolateExternal,false);assert.equal(one.provider.externalShoppingUnavailable,false)}finally{one.close()}
});

test('captures, actor identities, uploads, media and pending provider questions are isolated',async t=>{
 const {app,base,call,a,b}=await running(t),alpha=app.gardens.get('alpha'),beta=app.gardens.get('beta'),bytes=Buffer.from([255,216,255,224,1,255,217]);
 const uploaded=await call('/api/upload',a,{data:bytes.toString('base64')});assert.equal(uploaded.status,201);
 const denied=await call('/api/jobs',b,{requestKey:'foreign-photo',text:'Inspect photo',attachmentIds:[uploaded.body.id]});assert.equal(denied.status,400);assert.match(denied.body.error,/Unknown attachment/);assert.equal(beta.store.jobs().length,0);
 const captureId=crypto.randomUUID(),sha=hash(bytes),saved=await call('/api/captures',a,{id:captureId,kind:'meal',note:'alpha dinner',phoneTime:'2026-10-05T12:00:00Z',imageData:bytes.toString('base64'),imageSha256:sha,width:10,height:10});assert.equal(saved.status,201);await tick();
 assert.equal(alpha.store.events(`capture:${captureId}`)[0].actor,'alpha-owner:android');assert.equal((await call('/api/snapshot',b)).body.captures.length,0);
 assert.equal((await call('/api/captures/detail',b,{id:captureId,detailId:crypto.randomUUID(),text:'change alpha',phoneTime:'2026-10-05T12:01:00Z'})).status,400);
 assert.equal((await call('/api/captures/interpret',b,{id:captureId})).status,400);
 const mediaA=await fetch(`${base}/api/media/${sha}`,{headers:{Authorization:`Bearer ${a}`}});assert.equal(mediaA.status,200);assert.deepEqual(Buffer.from(await mediaA.arrayBuffer()),bytes);
 assert.equal((await call(`/api/media/${sha}`,b)).status,404);assert.equal((await call(`/api/media/${sha}`)).status,401);
 assert.equal(fs.existsSync(path.join(beta.runtime,'media',sha+'.jpg')),false);
 alpha.jobs.requests.set('alpha-question',{id:'alpha-question',jobId:alpha.store.jobs()[0].id});assert.equal((await call('/api/answer',b,{id:'alpha-question'})).status,400);assert.equal(alpha.jobs.requests.has('alpha-question'),true);
 const maliciousSha='0'.repeat(64);alpha.store.run('INSERT INTO media VALUES(?,?,?,?,?,?,?)',maliciousSha,'../household.json','image/jpeg',1,1,1,'now');assert.equal((await call(`/api/media/${maliciousSha}`,a)).status,404);
});

test('multi-household APK and private lab use data-home paths; public lab uses the code folder',async t=>{
 const {dataHome,base}=await running(t);fs.mkdirSync(path.join(dataHome,'builds'));fs.writeFileSync(path.join(dataHome,'builds/meal-garden.apk'),'shared-build');
 fs.mkdirSync(path.join(dataHome,'workspace/design-lab'),{recursive:true});fs.writeFileSync(path.join(dataHome,'workspace/design-lab/index.html'),'private-test-lab');
 assert.equal(await (await fetch(base+'/download/meal-garden.apk')).text(),'shared-build');assert.equal(await (await fetch(base+'/lab/private/')).text(),'private-test-lab');
 const publicLab=await fetch(base+'/lab/');assert.equal(publicLab.status,200);assert.notEqual(await publicLab.text(),'private-test-lab');
 fs.symlinkSync(path.join(dataHome,'households/alpha/household.json'),path.join(dataHome,'workspace/design-lab/escape.json'));assert.equal((await fetch(base+'/lab/private/escape.json')).status,404);
});

test('add-household creates empty personal records and refuses existing or escaping ids',t=>{
 const dataHome=fs.mkdtempSync(path.join(os.tmpdir(),'garden-add-')),sampleRoot=fs.mkdtempSync(path.join(os.tmpdir(),'garden-starter-'));t.after(()=>{fs.rmSync(dataHome,{recursive:true,force:true});fs.rmSync(sampleRoot,{recursive:true,force:true})});
 atomic(path.join(sampleRoot,'profile/owner.json'),{constraints:[]});atomic(path.join(sampleRoot,'profile/kitchen.json'),{appliances:[]});atomic(path.join(sampleRoot,'data/plans/sample.json'),{meals:[{recipe_id:'sample'}]});
 const root=addHousehold('second','Second kitchen','Second person',{dataHome,sampleRoot});assert.equal(loadHousehold(root).name,'Second kitchen');assert.equal(loadHousehold(root).shopping,undefined);assert.equal(JSON.parse(fs.readFileSync(path.join(root,'profile/owner.json'))).name,'Second person');assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root,'profile/owner.json'))).priorities,[]);assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root,'profile/kitchen.json'))).available,[]);assert.deepEqual(fs.readdirSync(path.join(root,'data/plans')),[]);assert.equal(JSON.parse(fs.readFileSync(path.join(root,'data/inventory/current.json'))).status,'not_inventoried');
 assert.throws(()=>addHousehold('second','Other','Other',{dataHome,sampleRoot}),/EEXIST/);assert.throws(()=>addHousehold('../escape','Other','Other',{dataHome,sampleRoot}),/Household id/);
});
