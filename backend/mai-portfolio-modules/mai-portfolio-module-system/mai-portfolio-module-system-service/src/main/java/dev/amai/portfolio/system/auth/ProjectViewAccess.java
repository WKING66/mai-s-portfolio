package dev.amai.portfolio.system.auth;

import dev.amai.portfolio.system.api.AuthConstants;
import jakarta.servlet.http.HttpServletRequest;

/** 统一列表只有明确的单个 MANAGE 参数进入管理边界；非法/重复参数由接口拒绝。 */
final class ProjectViewAccess {
    private ProjectViewAccess() {
    }

    static boolean isPublicProjectRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!AuthConstants.PROJECT_LIST_PATH.equals(path)) {
            return false;
        }
        String[] views = request.getParameterValues(AuthConstants.PROJECT_VIEW_PARAMETER);
        return views == null || views.length != 1
            || !AuthConstants.PROJECT_MANAGE_VIEW.equals(views[0]);
    }
}
