"""Independent read-only artifact audit for the fixed 72 x five-tool batch.

Parses retained source bytes, SHA256s, JaCoCo/JUnit XML and proxy ledgers.
Does not import the report, runner, Java, generator or measurement modules;
does not execute tests, network requests or model calls.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = Path('/home/clm/tooldemo72-20261023')
TOOLS = ('justin-llm', 'evosuite', 'chatunitest', 'chattester', 'hits')
METRICS = ('LINE', 'BRANCH', 'METHOD', 'INSTRUCTION', 'COMPLEXITY')
ISSUES, TRACKED = [], {}
CHECKS = 0


def check(value, kind, **context):
    global CHECKS
    CHECKS += 1
    if not value:
        ISSUES.append(dict(kind=kind, **context))
    return bool(value)


def close(a, b):
    if a is None or b is None:
        return a is b
    if isinstance(a, bool) or isinstance(b, bool):
        return a is b
    return isinstance(a, (int, float)) and math.isfinite(a) and abs(a - b) <= 0.00051


def digest(p):
    h = hashlib.sha256()
    with Path(p).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def sha(p):
    p = Path(p).resolve()
    value = digest(p)
    TRACKED[str(p)] = value
    return value


def expected_hash(p, value, **context):
    p = Path(p)
    if not check(p.is_file(), 'missing_evidence_file', path=str(p), **context):
        return None
    got = sha(p)
    if value is not None:
        check(got == value, 'sha256_disagreement', path=str(p), expected=value, actual=got, **context)
    return got


def load(p):
    sha(p)
    return json.loads(Path(p).read_text(encoding='utf-8'))


def lines(p):
    p = Path(p)
    if not p.exists():
        return []
    sha(p)
    rows = []
    for n, line in enumerate(p.read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError('JSON line is not a mapping')
            rows.append(value)
        except ValueError as exc:
            check(False, 'malformed_ledger_line', path=str(p), line=n, message=str(exc))
    return rows


def focal(xml, row):
    sha(xml)
    owner = row['class'].replace('.', '/')
    matches = []
    for _, node in ET.iterparse(xml, events=('end',)):
        if node.tag != 'class':
            continue
        if node.get('name') == owner:
            for method in node.findall('method'):
                if (method.get('name'), method.get('desc')) != (row['method'], row['descriptor']):
                    continue
                counters = {c.get('type'): (int(c.get('covered')), int(c.get('missed'))) for c in method.findall('counter')}
                matches.append({k: counters.get(k, (0, 0)) for k in METRICS})
        node.clear()
    check(len(matches) == 1, 'exact_focal_XML_cardinality', path=str(xml), method_id=row['method_id'], matches=len(matches))
    return matches[0] if len(matches) == 1 else None


def mask_java(text):
    # Independent single-pass lexer: comments and string/character literals are
    # replaced before detecting annotations. Newlines are retained for evidence.
    out, i, n = [], 0, len(text)
    while i < n:
        if text.startswith('//', i):
            end = text.find('\n', i + 2)
            end = n if end < 0 else end
        elif text.startswith('/*', i):
            end = text.find('*/', i + 2)
            end = n if end < 0 else end + 2
        elif text.startswith('"""', i):
            end = i + 3
            while end < n:
                if text.startswith('"""', end) and (end == 0 or text[end - 1] != '\\'):
                    end += 3
                    break
                end += 1
        elif text[i] in ('"', "'"):
            quote = text[i]
            end = i + 1
            while end < n:
                if text[end] == '\\':
                    end += 2
                    continue
                if text[end] == quote:
                    end += 1
                    break
                end += 1
        else:
            out.append(text[i])
            i += 1
            continue
        out.extend('\n' if c == '\n' else ' ' for c in text[i:end])
        i = end
    return ''.join(out)


def annotations(text):
    matches = list(re.finditer(r'@\s*((?:[A-Za-z_$][\w$]*\s*\.\s*)*[A-Za-z_$][\w$]*)', mask_java(text)))
    counts = Counter(re.sub(r'\s+', '', x.group(1)) for x in matches)
    return counts, sum(v for k, v in counts.items() if k.split('.')[-1] == 'Test')


def full_compile(m):
    return bool(m.get('compile_status') == 'compiled' and m.get('generated_files', 0) > 0
        and m.get('compiled_files') == m.get('generated_files') and not m.get('excluded_files'))


