"""Constant/protocol drift guard; actual filesystem behavior is tested by Kotlin JVM tests."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
def test_complete_reference_bound_matches_native_load_and_store():
 native=(ROOT/'reconstruction/include/realityforge/dense.hpp').read_text()
 selector=(ROOT/'app/src/main/java/com/realityforge/scanner/engine/pipeline/NativeDepthReferences.kt').read_text()
 n=int(re.search(r'MAX_DENSE_VIEWS\s*=\s*(\d+)',native).group(1))
 k=int(re.search(r'MAX_COMPLETE_NATIVE_REFERENCES\s*=\s*(\d+)',selector).group(1))
 assert k==n==8
 protocol=(ROOT/'reconstruction/src/depth_checkpoint.cpp').read_text()
 assert len(re.findall(r'index\s*>=\s*MAX_DENSE_VIEWS',protocol))==2
 assert '"ref-"+std::to_string(index)+".bin"' in protocol
 assert '"tile-"+std::to_string(k.reference)' in protocol

def test_record_uses_bounded_optional_reference_selection():
 selector=(ROOT/'app/src/main/java/com/realityforge/scanner/engine/pipeline/NativeDepthReferences.kt').read_text()
 record=(ROOT/'app/src/main/java/com/realityforge/scanner/engine/pipeline/PipelineDownstreamCheckpoints.kt').read_text()
 assert 'completeNativeDepthReferences(directory)' in record
 assert '0 until MAX_COMPLETE_NATIVE_REFERENCES' in selector
 assert 'Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)' in selector
 assert 'newDirectoryStream' not in record and 'newDirectoryStream' not in selector
