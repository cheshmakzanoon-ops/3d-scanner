"""Call-owned byte identities for dense QA receipts, never build attestations."""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
from typing import Any, Iterator


class ProvenanceChanged(ValueError):
    """Observed execution inputs no longer match the evidence being published."""


def byte_identity(payload: bytes) -> dict[str, Any]:
    return {"sha256": hashlib.sha256(payload).hexdigest(), "byteCount": len(payload)}


def _stat_identity(value: os.stat_result) -> tuple[int, ...]:
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


@dataclass(frozen=True)
class FileIdentity:
    label: str
    path: Path
    sha256: str
    byte_count: int
    observed_stat: tuple[int, ...]

    @classmethod
    def capture(cls, label: str, path: Path) -> FileIdentity:
        path = path.absolute()
        with path.open("rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode):
                raise ProvenanceChanged(f"Provenance input is not a regular file: {label}")
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
            after = os.fstat(stream.fileno())
        identity = _stat_identity(before)
        if identity != _stat_identity(after) or identity != _stat_identity(path.stat()):
            raise ProvenanceChanged(f"Provenance input changed while hashing: {label}")
        return cls(label, path, digest, before.st_size, identity)

    def verify(self) -> None:
        try:
            current = self.capture(self.label, self.path)
        except OSError as failure:
            raise ProvenanceChanged(f"Provenance input became unavailable: {self.label}") from failure
        if current != self:
            raise ProvenanceChanged(f"Provenance input changed during execution: {self.label}")

    def document(self) -> dict[str, Any]:
        # Absolute paths and local filesystem identities never enter receipts.
        return {"sha256": self.sha256, "byteCount": self.byte_count}


def _git_context(root: Path) -> tuple[str | None, bool | None]:
    try:
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, check=True, capture_output=True,
            text=True, timeout=10,
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "status", "--porcelain", "--untracked-files=normal"], cwd=root,
            check=True, capture_output=True, timeout=10,
        ).stdout
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return None, None
    if len(head) not in (40, 64) or any(char not in "0123456789abcdef" for char in head):
        return None, None
    return head, bool(dirty)


def _array_identity(value: Any) -> dict[str, Any]:
    return {**byte_identity(value.tobytes(order="C")), "shape": list(value.shape),
            "dtype": value.dtype.str, "order": "C"}


def _measurement_identity(rows: Any, decisions: Any) -> str:
    payload = json.dumps(
        {"rows": [asdict(row) for row in rows], "decisions": decisions},
        sort_keys=True, separators=(",", ":"), allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


@dataclass(frozen=True)
class NativeRunInputs:
    library: FileIdentity
    helpers: tuple[FileIdentity, ...]
    git_head: str | None
    git_dirty: bool | None

    @classmethod
    def capture(cls, library: Path) -> NativeRunInputs:
        root = Path(__file__).resolve().parents[2]
        names = (
            "tools/native_api.py", "tools/qa/dense_profile_sweep.py",
            "tools/qa/dense_loss_report.py", "tools/qa/surface_pass_report.py",
            "tools/qa/report_io.py", "tools/qa/dense_receipt_provenance.py",
        )
        binary = FileIdentity.capture("native library", library.resolve(strict=True))
        helpers = tuple(FileIdentity.capture(name, root / name) for name in names)
        return cls(binary, helpers, *_git_context(root))

    def verify(self) -> None:
        self.library.verify()
        for helper in self.helpers:
            helper.verify()

    @contextmanager
    def isolated_library(self) -> Iterator[FileIdentity]:
        """Load a unique verified inode, never a cached dlopen pathname.

        A previous CDLL can keep the old mapping alive after its source pathname
        is atomically replaced. A fresh private copy prevents that mapping from
        being mistaken for the newly captured bytes. Its lifetime spans all
        native measurements; no temporary pathname enters the resulting receipt.
        The file is cleaned up, but ctypes/native TLS may retain its mapping
        until interpreter exit. Repeat bounded runs as separate CLI processes;
        this helper does not promise bounded mappings for a long-lived service.
        """
        root = Path(__file__).resolve().parents[2]
        temporary_root = Path(tempfile.gettempdir()).resolve()
        if temporary_root.is_relative_to(root):
            raise OSError("Native library temporary directory must be outside the source repository")
        self.verify()
        with tempfile.TemporaryDirectory(prefix="rf-native-evidence-", dir=temporary_root) as directory:
            target = Path(directory) / self.library.path.name
            with self.library.path.open("rb") as source, target.open("xb") as destination:
                shutil.copyfileobj(source, destination)
            # Read-only private bytes reduce accidental mutation. Before/after
            # identities still enforce the contract; permissions are not proof.
            target.chmod(stat.S_IRUSR)
            copied = FileIdentity.capture("isolated native library", target)
            if copied.document() != self.library.document():
                raise ProvenanceChanged("Native library copy differs from the captured bytes")
            self.verify()
            yield copied
            copied.verify()
            self.verify()


@dataclass(frozen=True)
class NativeRunEvidence:
    inputs: NativeRunInputs
    rgba: Any
    cameras: Any
    rgba_identity: str
    camera_identity: str
    measurements_sha256: str

    @classmethod
    def capture(cls, inputs: NativeRunInputs, rgba: Any, cameras: Any) -> NativeRunEvidence:
        return cls(inputs, rgba, cameras, json.dumps(_array_identity(rgba), sort_keys=True),
                   json.dumps(_array_identity(cameras), sort_keys=True), "")

    def verify(self) -> None:
        self.inputs.verify()
        if (json.dumps(_array_identity(self.rgba), sort_keys=True) != self.rgba_identity
                or json.dumps(_array_identity(self.cameras), sort_keys=True) != self.camera_identity):
            raise ProvenanceChanged("Fixture bytes changed during native execution")

    def bind(self, rows: Any, decisions: Any) -> NativeRunEvidence:
        self.verify()
        return NativeRunEvidence(
            self.inputs, self.rgba, self.cameras, self.rgba_identity, self.camera_identity,
            _measurement_identity(rows, decisions),
        )

    def document(self, rows: Any, decisions: Any) -> dict[str, Any]:
        self.verify()
        if not self.measurements_sha256 or self.measurements_sha256 != _measurement_identity(rows, decisions):
            raise ProvenanceChanged("Native provenance does not match the reported measurements")
        return {
            "execution": "HOST_CTYPES_NATIVE_SURFACE",
            "nativeLibrary": self.inputs.library.document(),
            "nativeLibraryLoadIsolation": "UNIQUE_VERIFIED_COPY",
            "limitations": [
                "A unique native library mapping may remain resident until interpreter exit; "
                "repeat bounded sweeps in separate CLI processes",
            ],
            "nativeLibraryBuildProvenance": None,
            "fixture": {"rgba": json.loads(self.rgba_identity),
                        "cameras": json.loads(self.camera_identity)},
            "helperSources": {item.label: item.document() for item in self.inputs.helpers},
            "sourceGitHEAD": self.inputs.git_head,
            "sourceGitDirty": self.inputs.git_dirty,
            "measurementsSha256": self.measurements_sha256,
        }


class SweepResult(tuple):
    """Preserve the public two-tuple while carrying explicit call-owned evidence."""

    def __new__(cls, rows: Any, decisions: Any, provenance: NativeRunEvidence) -> SweepResult:
        result = super().__new__(cls, (rows, decisions))
        result.provenance = provenance
        return result
