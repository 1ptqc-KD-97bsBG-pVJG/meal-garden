#!/usr/bin/env node
// Scans every git-tracked file for things that must not be published: private network addresses,
// home-folder paths, email addresses, credential-looking strings, and owner-specific terms.
// Owner terms are not in this repository: pass a text file (one term per line, # followed by whitespace for comments)
// with --terms <file> or MEAL_PRIVATE_TERMS. Exit code 1 when anything is found.
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const args=process.argv.slice(2),flag=name=>{const i=args.indexOf(name);return i>=0?args[i+1]:null};
const root=path.resolve(flag('--root')||'.'),termsFile=flag('--terms')||process.env.MEAL_PRIVATE_TERMS||null;
const escape=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
const rules=[
 ['tailnet address',/\b100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}\b/g],
 ['home folder path',/\/(Users|home)\/[A-Za-z0-9._-]+/g],
 ['email address',/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g],
 ['tailnet name',/\b[a-z0-9-]+\.ts\.net\b/g],
 ['credential',/\b(sk-[A-Za-z0-9]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,})/g],
 ['shared-list link',/samsungfood\.com\/shopping-list\/[A-Za-z0-9]+/g],
];
// Values that look like the patterns above but are deliberately public.
// Culinary words shared with an owner's nickname are harmless exact matches.
// The longer private list name still matches its own rule and is never allowed.
const allowed=new Set(['bean','Bean','BEAN',
// Synthetic boundary addresses used by pairing tests; none identifies a device.
'100.95.10.2','100.127.255.254','100.64.1.1','100.100.1.999','noreply@anthropic.com','100.64.0.1','100.100.100.100','100.101.102.103','/Users/you','/home/you','you@example.com']);
const terms=termsFile?fs.readFileSync(termsFile,'utf8').split('\n').map(l=>l.trim()).filter(l=>l&&!/^#(?:\s|$)/.test(l)):[];
const ownerTerm=terms.length?new RegExp(`(?<![A-Za-z])(${terms.map(escape).join('|')})(?![a-z])`,'gi'):null;
if(ownerTerm)rules.push(['owner term',ownerTerm]);
const files=execFileSync('git',['-C',root,'ls-files','-z'],{maxBuffer:64*1024*1024}).toString().split('\0').filter(Boolean);
const findings=[],binaries=[];
for(const file of files){
 const full=path.join(root,file);if(!fs.existsSync(full))continue;
 if(ownerTerm){ownerTerm.lastIndex=0;let m;while((m=ownerTerm.exec(file))){if(!allowed.has(m[0]))findings.push({file,line:0,rule:'owner term in file name',match:m[0]})}}
 const bytes=fs.readFileSync(full);
 if(bytes.subarray(0,8000).includes(0)){binaries.push(file);continue}
 const lines=bytes.toString('utf8').split('\n');
 lines.forEach((text,index)=>{for(const [name,pattern] of rules){pattern.lastIndex=0;let m;while((m=pattern.exec(text))){if(!allowed.has(m[0]))findings.push({file,line:index+1,rule:name,match:m[0].slice(0,80)})}}});
}
const byRule={};for(const f of findings)byRule[f.rule]=(byRule[f.rule]||0)+1;
if(args.includes('--json'))fs.writeSync(1,JSON.stringify({files:files.length,findings,binaries}));
else{
 for(const f of findings.slice(0,Number(flag('--max')||200)))console.log(`${f.file}:${f.line}  ${f.rule}: ${f.match}`);
 console.log(`\n${files.length} tracked files, ${findings.length} findings in ${new Set(findings.map(f=>f.file)).size} files`,byRule);
 console.log(`${binaries.length} binary files need a human look (images can show a home, receipts or names):`);for(const b of binaries)console.log('  '+b);
 if(!terms.length)console.log('\nNOTE: no owner-terms file given; only generic patterns were checked.');
}
process.exitCode=findings.length?1:0;
