package dev.amai.portfolio.portfolio.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.portfolio.service.ProfileMediaService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PublicProfileMediaControllerTest {
    @Test
    void forbidsCachingAndClosesMediaAfterStreaming() throws Exception {
        ProfileMediaService service = mock(ProfileMediaService.class);
        AtomicBoolean closed = new AtomicBoolean();
        byte[] expected = new byte[] {1, 2, 3};
        var input = new ByteArrayInputStream(expected) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        when(service.openPublicMedia(1L)).thenReturn(new AssetContentData(
            input, expected.length, "image/png", "avatar.png", AssetType.IMAGE));
        var response = new PublicProfileMediaController(service).open(1L);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(expected.length);
        var output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toByteArray()).isEqualTo(expected);
        assertThat(closed).isTrue();
    }
}
