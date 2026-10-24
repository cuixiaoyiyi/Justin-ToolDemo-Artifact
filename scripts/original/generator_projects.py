"""Prepare isolated ChatUniTest-family projects and parse exact method metadata.

This module has no method-generation entry point and never executes subject tests.
prepare(subject_meta) accepts sid, jar, sources, runtime_cp (or runtime_cp_file),
and optional frozen_methods/methods. Returned exact_metadata is keyed by
``fully.qualified.Class#method(JVMdescriptor)Return``.
"""
from pathlib import Path
import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path('/home/clm/tooldemo72-20261023')
TEMPLATE=Path('/home/clm/tooldemo-20261017/src/gson-2.10.1/pom.xml')
JAVA_HOME=Path('/home/clm/jdk/jdk-17.0.20.1+1')
URI='http://maven.apache.org/POM/4.0.0'
NS={'m':URI}
ET.register_namespace('',URI)
P=lambda tag:'{'+URI+'}'+tag

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def _class_methods(data):
    """Read names, descriptors and flags directly; never load the subject class."""
    pos=8
    def u1():
        nonlocal pos
        n=data[pos];pos+=1;return n
    def u2():
        nonlocal pos
        n=struct.unpack_from('>H',data,pos)[0];pos+=2;return n
    def u4():
        nonlocal pos
        n=struct.unpack_from('>I',data,pos)[0];pos+=4;return n
    assert data[:4]==b'\xca\xfe\xba\xbe'
    count=u2();cp=[None]*count;i=1
    while i<count:
        tag=u1()
        if tag==1:
            n=u2();cp[i]=data[pos:pos+n].decode('utf-8',errors='replace');pos+=n
        elif tag in (3,4):pos+=4
        elif tag in (5,6):pos+=8;i+=1
        elif tag in (7,8,16,19,20):pos+=2
        elif tag in (9,10,11,12,17,18):pos+=4
        elif tag==15:pos+=3
        else:raise ValueError(('Unsupported constant-pool tag',tag))
        i+=1
    pos+=6
    interface_count=u2();pos+=2*interface_count
    def member():
        nonlocal pos
        flags=u2();name=cp[u2()];desc=cp[u2()]
        for _ in range(u2()):
            u2();n=u4();pos+=n
        return {'name':name,'descriptor':desc,'access_flags':flags}
    for _ in range(u2()):member()
    return [member() for _ in range(u2())]

def _descriptor_args(desc):
    primitive={'B':'byte','C':'char','D':'double','F':'float','I':'int','J':'long','S':'short','Z':'boolean','V':'void'}
    out=[];i=1
    while desc[i]!=')':
        dims=0
        while desc[i]=='[':dims+=1;i+=1
        if desc[i]=='L':
            end=desc.index(';',i);base=desc[i+1:end].replace('/','.').replace('$','.');i=end+1
        else:base=primitive[desc[i]];i+=1
        out.append(base+'[]'*dims)
    return out

def _split_types(text):
    out=[];depth=0;start=0
    for i,c in enumerate(text):
        if c=='<':depth+=1
        elif c=='>':depth-=1
        elif c==',' and depth==0:out.append(text[start:i].strip());start=i+1
    tail=text[start:].strip()
    if tail:out.append(tail)
    return out

def _erase(text):
    out=[];depth=0
    for c in text:
        if c=='<':depth+=1
        elif c=='>':depth-=1
        elif depth==0:out.append(c)
    return re.sub(r'\s+','', ''.join(out)).replace('...','[]')

def _type_variables(info,method):
    # The declarations, rather than parameter counts, determine generic erasure.
    found={}
    declarations=[]
    for text,pattern in [
        # Native ClassInfo.classSignature omits class type parameters. The
        # unchanged full declaration retains the actual owning class bounds.
        (info.get('classDeclarationCode') or info.get('classSignature',''),
         r'(?m)^\s*(?:(?:public|protected|private|abstract|static|final|strictfp|sealed|non-sealed)\s+)*(?:class|interface|record)\s+'
         +re.escape(info.get('className',''))+r'\s*<'),
        (method.get('brief',''),r'\b(?:public|protected|private)\s+(?:(?:static|final|synchronized|native|abstract|default|strictfp)\s+)*<')]:
        m=re.search(pattern,text)
        if not m:continue
        start=m.end();depth=1
        for end in range(start,len(text)):
            if text[end]=='<':depth+=1
            elif text[end]=='>':depth-=1
            if depth==0:
                declarations.append(text[start:end]);break
    for declaration in declarations:
        for part in _split_types(declaration):
            m=re.match(r'^\s*([A-Za-z_$][\w$]*)(?:\s+extends\s+([\w.$]+))?',part)
            if m:
                found[m.group(1)]=m.group(2) or 'java.lang.Object'
    return found

