"""Independent exact-method measurement for the fresh 2026-10-23 72-method benchmark.

Importing this module never starts Java, compilers, tests, or model requests.
Only measure() executes commands. All writes are confined to the new ROOT;
original runtime binaries, old runs, generated sources, and failed tests remain
untouched. HITS receives the already-audited lexical class-name repair only.
"""
from __future__ import annotations

import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

from portable_config import CONFIG as CFG
ROOT = Path(CFG['root'])
REPO = Path(__file__).resolve().parent
TOOLS = Path(CFG['tools_dir'])
JAVA = Path(CFG['jdk17'])/'bin/java'
JDK8 = Path(CFG['jdk8'])/'bin/java'
PROTOCOL = 'exact-method-external-6g-v2-isolated-working-directory'
ALLOWED_TOOLS = {'justin-llm', 'evosuite', 'chatunitest', 'chattester', 'hits'}
NAME_FIX = REPO/'fix_hits_names.py'


def _sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(1024*1024), b''):
            h.update(b)
    return h.hexdigest()


def _json(path, value):
    path = Path(path)
    _inside(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + '.tmp-' + str(os.getpid()))
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temp.replace(path)


def _inside(path):
    path = Path(path).resolve()
    if not path.is_relative_to(ROOT.resolve()):
        raise ValueError('Measurement write target is outside the new benchmark ROOT: '+str(path))
    return path


def _execute(args, log, java, seconds=600):
    """Own process group only; no retries or global process manipulation."""
    log = _inside(log)
    log.parent.mkdir(parents=True, exist_ok=True)
    args = [str(x) for x in args]
    env = dict(os.environ, JAVA_HOME=str(java.parents[1]))
    env['PATH'] = str(java.parent) + os.pathsep + env.get('PATH', '')
    started = time.time()
    tick = time.monotonic()
    with log.open('w', encoding='utf-8') as stream:
        try:
            process = subprocess.Popen(args, stdout=stream, stderr=subprocess.STDOUT,
                                       env=env, cwd=log.parent, start_new_session=True)
        except OSError as exc:
            stream.write(str(exc)+'\n')
            return dict(exit=127, seconds=round(time.monotonic()-tick, 3),
                        started_unix=started, finished_unix=time.time(),
                        command=args, launch_error=str(exc), timeout=False)
        timed_out = False
        try:
            code = process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            code = 124
    return dict(exit=code, seconds=round(time.monotonic()-tick, 3),
                started_unix=started, finished_unix=time.time(), command=args,
                timeout=timed_out, working_directory=str(log.parent))


def _cp_items(value):
    if value is None:
        return []
    if isinstance(value, (tuple, list)):
        return [str(x) for x in value if str(x)]
    value = str(value)
    if value.endswith('.txt') and Path(value).is_file():
        value = Path(value).read_text().strip()
    return [x for x in value.split(os.pathsep) if x]


def _package(text):
    # Comments and literals cannot contribute a package declaration.
    masked = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                    lambda m: ''.join('\n' if c=='\n' else ' ' for c in m.group()), text)
    match = re.search(r'^\s*package\s+([\w.]+)\s*;', masked, re.M)
    return match.group(1) if match else ''


def _origins(out, tool, meta):
    explicit = meta.get('generated_source_root')
    if explicit:
        dirs = [Path(x) for x in explicit] if isinstance(explicit, list) else [Path(explicit)]
    elif tool == 'justin-llm':
        dirs = list((out/'out').glob('*/generated-tests/src'))
        dirs += [out/'out/run/generated-tests/src', out/'generated-tests/src']
    elif tool == 'evosuite':
        dirs = [out/'tests']
    else:
        dirs = [out/'tests'] + list((out/'classes').glob('*/tests'))
    return sorted({p.resolve() for d in dirs if d.is_dir() for p in d.rglob('*.java')})


