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
