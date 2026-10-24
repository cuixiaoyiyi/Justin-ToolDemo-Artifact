#!/usr/bin/env python3
"""Read-only target/LLM/zero-probe audit of the fresh 72-method experiment.

Never imports the runner or measurement module, executes a tool, or changes any
run artifact. Only this script's independent audit JSON in HERE is written.
"""
from __future__ import annotations
import collections
import hashlib
import json
from pathlib import Path
import re
import time

HERE = Path(__file__).resolve().parent
ROOT = Path('/home/clm/tooldemo72-20261023')
NATIVE = {'chatunitest', 'chattester', 'hits'}
TOOLS = ['justin-llm', 'evosuite', 'chatunitest', 'chattester', 'hits']


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_jsonl(path):
    path = Path(path)
    rows, errors = [], []
    if path.exists():
        for number, text in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            try:
                rows.append(json.loads(text))
            except json.JSONDecodeError:
                errors.append(number)
    return rows, errors


def main():
    frozen = load(HERE/'frozen-sample.json')
    rows = {r['method_id']: r for r in frozen['rows']}
    selectors = {r['method_id']: r for r in load(HERE/'baseline-selector-verification.json')['selectors']}
    native_starts, selector_issues = [], []
    for path in sorted((ROOT/'runs').rglob('started.json')):
        started = load(path)
        if started['tool'] not in NATIVE:
            continue
        row = started['row']
        expected = selectors[row['method_id']]
        copies = list((path.parent/'classes'/row['method_id']/'tmp').glob(
            '**/'+expected['binary_class'].replace('.', '/')+'/'+str(expected['metadata_id'])+'.json'))
        match = len(copies) == 1 and sha(copies[0]) == expected['metadata_sha256']
        record = dict(tool=started['tool'], method_id=row['method_id'],
                      target_class=row['class'], target_descriptor=row['descriptor'],
                      native_selector=started['selector'], expected_native_selector=expected['native_selector'],
                      selector_matches=started['selector'] == expected['native_selector'],
                      native_metadata_copies=len(copies), copied_metadata_sha_matches=match,
                      target_signature=expected['method_signature'], started_record=str(path))
        native_starts.append(record)
        if not record['selector_matches'] or not match:
            selector_issues.append(record)
    usage, usage_parse_errors = read_jsonl(ROOT/'usage.jsonl')
    model_issues = [{k: r.get(k) for k in ('tool', 'method_id', 'model', 'upstream_model', 'max_tokens', 'thinking')}
                    for r in usage if r.get('model') != 'deepseek-flash'
                    or r.get('upstream_model') != 'deepseek-flash' or r.get('max_tokens') != 4096]
    outcomes = []
    for path in sorted((ROOT/'runs').rglob('measure/result.json')):
        measured = load(path)
        generation_path = path.parent.parent/'done.json'
        generation = load(generation_path) if generation_path.exists() else {}
        generation_log = path.parent.parent/'generation.log'
        log_text = generation_log.read_text(encoding='utf-8', errors='replace') if generation_log.exists() else ''
        native_missing_slice = ('java.io.FileNotFoundException:' in log_text
                                and '/methodSlice/' in log_text and '/slice.json' in log_text)
        native_compilation_failure = bool(re.search(r'compilation failed round\s+\d+', log_text))
        native_repair_error = ('Fixing test for method' in log_text
                               and '[ERROR] Error when generating tests for' in log_text)
        mid = measured['method_id']
        row = rows[mid]
        binding = measured.get('overload_binding') or {}
        binding_path = binding.get('artifact')
        detail = load(binding_path) if binding_path and Path(binding_path).exists() else {}
        own = row['class'].replace('.', '/')
        others = [call for call in detail.get('invocations', [])
                  if call.get('name') == row['method'] and call.get('owner') == own
                  and call.get('descriptor') != row['descriptor']]
        zero = measured.get('line_covered', 0) == 0
        exact_count = binding.get('exact_focal_call_sites', 0)
        reflection_count = binding.get('reflection_call_sites', 0)
        source_paths = sorted((path.parent/'collected-tests').rglob('*.java'))
        assert_throws_sites = sum(len(re.findall(r'\bassertThrows\s*\(', p.read_text(encoding='utf-8', errors='replace')))
                                  for p in source_paths)
        no_export_reason = None
        if not measured.get('generated_files'):
            if native_missing_slice:
                no_export_reason = 'NATIVE_SLICE_ARTIFACT_NOT_SAVED'
            elif native_compilation_failure and native_repair_error:
                no_export_reason = 'NATIVE_COMPILE_REPAIR_ERROR_NO_SAVED_SOURCE'
            elif generation.get('timeout'):
                no_export_reason = 'GENERATION_TIMEOUT_NO_SAVED_SOURCE'
            else:
                no_export_reason = 'NO_SAVED_SOURCE_CAUSE_UNRESOLVED'
        category = None
        if zero:
            if not measured.get('generated_files'):
                category = 'NO_SAVED_GENERATED_JAVA_SOURCE'
            elif measured.get('compile_status') != 'compiled':
                category = 'NO_FULLY_EXECUTABLE_COMPILED_SUITE'
            elif not measured.get('junit_run_complete'):
                category = 'INCOMPLETE_JUNIT_RUN'
            elif not measured.get('tests_executed'):
                category = 'NO_EXECUTED_JUNIT_CASES'
            elif exact_count > 0:
                category = 'EXACT_STATIC_BINDING_WITH_ZERO_FOCAL_PROBES'
            elif others:
                category = 'OTHER_OVERLOAD_STATIC_CALLS_WITHOUT_DIRECT_FOCAL_BINDING'
            else:
                category = 'NO_DIRECT_FOCAL_BINDING_ZERO_PROBES_OTHER_OR_UNRESOLVED'
        record = {k: measured.get(k) for k in (
            'subject', 'tool', 'method_id', 'compile_status', 'generated_files', 'compiled_files',
            'excluded_files', 'tests', 'tests_executed', 'tests_passed', 'failures', 'errors',
            'junit_run_complete', 'line_covered', 'line_total', 'branch_covered', 'branch_total')}
        record.update(target_descriptor=row['descriptor'],
                      selector_correct=next((x['selector_matches'] and x['copied_metadata_sha_matches']
                                             for x in native_starts if x['tool'] == measured['tool']
                                             and x['method_id'] == mid), None),
                      generation_exit=generation.get('exit'), generation_timeout=generation.get('timeout'),
                      native_missing_slice_artifact=native_missing_slice,
                      native_logged_compilation_failure=native_compilation_failure,
                      native_repair_followed_by_generation_error=native_repair_error,
                      no_saved_source_reason=no_export_reason,
                      generation_log=str(generation_log),
                      exact_static_focal_call_sites=exact_count,
                      reflection_call_sites=reflection_count,
                      collected_source_assert_throws_call_sites=assert_throws_sites,
                      collected_sources=[dict(path=str(p), sha256=sha(p)) for p in source_paths],
                      other_same_owner_name_descriptors=sorted({c['descriptor'] for c in others}),
                      other_same_owner_name_call_sites=len(others), zero_focal_line_probes=zero,
                      zero_probe_evidence_category=category, measurement=str(path),
                      measurement_sha256=sha(path), binding_artifact=binding_path)
        outcomes.append(record)
    summaries = {}
    for tool in TOOLS:
        units = [r for r in outcomes if r['tool'] == tool]
        summaries[tool] = dict(measured=len(units),
                               compile_status=dict(collections.Counter(r['compile_status'] for r in units)),
                               zero_line_units=sum(r['zero_focal_line_probes'] for r in units),
                               native_missing_slice_artifact_units=sum(r['native_missing_slice_artifact'] for r in units),
                               no_saved_source_reasons=dict(collections.Counter(
                                   r['no_saved_source_reason'] for r in units if r['no_saved_source_reason'])),
                               generation_timeouts=sum(r['generation_timeout'] is True for r in units),
                               zero_probe_categories=dict(collections.Counter(
                                   r['zero_probe_evidence_category'] for r in units if r['zero_focal_line_probes'])),
                               exact_static_binding_units=sum(r['exact_static_focal_call_sites'] > 0 for r in units),
                               positive_focal_without_direct_binding=sum(not r['zero_focal_line_probes']
                                   and r['exact_static_focal_call_sites'] == 0 for r in units),
                               positive_focal_without_direct_binding_with_reflection=sum(not r['zero_focal_line_probes']
                                   and r['exact_static_focal_call_sites'] == 0 and r['reflection_call_sites'] > 0
                                   for r in units))
    manual = [
        dict(tool='chatunitest', method_id='s0003',
             finding='Native selector #10 correctly identifies append(CharSequence,int,int), but all four saved test calls pass String literals and javac binds append(String,int,int).',
             evidence='Four real invokevirtual instructions for String overload, zero exact CharSequence bindings; four JUnit cases pass, focal probes remain zero.',
             classification='Generated test actual static overload differs from the correctly selected focal method.',
             selector_error=False, infrastructure_error=False),
        dict(tool='chatunitest', method_id='s0002',
             finding='Native selector #8 correctly identifies append(CharBuffer,int,int), and eight compiled call sites bind that exact descriptor; all eight saved cases assert StringIndexOutOfBoundsException for invalid ranges.',
             evidence='Eight passed JUnit cases, eight exact static bindings, zero focal JaCoCo probes. Inputs use CharBuffer.wrap(String), or its read-only view; focal else branch delegates to append(String,int,int).',
             interpretation='The exception-only inputs and source delegation are consistent with exception escape before a later focal probe. No per-instruction runtime trace is retained; the established result is eight exact static call sites, eight passed exception assertions, and zero focal probes.',
             classification='Exact static focal invocation with zero recorded probes under exception-only inputs.',
             selector_error=False, infrastructure_error=False),
        dict(tool='hits', method_ids=['s0003', 's0005'],
              finding='Native generation exits zero but saves no Java tests; HITSRunner.startRounds throws FileNotFoundException while reading its own methodSlice/TextStringBuilder/append/slice.json.',
              classification='This third-party HITS reproduction configuration did not save the native slice artifact before its consumer read it.',
              interpretation='No specific response-format, parser, or algorithm cause is established by the retained sanitized logs.',
              selector_error=False, rerun_or_repair_performed=False),
        dict(tool='chatunitest', method_ids=['s0002', 's0011', 's0013', 's0014', 's0016'],
              finding='Final manual source review confirms all five exact-binding/zero-probe suites consist of exception-assertion tests: 8+2+4+2+2=18 passing cases.',
              evidence='The retained Java sources contain respectively 8,2,4,2,2 assertThrows calls; the independent JUnit result reports the same test/pass counts. Each suite has exact bytecode bindings and zero focal probes.',
              interpretation='All receivers are real TextStringBuilder instances constructed before the assertion lambda. The lambdas have no preliminary factory, mock, spy, or stubbing calls; the char[] argument in s0014 is a one-element literal allocation. Source validation/delegation is consistent with an exception-before-later-probe explanation, but the record contains no per-instruction dynamic trace. The factual conclusion remains 18 passed exception assertions, exact static call sites, and no focal probe.',
              selector_error=False, infrastructure_error=False),
        dict(tool='chattester', method_ids=['s0063', 's0065'],
             finding='Both native pipelines log initial compilation failure at round 0, then a repair attempt at round 1 followed by Error when generating tests; no Java test is exported and Maven process exit remains zero.',
             classification='Native generation/repair did not save test source in this third-party reproduction configuration. External measurement has no source to compile.',
             interpretation='The retained log establishes this stage sequence; it does not establish the internal repair error cause.',
             selector_error=False, rerun_or_repair_performed=False)
    ]
    final_report = HERE/'benchmark72.json'
    final_report_sha = sha(final_report) if final_report.exists() else None
    report = dict(schema='original72-native-exact-target-audit-v1', snapshot_unix=time.time(),
                  status='COMPLETE' if len(outcomes) == 360 else 'IN_PROGRESS_READ_ONLY_SNAPSHOT',
                  expected_units=360, measured_units=len(outcomes),
                  frozen_manifest=str(HERE/'frozen-sample.json'), frozen_manifest_sha256=sha(HERE/'frozen-sample.json'),
                  fixed_final_report=str(final_report) if final_report.exists() else None,
                  fixed_final_report_sha256=final_report_sha,
                  selection_sha256=frozen['selection_sha256'],
                  native_selector_units_checked=len(native_starts), selector_issues=selector_issues,
                  native_selector_records=native_starts,
                  actual_upstream_attempts=len(usage),
                  usage_by_tool_status={tool: dict(collections.Counter(str(r.get('status')) for r in usage if r.get('tool') == tool))
                                        for tool in TOOLS if tool != 'evosuite'},
                  observed_outgoing_models=sorted({str(r.get('upstream_model')) for r in usage}),
                  model_issues=model_issues, usage_snapshot_parse_error_lines=usage_parse_errors,
                  summaries=summaries, outcomes=outcomes, manually_reviewed_examples=manual,
                  interpretation=[
                      'ChatTester and HITS observations describe the frozen third-party ChatUniTest-core 2.1.1 reproduction configuration; they are not claims about original-author tool support.',
                      'Selector correctness is checked against frozen full descriptor metadata; it is separate from the generated tests actual bytecode invocation.',
                      'Static direct binding proves an invoke instruction exists. It does not prove that instruction executed, that the focal body returned, or that JaCoCo recorded a probe.',
                      'Zero direct binding alone does not exclude reflection, inherited-owner resolution, or indirect helper calls. Other-overload categories are bytecode evidence, not a claim that all indirect execution is impossible.',
                      'All five CU exact-binding/zero-probe source suites were manually reviewed for receiver construction and lambda argument evaluation. Their 18 passed exception assertions use real receivers and have no preliminary factory/mock/stubbing calls. No per-instruction dynamic trace is retained, so the primary evidence category remains exact static binding with zero recorded focal probes.',
                      'No ordinary generation failure was repaired, filtered, or rerun for this audit.'],
                  experiment_mutations=0, model_requests=0, new_test_executions=0,
                  script=str(HERE/'audit_native72.py'), script_sha256=sha(HERE/'audit_native72.py'))
    output = HERE/'native-exact-target-audit.json'
    if final_report_sha is not None:
        assert sha(final_report) == final_report_sha, 'Final report changed during read-only audit'
    temporary = output.with_name(output.name+'.tmp')
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    temporary.replace(output)
    print(json.dumps(dict(output=str(output), status=report['status'], measured=len(outcomes),
                          native_selectors_checked=len(native_starts), selector_issues=len(selector_issues),
                          actual_upstream_attempts=len(usage), model_issues=len(model_issues), summaries=summaries)))


if __name__ == '__main__':
    main()