def _namefix():
    spec = importlib.util.spec_from_file_location('boundary_safe_hits_namefix', NAME_FIX)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _collect(out, tool, meta):
    dest = _inside(out/'measure/collected-tests')
    dest.mkdir(parents=True, exist_ok=True)
    origins = _origins(out, tool, meta)
    inventory = []
    active = {}
    fixer = _namefix() if tool == 'hits' else None
    for src in origins:
        raw = src.read_text(encoding='utf-8', errors='replace')
        item = dict(origin=str(src), origin_sha256=_sha(src), origin_bytes=src.stat().st_size)
        if tool in {'chatunitest', 'chattester', 'hits'} and src.name.endswith('_Suite.java'):
            item.update(included=False, reason='aggregate_suite_excluded_to_avoid_duplicate_execution')
            inventory.append(item)
            continue
        text, have = fixer.transform(raw, src.stem) if fixer else (raw, None)
        if fixer:
            protected = lambda t: [m.group() for m in fixer.LEX.finditer(t)
                                   if m.group().startswith(('//', '/*', '"', "'"))]
            if protected(raw) != protected(text):
                raise RuntimeError('HITS repair altered a comment or literal: '+str(src))
        package = _package(text)
        relative = Path(*package.split('.'))/src.name if package else Path(src.name)
        target = _inside(dest/relative)
        if relative in active and active[relative] != text:
            raise RuntimeError('Conflicting generated source identity: '+str(relative))
        active[relative] = text
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')
        item.update(included=True, collected=str(target), collected_sha256=_sha(target),
                    top_level_type_renamed_from=have, filename_type=src.stem,
                    comments_and_literals_preserved=True)
        inventory.append(item)
    data = dict(protocol=PROTOCOL, generated_source_files=len(origins),
                collected_unique_source_files=len(active), files=inventory,
                hits_name_repair_sha256=_sha(NAME_FIX) if fixer else None)
    _json(out/'measure/source-inventory.json', data)
    return sorted(dest/p for p in active), data