def junit(dest, execution, identity):
    count = dict(tests=0, failures=0, errors=0, skipped=0)
    reports, valid, oom = [], True, False
    for p in sorted((dest / 'junit').glob('TEST-*.xml')):
        sha(p)
        text = p.read_text(encoding='utf-8', errors='replace')
        try:
            root = ET.fromstring(text)
        except ET.ParseError as exc:
            check(False, 'invalid_JUnit_XML', path=str(p), error=str(exc))
            valid = False
            continue
        declared = {k: int(root.get(k, 0)) for k in count}
        cases = list(root.iter('testcase'))
        observed = dict(tests=len(cases), failures=sum(x.find('failure') is not None for x in cases),
            errors=sum(x.find('error') is not None for x in cases), skipped=sum(x.find('skipped') is not None for x in cases))
        valid = check(declared == observed, 'JUnit_declared_vs_testcases', unit=identity, path=str(p), declared=declared, observed=observed) and valid
        valid = check(min(declared.values()) >= 0 and sum(declared[k] for k in ('failures', 'errors', 'skipped')) <= declared['tests'], 'JUnit_invalid_counts', path=str(p)) and valid
        for k in count:
            count[k] += declared[k]
        oom |= 'OutOfMemoryError' in text
        reports.append(str(p))
    log = dest / 'junit.log'
    logtext = log.read_text(encoding='utf-8', errors='replace') if log.exists() else ''
    if log.exists():
        sha(log)
    failed = count['failures'] + count['errors']
    code = execution.get('exit')
    if reports and ((code == 0 and failed > 0) or (code == 1 and failed == 0)):
        valid = check(False, 'JUnit_exit_vs_XML', unit=identity, exit=code, failed=failed)
    fatal = bool(re.search(r'^Exception in thread "main"|^# A fatal error', logtext, re.M))
    complete = bool(reports) and valid and code in (0, 1) and not execution.get('timeout') and not oom and 'OutOfMemoryError' not in logtext and not fatal
    passed = max(0, count['tests'] - failed - count['skipped'])
    executed = max(0, count['tests'] - count['skipped'])
    return dict(**count, junit_run_complete=bool(complete), tests_executed=executed,
        observed_passed=passed, tests_passed=passed if complete else None,
        test_pass_pct=round(100 * passed / executed, 3) if complete and executed else None)


