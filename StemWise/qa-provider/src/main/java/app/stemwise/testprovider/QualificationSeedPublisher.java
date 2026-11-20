package app.stemwise.testprovider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/** Unpublished seed bytes are private staging; a sealed published tree is never repaired or replaced. */
public final class QualificationSeedPublisher {
    private static final String COMPLETE = ".qualification-seed.complete";

    @FunctionalInterface
    public interface Seeder { void seed(File staging) throws IOException; }

    public static synchronized void ensurePublished(File base, File seal, Seeder seeder) throws IOException {
        if (seal.isFile()) {
            if (!base.isDirectory()) throw new IOException("Sealed qualification fixture is unavailable");
            return;
        }
        if (new File(base, COMPLETE).isFile()) {
            writeSeal(seal);
            return;
        }
        if (base.exists()) {
            File[] files = base.listFiles();
            if (files == null || files.length != 0) throw new IOException("Refusing to replace an unrecognized fixture");
            Files.delete(base.toPath());
        }
        File staging = new File(base.getParentFile(), base.getName() + ".seeding");
        if (!staging.getCanonicalFile().getParentFile().equals(base.getCanonicalFile().getParentFile())) {
            throw new IOException("Qualification staging escaped its owner");
        }
        if (staging.exists()) {
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(staging.toPath())) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
        if (!staging.mkdirs() && !staging.isDirectory()) throw new IOException("Could not create qualification staging");
        seeder.seed(staging);
        try (FileOutputStream output = new FileOutputStream(new File(staging, COMPLETE))) {
            output.write("COMPLETE\n".getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        Files.move(staging.toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE);
        writeSeal(seal);
    }

    private static void writeSeal(File seal) throws IOException {
        File pending = new File(seal.getParentFile(), seal.getName() + ".pending");
        try (FileOutputStream output = new FileOutputStream(pending)) {
            output.write("SEALED_V3\n".getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        Files.move(pending.toPath(), seal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private QualificationSeedPublisher() { }
}
