package app.stemwise.testprovider;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Grants the immutable synthetic tree to the exact disposable candidate and records its digest. */
public final class GrantQualificationTreeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        try {
            requireAuthorizedCaller(getCallingPackage(), getCallingActivity() == null ? null : getCallingActivity().getClassName());
            if (getIntent().hasExtra("stemwiseQaProbeGoldenControls")) {
                probeGoldenControls();
                return;
            }
            String grantTreeUri = getIntent().getStringExtra(GRANT_TREE_URI_EXTRA);
            Uri tree = grantTreeUri == null ? null : Uri.parse(grantTreeUri);
            if (tree != null && !QualificationDocumentsProvider.TREE_URI.equals(tree) &&
                    !QualificationDocumentsProvider.ALTERNATE_TREE_URI.equals(tree)) {
                throw new IllegalArgumentException("Unknown qualification tree " + tree);
            }
            String mutation = getIntent().getStringExtra(MUTATION_EXTRA);
            if (mutation != null && !QualificationDocumentsProvider.TREE_URI.equals(tree)) {
                throw new SecurityException("Qualification mutation requires its exact main-tree grant request");
            }
            applyRequestedMutation(mutation);
            if (tree != null) returnPersistableTreeUri(tree, flags);
            File digest = new File(getFilesDir(), "fixture.sha256");
            if (digest.exists() && !digest.delete()) {
                throw new IllegalStateException("Could not invalidate prior qualification fingerprint");
            }
            String fingerprint = fingerprint(
                    QualificationDocumentsProvider.providerBaseDirectory(this));
            try (FileOutputStream output = new FileOutputStream(digest, false)) {
                output.write(fingerprint.getBytes(StandardCharsets.US_ASCII));
                output.getFD().sync();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint qualification tree", failure);
        } finally {
            finish();
        }
    }

    private void probeGoldenControls() throws Exception {
        Uri uri = getIntent().getData();
        if (uri == null || !"content".equals(uri.getScheme()) ||
                !"app.stemwise.development.golden.qa.documents".equals(uri.getAuthority())) {
            throw new SecurityException("Unknown Golden QA probe destination");
        }
        org.json.JSONObject result = new org.json.JSONObject().put("uid", android.os.Process.myUid());
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            byte[] bytes = new byte[256];
            int count = input.read(bytes);
            if (count <= 0) throw new IllegalStateException("Granted fixture document is empty");
            result.put("read", new String(bytes, 0, count, StandardCharsets.UTF_8));
        }
        Bundle metadata = android.provider.DocumentsContract.getDocumentMetadata(getContentResolver(), uri);
        result.put("metadata", metadata == null ? org.json.JSONObject.NULL : metadata.getString("qaDisplayName"));
        for (String method : new String[]{"reset", "set-fault", "metrics", "reset-metrics", "measure-reads"}) {
            try {
                getContentResolver().call(uri, method, "set-fault".equals(method) ? "OPEN_SECURITY" : "true", null);
                result.put(method, "allowed");
            } catch (SecurityException denied) {
                result.put(method, "denied");
            } catch (Exception unexpected) {
                result.put(method, "unexpected:" + unexpected.getClass().getSimpleName());
            }
        }
        setResult(RESULT_OK, new Intent().putExtra("stemwiseQaGoldenProbeResult", result.toString()));
    }

    static void requireAuthorizedCaller(String callingPackage, String callingClass) {
        if (!"app.stemwise.development".equals(callingPackage) ||
                !"androidx.activity.ComponentActivity".equals(callingClass)) {
            throw new SecurityException("Qualification control requires the exact disposable target activity");
        }
    }

    private void returnPersistableTreeUri(Uri treeUri, int flags) {
        if (!QualificationDocumentsProvider.TREE_URI.equals(treeUri) &&
                !QualificationDocumentsProvider.ALTERNATE_TREE_URI.equals(treeUri)) {
            throw new IllegalArgumentException("Unknown qualification tree " + treeUri);
        }
        setResult(RESULT_OK, new Intent().setData(treeUri).addFlags(flags));
    }

    private void applyRequestedMutation(String mutation) throws Exception {
        if (mutation == null) return;
        String name;
        if ("delete-main-canonical".equals(mutation)) {
            name = ".stemwise-library.json";
        } else if ("delete-main-backup".equals(mutation)) {
            name = ".stemwise-library.json.bak";
        } else {
            throw new IllegalArgumentException("Unknown qualification mutation " + mutation);
        }
        File target = new File(QualificationDocumentsProvider.rootDirectory(this), name);
        if (!target.isFile() || !target.delete()) {
            throw new IllegalStateException("Could not apply qualification mutation " + mutation);
        }
    }

    private static String fingerprint(File root) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return fingerprintOnce(root);
            } catch (FileNotFoundException failure) {
                if (attempt == 7) throw failure;
            }
        }
    }

    private static String fingerprintOnce(File root) throws Exception {
        List<File> files = new ArrayList<>();
        collect(root, files);
        files.sort(Comparator.comparing(file -> root.toPath().relativize(file.toPath()).toString()));
        MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        for (File file : files) {
            String relative = root.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/');
            aggregate.update(relative.getBytes(StandardCharsets.UTF_8));
            aggregate.update((byte) 0);
            aggregate.update(Long.toString(file.length()).getBytes(StandardCharsets.US_ASCII));
            aggregate.update((byte) 0);
            MessageDigest content = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) content.update(buffer, 0, count);
            }
            aggregate.update(hex(content.digest()).getBytes(StandardCharsets.US_ASCII));
            aggregate.update((byte) '\n');
        }
        return hex(aggregate.digest()).toUpperCase(java.util.Locale.ROOT);
    }

    private static void collect(File file, List<File> files) {
        File[] children = file.listFiles();
        if (children == null) {
            if (file.isFile()) files.add(file);
            return;
        }
        for (File child : children) collect(child, files);
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }

    private static final String MUTATION_EXTRA = "stemwiseQaMutation";
    private static final String GRANT_TREE_URI_EXTRA = "stemwiseQaGrantTreeUri";
}
