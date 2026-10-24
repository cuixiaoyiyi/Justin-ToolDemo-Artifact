#!/usr/bin/env python3
"""Prepare or rerun the fixed72 cohort in a new Linux/WSL workspace.

Python3.10+, Linux/WSL2, JDK17/JDK8 and Maven are required for generation.
--help and manifest checks work without importing Linux runner modules.
Credentials are read only from the process environment by the local proxy.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

EXPECTED_SUBJECTS = {'commons-text-1.15.0', 'commons-lang3-3.17.0',
    'commons-collections4-4.6.0', 'jline-3.21.0', 'jsoup-1.23.2'}
REQUIRED_TOOLS = ('evosuite-1.2.0.jar', 'evosuite-standalone-runtime-1.2.0.jar',
    'junit-platform-console-standalone-1.12.2.jar', 'junit-4.13.2.jar',
    'hamcrest-core-1.3.jar', 'jacococli.jar', 'jacocoagent.jar')
JUSTIN_SHA = 'acc1227b2e0fbcb620107af99b77b64224d23703900a78f08887a3e9cbc38aed'
SAMPLE_SHA = '02e0f59f9f8174fab9f5a728fecfefe1d7c810396a8c9c796e2c3f5b34e81cf4'
SELECTION_SHA = '6c66fea195280c83c7782f127775476b09f76964b9a44108af1299784f440bb0'
HERE = Path(__file__).resolve().parent


def read(p):
    return json.loads(Path(p).read_text(encoding='utf-8'))


def write(p, value):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def sha(p):
    h = hashlib.sha256()
    with Path(p).open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def pinned_copy(source, destination, expected=None):
    source, destination = Path(source), Path(destination)
    actual = sha(source)
    if expected and actual != expected:
        raise ValueError('Input checksum differs: '+str(source))
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if sha(destination) != actual:
            raise ValueError('Refusing to replace different existing input: '+str(destination))
    else:
        shutil.copy2(source, destination)
    return dict(path=str(destination), sha256=actual, bytes=destination.stat().st_size)


def resolve(artifact, value):
    path = Path(value)
    return path.resolve() if path.is_absolute() else (artifact/path).resolve()


def config_path(root):
    return Path(root).resolve()/'artifact-config.json'


def configure(root):
    path = config_path(root)
    cfg = read(path)
    if Path(cfg['root']).resolve() != Path(root).resolve():
        raise ValueError('A prepared workspace cannot be relocated; prepare a new one')
    os.environ['JUSTIN_ARTIFACT_CONFIG'] = str(path)
    return cfg


def linux_only():
    if not sys.platform.startswith('linux'):
        raise SystemExit('Generation requires Linux or WSL2. Run this entrypoint with python3 inside WSL.')


def validate_inputs(artifact, manifest, sample):
    if sample.get('selection_sha256') != SELECTION_SHA:
        raise ValueError('Frozen target selection checksum differs')
    entries=manifest.get('files',[])
    if not entries:
        raise ValueError('Input manifest needs SHA256 entries for the bundled tools and dependencies')
    pinned_paths={resolve(artifact,e['path']):e['sha256'] for e in entries}
    for path,expected in pinned_paths.items():
        if sha(path) != expected:
            raise ValueError('Bundled input checksum differs: '+str(path))
    subjects = manifest['subjects']
    if {s['sid'] for s in subjects} != EXPECTED_SUBJECTS or len(subjects) != 5:
        raise ValueError('Inputs must contain exactly the five frozen subject versions')
    if len(sample['rows']) != 72 or {r['subject'] for r in sample['rows']} != EXPECTED_SUBJECTS:
        raise ValueError('Frozen sample must contain the unchanged 72 target identities')
    if sum(r['expected_denominator']['LINE'] for r in sample['rows']) != 777:
        raise ValueError('Unexpected line denominator')
    if sum(r['expected_denominator']['BRANCH'] for r in sample['rows']) != 722:
        raise ValueError('Unexpected branch denominator')
    for subject in subjects:
        pinned = sample['subjects'][subject['sid']]
        for kind in ('jar', 'sources'):
            path = resolve(artifact, subject[kind])
            if sha(path) != pinned[kind+'_sha256']:
                raise ValueError('Frozen subject '+kind+' changed: '+subject['sid'])
        for path in subject.get('runtime_dependencies', []):
            if resolve(artifact,path) not in pinned_paths:
                raise ValueError('Dependency has no pinned SHA256: '+str(path))
    tools = resolve(artifact, manifest['tools_dir'])
    for name in REQUIRED_TOOLS:
        if (tools/name).resolve() not in pinned_paths:
            raise ValueError('Tool has no pinned SHA256: '+str(tools/name))
    if sha(resolve(artifact, manifest.get('justin_jar', str(Path(manifest['tools_dir'])/'justin-all.jar')))) != JUSTIN_SHA:
        raise ValueError('Justin JAR differs from the evaluated binary')
    return subjects, tools


def prepare(args):
    linux_only()
    root = args.root.resolve()
    artifact = args.artifact.resolve()
    data = args.data_dir.resolve() if args.data_dir else artifact/'data'
    manifest_path = args.inputs_manifest.resolve() if args.inputs_manifest else artifact/'input-manifest.json'
    manifest = read(manifest_path)
    if sha(data/'frozen-sample.json') != SAMPLE_SHA:
        raise ValueError('Frozen sample file SHA256 differs from the archived 72-method selection')
    sample = read(data/'frozen-sample.json')
    subjects, tools = validate_inputs(artifact, manifest, sample)
    # Completed or partial runs are immutable; preparation only targets a fresh workspace.
    if root.exists() and any(root.iterdir()):
        raise ValueError('Prepare needs a new empty workspace; preserve previous runs and choose another --root')
    for home in (args.jdk17, args.jdk8):
        if not (home/'bin/java').is_file() or not (home/'bin/javac').is_file():
            raise ValueError('Expected java and javac in '+str(home/'bin'))
    maven = shutil.which(args.maven)
    if not maven:
        raise ValueError('Maven executable unavailable: '+args.maven)
    root.mkdir(parents=True, exist_ok=True)
    meta = root/'harness-metadata'
    meta.mkdir()
    for name in ('frozen-sample.json', 'implementation.json', 'native-selector-owner-map.json'):
        pinned_copy(data/name, meta/name)
    cfg = dict(schema='justin-artifact-portable-rerun-v1', root=str(root),
        artifact=str(artifact), data_dir=str(data), metadata_dir=str(meta),
        tools_dir=str(root/'tools'), jdk17=str(args.jdk17.resolve()), jdk8=str(args.jdk8.resolve()),
        maven=maven, maven_repo=str(args.maven_repo.resolve()), offline=args.offline, port=args.port,
        frozen_selection_sha256=sample['selection_sha256'], input_manifest=str(manifest_path),
        input_manifest_sha256=sha(manifest_path), credentials_stored=False)
    write(config_path(root),cfg)
    configure(root)
    copied=[]
    for name in REQUIRED_TOOLS:
        copied.append(pinned_copy(tools/name, root/'tools'/name))
    copied.append(pinned_copy(resolve(artifact, manifest.get('justin_jar', str(Path(manifest['tools_dir'])/'justin-all.jar'))), root/'justin-all.jar', JUSTIN_SHA))
    # Install exact native plugin/core/starter files into a caller-selected Maven repository.
    # Missing transitive Maven dependencies are resolved during static parse unless --offline.
    if manifest.get('native_maven_dir'):
        native = resolve(artifact, manifest['native_maven_dir'])
        if not native.is_dir():
            raise FileNotFoundError(native)
        for path in sorted(native.rglob('*')):
            if path.is_file() and path.suffix in {'.jar','.pom'}:
                copied.append(pinned_copy(path, args.maven_repo/path.relative_to(native)))
    prepared=[]
    for subject in subjects:
        sid=subject['sid']
        binary=pinned_copy(resolve(artifact,subject['jar']),root/'subjects'/(sid+'.jar'), sample['subjects'][sid]['jar_sha256'])
        source=pinned_copy(resolve(artifact,subject['sources']),root/'subjects'/(sid+'-sources.jar'),sample['subjects'][sid]['sources_sha256'])
        deps=[]
        for value in subject.get('runtime_dependencies',[]):
            origin=resolve(artifact,value)
            deps.append(pinned_copy(origin,root/'subjects/dependencies'/origin.name))
        cp=':'.join(d['path'] for d in deps)
        entry=dict(sid=sid,id=sid,root=str(root),jar=binary['path'],sources=source['path'],
            runtime_cp=cp,runtime_dependencies=deps,jar_sha256=binary['sha256'],sources_sha256=source['sha256'],
            frozen_methods=[r for r in sample['rows'] if r['subject']==sid])
        prepared.append(entry)
        copied.extend([binary,source,*deps])
    write(meta/'prepared-subjects.json',dict(subjects=prepared))
    write(root/'artifact-inputs.json',dict(files=copied,model_requests=0,test_generations=0))
    import benchmark
    all_subjects=benchmark.prepare()
    # JaCoCo analyzes unchanged class files without executing the subjects.
    from measurement import _denominator
    baselines=[]
    for row in sample['rows']:
        base=_denominator(Path(all_subjects[row['subject']]['analysis_jar']),row)
        if any(base['counters'][k] != row['expected_denominator'].get(k,0)
               for k in ('LINE','BRANCH','METHOD','INSTRUCTION','COMPLEXITY')):
            raise ValueError('Static denominator differs from frozen sample: '+row['method_id'])
        baselines.append(dict(method_id=row['method_id'],subject=row['subject'],
            descriptor=row['descriptor'],**base))
    write(root/'denominators/frozen72-baselines.json',baselines)
    write(root/'preparation-complete.json',dict(status='ready',methods=72,tools=5,units=360,
        model_requests=0,test_generations=0,subject_test_executions=0,
        setup_note='Native static parse and dependency preparation are outside timed generation'))
    print(json.dumps(dict(status='ready',root=str(root),methods=72,units=360)))


def run(args):
    linux_only()
    cfg=configure(args.root)
    if not (args.root/'preparation-complete.json').is_file():
        raise ValueError('Preparation is incomplete; inspect its logs, then use a fresh workspace')
    if not os.environ.get('JUSTIN_LLM_API_KEY') or not os.environ.get('JUSTIN_LLM_BASE_URL'):
        raise ValueError('Set JUSTIN_LLM_API_KEY and JUSTIN_LLM_BASE_URL in the environment')
    import benchmark, llm_proxy
    try:
        benchmark.run()
    finally:
        # Drain owned outstanding attempts so late usage remains recorded.
        llm_proxy.stop()
    summarize(args)


def summarize(args):
    cfg=configure(args.root)
    import report72, export72_csv
    result=report72.collect(args.root)
    out=args.root/'reports'
    out.mkdir(parents=True,exist_ok=True)
    if not result['final_ready']:
        write(out/'benchmark72-progress.json',result)
        raise ValueError('Fresh report is incomplete or needs audit: '+result['status']+'; inspect '+str(out/'benchmark72-progress.json'))
    write(out/'benchmark72.json',result)
    (out/'benchmark72.html').write_text(report72.render(result),encoding='utf-8')
    export72_csv.validate(result,final=True)
    outputs=[export72_csv.export_csv(out/'sample72.csv',export72_csv.SAMPLE_FIELDS,export72_csv.sample_rows(result)),
        export72_csv.export_csv(out/'results360.csv',export72_csv.RESULT_FIELDS,export72_csv.result_rows(result)),
        export72_csv.export_csv(out/'summary72.csv',export72_csv.SUMMARY_FIELDS,export72_csv.summary_rows(result))]
    write(out/'fresh-report-validation.json',dict(status='passed',source_sha256=sha(out/'benchmark72.json'),
        files=outputs,methods=72,units=360,archived_results_overwritten=False))
    print(json.dumps(dict(status=result['status'],completed=result['completed_units'],reports=str(out))))


def doctor(args):
    cfg=configure(args.root)
    manifest=read(cfg['input_manifest'])
    sample_path=Path(cfg['data_dir'])/'frozen-sample.json'
    if sha(sample_path) != SAMPLE_SHA:
        raise ValueError('Frozen sample file SHA256 differs')
    sample=read(sample_path)
    validate_inputs(Path(cfg['artifact']),manifest,sample)
    report=dict(status='passed',platform=sys.platform,linux_required_for_generation=True,
        methods=72,tools=5,units=360,credentials_stored=False,
        checks=['five frozen subject versions','exact 72 descriptors','777 line/722 branch denominator',
            'subject/source SHA256','frozen Justin binary SHA256','all bundled tools/native-Maven/runtime-dependency SHA256'])
    for key in ('jdk17','jdk8'):
        report[key+'_present']=all((Path(cfg[key])/'bin'/exe).is_file() for exe in ('java','javac'))
    report['maven_present']=bool(shutil.which(cfg['maven']))
    if not all(report[key+'_present'] for key in ('jdk17','jdk8')) or not report['maven_present']:
        report['status']='failed'
        print(json.dumps(report,indent=2))
        raise SystemExit(1)
    print(json.dumps(report,indent=2))


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    sub=ap.add_subparsers(dest='action',required=True)
    p=sub.add_parser('prepare',help='Prepare five pinned projects; no model requests or generation')
    p.add_argument('--artifact',type=Path,default=HERE.parents[1])
    p.add_argument('--data-dir',type=Path)
    p.add_argument('--inputs-manifest',type=Path)
    p.add_argument('--root',type=Path,required=True)
    p.add_argument('--jdk17',type=Path,required=True)
    p.add_argument('--jdk8',type=Path,required=True)
    p.add_argument('--maven',default='mvn')
    p.add_argument('--maven-repo',type=Path,default=Path.home()/'.m2/repository')
    p.add_argument('--offline',action='store_true',help='Refuse Maven network resolution; needs a warm dependency cache')
    p.add_argument('--port',type=int,default=8772)
    p.set_defaults(function=prepare)
    p=sub.add_parser('run',help='Generate and measure all five tools x72; uses the configured paid model service')
    p.add_argument('--root',type=Path,required=True)
    p.set_defaults(function=run)
    p=sub.add_parser('summarize',help='Read only fresh run evidence and export coverage/cost/case tables under its reports directory')
    p.add_argument('--root',type=Path,required=True)
    p.set_defaults(function=summarize)
    p=sub.add_parser('doctor',help='Read-only configured-input checks; no Java, model or test runs')
    p.add_argument('--root',type=Path,required=True)
    p.set_defaults(function=doctor)
    args=ap.parse_args()
    args.root=args.root.resolve()
    args.function(args)


if __name__=='__main__':
    main()