def _type_candidates(raw,info,variables):
    raw=_erase(raw)
    dims=''
    while raw.endswith('[]'):dims+='[]';raw=raw[:-2]
    if raw in variables:
        return {x+dims for x in _type_candidates(variables[raw],info,{k:v for k,v in variables.items() if k!=raw})}
    if raw in {'byte','char','double','float','int','long','short','boolean','void'}:
        return {raw+dims}
    imports=[]
    for line in info.get('imports',[]):
        m=re.match(r'\s*import\s+(?!static\b)([\w.$*]+)\s*;',line)
        if m:imports.append(m.group(1).replace('$','.'))
    head,_,tail=raw.partition('.')
    explicit=[x for x in imports if not x.endswith('.*') and x.split('.')[-1]==head]
    if explicit:return {x+('.'+tail if tail else '')+dims for x in explicit}
    if '.' in raw and raw.split('.')[0][0].islower():return {raw.replace('$','.')+dims}
    pkg=info.get('packageName','')
    names={raw,'java.lang.'+raw,(pkg+'.'+raw).lstrip('.')}
    names.update(x[:-1]+raw for x in imports if x.endswith('.*'))
    cls=info.get('fullClassName','').replace('$','.')
    names.add(cls+'.'+raw)
    # Canonical nested types can be referred through their enclosing class.
    if '.' in cls:names.add(cls.rsplit('.',1)[0]+'.'+raw)
    return {x.replace('$','.')+dims for x in names}

def _exact_map(parsed_root,jar):
    exact={};unmapped=[]
    with zipfile.ZipFile(jar) as z:
        names=set(z.namelist())
        for classfile in sorted(parsed_root.rglob('class.json')):
            info=json.loads(classfile.read_text())
            cls=info.get('fullClassName')
            if not cls:continue
            entry=cls.replace('.','/')+'.class'
            if entry not in names:
                # Parser sometimes flattens nested names. Do not guess the binary class.
                unmapped.append({'class':cls,'reason':'no identical fully-qualified binary class','metadata':str(classfile)})
                continue
            binary=[m for m in _class_methods(z.read(entry)) if not m['access_flags']&(0x40|0x1000)]
            for p in sorted(classfile.parent.glob('*.json')):
                if p.name=='class.json':continue
                m=json.loads(p.read_text())
                sig=m.get('methodSignature','')
                match=re.match(r'([^()]+)\((.*)\)$',sig)
                if not match:continue
                name='<init>' if m.get('isConstructor') else m.get('methodName')
                params=_split_types(match.group(2))
                variables=_type_variables(info,m)
                candidates=[_type_candidates(t,info,variables) for t in params]
                hits=[]
                for b in binary:
                    if b['name']!=name:continue
                    args=_descriptor_args(b['descriptor'])
                    if len(args)==len(candidates) and all(a in choices for a,choices in zip(args,candidates)):
                        hits.append(b)
                if len(hits)!=1:
                    unmapped.append({'class':cls,'signature':sig,'metadata':str(p),
                                     'reason':'no unique full typed descriptor match',
                                     'parsed_parameter_type_candidates':[sorted(x) for x in candidates],
                                     'matching_descriptors':[b['descriptor'] for b in hits]})
                    continue
                b=hits[0];key=cls+'#'+name+b['descriptor']
                value={'class':cls,'name':name,'descriptor':b['descriptor'],'metadata_id':int(p.stem),
                       'method_signature':sig,'metadata_path':str(p),'metadata_sha256':sha(p),
                       'class_metadata_path':str(classfile),'is_public':bool(b['access_flags']&1),
                       'is_abstract':bool(b['access_flags']&0x400),'is_get_set':m.get('isGetSet',False),
                       'parsed_signature_parameter_types':params,'formal_type_variables':variables,
                       'resolved_parameter_type_candidates':[sorted(x) for x in candidates],
                       'frozen_binary_parameter_types':_descriptor_args(b['descriptor']),
                       'verification':'parsed full parameter types including generic erasure matched against frozen binary descriptor; bridge/synthetic methods excluded'}
                if key in exact:raise ValueError('Duplicate exact mapping '+key)
                exact[key]=value
    return exact,unmapped

def _safe_extract(jar,dest,java_only=False):
    with zipfile.ZipFile(jar) as z:
        for item in z.infolist():
            if item.is_dir() or (java_only and not item.filename.endswith('.java')):continue
            p=dest/item.filename
            if not p.resolve().is_relative_to(dest.resolve()):raise ValueError('Unsafe jar path')
            p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(item))

