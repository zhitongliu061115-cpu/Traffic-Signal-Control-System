package com.traffic.analysis.repository;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.BuildingSummaryDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.CompositionItemDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.DailyPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.DashboardToastDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.HeatmapCellDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.HourlyPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringMetricDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.MonitoringRecordDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.ScatterPointDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.StatusBucketDto;
import com.traffic.analysis.dto.DataAnalysisBootstrapResponse.StrategyMetricDto;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataAnalysisRepository {

    private static final int REFRESHES_PER_MINUTE = 12;
    private static final DateTimeFormatter SAMPLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public DataAnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DataAnalysisBootstrapResponse loadBootstrapData() {
        Optional<TelemetrySnapshot> telemetry = findLatestTelemetrySnapshot();
        if (telemetry.isPresent()) {
            return buildTelemetryBootstrap(telemetry.get());
        }

        return loadBaselineData();
    }

    public DataAnalysisBootstrapResponse loadBaselineData() {
        DashboardSnapshot snapshot = findDashboardSnapshot();
        List<DailyPointDto> dailySeries = findDailySeries();
        replaceCurrentDailyPoint(dailySeries, snapshot.statistics());

        return new DataAnalysisBootstrapResponse(
                snapshot.intersections().size() + snapshot.roads().size(),
                REFRESHES_PER_MINUTE,
                calculateHealthScore(snapshot),
                findSampledPointId(snapshot.intersections()),
                "dashboard",
                "未运行",
                null,
                buildMetrics(snapshot),
                buildStatusDistribution(snapshot.intersections()),
                dailySeries,
                buildHourlySeries(snapshot),
                buildIntersectionSummaries(snapshot),
                findHeatmap(),
                buildComposition(snapshot),
                findScatterPoints(),
                buildMonitoringRecords(snapshot),
                buildStrategyMetrics(),
                buildToasts(snapshot)
        );
    }

    private DataAnalysisBootstrapResponse buildTelemetryBootstrap(TelemetrySnapshot snapshot) {
        List<TelemetryMetricPoint> trend = findRecentTelemetryMetrics(snapshot.runId(), 12);
        List<DailyPointDto> storedDailySeries = findDailySeries();
        replaceCurrentDailyPoint(storedDailySeries, findDashboardSnapshot().statistics());
        List<DailyPointDto> dailySeries = mergeDailySeries(
                storedDailySeries,
                findTelemetryDailySeries(snapshot.runId())
        );
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("MM-dd"));
        long dailyTraffic = Math.round(dailySeries.stream()
                .filter(point -> today.equals(point.date()))
                .map(DailyPointDto::electricity)
                .findFirst()
                .orElse((double) snapshot.cumulativeTraffic()));
        int sampleCount = jdbcTemplate.queryForObject(
                "select count(*) from simulation_metric_sample",
                Integer.class
        );
        int sampleRate = jdbcTemplate.queryForObject(
                "select count(*) from simulation_metric_sample where recorded_at >= ?",
                Integer.class,
                Timestamp.from(Instant.now().minusSeconds(60))
        );
        double averageQueue = snapshot.intersections().isEmpty()
                ? snapshot.metric().queueCount()
                : snapshot.metric().queueCount() / (double) snapshot.intersections().size();
        int jammedCount = (int) snapshot.intersections().stream()
                .filter(item -> "jammed".equalsIgnoreCase(item.level()))
                .count();
        double coverage = "fixed-time".equalsIgnoreCase(snapshot.controllerType()) ? 0 : 100;

        List<MonitoringMetricDto> metrics = List.of(
                new MonitoringMetricDto(
                        "云数据库当天历史基线叠加仿真会话累计发车/完成车辆数。",
                        "今日累计通行量", "sky", formatInteger(dailyTraffic) + " 辆"),
                new MonitoringMetricDto(
                        "当前策略会话最新帧的路口平均排队车辆数。",
                        "当前平均排队长度", "emerald", formatDecimal(averageQueue) + " 辆"),
                new MonitoringMetricDto(
                        "当前策略会话最新帧的全路网平均等待时间。",
                        "当前平均等待时间", "amber", formatDecimal(snapshot.metric().avgWait()) + " 秒"),
                new MonitoringMetricDto(
                        "当前会话是否启用自适应控制策略。",
                        "自适应控制覆盖率", "sky", formatDecimal(coverage) + "%"),
                new MonitoringMetricDto(
                        "最新帧中处于拥堵状态的路口数。",
                        "今日拥堵/事件告警", "rose", formatInteger(jammedCount) + " 条")
        );

        return new DataAnalysisBootstrapResponse(
                sampleCount,
                sampleRate,
                calculateTelemetryHealth(snapshot.metric(), averageQueue),
                findTelemetrySampledPointId(snapshot.intersections()),
                "simulation",
                snapshot.controllerType(),
                isLive(snapshot.status()) ? snapshot.sid() : null,
                metrics,
                buildTelemetryStatusDistribution(snapshot.intersections()),
                dailySeries,
                buildTelemetryHourlySeries(trend),
                buildTelemetrySummaries(snapshot),
                buildTelemetryHeatmap(trend),
                buildTelemetryComposition(snapshot.roads()),
                buildTelemetryScatter(snapshot),
                buildTelemetryRecords(snapshot),
                buildStrategyMetrics(),
                buildTelemetryToasts(snapshot, sampleCount, sampleRate)
        );
    }

    private Optional<TelemetrySnapshot> findLatestTelemetrySnapshot() {
        List<TelemetryRun> runs = jdbcTemplate.query("""
                select id, sid, scene_id, controller_type, status
                from simulation_run run
                where exists (
                      select 1
                      from simulation_metric_sample metric
                      where metric.run_id = run.id
                  )
                  and (
                      run.status not in ('running', 'paused')
                      or exists (
                          select 1
                          from simulation_metric_sample recent_metric
                          where recent_metric.run_id = run.id
                            and recent_metric.recorded_at >= ?
                      )
                  )
                order by case when run.status in ('running', 'paused') then 0 else 1 end,
                         coalesce(started_at, created_at) desc,
                         created_at desc
                limit 1
                """, (rs, rowNum) -> new TelemetryRun(
                UUID.fromString(rs.getString("id")),
                rs.getString("sid"),
                rs.getString("scene_id"),
                rs.getString("controller_type"),
                rs.getString("status")
        ), Timestamp.from(Instant.now().minusSeconds(60)));
        if (runs.isEmpty()) {
            return Optional.empty();
        }

        TelemetryRun run = runs.get(0);
        String sampleOrder = isLive(run.status())
                ? "order by recorded_at desc"
                : "order by vehicle_count desc, recorded_at desc";
        List<TelemetryMetricPoint> metrics = jdbcTemplate.query("""
                select id, seq, sim_time, recorded_at, vehicle_count,
                       active_vehicle_count, scheduled_departure_count,
                       queue_count, avg_speed, avg_wait, throughput
                from simulation_metric_sample
                where run_id = ?
                """ + sampleOrder + " limit 1", (rs, rowNum) -> mapTelemetryMetric(rs), run.id());
        if (metrics.isEmpty()) {
            return Optional.empty();
        }

        TelemetryMetricPoint metric = metrics.get(0);
        Map<String, Object> summary = jdbcTemplate.queryForMap("""
                select max(throughput) as max_throughput,
                       max(coalesce(scheduled_departure_count, 0)) as max_scheduled
                from simulation_metric_sample
                where run_id = ?
                """, run.id());
        int cumulativeTraffic = Math.max(
                mapInt(summary, "max_throughput"),
                mapInt(summary, "max_scheduled")
        );

        List<TelemetryRoad> roads = jdbcTemplate.query("""
                select road_id, vehicle_count, queue_count, avg_speed, level
                from simulation_road_sample
                where sample_id = ?
                order by road_id
                """, (rs, rowNum) -> new TelemetryRoad(
                rs.getString("road_id"),
                rs.getInt("vehicle_count"),
                rs.getInt("queue_count"),
                rs.getDouble("avg_speed"),
                rs.getString("level")
        ), metric.id());

        List<TelemetryIntersection> intersections = jdbcTemplate.query("""
                select intersection_id, vehicle_count, queue_count, avg_wait, level, phase_code
                from simulation_intersection_sample
                where sample_id = ?
                order by intersection_id
                """, (rs, rowNum) -> new TelemetryIntersection(
                rs.getString("intersection_id"),
                rs.getInt("vehicle_count"),
                rs.getInt("queue_count"),
                rs.getDouble("avg_wait"),
                rs.getString("level"),
                rs.getString("phase_code")
        ), metric.id());

        return Optional.of(new TelemetrySnapshot(
                run.id(),
                run.sid(),
                run.sceneId(),
                run.controllerType(),
                run.status(),
                cumulativeTraffic,
                metric,
                roads,
                intersections
        ));
    }

    private String mapString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            value = row.get(key.toUpperCase(Locale.ROOT));
        }
        return value == null ? null : String.valueOf(value);
    }

    private int mapInt(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            value = row.get(key.toUpperCase(Locale.ROOT));
        }
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean isLive(String status) {
        return "running".equalsIgnoreCase(status) || "paused".equalsIgnoreCase(status);
    }

    private List<TelemetryMetricPoint> findRecentTelemetryMetrics(UUID runId, int limit) {
        List<TelemetryMetricPoint> result = jdbcTemplate.query("""
                select id, seq, sim_time, recorded_at, vehicle_count,
                       active_vehicle_count, scheduled_departure_count,
                       queue_count, avg_speed, avg_wait, throughput
                from simulation_metric_sample
                where run_id = ?
                order by recorded_at desc, seq desc
                limit ?
                """, (rs, rowNum) -> mapTelemetryMetric(rs), runId, limit);
        Collections.reverse(result);
        return result;
    }

    private TelemetryMetricPoint mapTelemetryMetric(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TelemetryMetricPoint(
                UUID.fromString(rs.getString("id")),
                rs.getLong("seq"),
                rs.getDouble("sim_time"),
                rs.getTimestamp("recorded_at").toLocalDateTime(),
                rs.getInt("vehicle_count"),
                (Integer) rs.getObject("active_vehicle_count"),
                (Integer) rs.getObject("scheduled_departure_count"),
                rs.getInt("queue_count"),
                rs.getDouble("avg_speed"),
                rs.getDouble("avg_wait"),
                rs.getInt("throughput")
        );
    }

    private int calculateTelemetryHealth(TelemetryMetricPoint metric, double averageQueue) {
        double speedScore = Math.min(100, metric.avgSpeed() * 2);
        double waitScore = Math.max(0, 100 - metric.avgWait());
        double queueScore = Math.max(0, 100 - averageQueue * 5);
        return (int) Math.round(speedScore * 0.35 + waitScore * 0.4 + queueScore * 0.25);
    }

    private String findTelemetrySampledPointId(List<TelemetryIntersection> intersections) {
        return intersections.stream()
                .max(Comparator.comparingDouble(TelemetryIntersection::avgWait))
                .map(TelemetryIntersection::id)
                .orElse("");
    }

    private List<StatusBucketDto> buildTelemetryStatusDistribution(List<TelemetryIntersection> intersections) {
        int free = 0;
        int slow = 0;
        int jammed = 0;
        int unknown = 0;
        for (TelemetryIntersection intersection : intersections) {
            switch (intersection.level() == null ? "unknown" : intersection.level().toLowerCase(Locale.ROOT)) {
                case "free" -> free++;
                case "slow" -> slow++;
                case "jammed" -> jammed++;
                default -> unknown++;
            }
        }
        return List.of(
                new StatusBucketDto(free, "畅通", "emerald"),
                new StatusBucketDto(slow, "缓行", "amber"),
                new StatusBucketDto(jammed, "拥堵", "rose"),
                new StatusBucketDto(unknown, "未知", "slate")
        );
    }

    private List<DailyPointDto> findTelemetryDailySeries(UUID currentRunId) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        return jdbcTemplate.query("""
                with per_run as (
                    select cast(metric.recorded_at as date) as sample_date,
                           metric.run_id,
                           max(case
                               when metric.throughput > coalesce(metric.scheduled_departure_count, 0)
                                   then metric.throughput
                               else coalesce(metric.scheduled_departure_count, 0)
                           end) as traffic,
                           avg(metric.vehicle_count) as avg_vehicle_count,
                           avg(metric.avg_wait) as avg_wait,
                           avg(metric.queue_count) as avg_queue
                    from simulation_metric_sample metric
                    join simulation_run run on run.id = metric.run_id
                    where run.status = 'finished' or run.id = ?
                    group by cast(metric.recorded_at as date), metric.run_id
                )
                select sample_date,
                       max(traffic) as traffic,
                       avg(avg_vehicle_count) as avg_vehicle_count,
                       avg(avg_wait) as avg_wait,
                       avg(avg_queue) as avg_queue
                from per_run
                group by sample_date
                order by sample_date
                """, (rs, rowNum) -> {
            LocalDate date = rs.getDate("sample_date").toLocalDate();
            return new DailyPointDto(
                    date.format(formatter),
                    rs.getDouble("traffic"),
                    rs.getDouble("avg_vehicle_count"),
                    rs.getDouble("avg_wait"),
                    rs.getDouble("avg_queue")
            );
        }, currentRunId);
    }

    private List<DailyPointDto> mergeDailySeries(
            List<DailyPointDto> storedHistory,
            List<DailyPointDto> telemetryHistory
    ) {
        Map<String, DailyPointDto> byDate = new LinkedHashMap<>();
        storedHistory.forEach(point -> byDate.put(point.date(), point));
        telemetryHistory.forEach(point -> byDate.merge(point.date(), point, (stored, telemetry) ->
                new DailyPointDto(
                        stored.date(),
                        stored.electricity() + telemetry.electricity(),
                        telemetry.hvac(),
                        telemetry.occupancy(),
                        telemetry.water()
                )
        ));
        List<DailyPointDto> combined = new ArrayList<>(byDate.values());
        return combined.subList(Math.max(0, combined.size() - 12), combined.size());
    }

    private List<HourlyPointDto> buildTelemetryHourlySeries(List<TelemetryMetricPoint> trend) {
        int start = Math.max(0, trend.size() - 6);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        return trend.subList(start, trend.size()).stream()
                .map(point -> new HourlyPointDto(
                        point.recordedAt().format(formatter),
                        point.vehicleCount(),
                        point.throughput(),
                        point.queueCount(),
                        point.avgWait()
                ))
                .toList();
    }

    private List<BuildingSummaryDto> buildTelemetrySummaries(TelemetrySnapshot snapshot) {
        return snapshot.intersections().stream()
                .sorted(Comparator.comparingDouble(TelemetryIntersection::avgWait).reversed())
                .limit(4)
                .map(intersection -> new BuildingSummaryDto(
                        intersection.avgWait(),
                        intersection.id(),
                        intersection.id(),
                        Math.max(0, (int) Math.round(100 - intersection.avgWait())),
                        intersection.vehicleCount(),
                        intersection.queueCount(),
                        telemetryStatusLabel(intersection.level()),
                        intersection.queueCount(),
                        intersection.avgWait()
                ))
                .toList();
    }

    private List<HeatmapCellDto> buildTelemetryHeatmap(List<TelemetryMetricPoint> trend) {
        int start = Math.max(0, trend.size() - 7);
        List<TelemetryMetricPoint> points = trend.subList(start, trend.size());
        double maxVehicles = points.stream().mapToDouble(TelemetryMetricPoint::vehicleCount).max().orElse(1);
        double maxQueue = points.stream().mapToDouble(TelemetryMetricPoint::queueCount).max().orElse(1);
        double maxWait = points.stream().mapToDouble(TelemetryMetricPoint::avgWait).max().orElse(1);
        double maxSpeed = points.stream().mapToDouble(TelemetryMetricPoint::avgSpeed).max().orElse(1);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        List<HeatmapCellDto> result = new ArrayList<>();
        for (TelemetryMetricPoint point : points) {
            String time = point.recordedAt().format(formatter);
            result.add(new HeatmapCellDto(time, point.vehicleCount(), "在途车辆", ratio(point.vehicleCount(), maxVehicles), point.vehicleCount()));
            result.add(new HeatmapCellDto(time, point.queueCount(), "排队车辆", ratio(point.queueCount(), maxQueue), point.queueCount()));
            result.add(new HeatmapCellDto(time, point.avgWait(), "平均等待", ratio(point.avgWait(), maxWait), point.avgWait()));
            result.add(new HeatmapCellDto(time, point.avgSpeed(), "平均速度", 1 - ratio(point.avgSpeed(), maxSpeed), point.avgSpeed()));
        }
        return result;
    }

    private List<CompositionItemDto> buildTelemetryComposition(List<TelemetryRoad> roads) {
        Map<String, Double> totals = new LinkedHashMap<>();
        totals.put("畅通路段", 0.0);
        totals.put("缓行路段", 0.0);
        totals.put("拥堵路段", 0.0);
        for (TelemetryRoad road : roads) {
            String label = switch (road.level() == null ? "unknown" : road.level().toLowerCase(Locale.ROOT)) {
                case "jammed" -> "拥堵路段";
                case "slow" -> "缓行路段";
                default -> "畅通路段";
            };
            totals.computeIfPresent(label, (ignored, value) -> value + road.vehicleCount());
        }
        return List.of(
                new CompositionItemDto("#22c55e", "畅通路段", totals.get("畅通路段")),
                new CompositionItemDto("#f59e0b", "缓行路段", totals.get("缓行路段")),
                new CompositionItemDto("#ef4444", "拥堵路段", totals.get("拥堵路段"))
        );
    }

    private List<ScatterPointDto> buildTelemetryScatter(TelemetrySnapshot snapshot) {
        String time = snapshot.metric().recordedAt().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return snapshot.roads().stream()
                .map(road -> new ScatterPointDto(
                        road.id(),
                        road.queueCount(),
                        time,
                        road.id(),
                        road.vehicleCount(),
                        road.avgSpeed(),
                        telemetryTone(road.level())
                ))
                .toList();
    }

    private List<MonitoringRecordDto> buildTelemetryRecords(TelemetrySnapshot snapshot) {
        List<MonitoringRecordDto> result = new ArrayList<>();
        String sampleTime = snapshot.metric().recordedAt().format(SAMPLE_TIME_FORMAT);
        for (int index = 0; index < snapshot.intersections().size(); index++) {
            TelemetryIntersection intersection = snapshot.intersections().get(index);
            result.add(new MonitoringRecordDto(
                    index + 1L,
                    intersection.id(),
                    intersection.id(),
                    intersection.queueCount(),
                    levelIndex(intersection.level()),
                    intersection.phaseCode() == null ? "未知相位" : intersection.phaseCode(),
                    telemetryRecordStatus(intersection.level()),
                    snapshot.controllerType(),
                    intersection.vehicleCount(),
                    levelIndex(intersection.level()),
                    snapshot.metric().avgSpeed(),
                    intersection.queueCount(),
                    sampleTime,
                    levelIndex(intersection.level()),
                    intersection.avgWait()
            ));
        }
        return result;
    }

    private List<DashboardToastDto> buildTelemetryToasts(TelemetrySnapshot snapshot, int sampleCount, int sampleRate) {
        return List.of(new DashboardToastDto(
                1,
                "策略 " + snapshot.controllerType() + " 已累计记录 " + sampleCount
                        + " 帧，最近一分钟 " + sampleRate + " 帧。",
                "实时仿真数据已接入",
                "emerald"
        ));
    }

    private List<StrategyMetricDto> buildStrategyMetrics() {
        List<StrategyAggregate> aggregates = jdbcTemplate.query("""
                with ranked_runs as (
                    select id, controller_type,
                           row_number() over (partition by controller_type order by created_at desc) as run_rank
                    from simulation_run
                )
                select r.controller_type,
                       count(*) as sample_count,
                       avg(s.queue_count) as avg_queue,
                       sum(s.queue_count) as total_queue,
                       avg(s.avg_wait) as avg_wait,
                       avg(s.avg_speed) as avg_speed,
                       max(s.throughput) as throughput,
                       avg(s.vehicle_count) as avg_vehicle_count
                from simulation_metric_sample s
                join ranked_runs r on r.id = s.run_id and r.run_rank = 1
                group by r.controller_type
                order by r.controller_type
                """, (rs, rowNum) -> new StrategyAggregate(
                rs.getString("controller_type"),
                rs.getInt("sample_count"),
                rs.getDouble("avg_queue"),
                rs.getDouble("total_queue"),
                rs.getDouble("avg_wait"),
                rs.getDouble("avg_speed"),
                rs.getDouble("throughput"),
                rs.getDouble("avg_vehicle_count")
        ));
        if (aggregates.isEmpty()) {
            return List.of();
        }

        Map<String, Double> queue = new LinkedHashMap<>();
        Map<String, Double> totalQueue = new LinkedHashMap<>();
        Map<String, Double> wait = new LinkedHashMap<>();
        Map<String, Double> speed = new LinkedHashMap<>();
        Map<String, Double> throughput = new LinkedHashMap<>();
        Map<String, Double> vehicles = new LinkedHashMap<>();
        for (StrategyAggregate aggregate : aggregates) {
            queue.put(aggregate.controllerType(), aggregate.avgQueue());
            totalQueue.put(aggregate.controllerType(), aggregate.totalQueue());
            wait.put(aggregate.controllerType(), aggregate.avgWait());
            speed.put(aggregate.controllerType(), aggregate.avgSpeed());
            throughput.put(aggregate.controllerType(), aggregate.throughput());
            vehicles.put(aggregate.controllerType(), aggregate.avgVehicleCount());
        }
        return List.of(
                new StrategyMetricDto("平均排队车辆数", "辆", true, queue),
                new StrategyMetricDto("累计排队车辆数", "辆", true, totalQueue),
                new StrategyMetricDto("平均等待时间", "秒", true, wait),
                new StrategyMetricDto("平均速度", "km/h", false, speed),
                new StrategyMetricDto("累计通行量", "辆", false, throughput),
                new StrategyMetricDto("平均在途车辆", "辆", false, vehicles)
        );
    }

    private String telemetryStatusLabel(String level) {
        return switch (level == null ? "unknown" : level.toLowerCase(Locale.ROOT)) {
            case "jammed" -> "拥堵";
            case "slow" -> "缓行";
            case "free" -> "畅通";
            default -> "未知";
        };
    }

    private String telemetryTone(String level) {
        return switch (level == null ? "unknown" : level.toLowerCase(Locale.ROOT)) {
            case "jammed" -> "rose";
            case "slow" -> "amber";
            case "free" -> "emerald";
            default -> "sky";
        };
    }

    private String telemetryRecordStatus(String level) {
        return switch (level == null ? "unknown" : level.toLowerCase(Locale.ROOT)) {
            case "jammed" -> "warning";
            case "slow" -> "maintenance";
            case "free" -> "normal";
            default -> "offline";
        };
    }

    private double levelIndex(String level) {
        return switch (level == null ? "unknown" : level.toLowerCase(Locale.ROOT)) {
            case "jammed" -> 90;
            case "slow" -> 60;
            case "free" -> 20;
            default -> 0;
        };
    }

    private double ratio(double value, double max) {
        return max <= 0 ? 0 : Math.max(0, Math.min(1, value / max));
    }

    private DashboardSnapshot findDashboardSnapshot() {
        DashboardStatistics statistics = jdbcTemplate.queryForObject("""
                select total_flow, average_speed, average_wait_time, congestion_index,
                       congested_road_count, optimized_intersection_count, emergency_vehicle_count,
                       device_online_rate, today_alert_count, green_wave_count
                from dashboard_statistics
                where id = 1
                """, (rs, rowNum) -> new DashboardStatistics(
                rs.getInt("total_flow"),
                rs.getDouble("average_speed"),
                rs.getDouble("average_wait_time"),
                rs.getDouble("congestion_index"),
                rs.getInt("congested_road_count"),
                rs.getInt("optimized_intersection_count"),
                rs.getInt("emergency_vehicle_count"),
                rs.getDouble("device_online_rate"),
                rs.getInt("today_alert_count"),
                rs.getInt("green_wave_count")
        ));

        List<DashboardIntersection> intersections = jdbcTemplate.query("""
                select id, name, current_phase, queue_length, average_delay,
                       congestion_index, device_status
                from dashboard_intersection
                order by row_no, col_no
                """, (rs, rowNum) -> new DashboardIntersection(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("current_phase"),
                rs.getDouble("queue_length"),
                rs.getDouble("average_delay"),
                rs.getDouble("congestion_index"),
                rs.getString("device_status")
        ));

        List<DashboardRoad> roads = jdbcTemplate.query("""
                select id, from_intersection_id, to_intersection_id, flow, speed,
                       queue_length, congestion_index
                from dashboard_road
                order by id
                """, (rs, rowNum) -> new DashboardRoad(
                rs.getString("id"),
                rs.getString("from_intersection_id"),
                rs.getString("to_intersection_id"),
                rs.getDouble("flow"),
                rs.getDouble("speed"),
                rs.getDouble("queue_length"),
                rs.getDouble("congestion_index")
        ));

        return new DashboardSnapshot(statistics, intersections, roads);
    }

    private List<MonitoringMetricDto> buildMetrics(DashboardSnapshot snapshot) {
        DashboardStatistics statistics = snapshot.statistics();
        double averageQueue = snapshot.intersections().stream()
                .mapToDouble(DashboardIntersection::queueLength)
                .average()
                .orElse(0);
        double controlCoverage = snapshot.intersections().isEmpty()
                ? 0
                : statistics.optimizedIntersectionCount() * 100.0 / snapshot.intersections().size();

        return List.of(
                new MonitoringMetricDto(
                        "来自路网大屏统计表的当前累计通行量，每 5 秒从云数据库刷新。",
                        "今日累计通行量", "sky", formatInteger(statistics.totalFlow()) + " 辆"),
                new MonitoringMetricDto(
                        "来自当前路网全部路口的平均排队长度，每 5 秒从云数据库刷新。",
                        "当前平均排队长度", "emerald", formatDecimal(averageQueue) + " 辆"),
                new MonitoringMetricDto(
                        "来自路网大屏统计表的当前平均等待时间。",
                        "当前平均等待时间", "amber", formatDecimal(statistics.averageWaitTime()) + " 秒"),
                new MonitoringMetricDto(
                        "已优化路口数占当前路口总数的比例。",
                        "自适应控制覆盖率", "sky", formatDecimal(controlCoverage) + "%"),
                new MonitoringMetricDto(
                        "来自路网大屏统计表的今日告警总数。",
                        "今日拥堵/事件告警", "rose", formatInteger(statistics.todayAlertCount()) + " 条")
        );
    }

    private List<StatusBucketDto> buildStatusDistribution(List<DashboardIntersection> intersections) {
        int normal = 0;
        int slow = 0;
        int congested = 0;
        int unavailable = 0;

        for (DashboardIntersection intersection : intersections) {
            if (!"online".equalsIgnoreCase(intersection.deviceStatus())) {
                unavailable++;
            } else if (intersection.congestionIndex() >= 80) {
                congested++;
            } else if (intersection.congestionIndex() >= 60) {
                slow++;
            } else {
                normal++;
            }
        }

        return List.of(
                new StatusBucketDto(normal, "畅通", "emerald"),
                new StatusBucketDto(slow, "缓行", "amber"),
                new StatusBucketDto(congested, "拥堵", "rose"),
                new StatusBucketDto(unavailable, "离线/故障", "slate")
        );
    }

    private List<BuildingSummaryDto> buildIntersectionSummaries(DashboardSnapshot snapshot) {
        Map<String, RoadAggregate> roadAggregates = aggregateRoadsByIntersection(snapshot.roads());

        return snapshot.intersections().stream()
                .sorted(Comparator.comparingDouble(DashboardIntersection::congestionIndex).reversed())
                .limit(4)
                .map(intersection -> {
                    RoadAggregate roads = roadAggregates.getOrDefault(intersection.id(), RoadAggregate.EMPTY);
                    return new BuildingSummaryDto(
                            intersection.averageDelay(),
                            intersection.id(),
                            intersection.name(),
                            Math.max(0, (int) Math.round(100 - intersection.congestionIndex())),
                            roads.flow(),
                            intersection.queueLength(),
                            statusLabel(intersection),
                            (int) Math.round(intersection.queueLength()),
                            intersection.averageDelay()
                    );
                })
                .toList();
    }

    private List<HourlyPointDto> buildHourlySeries(DashboardSnapshot snapshot) {
        List<HourlyPointDto> result = new ArrayList<>(findHourlySeries());
        int hour = LocalDateTime.now().getHour();
        int slotIndex = hour < 6 ? 0 : hour < 12 ? 1 : hour < 18 ? 2 : 3;
        DashboardStatistics statistics = snapshot.statistics();
        double averageQueue = snapshot.intersections().stream()
                .mapToDouble(DashboardIntersection::queueLength)
                .average()
                .orElse(0);
        HourlyPointDto previous = result.get(slotIndex);
        result.set(slotIndex, new HourlyPointDto(
                previous.hour(),
                statistics.totalFlow(),
                0,
                statistics.congestionIndex(),
                averageQueue
        ));
        return result;
    }

    private List<CompositionItemDto> buildComposition(DashboardSnapshot snapshot) {
        Map<String, Integer> phaseCounts = new LinkedHashMap<>();
        for (DashboardIntersection intersection : snapshot.intersections()) {
            String label = phaseLabel(intersection.currentPhase());
            phaseCounts.merge(label, 1, Integer::sum);
        }

        List<String> colors = List.of("#3b82f6", "#22c55e", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4");
        int totalIntersections = Math.max(snapshot.intersections().size(), 1);
        List<CompositionItemDto> result = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Integer> entry : phaseCounts.entrySet()) {
            double value = snapshot.statistics().totalFlow() * entry.getValue() / (double) totalIntersections;
            result.add(new CompositionItemDto(colors.get(index % colors.size()), entry.getKey(), value));
            index++;
        }
        return result;
    }

    private List<MonitoringRecordDto> buildMonitoringRecords(DashboardSnapshot snapshot) {
        Map<String, RoadAggregate> roadAggregates = aggregateRoadsByIntersection(snapshot.roads());
        String sampledAt = LocalDateTime.now().format(SAMPLE_TIME_FORMAT);
        List<MonitoringRecordDto> result = new ArrayList<>();

        for (int index = 0; index < snapshot.intersections().size(); index++) {
            DashboardIntersection intersection = snapshot.intersections().get(index);
            RoadAggregate roads = roadAggregates.getOrDefault(intersection.id(), RoadAggregate.EMPTY);
            result.add(new MonitoringRecordDto(
                    index + 1L,
                    intersection.name(),
                    intersection.id(),
                    intersection.queueLength(),
                    intersection.congestionIndex(),
                    phaseLabel(intersection.currentPhase()),
                    recordStatus(intersection),
                    "未记录",
                    roads.flow(),
                    intersection.congestionIndex(),
                    roads.averageSpeed(),
                    intersection.queueLength(),
                    sampledAt,
                    intersection.congestionIndex(),
                    intersection.averageDelay()
            ));
        }
        return result;
    }

    private List<DashboardToastDto> buildToasts(DashboardSnapshot snapshot) {
        DashboardIntersection busiest = snapshot.intersections().stream()
                .max(Comparator.comparingDouble(DashboardIntersection::congestionIndex))
                .orElse(null);
        List<DashboardToastDto> result = new ArrayList<>();
        result.add(new DashboardToastDto(
                1,
                "当前指标、状态分布和路口明细已切换为 dashboard 路网表。",
                "云端路网数据已同步",
                "emerald"
        ));
        if (busiest != null && busiest.congestionIndex() >= 80) {
            result.add(new DashboardToastDto(
                    2,
                    busiest.name() + " 当前拥堵指数为 " + formatDecimal(busiest.congestionIndex()) + "。",
                    "高拥堵路口",
                    "rose"
            ));
        }
        return result;
    }

    private int calculateHealthScore(DashboardSnapshot snapshot) {
        double score = (100 - snapshot.statistics().congestionIndex()) * 0.5
                + snapshot.statistics().deviceOnlineRate() * 0.5;
        return (int) Math.round(Math.max(0, Math.min(100, score)));
    }

    private String findSampledPointId(List<DashboardIntersection> intersections) {
        return intersections.stream()
                .max(Comparator.comparingDouble(DashboardIntersection::congestionIndex))
                .map(DashboardIntersection::id)
                .orElse("");
    }

    private void replaceCurrentDailyPoint(List<DailyPointDto> points, DashboardStatistics statistics) {
        if (points.isEmpty()) {
            return;
        }
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd"));
        DailyPointDto current = new DailyPointDto(
                today,
                statistics.totalFlow(),
                0,
                statistics.averageWaitTime(),
                0
        );
        int lastIndex = points.size() - 1;
        if (today.equals(points.get(lastIndex).date())) {
            points.set(lastIndex, current);
        } else {
            points.remove(0);
            points.add(current);
        }
    }

    private Map<String, RoadAggregate> aggregateRoadsByIntersection(List<DashboardRoad> roads) {
        Map<String, MutableRoadAggregate> mutable = new LinkedHashMap<>();
        for (DashboardRoad road : roads) {
            mutable.computeIfAbsent(road.fromIntersectionId(), ignored -> new MutableRoadAggregate()).add(road);
            mutable.computeIfAbsent(road.toIntersectionId(), ignored -> new MutableRoadAggregate()).add(road);
        }
        Map<String, RoadAggregate> result = new LinkedHashMap<>();
        mutable.forEach((id, aggregate) -> result.put(id, aggregate.snapshot()));
        return result;
    }

    private String statusLabel(DashboardIntersection intersection) {
        if (!"online".equalsIgnoreCase(intersection.deviceStatus())) {
            return "设备不可用";
        }
        if (intersection.congestionIndex() >= 80) {
            return "拥堵压控";
        }
        if (intersection.congestionIndex() >= 60) {
            return "缓行监测";
        }
        return "运行平稳";
    }

    private String recordStatus(DashboardIntersection intersection) {
        if ("offline".equalsIgnoreCase(intersection.deviceStatus())) {
            return "offline";
        }
        if (!"online".equalsIgnoreCase(intersection.deviceStatus())) {
            return "maintenance";
        }
        return intersection.congestionIndex() >= 80 ? "warning" : "normal";
    }

    private String phaseLabel(String phase) {
        if (phase == null || phase.isBlank()) {
            return "未知相位";
        }
        return switch (phase.toLowerCase(Locale.ROOT)) {
            case "eastwest_straight" -> "东西直行";
            case "northsouth_straight" -> "南北直行";
            case "eastwest_left" -> "东西左转";
            case "northsouth_left" -> "南北左转";
            case "all_red" -> "全红";
            default -> phase;
        };
    }

    private String formatInteger(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private String formatDecimal(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private List<DailyPointDto> findDailySeries() {
        return new ArrayList<>(jdbcTemplate.query("""
                select date_label, electricity, hvac, occupancy, water
                from analytics_daily_point
                order by sequence_no
                """, (rs, rowNum) -> new DailyPointDto(
                rs.getString("date_label"),
                rs.getDouble("electricity"),
                rs.getDouble("hvac"),
                rs.getDouble("occupancy"),
                rs.getDouble("water")
        )));
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

    private record DashboardSnapshot(
            DashboardStatistics statistics,
            List<DashboardIntersection> intersections,
            List<DashboardRoad> roads
    ) {
    }

    private record DashboardStatistics(
            int totalFlow,
            double averageSpeed,
            double averageWaitTime,
            double congestionIndex,
            int congestedRoadCount,
            int optimizedIntersectionCount,
            int emergencyVehicleCount,
            double deviceOnlineRate,
            int todayAlertCount,
            int greenWaveCount
    ) {
    }

    private record DashboardIntersection(
            String id,
            String name,
            String currentPhase,
            double queueLength,
            double averageDelay,
            double congestionIndex,
            String deviceStatus
    ) {
    }

    private record DashboardRoad(
            String id,
            String fromIntersectionId,
            String toIntersectionId,
            double flow,
            double speed,
            double queueLength,
            double congestionIndex
    ) {
    }

    private record TelemetrySnapshot(
            UUID runId,
            String sid,
            String sceneId,
            String controllerType,
            String status,
            int cumulativeTraffic,
            TelemetryMetricPoint metric,
            List<TelemetryRoad> roads,
            List<TelemetryIntersection> intersections
    ) {
    }

    private record TelemetryRun(
            UUID id,
            String sid,
            String sceneId,
            String controllerType,
            String status
    ) {
    }

    private record TelemetryMetricPoint(
            UUID id,
            long seq,
            double simTime,
            LocalDateTime recordedAt,
            int vehicleCount,
            Integer activeVehicleCount,
            Integer scheduledDepartureCount,
            int queueCount,
            double avgSpeed,
            double avgWait,
            int throughput
    ) {
    }

    private record TelemetryRoad(
            String id,
            int vehicleCount,
            int queueCount,
            double avgSpeed,
            String level
    ) {
    }

    private record TelemetryIntersection(
            String id,
            int vehicleCount,
            int queueCount,
            double avgWait,
            String level,
            String phaseCode
    ) {
    }

    private record StrategyAggregate(
            String controllerType,
            int sampleCount,
            double avgQueue,
            double totalQueue,
            double avgWait,
            double avgSpeed,
            double throughput,
            double avgVehicleCount
    ) {
    }

    private record RoadAggregate(double flow, double averageSpeed) {
        private static final RoadAggregate EMPTY = new RoadAggregate(0, 0);
    }

    private static final class MutableRoadAggregate {
        private double flow;
        private double speedTotal;
        private int roadCount;

        private void add(DashboardRoad road) {
            flow += road.flow();
            speedTotal += road.speed();
            roadCount++;
        }

        private RoadAggregate snapshot() {
            return new RoadAggregate(flow, roadCount == 0 ? 0 : speedTotal / roadCount);
        }
    }
}
