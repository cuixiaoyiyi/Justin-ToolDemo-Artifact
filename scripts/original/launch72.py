"""Launch the audited 360-unit batch under WSL with persistent local logs."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = Path('/home/clm/tooldemo72-20261023')

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def write(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    with (ROOT / 'launch-main.lock').open('a') as guard:
        fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if (ROOT / 'execution-provenance.json').exists():
            raise RuntimeError('Batch already launched; inspect existing PIDs/state before explicit recovery.')
        audit = read(HERE / 'preflight-audit72.json')
        assert audit['issue_count'] == 0 and not audit.get('pending'), audit.get('pending')
        selectors = read(ROOT / 'selectors.json')
        assert len(selectors) == 72 and all(row['supported'] for row in selectors)
        code = ['benchmark.py', 'freeze72.py', 'generator_projects.py', 'measurement.py',
                'llm_proxy.py', 'resource_sampler.py', 'launch72.py', 'implementation.json']
        evidence = dict(
            schema='original72-batch-execution-provenance-v1',
            launched_unix=time.time(), runtime_root=str(ROOT), delivery_directory=str(HERE),
            python=sys.executable, operating_system=platform.platform(), cpu_count=os.cpu_count(),
            meminfo=Path('/proc/meminfo').read_text().splitlines()[:3],
            sample_sha256=digest(ROOT / 'frozen-sample.json'),
            selectors_sha256=digest(ROOT / 'selectors.json'),
            subjects_sha256=digest(ROOT / 'subjects.json'),
            preflight_audit_sha256=digest(HERE / 'preflight-audit72.json'),
            code_sha256={name: digest(HERE / name) for name in code},
            methods=72, tools=5, expected_units=360,
            generation_workers=8, external_measurement_workers=2,
            note='Original SUT bytecode, exact overloads and fresh isolated generation/measurement for every cell. No old numeric results reused.')
        for name, script in [('resource_sampler', 'resource_sampler.py'), ('runner', 'benchmark.py')]:
            args = [sys.executable, '-u', str(HERE / script)] + (['run'] if name == 'runner' else [])
            log_path = ROOT / (name + '.log')
            with log_path.open('a') as stream:
                child = subprocess.Popen(args, cwd=HERE, stdin=subprocess.DEVNULL,
                    stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            evidence[name] = dict(pid=child.pid, command=args, log=str(log_path))
        write(ROOT / 'execution-provenance.json', evidence)
        write(HERE / 'execution-provenance.json', evidence)
        print(json.dumps(dict(status='launched', runner=evidence['runner'],
                              resource_sampler=evidence['resource_sampler'], expected_units=360)))

if __name__ == '__main__':
    main()
