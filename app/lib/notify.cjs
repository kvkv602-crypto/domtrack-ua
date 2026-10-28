'use strict';
const {spawn}=require('node:child_process');
const {selectWindowsHelper}=require('./windows-helper.cjs');

const status={transport:null,requested:0,failed:0,lastWarning:null,lastResult:null};
const MAY_SUPPRESS='Системное уведомление запрошено. Windows может скрыть его; событие доступно внутри DomTrack.';
const UNAVAILABLE='Системное уведомление не подтверждено. Проверьте событие внутри DomTrack.';
function getWindowsNotificationStatus(){return {...status};}
function showWindowsNotifications(notifications,options={}){
  const {spawnImpl=spawn,platform=process.platform,disabled=process.env.DOMTRACK_DISABLE_DESKTOP==='1'}=options;
  if(disabled||platform!=='win32')return 0;
  const notices=(Array.isArray(notifications)?notifications:[]).filter(n=>n?.type==='targetprice').slice(0,3);
  if(!notices.length)return 0;
  let launch;
  try { launch=selectWindowsHelper('notify',{...(Object.hasOwn(options,'bridgePath')?{bridgePath:options.bridgePath}:{}),storeMode:options.storeMode}); }
  catch {status.transport='unavailable';status.failed+=notices.length;status.lastResult='failed';status.lastWarning=UNAVAILABLE;return 0;}
  status.transport=launch.transport;
  const environment=Object.fromEntries(Object.entries(process.env).filter(([name])=>!/(?:api.?key|token|secret|password)/i.test(name)));
  let queued=0;
  for(const notice of notices){
    const payload=JSON.stringify({title:'DomTrack UA · порог цены достигнут',text:String(notice.message).replaceAll('\0','').slice(0,240)});
    let child,timer,ended=false,output='',outputBytes=0,errorBytes=0;
    const finish=success=>{if(ended)return;ended=true;clearTimeout(timer);status.lastResult=success?'requested':'failed';status.lastWarning=success?MAY_SUPPRESS:UNAVAILABLE;if(!success)status.failed++;};
    try{
      child=spawnImpl(launch.executable,launch.args,{stdio:['pipe','pipe','pipe'],windowsHide:true,detached:true,shell:false,env:environment});
      child.on?.('error',()=>finish(false));child.stdin?.on('error',()=>finish(false));
      child.stdout?.on('data',chunk=>{outputBytes+=Buffer.byteLength(chunk);if(outputBytes>4096){try{child.kill?.();}catch{}finish(false);return;}output+=Buffer.from(chunk).toString('utf8');});
      child.stderr?.on('data',chunk=>{errorBytes+=Buffer.byteLength(chunk);if(errorBytes>8192){try{child.kill?.();}catch{}finish(false);}});
      child.on?.('close',code=>{
        if(code!==0){finish(false);return;}
        if(launch.transport==='native'){
          try{const result=JSON.parse(output.replace(/^\uFEFF/,'').trim());finish(result?.ok===true&&result.delivery==='balloon-requested');}catch{finish(false);}
        }else finish(true);
      });
      timer=setTimeout(()=>{try{child.kill?.();}catch{}finish(false);},16000);timer.unref?.();
      child.stdin.end(payload,'utf8');child.unref?.();queued++;status.requested++;
      if(!ended){status.lastWarning=MAY_SUPPRESS;status.lastResult='pending';}
    }catch{try{child?.kill?.();}catch{}finish(false);}
  }
  return queued;
}
module.exports={showWindowsNotifications,getWindowsNotificationStatus};
