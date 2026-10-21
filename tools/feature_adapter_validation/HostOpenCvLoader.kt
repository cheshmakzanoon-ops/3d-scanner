package hostadapter

import java.io.File
import java.security.MessageDigest
import org.opencv.core.*
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc

/** Parent-loader-only native binding. No Android types or fake initialization result. */
object HostOpenCvLoader {
 private var loaded:String?=null
 private fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
 @JvmStatic @Synchronized fun verify() {
  val lib=File(requireNotNull(System.getProperty("rf.adapter.opencvLibrary"))).canonicalFile
  check(sha(lib)==System.getProperty("rf.adapter.opencvLibrarySha256"))
  if(loaded==null){System.load(lib.path);loaded=lib.path}else check(loaded==lib.path)
  val jar=File(requireNotNull(System.getProperty("rf.adapter.opencvClasses"))).canonicalFile
  check(sha(jar)==System.getProperty("rf.adapter.opencvClassesSha256"))
  for(c in listOf(Core::class.java,Mat::class.java,SIFT::class.java,Imgproc::class.java)) {
   check(c.classLoader===javaClass.classLoader)
   check(File(c.protectionDomain.codeSource.location.toURI()).canonicalFile==jar)
  }
  check(Core.VERSION=="4.12.0"&&Core.getVersionString()=="4.12.0")
  Core.setNumThreads(2);check(Core.getNumThreads()<=2)
  checkMappings(lib)
 }
 @JvmStatic fun checkMappings(lib:File=File(requireNotNull(System.getProperty("rf.adapter.opencvLibrary"))).canonicalFile) {
  val paths=File("/proc/self/maps").readLines().mapNotNull{it.split(Regex("\\s+"),limit=6).getOrNull(5)}.filter{it.startsWith("/")}.map{File(it).canonicalPath}.toSet()
  check(paths.filter{File(it).name.startsWith("libopencv")}.toSet()==setOf(lib.path))
  check(paths.none{File(it).name.startsWith("librealityforge")}) // geometry is not part of this experiment
 }
 @JvmStatic fun smoke() {
  verify();val gray=Mat(64,64,CvType.CV_8UC1);val keys=MatOfKeyPoint();val desc=Mat();val mask=Mat();val sift=SIFT.create(64)
  try {
   val bytes=ByteArray(4096){i->if(((i/64)/8+(i%64)/8)%2==0)0 else 255.toByte()}
   check(gray.put(0,0,bytes)==bytes.size);val observed=ByteArray(bytes.size);check(gray.get(0,0,observed)==bytes.size);check(bytes.contentEquals(observed))
   check(sift.descriptorSize()==128);sift.detectAndCompute(gray,mask,keys,desc)
   check(desc.empty()||desc.cols()==128);checkMappings()
  }finally{sift.clear();mask.release();desc.release();keys.release();gray.release()}
 }
}
