import QRCode from 'qrcode';
import net from 'node:net';
export function isTailnetAddress(address='') {
 const ip=address.replace(/^::ffff:/,'');
 if(net.isIP(ip)!==4)return false;
 const [a,b]=ip.split('.').map(Number);
 return a===100&&b>=64&&b<=127;
}
export function connectionAddresses(interfaces,port){
 return [...new Set(Object.values(interfaces).flat().filter(a=>a&&a.family==='IPv4'&&!a.internal).map(a=>a.address))]
  .sort((a,b)=>Number(isTailnetAddress(b))-Number(isTailnetAddress(a)))
  .map(ip=>({endpoint:`http://${ip}:${port}`,tailnet:isTailnetAddress(ip)}));
}
export function pairingPayload(endpoint,code){return JSON.stringify({app:'meal-garden',version:1,endpoint,code})}
const escape=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function householdSection(addresses,{name,code,expires}){
 const cards=await Promise.all(addresses.map(async({endpoint,tailnet})=>{
 const svg=await QRCode.toString(pairingPayload(endpoint,code),{type:'svg',errorCorrectionLevel:'M',margin:4,color:{dark:'#203d30',light:'#ffffff'}});
 const download=`${endpoint}/download/meal-garden.apk${tailnet?'':`?code=${code}`}`;
 return `<section><div class="tag">${tailnet?'TAILSCALE · RECOMMENDED':'SAME WI-FI'}</div><div class="qr" role="img" aria-label="Scan to pair Meal Garden">${svg}</div><code>${escape(endpoint)}</code><p>Meal Garden → Connect laptop →<br><b>Scan laptop QR code</b></p><a class="button" href="${escape(download)}">Download Android APK ↓</a><p class="small">${tailnet?'Permanent download address on your tailnet.':'Wi-Fi download link expires with this pairing code.'}</p></section>`;
 }));
 return `<h2>${escape(name)}</h2><div class="grid">${cards.join('')||'<section>No network address found. Connect the laptop to Wi-Fi or Tailscale, then refresh.</section>'}</div><div class="manual"><p>Prefer to type? Use a laptop address above and this one-time code:</p><strong>${code}</strong><p class="small">Valid until ${escape(new Date(expires).toLocaleTimeString())} · one phone per code. Refresh this page for a new code after pairing or expiry.</p></div>`;
}
export async function setupPage(addresses,codeOrHouseholds,expires){
 const households=Array.isArray(codeOrHouseholds)?codeOrHouseholds:[{name:'Your kitchen',code:codeOrHouseholds,expires}];
 const sections=await Promise.all(households.map(household=>householdSection(addresses,household)));
 return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Meal Garden · Pair your phone</title><style>body{font:17px system-ui;background:#f7f5ed;color:#203d30;max-width:960px;margin:5vh auto;padding:24px}h1{font:clamp(38px,7vw,62px)/1.04 Georgia;margin:24px 0}p{line-height:1.55}.tag{font-size:12px;letter-spacing:2px;font-weight:650}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:20px;margin:30px 0}section{padding:26px;border:1px solid #d4dfd0;border-radius:26px;background:white;text-align:center}.qr{max-width:300px;margin:auto}.qr svg{width:100%;display:block}code{font-size:14px;overflow-wrap:anywhere}.button{display:block;background:#203d30;color:white;padding:15px;border-radius:16px;text-decoration:none}.small{font-size:13px;color:#5f7064}.manual{border-top:1px solid #d4dfd0;padding-top:20px}strong{font:32px monospace;letter-spacing:5px}a{color:inherit}</style><p class="tag">MEAL GARDEN / CONNECT</p><h1>Point your camera.<br>Come home to your kitchen.</h1><p>Open the scanner in Meal Garden and point it at the QR code below.<br>Your phone will connect automatically.</p>${sections.join('')}<p class="small">Keep the companion running. Pair only with your own phone: pairing gives it access to your meal workspace and Codex actions. Camera frames stay on your phone.</p><p class="small">Tailscale just connected? <a href="/setup">Refresh addresses</a>.</p></html>`;
}
