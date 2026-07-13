package com.traffic.analysis.repository;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.BuildingSummaryDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.CompositionItemDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.DailyPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.DashboardToastDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.HeatmapCellDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.HourlyPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MetricTrendDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringMetricDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringRecordDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.ScatterPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.StatusBucketDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.StrategyMetricDto;
import com.traffic.analysis.dto.DataAnalysisLiveUpdateResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataAnalysisRepository {

    private static final DateTimeFormatter MONITOR_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public DataAnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DataAnalysisBootstrapResponse loadBootstrapData() {
        DataAnalysisOverview overview = findOverview();
        StreamMetadata streamMetadata = findStreamMetadata();
        return new DataAnalysisBootstrapResponse(
                overview.sampleCount(),
                overview.sampleRate(),
                overview.healthScore(),
                overview.sampledPointId(),
                0L,
                streamMetadata.pollIntervalMs(),
                overview.scatterCorrelation(),
                findMetrics(),
                findMetricTrends(),
                findStatusDistribution(),
                findDailySeries(),
                findHourlySeries(),
                findBuildingSummaries(),
                findHeatmap(),
                findComposition(),
                findScatterPoints(),
                findStrategyMetrics(),
                findRecords(),
                findToasts()
        );
    }

    private DataAnalysisOverview findOverview() {
        return jdbcTemplate.queryForObject("""
                select sample_count, sample_rate, health_score, sampled_point_id, scatter_correlation
                from analytics_overview
                where id = 1
                """, (rs, rowNum) -> new DataAnalysisOverview(
                rs.getInt("sample_count"),
                rs.getInt("sample_rate"),
                rs.getInt("health_score"),
                rs.getString("sampled_point_id"),
                rs.getDouble("scatter_correlation")
        ));
    }

    private StreamMetadata findStreamMetadata() {
        return jdbcTemplate.queryForObject("""
                select dataset_started_at, poll_interval_ms
                from analytics_stream_metadata
                where id = 1
                """, (rs, rowNum) -> new StreamMetadata(
                rs.getTimestamp("dataset_started_at").toLocalDateTime(),
                rs.getInt("poll_interval_ms")
        ));
    }

    private List<MonitoringMetricDto> findMetrics() {
        return jdbcTemplate.query("""
                select detail, label, tone, metric_value
                from analytics_metric
                order by sequence_no
                """, (rs, rowNum) -> new MonitoringMetricDto(
                rs.getString("detail"),
                rs.getString("label"),
                rs.getString("tone"),
                rs.getString("metric_value")
        ));
    }

    private List<MetricTrendDto> findMetricTrends() {
        Map<String, List<Double>> valuesByLabel = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select m.label, p.point_value
                from analytics_metric_trend_point p
                join analytics_metric m on m.sequence_no = p.metric_sequence_no
                order by m.sequence_no, p.point_sequence_no
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> valuesByLabel
                .computeIfAbsent(rs.getString("label"), ignored -> new ArrayList<>())
                .add(rs.getDouble("point_value")));
        return valuesByLabel.entrySet().stream()
                .map(entry -> new MetricTrendDto(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private List<StatusBucketDto> findStatusDistribution() {
        return jdbcTemplate.query("""
                select bucket_count, label, tone
                from analytics_status_bucket
                order by sequence_no
                """, (rs, rowNum) -> new StatusBucketDto(
                rs.getInt("bucket_count"),
                rs.getString("label"),
                rs.getString("tone")
        ));
    }

    private List<DailyPointDto> findDailySeries() {
        return jdbcTemplate.query("""
                select date_label, electricity, hvac, occupancy, water
                from analytics_daily_point
                order by sequence_no
                """, (rs, rowNum) -> new DailyPointDto(
                rs.getString("date_label"),
                rs.getDouble("electricity"),
                rs.getDouble("hvac"),
                rs.getDouble("occupancy"),
                rs.getDouble("water")
        ));
    }

    private List<HourlyPointDto> findHourlySeries() {
        return jdbcTemplate.query("""
                select hour_label, electricity, hvac, occupancy, temperature
                from analytics_hourly_point
                order by sequence_no
                """, (rs, rowNum) -> new HourlyPointDto(
                rs.getString("hour_label"),
                rs.getDouble("electricity"),
                rs.getDouble("hvac"),
                rs.getDouble("occupancy"),
                rs.getDouble("temperature")
        ));
    }

    private List<BuildingSummaryDto> findBuildingSummaries() {
        return jdbcTemplate.query("""
                select average_occupancy, building_id, building_type, efficiency_score,
                       electricity, hvac, status_label, warning_count, water
                from analytics_building_summary
                order by sequence_no
                """, (rs, rowNum) -> new BuildingSummaryDto(
                rs.getDouble("average_occupancy"),
                rs.getString("building_id"),
                rs.getString("building_type"),
                rs.getInt("efficiency_score"),
                rs.getDouble("electricity"),
                rs.getDouble("hvac"),
                rs.getString("status_label"),
                rs.getInt("warning_count"),
                rs.getDouble("water")
        ));
    }

    private List<HeatmapCellDto> findHeatmap() {
        return jdbcTemplate.query("""
                select date_label, electricity, hour_label, intensity, occupancy
                from analytics_heatmap_cell
                order by sequence_no
                """, (rs, rowNum) -> new HeatmapCellDto(
                rs.getString("date_label"),
                rs.getDouble("electricity"),
                rs.getString("hour_label"),
                rs.getDouble("intensity"),
                rs.getDouble("occupancy")
        ));
    }

    private List<CompositionItemDto> findComposition() {
        return jdbcTemplate.query("""
                select color, label, item_value
                from analytics_composition_item
                order by sequence_no
                """, (rs, rowNum) -> new CompositionItemDto(
                rs.getString("color"),
                rs.getString("label"),
                rs.getDouble("item_value")
        ));
    }

    private List<ScatterPointDto> findScatterPoints() {
        return jdbcTemplate.query("""
                select building_id, electricity, hour_label, point_id, occupancy, temperature, tone
                from analytics_scatter_point
                order by sequence_no
                """, (rs, rowNum) -> new ScatterPointDto(
                rs.getString("building_id"),
                rs.getDouble("electricity"),
                rs.getString("hour_label"),
                rs.getString("point_id"),
                rs.getDouble("occupancy"),
                rs.getDouble("temperature"),
                rs.getString("tone")
        ));
    }

    private List<StrategyMetricDto> findStrategyMetrics() {
        return jdbcTemplate.query("""
                select baseline_value, label, max_pressure_value, traffic_r1_value, unit, lower_better
                from analytics_strategy_metric
                order by sequence_no
                """, (rs, rowNum) -> new StrategyMetricDto(
                rs.getDouble("baseline_value"),
                rs.getString("label"),
                rs.getDouble("max_pressure_value"),
                rs.getDouble("traffic_r1_value"),
                rs.getString("unit"),
                rs.getBoolean("lower_better")
        ));
    }

    private List<MonitoringRecordDto> findRecords() {
        return jdbcTemplate.query("""
                select record_id, building_id, building_type, chilled_water_return_temp,
                       chilled_water_supply_temp, device_id, device_status, control_strategy, electricity_kwh,
                       env_humidity, env_temperature, hvac_kwh, monitor_time,
                       occupancy_density, water_m3
                from analytics_monitoring_record
                order by sequence_no
                """, (rs, rowNum) -> new MonitoringRecordDto(
                rs.getLong("record_id"),
                rs.getString("building_id"),
                rs.getString("building_type"),
                rs.getDouble("chilled_water_return_temp"),
                rs.getDouble("chilled_water_supply_temp"),
                rs.getString("device_id"),
                rs.getString("device_status"),
                rs.getString("control_strategy"),
                rs.getDouble("electricity_kwh"),
                rs.getDouble("env_humidity"),
                rs.getDouble("env_temperature"),
                rs.getDouble("hvac_kwh"),
                rs.getString("monitor_time"),
                rs.getDouble("occupancy_density"),
                rs.getDouble("water_m3")
        ));
    }

    private List<DashboardToastDto> findToasts() {
        return jdbcTemplate.query("""
                select toast_id, body, title, tone
                from analytics_toast
                order by sequence_no
                """, (rs, rowNum) -> new DashboardToastDto(
                rs.getLong("toast_id"),
                rs.getString("body"),
                rs.getString("title"),
                rs.getString("tone")
        ));
    }

    public Optional<DataAnalysisLiveUpdateResponse> findNextLiveUpdate(long cursor) {
        LiveUpdateRow row = jdbcTemplate.query("""
                select *
                from analytics_live_update
                where sequence_no = (
                    select min(sequence_no)
                    from analytics_live_update
                    where sequence_no > ?
                )
                """, ps -> ps.setLong(1, Math.max(cursor, 0L)), rs -> rs.next() ? mapLiveUpdateRow(rs) : null);
        if (row == null) {
            return Optional.empty();
        }

        LocalDateTime monitorTime = findStreamMetadata().datasetStartedAt().plusSeconds(row.eventOffsetSeconds());
        return Optional.of(new DataAnalysisLiveUpdateResponse(
                row.sequenceNo(),
                row.sampleCount(),
                row.healthScore(),
                row.sampledPointId(),
                findLiveMetrics(row),
                findLiveStatusDistribution(row),
                new HourlyPointDto(
                        row.hourLabel(),
                        row.hourlyFlow(),
                        0,
                        row.hourlySaturation(),
                        row.hourlyQueue()
                ),
                findLiveComposition(row),
                new MonitoringRecordDto(
                        row.recordId(),
                        row.intersectionLabel(),
                        row.intersectionId(),
                        row.queueLength(),
                        row.saturation(),
                        row.phaseName(),
                        row.deviceStatus(),
                        row.controlStrategy(),
                        row.inflowCount(),
                        row.saturation(),
                        row.averageSpeed(),
                        row.queueLength(),
                        monitorTime.format(MONITOR_TIME_FORMAT),
                        row.saturation(),
                        row.averageDelay()
                ),
                row.toastId() == null ? null : new DashboardToastDto(
                        row.toastId(),
                        row.toastBody(),
                        row.toastTitle(),
                        row.toastTone()
                )
        ));
    }

    private List<MonitoringMetricDto> findLiveMetrics(LiveUpdateRow row) {
        return jdbcTemplate.query("""
                select detail, label, tone
                from analytics_metric
                order by sequence_no
                """, (rs, rowNum) -> new MonitoringMetricDto(
                rs.getString("detail"),
                rs.getString("label"),
                rs.getString("tone"),
                liveMetricValue(rs.getString("label"), row)
        ));
    }

    private String liveMetricValue(String label, LiveUpdateRow row) {
        return switch (label) {
            case "今日累计通行量" -> row.cumulativeTraffic() + " 辆";
            case "当前平均排队长度" -> String.format(Locale.ROOT, "%.1f 辆", row.averageQueue());
            case "当前平均等待时间" -> String.format(Locale.ROOT, "%.0f 秒", row.averageWait());
            case "自适应控制覆盖率" -> String.format(Locale.ROOT, "%.1f%%", row.adaptiveCoverage());
            case "今日拥堵/事件告警" -> row.alertCount() + " 条";
            default -> "0";
        };
    }

    private List<StatusBucketDto> findLiveStatusDistribution(LiveUpdateRow row) {
        return jdbcTemplate.query("""
                select label, tone
                from analytics_status_bucket
                order by sequence_no
                """, (rs, rowNum) -> new StatusBucketDto(
                switch (rs.getString("tone")) {
                    case "emerald" -> row.normalCount();
                    case "amber" -> row.slowCount();
                    case "rose" -> row.congestedCount();
                    case "slate" -> row.offlineCount();
                    default -> 0;
                },
                rs.getString("label"),
                rs.getString("tone")
        ));
    }

    private List<CompositionItemDto> findLiveComposition(LiveUpdateRow row) {
        return jdbcTemplate.query("""
                select sequence_no, color, label
                from analytics_composition_item
                order by sequence_no
                """, (rs, rowNum) -> new CompositionItemDto(
                rs.getString("color"),
                rs.getString("label"),
                switch (rs.getInt("sequence_no")) {
                    case 1 -> row.eastWestStraight();
                    case 2 -> row.northSouthStraight();
                    case 3 -> row.eastWestLeft();
                    case 4 -> row.northSouthLeft();
                    case 5 -> row.emergencyPriority();
                    case 6 -> row.otherDuration();
                    default -> 0;
                }
        ));
    }

    private LiveUpdateRow mapLiveUpdateRow(ResultSet rs) throws SQLException {
        long toastId = rs.getLong("toast_id");
        Long nullableToastId = rs.wasNull() ? null : toastId;
        return new LiveUpdateRow(
                rs.getLong("sequence_no"),
                rs.getInt("event_offset_seconds"),
                rs.getInt("sample_count"),
                rs.getInt("health_score"),
                rs.getString("sampled_point_id"),
                rs.getInt("cumulative_traffic"),
                rs.getDouble("average_queue"),
                rs.getDouble("average_wait"),
                rs.getDouble("adaptive_coverage"),
                rs.getInt("alert_count"),
                rs.getInt("normal_count"),
                rs.getInt("slow_count"),
                rs.getInt("congested_count"),
                rs.getInt("offline_count"),
                rs.getString("hour_label"),
                rs.getDouble("hourly_flow"),
                rs.getDouble("hourly_saturation"),
                rs.getDouble("hourly_queue"),
                rs.getDouble("east_west_straight"),
                rs.getDouble("north_south_straight"),
                rs.getDouble("east_west_left"),
                rs.getDouble("north_south_left"),
                rs.getDouble("emergency_priority"),
                rs.getDouble("other_duration"),
                rs.getLong("record_id"),
                rs.getString("intersection_label"),
                rs.getString("intersection_id"),
                rs.getDouble("inflow_count"),
                rs.getDouble("queue_length"),
                rs.getDouble("average_delay"),
                rs.getDouble("average_speed"),
                rs.getDouble("saturation"),
                rs.getString("phase_name"),
                rs.getString("control_strategy"),
                rs.getString("device_status"),
                nullableToastId,
                rs.getString("toast_title"),
                rs.getString("toast_body"),
                rs.getString("toast_tone")
        );
    }

    private record DataAnalysisOverview(
            int sampleCount,
            int sampleRate,
            int healthScore,
            String sampledPointId,
            double scatterCorrelation
    ) {
    }

    private record StreamMetadata(LocalDateTime datasetStartedAt, int pollIntervalMs) {
    }

    private record LiveUpdateRow(
            long sequenceNo,
            int eventOffsetSeconds,
            int sampleCount,
            int healthScore,
            String sampledPointId,
            int cumulativeTraffic,
            double averageQueue,
            double averageWait,
            double adaptiveCoverage,
            int alertCount,
            int normalCount,
            int slowCount,
            int congestedCount,
            int offlineCount,
            String hourLabel,
            double hourlyFlow,
            double hourlySaturation,
            double hourlyQueue,
            double eastWestStraight,
            double northSouthStraight,
            double eastWestLeft,
            double northSouthLeft,
            double emergencyPriority,
            double otherDuration,
            long recordId,
            String intersectionLabel,
            String intersectionId,
            double inflowCount,
            double queueLength,
            double averageDelay,
            double averageSpeed,
            double saturation,
            String phaseName,
            String controlStrategy,
            String deviceStatus,
            Long toastId,
            String toastTitle,
            String toastBody,
            String toastTone
    ) {
    }
}
