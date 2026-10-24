"""Compile actual adapter state expressions against both production state declarations.

This is a Kotlin/JVM name-resolution regression, not complete Android adapter compilation.
RF_REQUIRE_RELIEF_ENUM_CONTRACT=1 makes missing pinned compiler/JDK tools a failure.
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import pytest

ROOT = Path(__file__).resolve().parents[1]
ADAPTER = ROOT / 'docs/development/experiments/eight-view-sparse-runtime-successor/ReliefThreeCompleteControllerHostTest.kt'


def declaration(source, marker):
    """Extract the exact simple production enum/object declaration, including nested getter."""
    start = source.index(marker)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        assert end < len(source), 'Unclosed production declaration'
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def adapter_expressions():
    source = ADAPTER.read_text()
    explicit = re.findall(r'^import com\.realityforge\.scanner\.data\.jobs\.ReconstructionJobState as (\w+)$', source, re.M)
    assert explicit == ['DurableJobState'], 'Actual adapter needs the explicit durable-enum alias'
    wait = re.findall(r'^\s*(it\.state\.isTerminal \|\| it\.state == [^\n]+)$', source, re.M)
    cancel = re.findall(r'^\s*if \((current\?\.state == [^\n]+)\)$', source, re.M)
    assertions = [line.strip() for line in source.splitlines()
                  if line.strip().startswith('assertEquals(') and 'DurableJobState.COMPLETED' in line]
    assert len(wait) == len(cancel) == 1 and len(assertions) == 2
    assert source.count('DurableJobState.') == 5, 'Audit any new enum use in this executable contract'
    return wait[0], cancel[0], assertions


def test_actual_adapter_enum_predicates_and_assertions_compile_and_run(tmp_path):
    lib = Path(os.environ.get('RF_KOTLIN_TEST_LIB', '/workspace/shared/export-stream-qa/lib'))
    java = Path(os.environ.get('RF_PHASE_JAVA_HOME', '/workspace/shared/phase-jdk-tools/jdk-17.0.21+9')) / 'bin/java'
    if not java.is_file() or not (lib / 'kotlin-compiler-embeddable-2.1.21.jar').is_file():
        if os.environ.get('RF_REQUIRE_RELIEF_ENUM_CONTRACT') == '1':
            pytest.fail('Required Kotlin/JDK fixture tools missing')
        pytest.skip('Actual pinned Kotlin/JDK tools unavailable')
    compiler = sorted(lib.glob('*.jar'))
    metadata = ET.parse(ROOT / 'gradle/verification-metadata.xml').getroot()
    pins = {artifact.get('name'): digest.get('value')
            for component in metadata.iter() if component.tag.endswith('component')
            for artifact in component if artifact.tag.endswith('artifact')
            for digest in artifact if digest.tag.endswith('sha256')}
    for jar in compiler:
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == pins[jar.name]
    enum_file = ROOT / 'app/src/main/java/com/realityforge/scanner/data/jobs/JobContracts.kt'
    legacy_file = ROOT / 'app/src/main/java/com/realityforge/scanner/data/Database.kt'
    legacy = declaration(legacy_file.read_text(), 'object ReconstructionJobState {')
    enum_source = enum_file  # Compile the entire real production file, including JobSnapshot invariants.
    legacy_source = tmp_path / 'LegacyState.kt'
    legacy_source.write_text('package com.realityforge.scanner.data\n' + legacy + '\n')
    wait, cancel, assertions = adapter_expressions()
    header = '''package com.realityforge.scanner.hostintegration
import com.realityforge.scanner.data.*
import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.data.jobs.ReconstructionJobState as DurableJobState
import org.junit.Assert.assertEquals
fun fixtureSnapshot(state: com.realityforge.scanner.data.jobs.ReconstructionJobState): JobSnapshot {
    val id = JobId("enum-regression-job")
    val hash = "a".repeat(64)
    val input = JobInputSnapshot("enum-regression-project", 0, "enum-regression-request", hash, hash, hash, hash, "enum-regression-engine")
    val running = state.name == "RUNNING"
    val completed = state.name == "COMPLETED"
    return JobSnapshot(id, input.identityKey(), input, state,
        if (completed) JobStageCursor(ReconstructionStage.PUBLISHING, "checkpoint", hash) else JobStageCursor.initial(),
        1, if (running) JobLease(id, 1, "lease", "worker", 1000, 1) else null,
        if (completed) "result" else null, if (completed) hash else null,
        null, null, null, 0, 0, 1)
}
'''
    predicates = 'fun awaiting(it: JobSnapshot): Boolean = ' + wait + '\nfun cancelling(current: JobSnapshot?): Boolean = ' + cancel + '\n'
    assertion_functions = '\n'.join('fun assertion' + str(i) + '(finished: JobSnapshot) { val persisted = finished; ' + expression + ' }'
                                    for i, expression in enumerate(assertions)) + '\n'
    runtime = '''fun main() {
    val states = com.realityforge.scanner.data.jobs.ReconstructionJobState.entries
    check(states.size == 8)
    check(!cancelling(null))
    for (state in states) {
        val snapshot = fixtureSnapshot(state)
        check(awaiting(snapshot) == (state.name !in setOf("QUEUED", "RUNNING")))
        check(cancelling(snapshot) == (state.name in setOf("QUEUED", "RUNNING")))
        for (assertion in listOf(::assertion0, ::assertion1)) {
            var accepted = true
            try { assertion(snapshot) } catch (expected: AssertionError) { accepted = false }
            check(accepted == (state.name == "COMPLETED"))
        }
    }
    println("ENUM_CONTRACT_PASS states=8 nullable=9 assertions=16")
}
'''
    stdlib = lib / 'kotlin-stdlib-2.1.21.jar'
    runtime_jars = [stdlib, lib / 'junit-4.13.2.jar', lib / 'hamcrest-core-1.3.jar']
    cp = lambda items: os.pathsep.join(map(str, items))

    def compile_source(label, text):
        harness = tmp_path / (label + '.kt')
        harness.write_text(text)
        output = tmp_path / label
        command = [str(java), '-Xmx384m', '-XX:ActiveProcessorCount=2', '-cp', cp(compiler),
                   'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                   '-jvm-target', '17', '-classpath', cp(runtime_jars), '-d', str(output),
                   str(enum_source), str(legacy_source), str(harness)]
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=90)
        (tmp_path / (label + '.compile.log')).write_bytes(result.stdout)
        return result, output

    result, output = compile_source('Fixed', header + predicates + assertion_functions + runtime)
    assert result.returncode == 0, result.stdout.decode(errors='replace')[-6000:]
    run = subprocess.run([str(java), '-cp', cp([output, *runtime_jars]),
                          'com.realityforge.scanner.hostintegration.FixedKt'],
                         stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    assert run.returncode == 0, run.stdout.decode(errors='replace')[-6000:]
    assert run.stdout.decode().strip() == 'ENUM_CONTRACT_PASS states=8 nullable=9 assertions=16'

    # Reproduce the actual pre-fix form: remove alias import and restore wildcard-only names.
    def old_form(text):
        return text.replace('import com.realityforge.scanner.data.jobs.ReconstructionJobState as DurableJobState\n', '').replace('DurableJobState.', 'ReconstructionJobState.')

    broken, _ = compile_source('WildcardPredicates', old_form(header + predicates + assertion_functions + runtime))
    assert broken.returncode != 0, 'Wildcard enum/int predicate regression unexpectedly compiled'
    error = broken.stdout.decode(errors='replace')
    assert 'ReconstructionJobState' in error and 'Int' in error and '==' in error, error[-6000:]

    # JUnit assertEquals accepts Object: without predicates the bug may compile but reject COMPLETED.
    assertion_runtime = '''fun main() {
    val completed = fixtureSnapshot(com.realityforge.scanner.data.jobs.ReconstructionJobState.COMPLETED)
    var wrong = 0
    for (assertion in listOf(::assertion0, ::assertion1)) {
        try { assertion(completed) } catch (expected: AssertionError) { wrong++ }
    }
    check(wrong == 2) { "Old wildcard assertions did not reproduce the enum/int mismatch" }
    println("WILDCARD_ASSERTIONS_WRONG=2")
}
'''
    broken_assertions, old_output = compile_source('WildcardAssertions', old_form(header + assertion_functions + assertion_runtime))
    assert broken_assertions.returncode == 0, broken_assertions.stdout.decode(errors='replace')[-6000:]
    old_run = subprocess.run([str(java), '-cp', cp([old_output, *runtime_jars]),
                              'com.realityforge.scanner.hostintegration.WildcardAssertionsKt'],
                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    assert old_run.returncode == 0, old_run.stdout.decode(errors='replace')[-6000:]
    assert old_run.stdout.decode().strip() == 'WILDCARD_ASSERTIONS_WRONG=2'
    print('Actual JobContracts.kt + extracted legacy object: fixed 8 states/9 nullable cases/16 assertion checks PASS; old predicates enum/Int compile failure; both old completed assertions compile then fail at runtime.')
