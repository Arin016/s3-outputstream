package io.github.arinmallanna.s3outputstream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class EndpointPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "https://s3.amazonaws.com", "http://example.com:9000", "http://127.0.0.1", "http://127.0.0.1:9000/bucket",
            "http://127.0.0.1:9000?x=1", "http://user@127.0.0.1:9000", "http://127.0.0.1:9000#x", "http://127.0.0.1@evil.invalid:9000"})
    void externalOrAmbiguousEndpointsAreRejectedBeforeNetworkAccess(String value) {
        assertThrows(IllegalArgumentException.class, () -> LocalS3Support.endpoint(value));
    }
}
