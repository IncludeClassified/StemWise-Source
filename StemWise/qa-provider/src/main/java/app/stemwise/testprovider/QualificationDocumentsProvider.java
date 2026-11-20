package app.stemwise.testprovider;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Cross-UID, disposable SAF tree for current-package isolation and restart qualification. */
public final class QualificationDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.stemwise.testprovider.documents";
    public static final String ROOT_ID = "root";
    public static final String ALTERNATE_ROOT_ID = "alternate";
    public static final String LIBRARY_ID = "9e720909-1ce8-4f22-a02c-cf5da87041de";
    public static final String ALTERNATE_LIBRARY_ID = "3f0f14ec-774e-44b9-95a4-0b4e087e8cd7";
    public static final Uri TREE_URI =
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    public static final Uri ALTERNATE_TREE_URI =
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, ALTERNATE_ROOT_ID);
    private static final String[] FIXTURE_IDS = {
            "DEMOUI00001", "DEMOUI00002", "DEMOUI00003"
    };
    private static final String[] ALLOWED_FILES = {
            "original.opus", "cover.png", "song.json", ".stemwise-sources.json",
            "part-1.flac", "part-2.flac", "part-3.flac", "part-4.flac",
            "part-5.flac", "part-6.flac", "part-7.flac", "part-8.flac", "remaining.wv"
    };
    private static final String[] ROOT_PROJECTION = {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DOCUMENT_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
    };

    @Override
    public boolean onCreate() {
        try {
            File seal = new File(
                    java.util.Objects.requireNonNull(getContext()).getFilesDir(),
                    "qualification-provider-v3.sealed"
            );
            QualificationSeedPublisher.ensurePublished(providerBaseDirectory(getContext()), seal, this::seedMissingFixtures);
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not seed qualification provider", failure);
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(columns(projection, ROOT_PROJECTION));
        includeRoot(cursor, ROOT_ID, "StemWise qualification Library A");
        includeRoot(cursor, ALTERNATE_ROOT_ID, "StemWise qualification Library B");
        return cursor;
    }

    private void includeRoot(MatrixCursor cursor, String rootId, String title) {
        cursor.newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, rootId)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, rootId)
                .add(DocumentsContract.Root.COLUMN_TITLE, title)
                .add(DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_LOCAL_ONLY
                                | DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                .add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                        rootDirectory(rootId).getUsableSpace());
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(columns(projection, DOCUMENT_PROJECTION));
        includeDocument(cursor, documentId, requireDocument(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File parent = requireDocument(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException(parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(columns(projection, DOCUMENT_PROJECTION));
        File[] children = parent.listFiles();
        if (children == null) throw new FileNotFoundException(parentDocumentId);
        java.util.Arrays.sort(children, java.util.Comparator.comparing(File::getName));
        for (File child : children) includeDocument(cursor, documentId(child), child);
        return cursor;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        requireSafeName(displayName);
        File parent = requireDocument(parentDocumentId);
        File child = new File(parent, displayName);
        requireInsideRoot(child);
        try {
            boolean created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
                    ? child.mkdir() : child.createNewFile();
            if (!created) throw new FileNotFoundException("Could not create " + displayName);
        } catch (IOException failure) {
            throw new FileNotFoundException(failure.getMessage());
        }
        return documentId(child);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File target = requireDocument(documentId);
        if (QualificationDocumentPaths.isRoot(target, rootDirectory(ROOT_ID), rootDirectory(ALTERNATE_ROOT_ID))) throw new FileNotFoundException("Cannot delete root");
        if (!deleteRecursively(target)) throw new FileNotFoundException("Could not delete " + documentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = requireDocument(documentId);
        if (!file.isFile()) throw new FileNotFoundException(documentId);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeType(requireDocument(documentId));
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = requireDocument(parentDocumentId);
            File child = requireDocument(documentId);
            return !parent.equals(child)
                    && child.getPath().startsWith(parent.getPath() + File.separator);
        } catch (FileNotFoundException ignored) {
            return false;
        }
    }

    public static File rootDirectory(android.content.Context context) {
        return rootDirectory(context, ROOT_ID);
    }

    public static File providerBaseDirectory(android.content.Context context) {
        try {
            return new File(context.getNoBackupFilesDir(), "qualification-provider-v2")
                    .getCanonicalFile();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static File rootDirectory(android.content.Context context, String rootId) {
        if (!ROOT_ID.equals(rootId) && !ALTERNATE_ROOT_ID.equals(rootId)) {
            throw new IllegalArgumentException("Unknown root " + rootId);
        }
        try {
            return new File(providerBaseDirectory(context), rootId).getCanonicalFile();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private File rootDirectory() {
        return rootDirectory(java.util.Objects.requireNonNull(getContext()));
    }

    private File rootDirectory(String rootId) {
        return rootDirectory(java.util.Objects.requireNonNull(getContext()), rootId);
    }

    private void seedMissingFixtures(File base) throws IOException {
        if (!base.mkdirs() && !base.isDirectory()) throw new IOException("Could not create base");
        seedRoot(base, ROOT_ID, LIBRARY_ID);
        seedRoot(base, ALTERNATE_ROOT_ID, ALTERNATE_LIBRARY_ID);
    }

    private void seedRoot(File base, String rootId, String libraryId)
            throws IOException {
        File root = new File(base, rootId);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Could not create root");
        {
            byte[] manifestBytes = ("{\"schemaVersion\":1,\"kind\":\"stemwise.library-root\","
                    + "\"libraryId\":\"" + libraryId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            for (String name : new String[]{
                    ".stemwise-library.json", ".stemwise-library.json.bak"
            }) {
                File manifest = new File(root, name);
                if (!manifest.isFile()) writeAtomically(manifest, manifestBytes);
            }
        }
        for (String videoId : FIXTURE_IDS) {
            File folder = new File(root, videoId);
            if (!folder.mkdir() && !folder.isDirectory()) throw new IOException(videoId);
            for (String name : ALLOWED_FILES) {
                File target = new File(folder, name);
                if (target.isFile()) continue;
                try (InputStream input = java.util.Objects.requireNonNull(getContext())
                        .getAssets().open("demo_library/" + videoId + "/"
                                + (".stemwise-sources.json".equals(name) ? "sources.json" : name))) {
                    File pending = new File(folder, "." + name + ".pending");
                    try (FileOutputStream output = new FileOutputStream(pending)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (count > 0) output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                    if (!pending.renameTo(target)) throw new IOException("Could not publish " + name);
                }
            }
        }
    }

    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        File pending = new File(target.getParentFile(), "." + target.getName() + ".pending");
        try (FileOutputStream output = new FileOutputStream(pending)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (!pending.renameTo(target)) throw new IOException("Could not publish " + target);
    }

    private void includeDocument(MatrixCursor cursor, String id, File file) {
        boolean directory = file.isDirectory();
        cursor.newRow()
                .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        ROOT_ID.equals(id) ? "StemWise qualification Library" : file.getName())
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
                .add(DocumentsContract.Document.COLUMN_FLAGS,
                        directory
                                ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                : DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
                .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
                .add(DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
    }

    private File requireDocument(String documentId) throws FileNotFoundException {
        String rootId = rootId(documentId);
        return QualificationDocumentPaths.requireDocument(rootDirectory(rootId), rootId, documentId);
    }

    private void requireInsideRoot(File file) throws FileNotFoundException {
        try {
            File canonical = file.getCanonicalFile();
            if (rootContaining(canonical) == null) {
                throw new FileNotFoundException("Document escaped root");
            }
        } catch (IOException failure) {
            throw new FileNotFoundException(failure.getMessage());
        }
    }

    private static void requireSafeName(String name) throws FileNotFoundException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new FileNotFoundException("Unsafe display name");
        }
    }

    private String documentId(File file) throws FileNotFoundException {
        try {
            File canonical = file.getCanonicalFile();
            File root = rootContaining(canonical);
            if (root == null) throw new FileNotFoundException("Document escaped root");
            String rootId = root.getName();
            if (canonical.equals(root)) return rootId;
            return rootId + "/" + root.toPath().relativize(canonical.toPath()).toString()
                    .replace(File.separatorChar, '/');
        } catch (IOException failure) {
            throw new FileNotFoundException(failure.getMessage());
        }
    }

    private String rootId(String documentId) throws FileNotFoundException {
        for (String value : new String[]{ROOT_ID, ALTERNATE_ROOT_ID}) {
            if (value.equals(documentId) || documentId.startsWith(value + "/")) return value;
        }
        throw new FileNotFoundException(documentId);
    }

    private File rootContaining(File canonical) throws IOException {
        for (String rootId : new String[]{ROOT_ID, ALTERNATE_ROOT_ID}) {
            File root = rootDirectory(rootId);
            if (canonical.equals(root)
                    || canonical.getPath().startsWith(root.getPath() + File.separator)) return root;
        }
        return null;
    }

    private static boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        return file.delete();
    }

    private static String mimeType(File file) {
        if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        if (file.getName().startsWith(".stemwise-") && file.getName().contains(".json")) {
            return "application/json";
        }
        String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName()).toLowerCase();
        if ("json".equals(extension)) return "application/json";
        if ("png".equals(extension)) return "image/png";
        if ("flac".equals(extension)) return "audio/flac";
        if ("wv".equals(extension)) return "audio/wavpack";
        if ("opus".equals(extension) || "ogg".equals(extension)) return "audio/ogg";
        String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return value == null ? "application/octet-stream" : value;
    }

    private static String[] columns(String[] requested, String[] defaults) {
        return requested == null ? defaults : requested;
    }
}
