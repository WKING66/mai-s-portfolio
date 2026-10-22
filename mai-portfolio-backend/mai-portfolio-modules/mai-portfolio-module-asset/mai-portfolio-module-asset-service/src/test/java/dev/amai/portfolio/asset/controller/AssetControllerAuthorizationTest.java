package dev.amai.portfolio.asset.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.service.AssetService;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import org.junit.jupiter.api.Test;

class AssetControllerAuthorizationTest {
    private final AssetService assetService = mock(AssetService.class);

    private final AssetController controller = new AssetController(assetService);

    @Test
    void sharedUploadsRequireOwnerBeforeInvokingFileService() {
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkRole(AuthConstants.OWNER_ROLE))
                    .thenThrow(new ApiException(ApiErrorCode.FORBIDDEN, "owner required"));
            for (var usage : java.util.List.of(AssetUsage.IMAGE, AssetUsage.ATTACHMENT)) {
                assertThatThrownBy(() -> controller.upload(null, usage))
                        .isInstanceOf(ApiException.class);
            }
            verifyNoInteractions(assetService);
        }
    }

    @Test
    void avatarUploadDoesNotRequireOwnerRole() {
        try (var stp = mockStatic(StpUtil.class)) {
            controller.upload(null, AssetUsage.AVATAR);
            stp.verifyNoInteractions();
            verify(assetService).upload(null, AssetUsage.AVATAR);
        }
    }
}
