"""Execute the actual shell preflight with fixture tools (no Windows emulation)."""
import os, pathlib, subprocess, tempfile, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
@unittest.skipIf(os.name=='nt','Requires a POSIX Bash fixture runner')
class PerlDetection(unittest.TestCase):
 def run_check(self,perl_os,host='MSYS_NT-10.0'):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d)
   perl=root/'perl';perl.write_text('#!/bin/bash\nif [[ "$1" == -e ]]; then printf "%s" "'+perl_os+'"; else echo "fixture perl"; fi\n');perl.chmod(0o755)
   make=root/'make';make.write_text('#!/bin/bash\necho "fixture make"\n');make.chmod(0o755)
   script=(ROOT/'tools/openssl-build.sh').read_text().replace('$(uname -s)',host).replace('/usr/bin/perl',str(perl)).replace('/usr/bin/make',str(make))
   test=root/'test.sh';test.write_text(script)
   return subprocess.run(['bash',str(test),'--check'],text=True,capture_output=True)
 def test_old_msys(self):self.assertEqual(self.run_check('msys').returncode,0)
 def test_new_cygwin(self):
  result=self.run_check('cygwin');self.assertEqual(result.returncode,0,result.stderr);self.assertIn('OS=cygwin',result.stdout)
 def test_mingw_shell_uses_posix_perl(self):self.assertEqual(self.run_check('cygwin','MINGW64_NT-10.0').returncode,0)
 def test_cygwin_host_name(self):self.assertEqual(self.run_check('cygwin','CYGWIN_NT-10.0').returncode,0)
 def test_native_windows_rejected_with_diagnostics(self):
  result=self.run_check('MSWin32');self.assertNotEqual(result.returncode,0);self.assertIn("Unsupported Perl OS 'MSWin32'",result.stderr)
if __name__=='__main__':unittest.main(verbosity=2)
