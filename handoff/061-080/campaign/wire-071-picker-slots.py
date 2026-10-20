from pathlib import Path
p=Path('/workspace/agent-work/071/app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt');s=p.read_text()
s=s.replace('import kotlinx.coroutines.Dispatchers','import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers',1)
s=s.replace('    val pending = PendingRequest.restore(pendingValue)','''    val pending = PendingRequest.restore(pendingValue)
    var pendingEpoch by rememberSaveable { mutableLongStateOf(0L) }
    var launchEpoch by rememberSaveable { mutableLongStateOf(0L) }
    var callbackSlotsValue by rememberSaveable { mutableStateOf(PickerCallbackSlots.empty().encode()) }
    fun setPending(request:PendingRequest?) { pendingEpoch++;pendingValue=request?.encode() }
    fun setSlots(slots:PickerCallbackSlots) { launchEpoch++;callbackSlotsValue=slots.encode() }
    fun clearPending(request:PendingRequest) { if(PendingRequest.restore(pendingValue)==request) setPending(null) }
''',1)
s=s.replace('''    LaunchedEffect(exportStore) {
        val restore=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}}''','''    LaunchedEffect(exportStore) {
        val snapshot=ExportRestoreSnapshot(pendingEpoch,launchEpoch,PendingRequest.restore(pendingValue))
        val restore=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}}''',1)
s=s.replace('        if(restored!=null && pendingValue==null) pendingValue=PendingRequest(restored.requestId,PendingKind.EXPORT,restored.artifact.projectId,restored.artifact.revision,restored.artifact.artifactKey,restored.option.id).encode()','''        if(restored!=null && PickerCallbackBinding.mayRestore(snapshot,pendingEpoch,launchEpoch,PendingRequest.restore(pendingValue),exportAdmission))
            setPending(PendingRequest(restored.requestId,PendingKind.EXPORT,restored.artifact.projectId,restored.artifact.revision,restored.artifact.artifactKey,restored.option.id))''',1)
s=s.replace('    val pendingText = stringResource(R.string.a071_picker_busy)','''    val pendingText = stringResource(R.string.a071_picker_busy)
    val drainingText = stringResource(R.string.a071_picker_draining)
    val unconfirmedText = stringResource(R.string.a071_export_unconfirmed)''',1)
s=s.replace('''        if (PendingRequest.restore(pendingValue) != null || model.busy.value || exportAdmission) { model.notice.value = pendingText; return null }
        val project''','''        if (PendingRequest.restore(pendingValue) != null || model.busy.value || exportAdmission) { model.notice.value = pendingText; return null }
        if(!PickerCallbackSlots.restore(callbackSlotsValue).canLaunch(kind)) {model.notice.value=drainingText;return null}
        val project''',1)
