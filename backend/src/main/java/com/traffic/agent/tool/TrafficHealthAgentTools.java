package com.traffic.agent.tool;

import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TrafficHealthAgentTools {

    private final RuntimeQueryService runtimeQueryService;

    public TrafficHealthAgentTools(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    @Tool(name = "get_system_health", value = "查询系统健康摘要，包括数据库视角的关键表计数、会话状态分布和服务健康快照。只读。")
    public AgentToolResult getSystemHealth(Integer limit) {
        return AgentToolSupport.run(
                "get_system_health",
                () -> runtimeQueryService.getSystemHealth(limit == null || limit <= 0 ? 20 : Math.min(limit, 100)),
                "来自 RuntimeQueryService 的系统健康摘要"
        );
    }
}
