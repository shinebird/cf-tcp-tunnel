#include "tunnel_engine.hpp"
#include <atomic>
#include <mutex>
#include <functional>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>

struct Agent;
struct Proxy;
namespace cf_hooks {
struct State {
    std::atomic<std::uint64_t> up{0}, down{0}, sessions{0};
    cf_native::Engine::Logger logger;
    // Weak registries only; no new ownership cycles. Accessed on the IO thread.
    std::vector<std::weak_ptr<Agent>> agents;
    std::vector<std::weak_ptr<Proxy>> proxies;
};
thread_local State* current = nullptr;
void message(std::string const& s) { if (current && current->logger) current->logger(s); }
void upload(std::size_t n) { if (current) current->up.fetch_add(n, std::memory_order_relaxed); }
void download(std::size_t n) { if (current) current->down.fetch_add(n, std::memory_order_relaxed); }
void session_begin() { if (current) current->sessions.fetch_add(1, std::memory_order_relaxed); }
void session_end() { if (current) current->sessions.fetch_sub(1, std::memory_order_relaxed); }
void track_agent(std::shared_ptr<Agent> const& a) {
    if (!current) return;
    std::erase_if(current->agents, [](auto const& w) { return w.expired(); });
    current->agents.emplace_back(a);
}
void track_proxy(std::shared_ptr<Proxy> const& p) {
    if (!current) return;
    std::erase_if(current->proxies, [](auto const& w) { return w.expired(); });
    current->proxies.emplace_back(p);
}
}

// Derived mechanically from the supplied, tested PC reconnect-fix source.
// See ../upstream/pc-main.cpp and ../pc-core-adaptation.patch for the exact delta.
#include "pc_core.inc"
#include <boost/version.hpp>
#include <openssl/pem.h>
#include <openssl/err.h>

namespace cf_native {
struct Engine::Impl {
    std::vector<std::string> args;
    std::string ca;
    cf_hooks::State hooks;
    // io_context is destroyed BEFORE the TLS context, so pending coroutine
    // frames are released while the OpenSSL context still exists.
    ssl::context tls{ssl::context::tls_client};
    net::io_context ioc{1};
    std::atomic<bool> stopped{false}, started{false};
    Impl(std::vector<std::string> a, std::string c):args(std::move(a)),ca(std::move(c)) {}
    void cleanup() {
        // Called by run's IO thread, never concurrently with socket handlers.
        for (auto const& w: hooks.proxies) if (auto p=w.lock()) p->stop();
        for (auto const& w: hooks.agents) if (auto a=w.lock()) a->stop();
        hooks.proxies.clear(); hooks.agents.clear();
        ioc.stop();
    }
};
Engine::Engine(std::vector<std::string> a,std::string ca):p_(std::make_unique<Impl>(std::move(a),std::move(ca))) {}
Engine::~Engine() = default;
void Engine::stop() noexcept { p_->stopped.store(true); p_->ioc.stop(); }
std::array<std::uint64_t,3> Engine::stats() const noexcept {
    return {p_->hooks.up.load(),p_->hooks.down.load(),p_->hooks.sessions.load()};
}
std::string Engine::version() {
#ifdef __ANDROID__
    const char* platform="Android NDK";
#else
    const char* platform="Host JNI test";
#endif
    return std::string("C++ Native / PC reconnect-fix / ")+platform+" / Boost "+BOOST_LIB_VERSION+" / "+OPENSSL_VERSION_TEXT;
}
static unsigned load_ca(ssl::context& ctx,std::string const& pem) {
    // Add the exported Android trust anchors to OpenSSL; never silently switch
    // to verify_none if the trust store is absent or cannot be decoded.
    BIO* b=BIO_new_mem_buf(pem.data(),static_cast<int>(pem.size()));
    if(!b) throw std::runtime_error("Could not allocate CA input");
    std::unique_ptr<BIO,decltype(&BIO_free)> bio(b,&BIO_free);
    auto* store=SSL_CTX_get_cert_store(ctx.native_handle());
    unsigned count=0;
    while(auto* x=PEM_read_bio_X509(b,nullptr,nullptr,nullptr)) {
        if(X509_STORE_add_cert(store,x)==1) ++count;
        X509_free(x);
        // Duplicate anchors are harmless; clear their error queue entries.
        ERR_clear_error();
    }
    ERR_clear_error();
    if(!count) throw std::runtime_error("No usable CA certificates exported; verification stays enabled");
    return count;
}
int Engine::run(Logger logger) {
    if(p_->started.exchange(true)) return 2;
    if(p_->stopped.load()) return 0;
    auto* prior=cf_hooks::current;
    cf_hooks::current=&p_->hooks;
    p_->hooks.logger=std::move(logger);
    int result=0;
    try {
        std::vector<char*> argv;
        for(auto& s:p_->args) argv.push_back(s.data());
        auto o=parse(static_cast<int>(argv.size()),argv.data());
        auto url=parse_url(o.url);
        log("NATIVE_ENGINE "+version());
#ifdef NDEBUG
        log("NATIVE_BUILD Release (NDEBUG=1)");
#else
        log("NATIVE_BUILD Debug (NDEBUG=0)");
#endif
        if(!o.connect_host.empty()) log("TCP endpoint override: "+o.connect_host+":"+url.port+
              " (TLS SNI and WebSocket Host remain "+url.host+")");
        if(o.insecure_skip_verify) {
            p_->tls.set_verify_mode(ssl::verify_none);
            log("WARNING: TLS certificate verification is DISABLED");
        } else {
            p_->tls.set_verify_mode(ssl::verify_peer);
            log("Loaded CA anchors into OpenSSL: "+std::to_string(load_ca(p_->tls,p_->ca)));
        }
        auto completed=[&result,this](std::exception_ptr e) {
            if(e && !p_->stopped.load()) {
                result=1;
                try {std::rethrow_exception(e);} catch(std::exception const& x) {log(std::string("Native loop ended: ")+x.what());}
            }
            p_->ioc.stop();
        };
        if(o.mode=="agent") net::co_spawn(p_->ioc,agent_loop(o,p_->tls,url),completed);
        else net::co_spawn(p_->ioc,client_loop(o,p_->tls,url),completed);
        // stop() before/during this point is safe: run never calls restart().
        p_->ioc.run();
    } catch(std::exception const& e) {log(std::string("Native fatal: ")+e.what());result=1;}
    p_->cleanup();
    log("NATIVE_STOPPED: active sockets closed; pending operations released on engine destruction");
    p_->hooks.logger={};
    cf_hooks::current=prior;
    return result;
}
}
