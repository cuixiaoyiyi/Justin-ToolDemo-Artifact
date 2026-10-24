"""Independent read-only preflight checks for the frozen original72 batch.

Never starts a model, generator, compiler or subject. Only its own JSON audit
is written. Native checks remain pending until the static preparer finishes.
"""
from __future__ import annotations
import ast
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = Path('/home/clm/tooldemo72-20261023')
OLD = Path('/home/clm/tooldemo-20261017')
SELECTION = Path('/mnt/c/Users/Administrator/Documents/Codex/2026-10-17/new-chat/outputs/sequence-boundary-analysis.json')
EXPECTED_TOOLS = ['justin-llm','evosuite','chatunitest','chattester','hits']


def read(p): return json.loads(Path(p).read_text(encoding='utf-8'))
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def digest(b): return hashlib.sha256(b).hexdigest()


def java_tokens(text):
    lex = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?|[^\s]')
    return '\0'.join(m.group() for m in lex.finditer(text) if not m.group().startswith(('//','/*')))


def constants(path):
    result = {}
    for node in ast.parse(path.read_text()).body:
        if isinstance(node, ast.Assign) and len(node.targets)==1 and isinstance(node.targets[0],ast.Name):
            value = node.value
            if isinstance(value,ast.Call) and isinstance(value.func,ast.Name) and value.func.id=='Path' and value.args:
                value = value.args[0]
            try: result[node.targets[0].id] = ast.literal_eval(value)
            except (ValueError,TypeError): pass
    return result


