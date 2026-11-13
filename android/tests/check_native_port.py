#!/usr/bin/env python3
"""Offline checks of provenance and separation; not an NDK compile or Android Lint."""
from pathlib import Path
import hashlib,re,unittest
ROOT=Path(__file__).resolve().parents[1]
class Port(unittest.TestCase):
    def test_upstream_hash(self):
        expected=(ROOT/'native/UPSTREAM.sha256').read_text().split()[0]
        self.assertEqual(hashlib.sha256((ROOT/'native/upstream/pc-main.cpp').read_bytes()).hexdigest(),expected)
    def test_transport_is_pc_source_not_rewrite(self):
        old=(ROOT/'native/upstream/pc-main.cpp').read_text()
        old=old[:old.index('int main(int argc,char** argv)')]
        now=(ROOT/'native/src/pc_core.inc').read_text()
        # Only API compatibility adjustment: new Asio removed cancel(error_code&).
        now=now.replace('heartbeatTimer.cancel();','heartbeatTimer.cancel(ignore);').replace('deadline->cancel();','deadline->cancel(ignore);')
        now=now.replace('void log(std::string const& s) { cf_hooks::message(s); }',"void log(std::string const& s) { std::cerr << s << '\\n'; }")
        now=re.sub(r'\n\s*cf_hooks::(?:upload|download|track_proxy|track_agent)\([^;]+;', '',now)
        now=now.replace('cf_hooks::session_begin();','').replace('cf_hooks::session_end();','')
        self.assertEqual(old,now)
    def test_no_java_transport_in_app(self):
        core=ROOT/'app/src/main/java/uk/ayachi/cftunnel/core'
        for name in ['Tunnel.java','Wss.java','ByteQueue.java','Control.java']:
            self.assertFalse((core/name).exists())
        s=(core/'NativeTunnel.java').read_text()
        self.assertIn('System.loadLibrary("cf_tunnel")',s)
        self.assertNotIn('SSLSocket',s)
        self.assertNotIn('new Wss',s)
    def test_android_service_uses_native(self):
        s=(ROOT/'app/src/main/java/uk/ayachi/cftunnel/TunnelService.java').read_text()
        self.assertIn('new NativeTunnel(config',s)
        self.assertNotIn('new Tunnel(config',s)
        self.assertIn('thisGeneration',s)
    def test_sdk_and_lint_retained(self):
        s=(ROOT/'app/build.gradle').read_text()
        for part in ["orElse('37.0')","orElse('37.0.0')",'minSdk 26','targetSdk 36','abortOnError true','externalNativeBuild',"versionName '1.1.2-native'"]:
            self.assertIn(part,s)
    def test_min_sdk_native_deps_match(self):
        s=(ROOT/'tools/openssl-build.sh').read_text()
        self.assertIn('-D__ANDROID_API__=26',s)
        self.assertIn('no-shared',s)
        self.assertIn('-fPIC',s)
    def test_tls_not_disabled_by_port(self):
        s=(ROOT/'native/src/tunnel_engine.cpp').read_text()
        self.assertIn('if(o.insecure_skip_verify)',s)
        self.assertIn('ssl::verify_peer',s)
        self.assertIn('load_ca(p_->tls,p_->ca)',s)
    def test_pc_asio_coroutines_retained(self):
        s=(ROOT/'native/src/pc_core.inc').read_text()
        self.assertIn('using net::awaitable;',s)
        self.assertIn('co_await net::async_write(local',s)
        self.assertNotIn('boost::cobalt',s)
    def test_windows_proxy_jdk_args_retained(self):
        for name in ['build.ps1','build-apk.ps1']:
            s=(ROOT/'tools'/name).read_text(encoding='utf-8-sig')
            for arg in ['$ProxyUrl','$JdkPath','$AndroidSdkPath','$NdkPath','$NdkVersion','$NativeAbis','$MsysRoot']:
                self.assertIn(arg,s)
    def test_no_worker_changes_required(self):
        s=(ROOT/'native/src/pc_core.inc').read_text()
        for route in ['"/agent?agent="','"/connect?agent="']:
            self.assertIn(route,s)
if __name__=='__main__':unittest.main(verbosity=2)
