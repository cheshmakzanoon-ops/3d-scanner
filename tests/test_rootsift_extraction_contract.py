"""Source binding only; actual numeric behavior is covered by RootSiftTest/host extraction."""
from pathlib import Path
import importlib.util
ROOT=Path(__file__).resolve().parents[1]
ENGINE=ROOT/'app/src/main/java/com/realityforge/scanner/engine'

def test_extraction_normalizes_owned_clone_before_publication_and_versions_features():
 text=(ENGINE/'features/GrayFeatureExtraction.kt').read_text()
 assert 'rf-sift-v3|${RootSift.POLICY}' in text
 assert text.index('retained=owned') < text.index('RootSift.normalizeRow') < text.index('val frame=FeatureFrame')
 assert 'owned.get(i,0,rawRow)==RootSift.ROW_BYTES' in text
 assert 'owned.put(i,0,rootRow)==RootSift.ROW_BYTES' in text
 assert 'for(i in 0 until count) {\n                checkCancelled()' in text
 assert 'retained?.release()' in text
 assert 'sift=SIFT.create(settings.maxFeatures)' in text
 assert 'count=minOf(all.size,settings.maxFeatures)' in text

def test_raw_cache_cannot_share_new_extractor_identity_and_matcher_is_unchanged():
 text=(ENGINE/'features/SparseFeatureCache.kt').read_text()
 assert text.count('ExtractorSettings().identity(DECODER_IDENTITY)')==2
 snapshot=(ENGINE/'pipeline/ProductionPipelineSnapshot.kt').read_text()
 assert 'features = com.realityforge.scanner.engine.features.SparseFeatureCache.STAGE_SETTINGS' in snapshot
 matcher=(ENGINE/'matching/FeatureMatcher.kt').read_text()
 assert 'val ratio:Double=.67' in matcher
 assert 'BFMatcher.create(Core.NORM_L2,false)' in matcher
 assert 'x.distance<ratio*y.distance' in matcher
 assert 'RootSift' not in matcher

def test_all_explicit_extractor_compilers_bind_numeric_helper():
 for name in ['feature_adapter_validation','feature_extractor_validation','frontend_integration_validation']:
  path=ROOT/'tools'/name/'validate.py'
  spec=importlib.util.spec_from_file_location('rootsift_'+name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  assert sum(s.endswith('features/RootSift.kt') for s in m.SOURCES)==1


def test_host_inventory_counts_every_actual_extractor_test():
 text=(ROOT/'tools/feature_extractor_validation/ProductionExtractorHostTest.kt').read_text()
 assert text.count('@Test fun ')==13
 assert 'runCount==13' in (ROOT/'tools/feature_extractor_validation/HostRunner.kt').read_text()
 assert "'expectedTests':13" in (ROOT/'tools/feature_extractor_validation/validate.py').read_text()
