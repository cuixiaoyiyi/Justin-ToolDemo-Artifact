"""Inspect, verify, and selectively restore the frozen evidence; no code is run."""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path, PurePosixPath
import zipfile

def entries(archive):
    with archive.open('paths.jsonl') as stream:
        for line in stream:
            yield json.loads(line)

def safe_target(destination, relative):
    logical=PurePosixPath(relative)
    if logical.is_absolute() or '..' in logical.parts:
        raise ValueError('Unsafe archive path')
    target=(destination/relative).resolve()
    if not target.is_relative_to(destination):
        raise ValueError('Path escapes destination')
    return target

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['inspect','verify','list','restore'])
    parser.add_argument('--archive',type=Path,default=Path(__file__).resolve().parents[1]/'release-assets/raw-evidence.zip')
    parser.add_argument('--unit',help='Exact frozen method ID, e.g. s0001')
    parser.add_argument('--tool',choices=['justin-llm','evosuite','chatunitest','chattester','hits'])
    parser.add_argument('--prefix',help='Logical path prefix, e.g. runtime-root/usage.jsonl')
    parser.add_argument('--all',action='store_true',help='Explicitly permit restoring the entire archive')
    parser.add_argument('--destination',type=Path)
    args=parser.parse_args()
    if args.tool and not args.unit:parser.error('--tool requires --unit')
    if args.action=='restore' and not(args.all or args.unit or args.prefix):
        parser.error('Choose --unit, --prefix, or --all; full restoration contains many files')
    if args.action=='restore' and args.destination is None:parser.error('--destination is required')
    with zipfile.ZipFile(args.archive) as archive:
        metadata=json.loads(archive.read('manifest.json'))
        if args.action=='inspect':print(json.dumps(metadata,indent=2));return
        if args.action=='verify':
            count=0
            for item in archive.infolist():
                if not item.filename.startswith('objects/'):continue
                expected=item.filename.rsplit('/',1)[-1]
                assert hashlib.sha256(archive.read(item)).hexdigest()==expected,item.filename
                count+=1
            assert count==metadata['content_objects']
            # Also check every streamed mapping resolves to a retained object.
            members=set(archive.namelist());logical=0
            for relative,digest in entries(archive):
                logical_path=PurePosixPath(relative)
                if logical_path.is_absolute() or '..' in logical_path.parts:
                    raise ValueError('Unsafe archive path')
                assert f'objects/{digest[:2]}/{digest}' in members
                logical+=1
            assert logical==metadata['logical_files']
            print(json.dumps({'verified_objects':count,'verified_paths':logical,'passed':True}));return
        count=0
        destination=args.destination.resolve() if args.destination else None
        for relative,digest in entries(archive):
            parts=PurePosixPath(relative).parts
            if args.unit and not(len(parts)>4 and parts[:2]==('runtime-root','runs') and parts[4]==args.unit):continue
            if args.tool and parts[3]!=args.tool:continue
            if args.prefix and not relative.startswith(args.prefix):continue
            if args.action=='list':print(relative)
            else:
                blob=archive.read(f'objects/{digest[:2]}/{digest}')
                assert hashlib.sha256(blob).hexdigest()==digest
                target=safe_target(destination,relative)
                if target.exists():
                    if hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
                        raise ValueError('Refusing to overwrite different bytes: '+str(target))
                else:
                    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(blob)
            count+=1
        if not count:raise SystemExit('No matching paths')
        if args.action=='restore':print(json.dumps({'restored_files':count,'destination':str(destination)}))

if __name__=='__main__':main()
