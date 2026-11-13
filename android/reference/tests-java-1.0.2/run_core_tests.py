#!/usr/bin/env python3
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
out = ROOT / '.test-classes'
out.mkdir(exist_ok=True)
sources = sorted((ROOT/'app/src/main/java/uk/ayachi/cftunnel/core').glob('*.java'))
subprocess.run(['javac','--release','17','-Xlint:all','-encoding','UTF-8','-d',str(out),*[str(p) for p in sources],str(ROOT/'tests/CoreTest.java'),str(ROOT/'tests/Harness.java')],check=True)
subprocess.run(['java','-cp',str(out),'uk.ayachi.cftunnel.core.CoreTest'],check=True)
