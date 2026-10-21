package com.realityforge.scanner.engine.pipeline

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Filesystem selection only; these opaque bytes do not claim native binary validation. */
class NativeDepthReferencesTest {
 @get:Rule val temporary=TemporaryFolder()
 private fun file(root:File,name:String)=File(root,name).apply{writeText("opaque fixture")}
 @Test fun manyCompletedTilesAndPartialsDoNotHideCompleteReferences() {
  val root=temporary.newFolder();repeat(40){file(root,"tile-0-$it.bin")}
  file(root,"ref-0.bin");file(root,"ref-7.bin");file(root,"surface-v2.rfmesh");file(root,"ref-1.bin.partial")
  assertEquals(listOf("ref-0.bin","ref-7.bin"),completeNativeDepthReferences(root).map{it.name})
 }
 @Test fun exactNativeReferenceDomainOnlyAndDeterministicOrder() {
  val root=temporary.newFolder()
  for(name in listOf("ref-7.bin","ref-2.bin","ref-0.bin","ref-8.bin","ref-999.bin","ref-00.bin","ref--1.bin","ref-3.bin.partial","ref-4xbin"))file(root,name)
  assertEquals(listOf("ref-0.bin","ref-2.bin","ref-7.bin"),completeNativeDepthReferences(root).map{it.name})
 }
 @Test fun tilesOnlyAndMissingOptionalDirectoryAreValidEmptyInventories() {
  val root=temporary.newFolder();repeat(40){file(root,"tile-1-$it.bin")}
  assertTrue(completeNativeDepthReferences(root).isEmpty())
  assertTrue(completeNativeDepthReferences(File(root,"absent")).isEmpty())
 }
 @Test fun leafSymlinksAndDirectoriesAreNotCompleteReferences() {
  val root=temporary.newFolder();val outside=temporary.newFile();outside.writeText("not owned")
  Files.createSymbolicLink(File(root,"ref-0.bin").toPath(),outside.toPath());File(root,"ref-1.bin").mkdir()
  file(root,"ref-2.bin")
  assertEquals(listOf("ref-2.bin"),completeNativeDepthReferences(root).map{it.name})
 }
 @Test fun symlinkIdentityDirectoryIsNotFollowed() {
  val root=temporary.newFolder();val outside=temporary.newFolder();file(outside,"ref-0.bin")
  val link=File(root,"identity");Files.createSymbolicLink(link.toPath(),outside.toPath())
  assertTrue(completeNativeDepthReferences(link).isEmpty())
 }
}
