package com.realityforge.scanner.agents.a011;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Test APK only: fixed synthetic bytes with deliberately false MIME/size and revoked access.
 * This manifest-instantiated provider must use only Android/Java platform classes: its independent
 * process cannot load Kotlin dependencies that AGP deduplicates into the target application APK.
 */
public final class FixturePhotoProvider extends ContentProvider {
    public static final String AUTHORITY = "com.realityforge.scanner.test.a011.photo-fixture";

    @Override public boolean onCreate() { return true; }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read-only fixture");
        final String key = fixtureKey(uri);
        if ("revoked".equals(key)) throw new SecurityException("Synthetic provider revoked this document");
        return unchecked(() -> {
            if (getContext() == null) throw new IllegalArgumentException("Provider context is missing");
            File folder = new File(getContext().getCacheDir(), "a011-provider-fixtures");
            if (!folder.isDirectory() && !folder.mkdir()) throw new IllegalStateException("Cannot create fixture directory");
            File file = new File(folder, key + ".fixture");
            if (!file.exists()) {
                if ("corrupt".equals(key)) {
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        output.write("This is not an encoded photograph.".getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    int edge = "large-png".equals(key) ? 1024 : 80;
                    int height = "large-png".equals(key) ? 1024 : 64;
                    Bitmap bitmap = Bitmap.createBitmap(edge, height, Bitmap.Config.ARGB_8888);
                    try {
                        int state = 0x13579bdf;
                        int[] pixels = new int[edge * height];
                        for (int i = 0; i < pixels.length; i++) {
                            state ^= state << 13;
                            state ^= state >>> 17;
                            state ^= state << 5;
                            pixels[i] = 0xff000000 | (state & 0x00ffffff);
                        }
                        bitmap.setPixels(pixels, 0, edge, 0, 0, edge, height);
                        try (FileOutputStream output = new FileOutputStream(file)) {
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                                throw new IllegalStateException("Synthetic PNG encoding failed");
                        }
                    } finally { bitmap.recycle(); }
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        });
    }

    @Override public String getType(Uri uri) {
        return unchecked(() -> { fixtureKey(uri); return "image/jpeg"; });
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return unchecked(() -> {
            fixtureKey(uri);
            String[] columns = projection == null ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "misleading-name.jpg";
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = Long.MAX_VALUE;
            }
            cursor.addRow(row);
            return cursor;
        });
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only fixture"); }

    private String fixtureKey(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        String key = segments.size() == 1 ? segments.get(0) : null;
        if (!AUTHORITY.equals(uri.getAuthority()) || !("pattern-png".equals(key) || "large-png".equals(key)
                || "corrupt".equals(key) || "revoked".equals(key)))
            throw new FileNotFoundException("Unknown synthetic fixture");
        return key;
    }

    @FunctionalInterface private interface IoWork<T> { T run() throws Exception; }
    // Kotlin did not wrap checked failures at these framework boundaries. Preserve their exact
    // runtime type rather than converting provider I/O into a misleading success or generic error.
    private static <T> T unchecked(IoWork<T> work) {
        try { return work.run(); } catch (Exception failure) { return throwUnchecked(failure); }
    }
    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }
}
