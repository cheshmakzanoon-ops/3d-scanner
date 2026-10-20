"""Independent positive-weight box/mip filter oracle; no production sampling changes.

The standalone tests qualify complete provider layouts. Run this file with
--consumer-root and --native-library to additionally exercise057's actual baker.
"""
from functools import lru_cache
import json
import math
from pathlib import Path
import subprocess

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[1]


@lru_cache(maxsize=None)
def ancestry(size, level, index, mode):
    """Positive box weights from one mip texel back to original integer pixels."""
    if level == 0:
        return ((index, 1.),)
    previous = max(1, size // (2 ** (level-1)))
    current = max(1, size // (2 ** level))
    if mode == 'area':
        left, right = index*previous/current, (index+1)*previous/current
    else:
        left, right = (2*index, 2*index+2) if previous > 1 else (0, 1)
    result = {}
    for pixel in range(math.floor(left), math.ceil(right)):
        overlap = max(0., min(right, pixel+1)-max(left, pixel)) / (right-left)
        for source, weight in ancestry(size, level-1, pixel, mode):
            result[source] = result.get(source, 0.) + overlap*weight
    return tuple(sorted(result.items()))


def axis_weights(size, level, uv, mode):
    count = max(1, size // (2 ** level))
    position = uv*count-.5
    lower = math.floor(position)
    fraction = position-lower
    result = {}
    for index, factor in ((lower, 1-fraction), (lower+1, fraction)):
        if factor <= 1e-15:
            continue
        for pixel, weight in ancestry(size, level, min(count-1, max(0, index)), mode):
            result[pixel] = result.get(pixel, 0.) + factor*weight
    return tuple(result), np.array(tuple(result.values()))


def filtered(labels, uv, lod, mode, colors=None):
    """Actual positive color/provenance contributions, including trilinear mixing."""
    height, width = labels.shape
    result = np.zeros(256)  # uint8 labels; avoid rescanning a4K image per probe.
    color = np.zeros(3)
    low = math.floor(lod)
    for level, factor in ((low, 1-(lod-low)), (low+1, lod-low)):
        if factor <= 1e-15:
            continue
        xs, xw = axis_weights(width, level, float(uv[0]), mode)
        ys, yw = axis_weights(height, level, float(uv[1]), mode)
        weights = np.outer(yw, xw)*factor
        samples = labels[np.ix_(ys, xs)]
        result += np.bincount(samples.ravel(), weights=weights.ravel(), minlength=len(result))
        if colors is not None:
            color += (colors[np.ix_(ys, xs)]*weights[..., None]).sum(axis=(0, 1))
    assert abs(result.sum()-1) < 1e-12
    return result, color


def probes(triangle):
    # Exact corners and edge-near points exercise the largest gutter footprint.
    for bary in ((1,0,0),(0,1,0),(0,0,1),(.5,.5,0),(.5,0,.5),(0,.5,.5),
                 (.01,.49,.5),(.49,.01,.5),(.49,.5,.01),(1/3,1/3,1/3)):
        yield np.asarray(bary) @ np.asarray(triangle,dtype=np.float32).astype(float)


@pytest.fixture(scope='module')
def layouts(tmp_path_factory):
    binary = tmp_path_factory.mktemp('mip-layouts')/'fixture'
    subprocess.run(['c++','-O2','-DNDEBUG','-std=c++20','-Wall','-Wextra','-Werror',
        '-I',str(ROOT/'reconstruction/include'),
        str(ROOT/'reconstruction/tests/agent_058_mip_layout_fixture.cpp'),
        str(ROOT/'reconstruction/src/cancellation.cpp'),'-o',str(binary)],check=True)
    return json.loads(subprocess.check_output([str(binary)],text=True))


def test_full_2k_4k_layouts_have_bounded_mip_gutters(layouts):
    limits = {2:1, 3:1, 5:2, 11:3, 16:3}
    for layout in layouts:
        size, padding = layout['edge'], layout['padding']
        labels = np.zeros((size,size),np.uint8)
        assert any(x % 2 or y % 2 or w % 2 or h % 2 for x,y,w,h in layout['groups'])
        for group, (x,y,w,h) in enumerate(layout['groups'],1):
            assert not labels[y:y+h,x:x+w].any()
            labels[y:y+h,x:x+w] = group
        palette=np.array([[0.,0.,0.]]+[[((i*37)%251)/255.,((i*71)%251)/255.,((i*113)%251)/255.] for i in range(1,256)])
        max_lod = limits[padding]
        worst = 0.
        for chart in layout['charts']:
            own = chart['group']+1
            for uv in probes(chart['uv']):
                for lod in (*range(max_lod+1), max_lod-.37):
                    histogram, _ = filtered(labels,uv,lod,'area')
                    foreign = histogram.sum()-histogram[0]-histogram[own]
                    assert foreign < 1e-12, (size,padding,lod,own,uv,foreign)
                    # With uniform sentinel colors, no foreign provenance means
                    # any box/bilinear/trilinear mixture retains the same source.
                    assert histogram[own] > 1-1e-12
                    np.testing.assert_allclose(histogram@palette,palette[own],atol=1e-12,rtol=0)
                histogram, _ = filtered(labels,uv,max_lod+1,'area')
                worst = max(worst,histogram.sum()-histogram[0]-histogram[own])
        assert worst > 1e-5, (size,padding,'over-budget negative control did not bleed')
        print(f'{size} padding={padding} qualifiedMaxLOD={max_lod} nextLevelForeign={worst:.9g}')


def test_oracle_detects_unguarded_chart_and_does_not_hide_alpha_holes():
    labels=np.ones((16,16),np.uint8);labels[:,8:]=2
    uv=(7.5/16,7.5/16)
    base,_=filtered(labels,uv,0,'area');mip,_=filtered(labels,uv,1,'area')
    assert base[2]==0 and mip[2]>0
    labels[7,6]=0
    uv=(6.5/16,7.5/16)
    base,_=filtered(labels,uv,0,'area');mip,_=filtered(labels,uv,1,'area')
    assert base[0]==1 and 0<mip[0]<1  # Mips average coverage; no invented guarantee.
    # A two-pixel gutter is sufficient for the full POT layouts above, but not
    # an arbitrary odd NPOT crop: the next chart begins at9, its content at11.
    labels=np.ones((15,15),np.uint8);labels[:,9:]=2
    for mode in ('area','drop'):
        histogram,_=filtered(labels,(11.5/15,7.5/15),1,mode)
        assert histogram[1]>0 and histogram[2]>0



def qualify_actual_baker(consumer_root, library, output):
    """Integration-only actual C++ bake, independent of the provider padding model."""
    import hashlib
    from PIL import Image
    output.mkdir(parents=True,exist_ok=True)
    binary=output/'actual-bake';prefix=output/'actual'
    subprocess.run(['c++','-O2','-DNDEBUG','-std=c++20','-Wall','-Wextra','-Werror',
        '-I',str(consumer_root/'reconstruction/include'),
        str(ROOT/'reconstruction/tests/agent_058_mip_bake_fixture.cpp'),
        str(library),'-Wl,-rpath,'+str(library.parent),'-o',str(binary)],check=True)
    subprocess.run([str(binary),str(prefix)],check=True)
    info=json.loads(prefix.with_suffix('.json').read_text())
    pixels=np.asarray(Image.open(prefix.with_suffix('.png')))
    labels=np.where(pixels[...,3]==0,0,np.where(pixels[...,0]>pixels[...,1],1,2)).astype(np.uint8)
    assert pixels.shape[:2]==(15,46)
    assert info['layouts'][0]['groups']==info['layouts'][1]['groups']==[[0,0,23,15],[23,0,23,15]]
    assert np.count_nonzero(labels==0)>0
    summary={}
    for mode in ('area','drop'):
        levels={}
        for lod in (0, .37, 1, 1.63, 2):
            foreign=0.;min_alpha=1.
            for face in range(4):
                own=1 if face<2 else 2
                for uv in probes(info['uv'][face*3:face*3+3]):
                    histogram,color=filtered(labels,uv,lod,mode,pixels[...,:3]/255.)
                    foreign=max(foreign,histogram.sum()-histogram[0]-histogram[own])
                    min_alpha=min(min_alpha,1-histogram[0])
                    if lod<=1:
                        expected=np.array([220,20,20] if own==1 else [20,220,20])/255.
                        np.testing.assert_allclose(color,expected*(1-histogram[0]),atol=1e-12,rtol=0)
            levels[str(lod)]={'maximumForeignChartWeight':float(foreign),'minimumFilteredAlpha':float(min_alpha)}
            if lod<=1:assert foreign<1e-12,(mode,lod,foreign)
        assert levels['2']['maximumForeignChartWeight']>1e-5
        # An interior missing-photo texel stays unknown at base level; positive
        # neighboring coverage in its mip footprint is explicitly retained.
        hole=next((y,x) for y,x in zip(*np.where(labels==0)) if 6<=y<=8 and 9<=x<=12)
        uv=((hole[1]+.5)/46,(hole[0]+.5)/15)
        base,_=filtered(labels,uv,0,mode);mip,_=filtered(labels,uv,1,mode)
        assert base[0]==1 and 0<mip[0]<1
        summary[mode]={'levels':levels,'interiorHoleBaseAlpha':float(1-base[0]),'interiorHoleMip1Alpha':float(1-mip[0])}
    receipt={'qualifiedMaxLOD':1,'enabledProductionMipLevels':0,'filterDefinitions':['recursive normalized-area box','recursive2x2 drop odd last row/column'],
        'sampling':'bilinear at each level, trilinear between adjacent qualified levels; CLAMP_TO_EDGE',
        'layoutDimensions':{str(l['edge']):l['groups'] for l in info['layouts']},'actualCrop':[46,15],
        'nativeLibrarySha256':hashlib.sha256(library.read_bytes()).hexdigest(),
        'consumerSourceSha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=consumer_root,text=True).strip(),
        'providerSourceSha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'observed':summary,'scope':'synthetic HOST cross-chart color exclusion; finite mask footprints average alpha, no physical/driver/mipmap-enabling claim'}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,indent=2))


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--consumer-root',type=Path,required=True)
    parser.add_argument('--native-library',type=Path,required=True)
    parser.add_argument('--output-dir',type=Path,required=True)
    args=parser.parse_args()
    qualify_actual_baker(args.consumer_root.resolve(),args.native_library.resolve(),args.output_dir.resolve())
