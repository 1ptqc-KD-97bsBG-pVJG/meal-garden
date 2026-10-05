import {DatabaseSync} from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import {loadHousehold} from './household.mjs';
import {householdPath,id,now} from './domain.mjs';
export class Store {
 constructor(file,{recoverJobs=true,root=path.basename(path.dirname(path.resolve(file)))==='.runtime'?path.dirname(path.dirname(path.resolve(file))):path.dirname(path.resolve(file)),household=loadHousehold(root)}={}){this.root=root;this.household=household;file=householdPath(root,path.resolve(file));for(const suffix of ['-wal','-shm','-journal'])householdPath(root,file+suffix);fs.mkdirSync(path.dirname(file),{recursive:true,mode:0o700});this.db=new DatabaseSync(file);for(const p of [file,file+'-wal',file+'-shm'])if(fs.existsSync(p))fs.chmodSync(p,0o600);this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
 CREATE TABLE IF NOT EXISTS conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,provider_id TEXT,created TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,created TEXT NOT NULL,panels TEXT NOT NULL DEFAULT '[]');
 CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,request_key TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,input TEXT NOT NULL,model TEXT,created TEXT NOT NULL,updated TEXT NOT NULL,error TEXT,turn_id TEXT,progress TEXT);
 CREATE TABLE IF NOT EXISTS devices(hash TEXT PRIMARY KEY,name TEXT,created TEXT);
 CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT);
 CREATE TABLE IF NOT EXISTS job_events(id INTEGER PRIMARY KEY AUTOINCREMENT,job_id TEXT NOT NULL,created TEXT NOT NULL,type TEXT NOT NULL,data TEXT NOT NULL DEFAULT '{}');
 CREATE INDEX IF NOT EXISTS job_events_job_time ON job_events(job_id,id);
 CREATE TABLE IF NOT EXISTS attachments(id TEXT PRIMARY KEY,job_id TEXT,created TEXT NOT NULL,bytes INTEGER NOT NULL,sha256 TEXT NOT NULL,mime TEXT NOT NULL,status TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,type TEXT NOT NULL,subject TEXT,occurred_at TEXT NOT NULL,recorded_at TEXT NOT NULL,actor TEXT NOT NULL,payload TEXT NOT NULL DEFAULT '{}',evidence TEXT NOT NULL DEFAULT '[]');
 CREATE INDEX IF NOT EXISTS events_subject_time ON events(subject,recorded_at);
 CREATE INDEX IF NOT EXISTS events_type_time ON events(type,occurred_at);
 CREATE TABLE IF NOT EXISTS ui_events(id TEXT PRIMARY KEY,at TEXT NOT NULL,name TEXT NOT NULL,props TEXT NOT NULL DEFAULT '{}',device TEXT,app_version TEXT,received TEXT NOT NULL);
 CREATE INDEX IF NOT EXISTS ui_events_time ON ui_events(at);
 CREATE TABLE IF NOT EXISTS media(sha256 TEXT PRIMARY KEY,path TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,width INTEGER,height INTEGER,created TEXT NOT NULL);

 CREATE TABLE IF NOT EXISTS households(id TEXT PRIMARY KEY,
  name TEXT);
 CREATE TABLE IF NOT EXISTS persons(id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL REFERENCES households(id),
  name TEXT);
 CREATE TABLE IF NOT EXISTS partner_links(person_id TEXT NOT NULL REFERENCES persons(id),
  partner_id TEXT NOT NULL REFERENCES persons(id),
  access TEXT,
  notify_on_intake INTEGER DEFAULT 0,
  PRIMARY KEY(person_id,partner_id));
 CREATE TABLE IF NOT EXISTS products(id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  brand TEXT,
  kind TEXT NOT NULL CHECK(kind IN ('packaged','generic','homemade')),
  size_amount REAL,
  base_unit TEXT NOT NULL CHECK(base_unit IN ('g','ml','count')),
  barcode TEXT,
  storage TEXT CHECK(storage IN ('fridge','freezer','pantry','counter','unknown')),
  shelf_life_days INTEGER,
  match_words TEXT,
  nutrition_basis TEXT CHECK(nutrition_basis IN ('per_100g','per_100ml','per_unit')),
  grams_per_unit REAL,
  kcal REAL,
  protein_g REAL,
  carbs_g REAL,
  fat_g REAL,
  fiber_g REAL,
  sodium_mg REAL,
  sat_fat_g REAL,
  added_sugar_g REAL,
  serving_g REAL,
  nutrition_source TEXT NOT NULL DEFAULT 'none' CHECK(nutrition_source IN ('label_photo','usda_fdc','open_food_facts','web','computed','estimate','none')),
  nutrition_url TEXT,
  batch_id TEXT REFERENCES batches(id),
  created_at TEXT);
 CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL REFERENCES households(id),
  store TEXT,
  purchased_on TEXT,
  total REAL,
  source TEXT);
 CREATE TABLE IF NOT EXISTS purchases(id TEXT PRIMARY KEY,
  receipt_id TEXT NOT NULL REFERENCES receipts(id),
  product_id TEXT NOT NULL REFERENCES products(id),
  raw_line TEXT,
  packages REAL,
  price REAL);
 CREATE TABLE IF NOT EXISTS pantry_items(id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL REFERENCES households(id),
  product_id TEXT NOT NULL REFERENCES products(id),
  purchase_id TEXT REFERENCES purchases(id),
  split_from_id TEXT REFERENCES pantry_items(id),
  location TEXT NOT NULL DEFAULT 'unknown' CHECK(location IN ('fridge','freezer','pantry','counter','unknown')),
  condition TEXT CHECK(condition IN ('fine','use_soon')),
  opened_on TEXT,
  last_confirmed_at TEXT,
  created_at TEXT);
 CREATE TABLE IF NOT EXISTS pantry_movements(id TEXT PRIMARY KEY,
  pantry_item_id TEXT NOT NULL REFERENCES pantry_items(id),
  kind TEXT NOT NULL DEFAULT 'delta' CHECK(kind IN ('delta','count')),
  amount REAL,
  reason TEXT NOT NULL CHECK(reason IN ('purchase','made','batch_use','intake','transfer','tossed','assumed_use','stated','reversal')),
  confidence TEXT NOT NULL CHECK(confidence IN ('known','assumed','unknown')),
  batch_ingredient_id TEXT REFERENCES batch_ingredients(id),
  intake_component_id TEXT REFERENCES intake_components(id),
  assumption_id TEXT REFERENCES assumptions(id),
  transfer_id TEXT,
  reverses_movement_id TEXT REFERENCES pantry_movements(id),
  event_id TEXT NOT NULL REFERENCES events(id),
  occurred_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS batches(id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL REFERENCES households(id),
  recipe_id TEXT,
  recipe_revision INTEGER,
  title TEXT NOT NULL,
  made_at TEXT NOT NULL,
  recorded_from TEXT NOT NULL CHECK(recorded_from IN ('cook_mode','report','chat','food_log')),
  yield_g REAL,
  yield_basis TEXT NOT NULL DEFAULT 'unknown' CHECK(yield_basis IN ('measured','estimated','unknown')),
  portions_made REAL,
  nutrition_total TEXT,
  confidence TEXT NOT NULL CHECK(confidence IN ('known','assumed','unknown')));
 CREATE TABLE IF NOT EXISTS batch_ingredients(id TEXT PRIMARY KEY,
  batch_id TEXT NOT NULL REFERENCES batches(id),
  pantry_item_id TEXT REFERENCES pantry_items(id),
  product_id TEXT REFERENCES products(id),
  name TEXT NOT NULL,
  amount REAL,
  grams REAL,
  amount_text TEXT,
  confidence TEXT NOT NULL CHECK(confidence IN ('known','assumed','unknown')));
 CREATE TABLE IF NOT EXISTS intake(id TEXT PRIMARY KEY,
  capture_id TEXT NOT NULL,
  person_id TEXT NOT NULL REFERENCES persons(id),
  eaten_at TEXT NOT NULL,
  title TEXT,
  category TEXT,
  method TEXT NOT NULL CHECK(method IN ('computed','label','estimated')),
  nutrition TEXT,
  confidence TEXT NOT NULL CHECK(confidence IN ('known','assumed','unknown')),
  superseded_by_id TEXT REFERENCES intake(id));
 CREATE TABLE IF NOT EXISTS intake_components(id TEXT PRIMARY KEY,
  intake_id TEXT NOT NULL REFERENCES intake(id),
  pantry_item_id TEXT REFERENCES pantry_items(id),
  product_id TEXT REFERENCES products(id),
  name TEXT NOT NULL,
  amount REAL,
  grams REAL,
  confidence TEXT NOT NULL CHECK(confidence IN ('known','assumed','unknown')));
 CREATE TABLE IF NOT EXISTS preferences(id TEXT PRIMARY KEY,
  person_id TEXT NOT NULL REFERENCES persons(id),
  kind TEXT NOT NULL CHECK(kind IN ('constraint','taste','portion','process','routine','goal')),
  subject TEXT NOT NULL,
  product_id TEXT REFERENCES products(id),
  recipe_id TEXT,
  stance TEXT CHECK(stance IN ('never','avoid','neutral','like','love')),
  value TEXT,
  is_hard INTEGER NOT NULL DEFAULT 0,
  statement TEXT NOT NULL,
  source TEXT NOT NULL CHECK(source IN ('stated','inferred','imported')),
  confidence TEXT NOT NULL CHECK(confidence IN ('known','assumed','unknown')),
  evidence TEXT,
  superseded_by_id TEXT REFERENCES preferences(id),
  created_at TEXT);
 CREATE TABLE IF NOT EXISTS reactions(id TEXT PRIMARY KEY,
  person_id TEXT NOT NULL REFERENCES persons(id),
  batch_id TEXT REFERENCES batches(id),
  recipe_id TEXT,
  intake_id TEXT REFERENCES intake(id),
  rating INTEGER,
  aspects TEXT,
  notes TEXT,
  event_id TEXT NOT NULL REFERENCES events(id),
  created_at TEXT);
 CREATE TABLE IF NOT EXISTS assumptions(id TEXT PRIMARY KEY,
  household_id TEXT NOT NULL REFERENCES households(id),
  statement TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','confirmed','corrected')),
  evidence TEXT,
  created_at TEXT,
  resolved_at TEXT);
 CREATE TABLE IF NOT EXISTS product_lookups(id TEXT PRIMARY KEY,
  product_id TEXT NOT NULL REFERENCES products(id),
  step TEXT,
  succeeded INTEGER,
  tokens INTEGER,
  seconds REAL,
  detail TEXT,
  created_at TEXT);
 CREATE TABLE IF NOT EXISTS idempotency_keys(key TEXT PRIMARY KEY,
  operation TEXT,
  result TEXT,
  created_at TEXT);
 CREATE INDEX IF NOT EXISTS pantry_movements_item_time ON pantry_movements(pantry_item_id,occurred_at);
 CREATE INDEX IF NOT EXISTS intake_capture ON intake(capture_id,superseded_by_id);
 CREATE INDEX IF NOT EXISTS preferences_current ON preferences(person_id,kind,subject,superseded_by_id);

 `);
  this.run('INSERT OR IGNORE INTO households(id,name) VALUES(?,?)',household.id,household.name);
  this.run('INSERT OR IGNORE INTO persons(id,household_id,name) VALUES(?,?,?)',household.person.id,household.id,household.person.name);
  const add=(table,column,definition)=>{if(!this.all(`PRAGMA table_info(${table})`).some(c=>c.name===column))this.db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`)};
  this.db.exec('BEGIN IMMEDIATE');try{
   add('conversations','source',"TEXT NOT NULL DEFAULT 'legacy_unknown'");
   add('messages','job_id','TEXT');add('messages','metadata',"TEXT NOT NULL DEFAULT '{}'");
   add('jobs','metadata',"TEXT NOT NULL DEFAULT '{}'");add('jobs','thread_id','TEXT');add('jobs','observed_model','TEXT');add('jobs','model_evidence','TEXT');add('jobs','prompt','TEXT');add('jobs','developer_instructions','TEXT');
   this.db.exec('COMMIT');
  }catch(e){this.db.exec('ROLLBACK');throw e}
  if(recoverJobs)this.db.prepare("UPDATE jobs SET status='interrupted',error='Companion restarted during this task. Review the last result before retrying.',updated=? WHERE status IN ('running','waiting')").run(now());
 }
 all(sql,...args){return this.db.prepare(sql).all(...args)}
 get(sql,...args){return this.db.prepare(sql).get(...args)}
 run(sql,...args){return this.db.prepare(sql).run(...args)}
 conversation(title='A fresh conversation',source='unknown'){const v={id:id(),title,created:now(),source};this.run('INSERT INTO conversations(id,title,created,source) VALUES(?,?,?,?)',v.id,v.title,v.created,v.source);return v;}
 message(conversation,role,text,panels=[],jobId=null,metadata={}){const v={id:id(),conversation_id:conversation,role,text,created:now(),panels:JSON.stringify(panels),job_id:jobId,metadata:JSON.stringify(metadata)};this.run('INSERT INTO messages(id,conversation_id,role,text,created,panels,job_id,metadata) VALUES(?,?,?,?,?,?,?,?)',v.id,conversation,role,text,v.created,v.panels,jobId,v.metadata);return v;}
 event(jobId,type,data={}){this.run('INSERT INTO job_events(job_id,created,type,data) VALUES(?,?,?,?)',jobId,now(),type,JSON.stringify(data))}
 // Append-only domain record. Corrections are new events, never edits.
 record(type,subject,payload={},{occurredAt=now(),actor='system',evidence=[]}={}){const v={id:id(),type,subject,occurred_at:occurredAt,recorded_at:now(),actor,payload,evidence};this.run('INSERT INTO events(id,type,subject,occurred_at,recorded_at,actor,payload,evidence) VALUES(?,?,?,?,?,?,?,?)',v.id,type,subject,occurredAt,v.recorded_at,actor,JSON.stringify(payload),JSON.stringify(evidence));return v}
 events(subject){return this.all('SELECT * FROM events WHERE subject=? ORDER BY recorded_at,rowid',subject).map(e=>({...e,payload:JSON.parse(e.payload),evidence:JSON.parse(e.evidence)}))}
 conversations(){return this.all("SELECT * FROM conversations WHERE source NOT IN ('food_log','reflection','product_lookup') ORDER BY created DESC")}
 messages(c){return this.all('SELECT * FROM messages WHERE conversation_id=? ORDER BY created,rowid',c).map(m=>({...m,panels:JSON.parse(m.panels),metadata:JSON.parse(m.metadata)}))}
 jobs(){return this.all('SELECT * FROM jobs ORDER BY created DESC LIMIT 60').map(j=>({...j,input:JSON.parse(j.input),metadata:JSON.parse(j.metadata)}))}
 close(){this.db.close()}
}
