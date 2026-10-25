"""Closed source-reviewed experiment profiles, not configurable success thresholds."""
from dataclasses import dataclass
from .runtime import Failure

LEGACY = 'eight-cuboid'
RELIEF = 'relief-three'
COMPLETE = 'relief-three-complete'
REAL_STEREO = 'real-licensed-stereo-complete'
REAL_THREE = 'real-licensed-three-complete'
REAL_EIGHT_ORBIT = 'real-licensed-eight-orbit-sparse'
COMPLETE_PROFILES = (COMPLETE, REAL_STEREO, REAL_THREE)
REAL_PROFILES = (REAL_STEREO, REAL_THREE, REAL_EIGHT_ORBIT)
ALL_PROFILES = (LEGACY, RELIEF, COMPLETE, *REAL_PROFILES)
ACTIVE = REAL_EIGHT_ORBIT

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

_REAL_STEREO = Profile(REAL_STEREO,
    'com.realityforge.scanner.hostintegration.RealLicensedStereoCompleteControllerHostTest.fixedTwoLicensedPhotosCompletePublishReopenAndExportOrFail',
    'CI_ONLY_IMMUTABLE_LICENSED_STEREO', 'real_stereo_inputs.py',
    'RealLicensedStereoCompleteControllerHostTest.kt', 2, 1,
    'REAL_LICENSED_STEREO_HOST_FULL_CONTROLLER_NOT_DEVICE_QUALITY')

_REAL_THREE = Profile(REAL_THREE,
    'com.realityforge.scanner.hostintegration.RealLicensedThreeCompleteControllerHostTest.fixedThreeLicensedPhotosCompletePublishReopenAndExportOrFail',
    'CI_ONLY_IMMUTABLE_LICENSED_THREE', 'real_three_inputs.py',
    'RealLicensedThreeCompleteControllerHostTest.kt', 3, 3,
    'REAL_LICENSED_THREE_PARTIAL_ARC_HOST_FULL_CONTROLLER_NOT_DEVICE_QUALITY')

_REAL_EIGHT_ORBIT = Profile(REAL_EIGHT_ORBIT,
    'com.realityforge.scanner.hostintegration.RealLicensedEightOrbitSparsePrefixHostTest.fixedEightLicensedOrbitPhotosPersistSparseOrRecordUnmodifiedProductionFailure',
    'CI_ONLY_IMMUTABLE_LICENSED_EIGHT_ORBIT', 'real_eight_orbit_inputs.py',
    'RealLicensedEightOrbitSparsePrefixHostTest.kt', 8, 28,
    'REAL_LICENSED_EIGHT_WIDER_ORBIT_HOST_SPARSE_PREFIX_NOT_DEVICE_OR_SURFACE_QUALITY')

def get_profile(profile_id):
    if type(profile_id) is not str:
        raise Failure('Unknown closed experiment profile')
    if profile_id == LEGACY:
        return _LEGACY
    if profile_id == RELIEF:
        return _RELIEF
    if profile_id == COMPLETE:
        return _COMPLETE
    if profile_id == REAL_STEREO:
        return _REAL_STEREO
    if profile_id == REAL_THREE:
        return _REAL_THREE
    if profile_id == REAL_EIGHT_ORBIT:
        return _REAL_EIGHT_ORBIT
    raise Failure('Unknown closed experiment profile')


def artifact_budget(profile):
    """Reporting capacity only; never changes reconstruction or quality admission."""
    selected=get_profile(profile)
    return (56 if selected.id in COMPLETE_PROFILES else 32)*1024**2
