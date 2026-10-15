package dev.amai.portfolio.blog.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.blog.config.DocumentProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;

class RemoteResourceResponseHandlerTest {
    private final DocumentProperties limits = new DocumentProperties();

    @Test
    void relativeRedirectIsResolvedWithoutOpeningAnEntity() throws Exception {
        var response = new BasicClassicHttpResponse(302);
        response.addHeader("Location", "../next.png");
        var result = handler(100, Long.MAX_VALUE).handleResponse(response);
        assertThat(result.redirect()).isEqualTo(URI.create("https://example.test/next.png"));
        assertThat(result.bytes()).isNull();
    }

    @Test
    void redirectsWithoutLocationAndMalformedRedirectsKeepTheirFailureBoundary() {
        var missing = new BasicClassicHttpResponse(302);
        assertThatThrownBy(() -> handler(100, Long.MAX_VALUE).handleResponse(missing))
                .isInstanceOf(IOException.class).hasMessage("Missing redirect location");
        var invalid = new BasicClassicHttpResponse(302);
        invalid.addHeader("Location", "http://[invalid");
        assertThatThrownBy(() -> handler(100, Long.MAX_VALUE).handleResponse(invalid))
                .isInstanceOf(IOException.class).hasMessage("Invalid redirect URI");
    }

    @Test
    void successfulReadKeepsMimeAndBytesAndClosesTheStream() throws Exception {
        var input = new TrackingInputStream(new byte[] {1, 2});
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new InputStreamEntity(input, -1, ContentType.IMAGE_PNG));
        var result = handler(2, Long.MAX_VALUE).handleResponse(response);
        assertThat(result.bytes()).containsExactly((byte) 1, (byte) 2);
        assertThat(result.mime()).isEqualTo("image/png");
        assertThat(input.closed).isTrue();
    }

    @Test
    void unknownLengthOverflowAndExpiredDeadlineCloseTheOpenedStream() {
        var oversized = new TrackingInputStream(new byte[] {1, 2, 3});
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new InputStreamEntity(oversized, -1, ContentType.IMAGE_PNG));
        assertThatThrownBy(() -> handler(2, Long.MAX_VALUE).handleResponse(response))
                .isInstanceOf(IOException.class).hasMessage("Resource read limit exceeded");
        assertThat(oversized.closed).isTrue();

        var expired = new TrackingInputStream(new byte[] {1});
        response.setEntity(new InputStreamEntity(expired, -1, ContentType.IMAGE_PNG));
        assertThatThrownBy(() -> handler(2, System.nanoTime() - 1).handleResponse(response))
                .isInstanceOf(IOException.class).hasMessage("Resource read limit exceeded");
        assertThat(expired.closed).isTrue();
    }

    @Test
    void declaredOverflowEmptyEntityAndWrongMimeStillFail() {
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new ByteArrayEntity(new byte[] {1, 2, 3}, ContentType.IMAGE_PNG));
        assertThatThrownBy(() -> handler(2, Long.MAX_VALUE).handleResponse(response))
                .isInstanceOf(IOException.class).hasMessage("Resource too large");
        response.setEntity(new ByteArrayEntity(new byte[0], ContentType.IMAGE_PNG));
        assertThatThrownBy(() -> handler(2, Long.MAX_VALUE).handleResponse(response))
                .isInstanceOf(IOException.class).hasMessage("Resource size invalid");
        response.setEntity(new ByteArrayEntity(new byte[] {1}, ContentType.APPLICATION_OCTET_STREAM));
        assertThatThrownBy(() -> handler(2, Long.MAX_VALUE).handleResponse(response))
                .isInstanceOf(IOException.class).hasMessage("Unsupported image type");
    }

    @Test
    void readFailureClosesStreamWithoutReplacingTheIOException() throws Exception {
        var input = mock(InputStream.class);
        var failure = new IOException("interrupted");
        when(input.read(any(byte[].class))).thenThrow(failure);
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new InputStreamEntity(input, -1, ContentType.IMAGE_PNG));
        assertThatThrownBy(() -> handler(2, Long.MAX_VALUE).handleResponse(response)).isSameAs(failure);
        verify(input).close();
    }

    @Test
    void actualDnsResolverRejectsNumericLoopbackWithoutRequestingBytes() {
        assertThatThrownBy(() -> new PublicAddressDnsResolver().resolve("127.0.0.1"))
                .isInstanceOf(UnknownHostException.class).hasMessage("Non-public destination");
    }

    private RemoteResourceResponseHandler handler(int maxBytes, long deadlineNanos) {
        return new RemoteResourceResponseHandler(
                URI.create("https://example.test/images/a.png"), maxBytes, deadlineNanos, limits);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
