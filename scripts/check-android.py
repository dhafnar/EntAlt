#!/usr/bin/env python3
"""Run Gradle checks with persistent logs; --background survives IDE terminal timeouts."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--background', action='store_true')
parser.add_argument('--name', default='verification')
parser.add_argument('tasks', nargs='*', default=[':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'])
args, gradle_options = parser.parse_known_args()
if any(not option.startswith('-P') for option in gradle_options):
    parser.error('Only Gradle -P properties may follow the task list')
args.tasks.extend(gradle_options)
if not args.name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in args.name):
    parser.error('--name must contain lowercase letters, digits, or hyphens')
project = Path(__file__).resolve().parents[1] / 'android'
output = project / 'build'
output.mkdir(parents=True, exist_ok=True)
status = output / (args.name + '.json')
log_path = output / (args.name + '.log')
if args.background:
    process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--name', args.name, *args.tasks],
        cwd=project, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True)
    print('Started Android checks:', process.pid)
    print('Status:', status)
    print('Log:', log_path)
else:
    started = time.time()
    def record(phase, **extra):
        temporary = status.with_suffix('.json.tmp')
        temporary.write_text(json.dumps(dict(phase=phase, pid=os.getpid(), tasks=args.tasks,
            started=started, **extra), indent=2) + '\n')
        temporary.replace(status)
    record('running')
    try:
        with log_path.open('w') as log:
            result = subprocess.run([str(project / 'gradlew'), *args.tasks, '--console=plain'], cwd=project,
                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        record('complete', exit_code=result.returncode, elapsed_seconds=round(time.time() - started, 1))
        sys.exit(result.returncode)
    except Exception as error:
        record('failed', error=type(error).__name__, elapsed_seconds=round(time.time() - started, 1))
        raise