def _runtime_paths(meta):
    cp=meta.get('runtime_cp')
    if cp is None and meta.get('runtime_cp_file'):cp=Path(meta['runtime_cp_file']).read_text().strip()
    if cp is None:cp=[]
    return [str(Path(p)) for p in (cp.split(':') if isinstance(cp,str) else cp) if p]

def _validate_requested(meta,result):
    targets=meta.get('frozen_methods') or meta.get('methods') or []
    missing=[]
    for t in targets:
        name=t.get('name') or t.get('method')
        key=t['class']+'#'+name+t['descriptor']
        if key not in result['exact_metadata']:missing.append(key)
    if missing:raise ValueError('No exact parsed metadata mapping for '+json.dumps(missing))

def prepare(subject_meta):
    """Prepare and parse one subject. Does not call any test-generation goal."""
    meta=dict(subject_meta);sid=meta['sid'];jar=Path(meta['jar']);sources=Path(meta['sources'])
    if not re.fullmatch(r'[A-Za-z0-9_.-]+',sid):raise ValueError('Unsafe subject id')
    cp=_runtime_paths(meta)
    for p in [jar,sources,*map(Path,cp)]:
        if not p.exists():raise FileNotFoundError(p)
    root=Path(meta.get('root',ROOT));project=root/'projects'/sid;dest=root/'parsed'/sid
    locks=root/'locks';locks.mkdir(parents=True,exist_ok=True)
    with (locks/('generator-project-'+sid+'.lock')).open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        fingerprint={'jar':sha(jar),'sources':sha(sources),'runtime':[{'path':p,'sha256':sha(p)} for p in cp],
                     'template_sha256':sha(TEMPLATE),'mapping_version':4}
        marker=dest/'prepared.json'
        if marker.exists():
            result=json.loads(marker.read_text())
            if result['input_fingerprint']!=fingerprint:
                previous=result['input_fingerprint']
                # A mapping-algorithm upgrade needs no new Maven parse. All actual
                # inputs remain pinned, including dependency paths and template.
                old_stable={k:v for k,v in previous.items() if k!='mapping_version'}
                new_stable={k:v for k,v in fingerprint.items() if k!='mapping_version'}
                old_version=previous.get('mapping_version',0)
                new_version=fingerprint['mapping_version']
                if old_stable!=new_stable or not isinstance(old_version,int) or old_version>=new_version:
                    raise ValueError('Prepared project fingerprint changed: '+sid)
                parsed_root=Path(result['parsed_root'])
                if not parsed_root.is_dir():raise FileNotFoundError(parsed_root)
                exact,unmapped=_exact_map(parsed_root,jar)
                upgraded=dict(result,input_fingerprint=fingerprint,exact_metadata=exact,unmapped_metadata=unmapped)
                upgraded['mapping_upgrade']={'from_version':old_version,'to_version':new_version,
                    'time_unix':time.time(),'maven_parse_reused':True,
                    'previous_prepared_sha256':sha(marker)}
                _validate_requested(meta,upgraded)
                archive=dest/('prepared-mapping-v'+str(old_version)+'-before-'+str(time.time_ns())+'.json')
                marker.rename(archive)
                upgraded['mapping_upgrade']['previous_prepared_archive']=str(archive)
                debug=dest/'mapping-debug.json'
                if debug.exists():debug.rename(dest/('mapping-debug-v'+str(old_version)+'-before-'+str(time.time_ns())+'.json'))
                debug.write_text(json.dumps({'exact_metadata':exact,'unmapped_metadata':unmapped,
                    'mapping_upgrade':upgraded['mapping_upgrade']},ensure_ascii=False,indent=2))
                marker.write_text(json.dumps(upgraded,ensure_ascii=False,indent=2))
                result=upgraded
            _validate_requested(meta,result);return result
        project.mkdir(parents=True,exist_ok=True);dest.mkdir(parents=True,exist_ok=True)
        _safe_extract(sources,project/'src/main/java',True)
        _safe_extract(jar,project/'target/classes')
        doc=ET.fromstring(TEMPLATE.read_text())
        doc.find('m:artifactId',NS).text=sid
        build=doc.find('m:build',NS)
        final_name=build.find('m:finalName',NS)
        if final_name is None:final_name=ET.SubElement(build,P('finalName'))
        final_name.text=sid
        # Plugin TestCompiler expects Maven's final binary jar to exist, even for parse.
        shutil.copy2(jar,project/'target'/(sid+'.jar'))
        deps=doc.find('m:dependencies',NS)
        if deps is None:deps=ET.SubElement(doc,P('dependencies'))
        for i,path in enumerate(cp):
            d=ET.SubElement(deps,P('dependency'))
            for key,value in [('groupId','boundary.runtime'),('artifactId','dependency-'+str(i)),('version','1'),('scope','system'),('systemPath',path)]:
                ET.SubElement(d,P(key)).text=value
        # Preserve environment placeholders, never copy or write a real credential.
        for config in doc.findall('m:build/m:plugins/m:plugin/m:configuration',NS):
            for child in config:
                if 'key' in child.tag.lower():child.text='${env.LLM_KEY}'
                elif child.tag==P('model'):child.text='${env.LLM_MODEL}'
                elif child.tag==P('url'):child.text='${env.LLM_URL}'
        pom=project/'pom.xml';ET.indent(doc,space='  ');pom.write_text(ET.tostring(doc,encoding='unicode'))
        env=dict(os.environ,JAVA_HOME=str(JAVA_HOME),MAVEN_OPTS='-Xmx2g -Djava.awt.headless=true',
                 LLM_KEY='disabled-for-static-parse',LLM_MODEL='gpt-4o-mini',LLM_URL='http://127.0.0.1:9/disabled')
        env['PATH']=str(JAVA_HOME/'bin')+':'+env['PATH']
        command=['mvn','-B','-o','-Dmaven.test.skip=true','-DskipTests=true','chatunitest:parse','-DtmpOutput='+str(dest/'tmp')]
        log=dest/'parse.log';started=time.time()
        if log.exists():log.rename(dest/('parse-before-'+str(time.time_ns())+'.log'))
        timing_path=dest/'parse-timing.json'
        if timing_path.exists():timing_path.rename(dest/('parse-timing-before-'+str(time.time_ns())+'.json'))
        with log.open('w') as output:
            output.write('COMMAND '+json.dumps(command)+'\n');output.flush()
            run=subprocess.run(command,cwd=project,env=env,stdout=output,stderr=subprocess.STDOUT,timeout=900)
        timing={'command':command,'exit':run.returncode,'started_unix':started,'finished_unix':time.time()}
        (dest/'parse-timing.json').write_text(json.dumps(timing,indent=2))
        if run.returncode:raise RuntimeError('Static parse failed; inspect '+str(log))
        cp_file=dest/'test-classpath.txt'
        cp_command=['mvn','-B','-o','-Dmaven.test.skip=true','-DskipTests=true',
                    'org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath',
                    '-DincludeScope=test','-Dmdep.outputFile='+str(cp_file)]
        cp_log=dest/'test-classpath.log'
        if cp_log.exists():cp_log.rename(dest/('test-classpath-before-'+str(time.time_ns())+'.log'))
        with cp_log.open('w') as output:
            output.write('COMMAND '+json.dumps(cp_command)+'\n');output.flush()
            cp_run=subprocess.run(cp_command,cwd=project,env=env,stdout=output,stderr=subprocess.STDOUT,timeout=300)
        if cp_run.returncode:raise RuntimeError('Static test-classpath preparation failed; inspect '+str(cp_log))
        # Both forms are explicit so callers need not guess whether a value is a path.
        test_cp=cp_file.read_text().strip()
        roots=list((dest/'tmp').glob('*/class-info'))
        if len(roots)!=1:raise RuntimeError('Expected one class-info root, got '+str(roots))
        parsed_root=roots[0]
        exact,unmapped=_exact_map(parsed_root,jar)
        result={'sid':sid,'project':str(project),'pom':str(pom),'pom_sha256':sha(pom),
                'parsed_root':str(parsed_root),'tmp_root':str(dest/'tmp'),
                'test_cp':test_cp,'test_cp_file':str(cp_file),'test_cp_sha256':sha(cp_file),
                'parse_log':str(log),'parse_log_sha256':sha(log),'parse_timing':timing,
                'input_fingerprint':fingerprint,'exact_metadata':exact,'unmapped_metadata':unmapped,
                'new_generations':0,'junit_runs':0,'model_api_calls':0}
        (dest/'mapping-debug.json').write_text(json.dumps({'exact_metadata':exact,'unmapped_metadata':unmapped},ensure_ascii=False,indent=2))
        _validate_requested(meta,result)
        marker.write_text(json.dumps(result,ensure_ascii=False,indent=2))
        return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('manifest');parser.add_argument('--sid',action='append');args=parser.parse_args()
    doc=json.loads(Path(args.manifest).read_text());subjects=doc['subjects'] if isinstance(doc,dict) and 'subjects' in doc else doc
    for meta in subjects:
        if args.sid and meta['sid'] not in args.sid:continue
        r=prepare(meta)
        print(json.dumps({'sid':r['sid'],'project':r['project'],'parsed_root':r['parsed_root'],
                          'exact_method_count':len(r['exact_metadata']),'unmapped_count':len(r['unmapped_metadata'])}),flush=True)
