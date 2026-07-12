package com.traffic.agent.tool;

import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class EmergencyAgentTools {

    private final RuntimeQueryService runtimeQueryService;

    public EmergencyAgentTools(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    @Tool(name = "get_emergency_events", value = "查询应急车辆或绿波任务主事件。只读，不生成、不执行绿波控制。")
    public AgentToolResult getEmergencyEvents(String sid, String status, Integer limit) {
        return AgentToolSupport.run(
                "get_emergency_events",
                () -> runtimeQueryService.getEmergencyEvents(blankToNull(sid), blankToNull(status), normalizeLimit(limit)),
                "来自 RuntimeQueryService 的应急事件"
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }
}
