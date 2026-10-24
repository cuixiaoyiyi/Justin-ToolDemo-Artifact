"""Frozen, resumable exact-method rerun of the original72 cohort.

Run under WSL. All generated artifacts are isolated from the October 17 runs.
"""
from __future__ import annotations
import argparse, concurrent.futures as futures, fcntl, hashlib, json, os
from pathlib import Path
import re, shutil, signal, subprocess, time, zipfile

from portable_config import CONFIG as CFG
HERE=Path(CFG['metadata_dir'])
REPO=Path(__file__).resolve().parent
ROOT=Path(CFG['root'])
TOOLS=Path(CFG['tools_dir'])
JAVA=Path(CFG['jdk17'])/'bin/java'
JDK8=Path(CFG['jdk8'])/'bin/java'
MAVEN=CFG['maven']
MAVEN_REPO=Path(CFG['maven_repo'])
TOOL_NAMES=['justin-llm','evosuite','chatunitest','chattester','hits']
PORT=CFG['port']
CAP=900

def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def dump(p,value):
    p=Path(p);p.parent.mkdir(parents=True,exist_ok=True)
    tmp=p.with_name(p.name+'.tmp');tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2));tmp.replace(p)
def load(p): return json.loads(Path(p).read_text())
def decision(kind,**details):
    ROOT.mkdir(parents=True,exist_ok=True)
    with (ROOT/'decisions.jsonl').open('a') as f:
        f.write(json.dumps(dict(unix=time.time(),kind=kind,**details),ensure_ascii=False)+'\n')

def freeze():
    raw=load(HERE/'frozen-sample.json')
    rows=raw['rows']
    assert len(rows)==72 and all(r.get('descriptor') for r in rows)
    assert {k:sum(r['role']==k for r in rows) for k in ['core','state','thin']}=={'core':72,'state':0,'thin':0}
    proto=dict(raw['protocol'],workers=8,external_measure_workers=2,
        memory_reservation_gib=24,generation_reservation_gib=3,measurement_reservation_gib=6,
        reuse_old_results=False,old_results_policy='All360 method/tool cells receive fresh generation and measurement. Original72 class-level and341 results remain separate immutable records.',
        setup_costs='All setup-/preflight- method labels accounted separately, never charged as primary generation or silently discarded.')
    impl=load(HERE/'implementation.json')
    proto['tools_provenance']=impl['tools_provenance']
    proto['implementation_decisions']=impl
    document=dict(raw,protocol=proto)
    dest=ROOT/'frozen-sample.json'
    if dest.exists():
        if load(dest)!=document:raise RuntimeError('Frozen manifest/protocol changed')
    else:
        dump(dest,document)
        decision('SAMPLE_FROZEN',methods=72,subjects=5,roles=raw['role_counts'],
                 sha256=sha(dest),source_selection_sha256=raw['selection_sha256'])
    dump(ROOT/'protocol.json',proto);dump(HERE/'protocol.json',proto)
    assert sha(ROOT/'justin-all.jar')=='acc1227b2e0fbcb620107af99b77b64224d23703900a78f08887a3e9cbc38aed'
    return document

def params(desc):
    a=1;result=[];types=dict(Z='boolean',B='byte',C='char',S='short',I='int',J='long',F='float',D='double')
    while desc[a]!=')':
        dim=0
        while desc[a]=='[':a+=1;dim+=1
        if desc[a]=='L':end=desc.index(';',a);name=desc[a+1:end].replace('/','.');a=end+1
        else:name=types[desc[a]];a+=1
        result.append(name+'[]'*dim)
    return result

def env(java=JAVA,**values):
    d=dict(os.environ,JAVA_HOME=str(java.parents[1]),MAVEN_OPTS='-Xmx2g',**values)
    d['PATH']=str(java.parent)+':'+d.get('PATH','');return d

