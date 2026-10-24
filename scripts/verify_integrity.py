"""Verify tracked content, optional frozen binary inputs, and release archives."""
from __future__ import annotations
import argparse,hashlib,json
from pathlib import Path

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--inputs',action='store_true')
    ap.add_argument('--assets',action='store_true')
    args=ap.parse_args()
    root=Path(__file__).resolve().parents[1]
    checked=0
    for line in (root/'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        expected,relative=line.split('  ',1)
        path=(root/relative).resolve()
        if not path.is_relative_to(root):raise ValueError('Unsafe checksum path')
        if not path.is_file() or sha(path)!=expected:raise ValueError('Checksum mismatch: '+relative)
        checked+=1
    if args.inputs:
        manifest=json.loads((root/'input-manifest.json').read_text(encoding='utf-8'))
        for item in manifest['files']:
            path=root/item['path']
            if path.stat().st_size!=item['bytes'] or sha(path)!=item['sha256']:
                raise ValueError('Frozen binary checksum mismatch: '+item['path'])
            checked+=1
    if args.assets:
        manifest=json.loads((root/'assets.json').read_text(encoding='utf-8'))
        for item in manifest['assets']:
            path=root/'release-assets'/item['name']
            if path.stat().st_size!=item['bytes'] or sha(path)!=item['sha256']:
                raise ValueError('Release checksum mismatch: '+item['name'])
            checked+=1
    print(json.dumps({'passed':True,'files_checked':checked,'frozen_inputs':args.inputs,'release_assets':args.assets}))

if __name__=='__main__':main()
