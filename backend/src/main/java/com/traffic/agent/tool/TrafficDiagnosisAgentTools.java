package com.traffic.agent.tool;

import com.traffic.agent.analysis.CongestionDiagnosisService;
import com.traffic.agent.analysis.SignalAnomalyDetectionService;
import com.traffic.agent.analysis.SpillbackRiskService;
import com.traffic.agent.analysis.StrategyMetricsCompareService;
import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TrafficDiagnosisAgentTools {

    private final RuntimeQueryService runtimeQueryService;
    private final CongestionDiagnosisService congestionDiagnosisService;
    private final SignalAnomalyDetectionService signalAnomalyDetectionService;
    private final SpillbackRiskService spillbackRiskService;
    private final StrategyMetricsCompareService strategyMetricsCompareService;

    public TrafficDiagnosisAgentTools(
            RuntimeQueryService runtimeQueryService,
            CongestionDiagnosisService congestionDiagnosisService,
            SignalAnomalyDetectionService signalAnomalyDetectionService,
            SpillbackRiskService spillbackRiskService,
            StrategyMetricsCompareService strategyMetricsCompareService
    ) {
        this.runtimeQueryService = runtimeQueryService;
        this.congestionDiagnosisService = congestionDiagnosisService;
        this.signalAnomalyDetectionService = signalAnomalyDetectionService;
        this.spillbackRiskService = spillbackRiskService;
        this.strategyMetricsCompareService = strategyMetricsCompareService;
    }

    @Tool(name = "diagnose_congestion", value = "基于真实快照、movement、道路和决策证据诊断拥堵原因。只读，只生成建议，不执行控制。")
    public AgentToolResult diagnoseCongestion(String targetType, String targetId, String sid, String sceneCode) {
        return AgentToolSupport.run(
                "diagnose_congestion",
                () -> congestionDiagnosisService.diagnoseCongestion(
                        blankToNull(targetType),
                        blankToNull(targetId),
                        blankToNull(sid),
                        blankToNull(sceneCode)
                ),
                "来自 CongestionDiagnosisService 的拥堵诊断报告"
        );
    }

    @Tool(name = "detect_signal_anomaly", value = "检测信号控制异常，包括相位长时间不变、安全约束触发、相位映射疑似异常等。只读。")
    public AgentToolResult detectSignalAnomaly(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "detect_signal_anomaly",
                () -> signalAnomalyDetectionService.detectSignalAnomaly(
                        blankToNull(sid),
                        blankToNull(intersectionId),
                        normalizeLimit(limit)
                ),
                "来自 SignalAnomalyDetectionService 的信号异常检测报告"
        );
    }

    @Tool(name = "detect_spillback_risk", value = "检测道路或路口下游溢出风险。只读，只生成风险判断和建议。")
    public AgentToolResult detectSpillbackRisk(String sid, String roadId, String intersectionId, String sceneCode) {
        return AgentToolSupport.run(
                "detect_spillback_risk",
                () -> spillbackRiskService.detectSpillbackRisk(
                        blankToNull(sid),
                        blankToNull(roadId),
                        blankToNull(intersectionId),
                        blankToNull(sceneCode)
                ),
                "来自 SpillbackRiskService 的下游溢出风险检测报告"
        );
    }

    @Tool(name = "get_region_metrics", value = "查询区域或路口集合的平均排队、等待、速度和拥堵路口数量。只读。")
    public AgentToolResult getRegionMetrics(String sid, String regionId, String intersectionIds, Integer limit) {
        return AgentToolSupport.run(
                "get_region_metrics",
                () -> strategyMetricsCompareService.getRegionMetrics(
                        blankToNull(sid),
                        blankToNull(regionId),
                        blankToNull(intersectionIds),
                        normalizeLimit(limit)
                ),
                "来自 StrategyMetricsCompareService 的区域指标报告"
        );
    }

    @Tool(name = "compare_strategy_metrics", value = "对比多个仿真会话或同一场景下不同策略的等待、排队、速度和通行量。只读。")
    public AgentToolResult compareStrategyMetrics(String sids, String sceneCode, Integer limit) {
        return AgentToolSupport.run(
                "compare_strategy_metrics",
                () -> strategyMetricsCompareService.compareStrategyMetrics(
                        blankToNull(sids),
                        blankToNull(sceneCode),
                        normalizeLimit(limit)
                ),
                "来自 StrategyMetricsCompareService 的策略效果对比报告"
        );
    }

    @Tool(name = "get_fallback_events", value = "查询策略降级/fallback 事件，作为诊断 Traffic-R 超时、无效输出或切换 MaxPressure 的证据。只读。")
    public AgentToolResult getFallbackEvents(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "get_fallback_events",
                () -> runtimeQueryService.getFallbackEvents(blankToNull(sid), blankToNull(intersectionId), normalizeLimit(limit)),
                "来自 RuntimeQueryService 的策略降级事件"
        );
    }

    @Tool(name = "get_fallback_log", value = "查询策略降级/fallback 记录。是 get_fallback_events 的 Agent 语义化别名。只读。")
    public AgentToolResult getFallbackLog(String sid, String intersectionId, Integer limit) {
        return AgentToolSupport.run(
                "get_fallback_log",
                () -> runtimeQueryService.getFallbackEvents(blankToNull(sid), blankToNull(intersectionId), normalizeLimit(limit)),
                "来自 RuntimeQueryService 的策略降级日志"
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

    @Tool(name = "get_safety_constraint_log", value = "查询安全约束触发记录，包括约束类型、动作、前后相位和原因。只读。")
    public AgentToolResult getSafetyConstraintLog(String sid, String intersectionId, String decisionId, Integer limit) {
        return AgentToolSupport.run(
                "get_safety_constraint_log",
                () -> runtimeQueryService.getSafetyEvents(
                        blankToNull(sid),
                        blankToNull(intersectionId),
                        blankToNull(decisionId),
                        normalizeLimit(limit)
                ),
                "来自 RuntimeQueryService 的安全约束日志"
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
