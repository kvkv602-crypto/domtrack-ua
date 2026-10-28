'use strict';
const path=require('node:path');
const {spawn}=require('node:child_process');
const SCRIPT=path.join(__dirname,'notify.ps1');
function showWindowsNotifications(notifications,{spawnImpl=spawn,platform=process.platform,disabled=process.env.DOMTRACK_DISABLE_DESKTOP==='1'}={}){
  if(disabled||platform!=='win32')return 0;
  const executable=path.join(process.env.SystemRoot||process.env.WINDIR||'C:\\Windows','System32','WindowsPowerShell','v1.0','powershell.exe');
  const environment=Object.fromEntries(Object.entries(process.env).filter(([name])=>!/(?:api.?key|token|secret|password)/i.test(name)));
  let queued=0;
  for(const n of notifications.filter(n=>n.type==='targetprice').slice(0,3)){
    const payload=JSON.stringify({title:'DomTrack UA · порог цены достигнут',text:String(n.message).slice(0,240)});
    let child;
    try{
      child=spawnImpl(executable,['-NoProfile','-NonInteractive','-STA','-WindowStyle','Hidden','-File',SCRIPT],{stdio:['pipe','ignore','ignore'],windowsHide:true,detached:true,shell:false,env:environment});
      child.on?.('error',()=>{});child.stdin?.on('error',()=>{});
      child.stdin.end(payload,'utf8');child.unref?.();queued++;
    }catch{try{child?.kill?.();}catch{}}
  }
  // Dispatch is best-effort; policy denial never causes an inline/script-host retry.
  return queued;
}
module.exports={showWindowsNotifications};
