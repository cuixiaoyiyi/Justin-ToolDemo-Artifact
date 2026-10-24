"""Read-only consolidation of the fixed original 72-method x five-tool batch.

No Java, generation, tests, network, model calls or runner imports.  --final
writes the paper's two tables only after all 360 cells and proxy accounting
have completed.  Failures and timeouts stay in the requested-target scope.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import html
import json
import math
from pathlib import Path
import re
import statistics
import time
import xml.etree.ElementTree as ET
import zipfile

from report_accounting import (read_jsonl, index_usage, cost_summary,
    partition_generation_windows, split_auxiliary_records, whole_ledger_cost)

HERE = Path(__file__).resolve().parent
ROOT = Path('/home/clm/tooldemo72-20261023')
TOOLS = ('justin-llm', 'evosuite', 'chatunitest', 'chattester', 'hits')
LABELS = dict(zip(TOOLS, ('Justin', 'EvoSuite', 'ChatUniTest',
                        'ChatTester reproduction', 'HITS reproduction')))
METRICS = ('LINE', 'BRANCH', 'METHOD', 'INSTRUCTION', 'COMPLEXITY')
COST_FIELDS = ('api_attempts', 'client_requests', 'proxy_retry_attempts',
    'input_tokens', 'output_tokens', 'total_tokens', 'cached_tokens',
    'cache_miss_tokens', 'attempts_without_usage', 'input_unknown_attempts',
    'output_unknown_attempts', 'total_unknown_attempts', 'pending_attempts',
    'upstream_attempt_seconds')
OTHER_TESTS = {'ParameterizedTest', 'RepeatedTest', 'TestFactory', 'TestTemplate'}
ANNOTATION = re.compile(r'@\s*((?:[A-Za-z_$][\w$]*\s*\.\s*)*[A-Za-z_$][\w$]*)\b')
# Java comments, strings, characters and text blocks cannot introduce tests.
LITERALS = re.compile(r'"""[\s\S]*?"""|/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27')


def sha(p):
    p = Path(p)
    if not p.is_file():
        return None
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()


def dump(p, value):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2,
                              sort_keys=True) + '\n', encoding='utf-8')
    tmp.replace(p)


def read(p, issues, default=None, required=False):
    p = Path(p)
    if not p.is_file():
        if required:
            issues.append(dict(kind='missing_required_file', path=str(p)))
        return default
    try:
        return json.loads(p.read_text(encoding='utf-8'))
    except (ValueError, OSError) as exc:
        issues.append(dict(kind='json_read_error', path=str(p), error=str(exc)))
        return default


def number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def integer(v):
    return isinstance(v, int) and not isinstance(v, bool) and v >= 0


def pct(n, d):
    return 100 * n / d if d else None


def full_compile(m):
    return bool(m and m.get('compile_status') == 'compiled'
        and (m.get('generated_files') or 0) > 0
        and m.get('compiled_files') == m.get('generated_files')
        and not m.get('excluded_files'))


def closed(m):
    return bool(m and m.get('junit_run_complete') and (m.get('tests_executed') or 0) > 0)


def passing(m):
    return bool(closed(m) and m.get('tests_passed') == m.get('tests_executed')
                and not m.get('failures') and not m.get('errors'))


def finished(g):
    return bool(g and g.get('exit') == 0 and not g.get('timeout') and not g.get('tool_unsupported'))


def validate_sample(sample):
    issues = []
    rows = sample.get('rows', [])
    if len(rows) != 72:
        issues.append(dict(kind='sample_count', expected=72, actual=len(rows)))
    keys = [r.get('method_id') for r in rows]
    if len(set(keys)) != len(keys) or None in keys:
        issues.append(dict(kind='nonunique_or_missing_method_id'))
    identities = [(r.get('subject'), r.get('class'), r.get('method'), r.get('descriptor')) for r in rows]
    if len(set(identities)) != len(identities):
        issues.append(dict(kind='nonunique_exact_method_identity'))
    for r in rows:
        for k in ('subject', 'class', 'method', 'descriptor'):
            if not r.get(k):
                issues.append(dict(kind='missing_focal_identity', method_id=r.get('method_id'), field=k))
        if not re.fullmatch(r'\([^)]*\).+', r.get('descriptor', '')):
            issues.append(dict(kind='invalid_jvm_descriptor', method_id=r.get('method_id')))
    return dict(passed=not issues, methods=len(rows), expected_units=360,
                subjects=dict(Counter(r.get('subject') for r in rows)), issues=issues)


def focal(xml, row):
    owner = row['class'].replace('.', '/')
    matches = []
    for _, node in ET.iterparse(xml, events=('end',)):
        if node.tag != 'class':
            continue
        if node.get('name') == owner:
            for m in node.findall('method'):
                if (m.get('name'), m.get('desc')) == (row['method'], row['descriptor']):
                    found = {c.get('type'): dict(covered=int(c.get('covered')),
                        missed=int(c.get('missed'))) for c in m.findall('counter')}
                    matches.append({k: found.get(k, dict(covered=0, missed=0)) for k in METRICS})
        node.clear()
    if len(matches) != 1:
        raise ValueError('Exact focal descriptor has ' + str(len(matches)) + ' matches: ' + str(xml))
    return matches[0]


def source_cases(unit, m, issues):
    path = unit / 'measure/source-inventory.json'
    if not m:
        return None
    inv = read(path, issues, {}, required=True)
    files, annotations, raw_cases, selected_cases = [], Counter(), 0, 0
    selected_other = Counter()
    collected_seen = set()
    for f in inv.get('files', []):
        origin = Path(f['origin'])
        actual = sha(origin)
        if actual is None or actual != f.get('origin_sha256'):
            issues.append(dict(kind='original_source_sha256_mismatch', path=str(origin)))
        if not origin.is_file():
            continue
        text = origin.read_text(encoding='utf-8', errors='replace')
        masked = LITERALS.sub(lambda x: ' ' * len(x.group()), text)
        counts = Counter(re.sub(r'\s+', '', a) for a in ANNOTATION.findall(masked))
        tests = sum(v for k, v in counts.items() if k.split('.')[-1] == 'Test')
        other = {k: v for k, v in counts.items() if k.split('.')[-1] in OTHER_TESTS}
        annotations.update(counts)
        raw_cases += tests
        selected = f.get('included', True)
        if selected:
            collected = Path(f['collected'])
            actual_collected = sha(collected)
            if actual_collected != f.get('collected_sha256'):
                issues.append(dict(kind='collected_source_sha256_mismatch', path=str(collected)))
            if str(collected.resolve()) not in collected_seen:
                selected_cases += tests
                selected_other.update(other)
                collected_seen.add(str(collected.resolve()))
        files.append(dict(path=str(origin), sha256=actual, test_methods=tests,
            included_in_external_measurement=bool(selected),
            collected=f.get('collected'), collected_sha256=f.get('collected_sha256'),
            annotations=dict(counts)))
    # tests_total counts discovered JUnit XML cases. A source declaration can
    # expand to many invocations or fail before discovery, so this comparison
    # is descriptive and never an equality requirement.
    reported = m.get('tests_total')
    if len(files) != inv.get('generated_source_files', len(files)):
        issues.append(dict(kind='source_inventory_file_count', unit=str(unit)))
    if len(collected_seen) != inv.get('collected_unique_source_files', len(collected_seen)):
        issues.append(dict(kind='collected_inventory_file_count', unit=str(unit)))
    return dict(inventory_path=str(path), inventory_sha256=sha(path),
        raw_exported_test_methods=raw_cases, generated_test_methods=selected_cases,
        other_test_annotation_methods=dict(selected_other),
        measurement_tests_total=reported,
        plain_source_count_matches_discovered_junit=selected_cases == reported,
        source_files=files,
        annotation_counts=dict(annotations),
        policy='Original exported @Test declarations, masked comments/literals; aggregate suite classes excluded by the canonical measurement inventory; unique collected source identities.')


def classify(g, m):
    if g is None:
        return 'generation_pending'
    if m is None:
        return 'measurement_pending'
    if g.get('tool_unsupported'):
        return 'unsupported_native_selector'
    if g.get('timeout') or g.get('exit') == 124:
        return 'generation_timeout'
    if g.get('exit') not in (0, None):
        return 'generation_nonzero_exit'
    if not m.get('generated_files'):
        return 'no_exported_source'
    if not full_compile(m):
        return 'compilation_incomplete'
    if not closed(m):
        return 'junit_incomplete_or_empty'
    if not passing(m):
        return 'test_failure'
    if not m.get('line_covered'):
        return 'zero_focal_line_coverage'
    return 'pipeline_success'


def summarize(tool, selected, records, baselines):
    ids = {r['method_id'] for r in selected}
    rr = [r for r in records if r['tool'] == tool and r['method_id'] in ids]
    mm = [r['measurement'] for r in rr if r['measurement'] is not None]
    gg = [r['generation'] for r in rr if r['generation'] is not None]
    costs = [r['accounting'] for r in rr]
    sum_m = lambda k: sum(m.get(k, 0) or 0 for m in mm)
    durations = [g['seconds'] for g in gg if number(g.get('seconds'))]
    complete = [m for m in mm if closed(m)]
    result = dict(tool=tool, label=LABELS[tool], expected=len(selected),
        completed=sum(r['complete'] for r in rr),
        generation_s=sum(durations), generation_median_s=statistics.median(durations) if durations else None,
        generation_mean_s=statistics.mean(durations) if durations else None,
        generated_test_methods=sum((r['source_cases'] or {}).get('generated_test_methods', 0) for r in rr),
        raw_exported_test_methods=sum((r['source_cases'] or {}).get('raw_exported_test_methods', 0) for r in rr),
        other_test_annotation_methods=dict(sum((Counter((r['source_cases'] or {}).get('other_test_annotation_methods', {})) for r in rr), Counter())),
        generated_source_suites=sum((m.get('generated_files') or 0) > 0 for m in mm),
        fully_compiled_suites=sum(full_compile(m) for m in mm),
        pipeline_success_units=sum(r['pipeline_success'] for r in rr),
        generation_timeouts=sum(bool(g.get('timeout') or g.get('exit') == 124) for g in gg),
        generation_nonzero_exit=sum(g.get('exit') not in (None, 0) for g in gg),
        unsupported_units=sum(bool(g.get('tool_unsupported')) for g in gg),
        closed_junit_suites=len(complete),
        tests_executed=sum(m.get('tests_executed') or 0 for m in complete),
        tests_passed=sum(m.get('tests_passed') or 0 for m in complete),
        observed_tests_including_incomplete=sum_m('tests_executed'),
        compilation_s=sum_m('compilation_s'), execution_s=sum_m('execution_s'),
        outcome_counts=dict(Counter(r['outcome'] for r in rr)))
    for metric in METRICS:
        key = metric.lower()
        covered = sum_m(key + '_covered')
        total = sum(baselines[mid]['counters'][metric] for mid in ids if mid in baselines)
        ratios = [100 * m[key + '_covered'] / m[key + '_total'] for m in mm if m.get(key + '_total', 0) > 0]
        result.update({key + '_covered': covered, key + '_total': total,
            key + '_micro_pct': pct(covered, total),
            key + '_macro_pct': statistics.mean(ratios) if ratios else None})
    for field in COST_FIELDS:
        result[field] = sum(c.get(field, 0) for c in costs)
    result.update(usage_complete=all(c['usage_complete'] for c in costs),
        token_totals_are_observed_lower_bounds=any(c['token_totals_are_observed_lower_bounds'] for c in costs),
        total_tokens_are_observed_lower_bounds=any(c['total_tokens_are_observed_lower_bounds'] for c in costs),
        compile_delivery_pct=pct(result['fully_compiled_suites'], len(selected)),
        conditional_compile_pct=pct(result['fully_compiled_suites'], result['generated_source_suites']),
        pipeline_success_pct=pct(result['pipeline_success_units'], len(selected)),
        test_pass_pct=pct(result['tests_passed'], result['tests_executed']))
    return result


def collect(root):
    root = Path(root)
    issues = []
    paths = {n: root / n for n in ('frozen-sample.json', 'subjects.json', 'protocol.json',
        'implementation.json', 'state.json', 'usage.jsonl', 'proxy-lifecycle.jsonl',
        'decisions.jsonl', 'execution-provenance.json', 'resource-summary.json')}
    paths['selection-manifest'] = HERE / 'frozen-sample.json'
    for name in ('implementation.json',):
        if not paths[name].exists():
            paths[name] = HERE / name
    candidates = [root / 'denominators/frozen72-baselines.json',
                  root / 'denominators/frozen341-baselines.json']
    paths['baselines'] = next((p for p in candidates if p.is_file()), candidates[0])
    before = {n: sha(p) for n, p in paths.items()}
    sample = read(paths['frozen-sample.json'], issues, {}, required=True)
    selection = read(paths['selection-manifest'], issues, {}, required=True)
    if sample.get('selection_sha256') != selection.get('selection_sha256') or sample.get('rows') != selection.get('rows'):
        issues.append(dict(kind='raw_selection_vs_runtime_frozen_sample_disagreement'))
    schema = validate_sample(sample)
    rows = sample.get('rows', [])
    protocol = dict(sample.get('protocol', {}))
    protocol.update(read(paths['protocol.json'], issues, {}) or {})
    tools = protocol.get('tools', list(TOOLS))
    if set(tools) != set(TOOLS) or len(tools) != len(TOOLS):
        issues.append(dict(kind='tool_set', actual=tools))
    state = read(paths['state.json'], issues, {}) or {}
    implementation = read(paths['implementation.json'], issues, {}) or {}
    subjects = read(paths['subjects.json'], issues, {}) or {}
    if isinstance(subjects, list):
        subjects = {s['sid']: s for s in subjects}
    if isinstance(subjects.get('subjects'), list):
        subjects = {s['sid']: s for s in subjects['subjects']}
    baseline_rows = read(paths['baselines'], issues, [], required=True) or []
    baselines = {b['method_id']: b for b in baseline_rows}
    for row in rows:
        if row['method_id'] not in baselines:
            issues.append(dict(kind='missing_independent_denominator', method_id=row['method_id']))
    pairs = {(tool, row['method_id']) for row in rows for tool in tools}
    generations, raw_records = {}, []
    for row in rows:
        for tool in tools:
            unit = root / 'runs' / row['subject'] / tool / row['method_id']
            g = read(unit / 'done.json', issues)
            m = read(unit / 'measure/return.json', issues)
            if g is not None:
                generations[(tool, row['method_id'])] = g
            raw_records.append((row, tool, unit, g, m))
    usage, up = read_jsonl(paths['usage.jsonl'])
    lifecycle, lp = read_jsonl(paths['proxy-lifecycle.jsonl'])
    mu, ml, au, al, apairs = split_auxiliary_records(usage, lifecycle, tools)
    by, pending, usage_audit = index_usage(mu, ml, pairs)
    aby, apending, aux_audit = index_usage(au, al, apairs)
    excluded = []
    for folder in ('preflight-invalid', 'setup-invalid'):
        base = root / folder
        for p in sorted(base.rglob('done.json')) if base.exists() else []:
            g = read(p, issues, {}) or {}
            excluded.append(dict(g, path=str(p)))
    primary, ppending, window_audit = partition_generation_windows(by, pending, generations, excluded)
    accounting = {pair: cost_summary(primary[pair], ppending[pair]) for pair in pairs}
    records, missing, denominator_checks = [], [], []
    for row, tool, unit, g, m in raw_records:
        mid = row['method_id']
        identity = dict(method_id=mid, subject=row['subject'], tool=tool)
        complete = g is not None and m is not None
        if not complete:
            missing.append(dict(identity, generation=g is not None, measurement=m is not None))
        source = source_cases(unit, m, issues)
        denom_issues = []
        if m:
            expected = dict(identity, focal_class=row['class'], focal_method=row['method'], descriptor=row['descriptor'])
            if any(m.get(k) != v for k, v in expected.items()):
                denom_issues.append('focal_identity_mismatch')
            base = baselines.get(mid)
            for metric in METRICS:
                key = metric.lower()
                covered, total = m.get(key + '_covered'), m.get(key + '_total')
                if not integer(covered) or not integer(total) or covered > total:
                    denom_issues.append('invalid_' + key + '_counter')
                if not base or total != base.get('counters', {}).get(metric):
                    denom_issues.append('independent_' + key + '_denominator_mismatch')
            if not m.get('common_denominator_verified'):
                denom_issues.append('measurement_common_denominator_unverified')
            if base:
                try:
                    member = row['class'].replace('.', '/') + '.class'
                    with zipfile.ZipFile(base['analysis_jar']) as a, zipfile.ZipFile(m['denominator']['analysis_jar']) as b:
                        base_class_sha = hashlib.sha256(a.read(member)).hexdigest()
                        measured_class_sha = hashlib.sha256(b.read(member)).hexdigest()
                    if base_class_sha != measured_class_sha:
                        denom_issues.append('analysis_focal_classfile_sha256_mismatch')
                except (OSError, KeyError, zipfile.BadZipFile) as exc:
                    denom_issues.append('analysis_focal_classfile_unreadable:' + type(exc).__name__)
            expected_runtime = subjects.get(row['subject'], {}).get('jar_sha256') or row.get('binary_jar_sha256')
            if expected_runtime and m.get('runtime_jar_sha256') != expected_runtime:
                denom_issues.append('runtime_jar_sha256_mismatch')
        denominator_checks.append(dict(identity, passed=bool(m) and not denom_issues, issues=denom_issues))
        records.append(dict(identity, class_name=row['class'], method=row['method'], descriptor=row['descriptor'],
            complete=complete, generation=g, measurement=m, accounting=accounting[(tool, mid)],
            source_cases=source, pipeline_success=finished(g) and full_compile(m) and passing(m),
            outcome=classify(g, m), unit_path=str(unit),
            generation_sha256=sha(unit / 'done.json'), measurement_sha256=sha(unit / 'measure/return.json')))
    summaries = [summarize(t, rows, records, baselines) for t in tools]
    project_groups = [dict(subject=sid, methods=sum(r['subject'] == sid for r in rows),
        summaries=[summarize(t, [r for r in rows if r['subject'] == sid], records, baselines) for t in tools])
        for sid in sorted({r['subject'] for r in rows})]
    usage_audit['parse_issues'] = up + lp
    after = {n: sha(p) for n, p in paths.items()}
    changed = [n for n in before if before[n] != after[n]]
    audit_issues = issues + schema['issues'] + usage_audit['issues'] + usage_audit['unmatched_usage_records'] + up + lp + aux_audit['issues'] + aux_audit['unmatched_usage_records'] + window_audit['issues']
    pending_count = len(usage_audit['pending_attempts']) + len(aux_audit['pending_attempts'])
    pending_count += len(window_audit['awaiting_generation_records']) + len(window_audit['awaiting_pending_records'])
    if missing or len(records) != 360:
        status = 'incomplete'
    elif audit_issues or not all(c['passed'] for c in denominator_checks):
        status = 'needs_audit'
    elif pending_count:
        status = 'waiting_for_proxy_accounting'
    elif changed:
        status = 'live_snapshot_inputs_changed'
    elif state.get('status') != 'complete':
        status = 'awaiting_runner_completion'
    else:
        status = 'complete_with_unknown_usage' if whole_ledger_cost(usage, lifecycle)['attempts_without_usage'] else 'complete'
    return dict(schema='original72-method-benchmark-v1', created_unix=time.time(),
        status=status, final_ready=status in ('complete', 'complete_with_unknown_usage'),
        root=str(root), expected_units=360, completed_units=sum(r['complete'] for r in records),
        sample_sha256=after['frozen-sample.json'], frozen_rows=rows, schema_validation=schema,
        selection_manifest=dict(path=str(paths['selection-manifest']),
            sha256=after['selection-manifest'], selection_sha256=selection.get('selection_sha256'),
            relationship='Raw selection manifest; runtime frozen manifest separately includes execution protocol amendments. The selected rows and selection SHA256 must remain equal.'),
        protocol=protocol, implementation=implementation, runner_state=state,
        batch_elapsed_wall_s=state['finished_unix'] - state['started_unix'] if number(state.get('finished_unix')) and number(state.get('started_unix')) else None,
        summaries=summaries, subject_summaries=project_groups, records=records, missing=missing,
        independent_baselines=baseline_rows, denominator_checks=denominator_checks,
        usage_audit=usage_audit, generation_window_accounting=window_audit,
        auxiliary_accounting=dict(audit=aux_audit,
            summary=cost_summary([r for v in aby.values() for r in v], [r for v in apending.values() for r in v]),
            finished_records=au, pending_records=[r for v in apending.values() for r in v]),
        all_attempt_observed_cost=whole_ledger_cost(usage, lifecycle),
        issues=audit_issues, changed_input_files=changed,
        input_files={n: dict(path=str(p), sha256=after[n]) for n, p in paths.items()},
        report_code_sha256=sha(HERE / 'report72.py'), accounting_code_sha256=sha(HERE / 'report_accounting.py'),
        metric_definitions=dict(
            population='All original 72 exact methods at original artifact versions, 72 targets per tool, no post-generation filtering.',
            coverage='Micro: summed exact-focal covered counters / common independently frozen target counters. Failures, no exports and timeouts remain in scope.',
            generation_time='Sum observed per-target wall seconds; includes failed/time-limited generations, excludes external compilation/execution. Concurrency means this is not elapsed batch time.',
            tokens='Reported input + output tokens across all upstream attempts (including retries) received within retained generation windows. Cached input is included once. Missing usage yields observed lower bounds, never zero-cost inference.',
            cases='Exported @Test methods in canonical source inventory, verified from original bytes and source SHA256 after masking comments and literals; unique collected sources exclude aggregate suite duplicates.',
            nonplain_tests='ParameterizedTest/RepeatedTest/TestFactory/TestTemplate declarations are reported separately; generated @Test method counts and discovered/executed JUnit cases use different units and need not be equal.',
            compilation='Full exported suite compiled: generated_files > 0, compiled_files == generated_files and no measurement exclusions. Conditional rate divides by nonempty source suites; delivery divides by all 72 targets.',
            test_pass='Passed / executed cases among closed nonempty JUnit runs only. Incomplete executions remain separately recorded.',
            pipeline_success='Normal generation completion, fully compiled nonempty suite, complete JUnit run with every executed case passing.'))


def fmt(v, digits=2):
    return '\u2014' if v is None else f'{v:,.{digits}f}'


def html_table(headers, rows):
    esc = lambda v: html.escape(str(v))
    return '<table><thead><tr>' + ''.join('<th>' + esc(x) + '</th>' for x in headers) + '</tr></thead><tbody>' + ''.join('<tr>' + ''.join('<td>' + esc(x) + '</td>' for x in row) + '</tr>' for row in rows) + '</tbody></table>'


def render(result):
    esc = lambda v: html.escape(str(v))
    s = result['summaries']
    head = '<!doctype html><html lang="zh"><meta charset="utf-8"><title>Original 72-method comparison</title><style>body{font:15px/1.55 system-ui,sans-serif;max-width:1350px;margin:30px auto;padding:0 20px;color:#162431}table{border-collapse:collapse;width:100%;margin:16px 0}td,th{padding:8px;border-bottom:1px solid #d9e1e8;text-align:right}td:first-child,th:first-child{text-align:left}th{background:#eaf0f5}code{font-size:12px;overflow-wrap:anywhere}details{border:1px solid #d9e1e8;margin:8px 0;padding:10px}summary{cursor:pointer}p{max-width:1000px}.note{background:#f2f5f7;padding:12px}</style><h1>固定原72方法：同批五工具比较</h1>'
    head += '<p>状态：' + esc(result['status']) + '；已完成 ' + str(result['completed_units']) + '/360。覆盖率、开销、case 均来自本批相同72个目标；完整保留生成失败、超时和零覆盖。ChatTester 与 HITS 使用已记录的第三方复现。</p>'
    head += '<h2>表1 · 覆盖率（全部72方法）</h2>'
    head += html_table(['工具', '行覆盖率 %', '行命中/总数', '分支覆盖率 %', '分支命中/总数'],
        [[x['label'], fmt(x['line_micro_pct']), f"{x['line_covered']}/{x['line_total']}", fmt(x['branch_micro_pct']), f"{x['branch_covered']}/{x['branch_total']}"] for x in s])
    head += '<h2>表2 · 累计生成开销与输出（相同72方法）</h2>'
    def token(x, k):
        lower = x['total_tokens_are_observed_lower_bounds'] if k == 'total_tokens' else x[k.replace('_tokens', '_unknown_attempts')] > 0 or x['pending_attempts'] > 0
        return ('\u2265' if lower else '') + f'{x[k]:,}'
    head += html_table(['工具', '生成时间 min', 'Input token', 'Output token', '总 Token', '生成 @Test cases'],
        [[x['label'], fmt(x['generation_s'] / 60), token(x, 'input_tokens'), token(x, 'output_tokens'), token(x, 'total_tokens'), f"{x['generated_test_methods']:,}"] for x in s])
    head += '<p class="note">时间为各目标生成 wall time 的累加，包含失败和超时。Token 计入请求重试；若服务未返回 usage，显示已观测下界 \u2265。Case 从原始导出 Java 源码逐文件核对哈希并数 @Test 声明，排除注释、字符串和聚合 suite 重复；生成数与执行数分别记录。</p>'
    head += '<h2>输出可用性和失败保留</h2>'
    head += html_table(['工具', '非空导出套件', '完整编译', '条件编译率 %', '编译交付率 %', '闭合JUnit', '执行cases', '通过率 %', 'Pipeline成功/72', '生成超时'],
        [[x['label'], x['generated_source_suites'], x['fully_compiled_suites'], fmt(x['conditional_compile_pct']), fmt(x['compile_delivery_pct']), x['closed_junit_suites'], x['tests_executed'], fmt(x['test_pass_pct']), x['pipeline_success_units'], x['generation_timeouts']] for x in s])
    head += '<p>条件编译率分母为非空导出套件；编译交付率和 pipeline 成功率以全部72目标为分母。测试通过率只使用完整闭合且非空的 JUnit 执行，不把缺失报告作为通过。</p>'
    for group in result['subject_summaries']:
        head += '<details><summary>' + esc(group['subject']) + ' · ' + str(group['methods']) + ' 个方法</summary>'
        head += html_table(['工具', 'Line %', 'Branch %', 'Min.', 'Token', '@Test'], [[x['label'], fmt(x['line_micro_pct']), fmt(x['branch_micro_pct']), fmt(x['generation_s'] / 60), token(x, 'total_tokens'), x['generated_test_methods']] for x in group['summaries']]) + '</details>'
    head += '<h2>完整逐方法记录</h2>'
    for row in result['frozen_rows']:
        rr = [r for r in result['records'] if r['method_id'] == row['method_id']]
        head += '<details><summary><code>' + esc(row['method_id'] + ' ' + row['class'] + '#' + row['method'] + row['descriptor']) + '</code></summary>'
        head += '<p>' + esc(row['subject']) + '</p>'
        rows = []
        for r in rr:
            m, g, c = r['measurement'] or {}, r['generation'] or {}, r['accounting']
            rows.append([LABELS[r['tool']], r['outcome'], fmt(m.get('line_pct')), fmt(m.get('branch_pct')), fmt(g.get('seconds')), c['input_tokens'], c['output_tokens'], (r['source_cases'] or {}).get('generated_test_methods'), bool(m.get('junit_run_complete'))])
        head += html_table(['工具', '结局', 'Line %', 'Branch %', '时间 s', 'Input', 'Output', 'cases', 'JUnit完整'], rows) + '</details>'
    head += '<h2>账本与证据</h2><p><a href="benchmark72.json">完整 JSON 及证据路径</a> · <a href="audit72.json">独立审计</a>。冻结样本 SHA256：<code>' + esc(result['sample_sha256']) + '</code>。</p>'
    head += '<p>主实验之外的 setup/preflight、无有效生成窗口或未完成请求费用单独保留在 JSON 中；不混入表2，也不删除。批次实际 elapsed wall time 为 ' + fmt(result['batch_elapsed_wall_s']) + ' 秒。</p></html>'
    return head


def paper_tables(result):
    s = result['summaries']
    def name(x):
        return {'justin-llm': 'Justin', 'evosuite': 'EvoSuite', 'chatunitest': 'ChatUniTest',
                'chattester': r'ChatTester\reimpl{}', 'hits': r'HITS\reimpl{}'}[x['tool']]
    lines = [r'% Generated by report72.py from benchmark72.json; do not enter data manually.',
        '% Sample SHA256: ' + result['sample_sha256'],
        r'\begin{table}[t]', r'\caption{Exact-method micro coverage on all 72 targets (\%).}',
        r'\label{tab:sequence}', r'\centering\footnotesize', r'\setlength{\tabcolsep}{10pt}',
        r'\begin{tabular}{lrr}\hline', r'\textbf{Tool} & \textbf{Line} & \textbf{Branch} \\\hline']
    lines += [name(x) + ' & ' + fmt(x['line_micro_pct']) + ' & ' + fmt(x['branch_micro_pct']) + r' \\' for x in s]
    lines += [r'\hline\end{tabular}', r'\par\smallskip\raggedright\footnotesize',
        r'\reimpl{} Third-party reimplementation of the cited approach.', r'\end{table}', '',
        r'\begin{table}[t]', r'\caption{Cumulative generation cost and exported test cases on the same 72 targets.}',
        r'\label{tab:cost}', r'\centering\footnotesize', r'\setlength{\tabcolsep}{3pt}',
        r'\begin{tabular}{lrrr}\hline',
        r'\textbf{Tool} & \textbf{Min.} & \textbf{Total tokens} & \textbf{Cases} \\\hline']
    for x in s:
        def tok(k):
            lower = x['total_tokens_are_observed_lower_bounds'] if k == 'total_tokens' else x[k.replace('_tokens', '_unknown_attempts')] > 0
            return (r'$\geq$' if lower else '') + f'{x[k]:,}'
        lines.append(name(x) + ' & ' + fmt(x['generation_s'] / 60, 1) + ' & ' + tok('total_tokens') + ' & ' + f"{x['generated_test_methods']:,}" + r' \\')
    lines += [r'\hline\end{tabular}', r'\par\smallskip\raggedright\footnotesize',
        r'Cases count exported \code{@Test} methods; $\geq$ denotes observed usage lower bounds. \reimpl{} as in Table~\ref{tab:sequence}.', r'\end{table}']
    return '\n'.join(lines) + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root', type=Path, default=ROOT)
    ap.add_argument('--out', type=Path, default=HERE)
    ap.add_argument('--final', action='store_true')
    ap.add_argument('--schema-check', action='store_true')
    args = ap.parse_args()
    if args.schema_check:
        issues = []
        path = args.root / 'frozen-sample.json'
        value = validate_sample(read(path, issues, {}, required=True))
        value.update(read_issues=issues, sample_sha256=sha(path),
            mode='static_schema_check_no_experiment', report_code_sha256=sha(__file__))
        dump(args.out / 'report72-schema-validation.json', value)
        print(json.dumps(value, ensure_ascii=False))
        return
    result = collect(args.root)
    if args.final and result['final_ready']:
        dump(args.out / 'benchmark72.json', result)
        (args.out / 'benchmark72.html').write_text(render(result), encoding='utf-8')
        (args.out / 'paper-tables72.tex').write_text(paper_tables(result), encoding='utf-8')
        print(json.dumps(dict(status=result['status'], completed=result['completed_units'],
            json=str(args.out / 'benchmark72.json'), html=str(args.out / 'benchmark72.html'))))
    else:
        dump(args.out / 'benchmark72-progress.json', result)
        print(json.dumps(dict(status=result['status'], completed=result['completed_units'],
            expected=360, issue_count=len(result['issues']), final_written=False)))
        if args.final:
            raise SystemExit(2)


if __name__ == '__main__':
    main()
