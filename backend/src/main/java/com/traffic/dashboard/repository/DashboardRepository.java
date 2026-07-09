package com.traffic.dashboard.repository;

import com.traffic.dashboard.dto.DashboardAlertDto;
import com.traffic.dashboard.dto.DashboardBootstrapResponse;
import com.traffic.dashboard.dto.DashboardCompareMetricDto;
import com.traffic.dashboard.dto.DashboardEmergencyVehicleDto;
import com.traffic.dashboard.dto.DashboardIntersectionDto;
import com.traffic.dashboard.dto.DashboardRoadDto;
import com.traffic.dashboard.dto.DashboardStatisticsDto;
import com.traffic.dashboard.dto.DashboardTrendPointDto;
import com.traffic.dashboard.dto.DashboardVehicleDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbcTemplate;

    public DashboardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DashboardBootstrapResponse loadBootstrapData() {
        return new DashboardBootstrapResponse(
                findIntersections(),
                findRoads(),
                findVehicles(),
                findEmergencyVehicle(),
                findEmergencyRoute(),
                findAlerts(),
                findStatistics(),
                findCompareMetrics(),
                findCongestionTrend(),
                findAssistantReplies()
        );
    }

    private List<DashboardIntersectionDto> findIntersections() {
        return jdbcTemplate.query("""
                select id, name, x, y, lng, lat, row_no, col_no, current_phase,
                       green_remain, queue_length, average_delay, congestion_index, device_status
                from dashboard_intersection
                order by row_no, col_no
                """, (rs, rowNum) -> new DashboardIntersectionDto(
                rs.getString("id"),
                rs.getString("name"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("lng"),
                rs.getDouble("lat"),
                rs.getInt("row_no"),
                rs.getInt("col_no"),
                rs.getString("current_phase"),
                rs.getInt("green_remain"),
                rs.getInt("queue_length"),
                rs.getDouble("average_delay"),
                rs.getDouble("congestion_index"),
                rs.getString("device_status")
        ));
    }

    private List<DashboardRoadDto> findRoads() {
        return jdbcTemplate.query("""
                select id, from_intersection_id, to_intersection_id, name, flow, speed,
                       queue_length, congestion_index, lane_count, direction, path_json
                from dashboard_road
                order by id
                """, (rs, rowNum) -> new DashboardRoadDto(
                rs.getString("id"),
                rs.getString("from_intersection_id"),
                rs.getString("to_intersection_id"),
                rs.getString("name"),
                rs.getInt("flow"),
                rs.getDouble("speed"),
                rs.getDouble("queue_length"),
                rs.getDouble("congestion_index"),
                rs.getInt("lane_count"),
                rs.getString("direction"),
                rs.getString("path_json")
        ));
    }

    private List<DashboardVehicleDto> findVehicles() {
        return jdbcTemplate.query("""
                select id, road_id, progress, speed, vehicle_type, lane_index
                from dashboard_vehicle
                order by id
                """, (rs, rowNum) -> new DashboardVehicleDto(
                rs.getString("id"),
                rs.getString("road_id"),
                rs.getDouble("progress"),
                rs.getDouble("speed"),
                rs.getString("vehicle_type"),
                rs.getInt("lane_index")
        ));
    }

    private DashboardEmergencyVehicleDto findEmergencyVehicle() {
        return jdbcTemplate.queryForObject("""
                select id, vehicle_type, current_intersection_id, destination, green_wave_active, eta
                from dashboard_emergency_vehicle
                order by id
                limit 1
                """, (rs, rowNum) -> new DashboardEmergencyVehicleDto(
                rs.getString("id"),
                rs.getString("vehicle_type"),
                rs.getString("current_intersection_id"),
                rs.getString("destination"),
                rs.getBoolean("green_wave_active"),
                rs.getInt("eta")
        ));
    }

    private List<String> findEmergencyRoute() {
        return jdbcTemplate.query("""
                select intersection_id
                from dashboard_emergency_route
                order by sequence_no
                """, (rs, rowNum) -> rs.getString("intersection_id"));
    }

    private List<DashboardAlertDto> findAlerts() {
        return jdbcTemplate.query("""
                select id, type, level, title, location, event_time, intersection_id, acknowledged
                from dashboard_alert
                order by event_time desc, id
                """, (rs, rowNum) -> new DashboardAlertDto(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("level"),
                rs.getString("title"),
                rs.getString("location"),
                rs.getString("event_time"),
                rs.getString("intersection_id"),
                rs.getBoolean("acknowledged")
        ));
    }

    private DashboardStatisticsDto findStatistics() {
        return jdbcTemplate.queryForObject("""
                select total_flow, average_speed, average_wait_time, congestion_index,
                       congested_road_count, optimized_intersection_count, emergency_vehicle_count,
                       device_online_rate, today_alert_count, green_wave_count
                from dashboard_statistics
                where id = 1
                """, (rs, rowNum) -> new DashboardStatisticsDto(
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
    }

    private Map<String, DashboardCompareMetricDto> findCompareMetrics() {
        List<Map.Entry<String, DashboardCompareMetricDto>> rows = jdbcTemplate.query("""
                select metric_key, name, traditional_value, ai_value, unit, direction
                from dashboard_compare_metric
                order by metric_key
                """, (rs, rowNum) -> Map.entry(
                rs.getString("metric_key"),
                new DashboardCompareMetricDto(
                        rs.getString("name"),
                        rs.getDouble("traditional_value"),
                        rs.getDouble("ai_value"),
                        rs.getString("unit"),
                        rs.getString("direction")
                )
        ));
        Map<String, DashboardCompareMetricDto> result = new LinkedHashMap<>();
        rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private List<DashboardTrendPointDto> findCongestionTrend() {
        return jdbcTemplate.query("""
                select time_label, metric_value
                from dashboard_congestion_trend
                order by sequence_no
                """, (rs, rowNum) -> new DashboardTrendPointDto(
                rs.getString("time_label"),
                rs.getDouble("metric_value")
        ));
    }

    private Map<String, String> findAssistantReplies() {
        List<Map.Entry<String, String>> rows = jdbcTemplate.query("""
                select keyword, reply
                from dashboard_assistant_reply
                order by keyword
                """, (rs, rowNum) -> Map.entry(
                rs.getString("keyword"),
                rs.getString("reply")
        ));
        Map<String, String> result = new LinkedHashMap<>();
        rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
