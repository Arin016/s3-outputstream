package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class MultipartLimitsTest {
    private static final int P = MultipartLimits.MIN_PART_SIZE_BYTES;

    @Test void partSizeSelectionUsesCeilingWithoutOverflow() {
        assertEquals(P, MultipartLimits.partSizeFor(0));
        assertEquals(P, MultipartLimits.partSizeFor((long) P * 10000));
        assertEquals(P + 1, MultipartLimits.partSizeFor((long) P * 10000 + 1));
        long max = (long) MultipartLimits.MAX_PART_SIZE_BYTES * 10000;
        assertEquals(MultipartLimits.MAX_PART_SIZE_BYTES, MultipartLimits.partSizeFor(max));
        assertThrows(IllegalArgumentException.class, () -> MultipartLimits.partSizeFor(max + 1));
        assertThrows(IllegalArgumentException.class, () -> MultipartLimits.partSizeFor(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> MultipartLimits.partSizeFor(-1));
    }

    @Test void fixedPartCapacityAndExactKnownLengthSelection() throws Exception {
        long length = (long) P * 10000 + 1;
        assertEquals(52_428_800_000L, MultipartLimits.capacity(P));
        RecordingStore store = new RecordingStore();
        S3OutputStream.Builder builder = S3OutputStream.builder().uploadStrategy(store).bucket("test-bucket").key("test-key");
        assertThrows(IllegalArgumentException.class, () -> builder.partSize(P).expectedLength(length).build());
        try (S3OutputStream out = S3OutputStream.builder().uploadStrategy(store).bucket("test-bucket").key("test-key")
                .expectedLength(length).build()) {
            assertEquals(P + 1, out.getPartSize()); assertTrue(out.getMaximumObjectSize() >= length);
        }
        assertThrows(IllegalArgumentException.class, () -> MultipartLimits.capacity(P - 1));
    }

    @Test void partNumberGuardAllowsExactly10000() throws Exception {
        assertEquals(1, MultipartLimits.nextPartNumber(0));
        assertEquals(10000, MultipartLimits.nextPartNumber(9999));
        assertThrows(IOException.class, () -> MultipartLimits.nextPartNumber(10000));
        assertThrows(IOException.class, () -> MultipartLimits.nextPartNumber(Integer.MAX_VALUE));
    }

    @Test void capacityChecksBeforeAcceptingCallAndAvoidsOverflow() throws Exception {
        long capacity = MultipartLimits.capacity(P);
        MultipartLimits.checkWrite(capacity - 1, 1, capacity, -1);
        MultipartLimits.checkWrite(capacity, 0, capacity, -1);
        assertThrows(IOException.class, () -> MultipartLimits.checkWrite(capacity, 1, capacity, -1));
        assertThrows(IOException.class, () -> MultipartLimits.checkWrite(Long.MAX_VALUE, 1, capacity, -1));
        assertThrows(IOException.class, () -> MultipartLimits.checkWrite(capacity - 1, 2, capacity, -1));
        assertThrows(IOException.class, () -> MultipartLimits.checkWrite(3, 1, capacity, 3));
    }

    @Test void realStreamPartLimitWithSmallTestBuffers() throws Exception {
        RecordingStore store = new RecordingStore();
        S3OutputStream out = new S3OutputStream(store, "test-bucket", "test-key", 7);
        out.write(new byte[70000]); // same state logic; no huge allocation or invalid real S3 request
        assertEquals(9999, out.getPartsUploaded()); out.commit();
        assertEquals(10000, out.getPartsUploaded()); assertEquals(70000, store.published.length);
        assertEquals(10000, store.numbers.get(store.numbers.size() - 1));
        LifecycleTest.assertTerminalReleased(out);
    }

    @Test void exceedingLimitAbortsWithoutAcceptingPartialCall() throws Exception {
        RecordingStore store = new RecordingStore();
        S3OutputStream out = new S3OutputStream(store, "test-bucket", "test-key", 7);
        out.write(new byte[69999]);
        assertThrows(IOException.class, () -> out.write(new byte[2]));
        assertEquals(69999, out.getTotalBytesWritten()); assertEquals(1, store.aborts);
        assertNull(store.published); assertThrows(IOException.class, out::commit);
        LifecycleTest.assertTerminalReleased(out);
    }
}
