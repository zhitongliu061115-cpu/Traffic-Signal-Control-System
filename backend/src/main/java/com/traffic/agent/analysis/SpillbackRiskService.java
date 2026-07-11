package com.traffic.agent.analysis;

import com.traffic.agent.analysis.AgentAnalysisDtos.DiagnosisReport;
import com.traffic.common.exception.BusinessException;
import com.traffic.runtime.query.RuntimeQueryDtos.IntersectionDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.RoadLinkInfo;
import com.traffic.runtime.query.RuntimeQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SpillbackRiskService {

    private static final int HIGH_QUEUE = 10;
    private static final int SEVERE_QUEUE = 18;
    private static final double LOW_SPEED = 3.0;
    private static final double SEVERE_LOW_SPEED = 1.5;

    private final RuntimeQueryService runtimeQueryService;

    public SpillbackRiskService(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    public DiagnosisReport detectSpillbackRisk(String sid, String roadId, String intersectionId, String sceneCode) {
        if (roadId != null && !roadId.isBlank()) {
            return detectRoadSpillback(sid, roadId, sceneCode);
        }
        if (intersectionId != null && !intersectionId.isBlank()) {
            return detectIntersectionSpillback(sid, intersectionId, sceneCode);
        }
        return new DiagnosisReport(
                "缺少 roadId 或 intersectionId，无法检测下游溢出风险",
                List.of("detect_spillback_risk 至少需要 roadId 或 intersectionId"),
                List.of("未知"),
                List.of("目标范围不足"),
                List.of("请指定道路或路口后重新检测"),
                0.2,
                List.of("需要人工确认检测目标"),
                Map.of(),
                Instant.now()
        );
    }

    private DiagnosisReport detectRoadSpillback(String sid, String roadId, String sceneCode) {
        RoadDetail road = runtimeQueryService.getRoadDetail(roadId, sid, sceneCode);
        List<String> evidence = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        List<String> scope = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetType", "road");
        data.put("roadId", road.cityflowId());

        boolean risk = false;
        if (road.latestState() == null) {
            evidence.add("road=" + road.cityflowId() + " 当前没有 road_state_snapshot");
            causes.add("缺少道路快照，无法确认是否发生下游溢出");
            recommendations.add("建议确认仿真帧持久化是否正常。");
        } else {
            var state = road.latestState();
            evidence.add("road=" + road.cityflowId()
                    + ", queue_count=" + state.queueCount()
                    + ", vehicle_count=" + state.vehicleCount()
                    + ", avg_speed=" + round(state.avgSpeed()) + "m/s"
                    + ", level=" + state.level()
                    + ", downstream_intersection=" + road.toIntersectionId());
            scope.add("道路 " + road.cityflowId() + " 及下游路口 " + road.toIntersectionId());
            risk = state.queueCount() >= HIGH_QUEUE || state.avgSpeed() <= LOW_SPEED || isCongestedLevel(state.level());
            if (state.queueCount() >= SEVERE_QUEUE || state.avgSpeed() <= SEVERE_LOW_SPEED) {
                causes.add("道路排队或低速达到严重阈值，可能已经阻塞上游进入");
            } else if (risk) {
                causes.add("道路排队、低速或拥堵等级触发溢出风险阈值");
            }
        }

        recommendations.add("建议检查下游路口放行相位和队列排空能力。");
        recommendations.add("如需要限制上游进入或调整绿灯时长，只能生成建议，必须经过安全层和仲裁层。");
        return report(
                risk ? road.cityflowId() + " 存在下游溢出风险" : road.cityflowId() + " 未检测到明确溢出风险",
                evidence,
                scope,
                causes,
                recommendations,
                risk ? 0.78 : 0.55,
                data
        );
    }

    private DiagnosisReport detectIntersectionSpillback(String sid, String intersectionId, String sceneCode) {
        IntersectionDetail intersection = runtimeQueryService.getIntersectionDetail(intersectionId, sid, sceneCode);
        List<String> evidence = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        List<String> scope = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetType", "intersection");
        data.put("intersectionId", intersection.cityflowId());

        int riskRoads = 0;
        for (RoadLinkInfo link : intersection.roadLinks()) {
            if (link.toRoadId() == null || link.toRoadId().isBlank()) {
                continue;
            }
            try {
                RoadDetail downstream = runtimeQueryService.getRoadDetail(link.toRoadId(), sid, sceneCode);
                if (downstream.latestState() == null) {
                    evidence.add("downstream road=" + link.toRoadId() + " 没有 road_state_snapshot");
                    continue;
                }
                var state = downstream.latestState();
                boolean risk = state.queueCount() >= HIGH_QUEUE || state.avgSpeed() <= LOW_SPEED || isCongestedLevel(state.level());
                evidence.add("roadLink " + link.fromRoadId() + "->" + link.toRoadId()
                        + ", movement=" + link.movementType()
                        + ", downstream_queue=" + state.queueCount()
                        + ", downstream_speed=" + round(state.avgSpeed()) + "m/s"
                        + ", downstream_level=" + state.level());
                if (risk) {
                    riskRoads++;
                    scope.add("下游道路 " + link.toRoadId() + " 可能影响 movement=" + link.movementType());
                }
            } catch (BusinessException ex) {
                evidence.add("roadLink " + link.fromRoadId() + "->" + link.toRoadId()
                        + " 下游道路查询失败：" + ex.getMessage());
            }
        }

        if (riskRoads > 0) {
            causes.add("存在 " + riskRoads + " 条下游道路达到排队、低速或拥堵等级阈值");
            causes.add("上游继续放行可能导致路口内部阻塞或绿灯车辆不通行");
            recommendations.add("建议优先检查这些下游道路对应相位，必要时降低上游放行建议，但必须经过安全层。");
        } else {
            causes.add("当前 roadLink 下游道路未触发溢出风险阈值");
            recommendations.add("建议继续结合后续帧观察，避免单帧误判。");
        }

        data.put("roadLinkCount", intersection.roadLinks().size());
        data.put("riskRoadCount", riskRoads);
        return report(
                riskRoads > 0 ? intersection.cityflowId() + " 存在下游溢出风险" : intersection.cityflowId() + " 未检测到明确下游溢出风险",
                evidence,
                scope,
                causes,
                recommendations,
                riskRoads > 0 ? 0.8 : 0.55,
                data
        );
    }

    private DiagnosisReport report(
            String conclusion,
            List<String> evidence,
            List<String> impactScope,
            List<String> causes,
            List<String> recommendations,
            double confidence,
            Map<String, Object> data
    ) {
        return new DiagnosisReport(
                conclusion,
                evidence.isEmpty() ? List.of("没有可用于判断溢出的道路快照证据") : List.copyOf(evidence),
                impactScope.isEmpty() ? List.of("未发现明确受影响道路或 movement") : List.copyOf(impactScope),
                causes.isEmpty() ? List.of("当前证据不足以确认溢出原因") : List.copyOf(causes),
                List.copyOf(recommendations),
                confidence,
                List.of("需要人工确认道路上下游映射、路口内部 laneLink 几何和安全层约束"),
                data,
                Instant.now()
        );
    }

    private boolean isCongestedLevel(String level) {
        if (level == null) {
            return false;
        }
        String value = level.toLowerCase();
        return value.contains("congest") || value.contains("jam") || value.contains("heavy") || value.contains("严重");
    }

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
