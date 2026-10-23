import java.nio.file.*;
import java.util.*;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.Imgproc;
/** Real JNI smoke with original AAR classes; not photographic reconstruction evidence. */
public final class HostOpenCvSmoke {
 public static void main(String[] a) throws Exception {
  if(a.length!=1)throw new AssertionError("one exact native input");
  Path lib=Path.of(a[0]).toRealPath();System.load(lib.toString());
  if(!Core.VERSION.equals("4.12.0")||!Core.getVersionString().equals("4.12.0"))throw new AssertionError("version");
  Core.setNumThreads(1);
  Mat rgba=new Mat(2,2,CvType.CV_8UC4),gray=new Mat(),image=new Mat(96,96,CvType.CV_8UC1),des=new Mat(),mask=new Mat();
  MatOfKeyPoint keys=new MatOfKeyPoint();SIFT sift=SIFT.create();BFMatcher matcher=BFMatcher.create(Core.NORM_L2,false);
  List<MatOfDMatch> matches=new ArrayList<>();
  try {
   byte[] bytes={(byte)255,0,0,(byte)255,0,(byte)255,0,(byte)255,0,0,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255};
   if(rgba.put(0,0,bytes)!=16)throw new AssertionError("Mat write");
   byte[] copy=new byte[16];if(rgba.get(0,0,copy)!=16||!Arrays.equals(copy,bytes))throw new AssertionError("Mat roundtrip");
   Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);byte[] g=new byte[4];
   if(gray.get(0,0,g)!=4||!Arrays.equals(g,new byte[]{76,(byte)150,29,(byte)255}))throw new AssertionError("actual RGBA grayscale");
   byte[] texture=new byte[96*96];for(int y=0;y<96;y++)for(int x=0;x<96;x++)texture[y*96+x]=(byte)((((x/12)+(y/12))%2)*255);
   if(image.put(0,0,texture)!=texture.length)throw new AssertionError("image write");
   sift.detectAndCompute(image,mask,keys,des);
   if(des.rows()<2||des.cols()!=128||des.type()!=CvType.CV_32F)throw new AssertionError("SIFT native descriptors");
   matcher.knnMatch(des,des,matches,2);
   if(matches.size()!=des.rows()||matches.stream().anyMatch(m->m.toArray().length!=2||m.toArray()[0].distance!=0))throw new AssertionError("BF native matching");
   Set<String> maps=new HashSet<>();for(String line:Files.readAllLines(Path.of("/proc/self/maps"))){String[] p=line.split("\\s+",6);if(p.length==6&&p[5].startsWith("/")&&p[5].contains("libopencv"))maps.add(Path.of(p[5]).toRealPath().toString());}
   if(!maps.equals(Set.of(lib.toString())))throw new AssertionError("actual native mappings");
   System.out.println("OPENCV_REAL_SMOKE_PASS version=4.12.0 descriptors="+des.rows()+" gray=76,150,29,255");
  } finally {for(MatOfDMatch m:matches)m.release();matcher.clear();sift.clear();keys.release();mask.release();des.release();image.release();gray.release();rgba.release();}
 }
}
