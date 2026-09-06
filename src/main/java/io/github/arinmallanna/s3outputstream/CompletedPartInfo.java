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

    /** Creates a validated multipart receipt.
     * @param partNumber S3 part number from 1 through 10,000
     * @param eTag nonempty opaque receipt returned by S3
     */
    public CompletedPartInfo(int partNumber, String eTag) {
        if (partNumber < 1 || partNumber > MultipartLimits.MAX_PARTS) {
            throw new IllegalArgumentException("partNumber must be between 1 and 10000");
        }
        this.partNumber = partNumber;
        this.eTag = Objects.requireNonNull(eTag, "eTag must not be null");
        if (eTag.isEmpty()) throw new IllegalArgumentException("eTag must not be empty");
    }

    /** Returns acknowledged S3 part number.
     * @return acknowledged S3 part number
     */
    public int partNumber() {
        return partNumber;
    }

    /** Returns opaque S3 receipt, not a promised content checksum.
     * @return opaque S3 receipt, not a promised content checksum
     */
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
