"""Read-only outcome/mechanism analysis of the unchanged original72 cohort.

Consumes benchmark72.json (or an explicitly allowed progress snapshot). No
generators, test execution, compilation, network, model or rerun entry points.
Only analysis72[-progress].json is written. Source-level diagnostics require
actual compiled exact-call bindings; a method name never establishes a cause.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import statistics
import time

HERE = Path(__file__).resolve().parent
TOOLS = ['justin-llm','evosuite','chatunitest','chattester','hits']
LABELS = dict(zip(TOOLS,['Justin','EvoSuite','ChatUniTest','ChatTester reproduction','HITS reproduction']))
METRICS = ['LINE','BRANCH']
LEX = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def load(p): return json.loads(Path(p).read_text(encoding='utf-8'))
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def pct(n,d): return 100*n/d if d else None
def number(v): return isinstance(v,(int,float)) and not isinstance(v,bool)
def masked(t): return LEX.sub(lambda m:''.join('\n' if c=='\n' else ' ' for c in m.group()),t)


def fully_compiled(m):
    return bool(m and m.get('compile_status')=='compiled' and m.get('generated_files',0)>0
                and m.get('compiled_files')==m.get('generated_files') and not m.get('excluded_files'))


def closed(m):
    return bool(m and m.get('junit_run_complete') and m.get('tests_executed',0)>0)


def arguments(desc):
    """Exact JVM argument names; reference/array identity is retained."""
    primitive=dict(Z='boolean',B='byte',C='char',S='short',I='int',J='long',F='float',D='double')
    i=1; result=[]
    while desc[i]!=')':
        start=i; dims=0
        while desc[i]=='[': dims+=1; i+=1
        reference=bool(dims) or desc[i]=='L'
        if desc[i]=='L':
            end=desc.index(';',i); name=desc[i+1:end].replace('/','.'); i=end+1
        else: name=primitive[desc[i]]; i+=1
        result.append(dict(type=name+'[]'*dims,descriptor=desc[start:i],reference=reference))
    return result


def call_spans(text, name):
    """Find qualified same-name calls with balanced argument spans.

    These are only candidate locations; bytecode source lines below establish
    exact overload membership. Unresolved/ambiguous locations remain unknown.
    """
    view=masked(text); out=[]
    pattern=re.compile(r'\.\s*'+re.escape(name)+r'\s*\(')
    for match in pattern.finditer(view):
        begin=match.end(); stack=['(']; parts=[]; p=begin; j=begin
        while j<len(view) and stack:
            c=view[j]
            if c in '([{': stack.append(c)
            elif c in ')]}':
                expected={')':'(',']':'[','}':'{'}[c]
                if stack[-1]!=expected: break
                stack.pop()
                if not stack:
                    if text[begin:j].strip(): parts.append(text[p:j].strip())
                    out.append(dict(start=match.start(),end=j+1,
                        start_line=view.count('\n',0,match.start())+1,
                        end_line=view.count('\n',0,j)+1,args=parts,source=text[match.start():j+1]))
                    break
            elif c==',' and len(stack)==1:
                parts.append(text[p:j].strip()); p=j+1
            j+=1
    return out


def literal_null(expression):
    # Parentheses/casts may be emitted around null. No inference from variables.
    expression=LEX.sub(lambda m:'' if m.group().startswith(('//','/*')) else m.group(),expression).strip()
    while expression!='null':
        if not expression.startswith('('): return False
        depth=0; end=None
        for i,c in enumerate(expression):
            if c=='(': depth+=1
            elif c==')':
                depth-=1
                if depth==0: end=i; break
        if end is None: return False
        inside=expression[1:end].strip()
        if end==len(expression)-1:
            expression=inside; continue
        if inside=='null' or not re.fullmatch(r'[\w.$\[\]<>?,\s]+',inside): return False
        expression=expression[end+1:].strip()
    return True


def unit_diagnostics(record, row):
    """Index direct observations; explanations without evidence stay unknown."""
    m=record.get('measurement'); g=record.get('generation'); unit=Path(record['unit_path'])
    d=dict(method_id=row['method_id'],tool=record['tool'],evidence=[],observations=[],
           cause='unknown',interpretation='No causal diagnosis established from method name or coverage alone.')
    if g is None or m is None:
        d['observations'].append('generation_or_measurement_pending'); return d
    if g.get('timeout') or g.get('exit')==124: d['observations'].append('generation_wall_timeout')
    if g.get('exit') not in (None,0): d['observations'].append('generation_nonzero_exit')
    if not m.get('generated_files'):
        d['observations'].append('no_exported_java_source')
        d['cause']='no_exported_source'
        d['interpretation']='No exported Java suite was available for external compilation/execution; generator-internal cause requires separate log evidence.'
    elif not fully_compiled(m):
        d['observations'].append('exported_suite_not_fully_compiled')
        d['cause']='compilation_incomplete'
    if not closed(m): d['observations'].append('junit_incomplete_or_empty')
    if m.get('oom_in_log') or m.get('oom_in_reports'): d['observations'].append('oom_in_external_execution_evidence')
    if m.get('execution_timeout'): d['observations'].append('external_execution_timeout')
    if m.get('tests_executed',0) and m.get('tests_passed')!=m.get('tests_executed'):
        d['observations'].append('executed_test_failures_or_errors')
    if not m.get('line_covered'): d['observations'].append('zero_focal_line_coverage')
    elif m.get('line_covered',0)<m.get('line_total',0): d['observations'].append('partial_focal_line_coverage')
    invocation=unit/'measure/compiled-method-invocations.json'
    d['exact_focal_call_sites']=(m.get('overload_binding') or {}).get('exact_focal_call_sites',0)
    if invocation.is_file():
        calls=load(invocation)
        d['evidence'].append(dict(kind='compiled_method_invocations',path=str(invocation),sha256=sha(invocation)))
        exact=calls.get('exact_focal_call_sites',[])
        other=[c for c in calls.get('invocations',[]) if c.get('name')==row['method']
               and (c.get('owner')==row['class'].replace('.','/') or c.get('resolved_declaring_owner_in_subject')==row['class'].replace('.','/'))
               and c.get('descriptor')!=row['descriptor']]
        d['other_overload_direct_call_sites']=len(other)
        if not exact and not m.get('line_covered') and other and fully_compiled(m):
            d['observations'].append('other_overload_calls_without_exact_binding_or_focal_coverage')
            d['other_overload_evidence']=[{k:c.get(k) for k in ['caller_class','caller_method','source_line','name','descriptor','owner']} for c in other]
            d['interpretation']='Generated bytecode invokes other overloads of this owner/name, has no direct exact focal binding and produces no focal probes. This is direct overload-omission evidence; it does not prove that every other-overload call was intended as the focal action.'
            d['cause']='focal_overload_omitted_in_fully_compiled_direct_calls'
        elif not exact and not m.get('line_covered') and other:
            d['observations'].append('partial_compilation_prevents_overload_omission_conclusion')
        elif not exact and m.get('line_covered'):
            d['observations'].append('focal_coverage_without_direct_binding_indirect_or_dynamic_reachability')
        # Link only exact compiled invoke sites to their collected source lines.
        inv_path=unit/'measure/source-inventory.json'
        if exact and inv_path.is_file():
            inventory=load(inv_path); files=[x for x in inventory.get('files',[]) if x.get('included')]
            mapped=[]; unresolved=[]; sources={}; types=arguments(row['descriptor'])
            for call in exact:
                base=call.get('caller_class','').split('/')[-1].split('$')[0]+'.java'
                candidates=[x for x in files if Path(x['collected']).name==base]
                if len(candidates)!=1:
                    unresolved.append(dict(call=call,reason='source_file_missing_or_ambiguous')); continue
                item=candidates[0]; p=Path(item['collected'])
                if not p.is_file() or sha(p)!=item.get('collected_sha256'):
                    unresolved.append(dict(call=call,reason='source_sha_unverified')); continue
                if str(p) not in sources:
                    text=p.read_text(encoding='utf-8'); sources[str(p)]=call_spans(text,row['method'])
                line=call.get('source_line')
                hits=[c for c in sources[str(p)] if line is not None and c['start_line']<=line<=c['end_line'] and len(c['args'])==len(types)]
                if len(hits)!=1:
                    unresolved.append(dict(caller=call.get('caller_method'),source_line=line,reason='call_span_ambiguous_or_arity_unresolved')); continue
                c=hits[0]
                mapped.append(dict(source=str(p),source_sha256=item['collected_sha256'],source_line=line,
                    caller=call.get('caller_method'),arguments=c['args'],source_call=c['source'],
                    literal_null_parameters=[i for i,a in enumerate(c['args']) if types[i]['reference'] and literal_null(a)]))
            d['source_binding_resolution']=dict(exact_binding_count=len(exact),resolved_count=len(mapped),unresolved_count=len(unresolved),
                unresolved=unresolved,mapped_calls=mapped)
            if mapped and not unresolved and len(mapped)==len(exact):
                all_null=[dict(parameter_index=i,type=p['type']) for i,p in enumerate(types)
                          if p['reference'] and all(i in c['literal_null_parameters'] for c in mapped)]
                if all_null:
                    d['observations'].append('all_exact_focal_calls_pass_literal_null_for_reference_parameter')
                    d['all_literal_null_parameters']=all_null
                    if m.get('line_covered',0)<m.get('line_total',0):
                        d['cause']='direct_focal_calls_have_no_nonnull_inputs_for_observed_reference_parameters'
                        d['interpretation']='Every source-resolved direct exact focal invoke passes literal null for the listed reference parameters. This proves absence of non-null inputs at those direct sites. Indirect or dynamic calls could still pass other values; the internal reason no non-null direct case was retained remains unknown, and this observation alone does not establish the cause of every uncovered branch.'
    if record['tool']=='justin-llm':
        log=unit/'generation.log'
        if log.is_file():
            text=log.read_text(errors='replace')
            d['llm_enabled_log_evidence']='LLM annotation inference enabled' in text
            match=re.search(r'LLM:\s+(\d+) calls, (\d+) cache hits, (\d+) failures',text)
            if match: d['llm_native_log_counts']=dict(calls=int(match[1]),cache_hits=int(match[2]),failures=int(match[3]))
            rejected=re.search(r'^Rejected:\s+(\{[^\n]*\})',text,re.M)
            if rejected: d['rejected_candidate_log']=rejected[1]
            d['evidence'].append(dict(kind='generation_log',path=str(log),sha256=sha(log)))
    return d


def compact(record, baseline):
    m=record.get('measurement') or {}; g=record.get('generation') or {}; cost=record.get('accounting') or {}
    coverage={}
    for metric in METRICS:
        k=metric.lower(); total=baseline['counters'][metric]
        if record.get('measurement') is not None:
            if not (m.get('common_denominator_verified') and m.get(k+'_total')==total
                    and (m.get('denominator') or {}).get('counters',{}).get(metric)==total
                    and isinstance(m.get(k+'_covered'),int) and not isinstance(m.get(k+'_covered'),bool)
                    and 0<=m[k+'_covered']<=total):
                raise ValueError('Invalid or unverified independent focal denominator: '+record['tool']+'/'+record['method_id']+'/'+metric)
        value=m.get(k+'_covered') if record.get('measurement') is not None else None
        coverage[k]=dict(covered=value,total=total,pct=pct(value,total) if value is not None else None)
    return dict(tool=record['tool'],complete=record['complete'],outcome=record['outcome'],coverage=coverage,
        generation_s=g.get('seconds'),input_tokens=cost.get('input_tokens'),output_tokens=cost.get('output_tokens'),
        total_tokens=cost.get('total_tokens'),usage_complete=cost.get('usage_complete',False),
        total_tokens_are_observed_lower_bounds=cost.get('total_tokens_are_observed_lower_bounds',False),
        api_attempts=cost.get('api_attempts',0),attempts_without_usage=cost.get('attempts_without_usage',0),
        generated_test_methods=(record.get('source_cases') or {}).get('generated_test_methods'),
        full_compile=fully_compiled(record.get('measurement')),closed_nonempty_junit=closed(record.get('measurement')),
        tests_executed=m.get('tests_executed'),tests_passed=m.get('tests_passed'),
        exact_focal_call_sites=(m.get('overload_binding') or {}).get('exact_focal_call_sites'),
        unit_path=record['unit_path'])


def pairwise(selected, by_method):
    result=[]
    for other in TOOLS[1:]:
        counts={k:Counter() for k in ['line','branch','generation_s','total_tokens','generated_test_methods']}
        coverage_relation=Counter(); rows=[]
        for mid in selected:
            j=by_method[mid]['justin-llm']; o=by_method[mid][other]; comparisons={}
            for metric in ['line','branch']:
                a=j['coverage'][metric]; b=o['coverage'][metric]
                if not j['complete'] or not o['complete'] or a['covered'] is None or b['covered'] is None:
                    outcome='pending'
                elif a['total']==0: outcome='not_applicable'
                else: outcome='win' if a['covered']>b['covered'] else 'loss' if a['covered']<b['covered'] else 'tie'
                counts[metric][outcome]+=1; comparisons[metric]=outcome
            if all(comparisons[x] in {'win','loss','tie','not_applicable'} for x in ['line','branch']):
                values=[comparisons[x] for x in ['line','branch'] if comparisons[x]!='not_applicable']
                if all(x=='tie' for x in values): relation='tie'
                elif 'win' in values and 'loss' not in values: relation='Justin_dominates'
                elif 'loss' in values and 'win' not in values: relation='other_dominates'
                else: relation='mixed_line_branch_tradeoff'
            else: relation='pending'
            coverage_relation[relation]+=1
            for metric in ['generation_s','total_tokens','generated_test_methods']:
                a,b=j[metric],o[metric]
                if not j['complete'] or not o['complete'] or a is None or b is None: outcome='pending'
                elif metric=='total_tokens' and not (j['usage_complete'] and o['usage_complete']): outcome='unknown_usage_incomparable'
                elif a==b: outcome='equal' if metric=='generated_test_methods' else 'tie'
                elif metric=='generated_test_methods': outcome='higher' if a>b else 'lower'
                else: outcome='win' if a<b else 'loss'
                counts[metric][outcome]+=1; comparisons[metric]=outcome
            rows.append(dict(method_id=mid,comparisons=comparisons,coverage_relation=relation))
        result.append(dict(Justin_against=other,methods=len(selected),counts={k:dict(v) for k,v in counts.items()},
                           joint_line_branch_relation=dict(coverage_relation),per_method=rows))
    return result


def aggregate(selected, by_method, baselines):
    result=[]
    for tool in TOOLS:
        rr=[by_method[mid][tool] for mid in selected]
        s=dict(tool=tool,label=LABELS[tool],methods=len(selected),completed=sum(r['complete'] for r in rr),
               outcome_counts=dict(Counter(r['outcome'] for r in rr)),
               generation_s=sum(r['generation_s'] or 0 for r in rr),
               generated_test_methods=sum(r['generated_test_methods'] or 0 for r in rr),
               full_compile_units=sum(r['full_compile'] for r in rr),closed_nonempty_junit_units=sum(r['closed_nonempty_junit'] for r in rr),
               tests_executed_closed_runs=sum(r['tests_executed'] or 0 for r in rr if r['closed_nonempty_junit']),
               tests_passed_closed_runs=sum(r['tests_passed'] or 0 for r in rr if r['closed_nonempty_junit']))
        for metric in ['line','branch']:
            covered=sum(r['coverage'][metric]['covered'] or 0 for r in rr)
            total=sum(baselines[mid]['counters'][metric.upper()] for mid in selected)
            rates=[r['coverage'][metric]['pct'] for r in rr if r['coverage'][metric]['pct'] is not None]
            s[metric]=dict(covered=covered,total=total,micro_pct=pct(covered,total),macro_pct=statistics.mean(rates) if rates else None,
                          pending_units=sum(r['coverage'][metric]['covered'] is None for r in rr))
        for key in ['input_tokens','output_tokens','total_tokens']:
            s[key]=sum(r[key] or 0 for r in rr)
        s['usage_complete']=all(r['usage_complete'] for r in rr)
        s['total_tokens_are_observed_lower_bounds']=any(r['total_tokens_are_observed_lower_bounds'] for r in rr)
        s['generation_median_s']=statistics.median([r['generation_s'] for r in rr if r['generation_s'] is not None]) if any(r['generation_s'] is not None for r in rr) else None
        s['test_pass_pct_closed_runs']=pct(s['tests_passed_closed_runs'],s['tests_executed_closed_runs'])
        result.append(s)
    return dict(methods=len(selected),summaries=result,Justin_pairwise=pairwise(selected,by_method))


def analyze(path, allow_progress=False):
    report=load(path); final=bool(report.get('final_ready'))
    if not final and not allow_progress: raise ValueError('Input is not final_ready; use --allow-progress for explicitly preliminary analysis')
    rows=report['frozen_rows']; records=report['records']; baselines={r['method_id']:r for r in report['independent_baselines']}
    assert len(rows)==72 and len(records)==360 and len(baselines)==72
    expected={(r['method_id'],t) for r in rows for t in TOOLS}
    assert {(r['method_id'],r['tool']) for r in records}==expected and len(expected)==360
    assert len({(r['subject'],r['class'],r['method'],r['descriptor']) for r in rows})==72
    denominator_checks={(c['method_id'],c['tool']):c for c in report['denominator_checks']}
    for record in records:
        if record.get('measurement') is not None:
            check=denominator_checks.get((record['method_id'],record['tool']))
            if not check or not check['passed'] or check.get('issues'):
                raise ValueError('Input report marks focal denominator check invalid: '+record['tool']+'/'+record['method_id'])
    if final: assert report['completed_units']==360 and all(r['complete'] for r in records)
    by_method={r['method_id']:{} for r in rows}; diagnostics=[]; raw_by_pair={}
    for r in records:
        mid=r['method_id']; raw_by_pair[(mid,r['tool'])]=r
        by_method[mid][r['tool']]=compact(r,baselines[mid])
    method_rows=[]
    mechanism_path=HERE/'justin-minimization-mechanism72.json'
    confirmed_mechanisms=[]
    if mechanism_path.exists():
        mechanism=load(mechanism_path)
        # Manual implementation review is external evidence, not a guessed
        # diagnosis from the current method name or an aggregate coverage score.
        for item in mechanism.get('observed_current_run',{}).get('evidence',[]):
            if not Path(item['path']).is_file() or sha(item['path'])!=item['sha256']:
                raise ValueError('Confirmed mechanism evidence changed: '+item['path'])
        confirmed_mechanisms.append(dict(path=str(mechanism_path),sha256=sha(mechanism_path),
            status=mechanism['status'],method_id=mechanism['method_id'],
            conclusion=mechanism['confirmed_conclusion'],limits=mechanism['limits']))
    for row in rows:
        mid=row['method_id']
        ds=[unit_diagnostics(raw_by_pair[(mid,t)],row) for t in TOOLS]
        for diagnosis in ds:
            if diagnosis['tool']=='justin-llm':
                diagnosis['confirmed_implementation_notes']=[m for m in confirmed_mechanisms if m['method_id']==mid]
        diagnostics.extend(ds)
        method_rows.append(dict(method_id=mid,subject=row['subject'],class_name=row['class'],method=row['method'],descriptor=row['descriptor'],
            original72_group=row['original72_group'],source_start=row['source_start'],source_end=row['source_end'],
            source_file=row['source_file'],source_file_sha256=row['source_file_sha256'],
            tools=[by_method[mid][t] for t in TOOLS],diagnostics=ds))
    all_ids=[r['method_id'] for r in rows]
    overall=aggregate(all_ids,by_method,baselines)
    projects=[]; classes=[]; groups=[]
    for sid in sorted({r['subject'] for r in rows}):
        ids=[r['method_id'] for r in rows if r['subject']==sid]
        projects.append(dict(subject=sid,**aggregate(ids,by_method,baselines)))
    for sid,owner in sorted({(r['subject'],r['class']) for r in rows}):
        ids=[r['method_id'] for r in rows if (r['subject'],r['class'])==(sid,owner)]
        classes.append(dict(subject=sid,class_name=owner,**aggregate(ids,by_method,baselines)))
    for group in sorted({r['original72_group'] for r in rows}):
        ids=[r['method_id'] for r in rows if r['original72_group']==group]
        groups.append(dict(original72_group=group,**aggregate(ids,by_method,baselines)))
    sensitivity_ids=[mid for mid in all_ids if all(by_method[mid][t]['full_compile'] and by_method[mid][t]['closed_nonempty_junit'] for t in TOOLS)]
    sensitivity=dict(selection='All five tools fully compile their nonempty exported suites and finish a closed nonempty JUnit run. Failed tests, zero coverage and generation exit codes are not additional filters.',
        interpretation='Post-outcome conditional sensitivity only; cannot replace all72 primary population or support unconditional reliability/coverage claims.',
        method_ids=sensitivity_ids,**aggregate(sensitivity_ids,by_method,baselines))
    summary_by_tool={r['tool']:r for r in overall['summaries']}
    validation=[]
    for original in report['summaries']:
        actual=summary_by_tool[original['tool']]
        for metric in ['line','branch']:
            validation.append(dict(tool=original['tool'],metric=metric,
                passed=actual[metric]['covered']==original[metric+'_covered'] and actual[metric]['total']==original[metric+'_total']))
        for key in ['generation_s','input_tokens','output_tokens','total_tokens','generated_test_methods']:
            validation.append(dict(tool=original['tool'],metric=key,passed=abs(actual[key]-original[key])<1e-6))
    assert all(x['passed'] for x in validation), [x for x in validation if not x['passed']]
    return dict(schema='original72-independent-analysis-v1',created_unix=time.time(),
        status='FINAL_COMPLETE' if final else 'PRELIMINARY_INCOMPLETE_DO_NOT_USE_AS_FINAL',
        final_ready=final,completed_units=report['completed_units'],expected_units=360,
        input_report=dict(path=str(Path(path).resolve()),sha256=sha(path),status=report['status']),
        selection_manifest=report['selection_manifest'],execution_manifest_sha256=report['sample_sha256'],
        analysis_code_sha256=sha(Path(__file__)),overall=overall,projects=projects,classes=classes,
        original_descriptive_groups=groups,methods=method_rows,all_five_compile_closed_sensitivity=sensitivity,
        diagnostic_index=dict(observation_counts_by_tool={t:dict(Counter(o for d in diagnostics if d['tool']==t for o in d['observations'])) for t in TOOLS},
                              cause_counts_by_tool={t:dict(Counter(d['cause'] for d in diagnostics if d['tool']==t)) for t in TOOLS},
                              observations=[dict(method_id=d['method_id'],tool=d['tool'],observations=d['observations'],cause=d['cause']) for d in diagnostics if d['observations']]),
        primary_summary_crosschecks=validation,confirmed_implementation_mechanisms=confirmed_mechanisms,
        existing_measurements_with_independent_denominator_revalidated=sum(r.get('measurement') is not None for r in records),
        interpretation_rules=[
            'Main population is all72 exact original method/version targets; no outcome-based removal or favorable subset selection.',
            'Coverage W/T/L compares covered counters on the same focal denominator. Zero-branch methods are N/A, not wins or ties.',
            'Generation time and token W/T/L use lower-is-less-cost orientation. Failures remain in these comparisons; a low cost is not evidence of useful output.',
            'Token W/T/L requires both usage totals complete. Incomplete/lower-bound usage remains incomparable; observed totals are retained.',
            'Case counts use higher/equal/lower, not efficacy wins. More cases do not establish higher coverage or better assertions.',
            'Source call diagnosis requires exact compiled binding, source line, verified collected source SHA and unambiguous argument spans. Missing evidence stays unknown.',
            'Literal-null-only calls prove absence of non-null inputs at resolved direct focal sites. Indirect/dynamic inputs and whether the constructor, solver or minimizer caused omission remain unknown without further evidence.',
            'Other-overload invocation evidence is distinct from generation failure, no export, compile failure and incomplete JUnit execution.',
            'One seed/repetition is descriptive; no statistical superiority or stability claim follows from this analysis.',
            'Progress snapshots are incomplete and pending coverage is explicitly flagged; final claims require all360 complete.'],
        no_model_calls=True,no_generation=True,no_subject_execution=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,default=HERE/'benchmark72.json')
    parser.add_argument('--allow-progress',action='store_true')
    args=parser.parse_args(); result=analyze(args.input,args.allow_progress)
    name='analysis72.json' if result['final_ready'] else 'analysis72-progress.json'
    p=HERE/name; tmp=p.with_suffix('.json.tmp')
    tmp.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8'); tmp.replace(p)
    print(json.dumps(dict(output=str(p),status=result['status'],completed=result['completed_units'],
        methods=len(result['methods']),projects=len(result['projects']),classes=len(result['classes']),
        sensitivity_methods=result['all_five_compile_closed_sensitivity']['methods'],
        summary_crosschecks=len(result['primary_summary_crosschecks'])),indent=2))


if __name__=='__main__': main()
