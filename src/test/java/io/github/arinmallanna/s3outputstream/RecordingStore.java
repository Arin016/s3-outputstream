package io.github.arinmallanna.s3outputstream;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent in-memory multipart oracle; completion checks the ordered receipts. */
final class RecordingStore implements UploadStrategy {
    final RuntimeException primary = new RuntimeException("injected operation failure");
    final RuntimeException cleanupFailure = new RuntimeException("injected cleanup failure");
    final List<byte[]> parts = new ArrayList<>();
    final List<Integer> numbers = new ArrayList<>();
    int creates, puts, completions, aborts;
    byte[] published;
    String failAt = "";
    boolean failAbort;
    boolean invalidEtag;
    boolean errorOnUpload;
    boolean loseCompleteResponse;
    List<CompletedPartInfo> completionReceipts;

    private void fail(String stage) { if (stage.equals(failAt)) throw primary; }
    @Override public String initiateUpload(String b, String k) {
        creates++; fail("create"); return "local-upload";
    }
    @Override public String uploadPart(String b, String k, String id, int n, byte[] data, int len) {
        if (errorOnUpload) throw new AssertionError("injected producer-independent error");
        fail("part");
        if (n != parts.size() + 1) throw new AssertionError("out-of-order part number");
        if (n > MultipartLimits.MAX_PARTS) throw new AssertionError("out-of-range part number");
        parts.add(Arrays.copyOf(data, len)); numbers.add(n);
        return invalidEtag ? null : "etag-" + n;
    }
    @Override public void completeUpload(String b, String k, String id, List<CompletedPartInfo> receipts) {
        completions++; fail("complete");
        if (receipts.size() != parts.size()) throw new AssertionError("missing part receipt");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < receipts.size(); i++) {
            if (receipts.get(i).partNumber() != i + 1 || !receipts.get(i).eTag().equals("etag-" + (i + 1))) {
                throw new AssertionError("bad ordered receipt");
            }
            bytes.write(parts.get(i), 0, parts.get(i).length);
        }
        completionReceipts = new ArrayList<>(receipts);
        published = bytes.toByteArray();
        if (loseCompleteResponse) throw primary;
    }
    @Override public void abortUpload(String b, String k, String id) {
        aborts++; if (failAbort) throw cleanupFailure;
    }
    @Override public void putObject(String b, String k, byte[] data, int len) {
        puts++; fail("put"); published = Arrays.copyOf(data, len);
    }
}
