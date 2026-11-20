package app.stemwise.testprovider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Exact document identities stay within the root named by the caller. */
public final class QualificationDocumentPaths {
    private QualificationDocumentPaths() {}

    public static File requireDocument(File root, String rootId, String documentId) throws FileNotFoundException {
        try {
            File canonicalRoot = root.getCanonicalFile();
            if (rootId.equals(documentId)) return canonicalRoot;
            if (!documentId.startsWith(rootId + "/")) throw new FileNotFoundException(documentId);
            File target = new File(canonicalRoot, documentId.substring(rootId.length() + 1)).getCanonicalFile();
            String prefix = canonicalRoot.getPath() + File.separator;
            if (!target.getPath().startsWith(prefix)) throw new FileNotFoundException(documentId);
            String roundTrip = rootId + "/" + canonicalRoot.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/');
            if (!documentId.equals(roundTrip) || !target.exists()) throw new FileNotFoundException(documentId);
            return target;
        } catch (IOException failure) {
            throw new FileNotFoundException(documentId);
        }
    }

    public static boolean isRoot(File target, File primary, File alternate) {
        return target.equals(primary) || target.equals(alternate);
    }
}
