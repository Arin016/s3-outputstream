import io.github.arinmallanna.s3outputstream.S3OutputStream;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Complete callback composition example. The caller owns/configures the S3 client. */
public final class UploadZip {
    private UploadZip() { }

    public static void upload(S3Client s3, String destinationBucket, String destinationKey,
                              List<Path> files) throws IOException {
        S3OutputStream.upload(S3OutputStream.builder()
                .s3Client(s3)
                .bucket(destinationBucket)
                .key(destinationKey)
                .contentType("application/zip"), out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Path file : files) {
                    zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            } // finishes the ZIP; the producer view cannot commit
        }); // commits only after normal return; throws on duplicate ZIP entry names
    }
}