def unit(root, row, tool, retained, subjects):
    identity = dict(subject=row['subject'], tool=tool, method_id=row['method_id'])
    folder = root / 'runs' / row['subject'] / tool / row['method_id']
    dest = folder / 'measure'
    done, started = load(folder / 'done.json'), load(folder / 'started.json')
    expected_hash(folder / 'generation.log', None, unit=identity)
    for k, v in identity.items():
        check(done.get(k) == v, 'generation_identity', unit=identity, record='done', field=k)
    check(started.get('tool') == tool and started.get('row') == row,
          'generation_started_frozen_row', unit=identity)
    check(done == retained['generation'], 'report_generation_copy', unit=identity)
    # execute() records a millisecond-rounded monotonic duration, followed by
    # a separate time.time() call. The rounding alone contributes up to 0.5 ms;
    # adjacent clock sampling contributes a further small positive offset.
    # Use 1 ms only for this cross-clock check; all scientific metric/summary
    # comparisons retain close()'s original tolerance.
    wall_window = done.get('finished_unix', 0) - done.get('started_unix', 0)
    duration = done.get('seconds')
    check(isinstance(duration, (int, float)) and math.isfinite(duration)
          and abs(duration - wall_window) <= 0.001,
          'generation_duration_window', unit=identity, recorded_seconds=duration,
          wall_window_seconds=wall_window, tolerance_seconds=0.001)
    expected_hash(folder / 'done.json', retained.get('generation_sha256'), unit=identity)
    m = load(dest / 'return.json')
    mirror = load(dest / 'result.json')
    comp = load(dest / 'compilation.json')
    execution = load(dest / 'execution.json')
    inventory = load(dest / 'source-inventory.json')
    inputs = load(dest / 'measurement-input.json')
    check(m == mirror == retained['measurement'], 'measurement_mirror_and_report_copy', unit=identity)
    expected_hash(dest / 'return.json', retained.get('measurement_sha256'), unit=identity)
    for k, v in dict(identity, focal_class=row['class'], focal_method=row['method'], descriptor=row['descriptor']).items():
        check(m.get(k) == v, 'measurement_identity', unit=identity, field=k)
    check(inputs.get('row') == {k: row[k] for k in ('class', 'method', 'descriptor', 'subject', 'method_id')}, 'measurement_input_exact_row', unit=identity)
    fingerprint = {k: v for k, v in inputs.items() if k != 'input_fingerprint'}
    expected_fp = hashlib.sha256(json.dumps(fingerprint, sort_keys=True).encode()).hexdigest()
    check(expected_fp == inputs.get('input_fingerprint') == m.get('input_fingerprint'), 'measurement_input_fingerprint', unit=identity)
    meta = subjects[row['subject']]
    runtime, analysis = sha(meta['jar']), sha(meta['analysis_jar'])
    check(runtime == m.get('runtime_jar_sha256') == inputs.get('runtime_jar_sha256'), 'runtime_jar_sha256', unit=identity)
    check(analysis == m.get('denominator', {}).get('analysis_jar_sha256') == inputs.get('analysis_jar_sha256'), 'analysis_jar_sha256', unit=identity)
    counters = focal(dest / 'coverage.xml', row)
    baseline = focal(m['denominator']['baseline_xml'], row)
    if counters and baseline:
        for metric, (covered, missed) in counters.items():
            key = metric.lower()
            total = covered + missed
            check(covered >= 0 and missed >= 0, 'negative_coverage_counter', unit=identity, metric=metric)
            check(m.get(key + '_covered') == covered and m.get(key + '_total') == total, 'raw_focal_counter', unit=identity, metric=metric)
            check(total == sum(baseline[metric]) == m['denominator']['counters'][metric], 'raw_common_denominator', unit=identity, metric=metric)
            check(close(m.get(key + '_pct'), round(100 * covered / total, 3) if total else None), 'raw_focal_percentage', unit=identity, metric=metric)
    check(m.get('zero_coverage_retained') is True and m.get('common_denominator_verified') is True, 'coverage_failure_retention_policy', unit=identity)
    origins, collected, count = set(), set(), 0
    report_sources = {x['path']: x for x in (retained.get('source_cases') or {}).get('source_files', [])}
    for entry in inventory.get('files', []):
        origin = Path(entry['origin'])
        origins.add(origin.resolve())
        expected_hash(origin, entry.get('origin_sha256'), unit=identity)
        raw = origin.read_text(encoding='utf-8', errors='replace')
        ann, tests = annotations(raw)
        exported = report_sources.get(str(origin))
        check(exported is not None and exported['test_methods'] == tests and exported['sha256'] == sha(origin), 'report_source_case_count', unit=identity, path=str(origin))
        if not entry.get('included', True):
            check(origin.name.endswith('_Suite.java'), 'unexpected_source_inventory_exclusion', unit=identity, path=str(origin))
            continue
        target = Path(entry['collected'])
        expected_hash(target, entry.get('collected_sha256'), unit=identity)
        if target.resolve() not in collected:
            count += tests
        collected.add(target.resolve())
        check(target.resolve().is_relative_to(folder.resolve()), 'collected_source_outside_unit', unit=identity, path=str(target))
        transformed = target.read_text(encoding='utf-8', errors='replace')
        if tool != 'hits' or not entry.get('top_level_type_renamed_from'):
            check(raw == transformed, 'collected_source_changed', unit=identity, path=str(target))
        else:
            old = entry['top_level_type_renamed_from']
            masked = mask_java(raw)
            edits = list(re.finditer(r'\b' + re.escape(old) + r'\b', masked))
            predicted = raw
            for edit in reversed(edits):
                predicted = predicted[:edit.start()] + origin.stem + predicted[edit.end():]
            check(predicted == transformed, 'HITS_change_exceeds_type_identifier_rename', unit=identity, path=str(target))
    check(len(origins) == len(inventory.get('files', [])) == inventory.get('generated_source_files'), 'raw_source_inventory_count', unit=identity)
    check(len(collected) == inventory.get('collected_unique_source_files') == m.get('generated_files'), 'canonical_collected_file_count', unit=identity)
    check(count == (retained.get('source_cases') or {}).get('generated_test_methods'), 'all_source_Test_counts', unit=identity, count=count)
    check(m.get('tests_total', 0) == (retained.get('source_cases') or {}).get('measurement_tests_total'), 'discovered_JUnit_source_cross_reference', unit=identity)
    input_origins = {Path(x['path']).resolve(): x['sha256'] for x in inputs.get('source_inputs', [])}
    check(set(input_origins) == origins, 'measurement_input_source_membership', unit=identity)
    for p, digest_value in input_origins.items():
        expected_hash(p, digest_value, unit=identity)
    active = {Path(p).resolve() for p in comp.get('active_sources', [])}
    excluded = {Path(x['path']).resolve() for x in comp.get('excluded_sources', [])}
    check(active <= collected and excluded <= collected and not active & excluded, 'compiler_retained_source_membership', unit=identity)
    check(m.get('compiled_files') == len(active) and m.get('excluded_files') == len(excluded), 'compiler_source_counts', unit=identity)
    for attempt in comp.get('attempts', []):
        expected_hash(attempt['log'], None, unit=identity)
    check(close(m.get('compilation_s'), round(sum(x['seconds'] for x in comp.get('attempts', [])), 3)), 'compilation_duration', unit=identity)
    check(not comp.get('failed_test_sources_deleted') and m.get('failed_tests_pruned') is False, 'failed_source_or_test_pruning_policy', unit=identity)
    parsed = junit(dest, execution, identity)
    for key, value in parsed.items():
        observed = m.get(key)
        if isinstance(value, float):
            check(close(observed, value), 'raw_JUnit_counter', unit=identity, field=key, expected=value, actual=observed)
        else:
            check(observed == value, 'raw_JUnit_counter', unit=identity, field=key, expected=value, actual=observed)
    check(close(execution.get('seconds'), m.get('execution_s')), 'execution_duration', unit=identity)
    command = execution.get('command') or []
    if command:
        check('-Xmx6g' in command, 'JUnit_heap', unit=identity)
        check(('jdk8' in command[0]) if tool == 'evosuite' else ('jdk-17' in command[0]), 'JUnit_JDK', unit=identity)
        check(any('append=false' in x for x in command if x.startswith('-javaagent:')), 'JaCoCo_exec_append_policy', unit=identity)
        check(Path(execution.get('working_directory', '')).resolve() == dest.resolve(), 'JUnit_isolated_working_directory', unit=identity)
    check(full_compile(m) == ((retained.get('source_cases') is not None) and m.get('compile_status') == 'compiled' and len(collected) > 0 and len(active) == len(collected) and not excluded), 'full_compilation_definition', unit=identity)
    generated_finished = done.get('exit') == 0 and not done.get('timeout') and not done.get('tool_unsupported')
    passed = parsed['junit_run_complete'] and parsed['tests_executed'] > 0 and parsed['tests_passed'] == parsed['tests_executed'] and not parsed['failures'] and not parsed['errors']
    success = bool(generated_finished and full_compile(m) and passed)
    check(success == retained.get('pipeline_success'), 'pipeline_success_definition', unit=identity)
    return dict(**identity, generation=done, measurement=m, generated_test_methods=count,
        pipeline_success=success, raw_counters=counters,
        denominator_signature=(tuple((k, sum(counters[k])) for k in METRICS), runtime, analysis) if counters else None)


