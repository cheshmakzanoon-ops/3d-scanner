package com.realityforge.scanner.hostintegration

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

/** Integrity recheck of the reviewed controller's internally produced capability, NOT a
 * substitute for its authoritative in-memory producer/ABI/header/ELF verification.
 * No API accepts a capability without the exact reviewed build policy and launcher properties.
 */
internal class QualifiedCapability private constructor(private val manifestFile: File, private val manifestHash: String,
    val value: JSONObject, private val policyFile: File) {
    private val native = value.getJSONObject("native")
    fun nativeFile(role: String): File = File(native.getJSONObject(role).getString("path")).canonicalFile
    fun nativeHash(role: String): String = native.getJSONObject(role).getString("sha256")
    fun verifyCurrent() {
        require(RuntimeProviderIdentity.fileHash(manifestFile, 32L * 1024 * 1024) == manifestHash)
        require(RuntimeProviderIdentity.fileHash(policyFile) == POLICY_SHA256)
        for (role in ROLES) require(RuntimeProviderIdentity.fileHash(nativeFile(role)) == nativeHash(role))
        val inputs=value.getJSONObject("inputs")
        require(RuntimeProviderIdentity.fileHash(File(inputs.getJSONObject("androidAll").getString("path")),512L*1024*1024)==Api35ProviderContract.FRAMEWORK_SHA256)
        require(RuntimeProviderIdentity.fileHash(File(inputs.getJSONObject("robolectricNativeDistribution").getString("path")),512L*1024*1024)==Api35ProviderContract.COMPAT_SHA256)
    }
    /** Called only after actual Bitmap pixel roundtrip + actual Room SQLite query. */
    fun captureRuntime(evidence: File, phase: String, sqliteVersion: String) {
        require(phase in setOf("after-bitmap-room", "after-room-reopen"))
        val output = File(evidence, "runtime-providers-$phase.json")
        require(!output.exists())
        val receipt = JSONObject().put("schema", 1).put("phase", phase).put("status", "UNQUALIFIED")
            .put("capabilitySha256", manifestHash).put("policySha256", POLICY_SHA256)
            .put("sourceSha", value.getString("sourceSha")).put("sqliteVersion", sqliteVersion)
            .put("activity", JSONArray(listOf("actual Bitmap pixel roundtrip", "actual Room SELECT sqlite_version()")))
        try {
            verifyCurrent()
            require(sqliteVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            val maps = RuntimeProviderIdentity.ownMaps()
            val scope = maps.filter { entry -> val name=File(entry.path.removeSuffix(" (deleted)")).name
                name.startsWith("libopencv") || name.startsWith("librealityforge") || name.contains("android_runtime") || name.contains("sqlite") || name.contains("nativeruntime") }
            receipt.put("observedMappings", JSONArray(scope.map { JSONObject().put("path",it.path).put("inode",it.inode) }))
            val expected = ROLES.map { nativeFile(it).path }.toSet()
            val actualScanner = scope.filter { File(it.path.removeSuffix(" (deleted)")).name.let { n -> n.startsWith("libopencv") || n.startsWith("librealityforge") } }
            require(actualScanner.map { it.path }.toSet() == expected) { "Unexpected/missing mapped OpenCV or scanner provider" }
            val scannerProof = JSONArray()
            for (role in ROLES) {
                val file=nativeFile(role);val mapped=actualScanner.single { it.path == file.path }
                val inode=(Files.getAttribute(file.toPath(), "unix:ino") as Number).toLong()
                require(inode==mapped.inode && inode>0)
                scannerProof.put(JSONObject().put("role",role).put("path",file.path).put("sha256",nativeHash(role)).put("inode",inode))
            }
            val runtime=value.getJSONObject("runtimeProvider")
            val proof=RuntimeProviderIdentity.matchProvider(maps,File(required("java.io.tmpdir",false)),runtime.getString("entrySha256"),runtime.getLong("entryBytes"),Api35ProviderContract.MAXIMUM_BYTES)
            require(scope.none { File(it.path.removeSuffix(" (deleted)")).name.let { n -> n.contains("sqlite") || n.contains("nativeruntime") } }) {
                "Additional unadmitted SQLite/native-runtime provider mapped"
            }
            require(scope.all { it.path in expected || it.path == proof.path }) { "Unexpected extra target native provider" }
            val dependencyProof = JSONArray()
            val dependencies = linkedMapOf<String, String>()
            val elfRecords = listOf(value.getJSONObject("opencvElf"), value.getJSONObject("scannerElf").getJSONObject("engine"), value.getJSONObject("scannerElf").getJSONObject("shim"))
            for (elf in elfRecords) {
                val providers=elf.getJSONObject("providers")
                for(name in keys(providers)) {
                    val entry=providers.getJSONObject(name);val path=entry.getString("path");val hash=entry.getString("sha256")
                    require(dependencies[path]==null || dependencies[path]==hash);dependencies[path]=hash
                }
            }
            require(dependencies.size in 1..32)
            for ((path, hash) in dependencies) {
                val mapped=maps.single { it.path == path }
                val file=File(path);val inode=(Files.getAttribute(file.toPath(), "unix:ino") as Number).toLong()
                require(inode==mapped.inode && inode>0 && RuntimeProviderIdentity.fileHash(file)==hash)
                dependencyProof.put(JSONObject().put("path",path).put("sha256",hash).put("inode",inode))
            }
            receipt.put("scannerElfDependencies", dependencyProof)
            // The same API35 libandroid_runtime contains graphics and SQLite; do not invent separate providers.
            receipt.put("scannerProviders",scannerProof).put("androidRuntimeProvider",JSONObject()
                .put("path",proof.path).put("sha256",proof.sha256).put("bytes",proof.bytes).put("inode",proof.inode)
                .put("archivePath",runtime.getString("path")).put("archiveSha256",RUNTIME_JAR_SHA256)
                .put("archiveEntry",RUNTIME_ENTRY)).put("status","OBSERVED_PROVIDER_IDENTITIES_MATCH")
            output.writeText(receipt.toString(2))
        } catch (failure: Throwable) {
            receipt.put("failureType",failure.javaClass.name).put("failure",failure.message)
            preserveEvidenceFailure(failure) { output.writeText(receipt.toString(2)) }
            throw failure
        }
    }
    companion object {
        const val POLICY_SHA256="4d727401861b7d460f3e6c4a30a99b34d7db59a0f3a930920d169d872a3b1820"
        const val RUNTIME_JAR_SHA256=Api35ProviderContract.FRAMEWORK_SHA256
        const val RUNTIME_ENTRY=Api35ProviderContract.ENTRY
        private val ROLES=setOf("opencv","shim","engine")
        private val CHECKS=setOf("sourceIntegrity","opencvModules","opencvAbi","opencvElf","opencvSmoke","scannerSource","scannerAbi","scannerElf")
        private var accepted: QualifiedCapability?=null
        fun required(key:String,loader:Boolean=true):String=requireNotNull(System.getProperty(if(loader) "rf.loader.$key" else key))
        private fun keys(obj:JSONObject)=obj.keys().asSequence().toSet()
        private fun record(obj:JSONObject, commandLog:Boolean=false):File {
            require(keys(obj)==setOf("path","sha256"))
            val file=File(obj.getString("path"));require(file.isAbsolute && file.path==file.canonicalPath)
            val hash=obj.getString("sha256");require(hash.matches(Regex("[a-f0-9]{64}")))
            require((if(commandLog) CapabilityIdentity.logHash(file) else RuntimeProviderIdentity.fileHash(file,512L*1024*1024))==hash)
            return file
        }
        @Synchronized fun load():QualifiedCapability {
            accepted?.let { it.verifyCurrent();return it }
            require(required("policySha256")==POLICY_SHA256) { "Unreviewed build policy" }
            val file=File(required("capabilityManifest"));require(file.isAbsolute && file.path==file.canonicalPath && file.name=="capability.json" && file.parentFile.name=="capability")
            val hash=required("capabilitySha256");require(hash.matches(Regex("[a-f0-9]{64}")))
            require(RuntimeProviderIdentity.fileHash(file,32L*1024*1024)==hash)
            val value=JSONObject(file.readText())
            require(value.getInt("schema")==1 && value.getString("classification")=="HOST_SOURCE_BUILT_CAPABILITY")
            require(value.getString("sourceSha")==required("sourceSha") && required("sourceSha").matches(Regex("[a-f0-9]{40}")))
            require(value.getString("policySha256")==POLICY_SHA256)
            val checks=value.getJSONObject("checks");require(keys(checks)==CHECKS && CHECKS.all { checks.get(it)==true })
            val native=value.getJSONObject("native");require(keys(native)==ROLES)
            val relative=mapOf("opencv" to "build/lib/libopencv_java4120.so","shim" to "librealityforge_jni.so","engine" to "scanner/librealityforge.so")
            for(role in ROLES) {
                val f=record(native.getJSONObject(role))
                require(f==File(file.parentFile,relative.getValue(role)).canonicalFile && f==File(required(role)).canonicalFile)
            }
            val inputs=value.getJSONObject("inputs");require(inputs.length() in 30..25064)
            val mandatory=setOf("compiler","cc","cmake","ninja","nm","readelf","ldd","java","javap","javac","javaRelease","policy","smokeSource","archive","license","aar","classes","nativeOwner","generatedJar","jniSource","jniContract","source-files.json","scanner-source.json","opencv-abi.json","scanner-abi.json","consumed-inputs.json","prebuild-inputs.json","consumed-provenance.json","robolectricNativeDistribution","androidAll")
            require(keys(inputs).containsAll(mandatory) && keys(inputs).any { it.startsWith("consumed-") })
            for(name in keys(inputs))record(inputs.getJSONObject(name))
            val policyFile=File(inputs.getJSONObject("policy").getString("path"))
            require(RuntimeProviderIdentity.fileHash(policyFile)==POLICY_SHA256)
            val producer=value.getJSONObject("producer")
            require(keys(producer)==setOf("runOwner","workDir","sourceSha","commands"))
            val expectedProducer=CapabilityIdentity.Producer(required("runOwner"),required("workDir"),required("sourceSha"))
            CapabilityIdentity.producer(CapabilityIdentity.Producer(producer.getString("runOwner"),producer.getString("workDir"),producer.getString("sourceSha")),expectedProducer)
            val work=File(expectedProducer.workDir)
            require(file==File(work,"capability/capability.json"))
            val before=JSONObject(File(inputs.getJSONObject("prebuild-inputs.json").getString("path")).readText())
            val consumed=JSONObject(File(inputs.getJSONObject("consumed-inputs.json").getString("path")).readText())
            val provenance=JSONObject(File(inputs.getJSONObject("consumed-provenance.json").getString("path")).readText())
            require(before.length() in 1..60000 && consumed.length() in 1..24000 && keys(provenance)==keys(consumed))
            val generated=setOf("core.inl.hpp","imgproc.inl.hpp","features2d.inl.hpp","opencv_jni.hpp").map { File(file.parentFile,"build/modules/java_bindings_generator/gen/cpp/$it").path }.toSet()
            require(keys(inputs)==mandatory + (0 until consumed.length()).map { "consumed-$it" }.toSet())
            for((index,path) in keys(consumed).sorted().withIndex()) {
                val digest=consumed.getString(path);val item=provenance.getJSONObject(path)
                val boundInput=inputs.getJSONObject("consumed-$index")
                require(boundInput.getString("path")==path && boundInput.getString("sha256")==digest)
                require(keys(item)==setOf("sha256","scope") && item.getString("sha256")==digest)
                if(before.has(path)) require(before.getString(path)==digest && item.getString("scope")=="PREBUILD_AND_POSTBUILD_IDENTICAL")
                else require(path in generated && item.getString("scope")=="POST_GENERATION_ONLY_NOT_PRECONSUMPTION")
            }
            val commands=producer.getJSONArray("commands");require(commands.length() in 40..256)
            val labels=mutableSetOf<String>()
            for(i in 0 until commands.length()) {
                val command=commands.getJSONObject(i);val label=command.getString("label")
                require(label.matches(Regex("[a-z0-9-]+")) && labels.add(label))
                CapabilityIdentity.command(command.getString("runOwner"),expectedProducer.runOwner,command.getLong("starttime"),command.getLong("pid"),command.getString("environmentBoundary"))
                require(command.get("complete")==true && command.getInt("exit")==0 && listOf("error","cleanupError","secondaryErrors").none(command::has))
                val log=record(command.getJSONObject("log"),commandLog=true)
                require(log==File(work,"logs/$label.raw.log") && log.length()==command.getLong("bytes"))
                require(command.getString("sha256")==command.getJSONObject("log").getString("sha256"))
                require(command.getJSONArray("argv").length()>0)
            }
            val expectedLabels=mutableSetOf("compiler-version","opencv-configure","opencv-build","opencv-symbols","opencv-consumed-deps","opencv-smoke-compile","opencv-smoke","scanner-configure","scanner-build","scanner-javap","scanner-shim","scanner-consumed-deps","scanner-symbols")
            for(i in 0 until 10) for(kind in listOf("aar","generated")) expectedLabels.add("opencv-$kind-$i")
            for(prefix in listOf("opencv","scanner-engine","scanner-shim")) for(part in listOf("elf-header","elf-dynamic","ldd")) expectedLabels.add("$prefix-$part")
            require(labels==expectedLabels)
            val framework=inputs.getJSONObject("androidAll")
            val offline=File(required("robolectric.dependency.dir",false)).canonicalFile
            val frameworkFile=File(framework.getString("path"))
            require(frameworkFile==File(offline,Api35ProviderContract.FRAMEWORK_NAME) && framework.getString("sha256")==Api35ProviderContract.FRAMEWORK_SHA256)
            require(offline.list()?.toSet()==setOf(Api35ProviderContract.FRAMEWORK_NAME))
            require(inputs.getJSONObject("robolectricNativeDistribution").getString("sha256")==Api35ProviderContract.COMPAT_SHA256)
            val runtime=value.getJSONObject("runtimeProvider")
            Api35ProviderContract.validateArchive(runtime.getString("sha256"),runtime.getString("entry"),runtime.getLong("entryBytes"))
            require(runtime.getString("path")==frameworkFile.path)
            val entry=RuntimeProviderIdentity.archiveEntry(frameworkFile,RUNTIME_JAR_SHA256,RUNTIME_ENTRY,Api35ProviderContract.MAXIMUM_BYTES)
            require(entry.first==runtime.getString("entrySha256") && entry.second==Api35ProviderContract.ENTRY_BYTES)
            return QualifiedCapability(file,hash,value,policyFile).also { it.verifyCurrent();accepted=it }
        }
    }
}
