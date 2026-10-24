"""Source-wiring guards only; Kotlin tests exercise geometry, Android tests cover file decoding.
These guards do not claim runtime cancellation or Android execution.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/realityforge/scanner'


def test_preflight_checks_cancellation_around_authoritative_header_inspection():
    source = (MAIN / 'engine/SparsePipeline.kt').read_text()
    start = source.index('// Stored photo dimensions describe encoded pixels')
    end = source.index('val compatible =', start)
    handoff = source[start:end]
    before, after = handoff.split('val inspection = AndroidImageDecoder.inspectUpright(', 1)
    assert 'coroutineContext.ensureActive()' in before
    inspected, publication = after.split('val capture =', 1)
    assert 'DecodeRequest(repository.photoFile(photo))' in inspected
    assert 'coroutineContext.ensureActive()' in inspected
    assert '.forInspectedScan(metadata, inspection, photo.focal35mm)' in publication
    assert 'photo.width' not in handoff and 'photo.height' not in handoff
    assert 'catch' not in handoff  # Inspection/cancellation cannot silently become default geometry.


def test_header_inspection_and_pixel_decode_share_orientation_policy():
    source = (MAIN / 'imaging/AndroidImageDecoder.kt').read_text()
    header = source.split('fun inspectUpright(request: DecodeRequest): ImageInspection {', 1)[1].split('fun decodeBlocking', 1)[0]
    assert 'val bounds = inspect(request)' in header
    assert 'request.applyExifOrientation' in header
    assert 'readOrientation(request.file, bounds.format)' in header
    assert 'OrientationRead(ExifOrientation.NORMAL, OrientationEvidence.ABSENT, emptySet())' in header
    assert 'Bitmap.createBitmap' not in header and 'BitmapFactory.decodeFile' not in header
    decode = source.split('fun decodeBlocking(request: DecodeRequest): OwnedImage {', 1)[1].split('private fun readOrientation', 1)[0]
    assert 'val inspection = inspectUpright(request)' in decode
    assert 'readOrientation(' not in decode
    assert 'inspection.orientation.outputDimensions(' in decode
    assert 'inspection.orientation.renderMatrixValues(' in decode
