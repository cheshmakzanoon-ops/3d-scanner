"""Closed real-input admission controls; tiny synthetic bytes are not a pipeline/quality run."""
import hashlib
import importlib.util
from pathlib import Path
import pytest

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / 'docs/development/experiments/eight-view-sparse-runtime-successor/real_stereo_inputs.py'

def load():
    spec = importlib.util.spec_from_file_location('real_stereo_test_inputs', PATH)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

def jpeg(width=4272, height=2848):
    return b'\xff\xd8\xff\xc0\x00\x11\x08' + height.to_bytes(2,'big') + width.to_bytes(2,'big') + b'\x03' + b'\x01\x11\x00\x02\x11\x00\x03\x11\x00' + b'\xff\xd9'

@pytest.fixture
def controlled(tmp_path, monkeypatch):
    m = load(); files = {'10.JPG': jpeg(), '11.JPG': jpeg() + b'2', 'README.md': b'closed license fixture'}
    monkeypatch.setattr(m, 'SIZES', {k:len(v) for k,v in files.items()})
    monkeypatch.setattr(m, 'SOURCE_HASHES', {k:m.digest(v) for k,v in files.items()})
    monkeypatch.setattr(m, 'LICENSE_README_BYTES', files['README.md'])
    monkeypatch.setitem(m.LICENSE_EVIDENCE, 'real-input-license-readme.md', files['README.md'])
    monkeypatch.setattr(m, 'HASHES', {k:m.digest(files[k]) for k in ('10.JPG','11.JPG')})
    source = tmp_path/'source'; source.mkdir()
    for name,data in files.items(): (source/name).write_bytes(data)
    return m, source, files

def test_literal_pins_and_manifest():
    m=load()
    assert m.SIZES == {'10.JPG':3086546,'11.JPG':3124295,'README.md':1205}
    assert m.HASHES == {'10.JPG':'9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e',
                       '11.JPG':'dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c'}
    assert m.digest(m.LICENSE_README_BYTES) == m.README_SHA256
    assert len(m.LICENSE_README_BYTES) == m.SIZES['README.md']
    assert m.README_SHA256 == '01e8bfdc1aafda11e61fb13fb6b05b3701349f3820a9f04fc0c744700e2363e8'
    assert m.COMMIT == '0f31c229ac289a615b3ae981a5f584856bb24c47'
    assert m.URLS == {name:'https://gitlab.com/api/v4/projects/8724498/repository/files/'+name+'/raw?ref='+m.COMMIT for name in m.SIZES}
    assert m.digest(m.MANIFEST_BYTES) == 'a6ef5caca61f975026d1e4c04fa1e380ba310dad001643f521ac657e4619d901'
    assert m.MANIFEST['synthetic'] is False and m.MANIFEST['nominalFocal35']==88.0
    assert m.MANIFEST['intrinsicsProvenance']=='ESTIMATED_35MM_EQUIVALENT_88_NO_CALIBRATION_PROFILE'
    assert set(m.MANIFEST)=={'schema','dataset','synthetic','width','height','intrinsicsProvenance','nominalFocal35','images'}

def test_original_bytes_stage_and_minimal_evidence(controlled,tmp_path):
    m,source,files=controlled
    observed=m.stage(source,tmp_path/'observations')
    assert observed['images']=={'images/'+k:v for k,v in m.HASHES.items()}
    for name in m.HASHES: assert (tmp_path/'observations/images'/name).read_bytes()==files[name]==(source/name).read_bytes()
    evidence=m.public_evidence(source)
    assert set(evidence)=={'real-input-license-readme.md','real-input-provenance.json','real-input-attribution.txt'}
    assert evidence['real-input-license-readme.md']==files['README.md']
    assert all(not v.startswith(b'\xff\xd8') for v in evidence.values())
    assert b'derivative' in evidence['real-input-attribution.txt']
    assert b'# 10' in evidence['real-input-attribution.txt'] and b'# 11' in evidence['real-input-attribution.txt']
    assert b'https://alansartlog.com' in evidence['real-input-attribution.txt']

@pytest.mark.parametrize('kind',['extra','hash','short','large','symlink','dimensions','license'])
def test_source_admission_rejects(controlled,tmp_path,kind,monkeypatch):
    m,source,files=controlled; target=source/'10.JPG'
    if kind=='extra': (source/'camera-truth.json').write_text('{}')
    if kind=='hash': target.write_bytes(b'x'+files['10.JPG'][1:])
    if kind=='short': target.write_bytes(files['10.JPG'][:-1])
    if kind=='large': target.write_bytes(files['10.JPG']+b'x')
    if kind=='symlink': target.unlink();target.symlink_to(source/'11.JPG')
    if kind=='dimensions':
        data=jpeg(width=800,height=600);target.write_bytes(data)
        monkeypatch.setitem(m.SOURCE_HASHES,'10.JPG',m.digest(data))
    if kind=='license': (source/'README.md').write_bytes(b'x'*len(files['README.md']))
    with pytest.raises(ValueError): m.stage(source,tmp_path/'rejected')
    assert not (tmp_path/'rejected').exists()