def execute(args,log,cwd=None,environ=None,cap=CAP):
    start=time.time();tick=time.monotonic();log.parent.mkdir(parents=True,exist_ok=True)
    with log.open('w') as stream:
        p=subprocess.Popen([str(a) for a in args],stdout=stream,stderr=subprocess.STDOUT,
            cwd=cwd,env=environ or env(),start_new_session=True)
        dump(log.with_suffix('.process.json'),dict(pid=p.pid,pgid=p.pid,started_unix=start))
        timeout=False
        try:code=p.wait(timeout=cap)
        except subprocess.TimeoutExpired:
            timeout=True;os.killpg(p.pid,signal.SIGTERM)
            try:p.wait(timeout=10)
            except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
            code=124
    return dict(exit=code,seconds=round(time.monotonic()-tick,3),started_unix=start,
        finished_unix=time.time(),timeout=timeout,wall_cap_s=cap,command=[str(x) for x in args])

def merge_subjects():
    data={}
    files=['prepared-subjects.json']
    for fn in files:
        doc=load(HERE/fn)
        for raw in doc['subjects']:
            sid=raw.get('sid',raw.get('id',raw.get('subject')))
            d=dict(raw);d.update(raw.get('generator_project',{}));d['sid']=sid;d['id']=sid
            assert sid not in data,sid
            d['runtime_cp']=raw.get('runtime_cp',raw.get('runtimecp',[]))
            if isinstance(d['runtime_cp'],list):d['runtime_cp']=':'.join(d['runtime_cp'])
            if not d['runtime_cp'] and raw.get('runtimecp_file'):d['runtime_cp']=Path(raw['runtimecp_file']).read_text().strip()
            d['jar']=str(ROOT/'subjects'/(sid+'.jar'));d['sources']=str(ROOT/'subjects'/(sid+'-sources.jar'))
            derived=ROOT/'subjects'/(sid+'-analysis.jar')
            if not derived.exists():
                with zipfile.ZipFile(d['jar']) as source,zipfile.ZipFile(derived,'w',zipfile.ZIP_DEFLATED) as out:
                    for name in source.namelist():
                        if name.endswith('.class') and not name.startswith('META-INF/') and name!='module-info.class':out.writestr(name,source.read(name))
            d['analysis_jar']=str(derived)
            data[sid]=d
    needed={r['subject'] for r in load(HERE/'frozen-sample.json')['rows']}
    assert needed==set(data),(needed,set(data))
    return data

def method_number(row,meta):
    key=row['class']+'#'+row['method']+row['descriptor']
    if meta.get('exact_metadata'):
        exact=meta['exact_metadata'].get(key)
        if not exact:raise RuntimeError('No verified full binary descriptor mapping: '+key)
        if not exact['is_public'] or exact['is_abstract']:raise RuntimeError('Focal method not public concrete: '+key)
        return exact['metadata_id'],exact['method_signature']
    parsed=Path(meta['parsed_root'])
    matches=list(parsed.glob('**/class-info/'+row['class'].replace('.','/')+'/class.json'))
    if len(matches)!=1:raise RuntimeError('class metadata missing/ambiguous: '+str(row))
    obj=load(matches[0]);wanted=[p.replace('$','.').rsplit('.',1)[-1] for p in params(row['descriptor'])];found=[]
    for sig,number in obj['methodSigs'].items():
        m=load(matches[0].parent/(str(number)+'.json'))
        if m.get('methodName')!=row['method']:continue
        bounds=dict(re.findall(r'\b([A-Za-z_$][\w$]*)\s+extends\s+([\w.$]+)',obj.get('classSignature','')+'\n'+m.get('brief','')))
        # JavaParser's parameters list loses varargs dimensions; its canonical
        # methodSignature retains them (String... -> String[]).
        signature=m.get('methodSignature',sig)
        inside=signature[signature.find('(')+1:signature.rfind(')')]
        raw=[];depth=0;start=0
        for at,ch in enumerate(inside):
            if ch=='<':depth+=1
            elif ch=='>':depth-=1
            elif ch==',' and depth==0:raw.append(inside[start:at].strip());start=at+1
        if inside.strip():raw.append(inside[start:].strip())
        types=[re.sub(r'<.*>','',t).replace('...','[]').replace('$','.').rsplit('.',1)[-1] for t in raw]
        types=[bounds.get(t,t).replace('$','.').rsplit('.',1)[-1] for t in types]
        if types==wanted:found.append((number,sig))
    if len(found)!=1:raise RuntimeError('method metadata ambiguous: '+str((row,found)))
    return found[0]

