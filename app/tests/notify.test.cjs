'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),path=require('node:path');
const {EventEmitter}=require('node:events'),{PassThrough}=require('node:stream');
const {showWindowsNotifications,getWindowsNotificationStatus}=require('../lib/notify.cjs');
const {EXPECTED_BRIDGE}=require('../lib/windows-helper.cjs');

test('notifications use the fixed on-disk script and keep all message text in stdin',()=>{
 const calls=[];const secret='Тестовий приватний поріг 12345 UAH';
 const queued=showWindowsNotifications([{type:'targetprice',message:secret}],{platform:'win32',disabled:false,spawnImpl:(file,args,options)=>{
  const child=new EventEmitter();child.stdin=new PassThrough();child.unref=()=>{};let input='';child.stdin.on('data',data=>{input+=data.toString('utf8');});calls.push({file,args,options,read:()=>input});return child;
 }});
 assert.equal(queued,1);assert.equal(calls.length,1);const call=calls[0];
 assert.equal(call.file,path.join(process.env.SystemRoot||process.env.WINDIR||'C:\\Windows','System32','WindowsPowerShell','v1.0','powershell.exe'));
 assert.deepEqual(call.args,['-NoProfile','-NonInteractive','-STA','-WindowStyle','Hidden','-File',path.join(__dirname,'..','lib','notify.ps1')]);
 assert.equal(call.options.windowsHide,true);assert.equal(call.options.shell,false);assert.deepEqual(call.options.stdio,['pipe','pipe','pipe']);
  assert.equal(Object.keys(call.options.env).some(name=>/api.?key|token|secret|password/i.test(name)),false);
 assert.equal(JSON.stringify(call.args).includes(secret),false);assert.deepEqual(JSON.parse(call.read()),{title:'DomTrack UA · порог цены достигнут',text:secret});
});

test('notification policy denial does not retry the script through another execution mode',async()=>{
 let calls=0;
 showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'win32',disabled:false,spawnImpl:()=>{
  calls++;const child=new EventEmitter();child.stdin=new PassThrough();child.unref=()=>{};
  queueMicrotask(()=>{child.emit('error',Object.assign(new Error('Application Control policy blocked this script'),{code:'EACCES'}));child.stdin.emit('error',Object.assign(new Error('Closed stdin'),{code:'EPIPE'}));child.emit('close',1);});return child;
 }});
 await new Promise(resolve=>setImmediate(resolve));assert.equal(calls,1);
});

test('notification spawn failure does not dispatch a fallback',()=>{
 let calls=0;assert.equal(showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'win32',disabled:false,spawnImpl:()=>{calls++;throw Object.assign(new Error('Policy denied'),{code:'EACCES'});}}),0);assert.equal(calls,1);
});

test('notification stdin write failure stops that child without another launch',()=>{
 let calls=0,killed=0;assert.equal(showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'win32',disabled:false,spawnImpl:()=>{calls++;return{on(){},stdin:{on(){},end(){throw Error('Closed input');}},kill(){killed++;}};}}),0);assert.equal(calls,1);assert.equal(killed,1);
});

test('disabled and non-Windows notifications never start a script',()=>{
 let calls=0;const spawnImpl=()=>{calls++;throw Error('Must not start');};
 assert.equal(showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'win32',disabled:true,spawnImpl}),0);
 assert.equal(showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'linux',disabled:false,spawnImpl}),0);assert.equal(calls,0);
});

