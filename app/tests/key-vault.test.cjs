'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { createKeyVault, createPowerShellRunner, createWindowsRunner } = require('../lib/key-vault.cjs');
const { EXPECTED_BRIDGE, STORE_MARKER, readStoreRuntimeMarker } = require('../lib/windows-helper.cjs');

function temporary(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'domtrack-vault-test-'));
  t.after(() => {
    const resolved = fs.realpathSync(directory);
    const parent = fs.realpathSync(os.tmpdir());
    assert.ok(resolved.startsWith(parent + path.sep) && path.basename(resolved).startsWith('domtrack-vault-test-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  });
  return { directory, file: path.join(directory, 'key-vault.json') };
}
function mockRunner() {
  const known = new Map(); const calls = [];
  const runner = async request => {
    calls.push(request.operation);
    if (request.operation === 'protect') { const cipher = crypto.randomBytes(64).toString('base64'); known.set(cipher, request.value); return cipher; }
    if (!known.has(request.value)) throw new Error('mock decrypt rejected');
    return known.get(request.value);
  };
  return { runner, calls };
}
function fakeSpawner(handler) {
  return (file, args, options) => {
    const child = new EventEmitter();
    child.stdin = new PassThrough(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
    child.kill = () => child.emit('close', 1);
    let input = '';
    child.stdin.on('data', chunk => { input += chunk.toString('utf8'); });
    child.stdin.on('finish', () => queueMicrotask(() => handler({ child, file, args, options, input })));
    return child;
  };
}

test('missing vault loads null without requesting decryption', async t => {
  const { file } = temporary(t); const mock = mockRunner();
  const vault = createKeyVault({ file, platform: 'win32', runner: mock.runner });
  assert.equal(await vault.load(), null); assert.equal(mock.calls.length, 0);
});
test('persists versioned ciphertext only and reloads the original key through the runner', async t => {
  const { file, directory } = temporary(t); const mock = mockRunner(); const key = 'synthetic-fixture-secret-42';
  const vault = createKeyVault({ file, platform: 'win32', runner: mock.runner });
  await vault.save(key);
  const disk = fs.readFileSync(file, 'utf8'); const value = JSON.parse(disk);
  assert.equal(value.version, 1); assert.equal(value.protection, 'windows-dpapi-current-user');
  assert.equal(disk.includes(key), false); assert.equal(Object.hasOwn(value, 'key'), false);
  assert.equal(await vault.load(), key);
  assert.deepEqual(fs.readdirSync(directory), ['key-vault.json']);
});
test('encryption failure preserves the previous ciphertext and exposes no secret details', async t => {
  const { file } = temporary(t); const mock = mockRunner();
  await createKeyVault({ file, platform: 'win32', runner: mock.runner }).save('synthetic-old');
  const before = fs.readFileSync(file, 'utf8'); const secret = 'synthetic-new-sensitive';
  const vault = createKeyVault({ file, platform: 'win32', runner: async () => { throw new Error(secret); } });
  await assert.rejects(vault.save(secret), error => error.code === 'VAULT_SAVE' && !error.message.includes(secret));
  assert.equal(fs.readFileSync(file, 'utf8'), before);
});
test('malformed, unsupported and oversized vault files fail closed before the runner', async t => {
  const { file } = temporary(t); let calls = 0;
  const vault = createKeyVault({ file, platform: 'win32', runner: async () => { calls++; return 'unused'; } });
  for (const content of ['not json', JSON.stringify({ version: 2 }), JSON.stringify({ format: 'domtrack-key-vault', version: 1, protection: 'windows-dpapi-current-user', ciphertext: 'not base64!' }), 'x'.repeat(64 * 1024 + 1)]) {
    fs.writeFileSync(file, content);
    await assert.rejects(vault.load(), { code: 'VAULT_READ' });
  }
  assert.equal(calls, 0);
});
test('ciphertext tampering is reported without plaintext fallback or deleting the vault', async t => {
  const { file } = temporary(t); const mock = mockRunner(); const vault = createKeyVault({ file, platform: 'win32', runner: mock.runner });
  await vault.save('synthetic-secret');
  const value = JSON.parse(fs.readFileSync(file, 'utf8')); value.ciphertext = crypto.randomBytes(64).toString('base64'); fs.writeFileSync(file, JSON.stringify(value));
  await assert.rejects(vault.load(), { code: 'VAULT_DECRYPT' }); assert.ok(fs.existsSync(file));
});
test('key validation rejects empty, oversized or multiline strings without writing files', async t => {
  const { file, directory } = temporary(t); const mock = mockRunner(); const vault = createKeyVault({ file, platform: 'win32', runner: mock.runner });
  for (const key of ['', 'x'.repeat(501), 'line\nline', 'line\rline', 'binary\0key']) await assert.rejects(vault.save(key), { code: 'VAULT_KEY_INVALID' });
  assert.deepEqual(fs.readdirSync(directory), []); assert.equal(mock.calls.length, 0);
});
test('Windows-only errors do not create a plaintext fallback', async t => {
  const { file, directory } = temporary(t); const vault = createKeyVault({ file, platform: 'linux' });
  await assert.rejects(vault.save('synthetic-key'), { code: 'VAULT_WINDOWS_ONLY' });
  await assert.rejects(vault.load(), { code: 'VAULT_WINDOWS_ONLY' });
  await assert.rejects(vault.remove(), { code: 'VAULT_WINDOWS_ONLY' });
  assert.deepEqual(fs.readdirSync(directory), []);
});
test('removal deletes ciphertext and is idempotent', async t => {
  const { file } = temporary(t); const mock = mockRunner(); const vault = createKeyVault({ file, platform: 'win32', runner: mock.runner });
  await vault.save('synthetic-key'); await vault.remove(); await vault.remove(); assert.equal(await vault.load(), null);
});
test('runner places key only in stdin, uses fixed arguments and excludes key environment variables', async () => {
  const key = 'synthetic-stdin-only-secret'; const captured = [];
  const runner = createPowerShellRunner({ spawnImpl: fakeSpawner(request => { captured.push(request); request.child.stdout.end(JSON.stringify({ ok: true, value: crypto.randomBytes(64).toString('base64') })); request.child.emit('close', 0); }) });
  await runner({ operation: 'protect', value: key });
  assert.equal(captured.length, 1); const call = captured[0];
  assert.equal(call.args.includes('-File'), true); assert.equal(call.args.includes('-ExecutionPolicy'), false); assert.equal(call.args.includes('-Command'), false);
  assert.equal(call.args.at(-1), path.join(__dirname, '..', 'lib', 'key-vault.ps1'));
  assert.equal(call.options.windowsHide, true); assert.equal(call.options.shell, false);
  assert.equal(JSON.stringify(call.args).includes(key), false); assert.equal(JSON.stringify(call.options.env).includes(key), false);
  assert.equal(Object.keys(call.options.env).some(name => /api.?key|token|secret|password/i.test(name)), false);
  assert.deepEqual(JSON.parse(call.input), { operation: 'protect', value: key });
});
test('policy-blocked file invocation fails closed after one attempt and keeps key out of errors', async () => {
  const key = 'synthetic-policy-test'; const calls = [];
  for(const denial of ['PSSecurityException: running scripts is disabled','UnauthorizedAccess: '+key,'An Application Control policy has blocked this file.']){
    calls.length=0;
    const runner = createPowerShellRunner({ spawnImpl: fakeSpawner(request => { calls.push(request); request.child.stderr.end(denial); request.child.emit('close', 1); }) });
    await assert.rejects(runner({ operation: 'protect', value: key }), error=>error.code==='VAULT_HELPER'&&!error.message.includes(key)&&!error.message.includes(denial));
    assert.equal(calls.length, 1); assert.equal(calls[0].args.includes('-Command'), false);
    assert.equal(calls[0].args.some(value => value.includes(key)), false);
    assert.equal(calls[0].args.includes('-ExecutionPolicy'), false);
    assert.deepEqual(JSON.parse(calls[0].input), { operation: 'protect', value: key });
  }
});

test('spawn permission denial never retries and preserves the existing ciphertext', async t=>{
  const {file}=temporary(t),mock=mockRunner();await createKeyVault({file,platform:'win32',runner:mock.runner}).save('synthetic-existing-key');
  const before=fs.readFileSync(file,'utf8');let calls=0;
  const runner=createPowerShellRunner({spawnImpl:()=>{calls++;throw Object.assign(new Error('Permission denied'),{code:'EACCES'});}});
  await assert.rejects(createKeyVault({file,platform:'win32',runner}).save('synthetic-replacement'),{code:'VAULT_SAVE'});
  assert.equal(calls,1);assert.equal(fs.readFileSync(file,'utf8'),before);
});
test('runner suppresses arbitrary stderr and malformed helper output', async () => {
  const secret = 'synthetic-not-in-errors';
  for (const fail of [true, false]) {
    const runner = createPowerShellRunner({ spawnImpl: fakeSpawner(({ child }) => { child.stderr.end(secret); child.stdout.end(fail ? '' : secret); child.emit('close', fail ? 1 : 0); }) });
    await assert.rejects(runner({ operation: 'protect', value: secret }), error => error.code === 'VAULT_HELPER' && !error.message.includes(secret));
  }
});

test('native vault uses only the packaged sibling EXE with the request in stdin',async()=>{
 const calls=[],key='native-synthetic-secret-Пример';
 const runner=createWindowsRunner({bridgePath:EXPECTED_BRIDGE,spawnImpl:fakeSpawner(request=>{calls.push(request);request.child.stdout.end(JSON.stringify({ok:true,value:'cipher-fixture'}));request.child.emit('close',0);})});
 assert.equal(await runner({operation:'protect',value:key}),'cipher-fixture');assert.equal(calls.length,1);
 const call=calls[0];assert.equal(call.file,EXPECTED_BRIDGE);assert.deepEqual(call.args,['--windows-helper','vault']);
 assert.deepEqual(JSON.parse(call.input),{operation:'protect',value:key});assert.equal(JSON.stringify(call.args).includes(key),false);assert.equal(JSON.stringify(call.options.env).includes(key),false);
 assert.equal(call.options.windowsHide,true);assert.equal(call.options.shell,false);assert.equal(call.args.some(arg=>/powershell|-file|-command|executionpolicy/i.test(arg)),false);
});

test('present but invalid native bridge paths fail before any launch or PowerShell fallback',async()=>{
 for(const bridgePath of ['',null,'DomTrack-UA.exe',path.join(os.tmpdir(),'DomTrack-UA.exe'),EXPECTED_BRIDGE+'-other.exe','\\\\server\\share\\DomTrack-UA.exe']){
  let calls=0;const runner=createWindowsRunner({bridgePath,spawnImpl:()=>{calls++;throw Error('Must not launch');}});
  await assert.rejects(runner({operation:'protect',value:'synthetic-native-test'}),{code:'VAULT_HELPER'});assert.equal(calls,0);
 }
});

test('native bridge denial rejects once without a PowerShell retry and preserves ciphertext',async t=>{
 const {file}=temporary(t),mock=mockRunner();await createKeyVault({file,platform:'win32',runner:mock.runner}).save('synthetic-old-native');
 const before=fs.readFileSync(file,'utf8'),calls=[];
 const runner=createWindowsRunner({bridgePath:EXPECTED_BRIDGE,spawnImpl:fakeSpawner(request=>{calls.push(request);request.child.stderr.end('denied synthetic-new-native');request.child.emit('close',1);})});
 await assert.rejects(createKeyVault({file,platform:'win32',runner}).save('synthetic-new-native'),error=>error.code==='VAULT_SAVE'&&!error.message.includes('synthetic-new-native'));
 assert.equal(calls.length,1);assert.equal(calls[0].file,EXPECTED_BRIDGE);assert.deepEqual(calls[0].args,['--windows-helper','vault']);assert.equal(fs.readFileSync(file,'utf8'),before);
});

test('the default vault runner honors the explicit launcher bridge environment',async t=>{
 const previous=process.env.DOMTRACK_WINDOWS_BRIDGE;process.env.DOMTRACK_WINDOWS_BRIDGE='relative-denied-bridge.exe';
 try{const {file}=temporary(t);await assert.rejects(createKeyVault({file,platform:'win32'}).save('synthetic-only'),{code:'VAULT_SAVE'});assert.equal(fs.existsSync(file),false);}
 finally{if(previous===undefined)delete process.env.DOMTRACK_WINDOWS_BRIDGE;else process.env.DOMTRACK_WINDOWS_BRIDGE=previous;}
});

test('native unprotect decodes UTF-8 correctly across arbitrary pipe chunks',async()=>{
 const key='Синтетичний-ключ-😀';
 const runner=createWindowsRunner({bridgePath:EXPECTED_BRIDGE,spawnImpl:fakeSpawner(({child})=>{const bytes=Buffer.from(JSON.stringify({ok:true,value:key}),'utf8');for(const byte of bytes)child.stdout.write(Buffer.from([byte]));child.emit('close',0);})});
 assert.equal(await runner({operation:'unprotect',value:'Y2lwaGVy'}),key);
});

test('Store mode without a launcher-provided bridge fails without starting PowerShell',async()=>{
 let calls=0;const runner=createWindowsRunner({storeMode:true,bridgePath:undefined,spawnImpl:()=>{calls++;throw Error('Must not launch');}});
 await assert.rejects(runner({operation:'protect',value:'synthetic-store-only'}),{code:'VAULT_HELPER'});assert.equal(calls,0);
});
test('the generated Store marker is bounded, schema checked and fails closed on read errors',()=>{
 const fixture=(value,size)=>{const bytes=Buffer.from(JSON.stringify(value));return{openSync:file=>{assert.equal(file,STORE_MARKER);return 3;},fstatSync:()=>({isFile:()=>true,size:size??bytes.length}),readSync:(_file,buffer,offset,length,position)=>{const count=Math.min(length,Math.max(0,bytes.length-position));bytes.copy(buffer,offset,position,position+count);return count;},closeSync(){}};};
 assert.equal(readStoreRuntimeMarker(fixture({format:'domtrack-store-runtime',version:1})),true);
 for(const fake of [fixture({format:'wrong',version:1}),fixture({format:'domtrack-store-runtime',version:2}),fixture({format:'domtrack-store-runtime',version:1,extra:true}),fixture({format:'domtrack-store-runtime',version:1},1025),fixture('x'.repeat(2048),20),{openSync(){throw Object.assign(Error('Denied'),{code:'EACCES'});}}])assert.throws(()=>readStoreRuntimeMarker(fake),{code:'WINDOWS_BRIDGE_CONFIG'});
 assert.equal(readStoreRuntimeMarker({openSync(){throw Object.assign(Error('Missing'),{code:'ENOENT'});}}),false);
});
// This integration case starts a real PowerShell script. Default fixture runs
// never execute it; a trusted/signed test environment must explicitly opt in.
test('real Windows CurrentUser DPAPI roundtrip with a synthetic key only', { skip: process.platform !== 'win32' ? 'Windows only' : process.env.DOMTRACK_TEST_REAL_DPAPI !== '1' ? 'Requires explicit DOMTRACK_TEST_REAL_DPAPI=1 in an allowed test environment' : false }, async t => {
  const { file } = temporary(t);
  const key = 'synthetic-DPAPI-only-' + crypto.randomUUID() + '-Тест';
  const vault = createKeyVault({ file });
  await vault.save(key); assert.equal(await vault.load(), key);
  assert.equal(fs.readFileSync(file, 'utf8').includes(key), false);
  await vault.remove(); assert.equal(await vault.load(), null);
});
