// Cloudflare TCP tunnel client, compatible with the existing Worker protocol.
// C++20, Boost.Beast/Asio/JSON, OpenSSL. Single-threaded io_context by design.
#include <utility>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <iostream>
#include <map>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include <boost/asio.hpp>
#include <boost/asio/ssl.hpp>
#include <boost/beast.hpp>
#include <boost/beast/ssl.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/beast/websocket/ssl.hpp>
#include <boost/json.hpp>
#include <openssl/ssl.h>

namespace net = boost::asio;
namespace ssl = net::ssl;
namespace beast = boost::beast;
namespace websocket = beast::websocket;
namespace json = boost::json;
using tcp = net::ip::tcp;
using net::awaitable;
using net::use_awaitable;
using namespace std::chrono_literals;
using Wss = websocket::stream<beast::ssl_stream<beast::tcp_stream>>;
constexpr std::size_t FRAME = 64 * 1024;
constexpr std::size_t MAX_QUEUE = 4 * 1024 * 1024;

void log(std::string const& s) { std::cerr << s << '\n'; }

struct Options {
    std::string mode, url, token, connect_host, target_host="127.0.0.1", listen_host="127.0.0.1";
    unsigned listen_port=0, remote_port=0;
    std::vector<unsigned> ports;
    bool insecure_skip_verify = false;
};
unsigned port_number(std::string const& s) {
    auto n = std::stoul(s);
    if (n == 0 || n > 65535) throw std::runtime_error("port must be 1..65535");
    return static_cast<unsigned>(n);
}
Options parse(int argc, char** argv) {
    if (argc < 2) throw std::runtime_error("Specify 'agent' or 'client'");
    Options o; o.mode = argv[1];
    if (o.mode != "agent" && o.mode != "client") throw std::runtime_error("Unknown mode");
    for (int i=2; i<argc; i++) {
        if (std::string(argv[i]) == "--insecure-skip-verify") {
            o.insecure_skip_verify = true;
            continue;
        }
        if (i+1>=argc) throw std::runtime_error(std::string("Missing value for ")+argv[i]);
        std::string k=argv[i], v=argv[++i];
        if (k=="--url") o.url=v;
        else if (k=="--token") o.token=v;
        else if (k=="--connect-host") o.connect_host=v;
        else if (k=="--target-host") o.target_host=v;
        else if (k=="--listen-host") o.listen_host=v;
        else if (k=="--listen-port") o.listen_port=port_number(v);
        else if (k=="--remote-port") o.remote_port=port_number(v);
        else if (k=="--ports") {
            std::size_t pos=0;
            do {auto comma=v.find(',',pos); o.ports.push_back(port_number(v.substr(pos, comma-pos)));
                if(comma==std::string::npos) break;
                pos=comma+1; } while(pos<v.size());
        } else throw std::runtime_error("Unknown argument: "+k);
    }
    if (o.url.empty() || o.token.empty()) throw std::runtime_error("--url and --token required");
    if (!o.connect_host.empty()) {
        // Accept DNS names, IPv4 literals, or unbracketed IPv6 literals.
        // Port and URL scheme remain controlled by --url.
        if (o.connect_host.find_first_of("/\\?#@[] \t\r\n") != std::string::npos)
            throw std::runtime_error("--connect-host must be an IP or DNS name, without scheme, port or brackets");
    }
    if (o.mode=="agent" && o.ports.empty()) throw std::runtime_error("--ports required");
    if (o.mode=="client" && (!o.remote_port || !o.listen_port))
        throw std::runtime_error("--remote-port and --listen-port required");
    return o;
}
struct Url { std::string host, port="443", base; };
Url parse_url(std::string u) {
    if (!u.starts_with("wss://")) throw std::runtime_error("Only wss:// is supported");
    u.erase(0,6);
    auto slash=u.find('/');
    std::string authority=u.substr(0,slash);
    Url r;
    r.base=(slash==std::string::npos ? "" : u.substr(slash));
    while(!r.base.empty() && r.base.back()=='/') r.base.pop_back();
    if (r.base.find('?')!=std::string::npos || r.base.find('#')!=std::string::npos)
        throw std::runtime_error("URL must not contain query or fragment");
    if (authority.empty() || authority.find('@')!=std::string::npos)
        throw std::runtime_error("Invalid URL host");
    if (authority.front()=='[') {
        auto end=authority.find(']'); if(end==std::string::npos) throw std::runtime_error("Invalid IPv6 URL");
        r.host=authority.substr(1,end-1);
        if(end+1<authority.size()) {if(authority[end+1]!=':') throw std::runtime_error("Invalid port");r.port=authority.substr(end+2);}
    } else {
        auto colon=authority.rfind(':');
        if(colon!=std::string::npos) {r.host=authority.substr(0,colon);r.port=authority.substr(colon+1);}
        else r.host=authority;
    }
    if(r.host.empty() || r.port.empty()) throw std::runtime_error("Invalid URL");
    return r;
}
std::string host_header(Url const& u) {return u.host+(u.port=="443"?"":":"+u.port);}

