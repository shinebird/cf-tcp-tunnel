#!/usr/bin/env python3
"""Check APK library architecture and every ELF64 LOAD alignment. Not a device test."""
import struct,sys,zipfile
if len(sys.argv)!=2:raise SystemExit('Usage: python tools/check_apk_native.py app-release.apk')
with zipfile.ZipFile(sys.argv[1]) as z:
    libs=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
    if not any(n.endswith('/libcf_tunnel.so') for n in libs):raise SystemExit('Native tunnel library missing')
    for n in libs:
        b=z.read(n)
        if b[:6]!=b'\x7fELF\x02\x01':raise SystemExit(f'{n}: expected ELF64 little-endian')
        machine=struct.unpack_from('<H',b,18)[0]
        expected=183 if '/arm64-v8a/' in n else 62 if '/x86_64/' in n else None
        if machine!=expected:raise SystemExit(f'{n}: wrong ELF machine ({machine}); host library accidentally packaged?')
        phoff=struct.unpack_from('<Q',b,32)[0];entsize,num=struct.unpack_from('<HH',b,54)
        load=0
        for i in range(num):
            off=phoff+i*entsize
            if struct.unpack_from('<I',b,off)[0]==1:
                load+=1;alignment=struct.unpack_from('<Q',b,off+48)[0]
                if alignment<16384:raise SystemExit(f'{n}: LOAD alignment {alignment} < 16384')
        if not load:raise SystemExit(f'{n}: no LOAD segments')
        print('PASS',n,'ELF machine',machine,'16KiB LOAD alignment')
