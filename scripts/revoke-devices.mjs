#!/usr/bin/env node
import {DatabaseSync} from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
// Require an explicit household so a command cannot revoke another garden by accident.
const root=process.argv[2]||process.env.MEAL_DATA_DIR;
if(!root)throw new Error('Select one household: MEAL_DATA_DIR=/path/to/household node scripts/revoke-devices.mjs (stop its companion first).');
const file=path.resolve(root,'.runtime/garden.sqlite');
if(!fs.existsSync(file))throw new Error('Household database does not exist');
const db=new DatabaseSync(file);
const result=db.prepare('DELETE FROM devices').run();db.close();console.log(`Revoked ${result.changes} paired devices. Open laptop setup to pair again.`);
