package com.traffic.agent.analysis;

import com.traffic.agent.analysis.AgentAnalysisDtos.DiagnosisReport;
import com.traffic.runtime.query.RuntimeQueryDtos.ControlDecisionSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.IntersectionDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.MovementSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.SafetyEventSummary;
import com.traffic.runtime.query.RuntimeQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SignalAnomalyDetectionService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int HIGH_QUEUE = 12;
    private static final double HIGH_WAIT_SECONDS = 75.0;
    private static final int LONG_PHASE_SECONDS = 120;

    private final RuntimeQueryService runtimeQueryService;

    public SignalAnomalyDetectionService(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    public DiagnosisReport detectSignalAnomaly(String sid, String intersectionId, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        List<String> evidence = new ArrayList<>();
        List<String> impactScope = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();

        List<ControlDecisionSummary> decisions = runtimeQueryService.getLatestControlDecisions(sid, intersectionId, safeLimit);
        List<SafetyEventSummary> safetyEvents = runtimeQueryService.getSafetyEvents(sid, intersectionId, null, safeLimit);
        data.put("decisionCount", decisions.size());
        data.put("safetyEventCount", safetyEvents.size());

        decisions.stream().limit(5).forEach(decision -> evidence.add("decision " + decision.id()
                + " intersection=" + decision.cityflowIntersectionId()
                + ", controller=" + decision.controllerType()
                + ", requested=" + decision.requestedPhaseCode()
                + ", final=" + decision.finalPhaseCode()
                + ", duration=" + decision.durationSec() + "s"
                + ", status=" + decision.status()
                + ", reason=" + decision.reason()));
        safetyEvents.stream().limit(5).forEach(event -> evidence.add("safety_event " + event.constraintType()
                + ", action=" + event.action()
                + ", before=" + event.beforePhaseCode()
                + ", after=" + event.afterPhaseCode()
                + ", reason=" + event.reason()));

        boolean anomaly = false;
        if (!decisions.isEmpty()) {
            ControlDecisionSummary latest = decisions.get(0);
            impactScope.add("最近决策路口 " + latest.cityflowIntersectionId());
            if (latest.errorMessage() != null && !latest.errorMessage().isBlank()) {
                anomaly = true;
                causes.add("最近控制决策包含错误：" + latest.errorMessage());
                recommendations.add("建议检查策略输出解析、相位映射和 CityFlow 下发日志。");
            }
            if (!"SUCCESS".equalsIgnoreCase(latest.status()) && !"EXECUTED".equalsIgnoreCase(latest.status())) {
                anomaly = true;
                causes.add("最近控制决策状态不是成功态：" + latest.status());
            }
            if (latest.durationSec() >= LONG_PHASE_SECONDS) {
                anomaly = true;
                causes.add("最近相位持续时间达到 " + latest.durationSec() + "s，超过长相位阈值 " + LONG_PHASE_SECONDS + "s");
                recommendations.add("建议核对最小/最大绿灯约束和相位切换状态机。");
            }
            boolean repeatedPhase = decisions.stream()
                    .limit(Math.min(5, decisions.size()))
                    .map(ControlDecisionSummary::finalPhaseCode)
                    .distinct()
                    .count() == 1 && decisions.size() >= 3;
            if (repeatedPhase) {
                anomaly = true;
                causes.add("最近多次决策最终相位相同，可能存在相位长时间不变或策略输出单一");
            }
        }

        if (!safetyEvents.isEmpty()) {
            anomaly = true;
            causes.add("存在安全约束事件，说明策略建议曾被修改、拒绝或 fallback");
            recommendations.add("建议查看 get_safety_constraint_log 的完整事件，确认是否为非法相位、冲突 movement 或过渡约束触发。");
        }

        if (intersectionId != null && !intersectionId.isBlank()) {
            IntersectionDetail detail = runtimeQueryService.getIntersectionDetail(intersectionId, sid, null);
            MovementSnapshot blocked = detail.movements().stream()
                    .filter(movement -> movement.queueLen() >= HIGH_QUEUE || movement.avgWaitTime() >= HIGH_WAIT_SECONDS)
                    .max(Comparator.comparingInt(MovementSnapshot::queueLen))
                    .orElse(null);
            if (blocked != null) {
                evidence.add("movement " + blocked.movementCode()
                        + " queue=" + blocked.queueLen()
                        + ", avg_wait=" + round(blocked.avgWaitTime()) + "s"
                        + ", avg_speed=" + (blocked.avgSpeed() == null ? "unknown" : round(blocked.avgSpeed())) + "m/s");
                impactScope.add("疑似受影响 movement=" + blocked.movementCode());
                if (decisions.stream().limit(3).noneMatch(decision -> containsPhase(decision.finalPhaseCode(), blocked.movementCode()))) {
                    anomaly = true;
                    causes.add("高排队 movement 近期可能没有获得匹配相位放行");
                    recommendations.add("建议人工核对 movement 与 phase_code 映射，避免显示绿灯但车辆不通行。");
                }
            }
            data.put("intersectionId", detail.cityflowId());
            data.put("movementCount", detail.movements().size());
        }

        if (!anomaly) {
            causes.add("最近决策、安全事件和 movement 快照未触发异常阈值");
            recommendations.add("建议继续观察，不应基于当前证据直接调整信号。");
        }

        String conclusion = anomaly ? "检测到信号控制异常风险" : "未检测到明确的信号控制异常证据";
        return new DiagnosisReport(
                conclusion,
                evidence.isEmpty() ? List.of("没有可用的决策、安全事件或 movement 快照证据") : List.copyOf(evidence),
                impactScope.isEmpty() ? List.of("影响范围需要更多决策和快照确认") : List.copyOf(impactScope),
                List.copyOf(causes),
                List.copyOf(recommendations),
                anomaly ? 0.78 : 0.56,
                List.of("需要人工确认相位映射、黄灯/全红过渡、最大绿灯约束和 CityFlow 实际相位状态"),
                data,
                Instant.now()
        );
    }

    private boolean containsPhase(String phaseCode, String movementCode) {
        if (phaseCode == null || movementCode == null) {
            return false;
        }
        return phaseCode.toLowerCase().contains(movementCode.toLowerCase());
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 100);
    }

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
