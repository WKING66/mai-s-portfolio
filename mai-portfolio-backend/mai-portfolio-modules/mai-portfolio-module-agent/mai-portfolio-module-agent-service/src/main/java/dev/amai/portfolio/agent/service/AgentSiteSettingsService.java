package dev.amai.portfolio.agent.service;

import dev.amai.portfolio.agent.entity.AgentSiteSettings;
import dev.amai.portfolio.agent.entity.request.UpdateAgentSiteSettings;
import dev.amai.portfolio.agent.mapper.AgentSiteSettingsMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSiteSettingsService {
    private final AgentSiteSettingsMapper agentSiteSettingsMapper;

    public AgentSiteSettingsService(AgentSiteSettingsMapper agentSiteSettingsMapper) {
        this.agentSiteSettingsMapper = agentSiteSettingsMapper;
    }

    public AgentSiteSettings read() {
        var settings = agentSiteSettingsMapper.read();
        if (settings == null) {
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "站点配置尚未初始化。");
        }
        return settings;
    }

    @Transactional
    public AgentSiteSettings save(UpdateAgentSiteSettings request) {
        // 旧页面保存时必须匹配读取到的时间，避免覆盖另一位管理员已提交的限额。
        if (agentSiteSettingsMapper.save(request) != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "站点配置已更新，请刷新后再试。");
        }
        return read();
    }
}
