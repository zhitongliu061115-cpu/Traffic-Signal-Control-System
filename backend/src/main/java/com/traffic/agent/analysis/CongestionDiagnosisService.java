package com.traffic.agent.analysis;

import com.traffic.agent.analysis.AgentAnalysisDtos.DiagnosisReport;
import com.traffic.runtime.query.RuntimeQueryDtos.CurrentSimulationState;
import com.traffic.runtime.query.RuntimeQueryDtos.IntersectionDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.MovementSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.SignalSnapshot;
import com.traffic.simulation.state.LiveSimulationStateService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CongestionDiagnosisService {

    private static final int HIGH_QUEUE = 10;
    private static final int SEVERE_QUEUE = 18;
    private static final double HIGH_WAIT_SECONDS = 60.0;
    private static final double SEVERE_WAIT_SECONDS = 90.0;
    private static final double LOW_SPEED = 3.0;

    private final LiveSimulationStateService liveSimulationStateService;

    public CongestionDiagnosisService(LiveSimulationStateService liveSimulationStateService) {
        this.liveSimulationStateService = liveSimulationStateService;
    }

    public DiagnosisReport diagnoseCongestion(String targetType, String targetId, String sid, String sceneCode) {
        String normalizedType = normalizeType(targetType, targetId);
        if ("road".equals(normalizedType)) {
            return diagnoseRoad(targetId, sid, sceneCode);
        }
        if ("intersection".equals(normalizedType)) {
            return diagnoseIntersection(targetId, sid, sceneCode);
        }
        return diagnoseCurrentNetwork(sid);
    }

    private DiagnosisReport diagnoseIntersection(String intersectionId, String sid, String sceneCode) {
        IntersectionDetail detail = liveSimulationStateService.getIntersectionDetail(intersectionId, sid, sceneCode);
        List<String> evidence = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        List<String> impactScope = new ArrayList<>();

        SignalSnapshot state = detail.latestState();
        if (state != null) {
            evidence.add("intersection=" + detail.cityflowId()
                    + ", queue_count=" + state.queueCount()
                    + ", avg_wait=" + round(state.avgWait()) + "s"
                    + ", level=" + state.level()
                    + ", phase=" + state.phaseCode());
            impactScope.add("路口 " + detail.cityflowId() + " 当前相位 " + state.phaseCode());
        } else {
            evidence.add("intersection=" + detail.cityflowId() + " 当前没有已落库的 intersection_state_snapshot");
        }

        MovementSnapshot worstQueue = detail.movements().stream()
                .max(Comparator.comparingInt(MovementSnapshot::queueLen))
                .orElse(null);
        MovementSnapshot worstWait = detail.movements().stream()
                .max(Comparator.comparingDouble(MovementSnapshot::avgWaitTime))
                .orElse(null);
        detail.movements().stream()
                .filter(movement -> movement.queueLen() >= HIGH_QUEUE
                        || movement.avgWaitTime() >= HIGH_WAIT_SECONDS
                        || (movement.avgSpeed() != null && movement.avgSpeed() <= LOW_SPEED))
                .limit(6)
                .forEach(movement -> evidence.add("movement " + movement.movementCode()
                        + " queue=" + movement.queueLen()
                        + ", vehicle_count=" + movement.vehicleCount()
                        + ", avg_wait=" + round(movement.avgWaitTime()) + "s"
                        + ", avg_speed=" + nullableRound(movement.avgSpeed()) + "m/s"
                        + ", cells=" + movement.cells()));

        if (worstQueue != null && worstQueue.queueLen() >= HIGH_QUEUE) {
            impactScope.add("主要积压 movement=" + worstQueue.movementCode());
            causes.add("该进口或转向 movement 排队明显高于阈值 queue>=" + HIGH_QUEUE);
            recommendations.add("建议检查 " + worstQueue.movementCode() + " 对应相位绿灯是否偏短；如需延长，必须提交安全层和仲裁层校验。");
        }
        if (worstWait != null && worstWait.avgWaitTime() >= HIGH_WAIT_SECONDS) {
            causes.add("等待时间偏高，可能存在放行不足、相位周期过长或下游排空能力不足");
            recommendations.add("建议结合 detect_spillback_risk 检查下游道路是否低速或排队。");
        }
        if (detail.movements().stream().anyMatch(movement -> movement.avgSpeed() != null && movement.avgSpeed() <= LOW_SPEED)) {
            causes.add("存在低速 movement，可能受下游溢出或路口内部冲突影响");
        }

        boolean congested = (state != null && (state.queueCount() >= HIGH_QUEUE || state.avgWait() >= HIGH_WAIT_SECONDS))
                || (worstQueue != null && worstQueue.queueLen() >= HIGH_QUEUE)
                || (worstWait != null && worstWait.avgWaitTime() >= HIGH_WAIT_SECONDS);
        if (!congested) {
            causes.add("当前已落库快照未显示明显排队或长等待");
            recommendations.add("建议继续观察最新帧，或扩大到区域级 get_region_metrics。");
        }

        String conclusion = congested
                ? detail.cityflowId() + " 存在拥堵风险，主要证据来自排队、等待时间或低速 movement"
                : detail.cityflowId() + " 当前未发现明显拥堵证据";
        return report(conclusion, evidence, impactScope, causes, recommendations, confidence(evidence, congested),
                List.of("任何相位延长、策略切换或绿波请求都需要人工确认并经过安全层"), Map.of(
                        "targetType", "intersection",
                        "targetId", detail.cityflowId(),
                        "movementCount", detail.movements().size()
                ));
    }

    private DiagnosisReport diagnoseRoad(String roadId, String sid, String sceneCode) {
        RoadDetail detail = liveSimulationStateService.getRoadDetail(roadId, sid, sceneCode);
        List<String> evidence = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        List<String> impactScope = new ArrayList<>();

        if (detail.latestState() == null) {
            evidence.add("road=" + detail.cityflowId() + " 当前没有已落库 road_state_snapshot");
            causes.add("缺少道路快照，无法判断是否拥堵");
            recommendations.add("建议确认仿真帧是否已持久化，或先调用 get_current_simulation_state。");
            return report(detail.cityflowId() + " 缺少道路拥堵诊断数据", evidence, impactScope, causes,
                    recommendations, 0.35, List.of("需要人工确认数据采集是否正常"), Map.of("targetType", "road"));
        }

        var state = detail.latestState();
        evidence.add("road=" + detail.cityflowId()
                + ", queue_count=" + state.queueCount()
                + ", vehicle_count=" + state.vehicleCount()
                + ", avg_speed=" + round(state.avgSpeed()) + "m/s"
                + ", level=" + state.level());
        impactScope.add("道路 " + detail.cityflowId() + "，上游路口 " + detail.fromIntersectionId()
                + "，下游路口 " + detail.toIntersectionId());

        boolean congested = state.queueCount() >= HIGH_QUEUE || state.avgSpeed() <= LOW_SPEED
                || isCongestedLevel(state.level());
        if (state.queueCount() >= HIGH_QUEUE) {
            causes.add("道路排队数超过阈值 queue>=" + HIGH_QUEUE);
        }
        if (state.avgSpeed() <= LOW_SPEED) {
            causes.add("道路平均速度低于 " + LOW_SPEED + "m/s，可能存在下游溢出或瓶颈");
        }
        if (isCongestedLevel(state.level())) {
            causes.add("道路拥堵等级为 " + state.level());
        }
        recommendations.add("建议检查下游路口放行能力和上游进入流量。");
        recommendations.add("如需限制上游放行或调整相位，只能生成建议，必须经过安全层和仲裁层。");

        String conclusion = congested
                ? detail.cityflowId() + " 存在道路拥堵或溢出风险"
                : detail.cityflowId() + " 当前道路快照未显示明显拥堵";
        return report(conclusion, evidence, impactScope, causes, recommendations, confidence(evidence, congested),
                List.of("需要人工确认道路几何、车道映射和下游相位是否正确"), Map.of(
                        "targetType", "road",
                        "targetId", detail.cityflowId(),
                        "laneCount", detail.laneCount()
                ));
    }

    private DiagnosisReport diagnoseCurrentNetwork(String sid) {
        CurrentSimulationState state = liveSimulationStateService.getCurrentSimulationState(sid);
        List<SignalSnapshot> congestedSignals = state.signals().stream()
                .filter(signal -> signal.queueCount() >= HIGH_QUEUE || signal.avgWait() >= HIGH_WAIT_SECONDS
                        || isCongestedLevel(signal.level()))
                .sorted(Comparator.comparingInt(SignalSnapshot::queueCount).reversed())
                .limit(5)
                .toList();
        List<String> evidence = new ArrayList<>();
        if (state.latestFrame() != null) {
            evidence.add("frame seq=" + state.latestFrame().seq()
                    + ", sim_time=" + round(state.latestFrame().simTime())
                    + ", vehicle_count=" + state.latestFrame().vehicleCount()
                    + ", queue_count=" + state.latestFrame().queueCount()
                    + ", avg_wait=" + round(state.latestFrame().avgWait()) + "s"
                    + ", avg_speed=" + round(state.latestFrame().avgSpeed()) + "m/s");
        }
        congestedSignals.forEach(signal -> evidence.add("intersection " + signal.cityflowIntersectionId()
                + " queue=" + signal.queueCount()
                + ", avg_wait=" + round(signal.avgWait()) + "s"
                + ", level=" + signal.level()));
        boolean congested = !congestedSignals.isEmpty()
                || (state.latestFrame() != null && state.latestFrame().queueCount() >= SEVERE_QUEUE);
        return report(
                congested ? "当前仿真存在局部拥堵风险" : "当前仿真未发现明显拥堵证据",
                evidence,
                congestedSignals.stream().map(SignalSnapshot::cityflowIntersectionId).toList(),
                congested ? List.of("局部路口排队或等待时间超过阈值", "可能存在下游排空能力不足或相位分配不均")
                        : List.of("已落库信号快照未超过拥堵阈值"),
                congested ? List.of("建议逐个调用 get_intersection_detail 或 diagnose_congestion 定位主要 movement",
                        "建议检查高排队路口的下游道路和最近控制决策")
                        : List.of("建议继续观察最新帧，避免基于单帧做控制调整"),
                confidence(evidence, congested),
                List.of("任何控制策略变化都需要人工确认"),
                Map.of("targetType", "network", "signalCount", state.signals().size())
        );
    }

    private DiagnosisReport report(
            String conclusion,
            List<String> evidence,
            List<String> impactScope,
            List<String> possibleCauses,
            List<String> recommendations,
            double confidence,
            List<String> humanConfirmationRequired,
            Map<String, Object> data
    ) {
        return new DiagnosisReport(
                conclusion,
                nonEmpty(evidence, "缺少可用证据，无法做可靠诊断"),
                nonEmpty(impactScope, "影响范围需要更多快照确认"),
                nonEmpty(possibleCauses, "当前证据不足以判断具体原因"),
                nonEmpty(recommendations, "建议补充仿真快照和决策日志后再判断"),
                confidence,
                humanConfirmationRequired,
                new LinkedHashMap<>(data),
                Instant.now()
        );
    }

    private String normalizeType(String targetType, String targetId) {
        if (targetType == null || targetType.isBlank()) {
            return targetId == null || targetId.isBlank() ? "network" : "intersection";
        }
        String value = targetType.trim().toLowerCase();
        if (value.contains("road")) {
            return "road";
        }
        if (value.contains("intersection")) {
            return "intersection";
        }
        return "network";
    }

    private boolean isCongestedLevel(String level) {
        if (level == null) {
            return false;
        }
        String value = level.toLowerCase();
        return value.contains("congest") || value.contains("jam") || value.contains("heavy") || value.contains("严重");
    }

    private double confidence(List<String> evidence, boolean hasPositiveFinding) {
        double base = hasPositiveFinding ? 0.72 : 0.58;
        return Math.min(0.92, base + Math.min(evidence.size(), 5) * 0.03);
    }

    private List<String> nonEmpty(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? List.of(fallback) : List.copyOf(values);
    }

    private String nullableRound(Double value) {
        return value == null ? "unknown" : round(value);
    }

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
