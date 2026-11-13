#!/usr/bin/env python3
"""Mock-build integration: orchestration/cache/failure safety, not an NDK compiler test."""
import importlib.util, json, os, pathlib, subprocess, sys, tempfile, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import native_build as n
import test_local_sources as fixtures
@unittest.skipIf(os.name=="nt", "POSIX fixture runner; use PowerShell -CheckOnly for Windows tool validation")
class NativeBuild(unittest.TestCase):
 setUp=fixtures.LocalSources.setUp
 repo=fixtures.LocalSources.repo
 openssl=fixtures.LocalSources.openssl
 def setup_driver(self):
  project=self.root/'project';(project/'tools').mkdir(parents=True)
  for name in ['native_build.py','local_sources.py','openssl-build.sh']:
   (project/'tools'/name).write_bytes((ROOT/'tools'/name).read_bytes())
  files={'boost/version.hpp':'#define BOOST_VERSION 109300\n','LICENSE_1_0.txt':'license'}
  for name in ['asio.hpp','beast.hpp','json.hpp','json/src.hpp','config.hpp']:files['boost/'+name]='header'
  boost=self.repo('Boost source',files);ssl=self.openssl()
  ndk=self.root/'ndk';ndk.mkdir();(ndk/'source.properties').write_text('Pkg.Revision = 30.0.16248370')
  bash=self.root/'fake-bash'
  bash.write_text('''#!/usr/bin/env python3
import pathlib,sys,os
if sys.argv[-1]=='--check':print('fixture perl/make');sys.exit(0)
prefix=pathlib.Path(sys.argv[4]);src=pathlib.Path(sys.argv[2])
if (src/'Configure').read_text()=='fail':sys.exit(9)
for f in ['lib/libssl.a','lib/libcrypto.a','include/openssl/ssl.h']:
 p=prefix/f;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('fixture archive')
print('MOCK-BUILD '+sys.argv[5])
''');bash.chmod(0o755)
  cmd=[sys.executable,str(project/'tools/native_build.py'),'--boost',str(boost),'--openssl',str(ssl),'--ndk',str(ndk),'--bash',str(bash)]
  return project,ssl,ndk,cmd
 def test_check_only_has_no_writes(self):
  p,s,d,cmd=self.setup_driver()
  subprocess.run(cmd+['--check'],check=True,capture_output=True)
  self.assertFalse((p/'.native-deps').exists())
 def test_cache_abi_source_ndk_and_failed_build(self):
  p,s,d,cmd=self.setup_driver()
  def run(extra=[]):return subprocess.run(cmd+extra,text=True,capture_output=True)
  first=run();self.assertEqual(first.returncode,0,first.stderr);self.assertIn('MOCK-BUILD',first.stdout)
  self.assertIn('Reusing completed',run().stdout)
  self.assertIn('MOCK-BUILD x86_64',run(['--abis','x86_64']).stdout)
  (d/'source.properties').write_text('Pkg.Revision = 30.0.99999')
  self.assertIn('MOCK-BUILD',run().stdout)
  dest=p/'.native-deps/installed-direct/arm64-android-cftunnel'
  before=(dest/'build-info.json').read_bytes()
  (s/'Configure').write_text('fail')
  failed=run();self.assertNotEqual(failed.returncode,0)
  self.assertEqual((dest/'build-info.json').read_bytes(),before)
  self.assertFalse((p/'.native-deps/direct-build.lock').exists())
  (s/'Configure').write_text('changed source')
  self.assertIn('MOCK-BUILD',run().stdout)
  (dest/'lib/libssl.a').unlink()
  self.assertEqual(run().returncode,0);self.assertTrue((dest/'lib/libssl.a').is_file())
 def test_unknown_abi_fails_before_build(self):
  p,s,d,cmd=self.setup_driver()
  result=subprocess.run(cmd+['--abis','armeabi-v7a'],capture_output=True)
  self.assertNotEqual(result.returncode,0);self.assertFalse((p/'.native-deps').exists())
if __name__=='__main__':unittest.main(verbosity=2)
