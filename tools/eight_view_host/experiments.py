"""Closed source-reviewed experiment profiles, not configurable success thresholds."""
from dataclasses import dataclass
from .runtime import Failure

LEGACY = 'eight-cuboid'
RELIEF = 'relief-three'
COMPLETE = 'relief-three-complete'
ACTIVE = COMPLETE

@dataclass(frozen=True)
class Profile:
    id: str
    selector: str
    fixture: str
    input_module: str
    test_source: str
    photo_count: int
    pair_count: int
    classification: str

_LEGACY = Profile(LEGACY,
    'com.realityforge.scanner.hostintegration.EightViewSparsePrefixHostTest.fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure',
    'docs/development/experiments/new-cuboid-eight-view-160x120', 'inputs.py',
    'EightViewSparsePrefixHostTest.kt', 8, 28, 'ALL8_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY')
_RELIEF = Profile(RELIEF,
    'com.realityforge.scanner.hostintegration.ReliefThreeSparsePrefixHostTest.fixedThreePhotosPersistSparseOrRecordUnmodifiedProductionFailure',
    'benchmarks/fixtures/relief', 'relief_inputs.py',
    'ReliefThreeSparsePrefixHostTest.kt', 3, 3, 'ALL3_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY')

_COMPLETE = Profile(COMPLETE,
    'com.realityforge.scanner.hostintegration.ReliefThreeCompleteControllerHostTest.fixedThreePhotosCompletePublishReopenAndExportOrFail',
    'benchmarks/fixtures/relief', 'relief_inputs.py',
    'ReliefThreeCompleteControllerHostTest.kt', 3, 3,
    'SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY')

def get_profile(profile_id):
    if type(profile_id) is not str:
        raise Failure('Unknown closed experiment profile')
    if profile_id == LEGACY:
        return _LEGACY
    if profile_id == RELIEF:
        return _RELIEF
    if profile_id == COMPLETE:
        return _COMPLETE
    raise Failure('Unknown closed experiment profile')


def artifact_budget(profile):
    """Reporting capacity only; never changes reconstruction or quality admission."""
    selected=get_profile(profile)
    return (56 if selected.id==COMPLETE else 32)*1024**2