awaitable<std::shared_ptr<Wss>> connect_wss(ssl::context& ctx, Url const& url,
                                             std::string const& path, std::string const& token,
                                             std::string const& connect_host) {
    auto ex=co_await net::this_coro::executor;
    tcp::resolver resolver(ex);
    auto eps=co_await resolver.async_resolve(connect_host.empty() ? url.host : connect_host,
                                             url.port,use_awaitable);
    auto ws=std::make_shared<Wss>(ex,ctx);
    beast::get_lowest_layer(*ws).expires_after(30s);
    co_await beast::get_lowest_layer(*ws).async_connect(eps,use_awaitable);
    if(!SSL_set_tlsext_host_name(ws->next_layer().native_handle(),url.host.c_str()))
        throw std::runtime_error("Failed to set TLS SNI");
    if (SSL_get_verify_mode(ws->next_layer().native_handle()) != SSL_VERIFY_NONE)
        ws->next_layer().set_verify_callback(ssl::host_name_verification(url.host));
    co_await ws->next_layer().async_handshake(ssl::stream_base::client,use_awaitable);
    beast::get_lowest_layer(*ws).expires_never();
    ws->set_option(websocket::stream_base::timeout::suggested(beast::role_type::client));
    ws->read_message_max(FRAME + 4096);
    ws->set_option(websocket::stream_base::decorator([token](websocket::request_type& req){
        req.set(beast::http::field::authorization, "Bearer " + token);
        req.set(beast::http::field::user_agent, "cf-tcp-tunnel-cpp/1.0");
    }));
    co_await ws->async_handshake(host_header(url),url.base+path,use_awaitable);
    co_return ws;
}
void terminate(Wss& ws) {
    beast::error_code ec;
    beast::get_lowest_layer(ws).socket().close(ec);
}
void terminate(tcp::socket& s) { beast::error_code ec; s.close(ec); }
std::vector<unsigned char> bytes(beast::flat_buffer const& b) {
    std::vector<unsigned char> result(b.size());
    net::buffer_copy(net::buffer(result), b.data());
    return result;
}
std::vector<unsigned char> with_id(std::uint32_t id, std::vector<unsigned char> const& data) {
    std::vector<unsigned char> v(4+data.size());
    v[0]=static_cast<unsigned char>(id>>24);v[1]=static_cast<unsigned char>(id>>16);
    v[2]=static_cast<unsigned char>(id>>8);v[3]=static_cast<unsigned char>(id);
    std::copy(data.begin(),data.end(),v.begin()+4);return v;
}
std::uint32_t read_id(std::vector<unsigned char> const& v) {
    return (std::uint32_t(v[0])<<24)|(std::uint32_t(v[1])<<16)|
           (std::uint32_t(v[2])<<8)|std::uint32_t(v[3]);
}
struct OutMsg { bool binary; std::vector<unsigned char> data; };
struct Queue {
    explicit Queue(net::any_io_executor ex): wake(ex) {wake.expires_at((net::steady_timer::time_point::max)());}
    net::steady_timer wake;
    std::deque<OutMsg> items;
    std::size_t used=0;
    bool stopped=false;
    bool push(OutMsg m) {
        if(stopped || m.data.size()+used>MAX_QUEUE) return false;
        used+=m.data.size();items.push_back(std::move(m));wake.cancel();return true;
    }
    void stop() {stopped=true;wake.cancel();}
    awaitable<std::optional<OutMsg>> pop() {
        while(items.empty() && !stopped) {
            wake.expires_at((net::steady_timer::time_point::max)());
            beast::error_code ec;
            co_await wake.async_wait(net::redirect_error(use_awaitable,ec));
        }
        if(items.empty()) co_return std::nullopt;
        OutMsg m=std::move(items.front());items.pop_front();used-=m.data.size();co_return m;
    }
};
OutMsg control(json::object object) {
    auto s=json::serialize(object);
    return {false, {s.begin(),s.end()}};
}
OutMsg binary(std::vector<unsigned char> v) {return {true,std::move(v)};}

