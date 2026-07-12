package com.traffic.agent.tool;

import com.traffic.agent.service.AgentSystemHealthService;
import com.traffic.agent.service.ConfigurationConsistencyAuditService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TrafficHealthAgentTools {

    private final AgentSystemHealthService systemHealthService;
    private final ConfigurationConsistencyAuditService configurationAuditService;

    public TrafficHealthAgentTools(
            AgentSystemHealthService systemHealthService,
            ConfigurationConsistencyAuditService configurationAuditService
    ) {
        this.systemHealthService = systemHealthService;
        this.configurationAuditService = configurationAuditService;
    }

    @Tool(name = "get_system_health", value = "Probe Spring Boot, CityFlow, Traffic-R, tunnel, WebSocket, database and live cache. Read-only.")
    public AgentToolResult getSystemHealth(Integer limit) {
        return AgentToolSupport.run(
                "get_system_health",
                () -> systemHealthService.getSystemHealth(limit == null || limit <= 0 ? 20 : Math.min(limit, 100)),
                "Active health probes for Spring Boot, CityFlow, Traffic-R, WebSocket, database and runtime cache"
        );
    }

    @Tool(name = "audit_configuration_consistency", value = "Audit CityFlow roadnet, Traffic-R phase codes, DB signal phases and live frame consistency. Read-only.")
    public AgentToolResult auditConfigurationConsistency(String sid, String sceneCode) {
        return AgentToolSupport.run(
                "audit_configuration_consistency",
                () -> configurationAuditService.audit(blankToNull(sid), blankToNull(sceneCode)),
                "Configuration consistency audit for CityFlow roadnet, phase mapping, Traffic-R and database tables"
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
