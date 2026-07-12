package com.traffic.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.runtime.query.RuntimeQueryDtos.ControlDecisionSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.DecisionEffectSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.DecisionTraceEntry;
import com.traffic.runtime.query.RuntimeQueryDtos.DecisionTraceResponse;
import com.traffic.runtime.query.RuntimeQueryDtos.FallbackEventSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.MaxPressureScoreSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.ModelInferenceLogSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.SafetyEventSummary;
import com.traffic.runtime.query.RuntimeQueryService;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DecisionTraceAggregatorService {

    private final RuntimeQueryService runtimeQueryService;
    private final ObjectMapper objectMapper;

    public DecisionTraceAggregatorService(RuntimeQueryService runtimeQueryService, ObjectMapper objectMapper) {
        this.runtimeQueryService = runtimeQueryService;
        this.objectMapper = objectMapper;
    }

    public EnhancedDecisionTrace getDecisionTrace(String decisionId) {
        DecisionTraceResponse base = runtimeQueryService.getDecisionTrace(decisionId);
        ControlDecisionSummary decision = base.decision();
        Map<String, Object> metadata = parseObject(decision.metadata());

        List<ModelInferenceLogSummary> inferenceLogs = runtimeQueryService.getModelInferenceLog(
                        decision.sid(),
                        decision.cityflowIntersectionId(),
                        10
                ).stream()
                .sorted(Comparator.comparingDouble(log -> Math.abs(log.simTime() - decision.simTime())))
                .limit(3)
                .toList();
        List<SafetyEventSummary> safetyEvents = runtimeQueryService.getSafetyEvents(
                decision.sid(),
                decision.cityflowIntersectionId(),
                decision.id(),
                20
        );
        List<FallbackEventSummary> fallbackEvents = runtimeQueryService.getFallbackEvents(
                decision.sid(),
                decision.cityflowIntersectionId(),
                20
        ).stream().limit(5).toList();

        Map<String, Object> cityFlowApply = new LinkedHashMap<>();
        copyIfPresent(metadata, cityFlowApply, "cityflowApplyPending");
        copyIfPresent(metadata, cityFlowApply, "cityflowApplied");
        copyIfPresent(metadata, cityFlowApply, "cityflowApplyStatus");
        copyIfPresent(metadata, cityFlowApply, "cityflowAppliedPhaseIndex");
        copyIfPresent(metadata, cityFlowApply, "cityflowAppliedPhaseId");
        copyIfPresent(metadata, cityFlowApply, "cityflowApplyError");

        List<TraceStep> timeline = buildTimeline(base.traces(), inferenceLogs, safetyEvents, fallbackEvents, decision, cityFlowApply);
        List<String> explanationHints = buildHints(decision, metadata, safetyEvents, fallbackEvents, cityFlowApply);
        return new EnhancedDecisionTrace(
                decision,
                metadata,
                base.traces(),
                base.maxPressureScores(),
                base.effect(),
                inferenceLogs,
                safetyEvents,
                fallbackEvents,
                cityFlowApply,
                timeline,
                explanationHints,
                Instant.now()
        );
    }

    private List<TraceStep> buildTimeline(
            List<DecisionTraceEntry> traces,
            List<ModelInferenceLogSummary> inferenceLogs,
            List<SafetyEventSummary> safetyEvents,
            List<FallbackEventSummary> fallbackEvents,
            ControlDecisionSummary decision,
            Map<String, Object> cityFlowApply
    ) {
        java.util.ArrayList<TraceStep> steps = new java.util.ArrayList<>();
        inferenceLogs.forEach(log -> steps.add(new TraceStep(
                "traffic-r-inference",
                log.status(),
                "Traffic-R returned parsedPhaseCode=" + log.parsedPhaseCode() + ", valid=" + log.valid(),
                Map.of("logId", log.id(), "simTime", log.simTime(), "latencyMs", log.latencyMs())
        )));
        traces.forEach(trace -> steps.add(new TraceStep(
                trace.stage(),
                "RECORDED",
                trace.message(),
                mapOfNullable("traceId", trace.id(), "inputPayload", trace.inputPayload(), "outputPayload", trace.outputPayload())
        )));
        safetyEvents.forEach(event -> steps.add(new TraceStep(
                "safety-layer",
                event.action(),
                event.constraintType() + ": " + event.beforePhaseCode() + " -> " + event.afterPhaseCode()
                        + ", reason=" + event.reason(),
                Map.of("eventId", event.id(), "decisionId", event.decisionId())
        )));
        fallbackEvents.forEach(event -> steps.add(new TraceStep(
                "fallback",
                "RECORDED",
                event.fromStrategy() + " -> " + event.toStrategy() + ", reason=" + event.reason(),
                Map.of("eventId", event.id(), "simTime", event.simTime())
        )));
        steps.add(new TraceStep(
                "final-decision",
                decision.status(),
                "requested=" + decision.requestedPhaseCode() + ", final=" + decision.finalPhaseCode()
                        + ", duration=" + decision.durationSec() + "s",
                Map.of("decisionId", decision.id(), "cityFlowApply", cityFlowApply)
        ));
        return steps;
    }

    private List<String> buildHints(
            ControlDecisionSummary decision,
            Map<String, Object> metadata,
            List<SafetyEventSummary> safetyEvents,
            List<FallbackEventSummary> fallbackEvents,
            Map<String, Object> cityFlowApply
    ) {
        java.util.ArrayList<String> hints = new java.util.ArrayList<>();
        if (decision.requestedPhaseCode() != null && !decision.requestedPhaseCode().equals(decision.finalPhaseCode())) {
            hints.add("requestedPhase 与 finalPhase 不一致，需要优先查看 safety-layer 或 fallback 记录。");
        }
        if (!safetyEvents.isEmpty()) {
            hints.add("该决策触发了安全约束，最终相位可能被安全层修正或阻断。");
        }
        if (!fallbackEvents.isEmpty()) {
            hints.add("该路口/会话近期存在 fallback 事件，Traffic-R 输出可能曾被降级处理。");
        }
        if (Boolean.FALSE.equals(cityFlowApply.get("cityflowApplied"))) {
            hints.add("metadata 显示 CityFlow 下发未成功或仍待确认，需要检查 applyControlActions 响应。");
        }
        if (metadata.containsKey("safetyOriginalPhaseCode")) {
            hints.add("metadata 中记录了 safetyOriginalPhaseCode，可用于解释安全层修正前的原始策略输出。");
        }
        if (hints.isEmpty()) {
            hints.add("未发现明显修正链路；可重点核对 Traffic-R 原始输出、最终相位和 CityFlow 下发状态是否一致。");
        }
        return hints;
    }

    private Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of("raw", text, "parseWarning", ex.getOriginalMessage());
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private Map<String, Object> mapOfNullable(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            values.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return values;
    }

    public record EnhancedDecisionTrace(
            ControlDecisionSummary decision,
            Map<String, Object> decisionMetadata,
            List<DecisionTraceEntry> recordedTraces,
            List<MaxPressureScoreSummary> maxPressureScores,
            DecisionEffectSummary effect,
            List<ModelInferenceLogSummary> trafficRInference,
            List<SafetyEventSummary> safetyEvents,
            List<FallbackEventSummary> fallbackEvents,
            Map<String, Object> cityFlowApply,
            List<TraceStep> timeline,
            List<String> explanationHints,
            Instant generatedAt
    ) {
    }

    public record TraceStep(
            String stage,
            String status,
            String summary,
            Map<String, Object> evidence
    ) {
    }
}
