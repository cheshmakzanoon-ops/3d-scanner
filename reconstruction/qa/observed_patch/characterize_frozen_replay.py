#!/usr/bin/env python3
"""Read-only provenance/refusal characterization. NEVER invokes a mesh kernel.
Only the checksum-pinned public, different-pixel local baseline is admitted.
No production observation capture, source-ID invention, or artifact upload.
"""
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path
import struct

PINNED = {
    'frozen-baseline.case': '4ac1007c4dbcb7dc247f941a7a75ac5181cf0ad24adaae9ce99c7b1301233daa',
    'cameras.bin': 'e4abe214237fdf449ccc9eef477d759f8b05549791fc0d1cb20935e4bfb459c9',
    'candidate2-baseline.ledger.json': '58ab21c72342c8885937d7795755efecabee1139ebec64f422089d7188f52db1',
    'candidate2-baseline.mesh.json': 'c089a3f940b0b5a211b9d0df684afbcb383a34e02c99e0f4a1499a2d4f8b2ee0',
}
MAX_RESIDUAL = .02  # Same predeclared synthetic kernel setting. No sweep.
MAX_WORK = 2_000_000


def down(x): return math.nextafter(x, -math.inf)
def up(x): return math.nextafter(x, math.inf)
def scalar(x): return (x, x)
def add(a, b): return (down(a[0]+b[0]), up(a[1]+b[1]))
def mul(a, b):
    products = [x*y for x in a for y in b]
    return (down(min(products)), up(max(products)))
def divide(a, b):
    if b[0] <= 0 <= b[1]:
        return (-math.inf, math.inf)
    return mul(a, (down(1/b[1]), up(1/b[0])))


def project_bounds(camera, points):
    result = [[math.inf, -math.inf], [math.inf, -math.inf]]
    for p in points:
        q = []
        for axis in range(3):
            v = scalar(camera[9+axis])
            for k in range(3):
                v = add(v, mul(scalar(camera[axis*3+k]), scalar(p[k])))
            q.append(v)
        if q[2][0] <= 1e-8:
            return ((-math.inf, math.inf),)*2  # Unknown => conservatively include.
        for axis in range(2):
            x = add(mul(scalar(camera[12+axis]), divide(q[axis], q[2])), scalar(camera[14+axis]))
            if not all(map(math.isfinite, x)):
                return ((-math.inf, math.inf),)*2
            result[axis][0] = min(result[axis][0], x[0])
            result[axis][1] = max(result[axis][1], x[1])
    return result


def overlap(a, b):
    return all(a[k][0] <= b[k][1] and b[k][0] <= a[k][1] for k in range(len(a)))


def bounds(points):
    return [(min(p[k] for p in points), max(p[k] for p in points)) for k in range(3)]


def read_pinned(directory):
    result = {}
    for name, expected in PINNED.items():
        path = directory/name
        if path.stat().st_size > 16*1024*1024:
            raise ValueError('Input byte cap: '+name)
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Immutable fixture checksum mismatch: '+name)
        result[name] = data
    return result


