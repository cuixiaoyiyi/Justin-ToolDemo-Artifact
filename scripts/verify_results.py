"""Independent, offline validation of the frozen 72-method / 360-cell exports.

Usage: python verify_frozen72.py DATA_DIRECTORY [--output receipt.json]
DATA_DIRECTORY must hold benchmark72.json, sample72.csv, results360.csv,
summary72.csv and protocol.json. No model calls, Java, network, or imports
from experiment scripts. The input dataset is never changed.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
from collections import Counter
from decimal import Decimal
from pathlib import Path

TOOLS = ('justin-llm','evosuite','chatunitest','chattester','hits')
EXPECTED = {
    'justin-llm': (751,697,'1610.205',163419,733,72,733,731,2),
    'evosuite': (737,690,'1796.235',0,349,72,348,348,0),
    'chatunitest': (672,651,'2562.828',186644,863,72,863,771,2),
    'chattester': (755,685,'3754.604',373306,965,70,965,858,2),
    'hits': (420,408,'7585.373',1238654,2640,38,2640,2364,15),
}
METRICS = ('line','branch','instruction','method','complexity')

def readcsv(path):
    with path.open(encoding='utf-8-sig',newline='') as f:
        return list(csv.DictReader(f))

def truth(value):
    assert value in ('true','false'), 'Unexpected CSV boolean: '+str(value)
    return value == 'true'

def near(a,b,epsilon=Decimal('0.000000001')):
    assert abs(Decimal(str(a))-Decimal(str(b))) <= epsilon, (a,b)

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('data_directory',type=Path)
    ap.add_argument('--output',type=Path)
    args=ap.parse_args()
    root=args.data_directory
    b=json.loads((root/'benchmark72.json').read_text(encoding='utf-8'))
    p=json.loads((root/'protocol.json').read_text(encoding='utf-8'))
    sample=readcsv(root/'sample72.csv')
    rows=readcsv(root/'results360.csv')
    summaries=readcsv(root/'summary72.csv')
    assert b['schema']=='original72-method-benchmark-v1'
    assert b['final_ready'] is True
    assert b['completed_units']==b['expected_units']==len(b['records'])==len(rows)==360
    assert not b['missing'] and not b['issues'] and not b['changed_input_files']
    assert b['schema_validation']['passed'] is True
    assert p['methods']==72 and p['work_units']==360 and p['subjects']==5 and p['classes']==10
    assert p['repetitions']==1 and p['reuse_old_results'] is False
    assert len(sample)==len(b['frozen_rows'])==len(b['independent_baselines'])==72
    assert len(summaries)==len(b['summaries'])==5
    assert set(r['tool'] for r in summaries)==set(TOOLS)
    sm={r['method_id']:r for r in sample}
    assert len(sm)==72
    assert len({(r['project_version'],r['class_name'],r['method_name'],r['jvm_descriptor']) for r in sample})==72
    assert len({(r['project_version'],r['class_name']) for r in sample})==10
    assert len({r['project_version'] for r in sample})==5
    expected_cells={(method_id,tool) for method_id in sm for tool in TOOLS}
    assert {(r['method_id'],r['tool']) for r in rows}==expected_cells
    jr={(r['method_id'],r['tool']):r for r in b['records']}
    assert len(jr)==360 and set(jr)==expected_cells
    baselines={r['method_id']:r for r in b['independent_baselines']}
    json_summaries={r['tool']:r for r in b['summaries']}
    for r in rows:
        s=sm[r['method_id']]
        j=jr[(r['method_id'],r['tool'])]
        assert truth(r['completed']) and j['complete'] is True
        assert r['project_version']==s['project_version']==j['subject']
        assert r['class_name']==s['class_name']==j['class_name']
        assert r['method_name']==s['method_name']==j['method']
        assert r['jvm_descriptor']==s['jvm_descriptor']==j['descriptor']
        for metric in METRICS:
            for suffix in ('covered','total'):
                key=metric+'_'+suffix
                assert int(r[key])==j['measurement'][key]
            assert int(r[metric+'_total'])==baselines[r['method_id']]['counters'][metric.upper()]
        near(r['generation_seconds'],j['generation']['seconds'])
        for csv_key,json_key in (
            ('observed_input_tokens','input_tokens'),
            ('observed_output_tokens','output_tokens'),
            ('observed_total_tokens','total_tokens'),
            ('total_unknown_attempts','total_unknown_attempts'),
            ('api_attempts','api_attempts'),
        ):
            assert int(r[csv_key])==j['accounting'][json_key]
        assert int(r['exported_Test_methods'])==j['source_cases']['generated_test_methods']
    result=[]
    for summary in summaries:
        tool=summary['tool']
        cells=[r for r in rows if r['tool']==tool]
        js=json_summaries[tool]
        assert len(cells)==int(summary['targets'])==int(summary['completed_targets'])==72
        values={}
        for metric in METRICS:
            for suffix in ('covered','total'):
                key=metric+'_'+suffix
                values[key]=sum(int(r[key]) for r in cells)
                assert values[key]==int(summary[key])==js[key]
            near(summary[metric+'_micro_pct'],100*values[metric+'_covered']/values[metric+'_total'])
        assert values['line_total']==777 and values['branch_total']==722
        assert values['instruction_total']==4099 and values['method_total']==72 and values['complexity_total']==433
        seconds=sum(Decimal(r['generation_seconds']) for r in cells)
        near(seconds,summary['generation_seconds'])
        near(seconds,js['generation_s'])
        near(seconds/60,summary['generation_minutes'])
        integers={}
        for key in ('observed_input_tokens','observed_output_tokens','observed_total_tokens',
                    'exported_Test_methods','api_attempts','total_unknown_attempts'):
            integers[key]=sum(int(r[key]) for r in cells)
            assert integers[key]==int(summary[key])
        delivery=sum(truth(r['fully_compiled_suite']) for r in cells)
        nonempty=sum(int(r['exported_source_files'])>0 for r in cells)
        closed=[r for r in cells if truth(r['junit_run_complete']) and int(r['junit_executed_tests'])>0]
        executed=sum(int(r['junit_executed_tests']) for r in closed)
        passed=sum(int(r['junit_passed_tests']) for r in closed)
        assert delivery==int(summary['fully_compiled_suites'])==js['fully_compiled_suites']
        assert nonempty==int(summary['nonempty_exported_suites'])
        assert delivery==nonempty
        assert executed==int(summary['executed_tests_in_closed_junit'])==js['tests_executed']
        assert passed==int(summary['passed_tests_in_closed_junit'])==js['tests_passed']
        near(100*passed/executed,summary['test_pass_pct_in_closed_junit'])
        expected=EXPECTED[tool]
        assert (values['line_covered'],values['branch_covered'],str(seconds),
                integers['observed_total_tokens'],integers['exported_Test_methods'],
                delivery,executed,passed,integers['total_unknown_attempts'])==expected
        result.append(dict(tool=tool,line=f"{values['line_covered']}/777",
            branch=f"{values['branch_covered']}/722",generation_seconds=str(seconds),
            generation_minutes=str(seconds/60),observed_total_tokens=integers['observed_total_tokens'],
            exported_cases=integers['exported_Test_methods'],fully_compiled_targets=delivery,
            tests_executed=executed,tests_passed=passed,
            attempts_without_total_usage=integers['total_unknown_attempts']))
    common=set(sm)
    for tool in TOOLS:
        common &= {r['method_id'] for r in rows if r['tool']==tool and truth(r['fully_compiled_suite'])}
    assert len(common)==37
    assert sum(int(r['total_unknown_attempts']) for r in summaries)==21
    receipt=dict(status='passed',schema='independent-frozen72-csv-audit-v1',
        methods=72,cells=360,projects=5,classes=10,
        denominators=dict(line=777,branch=722,instruction=4099,method=72,complexity=433),
        common_fully_compiled_methods=37,
        projects_method_counts=dict(Counter(r['project_version'] for r in sample)),
        original_group_counts=dict(Counter(r['original_group'] for r in sample)),
        missing_total_usage_attempts=21,results=result,
        files={name:hashlib.sha256((root/name).read_bytes()).hexdigest()
            for name in ('benchmark72.json','protocol.json','sample72.csv','results360.csv','summary72.csv')},
        model_calls=0,java_invocations=0,input_files_modified=False)
    text=json.dumps(receipt,indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(text,encoding='utf-8')
    print(text)
if __name__=='__main__':
    main()