start=s.index('    fun consume(kind: PendingKind): PendingRequest? {')
end=s.index('    LaunchedEffect(notice)',start)
s=s[:start]+'''    fun cancelRequest(request:PendingRequest) {
        clearPending(request)
        setSlots(PickerCallbackSlots.restore(callbackSlotsValue).cancel(request.requestId))
        model.cancelPendingRequest(request.requestId)
    }
    fun consume(slot:PickerCallbackSlot):PendingRequest? {
        val current=PendingRequest.restore(pendingValue)
        val consumed=PickerCallbackBinding.consume(slot,current,model.busy.value)
        if(consumed.pendingAfter!=current) setPending(consumed.pendingAfter)
        consumed.cancelRequestId?.let {model.cancelPendingRequest(it)}
        if(consumed.accepted==null && !slot.cancelled) model.notice.value=staleText
        return consumed.accepted
    }
    fun takeCallback(kind:PendingKind):PickerCallbackSlot? {
        val (slot,remaining)=PickerCallbackSlots.restore(callbackSlotsValue).take(kind)
        setSlots(remaining)
        return slot
    }
    fun launchPicker(request:PendingRequest,launch:()->Unit):Boolean = try {
        setSlots(PickerCallbackSlots.restore(callbackSlotsValue).launched(request))
        launch();true
    } catch(_:Exception) {
        // A synchronous launch failure has no outstanding Android result to drain.
        setSlots(PickerCallbackSlots.restore(callbackSlotsValue).launchFailed(request.requestId))
        clearPending(request);model.cancelPendingRequest(request.requestId);model.notice.value=errorText
        false
    }
    suspend fun clearStored(requestId:String):Boolean = withContext(NonCancellable+Dispatchers.IO) {
        runCatching {exportStore.clear(requestId)}.isSuccess
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        takeCallback(PendingKind.IMPORT)?.let {slot->consume(slot)?.let {model.completeImport(it.requestId,uris)}}
    }
    val calibrationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        takeCallback(PendingKind.CALIBRATE)?.let {slot->consume(slot)?.let {model.completeCalibration(it.requestId,uris)}}
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val slot=takeCallback(PendingKind.EXPORT) ?: return@rememberLauncherForActivityResult
        val request=slot.request
        val accepted=consume(slot)
        val uri=result.data?.data
        if(request.kind==PendingKind.EXPORT) {
            exportAdmission=true
            scope.launch {
                var typed:ExportRequest?=null
                try {
                    val restored=withContext(Dispatchers.IO) {runCatching {exportStore.restore()}}
                    typed=restored.getOrNull()
                    if(restored.isFailure) invalidExport=true
                    if(accepted!=null && PickerCallbackBinding.matchesStoredExport(request,typed) && result.resultCode==Activity.RESULT_OK && uri!=null) {
                        val fixed=requireNotNull(typed)
                        val valid=withContext(Dispatchers.IO) {runCatching {
                            if(!source.isCurrent(fixed.artifact)) false else {
                                source.validate(fixed,model.repository.artifact(fixed.artifact.projectId,fixed.artifact.artifactKey,fixed.option.id))
                                source.isCurrent(fixed.artifact)
                            }
                        }.getOrDefault(false)}
                        if(valid) model.completeArtifactExport(request.requestId,uri)
                        else model.notice.value=unconfirmedText
                    } else if(result.resultCode==Activity.RESULT_OK && uri!=null) model.notice.value=unconfirmedText
                } catch(cancelled:CancellationException) {
                    model.notice.value=unconfirmedText
                    throw cancelled
                } finally {
                    // C1 removes an admitted request before transfer. This cancels only an unclaimed
                    // original, including mismatched records; it never cancels another/new request.
                    model.cancelPendingRequest(request.requestId)
                    if(PickerCallbackBinding.matchesStoredExport(request,typed) && !clearStored(request.requestId)) model.notice.value=unconfirmedText
                    exportAdmission=false
                }
            }
        } else if(request.kind==PendingKind.CAPTURE_ARCHIVE && accepted!=null && result.resultCode==Activity.RESULT_OK && uri!=null)
            model.completeCaptureBundleExport(request.requestId,uri)
        else model.cancelPendingRequest(request.requestId)
    }
''' +s[end:]
s=s.replace('''        if (route.destination == Destination.CAPTURE) {pending?.let {model.cancelPendingRequest(it.requestId)};pendingValue = null}''','''        if (route.destination == Destination.CAPTURE) pending?.let(::cancelRequest)''',1)
start=s.index('    fun beginImport() {')
end=s.index('    fun leaveGuide()',start)
s=s[:start]+'''    fun beginImport() { currentRequest(PendingKind.IMPORT)?.let {request->setPending(request);launchPicker(request) {importPicker.launch(imageTypes)}} }
    fun beginCamera() { currentRequest(PendingKind.CAMERA)?.let {setPending(it);navigate(AppRoute(Destination.CAPTURE,it.projectId))} }
    fun beginCalibration() { currentRequest(PendingKind.CALIBRATE)?.let {request->setPending(request);launchPicker(request) {calibrationPicker.launch(imageTypes)}} }
    fun launchExport(identity:ArtifactIdentity,option:ExportOption) {
        val admitted=currentRequest(PendingKind.EXPORT,option.id) ?: return
        if(admitted.projectId!=identity.projectId || admitted.revision!=identity.revision || admitted.artifactKey!=identity.artifactKey) {model.cancelPendingRequest(admitted.requestId);model.notice.value=staleText;return}
        val typed=ExportRequest(admitted.requestId,identity,option)
        setPending(admitted)
        scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                check(source.isCurrent(identity))
                source.validate(typed,model.repository.artifact(identity.projectId,identity.artifactKey,option.id))
                check(source.isCurrent(identity))
                exportStore.save(typed)
            }}.onSuccess {
                if(PendingRequest.restore(pendingValue)!=admitted) {
                    clearStored(typed.requestId);model.cancelPendingRequest(typed.requestId)
                } else if(!launchPicker(admitted) {exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(option.format.mime).putExtra(Intent.EXTRA_TITLE,"realityforge-${option.representation.name.lowercase()}.${option.format.extension}"))}) {
                    if(!clearStored(typed.requestId)) model.notice.value=unconfirmedText
                }
            }.onFailure {
                model.cancelPendingRequest(typed.requestId);clearPending(admitted)
                invalidExport=withContext(NonCancellable+Dispatchers.IO) {runCatching {exportStore.restore()}.isFailure}
                model.notice.value=staleText
            }
        }
    }
''' +s[end:]
s=s.replace('''            CameraScreen(onClose = {pending?.let {model.cancelPendingRequest(it.requestId)};pendingValue=null; navigate(route.back()) }, onPhoto = { file ->
                val accepted = consume(PendingKind.CAMERA)
                navigate(route.back())
                if (accepted != null) model.completeCapture(accepted.requestId,file) else file.delete()
            })''','''            val captureRequest=requireNotNull(pending)
            CameraScreen(onClose = {
                if(PendingRequest.restore(pendingValue)==captureRequest) {cancelRequest(captureRequest);navigate(route.back())}
                else model.cancelPendingRequest(captureRequest.requestId)
            }, onPhoto = { file ->
                val accepted=consume(PickerCallbackSlot(captureRequest))
                if(accepted!=null) {navigate(route.back());model.completeCapture(accepted.requestId,file)} else file.delete()
            })''',1)
