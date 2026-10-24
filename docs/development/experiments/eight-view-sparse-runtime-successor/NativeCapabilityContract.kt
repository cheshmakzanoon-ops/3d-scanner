package com.realityforge.scanner.hostintegration

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** JVM integrity mirror of the controller's already verified, sealed capability.
 * Python remains authoritative for build ownership and chronological graph replay.
 * This mirror derives closed roles from bound proofs, never from filename suffixes.
 */
internal object NativeCapabilityContract {
    const val EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val domains = linkedMapOf("opencv" to Pair("build", "opencv_java"), "scanner" to Pair("scanner", "realityforge"))
    private val phases = listOf("generate-java-core", "build-core", "generate-imgproc", "build-imgproc", "generate-features2d", "build-java")
    private val targets = listOf(listOf("CMakeFiles/dephelper/gen_opencv_java_source", "modules/core/opencl_kernels_core.cpp"), listOf("lib/libopencv_core.a"), listOf("modules/imgproc/opencl_kernels_imgproc.cpp"), listOf("lib/libopencv_imgproc.a"), listOf("modules/features2d/opencl_kernels_features2d.cpp"), listOf("opencv_java"))
    private val packageNames = setOf("ClassFilelist", "ClearClassFiles", "Symlinks", "UseJava", "cmake", "config", "copy", "definition", "jar", "javaRelease", "javac", "list", "rootDefinition")
    private val packageOutputs = listOf("CMakeFiles/dephelper/opencv_java_jar_source_copy", "CMakeFiles/dephelper/opencv_java_jar", "modules/java/jar/opencv/java_sources", "modules/java/jar/CMakeFiles/opencv_java_jar.dir/java_compiled_opencv_java_jar", "modules/java/jar/CMakeFiles/opencv_java_jar.dir/java_class_filelist", "bin/opencv-4120.jar")
    private val mandatory = setOf("compiler", "cc", "cmake", "ninja", "nm", "readelf", "ldd", "java", "javap", "javac", "javaRelease", "policy", "smokeSource", "archive", "license", "aar", "classes", "nativeOwner", "exportNativeOwner", "generatedJar", "jniSource", "exportJniSource", "jniObject", "exportJniObject", "jniDepfile", "exportJniDepfile", "jniContract", "source-files.json", "scanner-source.json", "opencv-abi.json", "scanner-abi.json", "consumed-inputs.json", "prebuild-inputs.json", "consumed-provenance.json", "robolectricNativeDistribution", "androidAll")
    data class Input(val path: String, val sha256: String, val emptyBaseline: Boolean = false)
    data class Contract(val inputs: Map<String, Input>, val labels: Set<String>, val workDir: String)
    private fun keys(obj: JSONObject) = obj.keys().asSequence().toSet()
    // Use only the JSONObject/JSONArray API also present in the Android sandbox.
    fun same(left: Any?, right: Any?): Boolean = when {
        left is JSONObject || right is JSONObject -> left is JSONObject && right is JSONObject && keys(left)==keys(right) && keys(left).all {same(left.get(it),right.get(it))}
        left is JSONArray || right is JSONArray -> left is JSONArray && right is JSONArray && left.length()==right.length() && (0 until left.length()).all {same(left.get(it),right.get(it))}
        else -> left==right
    }
    private fun exact(obj: JSONObject, names: Set<String>) { require(keys(obj) == names) { "Capability contract fields differ" } }
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { require(array.get(it) is String); array.getString(it) }
    private fun hash(value: String): String { require(value.matches(Regex("[0-9a-f]{64}"))); return value }
    private fun path(value: String): String {
        require(value.length in 1..4096 && File(value).isAbsolute && File(value).toPath().normalize().toString() == value)
        require(value.none { it.code < 32 || it.code == 127 })
        var i=0;while(i<value.length) { val c=value[i];if(c.isHighSurrogate()){require(i+1<value.length && value[i+1].isLowSurrogate());i++}else require(!c.isLowSurrogate());i++ }
        return value
    }
    private fun under(value: String, root: String): Boolean = File(path(value)).toPath().startsWith(File(path(root)).toPath())
    fun codepointSorted(values: Collection<String>): List<String> = values.sortedWith(Comparator { a,b ->
        val x=a.codePoints().toArray();val y=b.codePoints().toArray();var result=0
        for(i in 0 until minOf(x.size,y.size)) {if(x[i]!=y[i]){result=x[i].compareTo(y[i]);break}}
        if(result==0)x.size.compareTo(y.size) else result
    })
    private fun quote(value: String): String = buildString {
        append('"');for(c in value) append(when(c){'"'->"\\\"";'\\'->"\\\\";'\b'->"\\b";'\u000c'->"\\f";'\n'->"\\n";'\r'->"\\r";'\t'->"\\t";else->if(c.code<32||c.code>=127)"\\u%04x".format(c.code) else c.toString()});append('"')
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun canonicalMapHash(value: JSONObject): String = sha(codepointSorted(keys(value)).joinToString(", ", "{", "}") { key -> require(value.get(key) is String);quote(key)+": "+quote(value.getString(key)) }.toByteArray(Charsets.UTF_8))
    private fun hashes(value: JSONObject, maximum: Int): Map<String,String> {
        require(value.length()<=maximum);return keys(value).associateWith {path(it);hash(value.getString(it))}
    }
    private fun integer(value: JSONObject, name: String): Long {val item=value.get(name);require(item is Int || item is Long);return (item as Number).toLong()}
    private fun input(value: JSONObject, empty: Boolean = false): Input {
        exact(value,setOf("path","sha256"));return Input(path(value.getString("path")),hash(value.getString("sha256")),empty)
    }
    fun readJson(file: File, expectedHash: String, maximum: Long): JSONObject {
        require(maximum in 1..(32L*1024*1024));val raw=read(file,maximum,false)
        require(sha(raw)==hash(expectedHash)) { "Bound capability JSON changed" }
        return JSONObject(raw.toString(Charsets.UTF_8))
    }
    private fun read(file: File, maximum: Long, allowEmpty: Boolean): ByteArray {
        require(file.path==path(file.path) && file.canonicalPath==file.path)
        val before=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        require(before.isRegularFile && !before.isSymbolicLink && before.size() in (if(allowEmpty)0 else 1)..maximum)
        val output=ByteArrayOutputStream()
        Files.newByteChannel(file.toPath(),setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val stream=Channels.newInputStream(channel);val buffer=ByteArray(65536)
            while(true){val n=stream.read(buffer);if(n<0)break;require(output.size().toLong()+n<=maximum);output.write(buffer,0,n)}
        }
        val after=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        require(after.isRegularFile && !after.isSymbolicLink && after.fileKey()==before.fileKey() && after.lastModifiedTime()==before.lastModifiedTime() && after.size()==before.size() && output.size().toLong()==before.size() && file.canonicalPath==file.path)
        return output.toByteArray()
    }
    fun verifyInput(name: String, spec: Input, work: String) {
        val file=File(path(spec.path));hash(spec.sha256);path(work);require(file.canonicalPath==file.path) { "Capability input path alias" }
        if(spec.emptyBaseline) {
            require(name in setOf("native-opencv-baseline-log.txt","native-scanner-baseline-log.txt") && spec.path=="$work/capability/$name" && spec.sha256==EMPTY_SHA)
            require(read(file,8L*1024*1024,true).isEmpty()) { "Fresh baseline log is not empty" }
        } else {
            val logs=domains.keys.flatMap {domain -> listOf("native-$domain-baseline-log.txt","native-$domain-build-log.txt")}+phases.map {"native-opencv-phase-$it-log.txt"}
            val limit=when(name){in logs->8L*1024*1024;"header-provider.json"->32L*1024;"native-input-provenance.json","prebuild-inputs.json","consumed-inputs.json","consumed-provenance.json"->16L*1024*1024;else->512L*1024*1024}
            require(RuntimeProviderIdentity.fileHash(file,limit)==spec.sha256 && file.canonicalPath==file.path) { "Capability input changed: $name" }
        }
    }
    fun verifyInputs(contract: Contract) { for((name,spec) in contract.inputs) verifyInput(name,spec,contract.workDir) }
    fun derive(value: JSONObject, native: JSONObject, header: JSONObject, before: JSONObject, consumed: JSONObject, provenance: JSONObject, expected: CapabilityIdentity.Producer): Contract {
        CapabilityIdentity.producer(expected,expected)
        val work=path(expected.workDir);val out="$work/capability"
        require(value.get("schema")==1 && value.getString("classification")=="HOST_SOURCE_BUILT_CAPABILITY" && value.getString("sourceSha")==expected.sourceSha)
        val producer=value.getJSONObject("producer");exact(producer,setOf("runOwner","workDir","sourceSha","commands"))
        CapabilityIdentity.producer(CapabilityIdentity.Producer(producer.getString("runOwner"),producer.getString("workDir"),producer.getString("sourceSha")),expected)
        exact(native,setOf("schema","sourceSha","runOwner","workDir","ninjaVersion","tools","systemLink","graphs","inputBindings","generation"))
        require(native.get("schema")==2 && native.getString("sourceSha")==expected.sourceSha && native.getString("runOwner")==expected.runOwner && native.getString("workDir")==work && native.getString("ninjaVersion")=="1.13.2")
        exact(header,setOf("schema","sourceSha","runOwner","workDir","status","providers","filesystem"))
        require(header.get("schema")==2 && header.getString("sourceSha")==expected.sourceSha && header.getString("runOwner")==expected.runOwner && header.getString("workDir")==work && header.getString("status")=="ADMITTED_ROOTS_NOT_CONTENT_SNAPSHOT")
        val inputs=value.getJSONObject("inputs");require(inputs.length() in 30..25064)
        val expectedPaths=mandatory.associateWith {input(inputs.getJSONObject(it)).path}.toMutableMap()
        // Bound postbuild outputs from the two successful direct JNI compiler commands.
        // These are not prebuild immutable inputs and never receive that provenance label.
        for ((role, relative) in mapOf("jniObject" to "jni.o", "exportJniObject" to "export-jni.o",
            "jniDepfile" to "jni.d", "exportJniDepfile" to "export-jni.d"))
            require(expectedPaths.getValue(role) == "$out/$relative")
        val providerProof=input(inputs.getJSONObject("header-provider.json"));require(providerProof.path=="$out/header-provider.json");expectedPaths["header-provider.json"]=providerProof.path
        require(same(value.getJSONObject("headerProvider"),header))
        val nativeReference=input(value.getJSONObject("nativeInputProvenance"));require(nativeReference.path=="$out/native-input-provenance.json" && nativeReference==input(inputs.getJSONObject("native-input-provenance.json")))
        expectedPaths["native-input-provenance.json"]=nativeReference.path
        val labels=mutableSetOf<String>()
        val providers=header.getJSONArray("providers");require(providers.length()==3)
        for((index,major) in listOf(16,17,18).withIndex()) {
            val role=providers.getJSONObject(index);exact(role,setOf("major","driver","includeRoot","aliases","probes"));require(role.get("major")==major)
            val driver=input(role.getJSONObject("driver"));require(driver.path=="/usr/lib/llvm-$major/bin/clang" && driver==input(inputs.getJSONObject("headerProviderDriver-$major")))
            expectedPaths["headerProviderDriver-$major"]=driver.path
            val root="/usr/lib/llvm-$major/lib/clang/$major/include";require(role.getString("includeRoot")==root)
            val versions=when(major){16->listOf("16","16.0.6");17->listOf("17","17.0.6");else->listOf("18.1.3")}
            val aliases=role.getJSONArray("aliases");require(aliases.length()==versions.size)
            for((i,version) in versions.withIndex()) {val alias=aliases.getJSONObject(i);exact(alias,setOf("path","rawTarget","root"));require(alias.getString("path")=="/usr/include/clang/$version/include" && alias.getString("rawTarget")=="../../../lib/llvm-$major/lib/clang/$major/include" && alias.getString("root")==root)}
            val probes=role.getJSONArray("probes");require(probes.length()==2)
            for((i,kind) in listOf("version","resource-dir").withIndex()){val probe=probes.getJSONObject(i);exact(probe,setOf("label","outputSha256","outputText"));val label="header-provider-$major-$kind";require(probe.getString("label")==label && probe.getString("outputText").toByteArray().size<=4096 && sha(probe.getString("outputText").toByteArray(Charsets.UTF_8))==hash(probe.getString("outputSha256")));labels.add(label)}
        }
        val tools=native.getJSONObject("tools");exact(tools,setOf("compiler","cc","linker","ninja"))
        fun toolPath(record: JSONObject): String {exact(record,setOf("path","sha256","size","device","inode","mtimeNs"));hash(record.getString("sha256"));require(integer(record,"size")>0 && integer(record,"mtimeNs")>0);for(name in listOf("device","inode"))require(record.getString(name).matches(Regex("0|[1-9][0-9]{0,19}")));return path(record.getString("path"))}
        for(name in keys(tools)) {val record=tools.getJSONObject(name);val key="native-tool-$name";expectedPaths[key]=toolPath(record);require(inputs.getJSONObject(key).getString("sha256")==record.getString("sha256"))}
        for(name in listOf("compiler","cc","ninja"))require(same(inputs.getJSONObject(name),inputs.getJSONObject("native-tool-$name")))
        val generation=native.getJSONObject("generation");exact(generation,setOf("frozen","firstAdmission","initialPaths","packaging","phases"))
        val packaging=generation.getJSONObject("packaging");exact(packaging,setOf("inputs","modules","outputs","recipes","source"))
        val packageInputs=packaging.getJSONObject("inputs");exact(packageInputs,packageNames)
        for(name in packageNames) {val record=packageInputs.getJSONObject(name);val key="native-package-$name";expectedPaths[key]=toolPath(record);require(inputs.getJSONObject(key).getString("sha256")==record.getString("sha256"))}
        val source="$out/source/opencv-49486f61fb25722cbcf586b7f4320921d46fb38e";require(packaging.getString("source")==source)
        val modules=path(packaging.getString("modules"));require(File(modules).name=="Modules")
        val scripts=mapOf("rootDefinition" to "$source/CMakeLists.txt","copy" to "$source/cmake/copy_files.cmake","list" to "$source/modules/java/jar/list_java_sources.cmake","definition" to "$source/modules/java/jar/CMakeLists.txt","config" to "$out/build/modules/java/jar/copyfiles-JAVA_SRC_COPY.cmake","UseJava" to "$modules/UseJava.cmake","ClearClassFiles" to "$modules/UseJava/ClearClassFiles.cmake","ClassFilelist" to "$modules/UseJava/ClassFilelist.cmake","Symlinks" to "$modules/UseJava/Symlinks.cmake")
        for((name,p) in scripts)require(expectedPaths["native-package-$name"]==p)
        for(name in listOf("cmake","javac","javaRelease"))require(same(inputs.getJSONObject(name),inputs.getJSONObject("native-package-$name")))
        val javaRoot=File(expectedPaths.getValue("java")).parentFile.parentFile.path
        require(expectedPaths["native-package-jar"]=="$javaRoot/bin/jar" && expectedPaths["javac"]=="$javaRoot/bin/javac" && expectedPaths["javaRelease"]=="$javaRoot/release")
        val packageSet=packageOutputs.map {"$out/build/$it"}.toSet();require(strings(packaging.getJSONArray("outputs")).toSet()==packageSet && packaging.getJSONArray("outputs").length()==packageSet.size)
        val frozen=hashes(generation.getJSONObject("frozen"),60000);val admissions=generation.getJSONObject("firstAdmission");require(keys(admissions)==frozen.keys)
        val stageRecords=generation.getJSONArray("phases");require(stageRecords.length()==6)
        val stageFrozen=mutableMapOf<String,String>()
        for((i,name) in phases.withIndex()) {
            val stage=stageRecords.getJSONObject(i);exact(stage,setOf("id","kind","targets","frozen","inputs","records","outputStats","logSha256"))
            require(stage.getString("id")==name && stage.getString("kind")==if(i%2==0)"G" else "B")
            require(strings(stage.getJSONArray("targets"))==targets[i]);val key="native-opencv-phase-$name-log.txt";expectedPaths[key]="$out/$key"
            require(inputs.getJSONObject(key).getString("sha256")==hash(stage.getString("logSha256")))
            val added=hashes(stage.getJSONObject("frozen"),60000);if(i%2==1)require(added.isEmpty())
            for((p,h) in added){require(under(p,"$out/build") && !stageFrozen.containsKey(p) && admissions.get(p)==i);stageFrozen[p]=h}
            if(i!=5)labels.add("native-opencv-phase-$name")
            if(i%2==1)labels.add("native-opencv-phase-$name-deps")
        }
        require(stageFrozen==frozen)
        for(i in 0..4)labels.add("native-opencv-package-recipe-$i")
        labels.addAll(listOf("native-ninja-version","native-linker-select","native-linker-version","native-link-probe-compile","native-link-probe-link"))
        for(name in listOf("probe.cpp","probe.o","probe.so","link.d"))expectedPaths["native-probe-$name"]="$out/link-input-probe/$name"
        val graphMap=native.getJSONObject("graphs");exact(graphMap,domains.keys)
        val emptyBaselines=mutableSetOf<String>();val outputs=mutableMapOf<String,Pair<String,String>>();val directoryInputs=mutableSetOf<String>()
        val graphFields=setOf("target","graphTargets","buildDirectory","manifests","dependencyRules","nodes","paths","reachable","queryLabels","producers","aliases","absent","baselineLogSha256","baselineLogOutputs","logSha256","outputs","outputStats","records","orderSentinel")
        for((domain,description) in domains) {
            val graph=graphMap.getJSONObject(domain);exact(graph,graphFields);val build="$out/${description.first}";require(graph.getString("buildDirectory")==build && graph.getString("target")==description.second)
            val nodes=graph.getJSONObject("nodes");require(nodes.length() in 1..24000)
            val nodePaths=graph.getJSONObject("paths");require(keys(nodePaths)==keys(nodes));val reach=strings(graph.getJSONArray("reachable")).toSet();require(nodePaths.has(description.second) && nodePaths.getString(description.second) in reach)
            directoryInputs.add(build)
            if(!graph.isNull("orderSentinel")){val sentinel=graph.getJSONObject("orderSentinel");exact(sentinel,setOf("schema","scope","parent","input","kind","directory"));require(domain=="scanner" && sentinel.get("schema")==1 && sentinel.getString("scope")=="ORDER_ONLY_DIRECTORY_IDENTITY_NOT_FILE_CONTENT" && sentinel.getString("parent")=="cmake_object_order_depends_target_realityforge" && sentinel.getString("input")=="." && sentinel.getString("kind")=="order");val directory=sentinel.getJSONObject("directory");exact(directory,setOf("path","device","inode","mode"));require(directory.getString("path")==build)
                val rootNames=keys(nodePaths).filter {nodePaths.getString(it)==build && build in reach};require(rootNames==listOf("."))
                require(same(nodes.getJSONObject("."),JSONObject().put("rule",JSONObject.NULL).put("inputs",JSONArray())))
                val parent="cmake_object_order_depends_target_realityforge"
                require(nodePaths.getString(parent) in reach && same(nodes.getJSONObject(parent),JSONObject().put("rule","phony").put("inputs",JSONArray().put(JSONObject().put("name",".").put("kind","order")))))
                val inbound=mutableListOf<Pair<String,String>>()
                for(n in keys(nodes))if(nodePaths.getString(n) in reach){val edges=nodes.getJSONObject(n).getJSONArray("inputs");for(i in 0 until edges.length()){val edge=edges.getJSONObject(i);if(nodePaths.getString(edge.getString("name"))==build)inbound.add(n to edge.getString("kind"))}}
                require(inbound==listOf(parent to "order"))
            } else require(keys(nodePaths).none {nodePaths.getString(it)==build && build in reach})
            val produced=hashes(graph.getJSONObject("outputs"),24000);val aliases=graph.getJSONObject("aliases")
            for((p,h) in produced){require(under(p,build) && p in reach);val names=strings(aliases.getJSONArray(p));require(names.isNotEmpty() && names.size<=24000)
                require(names==codepointSorted(names) && names.toSet()==keys(nodePaths).filter {nodePaths.getString(it)==p}.toSet() && names.map {graph.getJSONObject("producers").get(it)}.toSet().size==1)
                require(names.all {same(nodes.getJSONObject(it),nodes.getJSONObject(names.first()))})
                for(n in names){require(nodePaths.getString(n)==p);val node=nodes.getJSONObject(n);require(!node.isNull("rule") && node.getString("rule")!="phony")}
                require(outputs.put(p,domain to h)==null)
            }
            val queries=(0 until ((nodes.length()+63)/64)).map {"native-$domain-query-$it"};require(strings(graph.getJSONArray("queryLabels"))==queries);labels.addAll(queries);labels.add("native-$domain-targets");labels.add("native-$domain-graph")
            for(kind in listOf("baseline","build")){val key="native-$domain-$kind-log.txt";expectedPaths[key]="$out/$key";val digest=hash(graph.getString(if(kind=="baseline")"baselineLogSha256" else "logSha256"));require(inputs.getJSONObject(key).getString("sha256")==digest)
                if(kind=="baseline" && digest==EMPTY_SHA){require(graph.getJSONArray("baselineLogOutputs").length()==0);emptyBaselines.add(key)}
            }
        }
        val beforeMap=hashes(before,60000);val consumedMap=hashes(consumed,24000);require(beforeMap.isNotEmpty() && consumedMap.isNotEmpty() && keys(provenance)==consumedMap.keys)
        val bindings=native.getJSONObject("inputBindings");exact(bindings,setOf("before","consumed"));require(bindings.getString("before")==canonicalMapHash(before) && bindings.getString("consumed")==canonicalMapHash(consumed))
        val systemProof=native.getJSONObject("systemLink");exact(systemProof,setOf("aliases","inputs","sourceSha256","objectSha256","outputSha256","depfileSha256"))
        val system=hashes(systemProof.getJSONObject("inputs"),12000)
        for(p in system.keys)require(under(p,"/usr/lib/gcc") || under(p,"/usr/lib/x86_64-linux-gnu"))
        for((name,key) in mapOf("probe.cpp" to "sourceSha256","probe.o" to "objectSha256","probe.so" to "outputSha256","link.d" to "depfileSha256"))require(inputs.getJSONObject("native-probe-$name").getString("sha256")==hash(systemProof.getString(key)))
        for(name in listOf("rootDefinition","copy","list","definition","config"))require(beforeMap[expectedPaths.getValue("native-package-$name")]==packageInputs.getJSONObject(name).getString("sha256"))
        for((p,h) in frozen)require(beforeMap[p]==h)
        val jni=expectedPaths.getValue("jniSource");require(jni.endsWith("/reconstruction/src/jni.cpp"))
        val reconstruction=jni.removeSuffix("/src/jni.cpp")
        val exportJni = expectedPaths.getValue("exportJniSource")
        require(exportJni == "$reconstruction/src/asset_exports_jni.cpp")
        require(consumedMap[exportJni] == inputs.getJSONObject("exportJniSource").getString("sha256"))
        require(beforeMap[exportJni] == consumedMap[exportJni])
        val nativeOwner = expectedPaths.getValue("nativeOwner")
        val nativeOwnerSuffix = "/com/realityforge/scanner/engine/NativeCore.class"
        val expectedExportOwner = if (nativeOwner == "$work/owner-com.realityforge.scanner.engine.NativeCore.class")
            "$work/owner-com.realityforge.scanner.export.NativeAssetExports.class"
        else {
            require(nativeOwner.endsWith(nativeOwnerSuffix))
            nativeOwner.removeSuffix(nativeOwnerSuffix) + "/com/realityforge/scanner/export/NativeAssetExports.class"
        }
        require(expectedPaths.getValue("exportNativeOwner") == expectedExportOwner)
        val allowedRoots=listOf(out,reconstruction,"$javaRoot/include","/usr/include","/usr/lib/gcc")+listOf(16,17,18).map {"/usr/lib/llvm-$it/lib/clang/$it/include"}
        for((i,p) in codepointSorted(consumedMap.keys).withIndex()) {
            require(p in system || allowedRoots.any {under(p,it)}) { "Consumed file outside closed source/provider roots" }
            require(p !in directoryInputs) { "Ordering directory cannot be consumed file" };val h=consumedMap.getValue(p);val role=provenance.getJSONObject(p);val row=JSONObject().put("sha256",h)
            when {
                p in beforeMap -> {require(beforeMap[p]==h);row.put("scope",if(p in frozen)"GENERATION_PHASE_PRECONSUMPTION_AND_POSTBUILD_IDENTICAL" else "PREBUILD_AND_POSTBUILD_IDENTICAL")}
                p in system -> {require(system[p]==h);row.put("scope","SYSTEM_LINK_PREBUILD_AND_POSTBUILD_IDENTICAL")}
                p in outputs -> {val pair=outputs.getValue(p);require(pair.second==h);row.put("scope",if(p in packageSet)"CURRENT_SUCCESSFUL_JAVA_PACKAGE_POSTBUILD_NOT_PRECONSUMPTION" else "CURRENT_SUCCESSFUL_GRAPH_POSTBUILD_NOT_PRECONSUMPTION").put("graph",pair.first).put("outputs",graphMap.getJSONObject(pair.first).getJSONObject("aliases").getJSONArray(p))}
                else -> error("Unbound consumed input role")
            }
            require(same(role,row)) { "Consumed role differs from bound proof" };val name="consumed-$i";expectedPaths[name]=p;require(inputs.getJSONObject(name).getString("sha256")==h)
        }
        require(keys(inputs)==expectedPaths.keys) { "Missing/unexpected capability input role" }
        val admitted=expectedPaths.mapValues { (name,p) -> val spec=input(inputs.getJSONObject(name),name in emptyBaselines);require(spec.path==p);spec }
        labels.addAll(listOf("compiler-version","opencv-configure","opencv-build","opencv-symbols","opencv-consumed-deps","opencv-smoke-compile","opencv-smoke","scanner-configure","scanner-build","scanner-javap","scanner-export-javap","scanner-jni-compile","scanner-export-compile","scanner-shim","scanner-consumed-deps","scanner-symbols"))
        for(i in 0 until 10)for(kind in listOf("aar","generated"))labels.add("opencv-$kind-$i")
        for(prefix in listOf("opencv","scanner-engine","scanner-shim"))for(part in listOf("elf-header","elf-dynamic","ldd"))labels.add("$prefix-$part")
        require(labels.size in 40..256)
        val commands=value.getJSONObject("producer").getJSONArray("commands");require(commands.length()==labels.size)
        val actualLabels=(0 until commands.length()).map {commands.getJSONObject(it).getString("label")};require(actualLabels.size==actualLabels.toSet().size && actualLabels.toSet()==labels)
        for(i in 0 until providers.length())for(j in 0..1){val probe=providers.getJSONObject(i).getJSONArray("probes").getJSONObject(j);val command=(0 until commands.length()).map {commands.getJSONObject(it)}.single {it.getString("label")==probe.getString("label")};require(command.getString("sha256")==probe.getString("outputSha256"))}
        return Contract(admitted.toMap(),labels.toSet(),work)
    }
}
