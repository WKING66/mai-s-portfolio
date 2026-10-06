package dev.amai.portfolio.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.service.AccountProfileService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

class AccountProfileControllerTest {
    @Test
    void missingAvatarPartIs400InsteadOfUnexpected500() {
        var controller = new AccountProfileController(mock(AccountProfileService.class));
        var response = controller.missingAvatarPart(new MissingServletRequestPartException("file"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void avatarIsNeverPubliclyCachedAndStandardConverterClosesContent() throws Exception {
        var service = mock(AccountProfileService.class);
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        when(service.openAvatar()).thenReturn(new AssetContentData(input, 3, "image/png",
            "avatar.png", AssetType.IMAGE));
        var response = new AccountProfileController(service).avatar();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        var output = new MockHttpOutputMessage();
        output.getHeaders().putAll(response.getHeaders());
        new ResourceHttpMessageConverter().write(response.getBody(), MediaType.IMAGE_PNG, output);
        assertThat(output.getBodyAsBytes()).containsExactly(1, 2, 3);
        assertThat(closed).isTrue();
    }

    @Test
    void avatarUsesSynchronousMvcDispatchRatherThanAsyncRedispatch() throws Exception {
        var service = mock(AccountProfileService.class);
        when(service.openAvatar()).thenReturn(new AssetContentData(new ByteArrayInputStream(new byte[] {1, 2}),
            2, "image/png", "avatar.png", AssetType.IMAGE));
        MockMvcBuilders.standaloneSetup(new AccountProfileController(service)).build()
            .perform(get("/api/v1/account/avatar"))
            .andExpect(status().isOk())
            .andExpect(request().asyncNotStarted())
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(content().bytes(new byte[] {1, 2}));
    }

    @Test
    void converterClosesAvatarStreamEvenWhenClientWriteFails() {
        var service = mock(AccountProfileService.class);
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(new byte[] {1, 2}) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        when(service.openAvatar()).thenReturn(new AssetContentData(input, 2, "image/png",
            "avatar.png", AssetType.IMAGE));
        var response = new AccountProfileController(service).avatar();
        HttpOutputMessage disconnectedClient = new HttpOutputMessage() {
            private final HttpHeaders headers = new HttpHeaders();

            @Override
            public OutputStream getBody() {
                return new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("test-client-disconnected");
                    }
                };
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
        assertThatThrownBy(() -> new ResourceHttpMessageConverter().write(
            response.getBody(), MediaType.IMAGE_PNG, disconnectedClient)).isInstanceOf(IOException.class);
        assertThat(closed).isTrue();
    }

    @Test
    void responseSetupFailureClosesAvatarBeforeHandingOffToConverter() {
        var service = mock(AccountProfileService.class);
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        when(service.openAvatar()).thenReturn(new AssetContentData(input, 1, "invalid-mime",
            "avatar.png", AssetType.IMAGE));
        assertThatThrownBy(() -> new AccountProfileController(service).avatar())
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(closed).isTrue();
    }

    @Test
    void profileResponseCannotBeCachedAcrossAccountSwitches() {
        var service = mock(AccountProfileService.class);
        when(service.current()).thenReturn(new AccountProfileVo("reader", "阿霾", null));
        var response = new AccountProfileController(service).current();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getBody().data().nickname()).isEqualTo("阿霾");
    }
}
