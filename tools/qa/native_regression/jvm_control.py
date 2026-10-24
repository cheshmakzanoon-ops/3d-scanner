"""Scanner-free JVM startup/ordinary-exit control; never subtract leak totals."""
import hashlib
import os
from pathlib import Path
import shutil

from native_process import native_compile_env

SOURCE = Path(__file__).with_name('EmptyJvmControl.java')


def identity(path):
    resolved = Path(path).resolve(strict=True)
    return dict(path=str(path), resolved_path=str(resolved),
                sha256=hashlib.sha256(resolved.read_bytes()).hexdigest())


def runtime_environment(environment):
    # Match native_test_build.native_run_env's candidate-directory precedence.
    # This only sets a search path; the control never loads the selected library.
    candidate = Path(environment['RF_NATIVE_LIBRARY']).resolve(strict=True)
    if not candidate.is_file() or any(c in str(candidate) for c in ('$', ':', ';')):
        raise ValueError('Unsupported selected native library path for JVM control')
    result = dict(environment)
    previous = result.get('LD_LIBRARY_PATH')
    result['LD_LIBRARY_PATH'] = str(candidate.parent) + (os.pathsep + previous if previous else '')
    return result


def run_baseline(root, folder, environment, timeout, command):
    record = dict(kind='scanner-free JVM startup/ordinary-exit', commands=[],
                  mapping_scope='snapshot at inspection, not lifetime proof',
                  interpretation='No leak subtraction, allowance or candidate gate waiver')
    try:
        # Same resolution as test_actual_jvm_native_boundary: javac on PATH,
        # then java in that compiler's resolved JDK (not an unrelated PATH java).
        javac = shutil.which('javac', path=environment.get('PATH'))
        if not javac:
            raise FileNotFoundError('JDK compiler unavailable; JVM control NOT_RUN')
        jdk = Path(javac).resolve(strict=True).parents[1]
        java = jdk / 'bin/java'
        runtime = runtime_environment(environment)
        record['inputs'] = dict(source=identity(SOURCE), javac=identity(javac),
                                java=identity(java), jdk_release=identity(jdk / 'release'))
        classes = folder / 'jvm-baseline-classes'
        classes.mkdir(parents=True, exist_ok=True)
        compile_receipt = command([javac, '-d', classes, SOURCE], root, folder,
                                  'jvm-baseline-compile', native_compile_env(environment), min(timeout, 60))
        record['commands'].append(compile_receipt)
        if compile_receipt['result'] == 'PASS':
            record['class'] = identity(classes / 'EmptyJvmControl.class')
            record['commands'].append(command([java, '-cp', classes, 'EmptyJvmControl'],
                                              root, folder, 'jvm-baseline-empty', runtime, min(timeout, 60)))
        else:
            record['commands'].append(dict(result='NOT_RUN', reason='JVM control compilation failed'))
    except (OSError, ValueError, KeyError) as error:
        record['commands'].append(dict(result='NOT_RUN', reason=str(error)))
    return record
