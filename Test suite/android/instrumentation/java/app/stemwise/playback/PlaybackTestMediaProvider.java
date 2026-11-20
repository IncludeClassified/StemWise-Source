package app.stemwise.playback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import app.stemwise.BuildConfig;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Seekable, test-APK-owned PCM source; it never creates a selected-library document. */
public final class PlaybackTestMediaProvider extends ContentProvider {
    public static final Uri MEDIA_URI =
            Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".test.playback.media/session.wav");
    public static final Uri BLOCKING_MEDIA_URI =
            Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".test.playback.media/blocked.wav");
    public static final Uri WAVPACK_SLICE_URI =
            Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".test.playback.media/remaining-slice.wv");
    public static final Uri FLAC_SLICE_URI =
            Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".test.playback.media/original-slice.flac");
    public static final String FLAC_SLICE_FILE_NAME = "native-decoder-flac-slice.bin";
    public static final String WAVPACK_SLICE_FILE_NAME = "native-decoder-wavpack-slice.bin";
    public static final int SLICE_OFFSET = 37;
    public static final int SLICE_TRAILING_BYTES = 19;
    public static final String BLOCKING_OPEN_STATUS = "entered";
    private static final String MEDIA_FILE_NAME = "session-durability.wav";
    private static final int SAMPLE_RATE = 8_000;
    private static final int SAMPLE_COUNT = SAMPLE_RATE * 8;
    private static final int DATA_BYTES = SAMPLE_COUNT * 2;
    private static final long EXPECTED_FILE_BYTES = 44L + DATA_BYTES;
    private static volatile boolean blockingOpenEntered;

    @Override
    public boolean onCreate() {
        File file = mediaFile();
        if (!file.isFile() || file.length() != EXPECTED_FILE_BYTES) {
            try {
                writeSilentWave(file);
            } catch (IOException error) {
                return false;
            }
        }
        return file.isFile() && file.length() == EXPECTED_FILE_BYTES;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!MEDIA_URI.equals(uri) || !"r".equals(mode)) {
            throw new FileNotFoundException("Unsupported test-media request");
        }
        return ParcelFileDescriptor.open(mediaFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public AssetFileDescriptor openAssetFile(
            Uri uri,
            String mode,
            CancellationSignal signal
    ) throws FileNotFoundException {
        if (WAVPACK_SLICE_URI.equals(uri) || FLAC_SLICE_URI.equals(uri)) {
            if (!"r".equals(mode)) throw new FileNotFoundException("Unsupported test-media request");
            if (signal != null) signal.throwIfCanceled();
            boolean flac = FLAC_SLICE_URI.equals(uri);
            File file = new File(getContext().getCacheDir(), flac ? FLAC_SLICE_FILE_NAME : WAVPACK_SLICE_FILE_NAME);
            if (!file.isFile()) {
                try (java.io.InputStream input = getContext().getAssets().open(
                        flac ? "encoder/flac24-stereo-96000.flac" : "encoder/remaining-float32.wv")) {
                    File parent = file.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Fixture directory unavailable");
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        output.write(new byte[SLICE_OFFSET]);
                        byte[] buffer = new byte[8192];
                        for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                        output.write(new byte[SLICE_TRAILING_BYTES]);
                    }
                } catch (IOException error) {
                    throw new FileNotFoundException("Compressed test slice unavailable: " + error.getMessage());
                }
            }
            long length = file.length() - SLICE_OFFSET - SLICE_TRAILING_BYTES;
            if (!file.isFile() || length < 32) throw new FileNotFoundException("Compressed test slice missing");
            return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                    SLICE_OFFSET, length);
        }
        if (!BLOCKING_MEDIA_URI.equals(uri)) return super.openAssetFile(uri, mode, signal);
        if (!"r".equals(mode)) throw new FileNotFoundException("Unsupported test-media request");
        blockingOpenEntered = true;
        try {
            Object gate = new Object();
            signal.setOnCancelListener(() -> {
                synchronized (gate) {
                    gate.notifyAll();
                }
            });
            synchronized (gate) {
                while (!signal.isCanceled()) gate.wait();
            }
            throw new OperationCanceledException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException();
        } finally {
            blockingOpenEntered = false;
        }
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(
            Uri uri,
            String mimeTypeFilter,
            Bundle options,
            CancellationSignal signal
    ) throws FileNotFoundException {
        if (BLOCKING_MEDIA_URI.equals(uri) ||
                WAVPACK_SLICE_URI.equals(uri) || FLAC_SLICE_URI.equals(uri)) {
            return openAssetFile(uri, "r", signal);
        }
        return super.openTypedAssetFile(uri, mimeTypeFilter, options, signal);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        if (BLOCKING_MEDIA_URI.equals(uri)) {
            MatrixCursor cursor = new MatrixCursor(new String[] { BLOCKING_OPEN_STATUS });
            cursor.addRow(new Object[] { blockingOpenEntered ? 1 : 0 });
            return cursor;
        }
        if (!MEDIA_URI.equals(uri)) throw new IllegalArgumentException("Unknown test media");
        String[] columns = projection != null
                ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(MEDIA_FILE_NAME);
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(EXPECTED_FILE_BYTES);
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        if (FLAC_SLICE_URI.equals(uri)) return "audio/flac";
        if (WAVPACK_SLICE_URI.equals(uri)) return "audio/wavpack";
        return "audio/wav";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (WAVPACK_SLICE_URI.equals(uri) || FLAC_SLICE_URI.equals(uri)) {
            File file = new File(getContext().getCacheDir(),
                    FLAC_SLICE_URI.equals(uri) ? FLAC_SLICE_FILE_NAME : WAVPACK_SLICE_FILE_NAME);
            if (!file.exists()) return 0;
            if (!file.delete()) throw new IllegalStateException("Unable to delete compressed test slice");
            return 1;
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File mediaFile() {
        return new File(getContext().getCacheDir(), MEDIA_FILE_NAME);
    }

    private void writeSilentWave(File file) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
            output.writeBytes("RIFF");
            output.writeInt(Integer.reverseBytes(36 + DATA_BYTES));
            output.writeBytes("WAVEfmt ");
            output.writeInt(Integer.reverseBytes(16));
            output.writeShort(Short.reverseBytes((short) 1));
            output.writeShort(Short.reverseBytes((short) 1));
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE));
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2));
            output.writeShort(Short.reverseBytes((short) 2));
            output.writeShort(Short.reverseBytes((short) 16));
            output.writeBytes("data");
            output.writeInt(Integer.reverseBytes(DATA_BYTES));
            for (int index = 0; index < SAMPLE_COUNT; index++) output.writeShort(0);
        }
    }
}
