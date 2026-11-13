#!/usr/bin/env python3
"""Prepare read-only local Boost/OpenSSL inputs for the existing Android toolchain.
Never fetches, checks out, patches or builds in the supplied repositories.
"""
import argparse, hashlib, io, json, os, pathlib, re, shutil, subprocess, tarfile, tempfile
Path=pathlib.Path

def git(root,*args):
    return subprocess.check_output(['git','-C',str(root),*args],stderr=subprocess.PIPE)

def repo(path):
    root=Path(path).expanduser().resolve(strict=True)
    top=Path(os.fsdecode(git(root,'rev-parse','--show-toplevel')).strip()).resolve()
    if root!=top:raise ValueError(f'Point to the Git repository root: {top}')
    if any(c in str(root) for c in '\n\r;'):raise ValueError('Source path cannot contain newline or semicolon')
    return root

def boost_info(root):
    candidates=[root/'boost/version.hpp',root/'libs/config/include/boost/version.hpp']
    version_file=next((p for p in candidates if p.is_file()),None)
    if not version_file:raise ValueError('Boost version.hpp missing; initialize Boost submodules')
    match=re.search(r'#\s*define\s+BOOST_VERSION\s+(\d+)',version_file.read_text())
    if not match or int(match[1])<108300:raise ValueError('Boost >= 1.83 is required')
    include_dirs=[root] if (root/'boost/asio.hpp').is_file() else sorted({*root.glob('libs/*/include'),*root.glob('libs/numeric/*/include')})
    for name in ['asio.hpp','beast.hpp','json.hpp','json/src.hpp','config.hpp']:
        if not any((p/'boost'/name).is_file() for p in include_dirs):
            raise ValueError(f'Missing boost/{name}; run git submodule update --init --recursive in your Boost clone')
    return {'version':int(match[1]),'commit':os.fsdecode(git(root,'rev-parse','HEAD')).strip()}

def openssl_info(root):
    for name in ['Configure','Configurations/15-android.conf','VERSION.dat','LICENSE.txt']:
        if not (root/name).is_file():raise ValueError(f'OpenSSL source missing {name}; OpenSSL >=3 Git root required')
    text=(root/'VERSION.dat').read_text()
    parts=[]
    for key in ['MAJOR','MINOR','PATCH']:
        m=re.search(r'^'+key+r'\s*=\s*(\d+)\s*$',text,re.M)
        if not m:raise ValueError('Cannot parse OpenSSL VERSION.dat')
        parts.append(int(m[1]))
    if parts[0]<3:raise ValueError('OpenSSL >=3 required by this Android adapter; 1.x is not supported')
    return {'version':'.'.join(map(str,parts)), 'commit':os.fsdecode(git(root,'rev-parse','HEAD')).strip()}

def snapshot(root,destination):
    """Content-addressed deterministic tar of tracked working-tree files, not build artifacts."""
    entries=[p for p in git(root,'ls-files','--stage','-z').split(b'\0') if p]
    paths=[]
    for entry in entries:
        metadata,name=entry.split(b'\t',1)
        mode,oid,stage=metadata.split()
        if stage!=b'0':raise ValueError('Resolve OpenSSL Git merge conflicts before building')
        if mode==b'160000':continue # Optional OpenSSL test/interop submodules; port uses no-tests.
        paths.append(name)
    paths=sorted(paths)
    if not paths:raise ValueError('No tracked source files')
    destination.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix='snapshot-',suffix='.tar',dir=destination);os.close(fd)
    try:
        with tarfile.open(tmp,'w',format=tarfile.PAX_FORMAT) as tf:
            for raw in paths:
                name=os.fsdecode(raw); p=root/name
                # Missing tracked files/escaping symlinks are errors, never silently omit sources.
                resolved=p.resolve(strict=True)
                if not resolved.is_relative_to(root) or not resolved.is_file():raise ValueError(f'Unsupported tracked file: {name}')
                data=p.read_bytes();info=tarfile.TarInfo('openssl-local/'+name.replace('\\','/'))
                info.size=len(data);info.mode=0o755 if os.access(p,os.X_OK) else 0o644
                tf.addfile(info,io.BytesIO(data))
        digest=hashlib.sha256(Path(tmp).read_bytes()).hexdigest()
        out=destination/(digest+'.tar')
        if out.exists():Path(tmp).unlink()
        else:os.replace(tmp,out)
        return out,digest
    finally:
        if Path(tmp).exists():Path(tmp).unlink()

def write_selection(root,boost,prefix):
    path=root/'.native-deps/selection.properties';path.parent.mkdir(parents=True,exist_ok=True)
    # java.util.Properties accepts forward slashes and escaped colons/spaces.
    def escape(p):return str(p).replace('\\','/').replace(':','\\:').replace(' ','\\ ').replace('=','\\=').replace('#','\\#').replace('!','\\!')
    text='cfBoostSource='+escape(boost)+'\ncfNativeDeps='+escape(prefix)+'\n'
    # ASCII escapes also support non-ASCII Windows directory names.
    encoded=text.encode('utf-16-be')
    units=[int.from_bytes(encoded[i:i+2], 'big') for i in range(0,len(encoded),2)]
    path.write_text(''.join(chr(u) if u<128 else '\\u%04x'%u for u in units), encoding='ascii')

