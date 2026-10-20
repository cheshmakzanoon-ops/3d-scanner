#!/usr/bin/env python3
"""Run only the reviewed, unchanged JVM production/test closure. No Android API substitutes."""
from pathlib import Path
import argparse, datetime, hashlib, json, os, platform, re, shutil, subprocess, sys, time

BASE = Path(__file__).resolve().parent
CONFIG = json.loads((BASE / "selection.json").read_text())
COMPILER_NAMES = [
    "kotlin-compiler-embeddable-2.1.21.jar", "kotlin-stdlib-2.1.21.jar",
    "kotlin-script-runtime-2.1.21.jar", "kotlin-reflect-1.6.10.jar",
    "kotlin-daemon-embeddable-2.1.21.jar", "trove4j-1.0.20200330.jar",
    "kotlinx-coroutines-core-jvm-1.8.0.jar", "annotations-13.0.jar",
]
RUNTIME_NAMES = [
    "kotlin-stdlib-2.1.21.jar", "kotlinx-coroutines-core-jvm-1.10.2.jar",
    "json-20250517.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar",
    "annotations-13.0.jar",
]

def digest(data):
    return hashlib.sha256(data).hexdigest()

def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")

def verify_dependencies():
    root = Path(CONFIG["dependency_root"])
    pinned = {item["file"]: item for item in CONFIG["dependencies"]}
    verified = []
    for name in dict.fromkeys(COMPILER_NAMES + RUNTIME_NAMES):
        pin = pinned[name]
        path = root / name
        actual = path.read_bytes()
        if len(actual) != pin["bytes"] or digest(actual) != pin["sha256"]:
            raise RuntimeError("Pinned dependency integrity failed: " + name)
        verified.append(dict(pin, verified_path=str(path)))
    return verified

def prepare():
    repo = Path(CONFIG["repo"])
    source_ref = CONFIG["source_ref"]
    resolved = subprocess.check_output(["git", "rev-parse", source_ref], cwd=repo, text=True).strip()
    if resolved != source_ref:
        raise RuntimeError("The full reviewed source commit did not resolve exactly")
    tree = subprocess.check_output(["git", "rev-parse", source_ref + "^{tree}"], cwd=repo, text=True).strip()
    dependencies = verify_dependencies()
    snapshot = BASE / "sources"
    snapshot.mkdir(exist_ok=True)
    role_paths = [(p, "production") for p in CONFIG["production"]]
    role_paths += [(p, "test") for p in CONFIG["tests"]]
    role_paths += [(p, "test_fixture") for p in CONFIG["fixtures"]]
    if len({p for p, _ in role_paths}) != len(role_paths):
        raise RuntimeError("A source file was selected more than once")
    files = []
    test_methods = 0
    for relative, role in role_paths:
        source = subprocess.check_output(["git", "show", source_ref + ":" + relative], cwd=repo)
        text = source.decode("utf-8")
        if re.search(r"^import (?:android\.|androidx\.|org\.opencv\.)", text, re.M):
            raise RuntimeError("Framework dependency in portable closure: " + relative)
        target = snapshot / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.read_bytes() != source:
            raise RuntimeError("Existing snapshot belongs to different source bytes: " + relative)
        if not target.exists():
            target.write_bytes(source)
        count = len(re.findall(r"@Test\b", text)) if role == "test" else 0
        test_methods += count
        files.append({"path": relative, "role": role, "snapshot": str(target),
                      "bytes": len(source), "sha256": digest(source), "declared_test_methods": count})
    if test_methods != CONFIG["expected_test_methods"]:
        raise RuntimeError("The selected test declaration count changed")
    java = str(Path(shutil.which("java") or "/usr/bin/java").resolve())
    release_path = Path(java).parent.parent / "release"
    provenance = {
        "schema": 1, "status": "PREPARED_NOT_RUN", "prepared_at_utc": utc_now(),
        "source_commit": resolved, "source_tree": tree, "source_repository": str(repo),
        "runner_sha256": digest(Path(__file__).read_bytes()),
        "selection_sha256": digest((BASE / "selection.json").read_bytes()),
        "production_source_count": len(CONFIG["production"]),
        "test_source_count": len(CONFIG["tests"]), "fixture_source_count": len(CONFIG["fixtures"]),
        "declared_test_methods": test_methods, "test_classes": CONFIG["test_classes"],
        "source_policy": "Complete unmodified files from the exact Git commit; no Android/Room/OpenCV or test-API substitutions.",
        "test_scope": "Existing portable unit/protocol tests only; not Android instrumentation, Room integration, JNI or physical-device qualification.",
        "compiler_heap_mib": 512, "runtime_heap_mib": 512,
        "java_executable": java, "java_release": release_path.read_text() if release_path.is_file() else None,
        "host": {"platform": platform.platform(), "python": sys.version},
        "toolchain": dependencies, "files": files,
    }
    write_json(BASE / "prepared-provenance.json", provenance)
    print("Prepared " + str(len(files)) + " unchanged files and " + str(test_methods) + " declared tests; no JVM process started.", flush=True)
    return provenance

