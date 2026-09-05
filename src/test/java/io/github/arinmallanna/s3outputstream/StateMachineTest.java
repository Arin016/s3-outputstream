package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** Seeded operation sequences checked against a separate publication model. */
class StateMachineTest {
    @Test void generatedSequencesRespectPublicationAndTerminalInvariants() throws Exception {
        for (long seed = 0; seed < 500; seed++) {
            Random random = new Random(seed);
            RecordingStore store = new RecordingStore();
            S3OutputStream out = new S3OutputStream(store, "test-bucket", "test-key", 7);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            int model = 0; // 0 open, 1 committed, 2 aborted
            for (int step = 0; step < 80; step++) {
                int op = step < 20 ? random.nextInt(4) : random.nextInt(7);
                if (op <= 2) {
                    byte[] bytes = new byte[random.nextInt(20)]; random.nextBytes(bytes);
                    if (model == 0) { out.write(bytes); expected.write(bytes); }
                    else assertThrows(IOException.class, () -> out.write(bytes), "seed " + seed);
                } else if (op == 3) {
                    if (model == 0) out.flush(); else assertThrows(IOException.class, out::flush);
                } else if (op == 4) {
                    if (model == 2) assertThrows(IOException.class, out::commit);
                    else { out.commit(); model = 1; }
                } else {
                    if (op == 5) out.abort(); else out.close();
                    if (model == 0) model = 2;
                }
                if (model == 1) assertArrayEquals(expected.toByteArray(), store.published, "seed " + seed);
                else assertNull(store.published, "uncommitted seed " + seed);
                assertTrue(store.completions <= 1); assertTrue(store.puts <= 1); assertTrue(store.aborts <= 1);
                if (model == 0) assertEquals(7, out.getRetainedBufferBytes());
                else LifecycleTest.assertTerminalReleased(out);
            }
            out.close();
        }
    }

    @Test void generatedPartFaultsNeverCompleteMissingReceipts() throws Exception {
        for (int successfulParts = 0; successfulParts < 50; successfulParts++) {
            RecordingStore store = new RecordingStore();
            S3OutputStream out = new S3OutputStream(store, "test-bucket", "test-key", 7);
            out.write(new byte[successfulParts * 7 + 7]);
            store.failAt = "part";
            assertThrows(IOException.class, () -> out.write(42));
            out.close(); out.abort();
            assertEquals(successfulParts, out.getPartsUploaded());
            assertEquals(0, store.completions); assertEquals(1, store.aborts); assertNull(store.published);
            LifecycleTest.assertTerminalReleased(out);
        }
    }
}
