package com.traffic.agent.tool;

import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TrafficDecisionAgentTools {

    private static final int DEFAULT_LIMIT = 20;

    private final RuntimeQueryService runtimeQueryService;

    public TrafficDecisionAgentTools(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    @Tool(name = "get_latest_control_decisions", value = "查询最近一批控制决策，包括策略来源、请求相位、最终相位、持续时间、状态和原因。只读。")
    public AgentToolResult getLatestControlDecisions(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "get_latest_control_decisions",
                () -> runtimeQueryService.getLatestControlDecisions(
                        blankToNull(sid),
                        blankToNull(intersectionId),
                        normalizeLimit(limit)
                ),
                "来自 RuntimeQueryService 的最近控制决策"
        );
    }

    @Tool(name = "get_decision_trace", value = "查询指定控制决策链路，包括策略建议、安全/仲裁/fallback 轨迹和最终结果。只读。")
    public AgentToolResult getDecisionTrace(String decisionId) {
        return AgentToolSupport.run(
                "get_decision_trace",
                () -> runtimeQueryService.getDecisionTrace(decisionId),
                "来自 RuntimeQueryService 的控制决策追踪"
        );
    }

    @Tool(name = "get_model_inference_log", value = "查询 Traffic-R 推理日志，包括请求、输出、耗时、合法性和逐路口结果。只读。")
    public AgentToolResult getModelInferenceLog(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "get_model_inference_log",
                () -> runtimeQueryService.getModelInferenceLog(
                        blankToNull(sid),
                        blankToNull(intersectionId),
                        normalizeLimit(limit)
                ),
                "来自 RuntimeQueryService 的 Traffic-R 推理日志"
        );
    }

    String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 100);
    }
}
