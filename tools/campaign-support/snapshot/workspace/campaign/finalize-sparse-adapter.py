from pathlib import Path
p=Path('/workspace/agents/002/app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt')
s=p.read_text()
s=s.replace('''        val artifactKey = "sparse-m16-r$revision-${fingerprint.take(16)}"
''','''        // Stage/cache identities remain deterministic; completed publication paths are attempt-owned.
        // A failed rerun must never replace the files referenced by the prior completed database row.
        val artifactExtensions=listOf("json","glb","ply","dense.glb","dense.ply","mesh.glb","mesh.ply","textured.glb","texture.png")
        val artifactKey=generateSequence {
            "sparse-m16-r$revision-${sha256Utf8(fingerprint+java.util.UUID.randomUUID().toString()).take(16)}"
        }.take(32).firstOrNull { key -> artifactExtensions.none { extension ->
            File(directory,"$key.$extension").exists() || File(directory,"$key.$extension.partial").exists()
        } } ?: error("Cannot allocate a private reconstruction attempt")
        var publicationComplete=false
        try {
''',1)
s=s.replace('''        var denseSelection: DenseViewSelection.Plan? =''','''        val qualityByPhoto=photos.associate { photo ->
            require(photo.sharpness.isFinite() && photo.sharpness>=0)
            photo.id to photo.sharpness
        }
        val maximumSharpness=qualityByPhoto.values.maxOrNull() ?: 0.0
        var denseSelection: DenseViewSelection.Plan? =''',1)
s=s.replace('''DenseViewSelection.Camera(id,centre,if(id==first.id || id==second.id)anchorIds else emptySet(),0.0)''','''DenseViewSelection.Camera(id,centre,if(id==first.id || id==second.id)anchorIds else emptySet(),
                    if(maximumSharpness>0)qualityByPhoto.getValue(id)/maximumSharpness else 0.0)''',1)
s=s.replace('''List(views.size){0.0},List(views.size){true}))''','''nativeBatch.photoIds.map {qualityByPhoto.getValue(it)},List(views.size){true}))''',1)
s=s.replace('joint.getString("representation")=="sparse_points")','joint.getString("representation")=="sparse_points" && joint.getString("scale")=="arbitrary")',1)
s=s.replace('''original.put("refinedIntrinsics",models.getJSONArray(camera))''','''original.put("refinedIntrinsics",models.getJSONArray(camera))
                                .put("intrinsicPolicy",selectedGeometry.getString("intrinsicPolicy"))''',1)
needle='''        output
    }

    private suspend fun <T> nativeCall'''
replacement='''        publicationComplete=true
        output
        } finally {
            if(!publicationComplete) {
                // Only this fresh attempt's exact reserved names can be removed.
                for(extension in artifactExtensions) {
                    File(directory,"$artifactKey.$extension.partial").delete()
                    File(directory,"$artifactKey.$extension").delete()
                }
            }
        }
    }

    private suspend fun <T> nativeCall'''
assert needle in s;s=s.replace(needle,replacement,1)
p.write_text(s)