def attempt_key(r):
    if r.get('attempt_id'):
        return r.get('proxy_instance'), r['attempt_id']
    return r.get('proxy_instance'), r.get('request_id'), r.get('attempt')


def pair(r):
    explicit = (r['tool'], r['method_id']) if r.get('tool') and r.get('method_id') else None
    parts = r.get('label', '').strip('/').split('/')
    label = tuple(parts) if len(parts) == 2 else None
    check(not explicit or not label or explicit == label, 'proxy_identity_conflict', attempt=attempt_key(r))
    return explicit or label


def nonnegative(v):
    if isinstance(v, int) and not isinstance(v, bool) and v >= 0:
        return v
    if isinstance(v, str) and v.isdigit():
        return int(v)
    return None


def first(*values):
    return next((v for x in values if (v := nonnegative(x)) is not None), None)


def tokens(r):
    u = r.get('usage') or {}
    details = u.get('prompt_tokens_details') or {}
    inp = first(u.get('prompt_tokens'), u.get('input_tokens'), r.get('prompt_tokens'))
    out = first(u.get('completion_tokens'), u.get('output_tokens'), r.get('completion_tokens'))
    cache = first(u.get('prompt_cache_hit_tokens'), details.get('cached_tokens'), u.get('cache_read_input_tokens'), r.get('cached_tokens'))
    miss = first(u.get('prompt_cache_miss_tokens'))
    if inp is None and cache is not None and miss is not None:
        inp = cache + miss
    total = first(u.get('total_tokens'), r.get('total_tokens'))
    if total is None and inp is not None and out is not None:
        total = inp + out
    if inp is not None and out is not None and total is not None:
        check(inp + out == total, 'proxy_token_sum', attempt=attempt_key(r))
    return inp, out, total


