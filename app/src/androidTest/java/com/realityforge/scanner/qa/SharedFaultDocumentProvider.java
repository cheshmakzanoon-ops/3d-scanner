package com.realityforge.scanner.qa;

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
import android.os.Process;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared synthetic SAF fault provider. The independent test-APK process has only platform
 * dependencies; instrumentation-only clients live separately in SharedFaultDocumentFixtures.
 * Permissions, real proxy short writes/ENOSPC, readback, revocation and cleanup stay unchanged.
 */
public final class SharedFaultDocumentProvider extends DocumentsProvider {
    public enum Mode { EXACT, SHORT_WRITES, SILENT_TRUNCATION, DISK_FULL, REVOKED, REVOKE_AFTER_WRITE, UNAVAILABLE }
    public static final String AUTHORITY = "com.realityforge.scanner.test.shared.fault-documents";
    private static final String ROOT_ID = "fixtures";
    private static final String CONTROL_PREFIX = "rf-fixture:";
    private static final String CONTROL_ACTION = "com.realityforge.scanner.test.CONTROL_FAULT_DOCUMENT";
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final long DEFAULT_QUOTA = 12L * 1024;
    private static final long MAX_DOCUMENT_BYTES = 32L * 1024 * 1024;
    private static final int MAX_INSPECTION_BYTES = 512 * 1024;
    private static final int MAX_FIXTURES = 128;
    private static final String[] ROOT_COLUMNS = { Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES };
    private static final String[] DOCUMENT_COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE };

    private static final class Fixture {
        final String id; final File file; final Mode mode; final long quota;
        long writeCalls; long shortWriteCalls; boolean deleted;
        Fixture(String id, File file, Mode mode, long quota) {
            this.id = id; this.file = file; this.mode = mode; this.quota = quota;
        }
    }
    private final ConcurrentHashMap<String, Fixture> fixtures = new ConcurrentHashMap<>();
    private File root;
    private HandlerThread ioThread;
    private Handler ioHandler;

    @Override public boolean onCreate() {
        root = new File(nonNull(getContext()).getCacheDir(), "shared-saf-faults");
        check(root.isDirectory() || root.mkdir(), "Cannot create generated fixture directory");
        ioThread = new HandlerThread("shared-saf-proxy");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        return true;
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        addIfPresent(cursor, row, Root.COLUMN_ROOT_ID, ROOT_ID);
        addIfPresent(cursor, row, Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        addIfPresent(cursor, row, Root.COLUMN_TITLE, "Synthetic SAF fault fixtures");
        addIfPresent(cursor, row, Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
        addIfPresent(cursor, row, Root.COLUMN_AVAILABLE_BYTES, root.getUsableSpace());
        addIfPresent(cursor, row, Root.COLUMN_MIME_TYPES, "application/octet-stream");
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        includeDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        require(ROOT_ID.equals(parentDocumentId), "Unknown fixture root");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        ArrayList<Fixture> active = new ArrayList<>();
        for (Fixture fixture : fixtures.values()) if (!fixture.deleted) active.add(fixture);
        active.sort(Comparator.comparing(fixture -> fixture.id));
        for (int i = 0; i < Math.min(active.size(), MAX_FIXTURES); i++) includeDocument(cursor, active.get(i).id);
        return cursor;
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        require(ROOT_ID.equals(parentDocumentId) && displayName.length() >= 1 && displayName.length() <= 128
            && "application/octet-stream".equals(mimeType), "Unsupported generated document");
        return unchecked(() -> createFixture(Mode.EXACT, new byte[0], DEFAULT_QUOTA).id);
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        Fixture fixture = fixtures.get(documentId);
        if (fixture == null) throw new FileNotFoundException("Unknown generated fixture");
        synchronized (fixture) {
            if (fixture.file.exists() && !fixture.file.delete()) throw new FileNotFoundException("Fixture cleanup failed");
            fixture.deleted = true;
        }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        final Fixture fixture = existing(documentId);
        if (fixture.mode == Mode.UNAVAILABLE) throw new FileNotFoundException("Generated unavailable document");
        if (fixture.mode == Mode.REVOKED) throw new SecurityException("Generated revoked document");
        if ("r".equals(mode)) return ParcelFileDescriptor.open(fixture.file, ParcelFileDescriptor.MODE_READ_ONLY);
        require("w".equals(mode) || "wt".equals(mode), "Fixture only supports streaming read/write");
        return unchecked(() -> {
            final RandomAccessFile output = new RandomAccessFile(fixture.file, "rw");
            output.setLength(0);
            ProxyFileDescriptorCallback callback = new ProxyFileDescriptorCallback() {
                @Override public long onGetSize() {
                    synchronized (fixture) { return unchecked(output::length); }
                }
                @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                    synchronized (fixture) {
                        if (fixture.deleted) throw new ErrnoException("write deleted fixture", OsConstants.ENOENT);
                        if (offset < 0 || size < 0 || size > data.length || offset > MAX_DOCUMENT_BYTES - size)
                            throw new ErrnoException("bounded fixture write", OsConstants.EFBIG);
                        fixture.writeCalls++;
                        if (fixture.mode == Mode.DISK_FULL && offset >= fixture.quota)
                            throw new ErrnoException("generated quota exhausted", OsConstants.ENOSPC);
                        final int written;
                        switch (fixture.mode) {
                            case SHORT_WRITES:
                                written = Math.min(size, 1024);
                                if (written < size) fixture.shortWriteCalls++;
                                break;
                            case DISK_FULL: case SILENT_TRUNCATION:
                                written = (int) Math.min((long) size, Math.max(0L, fixture.quota - offset));
                                break;
                            default: written = size;
                        }
                        return unchecked(() -> {
                            output.seek(offset);
                            if (written > 0) output.write(data, 0, written);
                            if (fixture.mode == Mode.REVOKE_AFTER_WRITE && fixture.writeCalls == 1L)
                                nonNull(getContext()).revokeUriPermission(documentUri(documentId), GRANT_FLAGS);
                            return fixture.mode == Mode.SILENT_TRUNCATION ? size : written;
                        });
                    }
                }
                @Override public void onFsync() {
                    synchronized (fixture) { unchecked(() -> { output.getFD().sync(); return null; }); }
                }
                @Override public void onRelease() {
                    synchronized (fixture) { unchecked(() -> { output.close(); return null; }); }
                }
            };
            try {
                return nonNull(getContext()).getSystemService(StorageManager.class)
                    .openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY, callback, ioHandler);
            } catch (Exception failure) {
                output.close();
                throw failure;
            }
        });
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle framework = super.call(method, arg, extras);
        if (framework != null) return framework;
        if (!method.startsWith(CONTROL_PREFIX)) return null;
        check(Binder.getCallingUid() == Process.myUid(), "Fixture control is available only to its test APK owner");
        return unchecked(() -> {
            switch (method.substring(CONTROL_PREFIX.length())) {
                case "create": {
                    Bundle fields = nonNull(extras);
                    String packageName = nonNull(fields.getString("grantPackage"));
                    Context owner = nonNull(getContext());
                    check(owner.getPackageManager().checkSignatures(owner.getPackageName(), packageName)
                        == PackageManager.SIGNATURE_MATCH, "Fixture grant requires matching signatures");
                    byte[] initial = fields.getByteArray("initialBytes");
                    Fixture fixture = createFixture(Mode.valueOf(nonNull(fields.getString("mode"))),
                        initial == null ? new byte[0] : initial, fields.getLong("quota", DEFAULT_QUOTA));
                    Uri uri = documentUri(fixture.id);
                    owner.grantUriPermission(packageName, uri, GRANT_FLAGS);
                    if (fixture.mode == Mode.REVOKED) owner.revokeUriPermission(uri, GRANT_FLAGS);
                    Bundle result = new Bundle(); result.putString("uri", uri.toString()); return result;
                }
                case "inspect": {
                    Fixture fixture = fixtures.get(nonNull(arg));
                    if (fixture == null) throw new FileNotFoundException("Unknown fixture");
                    synchronized (fixture) {
                        Bundle result = new Bundle();
                        result.putBoolean("exists", fixture.file.isFile() && !fixture.deleted);
                        result.putLong("bytes", fixture.file.isFile() ? fixture.file.length() : 0);
                        result.putLong("writeCalls", fixture.writeCalls);
                        result.putLong("shortWriteCalls", fixture.shortWriteCalls);
                        if (fixture.file.isFile()) result.putString("sha256", sha256(fixture.file));
                        return result;
                    }
                }
                case "read": {
                    Fixture fixture = existing(nonNull(arg));
                    synchronized (fixture) {
                        check(fixture.file.length() <= MAX_INSPECTION_BYTES, "Fixture exceeds inspection bound");
                        Bundle result = new Bundle(); result.putByteArray("contents", readBytes(fixture.file)); return result;
                    }
                }
                case "cleanup": {
                    String id = nonNull(arg);
                    if (fixtures.get(id) != null) { deleteDocument(id); fixtures.remove(id); }
                    nonNull(getContext()).revokeUriPermission(documentUri(id), GRANT_FLAGS);
                    return new Bundle();
                }
                default: throw new IllegalArgumentException("Unknown fixture control");
            }
        });
    }

    private Fixture createFixture(Mode mode, byte[] initial, long quota) throws Exception {
        require(initial.length <= MAX_INSPECTION_BYTES && quota >= 1 && quota <= MAX_DOCUMENT_BYTES
            && fixtures.size() < MAX_FIXTURES, "Generated fixture exceeds bounds");
        String id = UUID.randomUUID().toString();
        File file = new File(root, id);
        check(file.createNewFile(), "Fixture file already exists");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(initial); }
        Fixture fixture = new Fixture(id, file, mode, quota);
        check(fixtures.putIfAbsent(id, fixture) == null, "Fixture identity collision");
        return fixture;
    }

    private Fixture existing(String id) throws FileNotFoundException {
        Fixture fixture = fixtures.get(id);
        if (fixture == null || fixture.deleted || !fixture.file.isFile()) throw new FileNotFoundException("Fixture is missing");
        return fixture;
    }

    private void includeDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        File file = ROOT_ID.equals(id) ? null : existing(id).file;
        MatrixCursor.RowBuilder row = cursor.newRow();
        addIfPresent(cursor, row, Document.COLUMN_DOCUMENT_ID, id);
        addIfPresent(cursor, row, Document.COLUMN_DISPLAY_NAME, file == null ? "Synthetic SAF fixtures" : id + ".fixture");
        addIfPresent(cursor, row, Document.COLUMN_MIME_TYPE, file == null ? Document.MIME_TYPE_DIR : "application/octet-stream");
        addIfPresent(cursor, row, Document.COLUMN_FLAGS,
            file == null ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE);
        addIfPresent(cursor, row, Document.COLUMN_SIZE, file == null ? null : file.length());
    }
    private static void addIfPresent(MatrixCursor cursor, MatrixCursor.RowBuilder row, String name, Object value) {
        if (cursor.getColumnIndex(name) >= 0) row.add(name, value);
    }
    private static Uri documentUri(String id) { return DocumentsContract.buildDocumentUri(AUTHORITY, id); }

    /** DUMP-protected test-only receiver; its manifest authority/permissions are unchanged. */
    public static final class ControlReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            long token = Binder.clearCallingIdentity();
            try {
                require(CONTROL_ACTION.equals(intent.getAction()), "Unknown fixture control action");
                Bundle answer = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY),
                    CONTROL_PREFIX + nonNull(intent.getStringExtra("method")), intent.getStringExtra("id"), intent.getBundleExtra("fields"));
                Bundle result = new Bundle(); result.putBoolean("ok", true); result.putBundle("result", answer);
                setResultExtras(result);
            } catch (Exception failure) {
                Bundle result = new Bundle(); result.putBoolean("ok", false); result.putString("error", failure.toString());
                setResultExtras(result);
            } finally { Binder.restoreCallingIdentity(token); }
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            while (true) { int n = input.read(buffer); if (n < 0) break; if (n > 0) output.write(buffer, 0, n); }
            return output.toByteArray();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) { int n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n); }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            int unsigned = value & 255;
            result.append("0123456789abcdef".charAt(unsigned >>> 4));
            result.append("0123456789abcdef".charAt(unsigned & 15));
        }
        return result.toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static <T> T nonNull(T value) { if (value == null) throw new IllegalArgumentException("Required value was null."); return value; }
    @FunctionalInterface private interface IoWork<T> { T run() throws Exception; }
    // Preserve the prior Kotlin implementation's exception type across framework callback methods.
    private static <T> T unchecked(IoWork<T> work) {
        try { return work.run(); } catch (Exception failure) { return throwUnchecked(failure); }
    }
    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }
}
