"""Host regressions for QA retention provenance; not Android runtime evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("abi", ROOT / "tools/instrumentation_abi.py")
abi = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(abi)


class InstrumentationAbiTest(unittest.TestCase):
    def setUp(self):
        # Metadata verifier has its own byte-level suite; ABI fixtures below
        # isolate receipt/source/hash orchestration rather than fabricate classes.
        patcher = patch.object(abi, "verify_metadata", return_value={"output":"fixture", "output_sha256":"fixture", "class_changes":[], "excluded_entries":[]})
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_archive_is_deterministic_independent_of_input_order(self):
        with tempfile.TemporaryDirectory() as temp:
            a, b = Path(temp)/"a.jar", Path(temp)/"b.jar"
            abi.archive(a, {"B.class": b"B", "A.class": b"A"})
            abi.archive(b, {"A.class": b"A", "B.class": b"B"})
            self.assertEqual(a.read_bytes(), b.read_bytes())

    def test_conflicting_bytecode_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            a, b = Path(temp)/"a.jar", Path(temp)/"b.jar"
            abi.archive(a, {"A.class": b"old"})
            abi.archive(b, {"A.class": b"new"})
            with self.assertRaisesRegex(ValueError, "Conflicting"):
                abi.classes([a, b])

    def fixture(self, root):
        (root/"app/src/main").mkdir(parents=True)
        (root/"app/src/androidTest").mkdir()
        source = root/"app/src/main/source.kt"
        source.write_text("original")
        directory = root/"app/build/generated/instrumentation-qa"
        directory.mkdir(parents=True)
        rules = directory/"keep.pro"
        rules.write_text("-keep class Exact { public void run(); }\n")
        bytecode = root/"classes.jar"
        bytecode.write_bytes(b"original bytecode")
        metadata = directory/"metadata.json"; metadata.write_text("{}")
        manifest = {"schema": 2,
                    "metadata_normalization": {"receipt": str(metadata), "receipt_sha256": abi.sha(metadata), "output":"fixture", "output_sha256":"fixture", "class_changes":[], "excluded_entries":[]}, "intent": "test-signed-release-qa-only",
                    "source_sha256": abi.source_hash(root), "inputs": {str(bytecode): abi.sha(bytecode)},
                    "rules_sha256": abi.sha(rules)}
        path = directory/"manifest.json"
        path.write_text(json.dumps(manifest))
        return path, rules, bytecode, source

    def test_stale_source_bytecode_or_rules_rejected(self):
        for index in (1, 2, 3):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                fixture = self.fixture(root)
                abi.verify(fixture[0], root)
                fixture[index].write_text("changed")
                with self.assertRaises(ValueError): abi.verify(fixture[0], root)

    def test_missing_rules_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest, rules, _, _ = self.fixture(root)
            rules.unlink()
            with self.assertRaises(FileNotFoundError): abi.verify(manifest, root)

    def test_symlink_rules_and_wrong_manifest_intent_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest, rules, _, _ = self.fixture(root)
            saved = rules.read_bytes()
            elsewhere = root/"outside.pro"
            elsewhere.write_bytes(saved)
            rules.unlink(); rules.symlink_to(elsewhere)
            with self.assertRaises(ValueError): abi.verify(manifest, root)
            rules.unlink(); rules.write_bytes(saved)
            data = json.loads(manifest.read_text()); data["intent"] = "production"
            manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError): abi.verify(manifest, root)

    def test_kotlin_androidx_and_app_missing_symbols_cannot_be_excluded(self):
        for target in ("kotlin.LazyKt", "androidx.tracing.Trace", "com.realityforge.scanner.MainActivity"):
            with self.assertRaises(ValueError):
                abi.validate_exclusions([f"Missing class {target} (referenced from: void org.hamcrest.X.run())"])

    def test_linkage_error_fails_even_if_process_returned_zero(self):
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(java="java", r8="r8.jar")
            result = SimpleNamespace(returncode=0, stdout="Warning: Missing class kotlin.LazyKt (referenced from: void X.run())\n")
            with patch.object(abi.subprocess, "run", return_value=result):
                with self.assertRaises(ValueError): abi.run_trace(args, ["--check"], Path(temp)/"log")

    def test_checker_removes_stale_success_receipt_before_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)/"receipt.json"; output.write_text('{"passed": true}')
            args = SimpleNamespace(output=str(output), manifest="missing", root=temp)
            with patch.object(abi, "verify", side_effect=ValueError("wrong manifest")):
                with self.assertRaises(ValueError): abi.check(args)
            self.assertFalse(output.exists())

    def test_variant_sources_and_build_logic_are_fingerprinted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            for relative in ("app/src/release/Variant.kt", "gradle/libs.versions.toml", "buildSrc/src/main/kotlin/Build.kt", "tools/instrumentation_qa_metadata.py"):
                before = abi.source_hash(root)
                file = root/relative; file.parent.mkdir(parents=True, exist_ok=True); file.write_text("changed")
                self.assertNotEqual(before, abi.source_hash(root))

    def test_input_symlink_rebinding_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); target = root/"target"; target.write_text("old")
            alias = root/"alias"; alias.symlink_to(target)
            with self.assertRaises(ValueError): abi.input_hash(alias)

    def test_declaring_owner_cannot_be_spoofed_by_return_type(self):
        for line in (
            "Missing method java.beans.BeanInfo kotlin.Broken.api() (referenced from: void org.hamcrest.X.run())",
            "Missing method sun.misc.Unsafe androidx.missing.Broken.api() (referenced from: void androidx.test.espresso.core.internal.deps.guava.X.run())",
        ):
            with self.assertRaises(ValueError): abi.validate_exclusions([line])

    def test_final_libraries_cannot_mask_app_or_kotlin_symbols(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp)/"platform-api.jar"
            abi.archive(jar, {"kotlin/LazyKt.class": b"not allowed"})
            with self.assertRaises(ValueError): abi.validate_runtime_libraries([jar])
            arbitrary = Path(temp)/"target.jar"; abi.archive(arbitrary, {"X.class": b"X"})
            with self.assertRaises(ValueError): abi.validate_runtime_libraries([arbitrary])

    def test_exact_legacy_context_signature_and_monitor_are_required(self):
        for line in abi.LEGACY_FRAMEWORK_DIAGNOSTICS:
            abi.validate_exclusions([line])
            for changed in (line.replace("MonitoringInstrumentation", "OtherInstrumentation"),
                            line.replace("ExposedInstrumentationApi", "BrokenAndroidX"),
                            line.replace("execStartActivity", "execWrongActivity"),
                            line.replace("android.content.Intent", "android.content.Intent[]")):
                with self.assertRaises(ValueError): abi.validate_exclusions([changed])
            with self.assertRaises(ValueError): abi.validate_legacy_inputs([line], {})
            with self.assertRaises(ValueError):
                abi.validate_legacy_inputs([line], {"/x/monitor-1.7.2-runtime.jar": "wrong"})
            abi.validate_legacy_inputs([line], {"/x/monitor-1.7.2-runtime.jar": abi.MONITOR_SHA256})

    def test_platform_provenance_rejects_extra_helpers_and_api_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sdk, framework, platform = root/"android.jar", root/"framework.jar", root/"platform-api.jar"
            abi.archive(sdk, {"android/Foo.class": b"public"})
            abi.archive(framework, {"android/app/Instrumentation.class": b"real-api-35"})
            expected = {"android/Foo.class": b"public", "android/app/Instrumentation.class": b"real-api-35"}
            with patch.object(abi, "FRAMEWORK_SHA256", abi.sha(framework)):
                abi.archive(platform, expected)
                abi.validate_runtime_libraries([platform], [sdk, framework])
                for helper in ("java/lang/invoke/StringConcatFactory.class", "com/google/common/Helper.class", "org/apache/Helper.class"):
                    abi.archive(platform, dict(expected, **{helper: b"not platform"}))
                    with self.assertRaises(ValueError): abi.validate_runtime_libraries([platform], [sdk, framework])
                abi.archive(platform, expected)
                abi.archive(framework, {"android/app/Instrumentation.class": b"wrong-api"})
                with self.assertRaises(ValueError): abi.validate_runtime_libraries([platform], [sdk, framework])

    def test_generator_rejects_nonstandard_output_before_running_tool(self):
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(root=temp, output=str(Path(temp)/"elsewhere"))
            with self.assertRaisesRegex(ValueError, "fixed QA"):
                abi.generate(args)

    def test_exporter_is_ordered_after_real_compile_producers(self):
        config = (ROOT/"tools/export_instrumentation_classpaths.init.gradle").read_text()
        self.assertIn("compileReleaseJavaWithJavac", config)
        self.assertIn("compileReleaseAndroidTestJavaWithJavac", config)
        self.assertIn("result.source_sha256", config)
        self.assertIn("'source-hash'", config)

    def test_atomic_receipt_refuses_symlink_and_has_no_partial_temporary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); output = root/"receipt.json"
            abi.atomic_json(output, {"qaRetention": True})
            self.assertEqual(json.loads(output.read_text()), {"qaRetention": True})
            self.assertEqual(list(root.iterdir()), [output])
            alias = root/"alias"; alias.symlink_to(output)
            with self.assertRaises(ValueError): abi.atomic_json(alias, {})

    def test_final_check_rejects_apk_replacement_without_publishing_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            files = {}
            for name in ("app.apk", "test.apk", "r8.jar", "java", "manifest.json"):
                files[name] = root/name; files[name].write_text("original")
            args = SimpleNamespace(output=str(root/"receipt.json"), root=str(root),
                java=str(files["java"]), r8=str(files["r8.jar"]),
                manifest=str(files["manifest.json"]), app_apk=str(files["app.apk"]),
                test_apk=str(files["test.apk"]), accepted_diagnostics=None)
            manifest = {"inputs": {str(files["r8.jar"]): abi.sha(files["r8.jar"])},
                "runtime_libraries": {}, "runtime_library_order": [], "platform_source_inputs": []}
            def replace_during_trace(*unused):
                files["app.apk"].write_text("replaced concurrently")
                return []
            with patch.object(abi, "version"), patch.object(abi, "verify", return_value=manifest), \
                 patch.object(abi, "validate_runtime_libraries"), \
                 patch.object(abi, "run_trace", side_effect=replace_during_trace):
                with self.assertRaisesRegex(ValueError, "changed during final"):
                    abi.check(args)
            self.assertFalse(Path(args.output).exists())

    def test_annotation_exclusion_is_not_an_ordinary_runtime_call(self):
        abi.validate_exclusions([abi.ANNOTATION_DIAGNOSTIC])
        for line in (abi.ANNOTATION_DIAGNOSTIC.replace("value()", "runtimeCall()"),
                     "Missing method void javax.lang.model.element.Modifier.invoke() (referenced from: void com.google.errorprone.annotations.IncompatibleModifiers.value())"):
            with self.assertRaises(ValueError): abi.validate_exclusions([line])

    def test_modifier_rule_is_single_class_and_qa_test_only(self):
        rules = (ROOT/"gradle/instrumentation-qa-test.pro").read_text()
        active = [line for line in rules.splitlines() if line and not line.startswith("#")]
        self.assertEqual([line for line in active if line.startswith("-dontwarn")], ["-dontwarn javax.lang.model.element.Modifier"])
        for file in ("app/build.gradle.kts", "gradle/release-validation.gradle.kts"):
            config = (ROOT/file).read_text()
            before, inside = config.split('if (instrumentationQa == "true") {', 1)
            self.assertNotIn("instrumentation-qa-test.pro", before)
            self.assertIn('"testProguardFiles"(rootProject.file("gradle/instrumentation-qa-test.pro"))', inside)
            self.assertNotIn('"proguardFiles"(rootProject.file("gradle/instrumentation-qa-test.pro"))', inside)

    def test_ineffective_lambda_keeps_removed_and_transform_scoped_to_qa_test(self):
        rules = (ROOT/"gradle/instrumentation-qa-test.pro").read_text()
        self.assertNotIn("keepclassmembers", rules)
        config = (ROOT/"app/build.gradle.kts").read_text()
        before, inside = config.split('if (instrumentationQa == "true") {', 1)
        self.assertNotIn('.toTransform(', before)
        self.assertIn('testComponent.artifacts.forScope', inside)
        self.assertNotIn('variant.artifacts.forScope', inside)
        self.assertIn('ScopedArtifact.CLASSES', inside)
        self.assertIn('normalizeQaMonitorMetadata', inside)
        for task in ('minifyReleaseWithR8', 'minifyReleaseAndroidTestWithR8', 'packageRelease', 'packageReleaseAndroidTest'):
            self.assertIn('"' + task + '"', inside)
        exporter = (ROOT/"tools/export_instrumentation_classpaths.init.gradle").read_text()
        self.assertIn("app.tasks.named('normalizeQaMonitorMetadata')", exporter)
        self.assertIn('result.test = [normalization.outputClasses.get().asFile.absolutePath]', exporter)

    def test_annotation_types_are_included_in_surface_count(self):
        rules = "\n".join('-keep ' + kind + ' Exact {}' for kind in ('class','interface','enum','@interface'))
        self.assertEqual(abi.count_retained_types(rules), 4)

    def test_changed_metadata_receipt_invalidates_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest, _, _, _ = self.fixture(root)
            metadata = Path(json.loads(manifest.read_text())["metadata_normalization"]["receipt"])
            metadata.write_text('{"changed":true}')
            with self.assertRaisesRegex(ValueError, "metadata receipt changed"):
                abi.verify(manifest, root)

    def test_final_receipt_preserves_normalization_and_qualification_provenance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); files = {}
            for name in ("app.apk", "test.apk", "r8.jar", "java", "manifest.json"):
                files[name] = root/name; files[name].write_text("fixture")
            args = SimpleNamespace(output=str(root/"receipt.json"), root=str(root),
                java=str(files["java"]), r8=str(files["r8.jar"]),
                manifest=str(files["manifest.json"]), app_apk=str(files["app.apk"]),
                test_apk=str(files["test.apk"]), accepted_diagnostics=None)
            normalization = {"receipt_sha256": "fixture", "class_changes": ["exact-two"]}
            manifest = {"inputs": {str(files["r8.jar"]): abi.sha(files["r8.jar"])},
                "runtime_libraries": {}, "runtime_library_order": [], "platform_source_inputs": [],
                "rules_sha256": "rules", "source_sha256": "source", "metadata_normalization": normalization,
                "platform_qualification_limit": "Not API35 runtime proof"}
            with patch.object(abi, "version"), patch.object(abi, "verify", return_value=manifest), \
                 patch.object(abi, "validate_runtime_libraries"), patch.object(abi, "run_trace", return_value=[]), \
                 patch("builtins.print"):
                abi.check(args)
            result = json.loads(Path(args.output).read_text())
            self.assertEqual(result["metadata_normalization"], normalization)
            self.assertEqual(result["platform_qualification_limit"], "Not API35 runtime proof")
            self.assertTrue(result["qaRetention"])
            self.assertFalse(result["runtime_executed"])
            self.assertFalse(result["production_equivalence"])

    def test_manifest_cannot_misstate_authoritative_metadata_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest, _, _, _ = self.fixture(root)
            data = json.loads(manifest.read_text()); data["metadata_normalization"]["output"] = "forged.jar"
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "manifest/receipt mismatch"):
                abi.verify(manifest, root)

    def test_normalized_test_coverage_and_unchanged_bytecode_are_required(self):
        originals = {"Test.class": b"test", "R.class": b"resource", "Shared.class": b"shared"}
        source = {"Test.class": b"test", "R.class": b"resource"}
        abi.validate_normalized_test_classes(source, {"Shared.class":b"shared"}, originals, {"class_changes":[]})
        for invalid in ({"Test.class":b"test"}, dict(source, **{"Extra.class":b"extra"}), dict(source, **{"R.class":b"changed"})):
            with self.assertRaises(ValueError):
                abi.validate_normalized_test_classes(invalid, {"Shared.class":b"shared"}, originals, {"class_changes":[]})

    def test_gradle_mode_is_explicit_test_signed_release_only(self):
        config = (ROOT/"app/build.gradle.kts").read_text()
        self.assertIn('gradleProperty("realityForgeInstrumentationQa").orElse("false")', config)
        self.assertIn('candidateMode == "test" && candidateTestBuildType == "release"', config)
        self.assertIn('minifyReleaseWithR8', config)
        self.assertIn('dependsOn(verifyQaRetention)', config)
        self.assertIn('setDsl("minifyEnabled", true)', config)


if __name__ == "__main__": unittest.main()
