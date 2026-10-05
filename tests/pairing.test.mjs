import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import {EventEmitter} from 'node:events';
import {connectionAddresses,isTailnetAddress,pairingPayload,setupPage} from '../companion/pairing.mjs';
import {createCompanion} from '../companion/server.mjs';
test('setup prefers tailnet and permanent APK links; LAN stays time-limited',async()=>{
 const interfaces={lo:[{family:'IPv4',internal:true,address:'127.0.0.1'}],en:[{family:'IPv4',internal:false,address:'192.168.1.2'}],utun:[{family:'IPv4',internal:false,address:'100.95.10.2'}]};
 const addresses=connectionAddresses(interfaces,4783);
 assert.equal(addresses[0].endpoint,'http://100.95.10.2:4783');
 const html=await setupPage(addresses,'12345678',Date.now()+600000);
 assert.match(html,/<svg /); assert.match(html,/href="http:\/\/100\.95\.10\.2:4783\/download\/meal-garden.apk"/);
 assert.match(html,/192\.168\.1\.2:4783\/download\/meal-garden.apk\?code=12345678/);
 assert.equal((html.match(/<svg /g)||[]).length,2);
 assert.deepEqual(JSON.parse(pairingPayload(addresses[0].endpoint,'12345678')),{app:'meal-garden',version:1,endpoint:addresses[0].endpoint,code:'12345678'});
 for(const ip of ['100.64.0.1','100.127.255.254','::ffff:100.64.1.1'])assert.equal(isTailnetAddress(ip),true);
 for(const ip of ['100.63.255.255','100.128.0.0','100.100.1.999','192.168.1.1','::1','evil',''])assert.equal(isTailnetAddress(ip),false);
});
class NoProvider extends EventEmitter {close(){}}
test('QR setup code pairs once; browser origin/host restrictions protect pairing',async()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'garden-pair-'));
 const app=createCompanion({root,provider:new NoProvider()});
 await new Promise(resolve=>app.server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${app.server.address().port}`;
 try {
  const setup=await fetch(base+'/setup'); const html=await setup.text();
  assert.equal(setup.status,200); assert.equal(setup.headers.get('cache-control'),'no-store');
  const code=html.match(/<strong>(\d{8})<\/strong>/)[1];
  const badHost=await new Promise((resolve,reject)=>{http.get(base+'/setup',{headers:{Host:'evil.example'}},res=>{res.resume();resolve(res.statusCode)}).on('error',reject)}); assert.equal(badHost,403);
  const pair=()=>fetch(base+'/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code,name:'QR test fixture'})});
  const first=await pair();assert.equal(first.status,200);const token=(await first.json()).token;
  assert.equal((await pair()).status,401);
  assert.equal((await fetch(base+'/api/activity',{headers:{Authorization:`Bearer ${token}`}})).status,200);
  const renewed=await (await fetch(base+'/setup')).text();assert.match(renewed,/<strong>\d{8}<\/strong>/);
  fs.mkdirSync(path.join(root,'exports'));fs.writeFileSync(path.join(root,'exports/meal-garden.apk'),'test-apk');
  const apk=await fetch(base+'/download/meal-garden.apk');assert.equal(apk.status,200);assert.equal(await apk.text(),'test-apk');assert.equal(apk.headers.get('content-length'),'8');
 } finally {app.close();fs.rmSync(root,{recursive:true,force:true})}
});
