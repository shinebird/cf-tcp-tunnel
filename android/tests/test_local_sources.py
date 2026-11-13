#!/usr/bin/env python3
import importlib.util, json, pathlib, subprocess, tarfile, tempfile, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('local_sources',ROOT/'tools/local_sources.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class LocalSources(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=pathlib.Path(self.tmp.name)
 def repo(self,name,files):
  p=self.root/name;p.mkdir()
  for name,data in files.items():
   f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(data)
  subprocess.run(['git','init','-q',str(p)],check=True)
  subprocess.run(['git','-C',str(p),'add','.'],check=True)
  subprocess.run(['git','-C',str(p),'-c','user.email=test@example.invalid','-c','user.name=Test','commit','-qm','fixture'],check=True)
  return p
 def openssl(self):
  return self.repo('OpenSSL source',{'Configure':'configure','Configurations/15-android.conf':'android','VERSION.dat':'MAJOR=3\nMINOR=6\nPATCH=0\n','LICENSE.txt':'license'})
 def test_snapshot_dirty_tracked_no_generated_files_no_repo_mutation(self):
  src=self.openssl();before=m.git(src,'status','--porcelain');cache=self.root/'cache'
  a,d=m.snapshot(src,cache);b,e=m.snapshot(src,cache);self.assertEqual(d,e)
  (src/'Makefile').write_text('Windows generated artifact')
  c,f=m.snapshot(src,cache);self.assertEqual(d,f)
  with tarfile.open(c) as t:self.assertNotIn('openssl-local/Makefile',t.getnames())
  (src/'Configure').write_text('user tracked modification')
  _,changed=m.snapshot(src,cache);self.assertNotEqual(d,changed)
  self.assertEqual((src/'Configure').read_text(),'user tracked modification')
  self.assertEqual(before,b'')
 def test_optional_openssl_gitlink_is_not_a_source_file(self):
  src=self.openssl();head=m.git(src,'rev-parse','HEAD').decode().strip()
  subprocess.run(['git','-C',str(src),'update-index','--add','--cacheinfo','160000,'+head+',cloudflare-quiche'],check=True)
  archive,_=m.snapshot(src,self.root/'cache')
  with tarfile.open(archive) as t:self.assertNotIn('openssl-local/cloudflare-quiche',t.getnames())
 def test_missing_tracked_file_rejected(self):
  src=self.openssl();(src/'Configure').unlink()
  with self.assertRaises(FileNotFoundError):m.snapshot(src,self.root/'cache')
 def test_modular_boost_version_and_missing_submodule(self):
  files={'libs/config/include/boost/version.hpp':'#define BOOST_VERSION 109300\n','LICENSE_1_0.txt':'license'}
  for module,headers in {'asio':['asio.hpp'],'beast':['beast.hpp'],'json':['json.hpp','json/src.hpp'],'config':['config.hpp']}.items():
   for h in headers:files[f'libs/{module}/include/boost/{h}']='// header'
  src=self.repo('Boost source',files);self.assertEqual(m.boost_info(m.repo(src))['version'],109300)
  (src/'libs/json/include/boost/json/src.hpp').unlink()
  with self.assertRaisesRegex(ValueError,'submodule'):m.boost_info(src)
 def test_openssl_42_dev_source_is_accepted(self):
  src=self.openssl();(src/'VERSION.dat').write_text('MAJOR=4\nMINOR=2\nPATCH=0\nPRE_RELEASE_TAG=dev\n')
  self.assertEqual(m.openssl_info(src)['version'],'4.2.0')
 def test_selection_paths_are_escaped(self):
  m.write_selection(self.root,'D:/开发目录/boost source','D:/build/deps')
  text=(self.root/'.native-deps/selection.properties').read_text('ascii')
  self.assertIn('D\\:/',text);self.assertIn('boost\\ source',text);self.assertIn('\\u5f00',text)
 def test_invalid_openssl_fails_without_download(self):
  src=self.openssl();(src/'VERSION.dat').write_text('MAJOR=1\nMINOR=1\nPATCH=1\n')
  with self.assertRaises(ValueError):m.openssl_info(src)
if __name__=='__main__':unittest.main(verbosity=2)
