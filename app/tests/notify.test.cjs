'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),path=require('node:path');
const {EventEmitter}=require('node:events'),{PassThrough}=require('node:stream');
const {showWindowsNotifications}=require('../lib/notify.cjs');

test('notifications use the fixed on-disk script and keep all message text in stdin',()=>{
 const calls=[];const secret='Тестовий приватний поріг 12345 UAH';
 const queued=showWindowsNotifications([{type:'targetprice',message:secret}],{platform:'win32',disabled:false,spawnImpl:(file,args,options)=>{
  const child=new EventEmitter();child.stdin=new PassThrough();child.unref=()=>{};let input='';child.stdin.on('data',data=>{input+=data.toString('utf8');});calls.push({file,args,options,read:()=>input});return child;
 }});
 assert.equal(queued,1);assert.equal(calls.length,1);const call=calls[0];
 assert.equal(call.file,path.join(process.env.SystemRoot||process.env.WINDIR||'C:\\Windows','System32','WindowsPowerShell','v1.0','powershell.exe'));
 assert.deepEqual(call.args,['-NoProfile','-NonInteractive','-STA','-WindowStyle','Hidden','-File',path.join(__dirname,'..','lib','notify.ps1')]);
 assert.equal(call.options.windowsHide,true);assert.equal(call.options.shell,false);assert.deepEqual(call.options.stdio,['pipe','ignore','ignore']);
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