def _class_file(blob, include_calls=False):
    """Parse signatures and real bytecode invokes without executing/loading a class."""
    if blob[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Not a Java class file')
    count = struct.unpack_from('>H', blob, 8)[0]
    cp, pos, index = [None]*count, 10, 1
    while index < count:
        tag = blob[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from('>H', blob, pos)[0]
            pos += 2
            cp[index] = (tag, blob[pos:pos+length].decode(errors='replace'))
            pos += length
        elif tag in (3, 4):
            cp[index] = (tag,)
            pos += 4
        elif tag in (5, 6):
            cp[index] = (tag,)
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[index] = (tag, struct.unpack_from('>H', blob, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[index] = (tag, *struct.unpack_from('>HH', blob, pos))
            pos += 4
        elif tag == 15:
            cp[index] = (tag, *struct.unpack_from('>BH', blob, pos))
            pos += 3
        else:
            raise ValueError('Unknown constant-pool tag '+str(tag))
        index += 1
    utf = lambda i: cp[i][1]
    cls = lambda i: utf(cp[i][1]) if i else None

    def attributes(offset, number):
        items = []
        for _ in range(number):
            name_index, length = struct.unpack_from('>HI', blob, offset)
            items.append((utf(name_index), blob[offset+6:offset+6+length]))
            offset += 6+length
        return items, offset

    access, this, super_ = struct.unpack_from('>HHH', blob, pos)
    pos += 6
    ni = struct.unpack_from('>H', blob, pos)[0]
    pos += 2
    interfaces = [cls(x) for x in struct.unpack_from('>'+'H'*ni, blob, pos)] if ni else []
    pos += ni*2
    nf = struct.unpack_from('>H', blob, pos)[0]
    pos += 2
    for _ in range(nf):
        ac, na, de, an = struct.unpack_from('>HHHH', blob, pos)
        _, pos = attributes(pos+8, an)
    nm = struct.unpack_from('>H', blob, pos)[0]
    pos += 2
    methods, calls = [], []
    for _ in range(nm):
        ac, na, de, an = struct.unpack_from('>HHHH', blob, pos)
        attrs, pos = attributes(pos+8, an)
        method = dict(name=utf(na), descriptor=utf(de), access=ac)
        methods.append(method)
        if not include_calls:
            continue
        for attr, data in attrs:
            if attr != 'Code':
                continue
            length = struct.unpack_from('>I', data, 4)[0]
            code = data[8:8+length]
            q = 8+length
            exceptions = struct.unpack_from('>H', data, q)[0]
            q += 2+8*exceptions
            nested = struct.unpack_from('>H', data, q)[0]
            q += 2
            lines = []
            for _ in range(nested):
                ai, size = struct.unpack_from('>HI', data, q)
                nested_data = data[q+6:q+6+size]
                if utf(ai) == 'LineNumberTable':
                    ln = struct.unpack_from('>H', nested_data, 0)[0]
                    lines = sorted(struct.unpack_from('>HH', nested_data, 2+4*i) for i in range(ln))
                q += 6+size
            for offset, opcode, reference in _invokes(code):
                entry = cp[reference]
                if entry[0] not in (10, 11):
                    continue
                nt = cp[entry[2]]
                line = next((n for start, n in reversed(lines) if start <= offset), None)
                calls.append(dict(caller_class=cls(this), caller_method=utf(na),
                                  caller_descriptor=utf(de), bytecode_offset=offset,
                                  source_line=line, opcode=opcode,
                                  owner=cls(entry[1]), name=utf(nt[1]), descriptor=utf(nt[2])))
    return dict(name=cls(this), superclass=cls(super_), interfaces=interfaces,
                access=access, methods=methods, calls=calls)


def _invokes(code):
    # Instruction widths, including wide and variable-size switch instructions.
    two = {0x10, 0x12, *range(0x15, 0x1a), *range(0x36, 0x3b), 0xa9, 0xbc}
    three = {0x11, 0x13, 0x14, 0x84, *range(0x99, 0xa9), *range(0xb2, 0xb9),
             0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7}
    pos = 0
    while pos < len(code):
        op = code[pos]
        if op in (0xb6, 0xb7, 0xb8, 0xb9):
            yield pos, {0xb6:'invokevirtual', 0xb7:'invokespecial', 0xb8:'invokestatic',
                        0xb9:'invokeinterface'}[op], struct.unpack_from('>H', code, pos+1)[0]
        if op in (0xaa, 0xab):
            aligned = (pos+4) & ~3
            if op == 0xaa:
                low, high = struct.unpack_from('>ii', code, aligned+4)
                size = aligned-pos+12+4*(high-low+1)
            else:
                pairs = struct.unpack_from('>i', code, aligned+4)[0]
                size = aligned-pos+8+8*pairs
        elif op == 0xc4:
            size = 6 if code[pos+1] == 0x84 else 4
        elif op in (0xb9, 0xba, 0xc8, 0xc9):
            size = 5
        elif op == 0xc5:
            size = 4
        else:
            size = 2 if op in two else (3 if op in three else 1)
        if size <= 0 or pos+size > len(code):
            raise ValueError('Invalid bytecode instruction at '+str(pos))
        pos += size


def _bindings(classes, analysis, row, dest):
    target = (row['class'].replace('.', '/'), row['method'], row['descriptor'])
    hierarchy = {}
    with zipfile.ZipFile(analysis) as archive:
        for name in archive.namelist():
            if name.endswith('.class') and not name.startswith('META-INF/'):
                item = _class_file(archive.read(name))
                hierarchy[item['name']] = item

    def declaring(owner, name, descriptor, seen=None):
        seen = set() if seen is None else seen
        if owner in seen or owner not in hierarchy:
            return None
        seen.add(owner)
        info = hierarchy[owner]
        if any(m['name']==name and m['descriptor']==descriptor for m in info['methods']):
            return owner
        for base in [info['superclass'], *info['interfaces']]:
            found = declaring(base, name, descriptor, seen)
            if found:
                return found
        return None

    inventory, calls = [], []
    for path in sorted(classes.rglob('*.class')):
        item = _class_file(path.read_bytes(), include_calls=True)
        inventory.append(dict(path=str(path), sha256=_sha(path), class_name=item['name']))
        for call in item['calls']:
            call['compiled_file'] = str(path)
            call['resolved_declaring_owner_in_subject'] = declaring(call['owner'], call['name'], call['descriptor'])
            call['exact_focal_binding'] = ((call['owner'], call['name'], call['descriptor'])==target or
                (call['resolved_declaring_owner_in_subject'], call['name'], call['descriptor'])==target)
            calls.append(call)
    exact = [c for c in calls if c['exact_focal_binding']]
    same_name = [c for c in calls if c['name']==target[1] and c['descriptor']==target[2]]
    reflection = [c for c in calls if c['owner'] in {'java/lang/reflect/Method', 'java/lang/invoke/MethodHandle'}]
    result = dict(target=dict(owner=target[0], name=target[1], descriptor=target[2]),
                  compiled_classes=inventory, invocations=calls,
                  shadowed_subject_classes=sorted({x['class_name'] for x in inventory} & set(hierarchy)),
                  exact_focal_call_sites=exact, same_signature_other_owner_sites=[c for c in same_name if not c['exact_focal_binding']],
                  reflection_call_sites=reflection,
                  note='Static invoke-instruction evidence, not constant-pool presence alone. Reflection, invokedynamic and indirect helper calls can reach the target without a direct binding here. Coverage zero alone never proves absence of an invocation.')
    _json(dest/'compiled-method-invocations.json', result)
    return dict(compiled_class_files=len(inventory), exact_focal_call_sites=len(exact),
                exact_focal_bindings=exact, reflection_call_sites=len(reflection),
                shadowed_subject_classes=result['shadowed_subject_classes'],
                artifact=str(dest/'compiled-method-invocations.json'))


def _counters(xml, row):
    found = []
    for cls in ET.parse(xml).getroot().iter('class'):
        if cls.get('name') != row['class'].replace('.', '/'):
            continue
        for method in cls.findall('method'):
            if method.get('name')==row['method'] and method.get('desc')==row['descriptor']:
                found.append({c.get('type'): dict(covered=int(c.get('covered')), missed=int(c.get('missed')))
                              for c in method.findall('counter')})
    if len(found) != 1:
        raise RuntimeError('Expected exactly one focal method in coverage report; found '+str(len(found)))
    return {metric: found[0].get(metric, dict(covered=0, missed=0))
            for metric in ['LINE', 'BRANCH', 'METHOD', 'INSTRUCTION', 'COMPLEXITY']}


def _denominator(analysis, row):
    cache = _inside(ROOT/'denominators')
    cache.mkdir(parents=True, exist_ok=True)
    key = _sha(analysis)+'-'+_sha(TOOLS/'jacococli.jar')[:16]
    xml = cache/(key+'.xml')
    with (cache/(key+'.lock')).open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not xml.exists():
            temp = cache/(key+'.tmp.xml')
            r = _execute([JAVA, '-jar', TOOLS/'jacococli.jar', 'report', '--classfiles', analysis,
                          '--xml', temp], cache/(key+'.log'), JAVA)
            if r['exit'] or not temp.exists():
                raise RuntimeError('Cannot build unexecuted common denominator; see '+str(cache/(key+'.log')))
            temp.replace(xml)
    counters = _counters(xml, row)
    return dict(analysis_jar=str(analysis), analysis_jar_sha256=_sha(analysis),
                baseline_xml=str(xml), counters={k:v['covered']+v['missed'] for k,v in counters.items()})


def _compile(sources, classes, cp, java, dest):
    active = list(sources)
    excluded, attempts = [], []
    for attempt in range(1, len(sources)+2):
        if classes.exists():
            shutil.rmtree(_inside(classes))
        classes.mkdir(parents=True)
        if not active:
            return active, excluded, attempts, 'no_compilable_sources'
        argfile = _inside(dest/'active-sources.txt')
        argfile.write_text('\n'.join('"'+str(p).replace('\\','\\\\').replace('"','\\"')+'"' for p in active))
        log = dest/f'javac-{attempt}.log'
        run = _execute([java.parent/'javac', '-nowarn', '-Xmaxerrs', '100000', '-encoding', 'UTF-8',
                        '-proc:none', '-cp', cp, '-d', classes, '@'+str(argfile)], log, java)
        attempts.append(dict(attempt=attempt, active_files=len(active), log=str(log), **run))
        if run['exit']==0:
            return active, excluded, attempts, 'compiled'
        diagnostics = log.read_text(errors='replace')
        bad = {Path(p).resolve() for p in re.findall(r'^([^\n:]+\.java):\d+: error:', diagnostics, re.M)}
        if run['timeout'] or not bad:
            return [], excluded, attempts, 'compile_timeout' if run['timeout'] else 'compile_failure_unisolated'
        removed = set(bad)
        for path in bad:
            base = re.sub(r'_ESTest(?:_scaffolding)?\.java$', '', str(path))
            if base != str(path):
                removed.update({Path(base+'_ESTest.java'), Path(base+'_ESTest_scaffolding.java')})
        doomed = [p for p in active if p.resolve() in removed]
        if not doomed:
            return [], excluded, attempts, 'compile_failure_unisolated'
        for path in doomed:
            excluded.append(dict(path=str(path), sha256=_sha(path), attempt=attempt,
                                 direct_error=path.resolve() in bad,
                                 reason='javac_error' if path.resolve() in bad else 'paired_evosuite_scaffolding',
                                 log=str(log)))
        active = [p for p in active if p not in doomed]
    raise RuntimeError('Compilation pruning did not converge')


def _junit(dest, execution):
    tests = failures = errors = skipped = 0
    files, parse_errors, inconsistencies = [], [], []
    oom_in_reports = False
    for path in sorted((dest/'junit').glob('TEST-*.xml')):
        try:
            item = ET.parse(path).getroot()
            count = dict(tests=int(item.get('tests',0)), failures=int(item.get('failures',0)),
                         errors=int(item.get('errors',0)), skipped=int(item.get('skipped',0)))
            cases = list(item.iter('testcase'))
            observed = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                            errors=sum(c.find('error') is not None for c in cases),
                            skipped=sum(c.find('skipped') is not None for c in cases))
            if count != observed or min(count.values()) < 0 or count['failures']+count['errors']+count['skipped']>count['tests']:
                inconsistencies.append(dict(path=str(path), suite_counts=count, testcase_counts=observed))
            tests += count['tests']; failures += count['failures']
            errors += count['errors']; skipped += count['skipped']
            oom_in_reports |= 'OutOfMemoryError' in path.read_text(errors='replace')
            files.append(dict(path=str(path), sha256=_sha(path)))
        except (ET.ParseError, ValueError) as exc:
            parse_errors.append(dict(path=str(path), error=str(exc)))
    log = (dest/'junit.log').read_text(errors='replace') if (dest/'junit.log').exists() else ''
    # --details=none omits the human summary, so it cannot be a completeness
    # requirement. A normal console exit plus internally consistent closed XML
    # documents is the machine-readable completion evidence.
    exit_code = execution.get('exit')
    if files and ((exit_code==0 and failures+errors>0) or (exit_code==1 and failures+errors==0)):
        inconsistencies.append(dict(reason='console_exit_disagrees_with_reported_test_failures',
                                    exit=exit_code, failures=failures, errors=errors))
    oom_detected = 'OutOfMemoryError' in log or oom_in_reports
    fatal_log = bool(re.search(r'^Exception in thread "main"|^# A fatal error',log,re.M))
    complete = (bool(files) and not parse_errors and not inconsistencies and exit_code in (0,1)
                and not execution.get('timeout') and not oom_detected and not fatal_log)
    observed_passed = max(0, tests-failures-errors-skipped)
    return dict(tests=tests, tests_total=tests, tests_executed=max(0,tests-skipped), failures=failures,
                errors=errors, skipped=skipped, tests_passed=observed_passed if complete else None,
                observed_passed=observed_passed, test_pass_pct=round(100*observed_passed/(tests-skipped), 3)
                if complete and tests>skipped else None,
                junit_run_complete=complete, junit_report_files=files, junit_xml_parse_errors=parse_errors,
                junit_count_inconsistencies=inconsistencies,
                junit_completion_basis='normal_console_exit_and_consistent_closed_engine_xml',
                human_summary_present='Test run finished after' in log,
                oom_in_log='OutOfMemoryError' in log, oom_in_reports=oom_in_reports,
                fatal_console_error=fatal_log)


def measure(row, tool, out, subject_meta):
    """Compile and measure one frozen exact-method generation outcome.

    row keys: class, method, descriptor, subject, method_id.
    meta keys: jar, sources, runtime_cp, analysis_jar, test_cp; optional
    generated_source_root overrides source discovery. Existing result inputs are
    immutable: changed inputs require a fresh output directory, not overwrite.
    """
    required = {'class', 'method', 'descriptor', 'subject', 'method_id'}
    if not required.issubset(row):
        raise ValueError('Missing row keys: '+str(sorted(required-set(row))))
    if tool not in ALLOWED_TOOLS:
        raise ValueError('Unknown tool: '+str(tool))
    if JAVA is None or JDK8 is None:
        raise RuntimeError('Expected configured JDK 17 and JDK 8 runtimes')
    out = _inside(out)
    out.mkdir(parents=True, exist_ok=True)
    with (out/'measurement.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return _measure(row, tool, out, subject_meta)


def _measure(row, tool, out, meta):
    dest = _inside(out/'measure')
    dest.mkdir(exist_ok=True)
    jar = Path(meta['jar']).resolve()
    analysis = Path(meta.get('analysis_jar') or jar).resolve()
    java = JDK8 if tool=='evosuite' else JAVA
    source_inputs = [dict(path=str(p), sha256=_sha(p)) for p in _origins(out, tool, meta)]
    signature = dict(protocol=PROTOCOL, tool=tool, row={k:row[k] for k in ['class','method','descriptor','subject','method_id']},
                     runtime_jar=str(jar), runtime_jar_sha256=_sha(jar),
                     analysis_jar=str(analysis), analysis_jar_sha256=_sha(analysis),
                     sources_jar=str(meta.get('sources','')), source_inputs=source_inputs,
                     runtime_cp=_cp_items(meta.get('runtime_cp')), test_cp=_cp_items(meta.get('test_cp')),
                     java=str(java), heap_gib=6,
                     isolated_working_directory=str(dest),
                     external_test_timeout_s=300,
                     namefix_sha256=_sha(NAME_FIX) if tool=='hits' else None)
    fingerprint = hashlib.sha256(json.dumps(signature,sort_keys=True).encode()).hexdigest()
    returned = dest/'return.json'
    if returned.exists():
        previous = json.loads(returned.read_text())
        if previous.get('input_fingerprint') != fingerprint:
            raise RuntimeError('Existing measurement inputs changed; use a fresh output directory: '+str(out))
        return previous
    old_input = dest/'measurement-input.json'
    if old_input.exists():
        old_fingerprint = json.loads(old_input.read_text()).get('input_fingerprint')
        if old_fingerprint != fingerprint:
            raise RuntimeError('Incomplete measurement has different inputs; preserve it and use a fresh output directory: '+str(out))
        if (dest/'test-execution-started.json').exists():
            raise RuntimeError('Incomplete measurement already launched its test JVM; recover its artifacts or explicitly archive the measurement before retrying. Automatic duplicate execution is disabled: '+str(dest))
    _json(dest/'measurement-input.json', dict(input_fingerprint=fingerprint, **signature))
    denominator = _denominator(analysis, row)
    sources, inventory = _collect(out, tool, meta)
    cp = [str(jar), str(TOOLS/'junit-platform-console-standalone-1.12.2.jar'),
          str(TOOLS/'junit-4.13.2.jar'), str(TOOLS/'hamcrest-core-1.3.jar')]
    cp += _cp_items(meta.get('runtime_cp'))
    if tool=='evosuite':
        cp.append(str(TOOLS/'evosuite-standalone-runtime-1.2.0.jar'))
    if tool in {'chatunitest','chattester','hits'}:
        cp += _cp_items(meta.get('test_cp'))
    cp = os.pathsep.join(dict.fromkeys(cp))
    classes = dest/'classes'
    active, excluded, compile_runs, compile_status = _compile(sources, classes, cp, java, dest)
    _json(dest/'compilation.json', dict(status=compile_status, active_sources=[str(p) for p in active],
                                      excluded_sources=excluded, attempts=compile_runs,
                                      failed_test_sources_deleted=False))
    # Unisolated javac failure can leave partial class files. They are retained as
    # compiler evidence but must never be executed as a supposedly valid suite.
    executable = compile_status=='compiled'
    binding = _bindings(classes, analysis, row, dest) if executable else dict(
        compiled_class_files=0, exact_focal_call_sites=0, exact_focal_bindings=[],
        note='No complete compilable source set. Partial javac output was not executed.')
    if binding.get('shadowed_subject_classes'):
        executable = False
        compile_status = 'generated_class_shadows_original_subject'
        _json(dest/'runtime-integrity-failure.json', binding)
    if executable:
        _json(dest/'test-execution-started.json', dict(started_unix=time.time(),
            note='A missing return.json after this marker requires artifact recovery, not automatic test re-execution.'))
        execution = _execute([java, '-Xmx6g',
            '-javaagent:'+str(TOOLS/'jacocoagent.jar')+'=destfile='+str(dest/'jacoco.exec')+',append=false',
            '-cp', str(classes)+os.pathsep+cp, 'org.junit.platform.console.ConsoleLauncher',
            'execute', '--scan-class-path', classes, '--include-classname=.*', '--details=summary',
            '--disable-banner', '--reports-dir='+str(dest/'junit')], dest/'junit.log', java, seconds=300)
    else:
        execution = dict(exit=None, seconds=0.0, timeout=False, skipped_reason=compile_status)
    _json(dest/'execution.json', execution)
    junit = _junit(dest, execution)
    execfile = dest/'jacoco.exec'
    execution_files = [execfile] if executable and execfile.exists() else []
    report = _execute([JAVA, '-jar', TOOLS/'jacococli.jar', 'report', *execution_files,
                       '--classfiles', analysis, '--xml', dest/'coverage.xml',
                       '--csv', dest/'coverage.csv'], dest/'coverage.log', JAVA)
    if report['exit'] or not (dest/'coverage.xml').exists():
        raise RuntimeError('External JaCoCo report failed; see '+str(dest/'coverage.log'))
    counters = _counters(dest/'coverage.xml', row)
    actual_totals = {k:v['covered']+v['missed'] for k,v in counters.items()}
    if actual_totals != denominator['counters']:
        raise RuntimeError('Exact-method coverage denominator changed: '+str(out))
    metrics = {}
    for key in ['LINE','BRANCH','METHOD','INSTRUCTION','COMPLEXITY']:
        covered, total = counters[key]['covered'], actual_totals[key]
        metrics.update({key.lower()+'_covered':covered, key.lower()+'_total':total,
                        key.lower()+'_pct':round(100*covered/total,3) if total else None})
    result = dict(protocol=PROTOCOL, input_fingerprint=fingerprint, subject=row['subject'],
                  tool=tool, tool_label='HITS 修复复现' if tool=='hits' else tool,
                  method_id=row['method_id'], focal_class=row['class'], focal_method=row['method'],
                  descriptor=row['descriptor'], scope='exact-focal-method', heap_gib=6,
                  runtime_jar=str(jar), runtime_jar_sha256=_sha(jar), denominator=denominator,
                  common_denominator_verified=True, generated_files=len(sources), compiled_files=len(active),
                  excluded_files=len(excluded), compile_status=compile_status,
                  compilation_s=round(sum(r['seconds'] for r in compile_runs),3),
                  execution_s=execution['seconds'], coverage_report_s=report['seconds'],
                  test_process_exit=execution['exit'], execution_timeout=execution.get('timeout',False),
                  external_test_timeout_s=300,
                  coverage_status=('complete_execution' if junit['junit_run_complete'] else
                      ('partial_execution' if executable else 'zero_no_compilable_suite')),
                  zero_coverage_retained=True, failed_tests_pruned=False, **metrics, **junit,
                  overload_binding=binding, source_inventory=str(dest/'source-inventory.json'),
                  compilation_record=str(dest/'compilation.json'), finished_unix=time.time())
    _json(returned, result)
    _json(dest/'result.json', result)
    return result


if __name__=='__main__':
    raise SystemExit('Library only: root runner calls measure(row, tool, out, subject_meta).')
