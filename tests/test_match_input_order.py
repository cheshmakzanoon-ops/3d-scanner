"""Wiring guards only; numeric payload behavior is covered by MatchInputOrderTest/JNI gate."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / 'app/src/main/java/com/realityforge/scanner/engine'


def test_pipeline_canonicalizes_physical_rows_after_uuid_ordered_descriptor_borrow():
    source = (ENGINE / 'SparsePipeline.kt').read_text()
    adapter = source[source.index('private suspend fun matches('):]
    assert 'val canonical = a.features.photoId < b.features.photoId' in adapter
    assert 'matcher.match(first, second,' in adapter
    assert 'tentative.inInputOrder(a.features.photoId, a.colors).withIndex()' in adapter
    assert 'val firstKey = m.firstKey' in adapter
    assert 'val secondKey = m.secondKey' in adapter
    assert 'val color = m.argb' in adapter
    assert 'if (canonical) m.' not in adapter


def test_production_matching_identity_binds_row_order_policy_before_alignment_reuse():
    source = (ENGINE / 'pipeline/ProductionPipelineSnapshot.kt').read_text()
    matching = next(line for line in source.splitlines() if 'matching = ' in line)
    assert 'TentativeMatches.INPUT_ORDER_POLICY' in matching
    assert 'pair-cache-v3' in matching  # wire shape unchanged; semantic identity changes
    assert 'physical-feature-key-row-order-v1' in (ENGINE / 'matching/VerifiedMatches.kt').read_text()