s=s.replace('''                        val cancelled=PendingRequest.restore(pendingValue);pendingValue=null
                        cancelled?.let {model.cancelPendingRequest(it.requestId)}
                        if(cancelled?.kind==PendingKind.EXPORT) scope.launch {withContext(Dispatchers.IO) {runCatching {exportStore.clear(cancelled.requestId)}}}''','''                        val cancelled=PendingRequest.restore(pendingValue)
                        cancelled?.let(::cancelRequest)
                        if(cancelled?.kind==PendingKind.EXPORT) scope.launch {if(!clearStored(cancelled.requestId)) model.notice.value=unconfirmedText}''',1)
s=s.replace('''                        currentRequest(PendingKind.CAPTURE_ARCHIVE)?.let { pendingValue=it.encode();exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"realityforge-capture.zip")) }''','''                        currentRequest(PendingKind.CAPTURE_ARCHIVE)?.let {request->setPending(request);launchPicker(request) {exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"realityforge-capture.zip"))}}''',1)
s=s.replace('''model.scannerState.value.pendingRequests.filter {it.kind==OperationKind.EXPORT_ARTIFACT}.forEach {model.cancelPendingRequest(it.requestId.value)};pendingValue=null;invalidExport=false''','''model.scannerState.value.pendingRequests.filter {it.kind==OperationKind.EXPORT_ARTIFACT}.forEach {request->
                    setSlots(PickerCallbackSlots.restore(callbackSlotsValue).cancel(request.requestId.value));model.cancelPendingRequest(request.requestId.value)
                };PendingRequest.restore(pendingValue)?.takeIf {it.kind==PendingKind.EXPORT}?.let {clearPending(it)};invalidExport=false''',1)
p.write_text(s)
p=Path('/workspace/agent-work/071/app/src/main/res/values/agent_071.xml');s=p.read_text().replace('</resources>','''    <string name="a071_picker_draining">Close the outstanding system picker before reopening this action. Its cancelled result cannot be applied to another request.</string>
    <string name="a071_export_unconfirmed">Export was not confirmed. The picker may have created an empty or incomplete file. Check the destination before trying again.</string>
</resources>''')
p.write_text(s)
