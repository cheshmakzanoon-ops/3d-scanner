package com.realityforge.scanner.export

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LegacyPlyValidationTest {
 private val legacy="comment RealityForge sparse reconstruction; arbitrary scale; x-right y-down z-forward"
 private val typed="comment RealityForge sparse_points; arbitrary scale; x-right y-down z-forward"
 private fun sparse(header:String=typed+"\ncomment representation sparse_points") = "ply\nformat ascii 1.0\n$header\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n1.25 -2.5 3 0 128 255\n"
 private fun checkText(text:String,surface:Boolean=false,valid:Boolean=true) {
  val f=File.createTempFile("ply-validation-", ".ply")
  var primary:Throwable?=null
  try {f.writeText(text,Charsets.US_ASCII)
   if(valid) LegacyPlyValidation.validate(f,surface)
   else assertThrows(IllegalArgumentException::class.java){LegacyPlyValidation.validate(f,surface)}
  } catch(failure:Throwable) {primary=failure;throw failure}
  finally {try {check(f.delete())} catch(cleanup:Throwable) {if(primary!=null) primary.addSuppressed(cleanup) else throw cleanup}}
 }
 @Test fun acceptsExactTypedSparseAndExistingLegacySparse() {
  checkText(sparse());checkText(sparse(legacy));checkText(sparse().replace("\n","\r\n"))
 }
 @Test fun rejectsWrongOrAmbiguousTypedRepresentation() {
  for(header in listOf(typed,typed+"\ncomment representation dense_points",typed+"\ncomment representation sparse_points\ncomment extra",typed.replace("sparse_points","dense_points")+"\ncomment representation dense_points",typed+" suffix\ncomment representation sparse_points","comment representation sparse_points\n"+typed)) checkText(sparse(header),valid=false)
  checkText(sparse(),surface=true,valid=false)
 }
 @Test fun retainsBoundedStrictBodyAndPropertyValidation() {
  val base=sparse()
  for(text in listOf(base.replace("element vertex 1","element vertex 0"),base.replace("element vertex 1","element vertex 2000001"),base.replace("property float x","property double x"),base.replace("1.25 -2.5 3","NaN -2.5 3"),base.replace("1.25 -2.5 3","1.25 Infinity 3"),base.replace("0 128 255","0 128 256"),base.replace("0 128 255","0 128 1.5"),base+"extra\n",base.dropLast(1),base.replace("end_header","comment "+"a".repeat(1024)+"\nend_header"))) checkText(text,valid=false)
 }
 @Test fun retainsSurfaceGrammarAndRejectsMeshAsSparse() {
  val mesh="ply\nformat ascii 1.0\ncomment RealityForge observed open surface; arbitrary scale\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nproperty float nx\nproperty float ny\nproperty float nz\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 1 0 0 1 0 128 255\n1 0 1 0 0 1 0 128 255\n0 1 1 0 0 1 0 128 255\n3 0 1 2\n"
  checkText(mesh,surface=true);checkText(mesh,valid=false)
  checkText(mesh.replace("3 0 1 2","3 0 0 2"),surface=true,valid=false)
 }
}
