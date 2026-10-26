package dev.amai.portfolio.system.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.service.AccountProfileService;
import dev.amai.portfolio.system.entity.request.AccountAvatarRequest;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

class AccountProfileControllerTest {
    @Test void profileResponseCannotBeCachedAcrossAccountSwitches() {
        var service = mock(AccountProfileService.class);
        when(service.current()).thenReturn(new AccountProfileVo("reader", "阿霾", null, null));
        var response = new AccountProfileController(service).current();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getBody().data().nickname()).isEqualTo("阿霾");
    }

    @Test void bindsJsonAssetReferenceInsteadOfReceivingFileOrServingBinary() throws Exception {
        var service = mock(AccountProfileService.class);
        when(service.bindAvatar(new AccountAvatarRequest(19L))).thenReturn(new AccountProfileVo("reader", "阿霾",
            "https://oss.test/signed", java.time.Instant.parse("2030-01-01T00:00:00Z")));
        var mvc = MockMvcBuilders.standaloneSetup(new AccountProfileController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(put("/api/v1/account/avatar").contentType(MediaType.APPLICATION_JSON).content("{\"assetId\":19}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.avatarUrl").value("https://oss.test/signed"));
        mvc.perform(put("/api/v1/account/avatar").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/account/avatar")).andExpect(status().isMethodNotAllowed());
        verify(service).bindAvatar(new AccountAvatarRequest(19L));
        verifyNoMoreInteractions(service);
    }
}
