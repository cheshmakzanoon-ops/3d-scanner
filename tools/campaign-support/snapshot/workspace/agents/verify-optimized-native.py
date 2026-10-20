#!/usr/bin/env python3
"""Attest actual Ninja compile flags for the externally configured DEBUG build."""
from pathlib import Path
import hashlib
import json
import re
import shlex
import shutil
import sys

root = Path('/workspace/agents/031')
output = Path(sys.argv[1])

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def cache_values(path):
    values = {}
    for line in path.read_text().splitlines():
        if line.startswith(('#', '//')) or ':' not in line or '=' not in line:
            continue
        key, value = line.split('=', 1)
        values[key.split(':', 1)[0]] = value
    return values

configuration_dir = output/'validation-configuration'
configuration_dir.mkdir(parents=True, exist_ok=True)
for name in ('android-optimized-native.init.gradle', 'run-optimized-native-android.sh', 'verify-optimized-native.py'):
    shutil.copy2(Path('/workspace/agents')/name, configuration_dir/name)

report = {'verifier_sha256': digest(Path(__file__)), 'configuration': 'Debug', 'source_sha': (output/'reviewed-source.sha').read_text().strip(),
          'init_sha256': digest(Path('/workspace/agents/android-optimized-native.init.gradle')),
          'checks': 'Actual CMake cache and all realityforge C++ Ninja object compile flags',
          'scope': 'Locally compiled realityforge target; prebuilt OpenCV and libc++ payloads retain their prior build configuration.', 'abis': {}}
for abi in ('arm64-v8a', 'x86_64'):
    matches = []
    for cache in (root/'app/.cxx/Debug').glob(f'*/{abi}/CMakeCache.txt'):
        values = cache_values(cache)
        if values.get('CMAKE_CXX_FLAGS_DEBUG') == '-g -O2':
            matches.append((cache, values))
    assert len(matches) == 1, (abi, 'expected one current optimized configuration', [str(x[0]) for x in matches])
    cache, values = matches[0]
    assert values.get('CMAKE_BUILD_TYPE') == 'Debug', values.get('CMAKE_BUILD_TYPE')
    assert values.get('RF_REQUIRE_CAMPAIGN_031_060') == 'ON', 'strict campaign source gate absent'
    assert values.get('CMAKE_JOB_POOLS') == 'rf_android_compile=2'
    assert values.get('CMAKE_JOB_POOL_COMPILE') == 'rf_android_compile'
    ninja = cache.parent/'build.ninja'
    blocks = []
    current = None
    for line in ninja.read_text().splitlines():
        if line.startswith('build '):
            if current is not None:
                blocks.append(current)
            current = {'object': line.split(': ', 1)[0][6:]} if ': CXX_COMPILER__realityforge_Debug ' in line else None
        elif current is not None and line.startswith('  ') and ' = ' in line:
            key, value = line.strip().split(' = ', 1)
            current[key] = value
    if current is not None:
        blocks.append(current)
    assert blocks, (abi, 'no actual C++ object compilation rules')
    evidence = []
    for block in blocks:
        flags = shlex.split(block.get('FLAGS', ''))
        definitions = shlex.split(block.get('DEFINES', ''))
        optimizations = [f for f in flags if re.fullmatch(r'-O(?:[0-3sgz]|fast)', f)]
        assert optimizations and optimizations[-1] == '-O2', (abi, block['object'], optimizations)
        prohibited = {'-ffast-math', '-Ofast', '-funsafe-math-optimizations', '-ffinite-math-only',
                      '-fno-signed-zeros', '-fno-trapping-math', '-fassociative-math', '-freciprocal-math',
                      '-fapprox-func', '-menable-unsafe-fp-math'}
        assert not prohibited.intersection(flags), (abi, block['object'], prohibited.intersection(flags))
        assert not any(f == '-DNDEBUG' or f.startswith('-DNDEBUG=') for f in flags+definitions), (abi, block['object'], 'NDEBUG')
        assert '-g' in flags, (abi, block['object'], 'debug symbols absent')
        assert block.get('pool') == 'rf_android_compile', (abi, block['object'], 'compile pool absent')
        evidence.append({'object': block['object'], 'flags': flags, 'defines': definitions,
                         'optimization_flags_in_order': optimizations, 'last_optimization': '-O2',
                         'NDEBUG': False, 'fast_math': False})
    for required in ('core.cpp.o', 'dense.cpp.o', 'surface.cpp.o', 'jni.cpp.o'):
        assert any(b['object'].endswith('/'+required) for b in blocks), (abi, required)
    abi_dir = output/'native-configuration'/abi
    abi_dir.mkdir(parents=True, exist_ok=True)
    copied = {}
    for filename in ('CMakeCache.txt', 'build.ninja', '.ninja_log', 'compile_commands.json'):
        path = cache.parent/filename
        if path.exists():
            target = abi_dir/filename
            shutil.copy2(path, target)
            copied[filename] = {'sha256': digest(target), 'bytes': target.stat().st_size}
    assert '.ninja_log' in copied, (abi, 'no completed Ninja build log')
    ninja_log = (cache.parent/'.ninja_log').read_text()
    assert all(b['object'] in ninja_log for b in blocks), (abi, 'missing object execution in Ninja log')
    report['abis'][abi] = {'configuration_path': str(cache.parent), 'object_count': len(blocks),
                           'files': copied, 'objects': evidence, 'all_objects_compiled': True,
                           'last_optimization_O2': True, 'NDEBUG': False, 'fast_math': False}
(output/'native-optimization-verification.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps({abi: {'objects': item['object_count'], 'last_optimization': '-O2', 'NDEBUG': False,
                        'fast_math': False} for abi, item in report['abis'].items()}, indent=2))