function nativeSpawner(handler,calls){return(file,args,options)=>{
 const child=new EventEmitter();child.stdin=new PassThrough();child.stdout=new PassThrough();child.stderr=new PassThrough();child.unref=()=>{};child.kill=()=>child.emit('close',1);
 let input='';child.stdin.on('data',chunk=>{input+=chunk.toString('utf8');});child.stdin.on('finish',()=>queueMicrotask(()=>handler({child,file,args,options,input})));
 calls.push({child,file,args,options,read:()=>input});return child;
};}
test('native notifications invoke only the sibling bridge and report requested rather than displayed',async()=>{
 const calls=[],privateText='synthetic native notice \0private';
 const queued=showWindowsNotifications([{type:'targetprice',message:privateText}],{platform:'win32',disabled:false,bridgePath:EXPECTED_BRIDGE,storeMode:true,spawnImpl:nativeSpawner(({child})=>{child.stdout.end(JSON.stringify({ok:true,delivery:'balloon-requested'}));child.emit('close',0);},calls)});
 assert.equal(queued,1);await new Promise(resolve=>setImmediate(resolve));assert.equal(calls.length,1);
 const call=calls[0];assert.equal(call.file,EXPECTED_BRIDGE);assert.deepEqual(call.args,['--windows-helper','notify']);assert.equal(call.options.windowsHide,true);assert.equal(call.options.shell,false);
 assert.equal(JSON.stringify(call.args).includes(privateText),false);assert.equal(JSON.stringify(call.options.env).includes(privateText),false);assert.deepEqual(JSON.parse(call.read()),{title:'DomTrack UA · порог цены достигнут',text:privateText.replaceAll('\0','')});
 const status=getWindowsNotificationStatus();assert.equal(status.transport,'native');assert.equal(status.lastResult,'requested');assert.match(status.lastWarning,/Windows.*скрыть/);assert.equal(status.lastWarning.includes('private'),false);
});
test('a Store runtime without the native bridge never tries PowerShell',()=>{
 let calls=0;assert.equal(showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'win32',disabled:false,bridgePath:undefined,storeMode:true,spawnImpl:()=>{calls++;throw Error('Must not launch');}}),0);
 assert.equal(calls,0);assert.equal(getWindowsNotificationStatus().lastResult,'failed');assert.ok(getWindowsNotificationStatus().lastWarning);
});
test('invalid native bridge configuration never dispatches any notification helper',()=>{
 for(const bridgePath of ['',null,'relative.exe',path.join(require('node:os').tmpdir(),'DomTrack-UA.exe')]){
  let calls=0;assert.equal(showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'win32',disabled:false,bridgePath,spawnImpl:()=>{calls++;throw Error('Must not launch');}}),0);assert.equal(calls,0);
 }
});
test('native denial and malformed acknowledgements have visible generic warnings and no fallback',async()=>{
 for(const reply of [null,{ok:false,code:'WINDOWS_NOTIFY_UNAVAILABLE'},{ok:true,delivery:'displayed'}]){
  const calls=[],before=getWindowsNotificationStatus().failed;
  assert.equal(showWindowsNotifications([{type:'targetprice',message:'synthetic-notification-secret'}],{platform:'win32',disabled:false,bridgePath:EXPECTED_BRIDGE,spawnImpl:nativeSpawner(({child})=>{if(reply)child.stdout.end(JSON.stringify(reply));else child.stderr.end('policy denied synthetic-notification-secret');child.emit('close',reply?0:1);},calls)}),1);
  await new Promise(resolve=>setImmediate(resolve));assert.equal(calls.length,1);assert.equal(calls[0].file,EXPECTED_BRIDGE);assert.deepEqual(calls[0].args,['--windows-helper','notify']);
  const status=getWindowsNotificationStatus();assert.equal(status.failed,before+1);assert.equal(status.lastResult,'failed');assert.equal(status.lastWarning.includes('synthetic-notification-secret'),false);
 }
});
test('oversized native acknowledgement is bounded and cannot cause a retry',async()=>{
 const calls=[];showWindowsNotifications([{type:'targetprice',message:'fixture'}],{platform:'win32',disabled:false,bridgePath:EXPECTED_BRIDGE,spawnImpl:nativeSpawner(({child})=>{child.stdout.write('x'.repeat(4097));},calls)});
 await new Promise(resolve=>setImmediate(resolve));assert.equal(calls.length,1);assert.equal(getWindowsNotificationStatus().lastResult,'failed');
});
