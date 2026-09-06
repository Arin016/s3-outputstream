package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleTest {
    private static final int P = 5 * 1024 * 1024;
    private S3OutputStream.Builder builder(RecordingStore store) {
        return S3OutputStream.builder().uploadStrategy(store).bucket("test-bucket").key("test-key");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, P - 1, P, P + 1, 2 * P, 2 * P + 1})
    void boundaryAndCommitIdempotence(int size) throws Exception {
        RecordingStore store = new RecordingStore();
        S3OutputStream out = builder(store).build();
        byte[] chunk = new byte[65536];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) i;
        int remaining = size;
        while (remaining > 0) { int n = Math.min(remaining, chunk.length); out.write(chunk, 0, n); remaining -= n; }
        out.flush();
        assertNull(store.published);
        out.commit(); out.commit(); out.close(); out.abort();
        assertEquals(UploadState.COMPLETED, out.getState());
        assertEquals(size, store.published.length);
        byte[] expected = new byte[size];
        for (int i = 0; i < size; i++) expected[i] = (byte) i;
        assertArrayEquals(expected, store.published);
        assertEquals(size, out.getTotalBytesWritten());
        assertEquals(size, out.getTotalBytesUploaded());
        assertEquals(size <= P ? 1 : 0, store.puts);
        assertEquals(size <= P ? 0 : 1, store.creates);
        assertEquals(size <= P ? 0 : (size + P - 1) / P, out.getPartsUploaded());
        assertTerminalReleased(out);
    }

    @Test void closeDiscardsUncommittedEmptyAndSmallStreams() throws Exception {
        for (int size : new int[]{0, 11}) {
            RecordingStore store = new RecordingStore(); S3OutputStream out = builder(store).build();
            out.write(new byte[size]); out.close(); out.close(); out.abort();
            assertNull(store.published); assertEquals(0, store.puts); assertEquals(0, store.aborts);
            assertEquals(UploadState.ABORTED, out.getState()); assertTerminalReleased(out);
        }
    }

    @Test void multipartCloseAbortsExactlyOnce() throws Exception {
        RecordingStore store = new RecordingStore(); S3OutputStream out = builder(store).build();
        out.write(new byte[P + 1]); out.close(); out.close(); out.abort();
        assertEquals(1, store.aborts); assertEquals(0, store.completions);
        assertThrows(IOException.class, out::commit); assertTerminalReleased(out);
    }

    @Test void callbackFinalizesZipBeforeCommitEvenWhenWrapperClosesView() throws Exception {
        RecordingStore store = new RecordingStore();
        S3OutputStream.upload(builder(store), output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("data.csv")); zip.write("a,b\n1,2\n".getBytes()); zip.closeEntry();
            }
            assertNull(store.published);
            assertThrows(IOException.class, () -> output.write(1));
        });
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(store.published))) {
            assertEquals("data.csv", zip.getNextEntry().getName());
            assertArrayEquals("a,b\n1,2\n".getBytes(), zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 7, P + 1})
    void callbackFailureNeverPublishesEvenWithLegacyBuilder(int size) throws Exception {
        RecordingStore store = new RecordingStore(); IOException failure = new IOException("producer");
        S3OutputStream.Builder config = builder(store).autoCommitOnClose(true);
        assertSame(failure, assertThrows(IOException.class, () -> S3OutputStream.upload(config, out -> {
            out.write(new byte[size]); out.close(); throw failure;
        })));
        assertNull(store.published);
        assertEquals(size > P ? 1 : 0, store.aborts);
        // The helper must not mutate a caller's builder.
        try (S3OutputStream legacy = config.build()) { legacy.write(42); }
        assertArrayEquals(new byte[]{42}, store.published);
    }

    @Test void callbackRuntimeExceptionAndErrorRetainIdentity() {
        for (Throwable primary : new Throwable[]{new IllegalArgumentException("producer"), new AssertionError("producer")}) {
            RecordingStore store = new RecordingStore();
            Throwable actual = assertThrows(primary.getClass(), () -> S3OutputStream.upload(builder(store), out -> {
                out.write(1);
                if (primary instanceof Error) throw (Error) primary;
                throw (RuntimeException) primary;
            }));
            assertSame(primary, actual); assertNull(store.published);
        }
    }

    @Test void callbackProducerAndAbortExceptionsPreserveCausality() {
        RecordingStore store = new RecordingStore(); store.failAbort = true;
        IOException producer = new IOException("producer");
        IOException actual = assertThrows(IOException.class, () -> S3OutputStream.upload(builder(store), out -> {
            out.write(new byte[P + 1]); throw producer;
        }));
        assertSame(producer, actual); assertEquals(1, actual.getSuppressed().length);
        assertSame(store.cleanupFailure, actual.getSuppressed()[0].getCause());
        assertEquals(1, store.aborts); assertNull(store.published);
    }

    @ParameterizedTest @ValueSource(strings = {"create", "part", "complete", "put"})
    void storageFailuresReleaseStateAndPreserveAbortFailure(String stage) throws Exception {
        RecordingStore store = new RecordingStore(); store.failAt = stage; store.failAbort = true;
        S3OutputStream out = builder(store).build();
        S3UploadException error = assertThrows(S3UploadException.class, () -> {
            if (!stage.equals("put")) out.write(new byte[P + 1]);
            else out.write(3);
            out.commit();
        });
        assertSame(store.primary, error.getCause());
        boolean multipartCreated = stage.equals("part") || stage.equals("complete");
        assertEquals(multipartCreated ? 1 : 0, store.aborts);
        assertEquals(multipartCreated ? 1 : 0, error.getSuppressed().length);
        if (multipartCreated) assertSame(store.cleanupFailure, error.getSuppressed()[0]);
        assertEquals(UploadState.FAILED, out.getState());
        out.close(); out.abort(); assertTerminalReleased(out);
        assertThrows(IOException.class, out::commit);
    }

    @Test void explicitAbortReportsFailureButDoesNotRetryOnClose() throws Exception {
        RecordingStore store = new RecordingStore(); store.failAbort = true;
        S3OutputStream out = builder(store).build(); out.write(new byte[P + 1]);
        assertSame(store.cleanupFailure, assertThrows(S3UploadException.class, out::abort).getCause());
        out.abort(); out.close(); assertEquals(1, store.aborts); assertTerminalReleased(out);
    }

    @Test void emptyEtagAndUnexpectedErrorCannotRetainBuffer() throws Exception {
        for (boolean fatal : new boolean[]{false, true}) {
            RecordingStore store = new RecordingStore(); store.invalidEtag = !fatal; store.errorOnUpload = fatal;
            S3OutputStream out = builder(store).build();
            if (fatal) assertThrows(AssertionError.class, () -> out.write(new byte[P + 1]));
            else assertThrows(S3UploadException.class, () -> out.write(new byte[P + 1]));
            assertTerminalReleased(out); assertEquals(1, store.aborts); assertEquals(UploadState.FAILED, out.getState());
        }
    }

    @Test void lostCompleteResponseIsExplicitlyAnUnknownRemoteOutcome() throws Exception {
        RecordingStore store = new RecordingStore(); store.loseCompleteResponse = true;
        S3OutputStream out = builder(store).build(); out.write(new byte[P + 1]);
        assertThrows(IOException.class, out::commit);
        assertNotNull(store.published); assertEquals(UploadState.FAILED, out.getState());
        assertEquals(1, store.aborts); assertTerminalReleased(out);
    }

    @Test void expectedLengthChecksBeforePublication() throws Exception {
        for (int accepted : new int[]{0, 2, 3, 4}) {
            RecordingStore store = new RecordingStore(); S3OutputStream out = builder(store).expectedLength(3).build();
            if (accepted > 3) {
                assertThrows(IOException.class, () -> out.write(new byte[accepted]));
                assertEquals(0, out.getTotalBytesWritten());
            } else {
                out.write(new byte[accepted]);
                if (accepted == 3) out.commit(); else assertThrows(IOException.class, out::commit);
            }
            assertEquals(accepted == 3, store.published != null); assertTerminalReleased(out);
        }
    }

    @Test void writeRangesAndInvalidArgumentsDoNotMutateOpenStream() throws Exception {
        RecordingStore store = new RecordingStore(); S3OutputStream out = builder(store).build();
        byte[] bytes = {0, 11, 22, 33, 4}; out.write(bytes, 1, 3); out.write(bytes, 5, 0); out.write(0x1ff);
        for (int[] range : new int[][]{{-1, 1}, {0, -1}, {1, Integer.MAX_VALUE}, {Integer.MAX_VALUE, 1}, {5, 1}}) {
            assertThrows(IndexOutOfBoundsException.class, () -> out.write(bytes, range[0], range[1]));
        }
        assertThrows(NullPointerException.class, () -> out.write(null, 0, 0));
        out.commit(); assertArrayEquals(new byte[]{11, 22, 33, -1}, store.published);
        assertThrows(IOException.class, () -> out.write(bytes, 0, 0));
        assertThrows(IOException.class, () -> out.write(1));
        assertThrows(IOException.class, out::flush);
    }

    @ParameterizedTest @ValueSource(strings = {"write", "flush", "commit"})
    void interruptionAbortsAndPreservesInterruptFlag(String operation) throws Exception {
        RecordingStore store = new RecordingStore(); S3OutputStream out = builder(store).build();
        out.write(new byte[P + 1]);
        Thread.currentThread().interrupt();
        try {
            IOException e = assertThrows(IOException.class, () -> {
                if (operation.equals("write")) out.write(1);
                else if (operation.equals("flush")) out.flush(); else out.commit();
            });
            assertInstanceOf(InterruptedIOException.class, e.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(store.published); assertEquals(1, store.aborts); assertTerminalReleased(out);
        } finally { Thread.interrupted(); }
    }

    @Test void telemetryIsOrderedAndCannotReenterOrChangePublication() throws Exception {
        RecordingStore store = new RecordingStore(); List<UploadEvent> events = new ArrayList<>();
        S3OutputStream[] sink = new S3OutputStream[1];
        sink[0] = builder(store).listener(event -> {
            events.add(event);
            if (sink[0] != null) assertThrows(IllegalStateException.class, sink[0]::abort);
            throw new IllegalStateException("listener is deliberately faulty");
        }).build();
        sink[0].write(new byte[P + 1]); sink[0].commit();
        assertEquals(events.size(), sink[0].getListenerFailures());
        assertEquals(UploadEvent.Type.STARTED, events.get(0).type());
        UploadEvent last = events.get(events.size() - 1);
        assertEquals(UploadEvent.Type.COMPLETED, last.type());
        assertEquals(P + 1, last.bytesWritten()); assertEquals(P + 1, last.bytesUploaded());
        assertEquals(UploadEvent.Mode.MULTIPART, last.mode());
        long before = -1;
        for (UploadEvent event : events) { assertTrue(event.elapsedNanos() >= before); before = event.elapsedNanos(); }
        assertEquals(List.of(1, 2), events.stream().filter(e -> e.type() == UploadEvent.Type.PART_COMPLETED)
                .map(UploadEvent::partNumber).collect(java.util.stream.Collectors.toList()));
    }

    @Test void cleanupFailureIsObservableWithoutSensitiveContext() throws Exception {
        RecordingStore store = new RecordingStore(); store.failAt = "part"; store.failAbort = true;
        List<UploadEvent> events = new ArrayList<>(); S3OutputStream out = builder(store).listener(events::add).build();
        S3UploadException failure = assertThrows(S3UploadException.class, () -> out.write(new byte[P + 1]));
        assertFalse(failure.getMessage().contains("test-bucket")); assertFalse(failure.getMessage().contains("test-key"));
        assertEquals("test-bucket", failure.getBucket());
        assertTrue(events.stream().anyMatch(e -> e.type() == UploadEvent.Type.PART_FAILED));
        assertTrue(events.stream().anyMatch(e -> e.type() == UploadEvent.Type.CLEANUP_FAILED));
        assertEquals(UploadEvent.Type.FAILED, events.get(events.size() - 1).type());
    }

    @Test void completedPartSnapshotsDoNotRetainInternalList() throws Exception {
        RecordingStore store = new RecordingStore(); S3OutputStream out = builder(store).build();
        out.write(new byte[P + 1]); List<CompletedPartInfo> snapshot = out.getCompletedParts();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        out.commit(); assertEquals(1, snapshot.size()); assertTrue(out.getCompletedParts().isEmpty());
    }

    @Test void fatalObserversCannotReplaceStorageAndCleanupFailures() throws Exception {
        RecordingStore store = new RecordingStore(); store.failAt = "part"; store.failAbort = true;
        AssertionError observer = new AssertionError("observer");
        S3OutputStream out = builder(store).listener(event -> {
            if (event.type() == UploadEvent.Type.PART_FAILED || event.type() == UploadEvent.Type.CLEANUP_FAILED
                    || event.type() == UploadEvent.Type.FAILED) throw observer;
        }).build();
        S3UploadException actual = assertThrows(S3UploadException.class, () -> out.write(new byte[P + 1]));
        assertSame(store.primary, actual.getCause());
        assertArrayEquals(new Throwable[]{observer}, store.primary.getSuppressed());
        assertArrayEquals(new Throwable[]{observer}, store.cleanupFailure.getSuppressed());
        assertArrayEquals(new Throwable[]{store.cleanupFailure, observer}, actual.getSuppressed());
        assertEquals(1, store.aborts); assertTerminalReleased(out);
    }

    @Test void fatalAbortObserverPreservesCleanupCauseAndReleasesState() throws Exception {
        RecordingStore store = new RecordingStore(); store.failAbort = true;
        AssertionError observer = new AssertionError("observer");
        S3OutputStream out = builder(store).listener(event -> {
            if (event.type() == UploadEvent.Type.ABORTED) throw observer;
        }).build();
        out.write(new byte[P + 1]);
        S3UploadException actual = assertThrows(S3UploadException.class, out::abort);
        assertSame(store.cleanupFailure, actual.getCause());
        assertArrayEquals(new Throwable[]{observer}, actual.getCause().getSuppressed());
        out.close(); assertEquals(1, store.aborts); assertTerminalReleased(out);
    }

    @Test void receiptsRejectInvalidProtocolValues() {
        for (int number : new int[]{0, -1, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> new CompletedPartInfo(number, "receipt"));
        }
        assertThrows(IllegalArgumentException.class, () -> new CompletedPartInfo(1, ""));
        assertThrows(NullPointerException.class, () -> new CompletedPartInfo(1, null));
        assertEquals(10000, new CompletedPartInfo(10000, "receipt").partNumber());
    }

    @Test void validationAndMetadataDefensiveCopy() {
        RecordingStore store = new RecordingStore();
        assertThrows(IllegalArgumentException.class, () -> builder(store).bucket("  ").build());
        assertThrows(IllegalArgumentException.class, () -> builder(store).expectedLength(-1));
        assertThrows(IllegalArgumentException.class, () -> builder(store).partSize(Integer.MAX_VALUE).build());
        assertThrows(IllegalArgumentException.class, () -> builder(store).contentType(" "));
        assertThrows(NullPointerException.class, () -> builder(store).metadata(null));
        assertThrows(NullPointerException.class, () -> builder(store).listener(null));
        assertThrows(NullPointerException.class, () -> builder(store).s3Client(null));
        assertThrows(NullPointerException.class, () -> S3OutputStream.upload(builder(store), null));
        assertThrows(NullPointerException.class, () -> S3OutputStream.upload(null, out -> { }));
        assertDoesNotThrow(() -> { try (S3OutputStream out = builder(store).metadata(Map.of("kind", "test")).build()) { } });
    }

    static void assertTerminalReleased(S3OutputStream out) throws Exception {
        assertEquals(0, out.getRetainedBufferBytes()); assertTrue(out.getCompletedParts().isEmpty());
        java.lang.reflect.Field buffer = S3OutputStream.class.getDeclaredField("buffer"); buffer.setAccessible(true);
        java.lang.reflect.Field id = S3OutputStream.class.getDeclaredField("uploadId"); id.setAccessible(true);
        assertNull(buffer.get(out)); assertNull(id.get(out));
    }
}