def main():
    checks = []
    issues = []
    pending = []
    def check(condition, name, **data):
        item = dict(check=name, passed=bool(condition), **data)
        checks.append(item)
        if not condition: issues.append(item)
    frozen = read(HERE/'frozen-sample.json')
    original = read(SELECTION)
    originals = [m for g in original['groups'] if g['role']=='core' for m in g['methods']]
    key = lambda r:(r['subject'],r['class'],r.get('method',r.get('name')),r['descriptor'])
    check(len(originals)==len(frozen['rows'])==72,'method_counts')
    check(Counter(map(key, originals))==Counter(map(key,frozen['rows'])),'full_original72_identity_set_with_versions')
    check([r['method_id'] for r in frozen['rows']]==[f's{i:04}' for i in range(1,73)],'stable_method_ids')
    check(len({r['subject'] for r in frozen['rows']})==5,'five_original_release_subjects')
    check(len({(r['subject'],r['class']) for r in frozen['rows']})==10,'ten_exact_classes')
    protocol = frozen['protocol']
    check(protocol['tools']==EXPECTED_TOOLS,'five_tool_names')
    check(len(frozen['rows'])*len(set(protocol['tools']))==protocol['work_units']==360,'all360_work_units')
    check(protocol['seed']==42 and protocol['generation_wall_cap_s']==900,'seed_and_common_wall_cap')
    check(protocol['evosuite_search_s']==60 and protocol['justin_sym_class_budget_ms']==20000,'record_native_budget_differences')
    check(protocol['model']=='deepseek-flash' and protocol['max_response_tokens']==4096 and protocol['thinking']=='disabled','model_envelope')
    for filename in ['benchmark.py','measurement.py','llm_proxy.py','generator_projects.py','resource_sampler.py']:
        c = constants(HERE/filename)
        check(c.get('ROOT')==str(ROOT),'isolated_root_constant',file=filename,root=c.get('ROOT'))
    runner = constants(HERE/'benchmark.py')
    proxy = constants(HERE/'llm_proxy.py')
    check(runner.get('PORT')==proxy.get('PORT')==8772,'runner_proxy_port')
    check(runner.get('TOOL_NAMES')==EXPECTED_TOOLS,'runner_five_tool_names')
    check(proxy.get('MODEL')=='deepseek-flash' and proxy.get('MAX_TOKENS')==4096,'proxy_upstream_model_envelope')
    check(proxy.get('IDENTITY')=='tooldemo72-20261023','proxy_process_identity')
    check("thinking=dict(type='disabled')" in (HERE/'llm_proxy.py').read_text(),'proxy_disables_thinking')
    impl = read(HERE/'implementation.json')
    check(impl['date_Asia_Shanghai']=='2026-10-23' and '900s' in impl['budget_comparability'],'implementation_current_batch')
    check('third-party' in impl['tools_provenance']['ChatTester'] and 'third-party' in impl['tools_provenance']['HITS'],'reproduction_labels')
    check(sha(ROOT/'justin-all.jar')=='acc1227b2e0fbcb620107af99b77b64224d23703900a78f08887a3e9cbc38aed','frozen_justin_jar')
    baseline = read(ROOT/'denominators/frozen72-baselines.json')
    by_baseline = {r['method_id']:r for r in baseline}
    check(len(baseline)==len(by_baseline)==72,'baseline_method_count')
    xml_cache = {}
    byte_checks = []
    for row in frozen['rows']:
        mid = row['method_id']
        sid = row['subject']
        context = dict(method_id=mid,subject=sid)
        original_jar = OLD/'subjects'/(sid+'.jar')
        staged_jar = ROOT/'subjects'/(sid+'.jar')
        original_sources = OLD/'subjects'/(sid+'-sources.jar')
        staged_sources = ROOT/'subjects'/(sid+'-sources.jar')
        check(sha(original_jar)==sha(staged_jar)==row['binary_jar_sha256'],'immutable_binary_jar_sha256',**context)
        check(sha(original_sources)==sha(staged_sources)==row['project_source_artifact_sha256'],'immutable_source_jar_sha256',**context)
        source = Path(row['source_file'])
        entry = row['source_relative_file']
        with zipfile.ZipFile(original_sources) as z: original_source = z.read(entry)
        with zipfile.ZipFile(staged_sources) as z: staged_source = z.read(entry)
        check(source.read_bytes()==original_source==staged_source and sha(source)==row['source_file_sha256'],'exact_source_bytes',**context)
        selected_lines = ''.join(source.read_text().splitlines(keepends=True)[row['source_start']-1:row['source_end']])
        check(row['source_fullsource'] in selected_lines and row['source_body'] in selected_lines,'source_body_range',**context)
        b = by_baseline[mid]
        clsentry = row['class'].replace('.','/')+'.class'
        focal_shas = {}
        for label, path in [('original',original_jar),('staged',staged_jar),('independent_baseline',Path(b['analysis_jar']))]:
            with zipfile.ZipFile(path) as z: focal_shas[label] = digest(z.read(clsentry))
        measure_analysis = ROOT/'subjects'/(sid+'-analysis.jar')
        if measure_analysis.exists():
            with zipfile.ZipFile(measure_analysis) as z: focal_shas['measurement_analysis'] = digest(z.read(clsentry))
        check(set(focal_shas.values())=={row['binary_verification']['class_sha256']},'focal_classfile_identity_across_containers',**context)
        byte_checks.append(dict(method_id=mid,class_entry=clsentry,class_sha256=row['binary_verification']['class_sha256'],containers=focal_shas))
        xml = b['baseline_xml']
        if xml not in xml_cache:
            entries = {}
            for cls in ET.parse(xml).getroot().iter('class'):
                for method in cls.findall('method'):
                    k = (cls.get('name').replace('/','.'),method.get('name'),method.get('desc'))
                    check(k not in entries,'baseline_descriptor_unique',subject=sid,descriptor_key=k)
                    entries[k] = {c.get('type'): int(c.get('covered'))+int(c.get('missed')) for c in method.findall('counter')}
            xml_cache[xml] = entries
        actual = xml_cache[xml][(row['class'],row['method'],row['descriptor'])]
        normalized = {k:actual.get(k,0) for k in ['LINE','BRANCH','METHOD','INSTRUCTION','COMPLEXITY']}
        check(b['descriptor']==row['descriptor'] and normalized==b['counters'] and all(actual.get(k,0)==v for k,v in row['expected_denominator'].items()),'independent_xml_focal_denominator',**context)
    totals = {k:sum(r['counters'][k] for r in baseline) for k in ['LINE','BRANCH','INSTRUCTION']}
    check(totals==dict(LINE=777,BRANCH=722,INSTRUCTION=4099),'common_denominator_777_722',totals=totals)
    # Parse native ClassInfo+MethodInfo independently of prepared selector claims.
    prep = read(HERE/'prepared-subjects.json')
    subjects = {r['sid']:r for r in prep['subjects']}
    native_report = HERE/'baseline-selector-verification.json'
    native_rows = []
    if not native_report.exists():
        pending.append('Native static preparation has not yet emitted baseline-selector-verification.json')
    else:
        native = read(native_report)
        selectors = {r['method_id']:r for r in native['selectors']}
        check(len(selectors)==72 and set(selectors)=={r['method_id'] for r in frozen['rows']},'native_selector_membership')
        for row in frozen['rows']:
            mid = row['method_id']
            r = selectors[mid]
            subj = subjects[row['subject']]['generator_project']
            exact = subj['exact_metadata'][row['class']+'#'+row['method']+row['descriptor']]
            mp = Path(exact['metadata_path'])
            cp = Path(exact['class_metadata_path'])
            mm, cm = read(mp), read(cp)
            check((r['binary_class'],r['method'],r['descriptor'])==(row['class'],row['method'],row['descriptor']),'native_target_identity',method_id=mid)
            check(cm['fullClassName']==row['class'] and mm['methodName']==row['method'],'native_owner_method_name',method_id=mid)
            check(int(r['metadata_id'])==int(exact['metadata_id'])==int(mp.stem),'native_metadata_numeric_selector',method_id=mid)
            check(sha(mp)==r['metadata_sha256']==exact['metadata_sha256'],'native_method_metadata_sha256',method_id=mid)
            check(mm['methodSignature']==r['method_signature']==exact['method_signature'],'native_source_signature',method_id=mid)
            check(int(cm['methodSigs'].get(mm['methodSignature'],-1))==int(mp.stem),'native_class_method_index',method_id=mid)
            check(exact['descriptor']==row['descriptor'] and exact['is_public'] and not exact['is_abstract'],'native_full_binary_descriptor',method_id=mid)
            check(java_tokens(row['source_body']) in java_tokens(mm.get('sourceCode','')),'native_source_body_binding',method_id=mid)
            native_rows.append(dict(method_id=mid,native_selector=r['native_selector'],descriptor=row['descriptor'],metadata_sha256=sha(mp)))
        owners = read(HERE/'native-selector-owner-map.json')
        check(not owners['rows'] and not owners['unsupported_method_ids'],'no_aliases_or_dropped_targets')
    final_manifest = ROOT/'frozen-sample.json'
    if final_manifest.exists():
        final = read(final_manifest)
        check(final['rows']==frozen['rows'] and final['selection_sha256']==frozen['selection_sha256'],'execution_manifest_same_frozen_rows')
        check(final['protocol']['work_units']==360 and final['protocol']['tools']==EXPECTED_TOOLS,'execution_protocol_full360')
    else:
        pending.append('Final execution manifest has not yet been written by benchmark.freeze/prepare')
    selectors_path = ROOT/'selectors.json'
    if selectors_path.exists() and native_rows:
        actual = read(selectors_path)
        final_by_id = {r['method_id']:r for r in actual}
        check(len(actual)==72 and set(final_by_id)=={r['method_id'] for r in frozen['rows']},'execution_selector_membership')
        for r in native_rows:
            final = final_by_id[r['method_id']]
            check(final['native_selector_class']+'#'+str(final['number'])==r['native_selector'] and final['descriptor']==r['descriptor'] and final['supported'],'execution_selector_exact',method_id=r['method_id'])
    elif not selectors_path.exists():
        pending.append('Final execution selectors.json has not yet been written by benchmark.prepare')
    result = dict(schema='original72-independent-preflight-audit-v1',created_unix=time.time(),
        status='ISSUES_FOUND' if issues else ('PENDING_STATIC_PREPARATION' if pending else 'PASSED'),
        issues=issues, issue_count=len(issues), pending=pending, checks=len(checks), failed_checks=[x for x in checks if not x['passed']],
        methods=72, work_units=360, subjects=5, classes=10, denominators=totals,
        selection_sha256=frozen['selection_sha256'], frozen_manifest_sha256=sha(HERE/'frozen-sample.json'),
        code_sha256={f:sha(HERE/f) for f in ['benchmark.py','freeze72.py','measurement.py','generator_projects.py','llm_proxy.py','implementation.json']},
        focal_classfile_identity=byte_checks, native_selectors=native_rows,
        audit_scope='Read-only source, class bytes, manifests, constants, JaCoCo XML and native parser metadata; no generators or model calls.',
        no_model_calls=True, no_generation=True, no_subject_execution=True)
    (HERE/'preflight-audit72.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ['status','issue_count','pending','checks','methods','work_units','denominators']},ensure_ascii=False,indent=2))


if __name__=='__main__': main()
