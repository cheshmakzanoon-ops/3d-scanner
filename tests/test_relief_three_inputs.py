"""Three-JPEG observation-only staging and separate sparse selector contracts."""
import importlib.util
import os
from pathlib import Path
import shutil
import pytest
ROOT=Path(__file__).resolve().parents[1]
PACKAGE=ROOT/'docs/development/experiments/eight-view-sparse-runtime-successor'
spec=importlib.util.spec_from_file_location('relief_three_inputs',PACKAGE/'relief_inputs.py')
inputs=importlib.util.module_from_spec(spec);spec.loader.exec_module(inputs)
SOURCE=ROOT/'benchmarks/fixtures/relief'


def test_stage_only_opens_three_literal_source_images(tmp_path,monkeypatch):
 source=tmp_path/'source';source.mkdir()
 for name in inputs.HASHES:shutil.copyfile(SOURCE/name,source/name)
 # Source siblings exist but neither enumeration nor payload access is authorized by staging.
 (source/'calibration.json').write_text('forbidden calibration payload')
 (source/'evaluation-only').mkdir();(source/'evaluation-only/secret').write_text('forbidden truth payload')
 opened=[];real_open=os.open;real_iterdir=Path.iterdir
 def tracked(path,*args,**kwargs):
  p=Path(path)
  if p.is_relative_to(source):
   assert p.parent==source and p.name in inputs.HASHES
   opened.append(p.name)
  return real_open(path,*args,**kwargs)
 def only_destination(path):
  assert not path.is_relative_to(source),'source enumeration forbidden'
  return real_iterdir(path)
 monkeypatch.setattr(os,'open',tracked);monkeypatch.setattr(Path,'iterdir',only_destination)
 result=inputs.stage(source,tmp_path/'staged')
 assert opened==list(inputs.HASHES)
 assert result==inputs.validate(tmp_path/'staged')
 assert result['manifestSha256']=='132d08cce472410d0debef543ca3dd90dcd2dca04712877cf247d7f0102812b8'
 assert result['policySha256']=='4b0ff0f32efaaef501427dcf84a6c75074a943dbe010d1a179297d3a45c2627e'
 assert set(result['images'])=={'images/'+n for n in inputs.HASHES}
 assert 'K' not in inputs.MANIFEST and 'distortion' not in inputs.MANIFEST
 assert all(set(row)=={'id','path','sha256'} for row in inputs.MANIFEST['images'])


@pytest.mark.parametrize('case',['extra-root','extra-image','missing','changed-image','changed-manifest','duplicate-json','traversal-json','image-symlink','images-symlink','root-symlink'],ids=str)
def test_staged_boundary_refuses_changes(tmp_path,case):
 root=tmp_path/'staged';inputs.stage(SOURCE,root);first=root/'images/view-01.jpg'
 if case=='extra-root':(root/'calibration.json').write_text('{}')
 elif case=='extra-image':(root/'images/mask.png').write_bytes(b'x')
 elif case=='missing':first.unlink()
 elif case=='changed-image':first.write_bytes(first.read_bytes()+b'x')
 elif case=='changed-manifest':(root/'observation-inputs.json').write_bytes(inputs.MANIFEST_BYTES+b' ')
 elif case=='duplicate-json':(root/'observation-inputs.json').write_bytes(b'{"schema":1,"schema":1}')
 elif case=='traversal-json':(root/'observation-inputs.json').write_bytes(inputs.MANIFEST_BYTES.replace(b'images/view-01.jpg',b'../view-01.jpg'))
 elif case=='image-symlink':first.unlink();first.symlink_to(SOURCE/'view-01.jpg')
 elif case=='images-symlink':(root/'images').rename(tmp_path/'images');(root/'images').symlink_to(tmp_path/'images',target_is_directory=True)
 elif case=='root-symlink':root.rename(tmp_path/'real');root.symlink_to(tmp_path/'real',target_is_directory=True)
 with pytest.raises((ValueError,OSError)):inputs.validate(root)