def prepare():
    sample=freeze();subjects=merge_subjects()
    from generator_projects import prepare as project_prepare
    for sid,meta in subjects.items():
        # Static native parser output is reused; immutable SUT binaries never recompiled.
        meta['root']=str(ROOT);meta.pop('frozen_methods',None);meta.pop('methods',None)
        ready=project_prepare(meta)
        meta.update(ready);meta['class_metadata_root']=ready['parsed_root'];meta['parsed_root']=ready['tmp_root']
    owner=load(HERE/'native-selector-owner-map.json')
    owner_rows=owner.get('rows',owner.get('mappings',[]))
    if isinstance(owner_rows,dict):owner_rows=list(owner_rows.values())
    by_id={r['method_id']:r for r in sample['rows']}
    for a in owner_rows:
        if a.get('unsupported') or not a.get('native_selector_class'):continue
        r=by_id[a['method_id']];meta=subjects[r['subject']]
        flat=a['native_selector_class'];key=r['class']+'#'+r['method']+r['descriptor']
        paths=list(Path(meta['class_metadata_root']).glob('**/'+flat.replace('.','/')+'/class.json'))
        if not paths:
            p=Path(meta['class_metadata_root'])/(flat.replace('.','/')+'/class.json')
            paths=[p] if p.exists() else []
        assert len(paths)==1,(r,paths)
        info=load(paths[0]);number=int(a['metadata_id'])
        mp=paths[0].parent/(str(number)+'.json');mm=load(mp)
        assert mm['methodName']==r['method'] and info['fullClassName']==flat
        assert sha(mp)==a['native_method_metadata_sha256'],('native_alias_method_changed',r['method_id'])
        meta['exact_metadata'][key]=dict(metadata_id=number,method_signature=mm['methodSignature'],
            is_public=True,is_abstract=False,native_selector_class=flat,
            metadata_path=str(mp),metadata_sha256=sha(mp),class_metadata_path=str(paths[0]),
            adaptation='Verified source-body and binary nested-owner mapping; original parser context and prompts unchanged.',
            owner_evidence=a)
    selectors=[]
    for r in sample['rows']:
        meta=subjects[r['subject']];key=r['class']+'#'+r['method']+r['descriptor']
        exact=meta['exact_metadata'].get(key)
        if exact:
            number,sig=method_number(r,meta)
            selectors.append(dict(method_id=r['method_id'],number=number,signature=sig,
                 native_selector_class=exact.get('native_selector_class',r['class']),descriptor=r['descriptor'],supported=True))
        else:
            # A confirmed parser limitation is retained for every tool rather than dropping the focal method.
            unsupported_ids=owner.get('unsupported_method_ids',[x['method_id'] for x in owner.get('unsupported_enum_targets',[])])
            assert r['method_id'] in unsupported_ids,('unexplained_missing_native_metadata',r)
            selectors.append(dict(method_id=r['method_id'],descriptor=r['descriptor'],supported=False,
                 reason='Native ChatUniTest-family parser emits no method metadata for enum declaration.'))
    dump(ROOT/'subjects.json',subjects);dump(ROOT/'selectors.json',selectors);dump(HERE/'selectors.json',selectors)
    decision('PREPARATION_COMPLETE',subjects=len(subjects),selectors=len(selectors),
             supported_native=sum(s['supported'] for s in selectors),
             unsupported_native=[s for s in selectors if not s['supported']])
    return subjects