def ledger(root, units, report):
    usage, lifecycle = lines(root / 'usage.jsonl'), lines(root / 'proxy-lifecycle.jsonl')
    finished, started, calls = {}, {}, defaultdict(list)
    for r in usage:
        check(r.get('event') in (None, 'attempt_finished'), 'unexpected_usage_event')
        key = attempt_key(r)
        if key in finished:
            check(finished[key] == r, 'conflicting_duplicate_usage_attempt', attempt=key)
        else:
            finished[key] = r
    for r in lifecycle:
        if r.get('event') == 'attempt_started':
            started[attempt_key(r)] = r
    check(not (set(started) - set(finished)), 'unresolved_proxy_attempts', count=len(set(started) - set(finished)))
    check(not (set(finished) - set(started)), 'usage_without_started_attempt', count=len(set(finished) - set(started)))
    overhead = []
    for r in finished.values():
        ident = pair(r)
        if ident and ident[0] in TOOLS and ident[1].startswith(('setup-', 'preflight-')):
            overhead.append(r)
            continue
        if not check(ident in units, 'proxy_identity_outside_frozen_sample', identity=ident):
            continue
        g = units[ident]['generation']
        received = r.get('received_unix')
        if isinstance(received, (int, float)) and g['started_unix'] <= received <= g['finished_unix']:
            calls[ident].append(r)
        else:
            overhead.append(r)
    for r in report['records']:
        ident = (r['tool'], r['method_id'])
        rows = calls[ident]
        values = [tokens(x) for x in rows]
        c = r['accounting']
        check(c['api_attempts'] == len(rows), 'per_unit_API_attempts', identity=ident)
        for i, field in enumerate(('input_tokens', 'output_tokens', 'total_tokens')):
            expected = sum(v[i] or 0 for v in values)
            check(c[field] == expected, 'per_unit_token_sum', identity=ident, field=field, expected=expected, actual=c[field])
        unknown = sum(v[0] is None or v[1] is None for v in values)
        total_unknown = sum(v[2] is None for v in values)
        check(c['attempts_without_usage'] == unknown, 'per_unit_unknown_usage', identity=ident)
        check(c['token_totals_are_observed_lower_bounds'] == (unknown > 0), 'per_unit_usage_lower_bound', identity=ident)
        check(c['total_tokens_are_observed_lower_bounds'] == (total_unknown > 0), 'per_unit_total_lower_bound', identity=ident)
        units[ident]['audited_tokens'] = dict(zip(('input_tokens', 'output_tokens', 'total_tokens'),
            [sum(v[i] or 0 for v in values) for i in range(3)]))
        units[ident]['audited_unknown_usage'] = unknown
        units[ident]['audited_unknown_total'] = total_unknown
    check(sum(len(x) for x in calls.values()) + len(overhead) == len(finished), 'whole_ledger_partition')
    return dict(unique_finished_attempts=len(finished), started_attempts=len(started),
        primary_attempts=sum(len(x) for x in calls.values()),
        setup_or_outside_current_window_attempts=len(overhead))