struct Agent;
struct Session : std::enable_shared_from_this<Session> {
    std::shared_ptr<Agent> owner;
    std::uint32_t id;
    unsigned port;
    tcp::socket sock;
    Queue pending;
    bool done=false;
    Session(std::shared_ptr<Agent> a,std::uint32_t sid,unsigned p,net::any_io_executor ex);
    void stop();
    awaitable<void> run();
    awaitable<void> reader();
    awaitable<void> writer();
};
struct Agent : std::enable_shared_from_this<Agent> {
    std::shared_ptr<Wss> ws;
    Queue out;
    std::map<std::uint32_t,std::shared_ptr<Session>> sessions;
    std::string target;
    std::vector<unsigned> ports;
    bool stopped=false;
    Agent(std::shared_ptr<Wss> w,net::any_io_executor ex,Options const& o)
        : ws(std::move(w)),out(ex),target(o.target_host),ports(o.ports){}
    void stop() {
        if(stopped)return;
        stopped=true;
        out.stop(); terminate(*ws);
        auto copy=std::move(sessions);sessions.clear();
        for(auto& [id,s]:copy) s->stop();
    }
    bool send(OutMsg msg) {if(!out.push(std::move(msg))){log("Agent outbound queue overflow; disconnecting");stop();return false;}return true;}
    void send_control(char const* type,std::uint32_t id,std::string const& message="") {
        json::object o{{"type",type},{"id",id}};
        if(!message.empty())o["message"]=message;
        send(control(std::move(o)));
    }
    awaitable<void> sender() {
        try {
            while(auto m=co_await out.pop()) {
                ws->binary(m->binary);
                co_await ws->async_write(net::buffer(m->data),use_awaitable);
            }
        } catch(std::exception const& e){log(std::string("Agent websocket send: ")+e.what());}
        stop();
    }
    awaitable<void> receiver() {
        auto ex=co_await net::this_coro::executor;
        net::co_spawn(ex,[self=shared_from_this()]()->awaitable<void>{co_await self->sender();},net::detached);
        try {
            while(!stopped) {
                beast::flat_buffer b;
                co_await ws->async_read(b,use_awaitable);
                auto raw=bytes(b);
                if(ws->got_text()) {
                    boost::system::error_code ec;
                    auto value=json::parse(std::string_view((char const*)raw.data(),raw.size()),ec);
                    if(ec || !value.is_object())continue;
                    auto const& o=value.as_object();
                    auto* t=o.if_contains("type");auto* idv=o.if_contains("id");
                    if(!t||!t->is_string()||!idv||!idv->is_int64()) continue;
                    auto id64=idv->as_int64();
                    if(id64<=0 || id64>UINT32_MAX)continue;
                    auto id=static_cast<std::uint32_t>(id64);
                    std::string type(t->as_string());
                    if(type=="open") {
                        auto* pv=o.if_contains("port");
                        if(!pv||!pv->is_int64() || pv->as_int64()<=0 || pv->as_int64()>65535 || sessions.count(id)) {
                            send_control("error",id,"Invalid session");continue;
                        }
                        unsigned p=static_cast<unsigned>(pv->as_int64());
                        if(std::find(ports.begin(),ports.end(),p)==ports.end()) {
                            send_control("error",id,"Port not allowed");continue;
                        }
                        auto s=std::make_shared<Session>(shared_from_this(),id,p,ex);
                        sessions[id]=s;
                        net::co_spawn(ex,[s]()->awaitable<void>{co_await s->run();},net::detached);
                    } else if(type=="close") {
                        if(auto it=sessions.find(id);it!=sessions.end())it->second->stop();
                    }
                } else if(raw.size()>=5 && raw.size()<=FRAME+4) {
                    auto id=read_id(raw);
                    if(auto it=sessions.find(id);it!=sessions.end()) {
                        std::vector<unsigned char> data(raw.begin()+4,raw.end());
                        if(!it->second->pending.push(binary(std::move(data)))) {
                            log("Target queue overflow; closing session "+std::to_string(id));
                            it->second->stop();
                        }
                    }
                }
            }
        } catch(std::exception const& e){log(std::string("Agent WebSocket disconnected: ")+e.what());}
        stop();
    }
};
Session::Session(std::shared_ptr<Agent> a,std::uint32_t sid,unsigned p,net::any_io_executor ex)
    :owner(std::move(a)),id(sid),port(p),sock(ex),pending(ex){}
