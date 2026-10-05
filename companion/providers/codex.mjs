import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {EventEmitter} from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
const observedModel=response=>response.model&&response.thread?.model&&response.model!==response.thread.model?null:response.model||response.thread?.model||null;
// The sole runtime-specific module. A queued local provider can implement this contract.
export class CodexProvider extends EventEmitter {
 constructor(root,{dataHome=null,codeRoot=null,isolateExternal=!!dataHome}={}){super();this.root=root;this.dataHome=dataHome;this.codeRoot=codeRoot;this.isolateExternal=isolateExternal;this.externalShoppingUnavailable=!!dataHome&&isolateExternal;this.threadPermissions=new Map();this.pending=new Map();this.seq=0;this.loaded=new Set();this.verifiedModels=new Map();this.threadModels=new Map();}
 async connect(){
  if(this.ready)return this.ready;
  this.ready=(async()=>{
   const args=['app-server','--stdio'];
   if(this.dataHome){
    const binary=process.env.MEAL_CODEX_BIN||'codex',binaryPath=path.isAbsolute(binary)?binary:(process.env.PATH||'').split(path.delimiter).map(folder=>path.join(folder,binary)).find(file=>fs.existsSync(file));
    const historyHome=process.env.CODEX_HOME||path.join(os.homedir(),'.codex');
    for(const [name,access] of [['meal_household_write','write'],['meal_household_read','read']]){
     const filesystem={'/':'read',[path.resolve(this.dataHome)]:'deny',[path.resolve(this.root)]:access};
     if(this.isolateExternal){filesystem[path.resolve(historyHome)]='deny';if(binaryPath){filesystem[path.resolve(binaryPath)]='read';filesystem[fs.realpathSync(binaryPath)]='read';filesystem[path.join(path.resolve(historyHome),'packages')]='read'}}
     args.push('-c',`permissions.${name}={filesystem={${Object.entries(filesystem).map(([file,mode])=>`${JSON.stringify(file)}=${JSON.stringify(mode)}`).join(',')}},network={enabled=false}}`);
    }
    args.push('-c','default_permissions="meal_household_write"','-c','approval_policy="never"');
    if(this.isolateExternal)args.push('-c','features.plugins=false','-c','features.apps=false','-c','features.browser_use=false','-c','features.computer_use=false','-c','features.multi_agent=false','-c','allow_login_shell=false');
   }
   const child=spawn(process.env.MEAL_CODEX_BIN||'codex',args,{cwd:this.root,env:{...process.env,OPENAI_API_KEY:'',CODEX_API_KEY:''},stdio:['pipe','pipe','pipe']});this.process=child;
   child.stderr.on('data',()=>{}); // Never forward unfiltered runtime logs to the phone.
   child.on('error',e=>{if(this.process===child)this.fail(e)});child.on('exit',()=>{if(this.process===child)this.fail(new Error('Codex app-server disconnected. Restart the companion, then retry.'))});
   createInterface({input:child.stdout}).on('line',l=>{if(this.process!==child)return;try {const m=JSON.parse(l);if(m.method&&m.id!=null)this.emit('request',m);else if(m.method)this.emit('event',m);else {const p=this.pending.get(m.id);if(p){clearTimeout(p.timer);this.pending.delete(m.id);m.error?p.reject(new Error(m.error.message)):p.resolve(m.result);}}}catch(e){this.emit('protocolError',e.message)}});
   await this.request('initialize',{clientInfo:{name:'meal_garden',title:'Meal Garden',version:'0.1.1'},capabilities:{experimentalApi:true}});
   this.send({method:'initialized'});
   if(this.dataHome){
    const configuration=(await this.request('config/read',{cwd:this.root,includeLayers:false})).config;
    this.isolationConfig=this.isolateExternal?Object.fromEntries(Object.keys(configuration.mcp_servers||{}).map(name=>[`mcp_servers.${name}.enabled`,false])):{};
    const profiles=await this.request('permissionProfile/list',{cwd:this.root});
    if(!['meal_household_write','meal_household_read'].every(name=>profiles.data?.some(p=>p.id===name&&p.allowed)))throw new Error('Household filesystem isolation is unavailable in this Codex runtime.');
   }
   const account=await this.request('account/read',{});if(account.account?.type!=='chatgpt')throw new Error('Sign in to Codex with your ChatGPT subscription on the laptop. API-key accounts are not used.');
   this.models=(await this.request('model/list',{includeHidden:true})).data;
   return this;
  })();
  try{return await this.ready}catch(e){this.process?.kill();this.ready=null;throw e}
 }
 fail(e){this.ready=null;this.loaded.clear();this.verifiedModels.clear();this.threadModels.clear();this.threadPermissions.clear();for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(e)}this.pending.clear();this.emit('disconnect',e);}
 restartForModelMismatch(){const old=this.process;this.process=null;this.ready=null;this.models=null;this.loaded.clear();this.verifiedModels.clear();this.threadModels.clear();this.threadPermissions.clear();old?.kill();}
 send(m){if(!this.process?.stdin.writable)throw new Error('Codex is not connected');this.process.stdin.write(JSON.stringify(m)+'\n')}
 request(method,params={}){return new Promise((resolve,reject)=>{const id=++this.seq;const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error(`Codex timed out: ${method}`))},90000);this.pending.set(id,{resolve,reject,timer});try{this.send({id,method,params})}catch(e){clearTimeout(timer);this.pending.delete(id);reject(e)}})}
 respond(id,result){this.send({id,result})}
 reject(id,message){this.send({id,error:{code:-32601,message}})}
 async thread(providerId,tools,instructions,model='gpt-6.1-sol',strictModel=false,sandbox='danger-full-access'){
  for(let attempt=0;attempt<(strictModel?2:1);attempt++){
   await this.connect();
   if(!this.models?.some(m=>m.model===model))throw new Error(`${model} is unavailable in this Codex account; no model substitution was made.`);
   const permissions=sandbox==='read-only'?'meal_household_read':'meal_household_write';
   const params={cwd:this.root,approvalPolicy:this.dataHome?'never':'on-request',...(this.dataHome?{permissions,...(this.isolateExternal?{ephemeral:true}:{})}:{sandbox}),developerInstructions:instructions,model,config:{model_reasoning_effort:'medium',...(this.dataHome?this.isolationConfig:{})},dynamicTools:tools};
   if(this.dataHome&&providerId){try{const previous=await this.request('thread/read',{threadId:providerId,includeTurns:false});if(!previous.thread?.cwd||fs.realpathSync(previous.thread.cwd)!==fs.realpathSync(this.root))providerId=null}catch{providerId=null}}
   if(providerId&&this.loaded.has(providerId)&&!strictModel&&(!this.dataHome||this.threadPermissions.get(providerId)===permissions))return providerId;
   if(this.dataHome&&providerId)this.threadPermissions.delete(providerId);
   const r=await this.request(providerId?'thread/resume':'thread/start',providerId?{...params,threadId:providerId}:params);
   if(this.dataHome){
    this.threadPermissions.delete(r.thread.id);
    if(r.activePermissionProfile?.id!==permissions||r.approvalPolicy!=='never'||!r.cwd||fs.realpathSync(r.cwd)!==fs.realpathSync(this.root))throw new Error('Codex did not confirm household filesystem isolation; the task was stopped.');
    const servers=this.isolateExternal?await this.request('mcpServerStatus/list',{threadId:r.thread.id,detail:'full'}):null;
    if(servers?.data?.some(server=>Object.keys(server.tools||{}).length))throw new Error('Household task stopped: an external tool could bypass filesystem isolation.');
   }
   const check=await this.request('thread/read',{threadId:r.thread.id,includeTurns:false});
   if(strictModel){
    if(observedModel(r)!==model||observedModel(check)!==model){this.restartForModelMismatch();if(attempt===0)continue;throw new Error(`Shopping stopped: Codex did not confirm ${model} for its thread.`)}
    this.verifiedModels.set(r.thread.id,model);
   }
   if(this.dataHome)this.threadPermissions.set(r.thread.id,permissions);
   this.threadModels.set(r.thread.id,observedModel(check)||observedModel(r));
   this.loaded.add(r.thread.id);return r.thread.id;
  }
 }
 async start(threadId,text,model,attachments=[],strictModel=false){
  if(!this.models?.some(m=>m.model===model))throw new Error(`${model} is unavailable in this Codex account; no model substitution was made.`);
  if(strictModel&&this.verifiedModels.get(threadId)!==model)throw new Error(`Shopping stopped: ${model} was not verified before the turn.`);
  if(this.dataHome&&!this.threadPermissions.has(threadId))throw new Error('Household task stopped: filesystem isolation was not verified.');
  return this.request('turn/start',{threadId,model,effort:'medium',...(this.dataHome?{permissions:this.threadPermissions.get(threadId),approvalPolicy:'never'}:{}),input:[{type:'text',text},...attachments.map(path=>({type:'localImage',path}))]});
 }
 async cancel(threadId,turnId){return this.request('turn/interrupt',{threadId,turnId})}
 close(){this.process?.kill()}
}
