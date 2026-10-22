"""Actual host JVM/JNI boundary execution plus injected allocation failure."""
from tools.native_process import native_compile_env
from pathlib import Path
from tools.native_test_build import native_link_args, native_run_env
import os
import shutil
import subprocess
import pytest

JAVA = r'''
package com.realityforge.scanner.engine;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
public class NativeCore {
 static {System.load(System.getProperty("native.library"));}
 public static native long beginReconstruction();
 public static native boolean cancelReconstruction(long t);
 public static native boolean finishReconstruction(long t);
 public static native long[] pollNativeProgress(long t);
 public static native String reconstruct(double[] rows,double threshold);
 public static native String reconstructCancellable(double[] rows,double threshold,long t);
 public static native String bundleAdjustProblemCancellable(double[] cameras,double[] seeds,double[] pixels,double[] models,String[] groups,String[] photos,String[] sourceTracks,boolean measured,boolean refine,double threshold,int iterations,long token);
 public static native void exportPointsTyped(double[] rows,String path,boolean glb,boolean dense);
 public static native String reconstructDenseRefinedCancellable(byte[] rgba,int w,int h,double[] cameras,double near,double far,int planes,int stride,double[] priors,byte[] coarse,int cw,int ch,double[] coarseCameras,String identity,long token);
 public static native String reconstructTexturedSurfaceCompactCheckpointed(byte[] rgba,int w,int h,byte[] photos,int pw,int ph,double[] cameras,double near,double far,int planes,int stride,String meshGlb,String meshPly,String photoGlb,String png,long token,String cache,String identity);
 public static native String reconstructTexturedSurfaceRefinedCheckpointed(byte[] rgba,int w,int h,byte[] photos,int pw,int ph,double[] cameras,double near,double far,int planes,int stride,String meshGlb,String meshPly,String photoGlb,String png,double[] priors,byte[] coarse,int cw,int ch,double[] coarseCameras,String identity,int[] adjacency,boolean compact,long token,String cache,String checkpointIdentity);
 static void check(boolean c){if(!c)throw new AssertionError("JNI lease regression");}
 public static void main(String[] args)throws Exception {
  for(int i=0;i<24;++i){long t=beginReconstruction();long[] p=pollNativeProgress(t);check(p.length==7&&p[0]==1&&p[5]==-1);check(cancelReconstruction(t));
   try{reconstructCancellable(new double[140],.002,t);throw new AssertionError("Cancellation returned JSON");}catch(CancellationException expected){}
   check(finishReconstruction(t));check(!cancelReconstruction(t));
   try{pollNativeProgress(t);throw new AssertionError("Stale progress accepted");}catch(IllegalArgumentException expected){}
  }
  double[] bad=new double[140];bad[0]=Double.NaN;try{reconstruct(bad,.002);throw new AssertionError("Nonfinite accepted");}catch(IllegalArgumentException expected){}
  try{reconstruct(null,.002);throw new AssertionError("Null accepted");}catch(IllegalArgumentException expected){}
  long first=beginReconstruction(),second=beginReconstruction();
  double[] rows=new double[20000*7];java.util.Random random=new java.util.Random(7);
  for(int i=0;i<20000;++i){double x=random.nextDouble()*2-1,y=random.nextDouble()*2-1,z=3+random.nextDouble()*5;rows[i*7]=x/z;rows[i*7+1]=y/z;rows[i*7+2]=(x-1)/z;rows[i*7+3]=y/z;rows[i*7+4]=100;rows[i*7+5]=140;rows[i*7+6]=180;}
  AtomicReference<Throwable> unexpected=new AtomicReference<>();
  Thread worker=new Thread(()->{try{reconstructCancellable(rows,.002,first);}catch(CancellationException expected){}catch(Throwable e){unexpected.set(e);}});
  worker.start();long deadline=System.nanoTime()+5_000_000_000L;while(pollNativeProgress(first)[6]==0&&worker.isAlive()&&System.nanoTime()<deadline)Thread.yield();
  boolean busy=false;try{reconstructCancellable(rows,.002,first);}catch(IllegalStateException expected){busy=true;}
  check(busy);check(finishReconstruction(first));worker.join(30000);check(!worker.isAlive()&&unexpected.get()==null);
  check(pollNativeProgress(second)[6]==0);check(finishReconstruction(second));
  java.nio.file.Path directory=java.nio.file.Path.of(System.getProperty("probe.directory"));
  int w=64,h=64,cw=32,ch=32;byte[] full=new byte[2*w*h*4],coarse=new byte[2*cw*ch*4];
  for(int i=0;i<full.length;++i)full[i]=(byte)(i%4==3?255:128);for(int i=0;i<coarse.length;++i)coarse[i]=(byte)(i%4==3?255:128);
  double[] cameras=new double[32],coarseCameras=new double[32];for(int i=0;i<2;++i){int b=i*16;for(int d:new int[]{0,4,8}){cameras[b+d]=1;coarseCameras[b+d]=1;}cameras[b+9]=coarseCameras[b+9]=-i*.5;cameras[b+12]=cameras[b+13]=84;coarseCameras[b+12]=coarseCameras[b+13]=42;cameras[b+14]=cameras[b+15]=31.5;coarseCameras[b+14]=coarseCameras[b+15]=15.5;}
  java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(full);digest.update(coarse);for(double c:cameras)digest.update(java.nio.ByteBuffer.allocate(8).putDouble(c).array());for(double c:coarseCameras)digest.update(java.nio.ByteBuffer.allocate(8).putDouble(c).array());String identity=java.util.HexFormat.of().formatHex(digest.digest());
  String meshGlb=directory.resolve("mesh.glb").toString(),meshPly=directory.resolve("mesh.ply").toString(),photoGlb=directory.resolve("photo.glb").toString(),png=directory.resolve("atlas.png").toString();
  long refinedToken=beginReconstruction();try{
   String refined=reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],coarse,cw,ch,coarseCameras,identity,new int[]{1,1,1,0},true,refinedToken,directory.resolve("refined-depth").toString(),identity);
   check(refined.contains("\"dense\":")&&refined.contains("\"coarseDepthMaps\":2")&&refined.contains("\"pointCount\":0")&&refined.contains("\"meshError\":")&&refined.contains("\"sourceViewIndices\":[[1],[0]]"));
   check(!java.nio.file.Files.exists(java.nio.file.Path.of(photoGlb)));check(pollNativeProgress(refinedToken)[6]>0);
   String direct=reconstructDenseRefinedCancellable(full,w,h,cameras,3,7,24,3,new double[0],coarse,cw,ch,coarseCameras,identity,refinedToken);check(direct.contains("\"coarseDepthMaps\":2")&&direct.contains("\"pointCount\":0"));
   String compact=reconstructTexturedSurfaceCompactCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,refinedToken,directory.resolve("compact-depth").toString(),identity);check(compact.contains("\"dense\":")&&compact.contains("\"meshError\":"));
   try{reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],coarse,cw,ch,coarseCameras,identity,new int[]{1,0,1,0},false,refinedToken,directory.resolve("bad-depth").toString(),identity);throw new AssertionError("Self source accepted");}catch(IllegalArgumentException expected){}
   try{reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],new byte[4],cw,ch,coarseCameras,identity,new int[]{1,1,1,0},false,refinedToken,directory.resolve("bad-coarse").toString(),identity);throw new AssertionError("Truncated coarse buffers accepted");}catch(IllegalArgumentException expected){}
   String planned=reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],new byte[0],0,0,new double[0],identity,new int[]{0,1,0},false,refinedToken,directory.resolve("plan-only").toString(),identity);check(planned.contains("\"coarseDepthMaps\":0")&&planned.contains("\"sourceViewIndices\":[[],[0]]"));
   try{reconstructDenseRefinedCancellable(full,w,h,cameras,3,7,24,3,new double[0],coarse,cw,ch,coarseCameras,"bad",refinedToken);throw new AssertionError("Malformed identity accepted");}catch(IllegalArgumentException expected){}
   check(cancelReconstruction(refinedToken));try{reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],coarse,cw,ch,coarseCameras,identity,new int[]{1,1,1,0},false,refinedToken,directory.resolve("cancel-depth").toString(),identity);throw new AssertionError("Refined cancellation ignored");}catch(CancellationException expected){}
  }finally{check(finishReconstruction(refinedToken));}
  int bundleViews=4,bundleCount=24;double[] bundleCameras=new double[bundleViews*12],bundleSeeds=new double[bundleCount*7],bundlePixels=new double[bundleViews*bundleCount*7],models=new double[bundleViews*11];String[] groups=new String[bundleViews],photos=new String[bundleViews],sourceIds=new String[40];
  for(int i=0;i<sourceIds.length;++i)sourceIds[i]=String.format("%064x",i+1);
  for(int c=0;c<bundleViews;++c){int b=c*12;bundleCameras[b]=bundleCameras[b+4]=bundleCameras[b+8]=1;bundleCameras[b+9]=-c*.3;groups[c]="measured-lens-crop";photos[c]="photo-"+c;int m=c*11;models[m]=640;models[m+1]=480;models[m+2]=models[m+3]=500;models[m+4]=319.5;models[m+5]=239.5;}
  for(int t=0;t<bundleCount;++t){int b=t*7;double x=(t%6-2.5)*.18,y=(t/6-1.5)*.16,z=3.5+(t%7)*.13;bundleSeeds[b]=t+8;bundleSeeds[b+1]=x;bundleSeeds[b+2]=y;bundleSeeds[b+3]=z;bundleSeeds[b+4]=128;bundleSeeds[b+5]=140;bundleSeeds[b+6]=180;
   for(int c=0;c<bundleViews;++c){int o=(t*bundleViews+c)*7;bundlePixels[o]=t+8;bundlePixels[o+1]=c;bundlePixels[o+2]=500*(x-c*.3)/z+319.5;bundlePixels[o+3]=500*y/z+239.5;bundlePixels[o+4]=128;bundlePixels[o+5]=140;bundlePixels[o+6]=180;}}
  long bundleToken=beginReconstruction();try{String bundle=bundleAdjustProblemCancellable(bundleCameras,bundleSeeds,bundlePixels,models,groups,photos,sourceIds,true,false,.003,1,bundleToken);
   check(bundle.contains("\"bundleProblemSchema\":1")&&bundle.contains("\"cameraIntrinsics\":[[640,480,500,500,319.5,239.5")&&bundle.contains("\"stableTrackMapping\":[[8,")&&bundle.contains("\"gaugeMode\":\"pose_and_baseline\""));
   double[] inconsistent=models.clone();inconsistent[11+2]=501;try{bundleAdjustProblemCancellable(bundleCameras,bundleSeeds,bundlePixels,inconsistent,groups,photos,sourceIds,true,false,.003,0,bundleToken);throw new AssertionError("Inconsistent shared intrinsic model accepted");}catch(IllegalArgumentException expected){}
   check(cancelReconstruction(bundleToken));try{bundleAdjustProblemCancellable(bundleCameras,bundleSeeds,bundlePixels,models,groups,photos,sourceIds,true,false,.003,1,bundleToken);throw new AssertionError("Typed bundle cancellation ignored");}catch(CancellationException expected){}
  }finally{check(finishReconstruction(bundleToken));}
  System.out.println("Actual typed pixel/intrinsic/stable-track bundle JNI path and cancellation passed");
  double[] points={0,0,1,128,140,180,1,0,1,140,150,160,0,1,1,100,120,130};
  String densePly=directory.resolve("dense.ply").toString(),denseGlb=directory.resolve("dense.glb").toString();exportPointsTyped(points,densePly,false,true);exportPointsTyped(points,denseGlb,true,true);
  check(java.nio.file.Files.readString(java.nio.file.Path.of(densePly)).contains("comment representation dense_points"));check(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(denseGlb)),java.nio.charset.StandardCharsets.ISO_8859_1).contains("\"representation\":\"dense_points\""));
  System.out.println("Actual refined/coarse/source-plan/compact JNI scopes and typed dense GLB/PLY labels passed");
  System.out.println("Actual JVM JNI cancellation, busy exclusion, finish lease, pending input and progress schema passed");
 }
}
'''

