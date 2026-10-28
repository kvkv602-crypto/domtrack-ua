'use strict';
const path=require('node:path');
const fs=require('node:fs');

const EXPECTED_BRIDGE=path.resolve(__dirname,'..','DomTrack-UA.exe');
const STORE_MARKER=path.resolve(__dirname,'..','store-runtime.json');
function configurationError(){const error=new Error('The configured Windows bridge is unavailable.');error.code='WINDOWS_BRIDGE_CONFIG';return error;}
function readStoreRuntimeMarker(fsImpl=fs){
  let file;const bytes=Buffer.alloc(1025);
  try{
    file=fsImpl.openSync(STORE_MARKER,'r');
    const stat=fsImpl.fstatSync(file);
    if(!stat.isFile()||stat.size<1||stat.size>1024)throw configurationError();
    let offset=0;
    while(offset<bytes.length){const count=fsImpl.readSync(file,bytes,offset,bytes.length-offset,offset);if(!count)break;offset+=count;}
    if(offset<1||offset>1024)throw configurationError();
    const data=JSON.parse(bytes.subarray(0,offset).toString('utf8'));
    if(!data||Array.isArray(data)||data.format!=='domtrack-store-runtime'||data.version!==1||Object.keys(data).length!==2)throw configurationError();
    return true;
  }catch(error){if(error.code==='ENOENT')return false;throw configurationError();}
  finally{bytes.fill(0);if(file!==undefined)try{fsImpl.closeSync(file);}catch{}}
}
function selectWindowsHelper(kind,options={}){
  const bridgePath=Object.hasOwn(options,'bridgePath')?options.bridgePath:process.env.DOMTRACK_WINDOWS_BRIDGE;
  if(!['vault','notify'].includes(kind))throw new Error('Invalid Windows helper kind.');
  const storeMode=readStoreRuntimeMarker()||options.storeMode===true;
  if(storeMode&&bridgePath===undefined)throw configurationError();
  if(bridgePath!==undefined){
    // The launcher sets this explicitly. An invalid setting is a denial, not a
    // reason to dispatch the same request through another interpreter.
    const canonical=value=>path.win32.normalize(value).toLocaleLowerCase('en-US');
    if(typeof bridgePath!=='string'||bridgePath.length>32767||/[\u0000-\u001f]/.test(bridgePath)||!path.win32.isAbsolute(bridgePath)||! /^[A-Za-z]:[\\/]/.test(bridgePath)||canonical(bridgePath)!==canonical(EXPECTED_BRIDGE)){
      throw configurationError();
    }
    return {transport:'native',executable:bridgePath,args:['--windows-helper',kind]};
  }
  const executable=path.join(process.env.SystemRoot||process.env.WINDIR||'C:\\Windows','System32','WindowsPowerShell','v1.0','powershell.exe');
  const args=['-NoProfile','-NonInteractive',...(kind==='notify'?['-STA']:[]),'-WindowStyle','Hidden','-File',path.join(__dirname,kind==='vault'?'key-vault.ps1':'notify.ps1')];
  return {transport:'powershell',executable,args};
}
module.exports={selectWindowsHelper,readStoreRuntimeMarker,EXPECTED_BRIDGE,STORE_MARKER};
