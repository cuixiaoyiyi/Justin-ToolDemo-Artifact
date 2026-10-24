"""Archive audited 360-cell raw evidence with SHA256 content deduplication.

This does not run Java, generators, tests, network or model calls. The ZIP
preserves original byte contents in a content-addressed object store; its
manifest maps each original path to one object and gives exact reconstruction
paths. Core report/JSON/HTML/LaTeX/scripts remain readable by filename.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import time
import zipfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = Path('/home/clm/tooldemo72-20261023')
SK = re.compile(rb'\bsk-[A-Za-z0-9_-]{24,}\b')
SENSITIVE_NAMES = {'tool-demo-20261017.properties', '.env', 'id_rsa', 'id_ed25519',
    'credentials', 'credentials.json', 'settings-security.xml'}
IGNORED_NAMES = {'proxy.pid.json', 'runner.pid.json', 'proxy-ready.json',
                 'benchmark.lock', 'runner.lock'}
IGNORED_SUFFIXES = {'.lock', '.pyc', '.tmp'}


def digest(p):
    h = hashlib.sha256()
    with Path(p).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def read(p):
    return json.loads(Path(p).read_text(encoding='utf-8'))


def write(p, data):
    tmp = p.with_name(p.name + '.tmp')
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    tmp.replace(p)


def scan(p):
    # Never print a matched token. Scan all byte objects, including compiled
    # outputs, and nested ZIP/JAR members to keep credentials out of exports.
    def raw_scan(stream, context):
        tail = b''
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            if SK.search(tail + block):
                raise ValueError('Credential-shaped value in ' + context)
            tail = (tail + block)[-512:]
    with Path(p).open('rb') as stream:
        raw_scan(stream, str(p))
    if zipfile.is_zipfile(p):
        with zipfile.ZipFile(p) as archive:
            for member in archive.infolist():
                if not member.is_dir():
                    with archive.open(member) as stream:
                        raw_scan(stream, str(p) + '!' + member.filename)


def excluded(p):
    if p.name in SENSITIVE_NAMES:
        return 'credential_or_machine_settings'
    if p.name == 'settings.xml' and '.m2' in p.parts:
        return 'machine_maven_settings'
    if p.name in IGNORED_NAMES or p.suffix in IGNORED_SUFFIXES:
        return 'transient_process_or_lock_file'
    if '__pycache__' in p.parts or '.git' in p.parts:
        return 'interpreter_cache_or_repository_metadata'
    if p.name.startswith('benchmark72-evidence'):
        return 'self_archive'
    return None


def external_inputs(root, report, sample):
    result = {}
    def add(value, why):
        if not isinstance(value, str) or not value.startswith(('/home/', '/mnt/')):
            return
        # Classpaths use ':' under WSL. Any path with embedded separators is
        # split only for an explicitly identified classpath field below.
        p = Path(value)
        if p.is_file() and not p.resolve().is_relative_to(root.resolve()):
            result.setdefault(str(p.resolve()), set()).add(why)
    add(sample.get('source_report'), 'original72_selection_source')
    for row in sample['rows']:
        for k in ('source_file', 'source_inventory'):
            add(row.get(k), 'frozen_method_' + k)
        add((row.get('binary_verification') or {}).get('jar'), 'original72_runtime_artifact')
    for r in report['records']:
        unit = Path(r['unit_path'])
        p = unit / 'measure/measurement-input.json'
        if p.is_file():
            inputs = read(p)
            for k in ('runtime_jar', 'sources_jar', 'analysis_jar'):
                add(inputs.get(k), 'measurement_' + k)
            for k in ('classpath', 'runtime_classpath', 'test_classpath', 'test_cp', 'runtime_cp'):
                cp = inputs.get(k)
                if isinstance(cp, str):
                    for entry in cp.split(':'):
                        add(entry, 'measurement_' + k)
                elif isinstance(cp, list):
                    for entry in cp:
                        add(entry, 'measurement_' + k)
        for record in (r['generation'], read(unit / 'measure/execution.json')):
            cmd = record.get('command') or []
            for i, arg in enumerate(cmd):
                if not isinstance(arg, str):
                    continue
                if arg.endswith('.jar'):
                    add(arg, 'executed_command_jar')
                if arg.startswith('-javaagent:'):
                    add(arg.split(':', 1)[1].split('=', 1)[0], 'execution_coverage_agent')
                if arg in ('-cp', '-classpath', '-projectCP') and i + 1 < len(cmd):
                    for cp in cmd[i + 1].split(':'):
                        add(cp, 'executed_command_classpath')
    subjects = read(root / 'subjects.json')
    subjects = {x['sid']: x for x in subjects} if isinstance(subjects, list) else subjects
    if isinstance(subjects.get('subjects'), list):
        subjects = {x['sid']: x for x in subjects['subjects']}
    for meta in subjects.values():
        for k in ('jar', 'sources', 'analysis_jar'):
            add(meta.get(k), 'subject_' + k)
        for k in ('runtime_cp', 'test_cp'):
            cp = meta.get(k)
            for item in (cp.split(':') if isinstance(cp, str) else cp or []):
                add(item, 'subject_' + k)
    for name in ('evosuite-1.2.0.jar', 'evosuite-standalone-runtime-1.2.0.jar',
                 'junit-platform-console-standalone-1.12.2.jar', 'junit-4.13.2.jar',
                 'hamcrest-core-1.3.jar', 'jacococli.jar', 'jacocoagent.jar'):
        add(str(Path.home() / 'compare/tools' / name), 'generator_and_measurement_tool')
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    for pom in sorted((root / 'projects').glob('*/pom.xml')):
        tree = ET.parse(pom)
        props = {x.tag.rsplit('}', 1)[-1]: x.text or '' for x in tree.findall('./m:properties/*', ns)}
        for plugin in tree.findall('./m:build/m:plugins/m:plugin', ns):
            group = plugin.findtext('m:groupId', 'org.apache.maven.plugins', ns)
            artifact = plugin.findtext('m:artifactId', '', ns)
            version = plugin.findtext('m:version', '', ns)
            if version.startswith('${') and version.endswith('}'):
                version = props.get(version[2:-1], version)
            location = Path.home() / '.m2/repository' / group.replace('.', '/') / artifact / version
            for suffix in ('.jar', '.pom'):
                add(str(location / (artifact + '-' + version + suffix)), 'declared_native_maven_plugin')
    # The plugin delegates native ChatUniTest/ChatTester/HITS execution to a
    # separate core JAR. Preserve the exact independently verified binaries,
    # not just the project POM's declared plugin artifact.
    native_path = HERE / 'maven-binary-provenance-readonly.json'
    if not native_path.is_file():
        raise ValueError('Missing verified native plugin/core binary manifest')
    native = read(native_path)
    artifacts = native.get('artifacts', [])
    if not {'chatunitest-maven-plugin', 'chatunitest-core'} <= {x.get('artifact') for x in artifacts}:
        raise ValueError('Verified native manifest must include plugin and core artifacts')
    add(str(native_path), 'verified_native_maven_binary_manifest')
    for artifact in artifacts:
        for kind in ('jar', 'pom'):
            p = Path(artifact[kind])
            if not p.is_file():
                raise ValueError('Verified native binary file is missing: ' + str(p))
            if digest(p) != artifact[kind + '_sha256']:
                raise ValueError('Verified native binary hash changed: ' + str(p))
            add(str(p), 'verified_native_' + artifact['artifact'] + '_' + kind)
    return result


def tool_manifest(root, report, external):
    java_paths = set()
    for r in report['records']:
        for record in (r['generation'], read(Path(r['unit_path']) / 'measure/execution.json')):
            command = record.get('command') or []
            if command and isinstance(command[0], str) and command[0].endswith('/java'):
                java_paths.add(Path(command[0]).resolve())
    jdks = []
    for java in sorted(java_paths):
        jhome = java.parent.parent
        release = jhome / 'release'
        javac = java.parent / 'javac'
        jdks.append(dict(java=str(java), java_sha256=digest(java),
            javac=str(javac), javac_sha256=digest(javac) if javac.is_file() else None,
            release=str(release), release_sha256=digest(release) if release.is_file() else None,
            release_text=release.read_text(errors='replace') if release.is_file() else None,
            installation_bundled=False))
    jars = []
    candidates = set(Path(p) for p in external if str(p).endswith('.jar')) | {root / 'justin-all.jar'}
    for p in sorted(candidates):
        metadata = {}
        if zipfile.is_zipfile(p):
            with zipfile.ZipFile(p) as archive:
                for name in archive.namelist():
                    if name == 'META-INF/MANIFEST.MF' or (name.startswith('META-INF/maven/') and name.endswith('/pom.properties')):
                        metadata[name] = archive.read(name).decode('utf-8', errors='replace')
        jars.append(dict(path=str(p), sha256=digest(p), bytes=p.stat().st_size,
            jar_declared_metadata=metadata))
    mvn = shutil.which('mvn')
    maven = None
    if mvn:
        binary = Path(mvn).resolve()
        maven = dict(command=str(binary), command_sha256=digest(binary), installation_bundled=False)
        candidates = list((binary.parent.parent / 'lib').glob('maven-core-*.jar'))
        maven['core_jars'] = [dict(path=str(p), sha256=digest(p)) for p in candidates]
    native_manifest = HERE / 'maven-binary-provenance-readonly.json'
    return dict(schema='original72-tool-environment-provenance-v1', jdks=jdks, maven=maven,
        binary_jars=jars, tool_implementation=report.get('implementation'),
        verified_native_binary_manifest=dict(path=str(native_manifest), sha256=digest(native_manifest)),
        measurement_protocol='Exact focal JaCoCo counters; retained source SHA256; isolated javac/JUnit under the recorded JDK; no generation or test execution during archiving.')


RESTORE = r'''"""Materialize archived files under a chosen output directory; no commands run."""
import argparse,hashlib,json,pathlib,zipfile
ap=argparse.ArgumentParser();ap.add_argument('archive',type=pathlib.Path);ap.add_argument('destination',type=pathlib.Path)
args=ap.parse_args();dest=args.destination.resolve();dest.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.archive) as z:
 manifest=json.loads(z.read('evidence-manifest.json'))
 for item in manifest['files']:
  target=(dest/item['restore_relative']).resolve()
  if not target.is_relative_to(dest):raise ValueError('Unsafe reconstruction path')
  target.parent.mkdir(parents=True,exist_ok=True)
  blob=z.read(item['archive_member'])
  if hashlib.sha256(blob).hexdigest()!=item['sha256']:raise ValueError('Object hash mismatch')
  if target.exists() and target.read_bytes()!=blob:raise ValueError('Refusing to overwrite different bytes: '+str(target))
  target.write_bytes(blob)
print('Verified and materialized',len(manifest['files']),'files under',dest)
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root', type=Path, default=ROOT)
    ap.add_argument('--out', type=Path, default=HERE)
    ap.add_argument('--final', action='store_true')
    args = ap.parse_args()
    if not args.final:
        print(json.dumps(dict(status='prepared', mode='no_archive_written', subject_runs=0, model_API_calls=0)))
        return
    root, out = args.root.resolve(), args.out.resolve()
    report = read(out / 'benchmark72.json')
    audit = read(out / 'audit72.json')
    sample = read(root / 'frozen-sample.json')
    if not report.get('final_ready') or report.get('completed_units') != 360:
        raise ValueError('Final 360-cell report required')
    if audit.get('status') != 'passed' or audit.get('artifact_units_audited') != 360:
        raise ValueError('Passing independent 360-cell audit required')
    if audit['report']['sha256'] != digest(out / 'benchmark72.json'):
        raise ValueError('Audit does not identify the current final report')
    candidate_map, omissions = {}, []
    for base, prefix in ((root, 'runtime-root'), (out, 'delivery')):
        for p in sorted(base.rglob('*')):
            if not p.is_file():
                continue
            why = excluded(p)
            if why:
                omissions.append(dict(path=str(p), reason=why))
                continue
            relative = str(Path(prefix) / p.relative_to(base))
            candidate_map[str(p.absolute())] = dict(path=str(p.absolute()),
                restore_relative=relative, reason=prefix)
    external = external_inputs(root, report, sample)
    provenance = tool_manifest(root, report, external)
    provenance_path = out / 'tool-environment-provenance.json'
    write(provenance_path, provenance)
    candidate_map[str(provenance_path)] = dict(path=str(provenance_path),
        restore_relative='delivery/' + provenance_path.name, reason='tool_environment_provenance')
    for value, reasons in external.items():
        p = Path(value)
        why = excluded(p)
        if why:
            omissions.append(dict(path=str(p), reason=why))
            continue
        candidate_map.setdefault(str(p.resolve()), dict(path=str(p.resolve()),
            restore_relative='external-inputs/' + str(p.resolve()).lstrip('/'),
            reason=','.join(sorted(reasons))))
    files, objects, scanned = [], {}, set()
    for item in candidate_map.values():
        p = Path(item['path'])
        hashed = digest(p)
        if hashed not in scanned:
            scan(p)
            scanned.add(hashed)
        member = 'objects/' + hashed[:2] + '/' + hashed
        objects.setdefault(hashed, p)
        files.append(dict(item, sha256=hashed, bytes=p.stat().st_size,
            archive_member=member, original_symlink_target=str(p.readlink()) if p.is_symlink() else None))
    files.sort(key=lambda x: x['restore_relative'])
    manifest = dict(schema='original72-content-addressed-evidence-v1', created_unix=time.time(),
        expected_method_tool_units=360, sample_sha256=digest(root / 'frozen-sample.json'),
        raw_selection_manifest_sha256=digest(out / 'frozen-sample.json'),
        selected_methods_sha256=sample.get('selection_sha256'),
        tool_environment_provenance_sha256=digest(provenance_path),
        report_sha256=digest(out / 'benchmark72.json'), audit_sha256=digest(out / 'audit72.json'),
        files=files, omitted_files=omissions, unique_content_objects=len(objects),
        materialized_file_count=len(files), materialized_bytes=sum(x['bytes'] for x in files),
        unique_uncompressed_bytes=sum(p.stat().st_size for p in objects.values()),
        credential_scan='Credential-shaped sk tokens scanned in original byte objects and nested ZIP/JAR members; machine settings and ignored credential filenames excluded.',
        reconstruction='Run restore_evidence.py ARCHIVE.zip DESTINATION; each original path has a safe restore_relative and content SHA256. Relative paths preserve every raw run file. Absolute original paths are provenance and need mapping before replay.',
        runtime_dependencies='Runtime subject and tool/classpath JARs referenced by retained measurement metadata and command vectors are bundled. JVM/Maven installations and all implicit plugin transitive dependencies are not promised as a fully offline environment.')
    final = out / 'benchmark72-evidence.zip'
    temp = out / 'benchmark72-evidence.zip.tmp'
    with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
        archive.writestr('evidence-manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
        archive.writestr('restore_evidence.py', RESTORE)
        for value, p in sorted(objects.items()):
            archive.write(p, 'objects/' + value[:2] + '/' + value)
        # Frequently accessed deliverables remain readable by name as well.
        for name in ('benchmark72.json', 'benchmark72.html', 'audit72.json',
                     'sample72.csv', 'results360.csv', 'summary72.csv',
                     'report72.py', 'audit72.py', 'export72_csv.py', 'report_accounting.py', 'protocol.json'):
            p = out / name
            if p.is_file():
                archive.write(p, 'readable/' + name)
        if (out / 'Latex/main.tex').is_file():
            archive.write(out / 'Latex/main.tex', 'readable/Latex/main.tex')
        if (out / 'Latex/main.pdf').is_file():
            archive.write(out / 'Latex/main.pdf', 'readable/Latex/main.pdf')
    # Read every object back; ZIP CRC and content hashes must both agree.
    with zipfile.ZipFile(temp) as archive:
        for value in objects:
            h = hashlib.sha256()
            with archive.open('objects/' + value[:2] + '/' + value) as f:
                for block in iter(lambda: f.read(1024 * 1024), b''):
                    h.update(block)
            if h.hexdigest() != value:
                raise ValueError('ZIP object read-back hash mismatch')
        if len({x.filename for x in archive.infolist()}) != len(archive.infolist()):
            raise ValueError('Duplicate ZIP members')
    changed = [x['path'] for x in files if not Path(x['path']).is_file() or digest(x['path']) != x['sha256']]
    if changed:
        raise ValueError('Evidence changed while archiving: ' + str(len(changed)) + ' files')
    temp.replace(final)
    write(out / 'benchmark72-evidence-index.json', manifest)
    (out / 'benchmark72-evidence.zip.sha256').write_text(digest(final) + '  ' + final.name + '\n')
    validation = dict(status='passed', source_files=len(files), content_objects=len(objects),
        credential_scan_passed=True, all_objects_ZIP_readback_sha256_verified=True,
        scientific_input_hashes_unchanged=True, archive_bytes=final.stat().st_size,
        archive_sha256=digest(final), bundle_script_sha256=digest(__file__))
    write(out / 'benchmark72-evidence-validation.json', validation)
    print(json.dumps(validation))


if __name__ == '__main__':
    main()
