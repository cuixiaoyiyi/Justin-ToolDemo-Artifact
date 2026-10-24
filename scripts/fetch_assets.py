"""Download checksum-pinned release assets, optionally restoring the runtime inputs."""
from __future__ import annotations
import argparse, hashlib, json, subprocess, urllib.request, zipfile
from pathlib import Path, PurePosixPath

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only',choices=['runtime-inputs.zip','raw-evidence.zip'])
    parser.add_argument('--extract-inputs',action='store_true')
    parser.add_argument('--gh',action='store_true',help='Use an authenticated GitHub CLI instead of HTTPS')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    manifest=json.loads((root/'assets.json').read_text(encoding='utf-8'))
    target=root/'release-assets';target.mkdir(exist_ok=True)
    for item in manifest['assets']:
        if args.only and item['name']!=args.only:continue
        path=target/item['name']
        if not path.exists() or digest(path)!=item['sha256']:
            partial=path.with_suffix(path.suffix+'.part')
            if args.gh:
                subprocess.run(['gh','release','download',manifest['tag'],'--repo',manifest['repository'],'--dir',str(target),'--pattern',item['name'],'--clobber'],check=True)
            else:
                request=urllib.request.Request(item['url'],headers={'User-Agent':'Justin-Artifact/1.0'})
                with urllib.request.urlopen(request,timeout=120) as response, partial.open('wb') as out:
                    while chunk:=response.read(1024*1024):out.write(chunk)
                if digest(partial)!=item['sha256']:raise ValueError('Download checksum mismatch: '+item['name'])
                partial.replace(path)
        if path.stat().st_size!=item['bytes'] or digest(path)!=item['sha256']:
            raise ValueError('Release asset checksum mismatch: '+item['name'])
        print('Verified',item['name'])
    if args.extract_inputs:
        path=target/'runtime-inputs.zip'
        if not path.exists():raise SystemExit('Download runtime-inputs.zip first')
        with zipfile.ZipFile(path) as archive:
            for item in archive.infolist():
                logical=PurePosixPath(item.filename)
                output=(root/item.filename).resolve()
                if logical.is_absolute() or '..' in logical.parts or not output.is_relative_to(root) or logical.parts[0]!='inputs':
                    raise ValueError('Unsafe runtime-input path')
                blob=archive.read(item)
                if output.exists() and output.read_bytes()!=blob:
                    raise ValueError('Refusing to overwrite different input: '+str(output))
                output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(blob)
        print('Runtime inputs restored under',root/'inputs')

if __name__=='__main__':main()
