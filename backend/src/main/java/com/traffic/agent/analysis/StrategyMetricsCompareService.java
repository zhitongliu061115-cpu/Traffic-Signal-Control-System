package com.traffic.agent.analysis;

import com.traffic.agent.analysis.AgentAnalysisDtos.RegionMetricsReport;
import com.traffic.agent.analysis.AgentAnalysisDtos.StrategyCompareReport;
import com.traffic.agent.analysis.AgentAnalysisDtos.StrategyMetricItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StrategyMetricsCompareService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StrategyMetricsCompareService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RegionMetricsReport getRegionMetrics(String sid, String regionId, String intersectionIds, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        List<String> ids = splitCsv(intersectionIds);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sid", blankToNull(sid))
                .addValue("regionId", blankToNull(regionId))
                .addValue("intersectionIds", ids)
                .addValue("limit", safeLimit);

        String filters = """
                where (:sid is null or ss.sid = :sid)
                  and (:regionId is null or iss.intersection_id in (
                      select cri.intersection_id
                      from control_region_intersection cri
                      join control_region cr on cr.id = cri.region_id
                      where cr.region_code = :regionId
                  ))
                """;
        if (!ids.isEmpty()) {
            filters += " and i.cityflow_id in (:intersectionIds)\n";
        }

        String metricsSql = """
                select count(distinct iss.intersection_id) as intersection_count,
                       count(*) as sample_count,
                       coalesce(avg(iss.queue_count), 0) as avg_queue,
                       coalesce(max(iss.queue_count), 0) as max_queue,
                       coalesce(avg(iss.avg_wait), 0) as avg_wait,
                       coalesce(max(iss.avg_wait), 0) as max_wait,
                       coalesce(sum(case when iss.queue_count >= 10 or iss.avg_wait >= 60
                           or lower(coalesce(iss.level, '')) like '%congest%'
                           or lower(coalesce(iss.level, '')) like '%heavy%' then 1 else 0 end), 0) as congested_count
                from intersection_state_snapshot iss
                join simulation_frame sf on sf.id = iss.frame_id
                join simulation_session ss on ss.id = sf.session_id
                join intersection i on i.id = iss.intersection_id
                """ + filters;

        RegionAgg agg = jdbcTemplate.query(metricsSql, params, rs -> rs.next() ? mapRegionAgg(rs) : RegionAgg.empty());
        double avgSpeed = queryRegionAvgSpeed(filters, params);

        List<String> evidence = new ArrayList<>();
        evidence.add("intersection_count=" + agg.intersectionCount());
        evidence.add("sample_count=" + agg.sampleCount());
        evidence.add("avg_queue=" + round(agg.avgQueue()) + ", max_queue=" + round(agg.maxQueue()));
        evidence.add("avg_wait=" + round(agg.avgWait()) + "s, max_wait=" + round(agg.maxWait()) + "s");
        evidence.add("avg_road_speed=" + round(avgSpeed) + "m/s");

        List<String> warnings = new ArrayList<>();
        if (agg.sampleCount() == 0) {
            warnings.add("没有命中区域快照样本，请确认 sid、regionId 或 intersectionIds 是否正确");
        }
        if (blankToNull(regionId) == null && ids.isEmpty()) {
            warnings.add("未指定 regionId 或 intersectionIds，结果代表当前过滤条件下的整体样本");
        }

        return new RegionMetricsReport(
                blankToNull(regionId),
                blankToNull(sid),
                agg.intersectionCount(),
                agg.sampleCount(),
                agg.avgQueue(),
                agg.maxQueue(),
                agg.avgWait(),
                agg.maxWait(),
                avgSpeed,
                agg.congestedCount(),
                evidence,
                warnings,
                Instant.now()
        );
    }

    private double queryRegionAvgSpeed(String filters, MapSqlParameterSource params) {
        String roadSql = """
                select coalesce(avg(rs.avg_speed), 0) as avg_speed
                from road_state_snapshot rs
                join simulation_frame sf on sf.id = rs.frame_id
                join simulation_session ss on ss.id = sf.session_id
                join road r on r.id = rs.road_id
                join intersection i on i.id = r.to_intersection_id
                """ + filters.replace("iss.intersection_id", "r.to_intersection_id")
                .replace("iss.queue_count", "0")
                .replace("iss.avg_wait", "0")
                .replace("iss.level", "''");
        Double value = jdbcTemplate.queryForObject(roadSql, params, Double.class);
        return value == null ? 0.0 : value;
    }

    public StrategyCompareReport compareStrategyMetrics(String sids, String sceneCode, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        List<String> sidList = splitCsv(sids);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sids", sidList)
                .addValue("sceneCode", blankToNull(sceneCode))
                .addValue("limit", safeLimit);

        String sql = """
                select ss.sid, ss.controller_type,
                       count(sf.id) as frame_count,
                       coalesce(avg(sf.vehicle_count), 0) as avg_vehicle_count,
                       coalesce(avg(sf.queue_count), 0) as avg_queue_count,
                       coalesce(max(sf.queue_count), 0) as max_queue_count,
                       coalesce(avg(sf.avg_speed), 0) as avg_speed,
                       coalesce(avg(sf.avg_wait), 0) as avg_wait,
                       coalesce(max(sf.throughput), 0) as throughput
                from simulation_session ss
                join scene sc on sc.id = ss.scene_id
                left join simulation_frame sf on sf.session_id = ss.id
                where (:sceneCode is null or sc.scene_code = :sceneCode)
                """;
        if (!sidList.isEmpty()) {
            sql += " and ss.sid in (:sids)\n";
        }
        sql += """
                group by ss.sid, ss.controller_type, coalesce(ss.started_at, ss.created_at)
                order by coalesce(ss.started_at, ss.created_at) desc
                limit :limit
                """;

        List<StrategyMetricItem> items = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            double avgQueue = rs.getDouble("avg_queue_count");
            double avgSpeed = rs.getDouble("avg_speed");
            double avgWait = rs.getDouble("avg_wait");
            return new StrategyMetricItem(
                    rs.getString("sid"),
                    rs.getString("controller_type"),
                    rs.getLong("frame_count"),
                    rs.getDouble("avg_vehicle_count"),
                    avgQueue,
                    rs.getDouble("max_queue_count"),
                    avgSpeed,
                    avgWait,
                    rs.getDouble("throughput"),
                    assessment(avgQueue, avgSpeed, avgWait)
            );
        });

        List<String> evidence = items.stream()
                .map(item -> item.sid() + "/" + item.controllerType()
                        + ": frames=" + item.frameCount()
                        + ", avg_queue=" + round(item.avgQueueCount())
                        + ", avg_wait=" + round(item.avgWait()) + "s"
                        + ", avg_speed=" + round(item.avgSpeed()) + "m/s")
                .toList();
        List<String> warnings = new ArrayList<>();
        if (items.size() < 2) {
            warnings.add("策略对比至少需要两个有帧数据的 session；当前结果只能作为单策略摘要");
        }
        List<String> recommendations = new ArrayList<>();
        bestStrategy(items).ifPresent(best -> recommendations.add("当前样本中综合表现较好的策略是 "
                + best.controllerType() + " / sid=" + best.sid()
                + "，但正式结论必须保证 roadnet、flow、随机种子和仿真时长一致。"));
        recommendations.add("不要基于非同源 session 直接切换线上策略；策略切换只能生成草案并经过人工确认。");

        return new StrategyCompareReport(items, evidence, recommendations, warnings, Instant.now());
    }

    private java.util.Optional<StrategyMetricItem> bestStrategy(List<StrategyMetricItem> items) {
        return items.stream()
                .filter(item -> item.frameCount() > 0)
                .min((a, b) -> Double.compare(score(a), score(b)));
    }

    private double score(StrategyMetricItem item) {
        return item.avgQueueCount() * 2.0 + item.avgWait() - item.avgSpeed();
    }

    private String assessment(double avgQueue, double avgSpeed, double avgWait) {
        if (avgQueue >= 15 || avgWait >= 90 || avgSpeed <= 2.0) {
            return "拥堵压力高";
        }
        if (avgQueue >= 8 || avgWait >= 45 || avgSpeed <= 5.0) {
            return "存在拥堵风险";
        }
        return "运行相对平稳";
    }

    private RegionAgg mapRegionAgg(ResultSet rs) throws SQLException {
        return new RegionAgg(
                rs.getInt("intersection_count"),
                rs.getInt("sample_count"),
                rs.getDouble("avg_queue"),
                rs.getDouble("max_queue"),
                rs.getDouble("avg_wait"),
                rs.getDouble("max_wait"),
                rs.getInt("congested_count")
        );
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[,，;；\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(100)
                .toList();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record RegionAgg(
            int intersectionCount,
            int sampleCount,
            double avgQueue,
            double maxQueue,
            double avgWait,
            double maxWait,
            int congestedCount
    ) {
        static RegionAgg empty() {
            return new RegionAgg(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
