"""Source contracts: cancellation must not bypass evidence acceptance checks."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_candidate_evidence_audits_failure_but_is_cancellable():
    workflow = (ROOT / ".github/workflows/verify.yml").read_text()
    finalizer = workflow.split("  candidate-evidence:\n", 1)[1]
    assert "    if: ${{ !cancelled() }}\n" in finalizer
    assert "    if: always()" not in finalizer
    assert "    needs: [native, android, device]" in finalizer
    assert 'all(x["result"] == "success"' in finalizer
    assert "Required job failed, skipped or cancelled" in finalizer
    assert "evidence.py verify --directory" in finalizer
    assert "cancel-in-progress: true" in workflow


def test_verify_group_migration_retains_one_stable_group_per_ref():
    workflow = (ROOT / ".github/workflows/verify.yml").read_text()
    concurrency = workflow.split("concurrency:\n", 1)[1].split("jobs:\n", 1)[0]
    assert "  group: verify-v2-${{ github.event.pull_request.number || github.ref }}\n" in concurrency
    assert "  cancel-in-progress: true\n" in concurrency
    assert "github.sha" not in concurrency
    assert "github.run_id" not in concurrency
    assert "github.run_attempt" not in concurrency
