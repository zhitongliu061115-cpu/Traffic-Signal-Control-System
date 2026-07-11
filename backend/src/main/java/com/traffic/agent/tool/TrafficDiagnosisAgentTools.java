package com.traffic.agent.tool;

import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TrafficDiagnosisAgentTools {

    private final RuntimeQueryService runtimeQueryService;

    public TrafficDiagnosisAgentTools(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    @Tool(name = "get_fallback_events", value = "查询策略降级/fallback 事件，作为诊断 Traffic-R 超时、无效输出或切换 MaxPressure 的证据。只读。")
    public AgentToolResult getFallbackEvents(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "get_fallback_events",
                () -> runtimeQueryService.getFallbackEvents(blankToNull(sid), blankToNull(intersectionId), normalizeLimit(limit)),
                "来自 RuntimeQueryService 的策略降级事件"
        );
    }

    @Tool(name = "get_safety_events", value = "查询安全约束触发事件，作为诊断非法相位、冲突 movement、最小/最大绿灯或 fallback 的证据。只读。")
    public AgentToolResult getSafetyEvents(String sid, String intersectionId, String decisionId, Integer limit) {
        return AgentToolSupport.run(
                "get_safety_events",
                () -> runtimeQueryService.getSafetyEvents(blankToNull(sid), blankToNull(intersectionId), blankToNull(decisionId), normalizeLimit(limit)),
                "来自 RuntimeQueryService 的安全约束事件"
        );
    }

    @Tool(name = "get_alert_events", value = "查询系统告警事件，作为诊断异常、服务状态或运行风险的证据。只读。")
    public AgentToolResult getAlertEvents(String sid, String level, String status, Integer limit) {
        return AgentToolSupport.run(
                "get_alert_events",
                () -> runtimeQueryService.getAlertEvents(blankToNull(sid), blankToNull(level), blankToNull(status), normalizeLimit(limit)),
                "来自 RuntimeQueryService 的系统告警事件"
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