def summary(tool, units, expected):
    rr = [u for (t, _), u in units.items() if t == tool]
    check(len(rr) == 72, 'tool_full_population', tool=tool, actual=len(rr))
    for metric in METRICS:
        k = metric.lower()
        covered = sum(u['raw_counters'][metric][0] for u in rr)
        total = sum(sum(u['raw_counters'][metric]) for u in rr)
        check(expected[k + '_covered'] == covered and expected[k + '_total'] == total, 'summary_micro_raw_counter_sum', tool=tool, metric=metric)
        check(close(expected[k + '_micro_pct'], 100 * covered / total if total else None), 'summary_micro_ratio', tool=tool, metric=metric)
    checks = dict(generation_s=sum(u['generation']['seconds'] for u in rr),
        generated_test_methods=sum(u['generated_test_methods'] for u in rr),
        generated_source_suites=sum(u['measurement'].get('generated_files', 0) > 0 for u in rr),
        fully_compiled_suites=sum(full_compile(u['measurement']) for u in rr),
        pipeline_success_units=sum(u['pipeline_success'] for u in rr))
    for field, value in checks.items():
        check(close(expected[field], value), 'summary_raw_value', tool=tool, field=field, expected=value, actual=expected[field])
    for field in ('input_tokens', 'output_tokens', 'total_tokens'):
        check(expected[field] == sum(u['audited_tokens'][field] for u in rr), 'summary_token_sum', tool=tool, field=field)
    check(expected['token_totals_are_observed_lower_bounds'] == any(u['audited_unknown_usage'] for u in rr), 'summary_unknown_usage_lower_bound', tool=tool)
    check(expected['total_tokens_are_observed_lower_bounds'] == any(u['audited_unknown_total'] for u in rr), 'summary_unknown_total_lower_bound', tool=tool)