void Session::stop() {if(done)return;done=true;pending.stop();terminate(sock);}
awaitable<void> Session::reader() {
    try {
        std::array<unsigned char,FRAME> buf{};
        while(!done) {
            auto n=co_await sock.async_read_some(net::buffer(buf),use_awaitable);
            if(!n)break;
            std::vector<unsigned char> part(buf.begin(),buf.begin()+n);
            if(!owner->send(binary(with_id(id,part))))break;
        }
    } catch(std::exception const& e) {if(!done)log("Target read: "+std::string(e.what()));}
    stop();
}
awaitable<void> Session::writer() {
    try {
        while(!done) {
            auto m=co_await pending.pop();
            if(!m)break;
            co_await net::async_write(sock,net::buffer(m->data),use_awaitable);
        }
    } catch(std::exception const& e) {if(!done)log("Target write: "+std::string(e.what()));}
    stop();
}
awaitable<void> Session::run() {
    try {
        tcp::resolver resolver(co_await net::this_coro::executor);
        auto eps=co_await resolver.async_resolve(owner->target,std::to_string(port),use_awaitable);
        if(done) co_return;
        co_await net::async_connect(sock,eps,use_awaitable);
        if(done) co_return;
        owner->send_control("ready",id);
        auto ex=co_await net::this_coro::executor;
        net::co_spawn(ex,[self=shared_from_this()]()->awaitable<void>{co_await self->reader();},net::detached);
        co_await writer();
    } catch(std::exception const& e) {
        if(!done) {log("Session "+std::to_string(id)+" connect: "+e.what());owner->send_control("error",id,"Target connection failed");}
    }
    stop();
    if(auto it=owner->sessions.find(id);it!=owner->sessions.end() && it->second.get()==this)
        owner->sessions.erase(it);
    if(!owner->stopped)owner->send_control("close",id);
}

