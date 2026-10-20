"""Actual host JVM/JNI boundary execution plus injected allocation failure."""
from pathlib import Path
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
    native=Path(os.environ.get('RF_NATIVE_LIBRARY',root/'build/host/librealityforge.so'))
    assert native.is_file(), 'Build actual native library before JNI validation'
    probe=tmp_path/'guard-probe'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Wpedantic','-Werror',*includes,str(root/'reconstruction/tests/agent_003_jni_boundary_test.cpp'),'-o',str(probe)],check=True)
    subprocess.run([str(probe)],check=True,timeout=20)
    library=tmp_path/'libagent003_jni.so'
    subprocess.run(['g++','-std=c++20','-fPIC','-shared','-pthread','-Wall','-Wextra','-Wpedantic','-Werror',*includes,str(root/'reconstruction/src/jni.cpp'),'-L'+str(native.parent),'-Wl,-rpath,'+str(native.parent),'-lrealityforge','-o',str(library)],check=True)
    source=tmp_path/'com/realityforge/scanner/engine/NativeCore.java';source.parent.mkdir(parents=True);source.write_text(JAVA)
    subprocess.run([javac,'-d',str(tmp_path),str(source)],check=True)
    subprocess.run([str(jdk/'bin/java'),'-Dnative.library='+str(library),'-cp',str(tmp_path),'com.realityforge.scanner.engine.NativeCore'],check=True,timeout=60)