def write(p, value):
    p.parent.mkdir(parents=True, exist_ok=True)
    temp = p.with_name(p.name + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    temp.replace(p)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root', type=Path, default=ROOT)
    ap.add_argument('--out', type=Path, default=HERE)
    ap.add_argument('--report', type=Path)
    ap.add_argument('--final', action='store_true')
    args = ap.parse_args()
    sample = load(args.root / 'frozen-sample.json')
    rows = sample.get('rows', [])
    check(len(rows) == 72 and len({r['method_id'] for r in rows}) == 72, 'frozen_72_unique_methods')
    identities = {(r['subject'], r['class'], r['method'], r['descriptor']) for r in rows}
    check(len(identities) == 72, 'frozen_exact_method_unique_identity')
    pairs = {(tool, row['method_id']) for tool in TOOLS for row in rows}
    done = [(tool, row['method_id']) for tool in TOOLS for row in rows
            if (args.root / 'runs' / row['subject'] / tool / row['method_id'] / 'done.json').exists()
            and (args.root / 'runs' / row['subject'] / tool / row['method_id'] / 'measure/return.json').exists()]
    if not args.final:
        result = dict(schema='original72-audit-progress-v1', created_unix=time.time(),
            expected=360, completed=len(done), mode='read_only_completion_inventory',
            issue_count=len(ISSUES), issues=ISSUES)
        write(args.out / 'audit72-progress.json', result)
        print(json.dumps({k: v for k, v in result.items() if k != 'issues'}))
        return
    report_path = args.report or args.out / 'benchmark72.json'
    report = load(report_path)
    check(len(done) == 360 and set(done) == pairs, 'all_360_completion')
    check(report.get('final_ready') and report.get('completed_units') == 360, 'report_final_ready')
    check(report['sample_sha256'] == sha(args.root / 'frozen-sample.json'), 'report_sample_sha256')
    check(report['frozen_rows'] == rows, 'report_frozen_sample_copy')
    selection = load(HERE / 'frozen-sample.json')
    check(selection['rows'] == rows and selection.get('selection_sha256') == sample.get('selection_sha256'), 'raw_selection_and_runtime_frozen_rows')
    check(report.get('selection_manifest', {}).get('sha256') == sha(HERE / 'frozen-sample.json'), 'raw_selection_manifest_sha256')
    retained = {(r['tool'], r['method_id']): r for r in report.get('records', [])}
    check(len(retained) == len(report.get('records', [])) == 360 and set(retained) == pairs, 'report_all_360_rectangular_records')
    subjects = load(args.root / 'subjects.json')
    if isinstance(subjects, list):
        subjects = {s['sid']: s for s in subjects}
    if isinstance(subjects.get('subjects'), list):
        subjects = {s['sid']: s for s in subjects['subjects']}
    baseline_path = args.root / 'denominators/frozen72-baselines.json'
    baselines = {b['method_id']: b for b in load(baseline_path)}
    check(set(baselines) == {r['method_id'] for r in rows}, 'independent_baseline_72_membership')
    jar_class_cache = {}
    for sid, meta in subjects.items():
        original = sample.get('subjects', {}).get(sid, {})
        if original:
            check(sha(meta['jar']) == original.get('jar_sha256'), 'original72_artifact_version_hash', subject=sid)
            check(sha(meta['sources']) == original.get('sources_sha256'), 'original72_source_release_hash', subject=sid)
    for row in rows:
        mid = row['method_id']
        b = baselines[mid]
        check(b.get('descriptor') == row['descriptor'] and b.get('subject') == row['subject'], 'independent_baseline_identity', method_id=mid)
        expected_hash(b['analysis_jar'], b.get('analysis_jar_sha256'), method_id=mid)
        independent = focal(b['baseline_xml'], row)
        for metric in METRICS:
            check(sum(independent[metric]) == b['counters'][metric], 'independent_baseline_raw_XML', method_id=mid, metric=metric)
        member = row['class'].replace('.', '/') + '.class'
        sha_values = []
        for jar in (row['binary_verification']['jar'], subjects[row['subject']]['jar'], subjects[row['subject']]['analysis_jar'], b['analysis_jar']):
            cachekey = (jar, member)
            if cachekey not in jar_class_cache:
                with zipfile.ZipFile(jar) as archive:
                    jar_class_cache[cachekey] = hashlib.sha256(archive.read(member)).hexdigest()
            sha_values.append(jar_class_cache[cachekey])
        check(len(set(sha_values + [row['binary_verification']['class_sha256']])) == 1,
              'original_runtime_independent_classfile_equivalence', method_id=mid)
        expected_hash(row['source_file'], row.get('source_file_sha256'), method_id=mid)
    if sample.get('source_report') and sample.get('source_report_sha256'):
        expected_hash(sample['source_report'], sample['source_report_sha256'], context='original72_selection_source')
    units = {}
    for row in rows:
        for tool in TOOLS:
            ident = (tool, row['method_id'])
            if ident not in retained:
                continue
            try:
                units[ident] = unit(args.root, row, tool, retained[ident], subjects)
            except (OSError, ValueError, KeyError, ET.ParseError) as exc:
                check(False, 'unit_audit_exception', tool=tool, method_id=row['method_id'], exception=type(exc).__name__, message=str(exc))
    for row in rows:
        signatures = {u['denominator_signature'] for (_, mid), u in units.items() if mid == row['method_id']}
        check(len(signatures) == 1 and None not in signatures, 'cross_tool_common_exact_denominator', method_id=row['method_id'])
    try:
        ledger_stats = ledger(args.root, units, report)
    except (OSError, ValueError, KeyError) as exc:
        check(False, 'ledger_audit_exception', exception=type(exc).__name__, message=str(exc))
        ledger_stats = {}
    for s in report.get('summaries', []):
        try:
            summary(s['tool'], units, s)
        except (KeyError, TypeError) as exc:
            check(False, 'summary_audit_exception', tool=s.get('tool'), exception=type(exc).__name__, message=str(exc))
    changed = []
    for p, expected in list(TRACKED.items()):
        if not Path(p).is_file() or digest(p) != expected:
            changed.append(p)
    check(not changed, 'scientific_inputs_changed_during_audit', paths=changed)
    result = dict(schema='original72-independent-artifact-audit-v1',
        created_unix=time.time(), status='passed' if not ISSUES else 'issues_found',
        read_only=True, subject_runs=0, model_API_calls=0,
        expected_units=360, completed_units=len(done), artifact_units_audited=len(units),
        checks=CHECKS, issue_count=len(ISSUES), issues=ISSUES,
        report=dict(path=str(report_path), sha256=TRACKED[str(report_path.resolve())]),
        sample_sha256=sha(args.root / 'frozen-sample.json'), ledger=ledger_stats,
        audited_files=[dict(path=p, sha256=s) for p, s in sorted(TRACKED.items())],
        audit_script_sha256=sha(__file__),
        scope='Independent raw SHA256/source annotations/JUnit and JaCoCo XML/token ledger re-reading; generation-selector correctness additionally documented by the frozen preparation validation.')
    write(args.out / 'audit72.json', result)
    print(json.dumps({k: result[k] for k in ('status', 'artifact_units_audited', 'checks', 'issue_count')}))
    if ISSUES:
        raise SystemExit(2)


if __name__ == '__main__':
    main()
