package io.github.arinmallanna.s3outputstream;

/**
 * Optional synchronous observer. Events contain no bucket, key, metadata, content
 * or exception text. Keep callbacks fast; they contribute to upload latency.
 * RuntimeExceptions are isolated and counted; callbacks must not reenter the sink.
 */
@FunctionalInterface
public interface UploadListener {
    /** Allocation-free disabled observer. */
    UploadListener NONE = event -> { };
    /** Receives a synchronous progress snapshot.
     * @param event immutable scalar snapshot
     */
    void onEvent(UploadEvent event);
}
