package com.traffic.agent.service;

import com.traffic.common.exception.BusinessException;
import com.traffic.roadnet.dto.PhaseDto;
import com.traffic.roadnet.dto.RoadnetResponse;
import com.traffic.simulation.state.LiveSimulationStateService;
import com.traffic.strategy.phase.JinanPhaseMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ConfigurationConsistencyAuditService {

    private final LiveSimulationStateService liveSimulationStateService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ConfigurationConsistencyAuditService(
            LiveSimulationStateService liveSimulationStateService,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.liveSimulationStateService = liveSimulationStateService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ConfigurationAuditReport audit(String sid, String sceneCode) {
        List<AuditItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        RoadnetResponse roadnet = null;
        try {
            roadnet = liveSimulationStateService.getRoadnetSnapshot(sid, sceneCode);
        } catch (RuntimeException ex) {
            warnings.add("live roadnet unavailable: " + safe(ex.getMessage()));
        }

        if (roadnet == null) {
            items.add(new AuditItem(
                    "cityflow-roadnet",
                    "WARN",
                    "No live roadnet is cached. Create a simulation first, or provide a valid sid.",
                    Map.of()
            ));
        } else {
            auditRoadnetShape(roadnet, items);
            auditPhaseMapping(roadnet, items);
            auditLaneLevelAvailability(sid, items);
        }
        auditDatabaseScene(sceneCode, roadnet, items);
        auditDatabasePhaseMapping(sceneCode, roadnet, items);

        long errorCount = items.stream().filter(item -> "ERROR".equals(item.status())).count();
        long warnCount = items.stream().filter(item -> "WARN".equals(item.status())).count() + warnings.size();
        String status = errorCount > 0 ? "FAILED" : warnCount > 0 ? "WARNING" : "PASSED";
        List<String> recommendations = new ArrayList<>();
        if (errorCount > 0 || warnCount > 0) {
            recommendations.add("修复 phaseCode/phaseIndex/roadnet/数据库映射后，再允许 Traffic-R 决策进入安全层。");
            recommendations.add("如果 Traffic-R 输出频繁被 safety 阻断，优先核对 Traffic-R phaseCode 与 CityFlow phaseIndex 映射。");
        } else {
            recommendations.add("当前未发现核心配置不一致；仍建议在变更 roadnet 或 phase 表后重新审计。");
        }
        return new ConfigurationAuditReport(status, items, warnings, recommendations, Instant.now());
    }

    private void auditRoadnetShape(RoadnetResponse roadnet, List<AuditItem> items) {
        int intersectionCount = roadnet.intersections() == null ? 0 : roadnet.intersections().size();
        int roadCount = roadnet.roads() == null ? 0 : roadnet.roads().size();
        int phaseCount = roadnet.phases() == null ? 0 : roadnet.phases().size();
        items.add(new AuditItem(
                "cityflow-roadnet",
                intersectionCount > 0 && roadCount > 0 ? "PASS" : "ERROR",
                "roadnet contains intersections=" + intersectionCount + ", roads=" + roadCount + ", phases=" + phaseCount,
                Map.of("sceneId", roadnet.sceneId(), "intersectionCount", intersectionCount, "roadCount", roadCount, "phaseCount", phaseCount)
        ));
    }

    private void auditPhaseMapping(RoadnetResponse roadnet, List<AuditItem> items) {
        if (roadnet.phases() == null || roadnet.phases().isEmpty()) {
            items.add(new AuditItem("phase-mapping", "ERROR", "CityFlow roadnet has no phase definitions.", Map.of()));
            return;
        }
        List<String> invalidCodes = new ArrayList<>();
        List<String> mismatchedIndexes = new ArrayList<>();
        for (PhaseDto phase : roadnet.phases()) {
            if (phase == null || !StringUtils.hasText(phase.phaseCode())) {
                continue;
            }
            if (!JinanPhaseMapper.isBusinessPhaseCode(phase.phaseCode())) {
                invalidCodes.add(phase.intersectionId() + ":" + phase.phaseCode());
                continue;
            }
            int expectedCityFlowPhase = JinanPhaseMapper.cityflowPhaseIndex(phase.phaseCode());
            if (expectedCityFlowPhase != phase.phaseIndex()) {
                mismatchedIndexes.add(phase.intersectionId() + ":" + phase.phaseCode()
                        + " expectedCityFlowPhase=" + expectedCityFlowPhase + " actual=" + phase.phaseIndex());
            }
        }
        items.add(new AuditItem(
                "traffic-r-phase-code",
                invalidCodes.isEmpty() ? "PASS" : "ERROR",
                invalidCodes.isEmpty()
                        ? "All live phaseCode values are in Traffic-R supported phase set."
                        : "Unsupported phaseCode values found: " + invalidCodes,
                Map.of("supportedCodes", JinanPhaseMapper.BUSINESS_PHASE_CODES, "invalidCodes", invalidCodes)
        ));
        items.add(new AuditItem(
                "cityflow-phase-index",
                mismatchedIndexes.isEmpty() ? "PASS" : "WARN",
                mismatchedIndexes.isEmpty()
                        ? "Live phaseCode to CityFlow phaseIndex mapping is consistent with JinanPhaseMapper."
                        : "phaseCode to phaseIndex mismatch found: " + mismatchedIndexes,
                Map.of("mismatches", mismatchedIndexes)
        ));
    }

    private void auditLaneLevelAvailability(String sid, List<AuditItem> items) {
        try {
            var state = liveSimulationStateService.getCurrentSimulationState(sid);
            int signalCount = state.signals() == null ? 0 : state.signals().size();
            items.add(new AuditItem(
                    "live-frame-signals",
                    signalCount > 0 ? "PASS" : "WARN",
                    "latest live frame signal count=" + signalCount,
                    Map.of("signalCount", signalCount)
            ));
        } catch (RuntimeException ex) {
            items.add(new AuditItem(
                    "live-frame-signals",
                    "WARN",
                    "Cannot inspect latest live frame: " + safe(ex.getMessage()),
                    Map.of()
            ));
        }
    }

    private void auditDatabaseScene(String sceneCode, RoadnetResponse roadnet, List<AuditItem> items) {
        String resolvedScene = StringUtils.hasText(sceneCode) ? sceneCode : roadnet == null ? null : roadnet.sceneId();
        MapSqlParameterSource params = new MapSqlParameterSource("sceneCode", resolvedScene);
        Long sceneCount = jdbcTemplate.queryForObject("""
                select count(*)
                from scene
                where (:sceneCode is null or scene_code = :sceneCode)
                """, params, Long.class);
        items.add(new AuditItem(
                "database-scene",
                sceneCount != null && sceneCount > 0 ? "PASS" : "WARN",
                "matching database scene count=" + (sceneCount == null ? 0 : sceneCount),
                Map.of("sceneCode", resolvedScene == null ? "" : resolvedScene)
        ));
    }

    private void auditDatabasePhaseMapping(String sceneCode, RoadnetResponse roadnet, List<AuditItem> items) {
        String resolvedScene = StringUtils.hasText(sceneCode) ? sceneCode : roadnet == null ? null : roadnet.sceneId();
        MapSqlParameterSource params = new MapSqlParameterSource("sceneCode", resolvedScene);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select sp.phase_code, sp.phase_index, count(*) as count
                from signal_phase sp
                join intersection i on i.id = sp.intersection_id
                join scene s on s.id = i.scene_id
                where (:sceneCode is null or s.scene_code = :sceneCode)
                group by sp.phase_code, sp.phase_index
                order by sp.phase_code, sp.phase_index
                """, params);
        List<String> unsupported = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String code = String.valueOf(row.get("phase_code"));
            int index = ((Number) row.get("phase_index")).intValue();
            if (!JinanPhaseMapper.isBusinessPhaseCode(code)) {
                unsupported.add(code);
            } else if (JinanPhaseMapper.cityflowPhaseIndex(code) != index) {
                mismatches.add(code + " expected=" + JinanPhaseMapper.cityflowPhaseIndex(code) + " actual=" + index);
            }
        }
        String status = unsupported.isEmpty() && mismatches.isEmpty()
                ? rows.isEmpty() ? "WARN" : "PASS"
                : "ERROR";
        items.add(new AuditItem(
                "database-phase-table",
                status,
                rows.isEmpty()
                        ? "No signal_phase rows found for scene."
                        : "database signal_phase rows=" + rows.size() + ", unsupported=" + unsupported.size() + ", mismatches=" + mismatches.size(),
                Map.of("rows", rows, "unsupported", unsupported, "mismatches", mismatches)
        ));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ConfigurationAuditReport(
            String status,
            List<AuditItem> items,
            List<String> warnings,
            List<String> recommendations,
            Instant generatedAt
    ) {
    }

    public record AuditItem(
            String checkName,
            String status,
            String message,
            Map<String, Object> details
    ) {
    }
}
