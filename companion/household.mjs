import fs from 'node:fs';
import path from 'node:path';

export const householdIdPattern=/^[a-z0-9][a-z0-9_-]{0,63}$/;
const text=(value,label,max=200)=>{if(typeof value!=='string'||!value.trim()||value.length>max)throw new Error(`Invalid household ${label}`);return value.trim()};
const identifier=(value,label)=>{const id=text(value,label,64);if(!householdIdPattern.test(id))throw new Error(`Invalid household ${label}`);return id};
const object=(value,label)=>{if(!value||typeof value!=='object'||Array.isArray(value))throw new Error(`Invalid household ${label}`);return value};

// Bare temporary roots deliberately carry no integration or personal settings.
export function loadHousehold(root){
 const file=root?path.join(root,'household.json'):null;
 if(!file||!fs.existsSync(file))return {schema_version:1,id:'home',name:'Your kitchen',person:{id:'owner',name:'You',pronouns:'they/them'},timezone:'UTC'};
 if(!fs.realpathSync(file).startsWith(fs.realpathSync(root)+path.sep))throw new Error('household.json must stay inside the household folder');
 const value=object(JSON.parse(fs.readFileSync(file,'utf8')),'configuration');
 if(value.schema_version!==1)throw new Error('Unsupported household schema_version');
 const person=object(value.person,'person');
 const household={schema_version:1,id:identifier(value.id,'id'),name:text(value.name,'name'),person:{id:identifier(person.id,'person.id'),name:text(person.name,'person.name'),pronouns:person.pronouns==null?'they/them':text(person.pronouns,'person.pronouns',100)},timezone:value.timezone==null?'UTC':text(value.timezone,'timezone',100)};
 try{new Intl.DateTimeFormat('en',{timeZone:household.timezone}).format()}catch{throw new Error('Invalid household timezone')}
 if(value.shopping!=null){
  const shopping=object(value.shopping,'shopping');
  const service=text(shopping.list_service,'shopping.list_service',100);
  if(service!=='samsung_food')throw new Error('Unsupported household shopping list service');
  household.shopping={list_service:service,list_name:text(shopping.list_name,'shopping.list_name')};
  if(shopping.store!=null){const store=object(shopping.store,'shopping.store');household.shopping.store={chain:text(store.chain,'shopping.store.chain'),...(store.label==null?{}:{label:text(store.label,'shopping.store.label')}),...(store.address==null?{}:{address:text(store.address,'shopping.store.address',500)})}}
 }
 if(value.assistant_notes!=null){
  const relative=text(value.assistant_notes,'assistant_notes',500),resolved=path.resolve(root,relative),base=path.resolve(root);
  if(path.isAbsolute(relative)||resolved===base||!resolved.startsWith(base+path.sep))throw new Error('assistant_notes must stay inside the household folder');
  if(fs.existsSync(resolved)&&!fs.realpathSync(resolved).startsWith(fs.realpathSync(root)+path.sep))throw new Error('assistant_notes must stay inside the household folder');
  household.assistant_notes=relative;
 }
 return household;
}
export function profilePath(root,household=loadHousehold(root)){return path.join(root,'profile',`${household.person.id}.json`)}
export function androidActor(rootOrHousehold){const household=typeof rootOrHousehold==='string'?loadHousehold(rootOrHousehold):rootOrHousehold;return `${household.person.id}:android`}