@pytest.mark.parametrize('case',['missing','tampered','symlink','source-alias','oversized','existing-output','dangling-output'],ids=str)
def test_source_refusals_do_not_stage_partial_success(tmp_path,case):
 source=tmp_path/'source';source.mkdir();target=tmp_path/'staged'
 for name in inputs.HASHES:shutil.copyfile(SOURCE/name,source/name)
 first=source/'view-01.jpg'
 if case=='missing':first.unlink()
 elif case=='tampered':first.write_bytes(b'bad')
 elif case=='symlink':first.unlink();first.symlink_to(SOURCE/'view-01.jpg')
 elif case=='source-alias':source.rename(tmp_path/'real');source.symlink_to(tmp_path/'real',target_is_directory=True)
 elif case=='oversized':first.write_bytes(b'x'*(inputs.LIMIT+1))
 elif case=='existing-output':target.mkdir()
 elif case=='dangling-output':target.symlink_to(tmp_path/'absent')
 with pytest.raises((ValueError,OSError)):inputs.stage(source,target)
 if case not in ('existing-output','dangling-output'):assert not target.exists()


@pytest.mark.parametrize('data',[b'',b'not-jpeg',b'\xff\xd8',b'\xff\xd8\xff\xc0\xff\xff',b'\xff\xd8\xff\xda\x00\x02'],ids=['empty','signature','truncated','segment-bound','no-frame'])
def test_jpeg_parser_rejects_missing_or_unbounded_frame(data):
 with pytest.raises(ValueError):inputs.jpeg_dimensions(data)


def test_new_sparse_selector_is_strict_separate_and_nominal_only():
 source=(PACKAGE/'ReliefThreeSparsePrefixHostTest.kt').read_text()
 assert 'class ReliefThreeSparsePrefixHostTest' in source
 assert '@Test(timeout = 120_000)' in source and 'withTimeout(90_000)' in source
 assert 'val focal = 32.88' in source and 'assertNull(LensCalibration.readRecord' in source
 assert 'val k = item.getJSONArray("K")' not in source and '200.0 * hypot' not in source
 for text in ['assertEquals(3L, revision)','assertEquals(3L, result.finalRevision)','assertEquals(3, photos.size)',
              'assertEquals(3, registeredIds.size)','assertEquals(selectedIds, registeredIds)',
              'assertEquals("global_typed_ba_admitted12", outcome)','it.pairCount == 3',
              'ALL3_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY','fixture.reopen()',
              'ReconstructionStage.SPARSE, persisted.cursor.acceptedStage','ReconstructionJobState.PAUSED']:
  assert text in source
 assert inputs.digest(inputs.MANIFEST_BYTES) in source
 assert all(digest in source for digest in inputs.HASHES.values())
 assert source.count('assertCache()')==3
 assert 'File(evidence, "feature-events.json")' in source and 'File(evidence, "pair-events.json")' in source
 assert '.put("firstDescriptorRows", item.firstDescriptorRows)' in source


def test_parent_symlink_alias_is_rejected(tmp_path):
 parent=tmp_path/'real';parent.mkdir();source=parent/'source';source.mkdir()
 for name in inputs.HASHES:shutil.copyfile(SOURCE/name,source/name)
 alias=tmp_path/'alias';alias.symlink_to(parent,target_is_directory=True)
 with pytest.raises(ValueError,match='alias'):inputs.stage(alias/'source',tmp_path/'staged')


def test_final_file_substitution_cannot_follow_foreign_target(tmp_path,monkeypatch):
 source=tmp_path/'source';source.mkdir()
 for name in inputs.HASHES:shutil.copyfile(SOURCE/name,source/name)
 victim=source/'view-01.jpg';foreign=tmp_path/'private';foreign.write_bytes(b'not authorized for observation read')
 real_open=os.open;swapped=[]
 def race(path,*args,**kwargs):
  if Path(path)==victim and not swapped:
   victim.unlink();victim.symlink_to(foreign);swapped.append(True)
  return real_open(path,*args,**kwargs)
 monkeypatch.setattr(os,'open',race)
 with pytest.raises(OSError):inputs.stage(source,tmp_path/'staged')
 assert swapped and not (tmp_path/'staged').exists()


def test_fresh_writer_never_overwrites_existing_or_symlink(tmp_path):
 target=tmp_path/'target';target.write_bytes(b'preserve')
 with pytest.raises(FileExistsError):inputs.write_fresh(target,b'replacement')
 alias=tmp_path/'alias';alias.symlink_to(target)
 with pytest.raises(FileExistsError):inputs.write_fresh(alias,b'replacement')
 assert target.read_bytes()==b'preserve'
