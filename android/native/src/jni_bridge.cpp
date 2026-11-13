#include "tunnel_engine.hpp"
#include <jni.h>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <stdexcept>

namespace {
std::mutex mutex;
std::unordered_map<jlong,std::shared_ptr<cf_native::Engine>> engines;
std::atomic<jlong> next_id{1};
std::shared_ptr<cf_native::Engine> lookup(jlong id) {
    std::lock_guard lock(mutex);
    auto it=engines.find(id);
    return it==engines.end()?nullptr:it->second;
}
std::string read_utf(JNIEnv* env,jstring str) {
    if(!str) throw std::runtime_error("Missing native argument");
    const char* p=env->GetStringUTFChars(str,nullptr);
    if(!p) throw std::runtime_error("Could not read native argument");
    std::string s(p);env->ReleaseStringUTFChars(str,p);return s;
}
void fail(JNIEnv* env,char const* message) {
    if(env->ExceptionCheck()) return;
    auto type=env->FindClass("java/lang/IllegalStateException");
    if(type) env->ThrowNew(type,message);
}
// Logs from OpenSSL/Boost can contain OS text in an arbitrary locale. Do not
// pass arbitrary bytes into NewStringUTF (modified UTF-8); use ASCII fallback.
jstring safe_log(JNIEnv* env,std::string const& text) {
    std::string out;out.reserve(text.size());
    for(unsigned char c:text) out.push_back(c>=32 && c<=126 ? char(c) : (c=='\n'?'\n':'?'));
    return env->NewStringUTF(out.c_str());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_uk_ayachi_cftunnel_core_NativeTunnel_nativeCreate(JNIEnv* env,jclass,jobjectArray argv,jstring ca) {
    try {
        std::vector<std::string> a;
        if(!argv) throw std::runtime_error("Missing argv");
        for(jsize i=0;i<env->GetArrayLength(argv);++i) {
            auto s=static_cast<jstring>(env->GetObjectArrayElement(argv,i));
            a.push_back(read_utf(env,s));env->DeleteLocalRef(s);
        }
        auto e=std::make_shared<cf_native::Engine>(std::move(a),read_utf(env,ca));
        jlong id=next_id.fetch_add(1);
        std::lock_guard lock(mutex);engines.emplace(id,std::move(e));return id;
    } catch(std::exception const& e) {fail(env,e.what());return 0;}
}
extern "C" JNIEXPORT jint JNICALL
Java_uk_ayachi_cftunnel_core_NativeTunnel_nativeRun(JNIEnv* env,jobject self,jlong id) {
    auto e=lookup(id);if(!e){fail(env,"Native handle expired");return 1;}
    auto type=env->GetObjectClass(self);
    auto method=env->GetMethodID(type,"onNativeLog","(Ljava/lang/String;)V");
    env->DeleteLocalRef(type);
    if(!method) return 1;
    // nativeRun is invoked from one Java-created worker thread. Its local
    // reference + JNIEnv remain valid for this synchronous call only.
    return e->run([env,self,method](std::string const& s) {
        auto text=safe_log(env,s);
        if(text) {env->CallVoidMethod(self,method,text);env->DeleteLocalRef(text);}
        // A UI/log callback must not unwind a native network coroutine.
        if(env->ExceptionCheck()) env->ExceptionClear();
    });
}
extern "C" JNIEXPORT void JNICALL
Java_uk_ayachi_cftunnel_core_NativeTunnel_nativeStop(JNIEnv*,jclass,jlong id) {
    if(auto e=lookup(id)) e->stop();
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_uk_ayachi_cftunnel_core_NativeTunnel_nativeStats(JNIEnv* env,jclass,jlong id) {
    auto e=lookup(id);auto s=e?e->stats():std::array<std::uint64_t,3>{};
    jlong v[3]={static_cast<jlong>(s[0]),static_cast<jlong>(s[1]),static_cast<jlong>(s[2])};
    auto a=env->NewLongArray(3);if(a)env->SetLongArrayRegion(a,0,3,v);return a;
}
extern "C" JNIEXPORT void JNICALL
Java_uk_ayachi_cftunnel_core_NativeTunnel_nativeDestroy(JNIEnv*,jclass,jlong id) {
    // Java calls this only AFTER nativeRun returns; stop() alone never deletes
    // the context underneath an IO handler. Map ids prevent pointer reuse bugs.
    std::shared_ptr<cf_native::Engine> e;
    {std::lock_guard lock(mutex);auto it=engines.find(id);if(it!=engines.end()){e=std::move(it->second);engines.erase(it);}}
    if(e)e->stop();
}
