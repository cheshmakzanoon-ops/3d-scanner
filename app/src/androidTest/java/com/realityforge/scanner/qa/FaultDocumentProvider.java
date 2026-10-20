package com.realityforge.scanner.qa;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sole shared test-APK provider. Uses only Android/Java platform classes because the
 * standalone provider process cannot load Kotlin dependencies deduplicated into the target APK.
 * All retained bytes belong to explicitly configured synthetic tests.
 */
public final class FaultDocumentProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.realityforge.scanner.test.faults";
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_REQUESTS = 16;
    public static final int MAX_EVENTS = 256;
    private static final String TARGET_PACKAGE = "com.realityforge.scanner";
    private static final Uri GRANT_ROOT = Uri.parse("content://" + AUTHORITY);
    private static final int ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    public static final List<String> MODES = java.util.Collections.unmodifiableList(Arrays.asList(
            "normal", "truncated", "slow", "revoked", "short-write", "enospc"));
    private static final Set<String> REQUEST_MODES = new HashSet<>(Arrays.asList(
            "normal", "slow", "silent-truncate", "enospc", "edquot", "revoked", "revoke-readback", "missing"));
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final HandlerThread thread = new HandlerThread("bounded-document-faults");
    private final Object lock = new Object();
    private final Map<String, Integer> handles = new HashMap<>();
    private StorageManager storageManager;
    private ParcelFileDescriptor bridgeAnchor;

    /**
     * Explicit shell-only bootstrap into the provider-owning test UID. Shell does
     * not possess MANAGE_DOCUMENTS; a normal consumer instead receives a URI grant.
     * The receiver is DUMP protected in the test manifest and cannot choose another
     * package, authority, file path or permission. No component is in the main APK.
     */
    public static final class GrantReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String operation = intent.getStringExtra("operation");
            if ("grant".equals(operation)) {
                context.grantUriPermission(TARGET_PACKAGE, GRANT_ROOT, ACCESS | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            } else if ("revoke".equals(operation)) {
                context.revokeUriPermission(TARGET_PACKAGE, GRANT_ROOT, ACCESS);
            } else throw new IllegalArgumentException("Explicit grant/revoke operation required");
            setResultCode(Activity.RESULT_OK);
            setResultData("RF088_" + operation);
        }
    }

    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static <T> T unchecked(Work<T> work) {
        try { return work.run(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private <T> T document(Work<T> work) throws FileNotFoundException {
        synchronized (lock) { return documentIo(work); }
    }
    private <T> T documentIo(Work<T> work) throws FileNotFoundException {
        try { return work.run(); }
        catch (FileNotFoundException error) { throw error; }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) {
            FileNotFoundException failure = new FileNotFoundException(error.toString());
            failure.initCause(error); throw failure;
        }
    }
    private <T> T proxy(Work<T> work) throws ErrnoException {
        synchronized (lock) {
            try { return work.run(); }
            catch (ErrnoException error) { throw error; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ErrnoException("synthetic interrupted I/O", OsConstants.EINTR, error); }
            catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new ErrnoException("synthetic provider I/O", OsConstants.EIO, error); }
        }
    }
    private File root() {
        File result = new File(java.util.Objects.requireNonNull(getContext()).getFilesDir(), "qa-fault-documents");
        if (!result.isDirectory() && !result.mkdirs()) throw new IllegalStateException("Cannot create synthetic root");
        return result;
    }
    private String requestId(String id) {
        require(id != null && REQUEST_ID.matcher(id).matches(), "Invalid synthetic request ID"); return id;
    }
    private File directory(String id) { return new File(root(), requestId(id)); }
    private File payload(String id) { return new File(directory(id), "payload.bin"); }
    private AtomicFile metadata(String id) { return new AtomicFile(new File(directory(id), "state.json")); }
    private static byte[] bytes(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
    private JSONObject read(String id) throws Exception {
        AtomicFile file = metadata(id);
        if (!file.getBaseFile().isFile()) throw new FileNotFoundException("Unknown synthetic request");
        try (FileInputStream input = file.openRead()) {
            return new JSONObject(new String(bytes(input), StandardCharsets.UTF_8));
        }
    }
    private void save(String id, JSONObject state) throws Exception {
        AtomicFile file = metadata(id); FileOutputStream stream = file.startWrite();
        try { stream.write(state.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception error) { file.failWrite(stream); throw error; }
    }
    private void event(String id, JSONObject state, String name) throws Exception {
        event(id, state, name, new JSONObject());
    }
    private void event(String id, JSONObject state, String name, JSONObject details) throws Exception {
        JSONArray events = state.getJSONArray("events"); long sequence = state.getLong("eventSequence") + 1;
        state.put("eventSequence", sequence);
        events.put(details.put("sequence", sequence).put("event", name)
                .put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos()).put("wallTimeMillis", System.currentTimeMillis()));
        if (events.length() > MAX_EVENTS) {
            events.remove(0); state.put("droppedEvents", state.getLong("droppedEvents") + 1);
        }
        save(id, state);
    }
    private static final class Request {
        final String id; final boolean directory;
        Request(String id, boolean directory) { this.id = id; this.directory = directory; }
    }
    private Request parsedDocument(String id) {
        if (id.startsWith("request/")) return new Request(requestId(id.substring("request/".length())), false);
        if (id.startsWith("request-root/")) return new Request(requestId(id.substring("request-root/".length())), true);
        return null;
    }
    private void available(JSONObject state) throws Exception {
        if (!state.getBoolean("created") || state.getBoolean("deleted") || state.getJSONObject("config").getString("mode").equals("missing"))
            throw new FileNotFoundException("Synthetic document unavailable");
    }
    private JSONObject snapshot(String id, JSONObject state) throws Exception {
        File data = payload(id); MessageDigest sha = MessageDigest.getInstance("SHA-256");
        if (data.isFile()) try (InputStream input = new FileInputStream(data)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) sha.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : sha.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new JSONObject(state.toString()).put("providerPid", android.os.Process.myPid())
                .put("storedBytes", data.isFile() ? data.length() : 0).put("storedSha256", hex.toString());
    }
    private long integer(JSONObject config, String key, long defaultValue) {
        Object value = config.opt(key); if (value == null) return defaultValue;
        require(value instanceof Integer || value instanceof Long, key + " must be an integer"); return ((Number) value).longValue();
    }
    private boolean bool(JSONObject config, String key, boolean defaultValue) {
        Object value = config.opt(key); if (value == null) return defaultValue;
        require(value instanceof Boolean, key + " must be a boolean"); return (Boolean) value;
    }
    @Override public boolean onCreate() {
        thread.start();
        storageManager = java.util.Objects.requireNonNull(getContext()).getSystemService(StorageManager.class);
        // Android10 tears down AppFuse when its final descriptor closes. Keep
        // exactly one empty, unexposed descriptor so client close/open sequences
        // do not race bridge destruction/recreation. Anchor callbacks never lock.
        bridgeAnchor = unchecked(() -> storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                new ProxyFileDescriptorCallback() {
                    @Override public long onGetSize() { return 0; }
                    @Override public int onRead(long offset, int size, byte[] data) { return 0; }
                    @Override public void onRelease() { }
                }, new Handler(thread.getLooper())));
        return true;
    }
    @Override public void shutdown() {
        try {
            if (bridgeAnchor != null) unchecked(() -> { bridgeAnchor.close(); bridgeAnchor = null; return null; });
        } finally { thread.quitSafely(); super.shutdown(); }
    }

    /** Permission checked administrative API; no arbitrary file paths or reset-all operation. */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith("qa.")) return super.call(method, arg, extras);
        Context context = java.util.Objects.requireNonNull(getContext());
        // Configuration requires either the platform manager permission or the
        // exact fixed consumer UID holding this test provider's explicit grant.
        boolean manager = context.checkCallingOrSelfPermission("android.permission.MANAGE_DOCUMENTS") == PackageManager.PERMISSION_GRANTED;
        boolean consumer = unchecked(() -> Binder.getCallingUid() == context.getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0).uid)
                && context.getPackageManager().checkSignatures(TARGET_PACKAGE, context.getPackageName()) == PackageManager.SIGNATURE_MATCH
                && context.checkUriPermission(GRANT_ROOT, Binder.getCallingPid(), Binder.getCallingUid(), ACCESS) == PackageManager.PERMISSION_GRANTED;
        if (!manager && !consumer) throw new SecurityException("Synthetic configuration requires explicit consumer URI grant");
        synchronized (lock) { return unchecked(() -> {
            Bundle result = new Bundle(); String id; JSONObject state;
            switch (method) {
                case "qa.configure": {
                    String text = java.util.Objects.requireNonNull(extras == null ? null : extras.getString("config"));
                    require(text.length() <= 4096, "Oversized configuration");
                    JSONObject config = new JSONObject(text);
                    Set<String> allowed = new HashSet<>(Arrays.asList("schema", "requestId", "mode", "verification", "limitBytes",
                            "delayMillis", "deleteFails", "preExisting", "created", "synthetic"));
                    for (Iterator<String> keys = config.keys(); keys.hasNext();) require(allowed.contains(keys.next()), "Unknown configuration field");
                    require(integer(config, "schema", -1) == 1 && bool(config, "synthetic", false), "Synthetic schema1 only");
                    id = requestId(config.getString("requestId"));
                    require(!directory(id).exists(), "Request exists; explicitly reset it before reuse");
                    int count = 0; File[] children = root().listFiles();
                    if (children != null) for (File child : children) if (child.isDirectory()) count++;
                    require(count < MAX_REQUESTS, "Synthetic request capacity reached");
                    require(!config.has("mode") || config.get("mode") instanceof String, "mode must be a string");
                    require(!config.has("verification") || config.get("verification") instanceof String, "verification must be a string");
                    String mode = config.optString("mode", "normal"), verification = config.optString("verification", "readback");
                    long limit = integer(config, "limitBytes", MAX_BYTES), delay = integer(config, "delayMillis", mode.equals("slow") ? 10 : 0);
                    require(REQUEST_MODES.contains(mode) && Arrays.asList("readback", "size-only", "none").contains(verification), "Unknown fault/capability");
                    require(limit >= 0 && limit <= MAX_BYTES && delay >= 0 && delay <= 25, "Bounded limit/delay required");
                    boolean preExisting = bool(config, "preExisting", false), created = bool(config, "created", true);
                    require(!preExisting || created, "Pre-existing document must exist");
                    config.put("mode", mode).put("verification", verification).put("limitBytes", limit).put("delayMillis", delay)
                            .put("preExisting", preExisting).put("created", created).put("deleteFails", bool(config, "deleteFails", false));
                    if (!directory(id).mkdir()) throw new IllegalStateException("Cannot create request");
                    try (FileOutputStream stream = new FileOutputStream(payload(id))) { stream.flush(); }
                    state = new JSONObject().put("schema", 1).put("requestId", id).put("config", config).put("created", created)
                            .put("deleted", false).put("revoked", mode.equals("revoked")).put("writeOpens", 0).put("readOpens", 0)
                            .put("writeCalls", 0).put("deleteCalls", 0).put("acceptedBytes", 0L).put("storedWriteBytes", 0L)
                            .put("eventSequence", 0L).put("droppedEvents", 0L).put("configuredProviderPid", android.os.Process.myPid()).put("events", new JSONArray());
                    event(id, state, "configured");
                    result.putString("documentId", "request/" + id); result.putString("parentDocumentId", "request-root/" + id);
                    result.putString("json", snapshot(id, state).toString()); return result;
                }
                case "qa.snapshot":
                    id = requestId(arg); result.putString("json", snapshot(id, read(id)).toString()); return result;
                case "qa.revoke":
                    id = requestId(arg); state = read(id); state.put("revoked", true); event(id, state, "permissionRevoked");
                    result.putString("json", snapshot(id, state).toString()); return result;
                case "qa.reset":
                    id = requestId(arg); require(handles.getOrDefault(id, 0) == 0, "Close request descriptors before reset");
                    result.putBoolean("removed", deleteTree(directory(id))); return result;
                default: throw new IllegalArgumentException("Unknown synthetic fault operation");
            }
        }); }
    }
    private boolean deleteTree(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        return directory.delete() || !directory.exists();
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS};
        MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) switch (columns[i]) {
            case Root.COLUMN_ROOT_ID: row[i] = "synthetic"; break;
            case Root.COLUMN_DOCUMENT_ID: row[i] = "root"; break;
            case Root.COLUMN_TITLE: row[i] = "Synthetic QA faults"; break;
            case Root.COLUMN_FLAGS: row[i] = 0; break;
            default: break;
        }
        cursor.addRow(row); return cursor;
    }
    private Cursor rows(List<String> ids, String[] projection) throws Exception {
        String[] columns = projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String id : ids) {
            Request request = parsedDocument(id); JSONObject state = request == null ? null : read(request.id);
            boolean directory = id.equals("root") || request != null && request.directory;
            if (state != null && !directory) available(state);
            if (request == null && !id.equals("root") && !MODES.contains(id)) throw new FileNotFoundException("Unknown synthetic document");
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: row[i] = directory ? "Synthetic request" : request == null ? id + ".jpg" : "synthetic-output.bin"; break;
                case Document.COLUMN_MIME_TYPE: row[i] = directory ? Document.MIME_TYPE_DIR : request == null ? "image/jpeg" : "application/octet-stream"; break;
                case Document.COLUMN_FLAGS:
                    row[i] = request != null && request.directory && !state.getJSONObject("config").getBoolean("preExisting") ? Document.FLAG_DIR_SUPPORTS_CREATE
                            : request != null && !directory ? Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE
                            : id.equals("short-write") || id.equals("enospc") ? Document.FLAG_SUPPORTS_WRITE : 0; break;
                case Document.COLUMN_SIZE:
                    row[i] = request != null && !directory && !state.getJSONObject("config").getString("verification").equals("none") ? payload(request.id).length() : null; break;
                default: break;
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        return document(() -> rows(java.util.Collections.singletonList(id), projection));
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        return document(() -> {
            List<String> ids = new ArrayList<>();
            if (parent.equals("root")) {
                ids.addAll(MODES); File[] children = root().listFiles();
                if (children != null) for (File child : children) if (child.isDirectory()) ids.add("request-root/" + child.getName());
            } else if (parent.startsWith("request-root/")) {
                String id = requestId(parent.substring("request-root/".length())); JSONObject state = read(id);
                if (state.getBoolean("created") && !state.getBoolean("deleted") && !state.getJSONObject("config").getString("mode").equals("missing")) ids.add("request/" + id);
            }
            return rows(ids, projection);
        });
    }
    @Override public String getDocumentType(String id) throws FileNotFoundException {
        return document(() -> {
            Request request = parsedDocument(id);
            if (request != null) { JSONObject state = read(request.id); if (!request.directory) available(state); }
            else if (!id.equals("root") && !MODES.contains(id)) throw new FileNotFoundException("Unknown synthetic document");
            return id.equals("root") || request != null && request.directory ? Document.MIME_TYPE_DIR : request == null ? "image/jpeg" : "application/octet-stream";
        });
    }
    @Override public String createDocument(String parent, String mimeType, String displayName) throws FileNotFoundException {
        return document(() -> {
            Request request = parsedDocument(parent);
            if (request == null) throw new FileNotFoundException("Only configured synthetic request parents support creation");
            require(request.directory, "Creation requires a parent"); JSONObject state = read(request.id);
            require(!state.getJSONObject("config").getBoolean("preExisting") && !state.getBoolean("created"), "Single new synthetic output only");
            state.put("created", true); event(request.id, state, "create"); return "request/" + request.id;
        });
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        document(() -> {
            Request request = parsedDocument(id); if (request == null) throw new FileNotFoundException("Read-only synthetic fixture");
            require(!request.directory, "Cannot delete request parent"); JSONObject state = read(request.id);
            state.put("deleteCalls", state.getInt("deleteCalls") + 1);
            event(request.id, state, "deleteAttempt", new JSONObject().put("preExisting", state.getJSONObject("config").getBoolean("preExisting")));
            available(state);
            if (state.getJSONObject("config").getBoolean("deleteFails")) throw new FileNotFoundException("Configured synthetic delete failure");
            if (!payload(request.id).delete() && payload(request.id).exists()) throw new IllegalStateException("Cannot delete payload");
            state.put("deleted", true); event(request.id, state, "deleted"); return null;
        });
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        // Opening a FUSE descriptor can synchronously dispatch onGetSize. Do not
        // hold the callback's state lock while waiting for the system open call.
        return documentIo(() -> {
            if (signal != null) signal.throwIfCanceled(); Request request = parsedDocument(id);
            if (request != null) { require(!request.directory, "Cannot open request parent"); return openRequest(request.id, mode, signal); }
            if (id.equals("revoked")) throw new SecurityException("Synthetic permission revoked");
            if (!MODES.contains(id)) throw new FileNotFoundException("Unknown synthetic destination");
            if (mode.contains("w")) {
                require(id.equals("enospc") || id.equals("short-write"), "Read-only synthetic fixture");
                return storageManager.openProxyFileDescriptor(
                        ParcelFileDescriptor.MODE_WRITE_ONLY, new ProxyFileDescriptorCallback() {
                            private long written;
                            @Override public long onGetSize() { return written; }
                            @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                                if (signal != null) signal.throwIfCanceled();
                                if (id.equals("enospc") || written >= 32) throw new ErrnoException("synthetic bounded write", id.equals("enospc") ? OsConstants.ENOSPC : OsConstants.EIO);
                                int count = Math.min(Math.min(size, 8), (int) (32 - written)); written += count; return count;
                            }
                            @Override public void onFsync() { }
                            @Override public void onRelease() { }
                        }, new Handler(thread.getLooper()));
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            new Thread(() -> {
                try {
                    byte[] data;
                    try (InputStream input = java.util.Objects.requireNonNull(getContext()).getAssets().open("relief/view-01.jpg")) { data = bytes(input); }
                    try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        int limit = id.equals("truncated") ? 64 : data.length, offset = 0;
                        while (offset < limit) {
                            if (signal != null) signal.throwIfCanceled();
                            int count = Math.min(1024, limit - offset); output.write(data, offset, count); offset += count;
                            if (id.equals("slow")) Thread.sleep(10);
                        }
                    }
                } catch (Exception error) { try { pipe[1].closeWithError(error.getClass().getSimpleName()); } catch (java.io.IOException ignored) { } }
            }, "synthetic-document-reader").start();
            return pipe[0];
        });
    }
    private void releaseHandle(String id) {
        int remaining = handles.getOrDefault(id, 1) - 1;
        if (remaining <= 0) handles.remove(id); else handles.put(id, remaining);
    }
    private ParcelFileDescriptor openRequest(String id, String mode, CancellationSignal signal) throws Exception {
        require(Arrays.asList("r", "w", "wt").contains(mode), "Bounded fixtures support r/w/wt only");
        boolean writing = !mode.equals("r");
        synchronized (lock) {
            JSONObject state = read(id); available(state); JSONObject config = state.getJSONObject("config");
            if (state.getBoolean("revoked") || !writing && config.getString("mode").equals("revoke-readback") && state.getInt("writeOpens") > 0) {
                event(id, state, "openPermissionDenied"); throw new SecurityException("Configured synthetic permission revoked");
            }
            if (!writing && !config.getString("verification").equals("readback")) {
                event(id, state, "readbackUnsupported"); throw new FileNotFoundException("Synthetic destination has no readback capability");
            }
            require(handles.getOrDefault(id, 0) < 4, "Synthetic descriptor bound reached");
            if (writing) {
                try (RandomAccessFile file = new RandomAccessFile(payload(id), "rw")) { file.setLength(0); file.getFD().sync(); }
                state.put("writeOpens", state.getInt("writeOpens") + 1);
            } else state.put("readOpens", state.getInt("readOpens") + 1);
            event(id, state, writing ? "openWrite" : "openRead"); handles.put(id, handles.getOrDefault(id, 0) + 1);
        }
        try {
            return storageManager.openProxyFileDescriptor(
                    writing ? ParcelFileDescriptor.MODE_WRITE_ONLY : ParcelFileDescriptor.MODE_READ_ONLY, new ProxyFileDescriptorCallback() {
                        @Override public long onGetSize() throws ErrnoException { return proxy(() -> payload(id).length()); }
                        @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                            return proxy(() -> {
                                if (signal != null) signal.throwIfCanceled(); JSONObject current = read(id);
                                if (current.getBoolean("revoked")) {
                                    event(id, current, "descriptorPermissionDenied"); throw new ErrnoException("synthetic revoked descriptor", OsConstants.EACCES);
                                }
                                require(offset >= 0 && size >= 0 && size <= data.length, "Invalid write range");
                                JSONObject options = current.getJSONObject("config"); String fault = options.getString("mode");
                                long threshold = Arrays.asList("silent-truncate", "enospc", "edquot").contains(fault) ? options.getLong("limitBytes") : MAX_BYTES;
                                if (!fault.equals("silent-truncate") && offset >= threshold || current.getLong("acceptedBytes") + size > 16L * MAX_BYTES) {
                                    int errno = fault.equals("edquot") ? OsConstants.EDQUOT : OsConstants.ENOSPC;
                                    event(id, current, "writeFault", new JSONObject().put("errno", errno)); throw new ErrnoException("bounded synthetic quota", errno);
                                }
                                int count = (int) Math.min(size, Math.max(0L, threshold - offset));
                                if (count > 0) try (RandomAccessFile file = new RandomAccessFile(payload(id), "rw")) {
                                    file.seek(offset); file.write(data, 0, count); file.getFD().sync();
                                }
                                int accepted = fault.equals("silent-truncate") ? size : count;
                                current.put("writeCalls", current.getInt("writeCalls") + 1).put("acceptedBytes", current.getLong("acceptedBytes") + accepted)
                                        .put("storedWriteBytes", current.getLong("storedWriteBytes") + count);
                                event(id, current, "write", new JSONObject().put("offset", offset).put("acceptedBytes", accepted).put("storedBytes", count));
                                long delay = options.getLong("delayMillis"); if (delay > 0) Thread.sleep(delay); return accepted;
                            });
                        }
                        @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                            return proxy(() -> {
                                if (signal != null) signal.throwIfCanceled(); JSONObject current = read(id);
                                if (current.getBoolean("revoked")) {
                                    event(id, current, "descriptorPermissionDenied"); throw new ErrnoException("synthetic revoked descriptor", OsConstants.EACCES);
                                }
                                require(offset >= 0 && size >= 0 && size <= data.length, "Invalid read range"); int count;
                                try (RandomAccessFile file = new RandomAccessFile(payload(id), "r")) { file.seek(offset); count = Math.max(0, file.read(data, 0, size)); }
                                event(id, current, "read", new JSONObject().put("offset", offset).put("bytes", count)); return count;
                            });
                        }
                        @Override public void onFsync() throws ErrnoException {
                            proxy(() -> { try (RandomAccessFile file = new RandomAccessFile(payload(id), "rw")) { file.getFD().sync(); }
                                event(id, read(id), "fsync"); return null; });
                        }
                        @Override public void onRelease() {
                            synchronized (lock) { unchecked(() -> { releaseHandle(id); event(id, read(id), "close"); return null; }); }
                        }
                    }, new Handler(thread.getLooper()));
        } catch (Exception error) { synchronized (lock) { releaseHandle(id); } throw error; }
    }
}