@pytest.mark.parametrize('kind',['extra','manifest','image-extra','image-hash','directory-alias'])
def test_staged_inventory_rejects(controlled,tmp_path,kind):
    m,source,files=controlled; dest=tmp_path/'observations';m.stage(source,dest)
    if kind=='extra': (dest/'poses.json').write_text('{}')
    if kind=='manifest': (dest/'observation-inputs.json').write_bytes(b'x'*len(m.MANIFEST_BYTES))
    if kind=='image-extra': (dest/'images/12.JPG').write_bytes(files['10.JPG'])
    if kind=='image-hash': (dest/'images/10.JPG').write_bytes(b'x'*len(files['10.JPG']))
    if kind=='directory-alias':
        alias=tmp_path/'alias';alias.symlink_to(dest,target_is_directory=True);dest=alias
    with pytest.raises(ValueError): m.validate(dest)

class FakeRun:
    def __init__(self, files, response=b'200', *, work):
        self.work=work
        self.env={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':'cheshmakzanoon-ops/3d-scanner'}
        self.files=files;self.response=response;self.calls=[];self.deadlines=[]
    def remaining(self,seconds): self.deadlines.append(seconds);return seconds
    def command(self,label,args,**kwargs):
        self.calls.append((label,args,kwargs));target=Path(args[args.index('--output')+1]);target.write_bytes(self.files[target.name])
        return self.response,0

def test_fetch_fixed_https_no_redirect_retry_or_byte_log(controlled,tmp_path):
    m,_,files=controlled;run=FakeRun(files,work=tmp_path);dest=tmp_path/'licensed-source'
    assert m.fetch(run,dest)==dest
    assert [c[0] for c in run.calls]==['licensed-input-license','licensed-input-10','licensed-input-11']
    for (_,args,kwargs),name in zip(run.calls,('README.md','10.JPG','11.JPG')):
        assert args[:2]==['curl','-q'] and args[-2:]==['--',m.URLS[name]]
        assert args[args.index('--proto')+1]=='=https'
        assert args[args.index('--max-time')+1]=='60'
        assert args[args.index('--max-filesize')+1]==str(m.SIZES[name])
        assert args[args.index('--write-out')+1]=='%{http_code}'
        assert not set(args)&{'--location','-L','--retry','--insecure','-k','--verbose','-v'}
        assert kwargs=={'seconds':60,'limit':4096}
    assert run.deadlines==[60,60,60]

@pytest.mark.parametrize('response',[b'301',b'302',b'404',b'200\n',b''])
def test_fetch_rejects_nonexact_http(controlled,tmp_path,response):
    m,_,files=controlled;run=FakeRun(files,response,work=tmp_path)
    with pytest.raises(ValueError): m.fetch(run,tmp_path/'licensed-source')
    assert len(run.calls)==1

def test_license_mismatch_stops_before_photos(controlled,tmp_path):
    m,_,files=controlled;run=FakeRun({**files,'README.md':b'x'*len(files['README.md'])},work=tmp_path)
    with pytest.raises(ValueError): m.fetch(run,tmp_path/'licensed-source')
    assert len(run.calls)==1

@pytest.mark.parametrize('key,value',[('GITHUB_ACTIONS','false'),('GITHUB_REPOSITORY','other/repo')])
def test_no_nonci_fetch(controlled,tmp_path,key,value):
    m,_,files=controlled;run=FakeRun(files,work=tmp_path);run.env[key]=value
    with pytest.raises(ValueError): m.fetch(run,tmp_path/'licensed-source')
    assert run.calls==[] and not (tmp_path/'licensed-source').exists()

def test_fetch_existing_destination_refused(controlled,tmp_path):
    m,_,files=controlled;run=FakeRun(files,work=tmp_path);dest=tmp_path/'licensed-source';dest.mkdir()
    with pytest.raises(ValueError): m.fetch(run,dest)
    assert run.calls==[]

@pytest.mark.parametrize('data',[b'',b'not jpeg',b'\xff\xd8',b'\xff\xd8\xff\xc2\x00\x02',b'\xff\xd8\xff\xda\x00\x02'])
def test_malformed_jpeg_rejected(data):
    with pytest.raises(ValueError): load().jpeg_dimensions(data)


def test_fetch_refuses_other_destination(controlled,tmp_path):
    m,_,files=controlled;run=FakeRun(files,work=tmp_path)
    with pytest.raises(ValueError): m.fetch(run,tmp_path/'other')
    assert run.calls==[] and not (tmp_path/'other').exists()


def test_private_marker_encodings_and_padding():
    m=load(); values=[('  private-é<&\"\'\x00', 'utf-8'),
                     (b'ASCII\0\0\0private-comment\0\0', 'comment'),
                     ('private-xp'.encode('utf-16-le'), 'utf-16-le'),
                     (b'\0\x01opaque-private', 'opaque'), ('\0 \t\r\n', 'utf-8')]
    markers=m._marker_variants(values)
    assert 'private-é<&\"\''.encode() in markers
    assert b'private-\\u00e9<&\\"\'' in markers
    assert 'private-é&lt;&amp;&quot;&#x27;'.encode() in markers
    assert b'private-comment' in markers and b'private-xp' in markers
    assert b'\0\x01opaque-private' in markers
    assert b'' not in markers
    assert m._marker_variants([('x','utf-8')])==(b'x',)


def test_private_marker_bounds_and_exception_no_values():
    m=load()
    for values in ([('PRIVATE-VALUE-'*2000,'utf-8')],
                   [(f'PRIVATE-VALUE-{n}','utf-8') for n in range(129)],
                   [('PRIVATE-VALUE-'+str(n)+'x'*16360,'utf-8') for n in range(5)]):
        with pytest.raises(ValueError) as exc: m._marker_variants(values)
        assert 'PRIVATE-VALUE' not in str(exc.value)


def test_private_reader_pin_matches_existing_lock():
    import json
    m=load(); rows=json.loads((ROOT/'tools/dependencies/evidence/host-tools.json').read_text())
    entry=next(row for row in rows if row['name']=='pillow')
    assert entry['version']=='13.0.0' and entry['sha256']==m.PILLOW_SHA256 and entry['wheel']==m.PILLOW_WHEEL
    assert m.PILLOW_BYTES==7144542
    assert m.PILLOW_URL=='https://files.pythonhosted.org/packages/e6/99/fdf65cc539e266ab93546c261bdf98411fe165c6e4e13f9dbf597097192b/'+m.PILLOW_WHEEL


def test_private_marker_reader_requires_preparation(controlled):
    m,source,_=controlled
    with pytest.raises(ValueError,match='not prepared'): m.private_markers(source)


@pytest.mark.parametrize('gps',[False,True])
def test_private_marker_selected_tags_only(controlled,monkeypatch,gps):
    m,source,_=controlled
    class Exif(dict):
        def get_ifd(self,key):
            if key==34853:return {1:'GPS-PRIVATE'} if gps else {}
            assert key==34665
            return {42032:'PRIVATE-OWNER',42033:'PRIVATE-BODY',42037:'PRIVATE-LENS',
                    37510:b'ASCII\0\0\0PRIVATE-COMMENT\0',37500:b'PRIVATE-MAKER',37386:55}
    class Image:
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def getexif(self):return Exif({315:'PRIVATE-ARTIST',33432:'PRIVATE-COPYRIGHT',
                                    270:'PRIVATE-DESCRIPTION',40093:'PRIVATE-XP'.encode('utf-16-le'),271:'NOT-A-PRIVATE-TAG'})
    class Reader:
        def open(self,source):return Image()
    monkeypatch.setattr(m,'_verify_privacy_reader',lambda:None)
    monkeypatch.setattr(m,'_PRIVACY_READER',Reader())
    if gps:
        with pytest.raises(ValueError) as exc:m.private_markers(source)
        assert str(exc.value)=='private original metadata inspection failed'
    else:
        markers=m.private_markers(source)
        assert {b'PRIVATE-OWNER',b'PRIVATE-BODY',b'PRIVATE-LENS',b'PRIVATE-COMMENT',b'PRIVATE-MAKER',
                b'PRIVATE-ARTIST',b'PRIVATE-COPYRIGHT',b'PRIVATE-DESCRIPTION',b'PRIVATE-XP'}<=set(markers)
        assert b'NOT-A-PRIVATE-TAG' not in markers


def test_reader_exception_does_not_reveal_value(controlled,monkeypatch):
    m,source,_=controlled
    class Reader:
        def open(self,source): raise ValueError('PRIVATE-NOT-FOR-LOGS')
    monkeypatch.setattr(m,'_verify_privacy_reader',lambda:None)
    monkeypatch.setattr(m,'_PRIVACY_READER',Reader())
    with pytest.raises(ValueError) as exc:m.private_markers(source)
    assert str(exc.value)=='private original metadata inspection failed'
    assert exc.value.__suppress_context__