awaitable<void> agent_loop(Options o,ssl::context& sslctx,Url url) {
    auto ex=co_await net::this_coro::executor;
    unsigned pause=1;
    while(true) {
        try {
            auto ws=co_await connect_wss(sslctx,url,"/agent",o.token,o.connect_host);
            log("Agent connected; ports configured: "+std::to_string(o.ports.size()));
            pause=1;
            auto agent=std::make_shared<Agent>(std::move(ws),ex,o);
            co_await agent->receiver();
        } catch(std::exception const& e) {log(std::string("Agent reconnect: ")+e.what());}
        net::steady_timer delay(ex);
        delay.expires_after(std::chrono::seconds(pause));
        co_await delay.async_wait(use_awaitable);
        pause=std::min(30u,pause*2);
    }
}
struct Proxy : std::enable_shared_from_this<Proxy> {
    tcp::socket local;
    std::shared_ptr<Wss> ws;
    bool stopped=false;
    Proxy(tcp::socket s):local(std::move(s)){}
    void stop() {if(stopped)return;
        stopped=true;terminate(local);if(ws)terminate(*ws);}
    awaitable<void> from_local() {
        try {
            std::array<unsigned char,FRAME> buf{};
            while(!stopped) {
                auto n=co_await local.async_read_some(net::buffer(buf),use_awaitable);
                if(!n)break;
                ws->binary(true);
                co_await ws->async_write(net::buffer(buf.data(),n),use_awaitable);
            }
        } catch(std::exception const& e) {if(!stopped)log("local->Worker: "+std::string(e.what()));}
        stop();
    }
    awaitable<void> from_worker() {
        try {
            while(!stopped) {
                beast::flat_buffer b;
                co_await ws->async_read(b,use_awaitable);
                if(!ws->got_binary()) continue;
                auto v=bytes(b);
                co_await net::async_write(local,net::buffer(v),use_awaitable);
            }
        } catch(std::exception const& e) {if(!stopped)log("Worker->local: "+std::string(e.what()));}
        stop();
    }
    awaitable<void> run(ssl::context& ctx,Url url,Options o) {
        try {
            ws=co_await connect_wss(ctx,url,"/connect?port="+std::to_string(o.remote_port),o.token,o.connect_host);
            beast::flat_buffer b;
            co_await ws->async_read(b,use_awaitable);
            if(!ws->got_text())throw std::runtime_error("Expected ready JSON");
            auto v=bytes(b);
            auto parsed=json::parse(std::string_view((char const*)v.data(),v.size()));
            auto const* t=parsed.is_object()?parsed.as_object().if_contains("type"):nullptr;
            if(!t || !t->is_string() || t->as_string()!="ready")
                throw std::runtime_error("Remote not ready: "+json::serialize(parsed));
            log("Remote TCP session ready");
            auto ex=co_await net::this_coro::executor;
            net::co_spawn(ex,[self=shared_from_this()]()->awaitable<void>{co_await self->from_local();},net::detached);
            co_await from_worker();
        } catch(std::exception const& e) {log("Proxy session ended: "+std::string(e.what()));}
        stop();
    }
};
awaitable<void> client_loop(Options o,ssl::context& ctx,Url url) {
    auto ex=co_await net::this_coro::executor;
    tcp::resolver resolver(ex);
    auto eps=co_await resolver.async_resolve(o.listen_host,std::to_string(o.listen_port),use_awaitable);
    tcp::acceptor acceptor(ex);
    acceptor.open(eps.begin()->endpoint().protocol());
    acceptor.set_option(net::socket_base::reuse_address(true));
    acceptor.bind(eps.begin()->endpoint());
    acceptor.listen();
    log("Listening on "+o.listen_host+":"+std::to_string(o.listen_port)+" -> port "+std::to_string(o.remote_port));
    for(;;) {
        auto s=co_await acceptor.async_accept(use_awaitable);
        auto proxy=std::make_shared<Proxy>(std::move(s));
        net::co_spawn(ex,[proxy,&ctx,url,o]()->awaitable<void>{co_await proxy->run(ctx,url,o);},net::detached);
    }
}
int main(int argc,char** argv) {
    try {
        auto o=parse(argc,argv);
        auto u=parse_url(o.url);
        if (!o.connect_host.empty())
            log("TCP endpoint override: " + o.connect_host + ":" + u.port +
                " (TLS SNI and WebSocket Host remain " + u.host + ")");
        net::io_context ioc(1);
        ssl::context tls(ssl::context::tls_client);
        if (o.insecure_skip_verify) {
            tls.set_verify_mode(ssl::verify_none);
            log("WARNING: TLS certificate verification is DISABLED (--insecure-skip-verify)");
        } else {
            tls.set_default_verify_paths();
            tls.set_verify_mode(ssl::verify_peer);
        }
        if(o.mode=="agent")
            net::co_spawn(ioc,agent_loop(o,tls,u),[](std::exception_ptr e){if(e)try{std::rethrow_exception(e);}catch(std::exception const& x){log(x.what());}});
        else
            net::co_spawn(ioc,client_loop(o,tls,u),[](std::exception_ptr e){if(e)try{std::rethrow_exception(e);}catch(std::exception const& x){log(x.what());}});
        ioc.run();
    } catch(std::exception const& e) {
        log(std::string("Fatal: ")+e.what());return 1;
    }
}
