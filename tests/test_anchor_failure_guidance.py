"""Wiring/resource guards supplement, not replace, the executable Kotlin classifier tests."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/realityforge/scanner'


def test_no_anchor_uses_evidence_not_unconditional_overlap():
    source = (JAVA / 'engine/SparsePipeline.kt').read_text()
    assert 'val anchorRejections = AnchorRejectionSummary()' in source
    assert 'anchorRejections.record(result.optString("errorCode"))' in source
    assert 'anchorRejections.record(null)' in source
    branch = source.split('var output = best ?: run {', 1)[1].split('diagnosticRecorder?.outcome', 1)[0]
    assert 'val failure = anchorRejections.failure()' in branch
    assert branch.index('activeDiagnosticStage = failure.stage') < branch.index('throw DiagnosticOperationException(failure)')
    assert 'best ?: reject(ScanStage.MATCHING, ScanFailureCode.INSUFFICIENT_OVERLAP' not in source


def test_installed_processing_guidance_uses_localizable_safe_copy():
    source = (JAVA / 'ui/navigation/InstalledScannerFeatures.kt').read_text()
    assert 'R.string.a074_geometry_unestablished' in source
    assert '"CAMERA_REGISTRATION_FAILED"' in source
    strings = {e.attrib['name']: e.text for e in ET.parse(ROOT / 'app/src/main/res/values/agent_074.xml').getroot() if e.tag == 'string'}
    copy = strings['a074_geometry_unestablished']
    assert 'distinct sideways viewpoints' in copy
    assert 'overlap' in copy
    assert 'weak or ambiguous' in copy
    assert 'too little' not in copy
