"""Read final benchmark72.json and export three UTF-8 BOM CSV files.

No experiment, Java, tests, model calls, network or runner imports. Default
mode checks schema and writes no CSV. --final requires exactly 72 methods,
360 completed method/tool records and five all-72 summaries.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import time

HERE = Path(__file__).resolve().parent
TOOLS = ('justin-llm', 'evosuite', 'chatunitest', 'chattester', 'hits')
METRICS = ('line', 'branch', 'instruction', 'method', 'complexity')
OTHER = ('ParameterizedTest', 'RepeatedTest', 'TestFactory', 'TestTemplate')
SAMPLE_FIELDS = ['method_id', 'project_version', 'class_name', 'method_name',
    'full_signature', 'jvm_descriptor', 'return_type', 'original_group',
    'original_source_signature', 'source_start', 'source_end',
    'source_file_sha256', 'focal_classfile_sha256']
RESULT_FIELDS = ['method_id', 'project_version', 'class_name', 'method_name',
    'full_signature', 'jvm_descriptor', 'tool', 'tool_label', 'third_party_reproduction', 'completed']
for _metric in METRICS:
    RESULT_FIELDS += [_metric + '_covered', _metric + '_total', _metric + '_pct']
RESULT_FIELDS += ['generation_seconds', 'generation_exit_code', 'generation_timeout',
    'generation_wall_cap_seconds', 'observed_input_tokens', 'observed_output_tokens',
    'observed_total_tokens', 'usage_complete', 'input_unknown_attempts',
    'output_unknown_attempts', 'total_unknown_attempts', 'attempts_without_usage',
    'input_or_output_token_lower_bound', 'total_token_lower_bound',
    'api_attempts', 'client_requests', 'proxy_retry_attempts',
    'exported_Test_methods', 'exported_ParameterizedTest_methods',
    'exported_RepeatedTest_methods', 'exported_TestFactory_methods',
    'exported_TestTemplate_methods', 'compile_status', 'fully_compiled_suite',
    'exported_source_files', 'compiled_source_files', 'excluded_source_files',
    'compilation_seconds', 'junit_discovered_tests', 'junit_executed_tests',
    'junit_passed_tests', 'junit_failures', 'junit_errors', 'junit_skipped_tests',
    'junit_run_complete', 'execution_timeout', 'execution_seconds',
    'pipeline_success', 'outcome', 'unit_path', 'generation_sha256', 'measurement_sha256']
SUMMARY_FIELDS = ['tool', 'tool_label', 'targets', 'completed_targets']
for _metric in METRICS:
    SUMMARY_FIELDS += [_metric + '_covered', _metric + '_total', _metric + '_micro_pct', _metric + '_macro_pct']
SUMMARY_FIELDS += ['generation_seconds', 'generation_minutes', 'generation_median_seconds',
    'generation_mean_seconds', 'observed_input_tokens', 'observed_output_tokens',
    'observed_total_tokens', 'usage_complete', 'input_or_output_token_lower_bound',
    'total_token_lower_bound', 'attempts_without_usage', 'input_unknown_attempts',
    'output_unknown_attempts', 'total_unknown_attempts', 'api_attempts', 'client_requests',
    'proxy_retry_attempts', 'exported_Test_methods', 'exported_ParameterizedTest_methods',
    'exported_RepeatedTest_methods', 'exported_TestFactory_methods',
    'exported_TestTemplate_methods', 'nonempty_exported_suites', 'fully_compiled_suites',
    'conditional_compilation_pct', 'compile_delivery_pct', 'closed_junit_suites',
    'executed_tests_in_closed_junit', 'passed_tests_in_closed_junit',
    'observed_executed_tests_including_incomplete', 'test_pass_pct_in_closed_junit',
    'pipeline_success_targets', 'pipeline_success_pct', 'generation_timeouts',
    'generation_nonzero_exit_targets', 'unsupported_targets']


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def dump(p, obj):
    p.parent.mkdir(parents=True, exist_ok=True)
    temp = p.with_name(p.name + '.tmp')
    temp.write_text(json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    temp.replace(p)


def require(value, message):
    if not value:
        raise ValueError(message)


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def close(a, b):
    if a is None or b is None:
        return a is b
    return finite(a) and finite(b) and abs(a - b) <= 0.00051


def descriptor_types(desc):
    primitives = dict(B='byte', C='char', D='double', F='float', I='int',
                      J='long', S='short', Z='boolean', V='void')
    def one(pos):
        dims = 0
        while pos < len(desc) and desc[pos] == '[':
            dims += 1
            pos += 1
        require(pos < len(desc), 'Truncated JVM descriptor')
        if desc[pos] == 'L':
            end = desc.index(';', pos)
            name = desc[pos + 1:end].replace('/', '.')
            pos = end + 1
        else:
            name = primitives[desc[pos]]
            pos += 1
        return name + '[]' * dims, pos
    require(desc.startswith('('), 'Method descriptor must start with (')
    parameters, pos = [], 1
    while pos < len(desc) and desc[pos] != ')':
        name, pos = one(pos)
        parameters.append(name)
    require(pos < len(desc) and desc[pos] == ')', 'Method descriptor missing )')
    returned, pos = one(pos + 1)
    require(pos == len(desc), 'Trailing descriptor bytes')
    return parameters, returned


def identities(row):
    params, returned = descriptor_types(row['descriptor'])
    return dict(method_id=row['method_id'], project_version=row['subject'],
        class_name=row['class'], method_name=row['method'],
        full_signature=row['class'] + '#' + row['method'] + '(' + ','.join(params) + ')',
        jvm_descriptor=row['descriptor'], return_type=returned)


def annotation_count(values, name):
    return sum(n for k, n in values.items() if k.split('.')[-1] == name)


def fully_compiled(m):
    return bool(m.get('compile_status') == 'compiled' and m.get('generated_files', 0) > 0
        and m.get('compiled_files') == m.get('generated_files') and not m.get('excluded_files'))


def validate(report, final=False):
    rows = report.get('frozen_rows', report.get('rows', []))
    require(len(rows) == 72, 'Expected exactly 72 sample methods')
    row_map = {r['method_id']: r for r in rows}
    require(len(row_map) == 72, 'Method IDs must be unique')
    keys = {(r['subject'], r['class'], r['method'], r['descriptor']) for r in rows}
    require(len(keys) == 72, 'Exact method identities must be unique')
    for row in rows:
        identities(row)
    records = report.get('records')
    if records is not None:
        expected = {(tool, mid) for tool in TOOLS for mid in row_map}
        observed = [(r['tool'], r['method_id']) for r in records]
        require(len(observed) == 360 and len(set(observed)) == 360 and set(observed) == expected,
                'Expected complete rectangular 5 x 72 record inventory')
        for r in records:
            row = row_map[r['method_id']]
            require(r['subject'] == row['subject'] and r['class_name'] == row['class']
                and r['method'] == row['method'] and r['descriptor'] == row['descriptor'],
                'Per-unit identity differs from sample: ' + str((r['tool'], r['method_id'])))
            if r.get('measurement') is not None:
                for metric in METRICS:
                    m = r['measurement']
                    c, n = m.get(metric + '_covered'), m.get(metric + '_total')
                    require(isinstance(c, int) and isinstance(n, int) and 0 <= c <= n,
                            'Invalid per-unit coverage counters')
            if final:
                require(r.get('complete') and r.get('generation') is not None and r.get('measurement') is not None
                    and r.get('source_cases') is not None and r.get('accounting') is not None,
                    'Incomplete final unit: ' + str((r['tool'], r['method_id'])))
    if final:
        require(report.get('final_ready') is True and report.get('completed_units') == 360,
                'CSV export requires final_ready and all 360 completed units')
        require(report.get('status') in ('complete', 'complete_with_unknown_usage'), 'Final report status is not complete')
        summaries = report.get('summaries', [])
        require(len(summaries) == 5 and {s['tool'] for s in summaries} == set(TOOLS), 'Expected five tool summaries')
        verify_summaries(report)
    return dict(methods=72, expected_units=360, observed_records=len(records) if records is not None else 0,
        complete_records=sum(bool(r.get('complete')) for r in records or []),
        schema_status='passed', final_ready=bool(report.get('final_ready')))


def verify_summaries(report):
    """Reconcile headline exports to final JSON and its 360 records."""
    for s in report['summaries']:
        rr = [r for r in report['records'] if r['tool'] == s['tool']]
        require(len(rr) == s['expected'] == s['completed'] == 72, 'Summary cardinality differs from 72')
        for metric in METRICS:
            covered = sum(r['measurement'][metric + '_covered'] for r in rr)
            total = sum(r['measurement'][metric + '_total'] for r in rr)
            require(s[metric + '_covered'] == covered and s[metric + '_total'] == total,
                    'Summary coverage counters disagree with records: ' + s['tool'])
            require(close(s[metric + '_micro_pct'], 100 * covered / total if total else None),
                    'Summary coverage percentage disagrees with counters: ' + s['tool'])
        require(close(s['generation_s'], sum(r['generation']['seconds'] for r in rr)),
                'Summary generation time differs from records')
        for field in ('input_tokens', 'output_tokens', 'total_tokens', 'api_attempts',
                      'client_requests', 'proxy_retry_attempts', 'attempts_without_usage',
                      'input_unknown_attempts', 'output_unknown_attempts', 'total_unknown_attempts'):
            require(s[field] == sum(r['accounting'][field] for r in rr), 'Summary accounting differs: ' + field)
        require(s['generated_test_methods'] == sum(r['source_cases']['generated_test_methods'] for r in rr),
                'Summary exported @Test count differs from records')
        require(s['generated_source_suites'] == sum(r['measurement'].get('generated_files', 0) > 0 for r in rr),
                'Summary nonempty source suites differ')
        require(s['fully_compiled_suites'] == sum(fully_compiled(r['measurement']) for r in rr),
                'Summary full compilation count differs')
        require(s['pipeline_success_units'] == sum(bool(r['pipeline_success']) for r in rr),
                'Summary pipeline count differs')
        closed = [r['measurement'] for r in rr if r['measurement'].get('junit_run_complete') and r['measurement'].get('tests_executed', 0) > 0]
        require(s['tests_executed'] == sum(m['tests_executed'] for m in closed), 'Closed JUnit executed total differs')
        require(s['tests_passed'] == sum(m['tests_passed'] or 0 for m in closed), 'Closed JUnit passed total differs')
        require(s['usage_complete'] == all(r['accounting']['usage_complete'] for r in rr), 'Summary usage completeness differs')
        require(s['token_totals_are_observed_lower_bounds'] == any(r['accounting']['token_totals_are_observed_lower_bounds'] for r in rr), 'Summary lower-bound flag differs')
        require(s['total_tokens_are_observed_lower_bounds'] == any(r['accounting']['total_tokens_are_observed_lower_bounds'] for r in rr), 'Summary total-token lower-bound flag differs')


def sample_rows(report):
    result = []
    for row in report['frozen_rows']:
        result.append(dict(identities(row), original_group=row.get('original72_group'),
            original_source_signature=row.get('source_signature'), source_start=row.get('source_start'),
            source_end=row.get('source_end'), source_file_sha256=row.get('source_file_sha256'),
            focal_classfile_sha256=(row.get('binary_verification') or {}).get('class_sha256')))
    return result


def result_rows(report):
    result = []
    samples = {r['method_id']: r for r in report['frozen_rows']}
    for r in report['records']:
        row, m, g, c, cases = samples[r['method_id']], r['measurement'], r['generation'], r['accounting'], r['source_cases']
        value = identities(row)
        value.pop('return_type')
        value.update(tool=r['tool'], tool_label=next(s['label'] for s in report['summaries'] if s['tool'] == r['tool']),
            third_party_reproduction=r['tool'] in ('chattester', 'hits'), completed=r['complete'])
        for metric in METRICS:
            for suffix in ('covered', 'total', 'pct'):
                value[metric + '_' + suffix] = m.get(metric + '_' + suffix)
        value.update(generation_seconds=g['seconds'], generation_exit_code=g.get('exit'),
            generation_timeout=bool(g.get('timeout') or g.get('exit') == 124),
            generation_wall_cap_seconds=g.get('wall_cap_s'),
            observed_input_tokens=c['input_tokens'], observed_output_tokens=c['output_tokens'],
            observed_total_tokens=c['total_tokens'], usage_complete=c['usage_complete'],
            input_unknown_attempts=c['input_unknown_attempts'], output_unknown_attempts=c['output_unknown_attempts'],
            total_unknown_attempts=c['total_unknown_attempts'], attempts_without_usage=c['attempts_without_usage'],
            input_or_output_token_lower_bound=c['token_totals_are_observed_lower_bounds'],
            total_token_lower_bound=c['total_tokens_are_observed_lower_bounds'],
            api_attempts=c['api_attempts'], client_requests=c['client_requests'], proxy_retry_attempts=c['proxy_retry_attempts'],
            exported_Test_methods=cases['generated_test_methods'], compile_status=m.get('compile_status'),
            fully_compiled_suite=fully_compiled(m), exported_source_files=m.get('generated_files'),
            compiled_source_files=m.get('compiled_files'), excluded_source_files=m.get('excluded_files'),
            compilation_seconds=m.get('compilation_s'), junit_discovered_tests=m.get('tests_total'),
            junit_executed_tests=m.get('tests_executed'), junit_passed_tests=m.get('tests_passed'),
            junit_failures=m.get('failures'), junit_errors=m.get('errors'), junit_skipped_tests=m.get('skipped'),
            junit_run_complete=m.get('junit_run_complete'), execution_timeout=m.get('execution_timeout'),
            execution_seconds=m.get('execution_s'), pipeline_success=r['pipeline_success'], outcome=r['outcome'],
            unit_path=r['unit_path'], generation_sha256=r.get('generation_sha256'), measurement_sha256=r.get('measurement_sha256'))
        for name in OTHER:
            value['exported_' + name + '_methods'] = annotation_count(cases.get('other_test_annotation_methods', {}), name)
        result.append(value)
    return result


def summary_rows(report):
    result = []
    for s in report['summaries']:
        row = dict(tool=s['tool'], tool_label=s['label'], targets=s['expected'], completed_targets=s['completed'])
        for metric in METRICS:
            for suffix in ('covered', 'total', 'micro_pct', 'macro_pct'):
                row[metric + '_' + suffix] = s.get(metric + '_' + suffix)
        row.update(generation_seconds=s['generation_s'], generation_minutes=s['generation_s'] / 60,
            generation_median_seconds=s['generation_median_s'], generation_mean_seconds=s['generation_mean_s'],
            observed_input_tokens=s['input_tokens'], observed_output_tokens=s['output_tokens'],
            observed_total_tokens=s['total_tokens'], usage_complete=s['usage_complete'],
            input_or_output_token_lower_bound=s['token_totals_are_observed_lower_bounds'],
            total_token_lower_bound=s['total_tokens_are_observed_lower_bounds'],
            attempts_without_usage=s['attempts_without_usage'], input_unknown_attempts=s['input_unknown_attempts'],
            output_unknown_attempts=s['output_unknown_attempts'], total_unknown_attempts=s['total_unknown_attempts'],
            api_attempts=s['api_attempts'], client_requests=s['client_requests'], proxy_retry_attempts=s['proxy_retry_attempts'],
            exported_Test_methods=s['generated_test_methods'], nonempty_exported_suites=s['generated_source_suites'],
            fully_compiled_suites=s['fully_compiled_suites'], conditional_compilation_pct=s['conditional_compile_pct'],
            compile_delivery_pct=s['compile_delivery_pct'], closed_junit_suites=s['closed_junit_suites'],
            executed_tests_in_closed_junit=s['tests_executed'], passed_tests_in_closed_junit=s['tests_passed'],
            observed_executed_tests_including_incomplete=s['observed_tests_including_incomplete'],
            test_pass_pct_in_closed_junit=s['test_pass_pct'], pipeline_success_targets=s['pipeline_success_units'],
            pipeline_success_pct=s['pipeline_success_pct'], generation_timeouts=s['generation_timeouts'],
            generation_nonzero_exit_targets=s['generation_nonzero_exit'], unsupported_targets=s['unsupported_units'])
        for name in OTHER:
            row['exported_' + name + '_methods'] = annotation_count(s.get('other_test_annotation_methods', {}), name)
        result.append(row)
    return result


def cell(value):
    if value is None:
        return ''
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, float):
        require(math.isfinite(value), 'Cannot export NaN or infinity')
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    return str(value)


def export_csv(path, fields, rows):
    require(len(fields) == len(set(fields)), 'Duplicate CSV column names')
    temp = path.with_name(path.name + '.tmp')
    with temp.open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction='raise')
        writer.writeheader()
        for row in rows:
            writer.writerow({k: cell(row.get(k)) for k in fields})
    require(temp.read_bytes().startswith(b'\xef\xbb\xbf'), 'UTF-8 BOM missing')
    with temp.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        require(reader.fieldnames == fields, 'CSV headers changed on round-trip')
        saved = list(reader)
    expected = [{k: cell(row.get(k)) for k in fields} for row in rows]
    require(saved == expected, 'CSV values changed on round-trip')
    temp.replace(path)
    return dict(path=str(path), rows=len(rows), columns=len(fields), sha256=sha(path),
        utf8_BOM_verified=True, exact_string_roundtrip_verified=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--input', type=Path, default=HERE / 'benchmark72.json')
    ap.add_argument('--out', type=Path, default=HERE)
    ap.add_argument('--schema-check', action='store_true')
    ap.add_argument('--final', action='store_true')
    args = ap.parse_args()
    require(not (args.final and args.schema_check), 'Choose --schema-check or --final')
    args.out.mkdir(parents=True, exist_ok=True)
    source = args.input
    if not source.is_file() and not args.final:
        progress = HERE / 'benchmark72-progress.json'
        source = progress if progress.is_file() else HERE / 'frozen-sample.json'
    require(source.is_file(), 'Final benchmark72.json does not exist')
    before = sha(source)
    report = json.loads(source.read_text(encoding='utf-8'))
    checked = validate(report, final=args.final)
    if not args.final:
        value = dict(checked, mode='schema_only_no_CSV_written', source=str(source), source_sha256=before,
            columns=dict(sample72=SAMPLE_FIELDS, results360=RESULT_FIELDS, summary72=SUMMARY_FIELDS),
            script_sha256=sha(__file__), subject_runs=0, model_API_calls=0)
        dump(args.out / 'csv72-schema-validation.json', value)
        print(json.dumps({k: value[k] for k in ('schema_status', 'mode', 'methods', 'expected_units', 'observed_records', 'complete_records')}))
        return
    outputs = [export_csv(args.out / 'sample72.csv', SAMPLE_FIELDS, sample_rows(report)),
        export_csv(args.out / 'results360.csv', RESULT_FIELDS, result_rows(report)),
        export_csv(args.out / 'summary72.csv', SUMMARY_FIELDS, summary_rows(report))]
    require([x['rows'] for x in outputs] == [72, 360, 5], 'Export cardinality changed')
    require(sha(source) == before, 'Final JSON changed during export')
    value = dict(status='passed', created_unix=time.time(), source=str(source), source_sha256=before,
        input_schema=checked, output_files=outputs, summary_values_equal_final_JSON=True,
        no_rounding_before_export=True, all_percentages_use_0_to_100_scale=True,
        missing_numeric_values='Empty CSV cell; preserved independently of true numeric zero.',
        booleans='true/false text', script_sha256=sha(__file__), subject_runs=0, model_API_calls=0)
    dump(args.out / 'csv72-export-validation.json', value)
    print(json.dumps(dict(status='passed', files=[x['path'] for x in outputs], rows=[72, 360, 5])))


if __name__ == '__main__':
    main()