def characterize(directory):
    raw = read_pinned(directory)
    data = raw['frozen-baseline.case']
    if data[:8] != b'RFCASE01' or struct.unpack_from('<III', data, 8) != (3, 384, 256):
        raise ValueError('Frozen case header')
    offset = 20 + 3*384*256*8
    count = struct.unpack_from('<Q', data, offset)[0]
    offset += 8
    sample_format = '<ddd3BfBBHHdddfB'
    stride = struct.calcsize(sample_format)
    if not 0 < count <= 100000 or offset+count*stride != len(data):
        raise ValueError('Frozen sample count/size')
    samples = [struct.unpack_from(sample_format, data, offset+i*stride) for i in range(count)]
    camera_bytes = raw['cameras.bin']
    if len(camera_bytes) != 3*16*8:
        raise ValueError('Camera size')
    cameras = [struct.unpack_from('<16d', camera_bytes, i*16*8) for i in range(3)]
    ledger = json.loads(raw['candidate2-baseline.ledger.json'])
    mesh = json.loads(raw['candidate2-baseline.mesh.json'])
    if mesh['fusedObservations'] != 0:
        raise ValueError('Require complete-view zero-fusion baseline')
    vertices = ledger['vertices']
    if not 0 < len(vertices) <= 40000 or len(ledger['triangles']) > 80000:
        raise ValueError('Mesh bounds')
    source_for_vertex = {}
    roundoff_vertices = set()
    max_roundoff = 0.
    for vi, camera, x, y, sid, relation in ledger['observations']:
        if vi in source_for_vertex or relation != 0 or not 0 <= vi < len(vertices) or not 0 <= sid < count:
            raise ValueError('Not a singleton direct Accumulator ledger')
        sample = samples[sid]
        if (camera, x, y) != (sample[8], sample[9], sample[10]):
            raise ValueError('Original sample camera/pixel disagreement')
        original = sample[:3]
        emitted = vertices[vi]['xyz']
        if not all(map(math.isfinite, original+tuple(emitted))):
            raise ValueError('Nonfinite source')
        error = max(abs(original[k]-emitted[k]) for k in range(3))
        if error > 8*math.ulp(max(1., *map(abs, original))):
            raise ValueError('Unexplained singleton displacement')
        if error:
            roundoff_vertices.add(vi)
        max_roundoff = max(max_roundoff, error)
        source_for_vertex[vi] = sid
    if len(source_for_vertex) != len(vertices):
        raise ValueError('Incomplete original vertex ledger')
    triangles = []
    for a, b, c, camera in ledger['triangles']:
        ids = tuple(source_for_vertex[v] for v in (a, b, c))
        if any(samples[sid][8] != camera for sid in ids) or len(set(ids)) != 3:
            raise ValueError('Triangle camera/source provenance')
        # Original sample coordinates, NOT guessed from exported vertex numbers.
        original = [samples[sid][:3] for sid in ids]
        admitted = [vertices[v]['xyz'] for v in (a, b, c)]
        identity = (camera, *sorted(ids))
        triangles.append({'identity': identity, 'original': original, 'admitted': admitted,
                          'bounds': bounds(original), 'needsAdmissionRecheck': any(v in roundoff_vertices for v in (a,b,c))})
    triangles.sort(key=lambda t: t['identity'])
    if len({t['identity'] for t in triangles}) != len(triangles):
        raise ValueError('Duplicate stable source triangle')
    pair = None
    visits = 0
    for i, a in enumerate(triangles):
        for j in range(i+1, len(triangles)):
            visits += 1
            if visits > MAX_WORK:
                raise ValueError('Candidate pair work cap')
            b = triangles[j]
            if a['identity'][0] == b['identity'][0]:
                continue
            padded_a = [(down(lo-MAX_RESIDUAL), up(hi+MAX_RESIDUAL)) for lo,hi in a['bounds']]
            padded_b = [(down(lo-MAX_RESIDUAL), up(hi+MAX_RESIDUAL)) for lo,hi in b['bounds']]
            if overlap(padded_a, padded_b):
                pair = (i, j)
                break
        if pair is not None:
            break
    report = {
        'classification': 'PUBLIC_DIFFERENT_PIXEL_FROZEN_NATIVE_REPLAY_PROVENANCE_ONLY_NOT_PRODUCTION_HOST_NOT_PHYSICAL_QUALITY',
        'kernelInvoked': False,
        'decision': 'STOP_FAILED_SYNTHETIC_PHYSICAL_DISAMBIGUATION_GATE',
        'sourceHashes': PINNED,
        'maxResidual': MAX_RESIDUAL,
        'sourceTriangles': len(triangles), 'originalVerifiedSamples': count,
        'cameraCount': len(cameras), 'retainedFusionCount': mesh['fusedObservations'],
        'witnessPopulation': 'All admitted source triangles; unmeshed depth pixels are not certified free-space witnesses.',
        'singletonRoundingVertices': len(roundoff_vertices), 'maximumCoordinateRounding': max_roundoff,
        'candidatePairVisits': visits, 'candidateSearchContinuedAfterFirst': False,
        'coordinatePolicy': 'Original RFCASE01 sample XYZ via verified singleton ledger; changed corners require fresh face-admission verification, NOT silently reused.',
        'productionObservationInputs': 'UNAVAILABLE_IN_PUBLISHED_HOST_ARTIFACT',
        'derivedReplayGeometryCreated': False,
    }
    if pair is None:
        report['adapterAdmission'] = 'REFUSED_NO_FIRST_OVERLAP'
        return report
    a, b = [triangles[i] for i in pair]
    domain = [(down(min(a['bounds'][k][0], b['bounds'][k][0])-2*MAX_RESIDUAL),
               up(max(a['bounds'][k][1], b['bounds'][k][1])+2*MAX_RESIDUAL)) for k in range(3)]
    corners = list(itertools.product(*domain))
    footprint = [project_bounds(camera, corners) for camera in cameras]
    witnesses = []
    for i, triangle in enumerate(triangles):
        camera = triangle['identity'][0]
        # Include union of original and actually admitted corners, conservatively.
        projected = project_bounds(cameras[camera], triangle['original']+triangle['admitted'])
        if overlap(footprint[camera], projected):
            witnesses.append(i)
    if not set(pair).issubset(witnesses):
        raise ValueError('Selected pair missing from conservative witness set')
    report.update({
        'selectedPairOriginalSampleIdentities': [a['identity'], b['identity']],
        'gridDomain': domain,
        'witnessTrianglesConsidered': len(triangles), 'potentialWitnessTriangles': len(witnesses),
        'certifiedDisjointWitnessTriangles': len(triangles)-len(witnesses),
        'witnessCameraCounts': [sum(triangles[i]['identity'][0] == c for i in witnesses) for c in range(3)],
        'witnessesNeedingOriginalCoordinateReadmission': sum(triangles[i]['needsAdmissionRecheck'] for i in witnesses),
        'selectedPairNeedsOriginalCoordinateReadmission': a['needsAdmissionRecheck'] or b['needsAdmissionRecheck'],
        'witnessTruncation': False,
        'adapterAdmission': 'REFUSED_WITNESS_CAP' if len(witnesses)>256 else 'NOT_RUN_PHYSICAL_GATE_FAILED_AND_ORIGINAL_CORNER_READMISSION_REQUIRED',
        'potentialWitnessOriginalSampleIdentities': [triangles[i]['identity'] for i in witnesses],
    })
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture_directory', type=Path)
    args = parser.parse_args()
    print(json.dumps(characterize(args.fixture_directory), indent=2, sort_keys=True))
