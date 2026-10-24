"""Actual production exporter JNI and serializers; synthetic geometry, no scan qualification."""
import hashlib,json,math,os,re,shutil,struct,subprocess,sys
from pathlib import Path
import xml.etree.ElementTree as ET
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.native_process import native_compile_env
from tools.eight_view_host import capability as c
from tools.eight_view_host.runtime import Failure

JAVA=r'''
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import com.realityforge.scanner.export.NativeAssetExports;
public final class ExportRoundtrip {
 interface Action {void run() throws Exception;}
 static int refused=0;
 static void reject(String message,Action action)throws Exception {
  try{action.run();throw new AssertionError("accepted "+message);}catch(IllegalArgumentException e){if(!e.getMessage().contains(message))throw e;refused++;}
 }
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),source=root.resolve("mesh.ply");
  String hash=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
  NativeAssetExports api=NativeAssetExports.INSTANCE;
  int[] callbacks={0};Runnable checkpoint=()->callbacks[0]++;
  api.writeObserved(source.toString(),root.resolve("mesh.obj").toString(),0,hash,checkpoint);
  api.writeObserved(source.toString(),root.resolve("mesh.stl").toString(),1,hash,checkpoint);
  if(callbacks[0]<10)throw new AssertionError("callbacks not executed");
  reject("EXPORT_TARGET_EXISTS_OR_UNSAFE",()->api.writeObserved(source.toString(),root.resolve("mesh.obj").toString(),0,hash,checkpoint));
  reject("SOURCE_OVERWRITE",()->api.writeObserved(source.toString(),source.toString(),0,hash,checkpoint));
  reject("SOURCE_UNAVAILABLE",()->api.writeObserved(root.resolve("missing").toString(),root.resolve("missing.obj").toString(),0,hash,checkpoint));
  reject("FORMAT_UNAVAILABLE",()->api.writeObserved(source.toString(),root.resolve("unsupported.bin").toString(),2,hash,checkpoint));
  reject("SOURCE_IDENTITY",()->api.writeObserved(source.toString(),root.resolve("identity.obj").toString(),0,"bad",checkpoint));
  Path invalid=root.resolve("invalid.ply");Files.writeString(invalid,Files.readString(source).replace("3 0 1 2","3 0 1 99"));
  reject("PLY_FACE",()->api.writeObserved(invalid.toString(),root.resolve("invalid.obj").toString(),0,hash,checkpoint));
  Path glb=root.resolve("not-ply.glb");Files.write(glb,new byte[]{'g','l','T','F',2,0,0,0});
  reject("PLY_",()->api.writeObserved(glb.toString(),root.resolve("glb.obj").toString(),0,hash,checkpoint));
  Path alias=root.resolve("alias.obj");Files.createSymbolicLink(alias,source);
  reject("EXPORT_TARGET_EXISTS_OR_UNSAFE",()->api.writeObserved(source.toString(),alias.toString(),0,hash,checkpoint));
  CancellationException cancellation=new CancellationException("owned-cancel");
  try{api.writeObserved(source.toString(),root.resolve("cancel-before.obj").toString(),0,hash,()->{throw cancellation;});throw new AssertionError();}
  catch(CancellationException e){if(e!=cancellation)throw new AssertionError("callback identity replaced");refused++;}
  if(Files.exists(root.resolve("cancel-before.obj")))throw new AssertionError("early cancellation wrote output");
  int[] ticks={0};
  try{api.writeObserved(source.toString(),root.resolve("cancel-mid.obj").toString(),0,hash,()->{if(++ticks[0]==4)throw cancellation;});throw new AssertionError();}
  catch(CancellationException e){if(e!=cancellation||ticks[0]!=4)throw new AssertionError();refused++;}
  IllegalStateException failure=new IllegalStateException("owned-callback-error");
  try{api.writeObserved(source.toString(),root.resolve("error.obj").toString(),0,hash,()->{throw failure;});throw new AssertionError();}
  catch(IllegalStateException e){if(e!=failure)throw new AssertionError();refused++;}
  if(!hash.equals(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))))throw new AssertionError("source changed");
  System.out.println("PASS callbacks="+callbacks[0]+" refusals="+refused);
 }
}
'''
PLY='''ply
format ascii 1.0
comment RealityForge observed open surface; arbitrary scale; no UV textures
element vertex 3
property float x
property float y
property float z
property float nx
property float ny
property float nz
property uchar red
property uchar green
property uchar blue
element face 1
property list uchar int vertex_indices
end_header
0 0 0 0 0 1 255 0 0
1 0 0 0 0 1 0 255 0
0 1 0 0 0 1 0 0 255
3 0 1 2
'''