def test_actual_jvm_native_boundary(tmp_path):
    root=Path(__file__).resolve().parents[1]
    javac=shutil.which('javac')
    if not javac: pytest.skip('JDK compiler unavailable; actual JVM JNI gate NOT_RUN')
    jdk=Path(javac).resolve().parents[1]
    includes=['-I'+str(root/'reconstruction/include'),'-I'+str(jdk/'include'),'-I'+str(jdk/'include/linux')]
    probe=tmp_path/'guard-probe'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wpedantic','-Werror',*includes,str(root/'reconstruction/tests/agent_003_jni_boundary_test.cpp'),'-o',str(probe)],check=True, env=native_compile_env())
    subprocess.run([str(probe)],check=True,timeout=20)
    library=tmp_path/'libagent003_jni.so'
    subprocess.run(['g++','-std=c++20','-fPIC','-shared','-pthread','-Wall','-Wextra','-Wpedantic','-Werror',*includes,str(root/'reconstruction/src/jni.cpp'),*native_link_args(),'-o',str(library)],check=True, env=native_compile_env())
    source=tmp_path/'com/realityforge/scanner/engine/NativeCore.java';source.parent.mkdir(parents=True);source.write_text(JAVA)
    subprocess.run([javac,'-d',str(tmp_path),str(source)],check=True)
    subprocess.run([str(jdk/'bin/java'),'-Dnative.library='+str(library),'-Dprobe.directory='+str(tmp_path),'-cp',str(tmp_path),'com.realityforge.scanner.engine.NativeCore'],env=native_run_env(),check=True,timeout=60)
