package com.realityforge.scanner.export

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Malformed-byte hardening, not a failure of the bounded current native writer. */
class LegacyPlyHardeningTest {
 private fun document(surface:Boolean=false, value:String="0", component:Int=0):String {
  val floats=MutableList(if(surface)6 else 3){"0"};floats[component]=value
  val header=buildString {
   append("ply\nformat ascii 1.0\n")
   if(surface) append("comment RealityForge observed open surface; arbitrary scale; no UV textures\n")
   else append("comment RealityForge sparse_points; arbitrary scale; x-right y-down z-forward\ncomment representation sparse_points\n")
   append("element vertex ${if(surface)3 else 1}\n")
   for(p in listOf("x","y","z")+if(surface)listOf("nx","ny","nz")else emptyList()) append("property float $p\n")
   for(p in listOf("red","green","blue")) append("property uchar $p\n")
   if(surface) append("element face 1\nproperty list uchar int vertex_indices\n")
   append("end_header\n")
  }
  return header+(floats.joinToString(" ")+" 0 128 255\n").repeat(if(surface)3 else 1)+(if(surface)"3 0 1 2\n" else "")
 }
 private fun validate(text:String,surface:Boolean=false,valid:Boolean=false) {
  val file=File.createTempFile("ply-hardening-", ".ply");var primary:Throwable?=null
  try {
   file.writeText(text,Charsets.US_ASCII)
   if(valid) LegacyPlyValidation.validate(file,surface)
   else assertThrows(IllegalArgumentException::class.java){LegacyPlyValidation.validate(file,surface)}
  } catch(failure:Throwable){primary=failure;throw failure}
  finally {try{check(file.delete())}catch(cleanup:Throwable){if(primary!=null)primary.addSuppressed(cleanup)else throw cleanup}}
 }
 @Test fun rejectsBareVertexCounts() {
  validate(document().replace("element vertex 1\n","1\n"))
  validate(document(true).replace("element vertex 3\n","3\n"),true)
 }
 @Test fun rejectsBareFaceCount() {validate(document(true).replace("element face 1\n","1\n"),true)}
 @Test fun rejectsFloatOverflowInEveryCoordinate() {
  for(i in 0..2)for(value in listOf("1e100","-1e100"))validate(document(value=value,component=i))
 }
 @Test fun rejectsFloatOverflowInEveryNormal() {
  for(i in 3..5)for(value in listOf("1e100","-1e100"))validate(document(true,value,i),true)
 }
 @Test fun retainsFiniteFloatBoundariesRoundingAndUnderflow() {
  val maximum=Float.MAX_VALUE.toDouble().toString()
  for(value in listOf(maximum,"-$maximum","0.1","1e-100","-1e-100","-0.0")) {
   for(i in 0..2)validate(document(value=value,component=i),valid=true)
   for(i in 3..5)validate(document(true,value,i),true,true)
  }
 }
}