def test_android_jni_translation_units_exact():
    source=(ROOT/'CMakeLists.txt').read_text()
    android=source.split('if(ANDROID)',1)[1].split('else()',1)[0]
    assert re.findall(r'reconstruction/src/\w+\.cpp',android)==['reconstruction/src/jni.cpp','reconstruction/src/asset_exports_jni.cpp']
    assert set(re.findall(r'reconstruction/src/\w*jni\.cpp',source))=={'reconstruction/src/jni.cpp','reconstruction/src/asset_exports_jni.cpp'}


def test_actual_production_export_jni(tmp_path):
    jdk=Path(os.environ.get('RF_PHASE_JAVA_HOME','/workspace/shared/phase-jdk-tools/jdk-17.0.21+9'))
    lib=Path(os.environ.get('RF_KOTLIN_TEST_LIB','/workspace/shared/export-stream-qa/lib'))
    cpp=shutil.which('g++')
    if not cpp or not (jdk/'bin/javac').is_file() or not (lib/'kotlin-compiler-embeddable-2.1.21.jar').is_file():
        if os.environ.get('RF_REQUIRE_EXPORT_JNI')=='1':pytest.fail('Required real JNI/C++/Kotlin tools unavailable')
        pytest.skip('Real exporter JNI fixture prerequisites unavailable')
    def execute(argv,compile=False):
        result=subprocess.run(list(map(str,argv)),cwd=tmp_path,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90,env=native_compile_env() if compile else None)
        assert result.returncode==0,result.stdout.decode(errors='replace')[-8000:]
        return result.stdout.decode()
    metadata=ET.parse(ROOT/'gradle/verification-metadata.xml').getroot()
    pins={a.get('name'):h.get('value') for c in metadata.iter() if c.tag.endswith('component') for a in c if a.tag.endswith('artifact') for h in a if h.tag.endswith('sha256')}
    jars=sorted(lib.glob('*.jar'))
    for jar in jars:assert hashlib.sha256(jar.read_bytes()).hexdigest()==pins[jar.name]
    original=(ROOT/'app/src/main/java/com/realityforge/scanner/export/LegacyMeshDerivedExports.kt').read_text()
    # Exact production singleton extraction, not a hand-written native declaration.
    object_source=original[original.index('object NativeAssetExports {'):original.index('\n}\n',original.index('object NativeAssetExports {'))+3]
    kotlin=tmp_path/'NativeAssetExports.kt';kotlin.write_text('package com.realityforge.scanner.export\n'+object_source)
    classes=tmp_path/'classes';stdlib=lib/'kotlin-stdlib-2.1.21.jar'
    execute([jdk/'bin/java','-Xmx512m','-XX:ActiveProcessorCount=2','-cp',os.pathsep.join(map(str,jars)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',stdlib,'-d',classes,kotlin],True)
    harness=tmp_path/'ExportRoundtrip.java';harness.write_text(JAVA)
    execute([jdk/'bin/javac','-cp',os.pathsep.join(map(str,[classes,stdlib])),'-d',classes,harness],True)
    declarations=c.native_declarations(execute([jdk/'bin/javap','-classpath',classes,'-private','-s','com.realityforge.scanner.export.NativeAssetExports'],True))
    # Actual exporter javap descriptor plus an explicitly synthetic unused core declaration.
    contract=tmp_path/'scanner-jni-contract.h';contract.write_text(c.scanner_contract([('fixtureUnused','()V')],declarations))
    assert 'Java_com_realityforge_scanner_export_NativeAssetExports_writeObserved' in contract.read_text()
    sources=[ROOT/'reconstruction/src'/name for name in ('asset_exports_jni.cpp','legacy_asset_export.cpp','asset_model.cpp','obj_export.cpp','stl_export.cpp','cancellation.cpp')]
    shared=tmp_path/'librealityforge.so'
    execute([cpp,'-std=c++20','-O0','-fPIC','-shared','-Wl,-z,defs','-include',contract,'-I',ROOT/'reconstruction/include','-I',jdk/'include','-I',jdk/'include/linux',*sources,'-lz','-o',shared],True)
    (tmp_path/'mesh.ply').write_text(PLY)
    # Actual candidate JNI code retains the parent runtime environment.
    output=execute([jdk/'bin/java','-Xcheck:jni','-Djava.library.path='+str(tmp_path),'-cp',os.pathsep.join(map(str,[classes,stdlib])),'ExportRoundtrip',tmp_path])
    assert 'PASS callbacks=' in output and 'refusals=11' in output and 'WARNING' not in output
    obj=(tmp_path/'mesh.obj').read_text();rows=[line.split() for line in obj.splitlines() if line and not line.startswith('#')]
    vertices=[list(map(float,row[1:4])) for row in rows if row[0]=='v']
    assert len(vertices)==3 and all(math.isfinite(x) for row in vertices for x in row)
    faces=[row[1:] for row in rows if row[0]=='f'];assert len(faces)==1
    assert sorted(int(index.split('/')[0]) for index in faces[0])==[1,2,3]
    stl=(tmp_path/'mesh.stl').read_bytes();assert len(stl)==134 and struct.unpack_from('<I',stl,80)==(1,)
    values=struct.unpack_from('<12fH',stl,84);assert all(math.isfinite(x) for x in values[:-1]) and values[-1]==0
    assert sorted(tuple(values[i:i+3]) for i in (3,6,9))==sorted(map(tuple,vertices))
    destination=os.environ.get('RF_EXPORT_JNI_EVIDENCE_DIR')
    if destination:
        out=Path(destination);out.mkdir(parents=True,exist_ok=True)
        (out/'receipt.json').write_text(json.dumps({'scope':'ACTUAL_EXPORTER_JNI_SYNTHETIC_OBSERVED_PLY_NOT_RECONSTRUCTION','sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'kotlinObjectSha256':hashlib.sha256(object_source.encode()).hexdigest(),'result':output.strip(),'objSha256':hashlib.sha256(obj.encode()).hexdigest(),'stlSha256':hashlib.sha256(stl).hexdigest()},sort_keys=True,indent=2)+'\n')


def test_two_tu_compile_link_contract(tmp_path):
    root=tmp_path/'source';out=tmp_path/'out';java=tmp_path/'jdk';contract=out/'contract.h'
    commands=c.scanner_compile_commands(out,root,'/usr/bin/g++',java,contract)
    assert set(commands)=={'scanner-jni-compile','scanner-export-compile','scanner-shim'}
    for label,source,obj,dep in [('scanner-jni-compile','jni.cpp','jni.o','jni.d'),('scanner-export-compile','asset_exports_jni.cpp','export-jni.o','export-jni.d')]:
        argv=commands[label]
        for flag,value in [('-MF',out/dep),('-MT',out/obj),('-include',contract),('-c',root/'reconstruction/src'/source),('-o',out/obj)]:assert argv[argv.index(flag)+1]==str(value)
        assert argv.count('-c')==1 and '-MD' in argv and '-fPIC' in argv
    link=commands['scanner-shim'];assert link.count(str(out/'jni.o'))==link.count(str(out/'export-jni.o'))==1
    assert '-c' not in link and '-Wl,-z,defs' in link and '-Wl,-rpath,'+str(out/'scanner') in link


def test_two_dependency_closures(tmp_path):
    from types import SimpleNamespace
    source=tmp_path/'jni.cpp';export=tmp_path/'asset_exports_jni.cpp';header=tmp_path/'contract.h'
    for file in (source,export,header):file.write_text(file.name)
    dep=tmp_path/'jni.d';other=tmp_path/'export-jni.d'
    dep.write_text(f'{tmp_path}/jni.o: {source} \\\n {header}\n')
    other.write_text(f'{tmp_path}/export-jni.o: {export} {header}\n')
    assert c.parse_scanner_depfile(dep,tmp_path/'jni.o')=={str(source),str(header)}
    run=SimpleNamespace(env={},remaining=lambda *a,**k:60,command=lambda *a,**k:(b'',None))
    result=c.consumed_inputs(run,'ninja',tmp_path,'scanner',[(dep,tmp_path/'jni.o'),(other,tmp_path/'export-jni.o')])
    assert set(result)==set(map(str,(source,export,header)))
    assert result[str(export)]==hashlib.sha256(export.read_bytes()).hexdigest()


@pytest.mark.parametrize('mode',['target','multi-target','empty','relative','missing','directory','extra-colon','symlink-dep','oversize','drift'],ids=str)
def test_dependency_refusals(tmp_path,monkeypatch,mode):
    header=tmp_path/'source.h';header.write_text('header');obj=tmp_path/'export-jni.o';dep=tmp_path/'export-jni.d'
    dep.write_text(f'{obj}: {header}\n')
    if mode=='target':dep.write_text(f'{tmp_path}/wrong.o: {header}\n')
    elif mode=='multi-target':dep.write_text(f'{obj} other: {header}\n')
    elif mode=='empty':dep.write_text(f'{obj}:\n')
    elif mode=='relative':dep.write_text(f'{obj}: relative.h\n')
    elif mode=='missing':header.unlink()
    elif mode=='directory':header.unlink();header.mkdir()
    elif mode=='extra-colon':dep.write_text(f'{obj}: {header}: bad\n')
    elif mode=='symlink-dep':other=tmp_path/'real.d';dep.rename(other);dep.symlink_to(other)
    elif mode=='oversize':dep.write_bytes(b'x'*(4*1024**2+1))
    elif mode=='drift':
        original=os.read;changed=False
        def raced(fd,size):
            nonlocal changed
            block=original(fd,size)
            if not changed:
                changed=True;dep.write_text(dep.read_text()+' ')
            return block
        monkeypatch.setattr(os,'read',raced)
    with pytest.raises((Failure,FileNotFoundError)):c.parse_scanner_depfile(dep,obj)


def test_owned_output_drift(tmp_path):
    from types import SimpleNamespace
    names=('jni.o','jni.d','export-jni.o','export-jni.d')
    for name in names:(tmp_path/name).write_bytes(name.encode())
    run=SimpleNamespace(remaining=lambda *a,**k:60,scanner_outputs={name:c.scanner_output_identity(tmp_path/name) for name in names})
    c.verify_scanner_outputs(run,tmp_path)
    (tmp_path/'export-jni.d').write_bytes(b'drift')
    with pytest.raises(Failure):c.verify_scanner_outputs(run,tmp_path)


def test_exporter_descriptor_not_optional_when_requested():
    expected=[('writeObserved','(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/Runnable;)V')]
    assert 'NativeAssetExports_writeObserved' in c.scanner_contract([('version','()I')],expected)
    for bad in ([],expected*2,[('writeObserved','()V')],[('other',expected[0][1])]):
        with pytest.raises(Failure):c.scanner_contract([('version','()I')],bad)
