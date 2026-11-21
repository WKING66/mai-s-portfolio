package dev.amai.portfolio.agent.mapper;

import dev.amai.portfolio.agent.entity.AgentSiteSettings;
import dev.amai.portfolio.agent.entity.request.UpdateAgentSiteSettings;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentSiteSettingsMapper {
    AgentSiteSettings read();

    int save(UpdateAgentSiteSettings request);
}
