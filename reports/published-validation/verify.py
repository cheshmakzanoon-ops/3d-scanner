#!/usr/bin/env python3
"""Read-only schema, Git source and optional artifact-byte checks for receipts."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import zipfile



class ReceiptError(ValueError):
    """Controlled diagnostic containing no host paths or raw payloads."""


def fail(message):
    raise ReceiptError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail("Duplicate JSON key")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object,
                      parse_constant=lambda _: fail("Invalid JSON numeric constant"))


# Dependency-free support for the exact keyword subset used by receipt.schema.json.
# Unknown keywords fail closed rather than silently weakening a future schema.
class SchemaMismatch(ReceiptError):
    pass


def check_schema(schema):
    if isinstance(schema, bool):
        return
    if not isinstance(schema, dict):
        fail("Invalid schema definition")
    allowed = {"$schema", "title", "type", "properties", "additionalProperties",
               "required", "const", "enum", "pattern", "minLength", "minProperties",
               "propertyNames", "items", "minItems", "uniqueItems", "contains", "minimum"}
    if set(schema) - allowed:
        fail("Unsupported schema keyword")
    if "$schema" in schema and schema["$schema"] != "https://json-schema.org/draft/2020-12/schema":
        fail("Unsupported schema dialect")
    if "type" in schema and schema["type"] not in ("object", "array", "string", "integer"):
        fail("Unsupported schema type")
    for key in ("minLength", "minProperties", "minItems", "minimum"):
        if key in schema and (type(schema[key]) is not int or schema[key] < 0):
            fail("Invalid schema numeric constraint")
    for key in ("title", "pattern"):
        if key in schema and not isinstance(schema[key], str):
            fail("Invalid schema string constraint")
    if "pattern" in schema:
        re.compile(schema["pattern"])
    if "required" in schema:
        required = schema["required"]
        if (not isinstance(required, list) or not all(isinstance(x, str) for x in required)
                or len(required) != len(set(required))):
            fail("Invalid required-property definition")
    if "enum" in schema and (not isinstance(schema["enum"], list) or not schema["enum"]):
        fail("Invalid enum definition")
    if "uniqueItems" in schema and type(schema["uniqueItems"]) is not bool:
        fail("Invalid uniqueness definition")
    if "properties" in schema:
        if not isinstance(schema["properties"], dict):
            fail("Invalid property definitions")
        for child in schema["properties"].values():
            check_schema(child)
    for key in ("additionalProperties", "propertyNames", "items", "contains"):
        if key in schema:
            check_schema(schema[key])


def json_equal(left, right):
    if isinstance(left, bool) or isinstance(right, bool):
        return type(left) is type(right) and left == right
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(json_equal(left[k], right[k]) for k in left)
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(json_equal(a, b) for a, b in zip(left, right))
    return left == right


def validate_schema(schema, value):
    def require(condition):
        if not condition:
            raise SchemaMismatch("Receipt schema constraint failed")
    if isinstance(schema, bool):
        require(schema)
        return
    kind = schema.get("type")
    if kind:
        valid_type = {
            "object": isinstance(value, dict), "array": isinstance(value, list),
            "string": isinstance(value, str),
            "integer": (type(value) is int or type(value) is float and value.is_integer()),
        }[kind]
        require(valid_type)
    if "const" in schema:
        require(json_equal(value, schema["const"]))
    if "enum" in schema:
        require(any(json_equal(value, entry) for entry in schema["enum"]))
    if isinstance(value, str):
        require(len(value) >= schema.get("minLength", 0))
        if "pattern" in schema:
            require(re.search(schema["pattern"], value) is not None)
    if isinstance(value, (int, float)) and not isinstance(value, bool) and "minimum" in schema:
        require(value >= schema["minimum"])
    if isinstance(value, dict):
        require(len(value) >= schema.get("minProperties", 0))
        require(all(key in value for key in schema.get("required", [])))
        for key, item in value.items():
            if "propertyNames" in schema:
                validate_schema(schema["propertyNames"], key)
            child = schema.get("properties", {}).get(key, schema.get("additionalProperties", True))
            validate_schema(child, item)
    if isinstance(value, list):
        require(len(value) >= schema.get("minItems", 0))
        if schema.get("uniqueItems"):
            require(not any(json_equal(item, prior) for i, item in enumerate(value) for prior in value[:i]))
        for item in value:
            validate_schema(schema.get("items", True), item)
        if "contains" in schema:
            matched = False
            for item in value:
                try:
                    validate_schema(schema["contains"], item)
                    matched = True
                except SchemaMismatch:
                    pass
            require(matched)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def stream_digest(stream):
    hasher = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        hasher.update(chunk)
    return hasher.hexdigest()


def file_digest(path):
    with path.open("rb") as stream:
        return stream_digest(stream)


def git(repo, *args, input=None):
    # Never fetch missing objects from a promisor remote during this read-only check.
    return subprocess.check_output(
        ["git", "-C", str(repo), *args], input=input, stderr=subprocess.PIPE,
        env={**os.environ, "GIT_NO_LAZY_FETCH": "1", "GIT_NO_REPLACE_OBJECTS": "1"},
    )


def commit_available(repo, commit):
    result = git(repo, "cat-file", "--batch-check=%(objecttype)",
                 input=(commit + "\n").encode()).decode().strip()
    if result == commit + " missing":
        return False
    if result != "commit":
        fail("Source identity is not a commit object")
    return True


def verify(args):
    here = Path(__file__).resolve().parent
    schema = read_json(here / "receipt.schema.json")
    check_schema(schema)
    filenames = {
        "dense-incumbent": "cloud-dense-incumbent-validation.json",
        "ci-diagnostics": "cloud-ci-diagnostic-validation.json",
    }
    receipts = {}
    source_count = binding_count = 0
    source_objects = {}
    partial = False
    for kind, filename in filenames.items():
        receipt = read_json(here.parent / filename)
        validate_schema(schema, receipt)
        if receipt["kind"] != kind:
            fail("Receipt kind/file mismatch")
        sources = receipt["sources"]
        source_objects[kind] = {}
        available_roles = set()
        for role, source in sources.items():
            if not commit_available(args.repo, source["commit"]):
                if role == "published" or args.require_retained_source_objects:
                    fail("Required source commit unavailable: " + kind + "." + role)
                source_objects[kind][role] = "NOT_CHECKED_RETAINED_COMMIT_UNAVAILABLE"
                partial = True
                continue
            actual = git(args.repo, "rev-parse", source["commit"] + "^{tree}").decode().strip()
            if actual != source["tree"]:
                fail("Commit/tree mismatch: " + kind + "." + role)
            source_objects[kind][role] = "PASS"
            available_roles.add(role)
            source_count += 1
        if sources["published"]["tree"] != sources["validated"]["tree"]:
            fail("Published and validated trees differ")
        if "published" not in receipt["sourceBindingRoles"]:
            fail("Published source bindings are required")
        for role in receipt["sourceBindingRoles"]:
            if role not in sources:
                fail("Unknown source-binding role")
            if role not in available_roles:
                continue
            for name, expected in receipt["sourceBindings"].items():
                if digest(git(args.repo, "show", sources[role]["commit"] + ":" + name)) != expected:
                    fail("Source binding mismatch: " + kind + "." + role)
                binding_count += 1
        for artifact in receipt["artifacts"].values():
            if artifact["sourceRole"] not in sources:
                fail("Unknown artifact build-source role")
        names = [gate["name"] for gate in receipt["gates"]]
        if len(names) != len(set(names)):
            fail("Duplicate gate name")
        receipts[kind] = receipt
    dense, ci = receipts["dense-incumbent"], receipts["ci-diagnostics"]
    if dense["artifacts"]["hostEngine"]["sha256"] != ci["artifacts"]["hostEngine"]["sha256"]:
        fail("Selected host engines differ")
    if dense["sources"]["artifactBuild"] != ci["sources"]["artifactBuild"]:
        fail("Host engine build-source identities differ")
    artifacts = {"hostEngine": "NOT_CHECKED_NOT_SUPPLIED", "debugApk": "NOT_CHECKED_NOT_SUPPLIED"}
    if args.native_library:
        if file_digest(args.native_library) != dense["artifacts"]["hostEngine"]["sha256"]:
            fail("Host engine SHA-256 mismatch")
        artifacts["hostEngine"] = "PASS"
    if args.debug_apk:
        expected = dense["artifacts"]["debugApk"]
        if file_digest(args.debug_apk) != expected["sha256"]:
            fail("Debug APK SHA-256 mismatch")
        with zipfile.ZipFile(args.debug_apk) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                fail("Duplicate APK ZIP entry")
            native_names = {name for name in names if name.endswith("/librealityforge.so")}
            if native_names != set(expected["zipEntries"]):
                fail("APK native entry set mismatch")
            for name, sha in expected["zipEntries"].items():
                with archive.open(name) as stream:
                    if stream_digest(stream) != sha:
                        fail("APK native entry hash mismatch")
        artifacts["debugApk"] = "PASS"
    print(json.dumps({
        "schema": "PASS", "publishedSourceBindings": "PASS",
        "sourceAndArtifactLinks": "PARTIAL" if partial else "PASS",
        "sourceLinkScope": "PARTIAL" if partial else "COMPLETE",
        "sourceObjects": source_objects,
        "sourceRecordsChecked": source_count, "sourceBindingsChecked": binding_count,
        "artifactBytes": artifacts,
        "scope": "Identity checks only; no test rerun, remote-ref check or build attestation."
    }, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--native-library", type=Path)
    parser.add_argument("--debug-apk", type=Path)
    parser.add_argument("--require-retained-source-objects", action="store_true",
                        help="Fail if any retained validation/build commit object is unavailable")
    try:
        verify(parser.parse_args())
    except ReceiptError as error:
        print("Receipt verification FAILED: " + str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError,
            re.error, zipfile.BadZipFile) as error:
        # No command stderr, host paths, raw input or schema payload in public output.
        print("Receipt verification FAILED: " + type(error).__name__, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