def verify_snapshot(provenance):
    verify_dependencies()
    for item in provenance["files"]:
        actual = Path(item["snapshot"]).read_bytes()
        if len(actual) != item["bytes"] or digest(actual) != item["sha256"]:
            raise RuntimeError("Prepared source snapshot changed: " + item["path"])

def artifact_tree(directory):
    files = []
    hasher = hashlib.sha256()
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            relative = path.relative_to(directory).as_posix()
            content_sha = digest(path.read_bytes())
            hasher.update((relative + "\0" + content_sha + "\n").encode())
            files.append({"path": relative, "bytes": path.stat().st_size, "sha256": content_sha})
    return {"sha256": hasher.hexdigest(), "files": files}

def run():
    provenance = prepare()
    verify_snapshot(provenance)
    run_dir = BASE / ("run-" + str(time.time_ns()))
    run_dir.mkdir()
    classes = run_dir / "classes"
    classes.mkdir()
    temporary = run_dir / "tmp"
    temporary.mkdir()
    dependency_root = Path(CONFIG["dependency_root"])
    compiler_cp = os.pathsep.join(str(dependency_root / name) for name in COMPILER_NAMES)
    runtime_cp = os.pathsep.join(str(dependency_root / name) for name in RUNTIME_NAMES)
    java = provenance["java_executable"]
    limits = ["-Xms32m", "-Xmx512m", "-Djava.io.tmpdir=" + str(temporary)]
    compiler = [java] + limits + ["-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", runtime_cp, "-d", str(classes)]
    compiler += [item["snapshot"] for item in provenance["files"]]
    runtime = [java] + limits + ["-cp", str(classes) + os.pathsep + runtime_cp, "org.junit.runner.JUnitCore"]
    runtime += provenance["test_classes"]
    provenance.update({"status": "COMPILING", "run_directory": str(run_dir), "started_at_utc": utc_now(),
                       "compiler_command": compiler, "runtime_command": runtime})
    result_file = run_dir / "provenance.json"
    write_json(result_file, provenance)
    write_json(BASE / "latest.json", {"provenance": str(result_file), "run_directory": str(run_dir)})
    started = time.monotonic()
    with (run_dir / "compile.log").open("w") as log:
        compiled = subprocess.run(compiler, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT)
    provenance["compiler_exit_code"] = compiled.returncode
    provenance["compiler_seconds"] = time.monotonic() - started
    provenance["compile_log_sha256"] = digest((run_dir / "compile.log").read_bytes())
    print("Compiler exit: " + str(compiled.returncode), flush=True)
    print((run_dir / "compile.log").read_text()[-16000:], flush=True)
    if compiled.returncode:
        provenance.update({"status": "COMPILE_FAILED", "finished_at_utc": utc_now()})
        write_json(result_file, provenance)
        return compiled.returncode
    verify_snapshot(provenance)
    provenance["compiled_artifacts"] = artifact_tree(classes)
    provenance["status"] = "RUNNING_JUNIT"
    write_json(result_file, provenance)
    started = time.monotonic()
    with (run_dir / "junit.log").open("w") as log:
        tested = subprocess.run(runtime, cwd=run_dir, stdout=log, stderr=subprocess.STDOUT)
    log_text = (run_dir / "junit.log").read_text()
    verify_snapshot(provenance)
    match = re.search(r"OK \((\d+) tests?\)", log_text)
    provenance.update({
        "status": "JUNIT_PASSED" if tested.returncode == 0 else "JUNIT_FAILED",
        "junit_exit_code": tested.returncode, "junit_seconds": time.monotonic() - started,
        "junit_reported_successful_run_count": int(match.group(1)) if match else None,
        "junit_log_sha256": digest((run_dir / "junit.log").read_bytes()),
        "finished_at_utc": utc_now(),
    })
    write_json(result_file, provenance)
    print(log_text, flush=True)
    print("Exact provenance: " + str(result_file), flush=True)
    return tested.returncode

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--prepare", action="store_true", help="Verify and snapshot only; do not start any JVM process.")
    action.add_argument("--run", action="store_true", help="Compile and run the reviewed closure after the coordinator releases the compiler slot.")
    args = parser.parse_args()
    if args.prepare:
        prepare()
    else:
        raise SystemExit(run())
