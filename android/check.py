#!/usr/bin/env python3
"""Compile Android sources and run host regression checks without packaging or signing an APK."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME'))
parser.add_argument('--out', type=Path)
args = parser.parse_args()
if not args.sdk:
    parser.error('Provide --sdk or ANDROID_HOME with platform 34 and build-tools 34.0.0')
root = Path(__file__).resolve().parent
sdk = Path(args.sdk)
out = (args.out or root / 'build/check').resolve()
out.mkdir(parents=True, exist_ok=True)
out = Path(tempfile.mkdtemp(prefix='run-', dir=out))
suffix = '.exe' if os.name == 'nt' else ''
tools = sdk / 'build-tools/34.0.0'
jar = sdk / 'platforms/android-34/android.jar'
javac = str(Path(args.jdk) / ('bin/javac' + suffix)) if args.jdk else shutil.which('javac')
java = str(Path(args.jdk) / ('bin/java' + suffix)) if args.jdk else shutil.which('java')
if not java or not javac:
    parser.error('A JDK is required')
for directory in ['gen', 'flat', 'linked', 'classes', 'tests']:
    (out / directory).mkdir(parents=True, exist_ok=True)

def run(*command):
    result = subprocess.run([str(value) for value in command], check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout or b''
    if output:
        sys.stdout.buffer.write(output)
        sys.stdout.buffer.flush()
    if b'\n\tat jdk.compiler/' in output or b'java.nio.file.AccessDeniedException:' in output:
        raise RuntimeError('Compiler reported an internal exception despite a zero exit code')

src = root / 'app/src/main'
for resource in sorted((src / 'res').rglob('*')):
    if resource.is_file():
        (out / 'linked/res' / resource.parent.name).mkdir(parents=True, exist_ok=True)
        run(tools / ('aapt2' + suffix), 'compile', resource, '-o', out / 'flat')
resources = []
for resource in sorted((out / 'flat').glob('*.flat')):
    resources.extend(['-R', resource])
# Directory output validates resources without creating an installable package.
run(tools / ('aapt2' + suffix), 'link', '--output-to-dir', '-o', out / 'linked', '-I', jar,
    '--manifest', src / 'AndroidManifest.xml', '--rename-manifest-package', 'dev.rhea.erses.guardian',
    '--java', out / 'gen', '--auto-add-overlay', *resources)
for interface in sorted((src / 'aidl').rglob('*.aidl')):
    run(tools / ('aidl' + suffix), '-p' + str(jar.with_name('framework.aidl')),
        '-I' + str(src / 'aidl'), '-o' + str(out / 'gen'), interface)
sources = sorted((src / 'java').rglob('*.java')) + sorted((out / 'gen').rglob('*.java'))
argfile = out / 'sources.txt'
argfile.write_text('\n'.join('"' + path.as_posix() + '"' for path in sources), encoding='utf-8')
# The same javac backend, with process-scoped file handles on restricted Windows hosts.
run(javac, '-encoding', 'UTF-8', '-d', out / 'tests', root / 'tests/CompileOnly.java')
run(java, '-cp', out / 'tests', 'CompileOnly', argfile, jar, out / 'classes')
policy = src / 'java/dev/linjian/peek'
run(javac, '-encoding', 'UTF-8', '-d', out / 'tests', policy / 'ReliabilityPolicy.java',
    policy / 'BoundedCalls.java', policy / 'RgbaFrame.java', root / 'tests/ReliabilityPolicyTest.java')
run(java, '-ea', '-cp', out / 'tests', 'dev.linjian.peek.ReliabilityPolicyTest')
print('PASS: resources, manifest, AIDL, Java and host regression checks; no APK or signing')
