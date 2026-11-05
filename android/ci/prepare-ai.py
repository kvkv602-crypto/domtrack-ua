"""Fetch pinned, Apache-2.0 model assets. Never includes application user data."""
import hashlib,json,os,subprocess,time,urllib.request
from pathlib import Path

root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets/ai'; assets.mkdir(parents=True,exist_ok=True)
base='https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/e8f8c211226b894fcb81acc59f3b34ba3efd5f42/'
files={
 'minilm-qint8.onnx':('onnx/model_qint8_arm64.onnx','783fea82d71a58179b830a4dbd2d58447e640609e98eedf9ffa12622d375a672'),
 'sentencepiece.bpe.model':('sentencepiece.bpe.model','cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865'),
 'tokenizer.json':('tokenizer.json','2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8')}
def sha(p): return hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
for name,(remote,digest) in files.items():
 target=assets/name
 if target.exists() and sha(target)==digest: continue
 for attempt in range(3):
  try:
   request=urllib.request.Request(base+remote+'?download=true',headers={'User-Agent':'DomTrackUA-build/0.2'})
   with urllib.request.urlopen(request,timeout=90) as response,target.with_suffix('.part').open('wb') as out:
    total=0
    while chunk:=response.read(1024*1024):
     out.write(chunk);total+=len(chunk)
     if total%(20*1024*1024)==0:print(name,total,flush=True)
   target.with_suffix('.part').replace(target)
   if sha(target)!=digest: raise RuntimeError('Model checksum mismatch: '+name)
   print('Verified',name,target.stat().st_size,flush=True);break
  except Exception:
   if attempt==2:raise
   time.sleep(3)

# Compare the real native SentencePiece tokenizer against the model's official fast tokenizer.
from tokenizers import Tokenizer
tokenizer=Tokenizer.from_file(str(assets/'tokenizer.json'))
samples=['Продаю двухкомнатную квартиру с балконом и видом на парк.',
         'Продається двокімнатна квартира з балконом і краєвидом на парк.',
         '  Вул. Перемоги, 12 / 4 поверх з 9  ', 'Київ, Шевченківський район',
         'Запорожье: Брюллова 16, 42 м²; 13 100 USD', 'Меблі — нові. Ремонт 2024, 2025. 🏠']
golden=[{'text':text,'ids':tokenizer.encode(text).ids} for text in samples]
testassets=root/'app/src/androidTest/assets';testassets.mkdir(parents=True,exist_ok=True)
(testassets/'tokenizer-golden.json').write_text(json.dumps(golden,ensure_ascii=False),encoding='utf-8')
(assets/'tokenizer.json').unlink() # Keep only the compact native tokenizer in the APK.

sp=root/'app/build/generated/sentencepiece';sp.parent.mkdir(parents=True,exist_ok=True)
commit='31646a467d2051eb904e0b45de3a73e91fe1c1e3'
if not sp.exists():
 subprocess.run(['git','init',str(sp)],check=True)
 subprocess.run(['git','-C',str(sp),'fetch','--depth=1','https://github.com/google/sentencepiece.git',commit],check=True)
 subprocess.run(['git','-C',str(sp),'checkout','--detach','FETCH_HEAD'],check=True)
assert subprocess.check_output(['git','-C',str(sp),'rev-parse','HEAD'],text=True).strip()==commit
(assets/'Apache-2.0.txt').write_bytes((sp/'LICENSE').read_bytes())
(assets/'NOTICE.txt').write_text('Multilingual MiniLM L12 v2\nSentence Transformers / UKPLab\nModel revision e8f8c211226b894fcb81acc59f3b34ba3efd5f42\nhttps://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2\nApache-2.0\n\nSentencePiece 0.2.1, Google Inc., Apache-2.0\nhttps://github.com/google/sentencepiece\n',encoding='utf-8')
