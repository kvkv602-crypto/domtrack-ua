#include <jni.h>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

static std::string utf8(JNIEnv* env,jstring value) {
    const jchar* p=env->GetStringChars(value,nullptr); jsize n=env->GetStringLength(value);
    std::string out;
    for(jsize i=0;i<n;i++) {
        uint32_t c=p[i];
        if(c>=0xd800 && c<=0xdbff && i+1<n && p[i+1]>=0xdc00 && p[i+1]<=0xdfff) { c=0x10000+((c-0xd800)<<10)+(p[++i]-0xdc00); }
        if(c<0x80) out+=char(c);
        else if(c<0x800) { out+=char(0xc0|(c>>6)); out+=char(0x80|(c&63)); }
        else if(c<0x10000) { out+=char(0xe0|(c>>12)); out+=char(0x80|((c>>6)&63)); out+=char(0x80|(c&63)); }
        else { out+=char(0xf0|(c>>18)); out+=char(0x80|((c>>12)&63)); out+=char(0x80|((c>>6)&63)); out+=char(0x80|(c&63)); }
    }
    env->ReleaseStringChars(value,p); return out;
}
extern "C" JNIEXPORT jlong JNICALL Java_ua_domtrack_mobile_SemanticModel_nativeOpen(JNIEnv* env,jclass,jstring path) {
    auto* processor=new sentencepiece::SentencePieceProcessor();
    if(!processor->Load(utf8(env,path)).ok()) { delete processor; env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Tokenizer model could not be loaded"); return 0; }
    return reinterpret_cast<jlong>(processor);
}
extern "C" JNIEXPORT jintArray JNICALL Java_ua_domtrack_mobile_SemanticModel_nativeEncode(JNIEnv* env,jclass,jlong handle,jstring text) {
    auto* processor=reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
    std::vector<int> ids;
    if(!processor || !processor->Encode(utf8(env,text),&ids).ok()) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Tokenizer failed"); return nullptr; }
    auto result=env->NewIntArray(ids.size()); env->SetIntArrayRegion(result,0,ids.size(),ids.data()); return result;
}
extern "C" JNIEXPORT void JNICALL Java_ua_domtrack_mobile_SemanticModel_nativeClose(JNIEnv*,jclass,jlong handle) { delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle); }
