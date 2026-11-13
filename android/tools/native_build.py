#!/usr/bin/env python3
"""Offline dependency build. Requires Python >=3.10, Git, Bash, Perl, GNU make."""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, sys, tarfile
from local_sources import repo, boost_info, openssl_info, snapshot, write_selection
Path=pathlib.Path
ABIS={'arm64-v8a':'arm64-android-cftunnel','x86_64':'x64-android-cftunnel'}

def cache_key(digest,ndk,abi,driver,versions):
    data={'source':digest,'ndk_path':str(ndk),'ndk_revision':(ndk/'source.properties').read_text(),
          'abi':abi,'api':26,'driver':driver,'tools':versions}
    return hashlib.sha256(json.dumps(data,sort_keys=True).encode()).hexdigest()

def ready(prefix):
    return all((prefix/f).is_file() and (prefix/f).stat().st_size for f in
               ['lib/libssl.a','lib/libcrypto.a','include/openssl/ssl.h'])

def shell_command(bash,script,args):
    if os.name=='nt':
        return [bash,'--noprofile','--norc','-c','exec /usr/bin/bash "$(/usr/bin/cygpath -u "$1")" "${@:2}"',
                'cf-native',str(script),*map(str,args)]
    return [bash,str(script),*map(str,args)]

def main():
    ap=argparse.ArgumentParser()
    for n in ['boost','openssl','ndk']:ap.add_argument('--'+n,required=True)
    ap.add_argument('--bash',default='bash');ap.add_argument('--abis',default='arm64-v8a')
    ap.add_argument('--jobs',type=int,default=4);ap.add_argument('--work-root',type=Path)
    ap.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
    ap.add_argument('--check',action='store_true')
    args=ap.parse_args();root=args.project.resolve();ndk=Path(args.ndk).resolve(strict=True)
    boost=repo(args.boost);openssl=repo(args.openssl)
    bi=boost_info(boost);oi=openssl_info(openssl)
    if not (boost/'LICENSE_1_0.txt').is_file():raise ValueError('Missing Boost LICENSE_1_0.txt')
    abis=list(dict.fromkeys(x.strip() for x in args.abis.split(',')))
    if any(a not in ABIS for a in abis):raise ValueError('Supported ABIs: arm64-v8a,x86_64')
    if not 1<=args.jobs<=128:raise ValueError('--jobs must be 1..128')
    revision=(ndk/'source.properties').read_text()
    import re
    match=re.search(r'Pkg\.Revision\s*=\s*(\d+)',revision)
    if not match or int(match[1])<28:raise ValueError('NDK r28 or later required')
    work=(args.work_root or root/'.native-deps/direct').resolve()
    # Upstream OpenSSL Makefiles do not reliably quote these paths.
    for p in [work,ndk]:
        if re.search(r'[\s$#;\'"`]',str(p)):raise ValueError('NDK/work path must not contain whitespace or shell metacharacters; use --work-root: '+str(p))
    script=root/'tools/openssl-build.sh'
    env=os.environ.copy()
    for key in ['BASH_ENV','ENV','CDPATH','MAKEFLAGS','MFLAGS']:env.pop(key,None)
    versions=subprocess.check_output(shell_command(args.bash,script,['--check']),env=env,text=True)
    print(f'[Local] Boost {bi["version"]}; OpenSSL {oi["version"]}; NDK {ndk}; ABI {",".join(abis)}',flush=True)
    print(versions.strip(),flush=True)
    if args.check:
        print('[Native] Prerequisites checked; no dependency download or compilation.');return
    cache=root/'.native-deps';cache.mkdir(exist_ok=True)
    lock=cache/'direct-build.lock'
    try:lock.mkdir()
    except FileExistsError:raise ValueError('Native build lock exists. Stop other builds; remove '+str(lock)+' only after confirming none are running.')
    work_lock=None
    try:
        work.mkdir(parents=True,exist_ok=True)
        candidate=work/'.build.lock'
        try:candidate.mkdir()
        except FileExistsError:raise ValueError('Native work directory is locked: '+str(candidate))
        work_lock=candidate
        archive,digest=snapshot(openssl,work/'archives')
        driver=hashlib.sha256(script.read_bytes()+Path(__file__).read_bytes()+
                              (root/'tools/local_sources.py').read_bytes()).hexdigest()
        install=cache/'installed-direct'
        for abi in abis:
            key=cache_key(digest,ndk,abi,driver,versions)
            build=work/abi/key;prefix=build/'prefix';marker=build/'success.json'
            if not (marker.is_file() and ready(prefix)):
                if build.exists():shutil.rmtree(build)
                build.mkdir(parents=True)
                with tarfile.open(archive) as tf:tf.extractall(build)
                command=shell_command(args.bash,script,[build/'openssl-local',ndk,prefix,abi,args.jobs])
                print('[Native] Building local OpenSSL for '+abi,flush=True)
                # Retain full output without buffering it all in memory.
                with (build/'build.log').open('w',encoding='utf-8') as log:
                    proc=subprocess.Popen(command,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors='replace')
                    for line in proc.stdout:print(line,end='',flush=True);log.write(line)
                    if proc.wait():raise ValueError('OpenSSL build failed; see '+str(build/'build.log'))
                if not ready(prefix):raise ValueError('OpenSSL build produced incomplete archives')
                marker.write_text(json.dumps({'key':key,'openssl':oi,'ndk':revision,'abi':abi},indent=2))
            else:print('[Native] Reusing completed local OpenSSL build: '+abi,flush=True)
            dest=install/ABIS[abi];install.mkdir(exist_ok=True)
            staging=install/(ABIS[abi]+'.pending')
            if staging.exists():shutil.rmtree(staging)
            shutil.copytree(prefix,staging)
            shutil.copy2(marker,staging/'build-info.json')
            if dest.exists():shutil.rmtree(dest)
            staging.rename(dest)
        assets=root/'app/src/main/assets';assets.mkdir(parents=True,exist_ok=True)
        (assets/'native-licenses.txt').write_text('Boost\n'+(boost/'LICENSE_1_0.txt').read_text()+
            '\nOpenSSL\n'+(openssl/'LICENSE.txt').read_text(),encoding='utf-8')
        write_selection(root,boost,install)
        print('[Native] Local Android dependencies ready; no vcpkg used.',flush=True)
    finally:
        if work_lock is not None:work_lock.rmdir()
        lock.rmdir()

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.CalledProcessError) as e:sys.exit('Native build failed: '+str(e))
