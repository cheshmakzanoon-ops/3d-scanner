from pathlib import Path
p=Path('tests/test_agent_003_jni_boundary.py');s=p.read_text();s=s.replace(' public static native String reconstructCancellable(double[] rows,double threshold,long t);', ''' public static native String reconstructCancellable(double[] rows,double threshold,long t);
 public static native void exportPointsTyped(double[] rows,String path,boolean glb,boolean dense);
 public static native String reconstructDenseRefinedCancellable(byte[] rgba,int w,int h,double[] cameras,double near,double far,int planes,int stride,double[] priors,byte[] coarse,int cw,int ch,double[] coarseCameras,String identity,long token);
 public static native String reconstructTexturedSurfaceCompactCheckpointed(byte[] rgba,int w,int h,byte[] photos,int pw,int ph,double[] cameras,double near,double far,int planes,int stride,String meshGlb,String meshPly,String photoGlb,String png,long token,String cache,String identity);
 public static native String reconstructTexturedSurfaceRefinedCheckpointed(byte[] rgba,int w,int h,byte[] photos,int pw,int ph,double[] cameras,double near,double far,int planes,int stride,String meshGlb,String meshPly,String photoGlb,String png,double[] priors,byte[] coarse,int cw,int ch,double[] coarseCameras,String identity,int[] adjacency,boolean compact,long token,String cache,String checkpointIdentity);''')
s=s.replace('  System.out.println("Actual JVM JNI cancellation', '''  java.nio.file.Path directory=java.nio.file.Path.of(System.getProperty("probe.directory"));
  int w=64,h=64,cw=32,ch=32;byte[] full=new byte[2*w*h*4],coarse=new byte[2*cw*ch*4];
  for(int i=0;i<full.length;++i)full[i]=(byte)(i%4==3?255:128);for(int i=0;i<coarse.length;++i)coarse[i]=(byte)(i%4==3?255:128);
  double[] cameras=new double[32],coarseCameras=new double[32];for(int i=0;i<2;++i){int b=i*16;for(int d:new int[]{0,4,8}){cameras[b+d]=1;coarseCameras[b+d]=1;}cameras[b+9]=coarseCameras[b+9]=-i*.5;cameras[b+12]=cameras[b+13]=84;coarseCameras[b+12]=coarseCameras[b+13]=42;cameras[b+14]=cameras[b+15]=31.5;coarseCameras[b+14]=coarseCameras[b+15]=15.5;}
  java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(full);digest.update(coarse);for(double c:cameras)digest.update(java.nio.ByteBuffer.allocate(8).putDouble(c).array());for(double c:coarseCameras)digest.update(java.nio.ByteBuffer.allocate(8).putDouble(c).array());String identity=java.util.HexFormat.of().formatHex(digest.digest());
  String meshGlb=directory.resolve("mesh.glb").toString(),meshPly=directory.resolve("mesh.ply").toString(),photoGlb=directory.resolve("photo.glb").toString(),png=directory.resolve("atlas.png").toString();
  long refinedToken=beginReconstruction();try{
   String refined=reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],coarse,cw,ch,coarseCameras,identity,new int[]{1,1,1,0},true,refinedToken,directory.resolve("refined-depth").toString(),identity);
   check(refined.contains("\\\"dense\\\":")&&refined.contains("\\\"coarseDepthMaps\\\":2")&&refined.contains("\\\"pointCount\\\":0")&&refined.contains("\\\"meshError\\\":")&&refined.contains("\\\"sourceViewIndices\\\":[[1],[0]]"));
   check(!java.nio.file.Files.exists(java.nio.file.Path.of(photoGlb)));check(pollNativeProgress(refinedToken)[6]>0);
   String direct=reconstructDenseRefinedCancellable(full,w,h,cameras,3,7,24,3,new double[0],coarse,cw,ch,coarseCameras,identity,refinedToken);check(direct.contains("\\\"coarseDepthMaps\\\":2")&&direct.contains("\\\"pointCount\\\":0"));
   String compact=reconstructTexturedSurfaceCompactCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,refinedToken,directory.resolve("compact-depth").toString(),identity);check(compact.contains("\\\"dense\\\":")&&compact.contains("\\\"meshError\\\":"));
   try{reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],coarse,cw,ch,coarseCameras,identity,new int[]{1,0,1,0},false,refinedToken,directory.resolve("bad-depth").toString(),identity);throw new AssertionError("Self source accepted");}catch(IllegalArgumentException expected){}
   try{reconstructDenseRefinedCancellable(full,w,h,cameras,3,7,24,3,new double[0],coarse,cw,ch,coarseCameras,"bad",refinedToken);throw new AssertionError("Malformed identity accepted");}catch(IllegalArgumentException expected){}
   check(cancelReconstruction(refinedToken));try{reconstructTexturedSurfaceRefinedCheckpointed(full,w,h,full,w,h,cameras,3,7,24,3,meshGlb,meshPly,photoGlb,png,new double[0],coarse,cw,ch,coarseCameras,identity,new int[]{1,1,1,0},false,refinedToken,directory.resolve("cancel-depth").toString(),identity);throw new AssertionError("Refined cancellation ignored");}catch(CancellationException expected){}
  }finally{check(finishReconstruction(refinedToken));}
  double[] points={0,0,1,128,140,180,1,0,1,140,150,160,0,1,1,100,120,130};
  String densePly=directory.resolve("dense.ply").toString(),denseGlb=directory.resolve("dense.glb").toString();exportPointsTyped(points,densePly,false,true);exportPointsTyped(points,denseGlb,true,true);
  check(java.nio.file.Files.readString(java.nio.file.Path.of(densePly)).contains("comment representation dense_points"));check(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(denseGlb)),java.nio.charset.StandardCharsets.ISO_8859_1).contains("\\\"representation\\\":\\\"dense_points\\\""));
  System.out.println("Actual refined/coarse/source-plan/compact JNI scopes and typed dense GLB/PLY labels passed");
  System.out.println("Actual JVM JNI cancellation''')
s=s.replace("'-Dnative.library='+str(library),'-cp'", "'-Dnative.library='+str(library),'-Dprobe.directory='+str(tmp_path),'-cp'")
p.write_text(s)
