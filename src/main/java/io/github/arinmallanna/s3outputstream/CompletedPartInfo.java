package io.github.arinmallanna.s3outputstream;

import java.util.Objects;

/**
 * Immutable value object representing a completed multipart upload part.
 *
 * <p>Decouples the internal state tracking from the AWS SDK's CompletedPart type,
 * keeping the domain model SDK-agnostic at the strategy interface boundary.
 */
public final class CompletedPartInfo {

    private final int partNumber;
    private final String eTag;

    public CompletedPartInfo(int partNumber, String eTag) {
        if (partNumber < 1) throw new IllegalArgumentException("partNumber must be >= 1");
        this.partNumber = partNumber;
        this.eTag = Objects.requireNonNull(eTag, "eTag must not be null");
    }

    public int partNumber() {
        return partNumber;
    }

    public String eTag() {
        return eTag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompletedPartInfo)) return false;
        CompletedPartInfo that = (CompletedPartInfo) o;
        return partNumber == that.partNumber && eTag.equals(that.eTag);
    }

    @Override
    public int hashCode() {
        return 31 * partNumber + eTag.hashCode();
    }

    @Override
    public String toString() {
        return "CompletedPartInfo{partNumber=" + partNumber + ", eTag='" + eTag + "'}";
    }
}
