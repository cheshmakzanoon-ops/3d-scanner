#!/usr/bin/env python3
"""Finite QA-only R8 test-to-app ABI retention. Not production APK qualification."""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

PINNED_R8 = "tracereferences 8.11.18 "
ANNOTATION_DIAGNOSTIC = "Missing class javax.lang.model.element.Modifier (referenced from: javax.lang.model.element.Modifier[] com.google.errorprone.annotations.IncompatibleModifiers.value() and 1 other context)"
MAIN = "com.android.tools.r8.tracereferences.TraceReferences"
FRAMEWORK_SHA256 = "6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b"
MONITOR_SHA256 = "3641a1bd19fade3c43351d657bd5845f51f43ee2e2a40eaaee5d124d7af374b1"
LEGACY_FRAMEWORK_DIAGNOSTICS = frozenset(['Missing method android.app.Instrumentation$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int) (referenced from: android.app.Instrumentation$ActivityResult androidx.test.runner.MonitoringInstrumentation.execStartActivity(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int))', 'Missing method android.app.Instrumentation$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int, android.os.Bundle, android.os.UserHandle) (referenced from: android.app.Instrumentation$ActivityResult androidx.test.runner.MonitoringInstrumentation.execStartActivity(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int, android.os.Bundle, android.os.UserHandle))', 'Missing method android.app.Instrumentation$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Fragment, android.content.Intent, int, android.os.Bundle) (referenced from: android.app.Instrumentation$ActivityResult androidx.test.runner.MonitoringInstrumentation.execStartActivity(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Fragment, android.content.Intent, int, android.os.Bundle))'])



