#!/usr/bin/env python3
"""Offline JVM checks using Android Studio's bundled compiler/libraries.
This supplements Android Gradle checks; it does not compile Compose or Android code.
"""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
choices = [Path(os.environ['ANDROID_STUDIO_HOME'])] if 'ANDROID_STUDIO_HOME' in os.environ else [
    Path.home()/'Applications/Android Studio.app', Path('/Applications/Android Studio.app')]
studio = next((p/'Contents' for p in choices if (p/'Contents').is_dir()), None)
if studio is None:
    raise SystemExit('Set ANDROID_STUDIO_HOME to the installed Android Studio.app directory.')
kotlin = studio/'plugins/Kotlin/kotlinc'
libs = [studio/p for p in (
    'lib/intellij.libraries.kotlinx.coroutines.core.jar',
    'lib/intellij.libraries.kotlinx.serialization.core.jar',
    'lib/intellij.libraries.kotlinx.serialization.json.jar',
    'lib/junit4.jar',
    'plugins/gemini/lib/okhttp-4.12.0.jar',
    'plugins/android/lib/okio-jvm-3.17.0.jar',
)]
for lib in libs:
    if not lib.is_file():
        raise SystemExit(f'Bundled library unavailable: {lib.name}. Run the Gradle test task instead.')
hamcrest = next((p for p in (Path.home()/'.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest').glob('*/*/hamcrest-[0-9]*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar'))), None)
if hamcrest:
    libs.append(hamcrest)
source = root/'android/app/src/main/java/si/hafnar/entalt'
files = list((source/'domain').glob('*.kt')) + [source/'data'/f for f in (
    'Contracts.kt', 'EntiaApi.kt', 'EntiaRepository.kt', 'DemoHome.kt')]
# Real localhost WebSocket tests require Gradle/MockWebServer and socket access.
tests = sorted(p for p in (root/'android/app/src/test/java').rglob('*Test.kt') if 'transport' not in p.parts)
output = root/'android/core-check/build'
output.mkdir(parents=True, exist_ok=True)
jar = output/'checks.jar'
cp = os.pathsep.join(map(str, libs))
subprocess.run([str(kotlin/'bin/kotlinc'), '-version'], check=True)
subprocess.run([str(kotlin/'bin/kotlinc'), *map(str, files+tests), '-classpath', cp,
    '-jvm-target', '17', '-d', str(jar)], cwd=root, check=True)
if tests:
    runtime = [*libs, jar, *sorted((kotlin/'lib').glob('kotlin-stdlib*.jar'))]
    classes = ['si.hafnar.entalt.' + str(p.relative_to(root/'android/app/src/test/java/si/hafnar/entalt')).replace('/', '.')[:-3] for p in tests]
    subprocess.run(['java', '-cp', os.pathsep.join(map(str, runtime)), 'org.junit.runner.JUnitCore', *classes], cwd=root, check=True)
print('Core JVM checks passed (Android Studio bundled dependencies; Android/Compose build still required).')