def unit(row,tool,meta):
    mid=row['method_id'];out=ROOT/'runs'/row['subject']/tool/mid;out.mkdir(parents=True,exist_ok=True)
    with (out/'generation.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if (out/'done.json').exists():return load(out/'done.json')
        if (out/'started.json').exists():raise RuntimeError('Unfinished prior generation requires explicit recovery: '+str(out))
        # Admission control leaves space for the serialized six-GiB test JVM.
        while int(re.search(r'MemAvailable:\s+(\d+)',Path('/proc/meminfo').read_text()).group(1))<8*1024*1024:time.sleep(5)
        if tool in {'chatunitest','chattester','hits'} and row['method_id'] in UNSUPPORTED_NATIVE:
            now=time.time();selector=row['class']+'#'+row['method']+row['descriptor']
            reason='Native parser emits no class/method metadata for enum target; no selectable method entry.'
            dump(out/'started.json',dict(unix=now,row=row,tool=tool,selector=selector,unsupported=True))
            (out/'generation.log').write_text(reason+'\n')
            r=dict(exit=125,seconds=0.0,started_unix=now,finished_unix=now,timeout=False,wall_cap_s=CAP,
                   command=[],subject=row['subject'],method_id=mid,tool=tool,selector=selector,
                   generated_java_files=0,tool_unsupported=True,unsupported_reason=reason)
            dump(out/'done.json',r);print('UNSUPPORTED',mid,tool,flush=True);return r
        cp=meta['runtime_cp'];jar=meta['jar'];selector=None;cwd=out;environ=env()
        if tool=='justin-llm':
            selector=row['class']+'#'+row['method']+'('+','.join(params(row['descriptor']))+')'
            config=out/'llm.properties'
            config.write_text('\n'.join([f'baseUrl=http://127.0.0.1:{PORT}/{tool}/{mid}',
                'model=deepseek-flash','apiKey=via-local-proxy','maxCallsPerRun=100000',
                'maxTokensPerCall=4096','timeoutSeconds=90','thinking=disabled',f'cacheDir={out}/llm-cache'])+'\n')
            args=[JAVA,'-Xmx3g','-jar',ROOT/'justin-all.jar','generate','--classes',jar,'--sources',meta['sources'],
                '--method',selector,'--threads','1','--sym-class-budget','20000','--seed','42',
                '--out',out/'out','--label','run','--run','--llm-config',config]
            if cp:args+=['--cp',cp]
        elif tool=='evosuite':
            environ=env(JDK8);selector=row['method']+row['descriptor']
            args=[JDK8,'-Xmx2g','-jar',TOOLS/'evosuite-1.2.0.jar','-class',row['class'],
                '-projectCP',str(jar)+(':'+cp if cp else ''),'-seed','42','-criterion','LINE:BRANCH',
                '-Dsearch_budget=60','-Dtarget_method='+selector,'-Duse_separate_classloader=false',
                '-Dshow_progress=false',f'-Dtest_dir={out}/tests',f'-Dreport_dir={out}/report']
        else:
            number,sig=method_number(row,meta);exact=meta['exact_metadata'][row['class']+'#'+row['method']+row['descriptor']];selector=exact.get('native_selector_class',row['class'])+'#'+str(number)
            child=out/'classes'/mid;child.mkdir(parents=True,exist_ok=True)
            shutil.copytree(meta['parsed_root'],child/'tmp')
            args=[MAVEN,'-Dmaven.repo.local='+str(MAVEN_REPO),'-B','-o','io.github.zju-aces-ise:chatunitest-maven-plugin:2.1.1:method',f'-DselectMethod={selector}',
                f'-DtestOutput={child}/tests',f'-DtmpOutput={child}/tmp','-DmaxResponseTokens=4096',
                '-DmaxThreads=1','-DenableMultithreading=false']
            if tool!='chatunitest':args.append('-DphaseType='+{'chattester':'CHATTESTER','hits':'HITS'}[tool])
            # Native clients whitelist model names. The local adapter replaces
            # this compatibility alias with the frozen actual upstream model.
            environ=env(LLM_KEY='via-local-proxy',LLM_MODEL='gpt-4o-mini',
                LLM_URL=f'http://127.0.0.1:{PORT}/{tool}/{mid}/v1/chat/completions')
            # Native repair can write basedir/target/test-classes and its backup.
            # Every method/tool gets its own project tree, not only own tmpOutput.
            isolated_project=out/'project'
            shutil.copytree(meta['project'],isolated_project,
                ignore=shutil.ignore_patterns('test-classes','test-classes-backup'))
            cwd=isolated_project
            dump(out/'selector.json',dict(number=number,signature=sig,descriptor=row['descriptor']))
        dump(out/'started.json',dict(unix=time.time(),row=row,tool=tool,selector=selector))
        r=execute(args,out/'generation.log',cwd,environ,CAP)
        r.update(subject=row['subject'],method_id=mid,tool=tool,selector=selector,
            generated_java_files=len(list(out.rglob('*.java'))))
        dump(out/'done.json',r)
        print('GENERATED',mid,tool,r['exit'],r['seconds'],flush=True)
        return r

class Resources:
    def __init__(self):
        import threading
        self.cv=threading.Condition();self.used=0;self.waiting_measure=0;self.active_gen=0;self.active_measure=0
    def acquire(self,kind):
        size=6 if kind=='measure' else 3
        with self.cv:
            if kind=='measure':self.waiting_measure+=1
            while self.used+size>24 or (kind=='generate' and self.waiting_measure) or int(re.search(r'MemAvailable:\s+(\d+)',Path('/proc/meminfo').read_text()).group(1))<4*1024*1024:
                self.cv.wait(timeout=2)
            if kind=='measure':self.waiting_measure-=1;self.active_measure+=1
            else:self.active_gen+=1
            self.used+=size
    def release(self,kind):
        with self.cv:
            self.used-=6 if kind=='measure' else 3
            if kind=='measure':self.active_measure-=1
            else:self.active_gen-=1
            self.cv.notify_all()
    def snapshot(self):
        with self.cv:return dict(reserved_gib=self.used,active_generators=self.active_gen,active_measurements=self.active_measure,waiting_measure=self.waiting_measure)

UNSUPPORTED_NATIVE=set()
def run():
    global UNSUPPORTED_NATIVE
    sample=freeze();subjects=load(ROOT/'subjects.json');rows=sample['rows']
    selectors=load(ROOT/'selectors.json');assert len(selectors)==72
    UNSUPPORTED_NATIVE={s['method_id'] for s in selectors if not s['supported']}
    import llm_proxy
    llm_proxy.start()
    from measurement import measure
    resources=Resources();state=dict(status='running',started_unix=time.time(),total=360,generated=0,measured=0,errors=[],generation_workers=8,measurement_workers=2)
    measuring=[]
    def generate(r,t):
        resources.acquire('generate')
        try:return unit(r,t,subjects[r['subject']])
        finally:resources.release('generate')
    def measurement(r,t):
        resources.acquire('measure')
        try:return measure(r,t,ROOT/'runs'/r['subject']/t/r['method_id'],subjects[r['subject']])
        finally:resources.release('measure')
    # Every subject/role uses the same worker pool, immutable per-tool budgets and seed.
    jobs=[(r,t) for r in rows for t in TOOL_NAMES]
    with futures.ThreadPoolExecutor(max_workers=2) as mx,futures.ThreadPoolExecutor(max_workers=8) as gx:
        pending={gx.submit(generate,r,t):(r,t) for r,t in jobs}
        for f in futures.as_completed(pending):
            r,t=pending[f]
            try:
                f.result();state['generated']+=1;mf=mx.submit(measurement,r,t);measuring.append((mf,r,t))
            except Exception as exc:
                state['errors'].append(dict(method_id=r['method_id'],tool=t,stage='generation',error=str(exc)))
                print('ERROR_GENERATION',r['method_id'],t,str(exc),flush=True)
            state['measured']=sum(x[0].done() and x[0].exception() is None for x in measuring)
            state['resources']=resources.snapshot();state['updated_unix']=time.time();dump(ROOT/'state.json',state)
        for f,r,t in measuring:
            try:f.result()
            except Exception as exc:
                state['errors'].append(dict(method_id=r['method_id'],tool=t,stage='measurement',error=str(exc)))
                print('ERROR_MEASUREMENT',r['method_id'],t,str(exc),flush=True)
            state['measured']=sum(x[0].done() and x[0].exception() is None for x in measuring)
            state['updated_unix']=time.time();dump(ROOT/'state.json',state)
    state.update(status='complete' if state['measured']==360 and not state['errors'] else 'needs_recovery',finished_unix=time.time())
    dump(ROOT/'state.json',state);dump(HERE/'state.json',state)
    llm_proxy.stop()
    print('BATCH_FINISHED',json.dumps(state),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['freeze','prepare','run']);a=p.parse_args()
    if a.action=='freeze':freeze()
    elif a.action=='prepare':prepare()
    else:run()