def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def atomic_json(path, value):
    path = plain_path(path)
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, prefix=path.name + ".", delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(json.dumps(value, indent=2, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def input_hash(path):
    path = plain_path(path)
    if path.is_file():
        return sha(path)
    if not path.is_dir():
        raise ValueError(f"Missing input: {path}")
    rows = [(str(p.relative_to(path)), sha(p)) for p in sorted(path.rglob("*")) if p.is_file()]
    return hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()


def source_hash(root):
    root = Path(root).resolve()
    names = ("app/src", "app/build.gradle.kts", "app/proguard-rules.pro", "gradle", "buildSrc",
             "gradle.properties", "build.gradle.kts", "settings.gradle.kts", "app/gradle.lockfile",
             "tools/instrumentation_abi.py", "tools/instrumentation_qa_metadata.py",
             "tools/export_instrumentation_classpaths.init.gradle")
    rows = [(name, input_hash(root / name)) for name in names if (root / name).exists()]
    return hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()


def plain_path(path):
    path = Path(os.path.abspath(path))
    if path != path.resolve() or (path.is_dir() and any(p.is_symlink() for p in path.rglob("*"))):
        raise ValueError(f"Input/output aliases and symlinks are not allowed: {path}")
    return path


def classes(inputs):
    result = {}
    for path in sorted(map(Path, inputs)):
        if path.is_dir():
            entries = [(str(p.relative_to(path)), p.read_bytes()) for p in sorted(path.rglob("*.class"))]
        else:
            with zipfile.ZipFile(path) as archive:
                entries = [(n, archive.read(n)) for n in sorted(archive.namelist()) if n.endswith(".class") and not n.startswith("META-INF/")]
        for name, data in entries:
            if name in result and result[name] != data:
                raise ValueError(f"Conflicting bytecode for {name}: {path}")
            result[name] = data
    return result


def archive(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(info, data)


def diagnostics(log):
    # Ignore only log framing, not missing APIs. Store exact reviewed diagnostics,
    # including their call-site context; any new missing symbol fails closed.
    return sorted(set(re.sub(r"^(?:Error|Warning): ", "", line) for line in log.splitlines()
                      if re.match(r"(?:(?:Error|Warning): )?Missing (?:class|method|field) ", line)))


def validate_exclusions(accepted):
    # The three exact pinned hidden-bridge legacy overloads are the only
    # AndroidX-target exceptions; all other AndroidX and all Kotlin/app missing
    # targets are rejected, even by a supplied diagnostics file.
    desktop = ("java.beans.BeanInfo", "java.beans.IntrospectionException", "java.beans.Introspector",
               "java.beans.PropertyDescriptor",
               "org.easymock.EasyMock", "org.easymock.IArgumentMatcher", "org.jmock.core.Constraint")
    for line in accepted:
        if line in LEGACY_FRAMEWORK_DIAGNOSTICS:
            continue
        target, separator, context = line.partition(" (referenced from: ")
        if not separator or not line.endswith(")") or "*" in line:
            raise ValueError("Exclusions require exact diagnostic and call-site context")
        # Parse the declaring owner, not a return/argument type that happens to
        # contain an approved name. This also rejects spoofed AndroidX owners.
        head = re.sub(r"^Missing (class|method|field) ", "", target)
        kind = target.split()[1]
        owner = head if kind == "class" else head.split("(", 1)[0].split()[-1].rsplit(".", 1)[0]
        context = re.sub(r" and \d+ other contexts?\)$", ")", context)
        call_owner = context.rstrip(")").split("(", 1)[0].split()[-1].rsplit(".", 1)[0]
        if line == ANNOTATION_DIAGNOSTIC:
            continue
        if owner in desktop and call_owner.startswith(("org.hamcrest.", "com.squareup.javawriter.")):
            continue
        if owner == "sun.misc.Unsafe" and call_owner.startswith("androidx.test.espresso.core.internal.deps.guava."):
            continue
        raise ValueError(f"Unapproved missing-API exclusion: {line}")


def validate_runtime_libraries(libraries, platform_inputs=None):
    allowed = {"android.jar", "android.test.base.jar", "android.test.runner.jar",
               "platform-api.jar", "annotations-23.0.0.jar",
               "error_prone_annotations-2.26.1.jar"}
    for path in libraries:
        path = plain_path(path)
        if path.name not in allowed:
            raise ValueError(f"Unapproved runtime API library: {path}")
        if path.name == "platform-api.jar":
            if platform_inputs is None:
                raise ValueError("Missing derived platform provenance")
            sdk, framework = platform_inputs
            if sha(plain_path(framework)) != FRAMEWORK_SHA256:
                raise ValueError("Wrong hidden framework API version/hash")
            expected = classes([plain_path(sdk)])
            with zipfile.ZipFile(framework) as original:
                expected["android/app/Instrumentation.class"] = original.read("android/app/Instrumentation.class")
            if classes([path]) != expected:
                raise ValueError("Derived platform class set/bytes differ from exact approved replacement")
        with zipfile.ZipFile(path) as jar:
            if any(n.startswith(("kotlin/", "kotlinx/", "androidx/", "com/realityforge/")) and n.endswith(".class") and n not in {"androidx/annotation/RecentlyNullable.class", "androidx/annotation/RecentlyNonNull.class"} for n in jar.namelist()):
                raise ValueError(f"Runtime API library contains application/shared dependency classes: {path}")


def validate_legacy_inputs(accepted, inputs):
    if set(accepted) & LEGACY_FRAMEWORK_DIAGNOSTICS:
        if not any(Path(p).name == "monitor-1.7.2-runtime.jar" and h == MONITOR_SHA256 for p, h in inputs.items()):
            raise ValueError("Legacy framework exclusions require the reviewed exact monitor bytecode")


def exclusion_rationales(found):
    return [{"diagnostic": line,
             "reason": "API35-absent legacy framework overload inherited via ExposedInstrumentationApi; modern used overloads resolve. Not device-safety proof."
                       if line in LEGACY_FRAMEWORK_DIAGNOSTICS else
                       "Optional desktop Hamcrest/compiler annotation API; not Android runtime or device-safety proof.",
             "api_level": 35, "monitor_version": "1.7.2", "monitor_sha256": MONITOR_SHA256}
            for line in found]


def run_trace(args, options, log_path, accepted=()):
    validate_exclusions(accepted)
    command = [args.java, "-Xmx1024m", "-cp", args.r8, MAIN] + options
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    Path(log_path).write_text(result.stdout)
    found = diagnostics(result.stdout)
    unexpected = sorted(set(found) - set(accepted))
    if result.returncode or unexpected:
        raise ValueError(f"TraceReferences failed or has {len(unexpected)} unreviewed diagnostics; see {log_path}")
    return found


def version(args):
    result = subprocess.run([args.java, "-cp", args.r8, MAIN, "--version"], check=True, text=True, stdout=subprocess.PIPE)
    actual = next((x for x in result.stdout.splitlines() if x.startswith("tracereferences ")), "")
    if not actual.startswith(PINNED_R8):
        raise ValueError(f"Unexpected R8 version: {actual}")
    return actual


def verify_metadata(receipt, root):
    path = Path(__file__).with_name("instrumentation_qa_metadata.py")
    spec = importlib.util.spec_from_file_location("qa_metadata_verifier", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.verify(receipt, root)


def verify(manifest_path, root):
    expected = Path(root).absolute() / "app/build/generated/instrumentation-qa/manifest.json"
    if Path(manifest_path).absolute() != expected or expected.resolve() != expected:
        raise ValueError("QA manifest must use the fixed, non-symlink generated path")
    manifest = json.loads(Path(manifest_path).read_text())
    if manifest["schema"] != 2 or manifest["intent"] != "test-signed-release-qa-only":
        raise ValueError("Not a QA retention manifest")
    if source_hash(root) != manifest["source_sha256"]:
        raise ValueError("Stale QA rules: application/test/config source changed")
    for path, expected in manifest["inputs"].items():
        if input_hash(path) != expected:
            raise ValueError(f"Stale QA bytecode/tool input: {path}")
    normalization = manifest["metadata_normalization"]
    authoritative = verify_metadata(normalization["receipt"], root)
    for key in ("output", "output_sha256", "class_changes", "excluded_entries"):
        if normalization[key] != authoritative[key]:
            raise ValueError("QA normalization manifest/receipt mismatch: " + key)
    if sha(normalization["receipt"]) != normalization["receipt_sha256"]:
        raise ValueError("QA metadata receipt changed")
    rules = Path(manifest_path).resolve().parent / "keep.pro"
    if rules.resolve() != rules.absolute():
        raise ValueError("QA rules must not be a symlink")
    if sha(rules) != manifest["rules_sha256"]:
        raise ValueError("QA keep rules changed")
    return manifest


def count_retained_types(rules):
    return len(re.findall(r"^-keep(?:,\w+)* (?:class|interface|enum|@interface) ", rules, re.M))


def validate_normalized_test_classes(source, target, originals, metadata):
    expected = {name: data for name, data in originals.items() if name not in target}
    if set(source) != set(expected):
        raise ValueError("Normalized test class coverage differs from original test-only classes: " +
                         str(sorted(set(source) ^ set(expected))[:20]))
    changed = {entry["class"]: entry for entry in metadata["class_changes"]}
    if not set(changed).issubset(source) or set(changed) & set(target):
        raise ValueError("Metadata repair must affect only test classes, never target app")
    for name, data in source.items():
        if name in changed:
            if hashlib.sha256(expected[name]).hexdigest() != changed[name]["before_sha256"] or hashlib.sha256(data).hexdigest() != changed[name]["after_sha256"]:
                raise ValueError("Normalized test metadata bytes mismatch: " + name)
        elif data != expected[name]:
            raise ValueError("Unexpected test class bytecode change: " + name)


def generate(args):
    output = plain_path(args.output)
    expected_output = plain_path(args.root) / "app/build/generated/instrumentation-qa"
    if output != expected_output:
        raise ValueError("Generation must use the fixed QA build directory")
    output.mkdir(parents=True, exist_ok=True)
    # Delete the approval receipt first: a failed regeneration cannot leave a
    # previously valid-looking manifest beside partial or changed rules.
    manifest_path = output / "manifest.json"
    manifest_path.unlink(missing_ok=True)
    actual_version = version(args)
    validate_runtime_libraries(args.lib)
    classpaths = json.loads(Path(args.classpaths).read_text())
    starting_source = source_hash(args.root)
    if classpaths.get("source_sha256") != starting_source or classpaths.get("before_compile_source_sha256") != starting_source:
        raise ValueError("Missing/stale post-compilation classpath export source fingerprint")
    metadata_receipt = str(plain_path(classpaths["metadata_receipt"]))
    metadata = verify_metadata(metadata_receipt, args.root)
    if args.test_input or classpaths["test"] != [metadata["output"]]:
        raise ValueError("QA test inputs must be only the downstream normalized artifact")
    metadata_paths = list(metadata["inputs"]) + list(metadata["helper_inputs"]) + [metadata_receipt, metadata["output"]]
    original_test_paths = classpaths["original_test_classpaths"]
    app_inputs = sorted(set(classpaths["app"] + args.app_input))
    test_inputs = sorted(set(classpaths["test"] + args.test_input))
    jmod = Path(args.java).resolve().parents[1] / "jmods/java.base.jmod"
    original_paths = sorted(set(app_inputs + test_inputs + metadata_paths + original_test_paths + args.lib + [str(jmod), args.framework, args.r8, args.java,
                        args.classpaths, str(Path(__file__).resolve())] + ([args.accepted_diagnostics] if args.accepted_diagnostics else [])))
    original_hashes = {str(plain_path(p)): input_hash(p) for p in original_paths}
    target = classes(app_inputs)
    source = classes([p for p in test_inputs if p not in app_inputs])
    # Shared artifacts belong to target, not both separately packaged APKs.
    for name, data in target.items():
        if name in source and source[name] != data:
            raise ValueError(f"Conflicting target/test bytecode: {name}")
        source.pop(name, None)
    validate_normalized_test_classes(source, target, classes(original_test_paths), metadata)
    if not target or not source:
        raise ValueError("Empty original target/test bytecode")
    archive(output / "target.jar", target)
    archive(output / "source.jar", source)
    if sha(plain_path(args.framework)) != FRAMEWORK_SHA256:
        raise ValueError("Unexpected full framework artifact; verify publisher provenance first")
    public_sdk = [p for p in args.lib if Path(p).name == "android.jar"]
    if len(public_sdk) != 1:
        raise ValueError("Exactly one pinned public Android SDK library is required")
    # Actual framework declaration replaces the public stub, without duplicate
    # class definitions or importing android-all's unrelated helpers/services.
    platform = classes(public_sdk)
    with zipfile.ZipFile(args.framework) as framework:
        platform["android/app/Instrumentation.class"] = framework.read("android/app/Instrumentation.class")
    archive(output / "platform-api.jar", platform)
    runtime_libraries = [str(output / "platform-api.jar")] + [p for p in args.lib if p not in public_sdk]
    validate_runtime_libraries(runtime_libraries, (public_sdk[0], args.framework))
    accepted = json.loads(Path(args.accepted_diagnostics).read_text()) if args.accepted_diagnostics else []
    validate_legacy_inputs(accepted, original_hashes)
    options = ["--keep-rules", "--source", str(output / "source.jar"), "--target", str(output / "target.jar"), "--output", str(output / "keep.pro"), "--map-diagnostics:MissingDefinitionsDiagnostic", "error", "warning"]
    for lib in runtime_libraries:
        options += ["--lib", lib]
    # javac/Kotlin JVM17 bootstrap calls are desugared before DEX. Supply only
    # this real JDK bootstrap class, never an entire host runtime that could
    # conceal Android-incompatible test APIs. Final APK checks do not use it.
    jmod = Path(args.java).resolve().parents[1] / "jmods/java.base.jmod"
    with zipfile.ZipFile(jmod) as host:
        bootstrap = {"java/lang/invoke/StringConcatFactory.class":
                     host.read("classes/java/lang/invoke/StringConcatFactory.class")}
    archive(output / "jvm-bootstrap.jar", bootstrap)
    options += ["--lib", str(output / "jvm-bootstrap.jar")]
    generated_trace_inputs = [str(output / name) for name in ("source.jar", "target.jar", "jvm-bootstrap.jar", "platform-api.jar")]
    generated_hashes = {p: input_hash(p) for p in generated_trace_inputs}
    found = run_trace(args, options, output / "generation.log", accepted)
    if any(input_hash(p) != h for p, h in generated_hashes.items()):
        raise ValueError("Generated trace input changed during generation")
    rules = (output / "keep.pro").read_text()
    if "*" in rules or any(x in rules for x in ("-dont", "-include", "-basedirectory")):
        raise ValueError("Unexpected broad or external generated keep directive")
    count = count_retained_types(rules)
    if not 1 <= count <= 10000 or len(rules.splitlines()) > 50000:
        raise ValueError("Generated retention surface outside reviewed bounds")
    # Preserve the traced ABI, while permitting optimization inside kept methods.
    rules = re.sub(r"^-keep ", "-keep,allowoptimization,includedescriptorclasses ", rules, flags=re.M)
    (output / "keep.pro").write_text("# QA-only finite TraceReferences surface; not production equivalence.\n" + rules)
    paths = sorted(set(app_inputs + test_inputs + metadata_paths + original_test_paths + generated_trace_inputs + args.lib + runtime_libraries + [args.framework, args.r8, args.java, str(jmod), args.classpaths, str(Path(__file__).resolve())] + ([args.accepted_diagnostics] if args.accepted_diagnostics else [])))
    if source_hash(args.root) != starting_source or any(input_hash(p) != h for p, h in original_hashes.items()):
        raise ValueError("Source/bytecode/tool inputs changed during generation")
    git = {}
    for key, ref in (("commit", "HEAD"), ("tree", "HEAD^{tree}")):
        result = subprocess.run(["git", "-C", str(args.root), "rev-parse", ref], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
        if result.returncode == 0: git[key] = result.stdout.strip()
    manifest = {"git_audit_metadata_only": git, "schema": 2, "intent": "test-signed-release-qa-only", "r8_version": actual_version,
                "source_sha256": source_hash(args.root), "inputs": {str(Path(p).resolve()): input_hash(p) for p in paths},
                "rules_sha256": sha(output / "keep.pro"), "target_classes": len(target), "source_classes": len(source),
                "retained_classes": count, "accepted_generation_diagnostics": found,
                "metadata_normalization": {"receipt": metadata_receipt, "receipt_sha256": sha(metadata_receipt),
                                           "output": metadata["output"], "output_sha256": metadata["output_sha256"],
                                           "class_changes": metadata["class_changes"], "excluded_entries": metadata["excluded_entries"]},
                "exclusion_rationales": exclusion_rationales(found),
                "runtime_libraries": {str(plain_path(p)): input_hash(p) for p in runtime_libraries},
                "runtime_library_order": [str(plain_path(p)) for p in runtime_libraries],
                "platform_source_inputs": [str(plain_path(public_sdk[0])), str(plain_path(args.framework))],
                "platform_replacements": {"android/app/Instrumentation.class": hashlib.sha256(platform["android/app/Instrumentation.class"]).hexdigest()},
                "platform_qualification_limit": "Compile SDK36 public APIs plus actual API35 hidden Instrumentation declarations; not API35 runtime availability or device-safety proof.",
                "qualification_limit": "Changed QA retention. No equivalence to the default shipping APK; independent black-box qualification required."}
    atomic_json(manifest_path, manifest)
    print(json.dumps({"manifest": str(manifest_path), "retained_classes": count}))


def check(args):
    Path(args.output).unlink(missing_ok=True)
    manifest = verify(args.manifest, args.root)
    if str(plain_path(args.r8)) not in manifest["inputs"] or sha(args.r8) != manifest["inputs"][str(plain_path(args.r8))]:
        raise ValueError("Checker shrinker differs from generation")
    libraries = manifest["runtime_libraries"]
    library_order = manifest["runtime_library_order"]
    if set(library_order) != set(libraries):
        raise ValueError("Runtime library order/identity mismatch")
    validate_runtime_libraries(libraries, manifest["platform_source_inputs"])
    checked_paths = [args.app_apk, args.test_apk, args.manifest, args.r8, args.java, __file__] + list(libraries) + ([args.accepted_diagnostics] if args.accepted_diagnostics else [])
    checked_hashes = {str(plain_path(p)): sha(plain_path(p)) for p in checked_paths}
    version(args)
    accepted = json.loads(Path(args.accepted_diagnostics).read_text()) if args.accepted_diagnostics else []
    validate_legacy_inputs(accepted, manifest["inputs"])
    options = ["--check", "--source", args.app_apk, "--source", args.test_apk, "--map-diagnostics:MissingDefinitionsDiagnostic", "error", "warning"]
    for lib in library_order:
        options += ["--lib", lib]
    found = run_trace(args, options, args.output + ".log", accepted)
    if any(sha(plain_path(p)) != h for p, h in checked_hashes.items()):
        raise ValueError("APK/checker/runtime inputs changed during final linkage check")
    verify(args.manifest, args.root)
    receipt = {"status": "PASS_WITH_DOCUMENTED_EXCLUSIONS" if found else "PASS", "schema": 1, "gate": "apk-pair-static-linkage", "app_sha256": sha(args.app_apk),
               "test_sha256": sha(args.test_apk), "r8_sha256": sha(args.r8), "accepted_diagnostics": found,
               "runtime_libraries": libraries, "generation_manifest_sha256": sha(args.manifest),
               "checker_sha256": sha(__file__), "exclusion_rationales": exclusion_rationales(found),
               "metadata_normalization": manifest["metadata_normalization"],
               "qaRetention": True, "rules_sha256": manifest["rules_sha256"],
               "source_sha256": manifest["source_sha256"], "checked_inputs": checked_hashes,
               "production_equivalence": False,
               "app_path": str(plain_path(args.app_apk)), "test_path": str(plain_path(args.test_apk)),
               "runtime_executed": False,
               "platform_qualification_limit": manifest["platform_qualification_limit"],
               "qualification_limit": "QA-retained APK pair only, not equivalent to the normal shipping APK. Static ABI check only; listed exclusions are not device-safety proof."}
    atomic_json(args.output, receipt)
    print(json.dumps(receipt))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    gen = subs.add_parser("generate")
    gen.add_argument("--classpaths", required=True)
    gen.add_argument("--root", required=True)
    gen.add_argument("--framework", required=True)
    gen.add_argument("--app-input", action="append", default=[])
    gen.add_argument("--test-input", action="append", default=[])
    chk = subs.add_parser("check")
    chk.add_argument("--app-apk", required=True)
    chk.add_argument("--test-apk", required=True)
    chk.add_argument("--manifest", required=True)
    chk.add_argument("--root", required=True)
    gen.add_argument("--lib", action="append", required=True)
    for sub in (gen, chk):
        sub.add_argument("--java", required=True)
        sub.add_argument("--r8", required=True)
        sub.add_argument("--output", required=True)
        sub.add_argument("--accepted-diagnostics")
    ver = subs.add_parser("verify")
    ver.add_argument("--manifest", required=True)
    ver.add_argument("--root", required=True)
    snapshot = subs.add_parser("source-hash")
    snapshot.add_argument("--root", required=True)
    args = parser.parse_args()
    if args.command == "generate": generate(args)
    elif args.command == "check": check(args)
    elif args.command == "verify": verify(args.manifest, args.root); print("QA retention inputs verified")
    else: print(source_hash(args.root))


if __name__ == "__main__":
    main()
