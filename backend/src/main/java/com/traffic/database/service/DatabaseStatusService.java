package com.traffic.database.service;

import com.traffic.database.dto.DatabaseStatusResponse;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseStatusService {

    private static final List<String> CORE_TABLES = List.of(
            "scene",
            "intersection",
            "road",
            "lane",
            "road_link",
            "lane_link",
            "signal_phase",
            "signal_phase_road_link",
            "signal_timing_plan",
            "signal_timing_plan_phase",
            "safety_constraint",
            "phase_transition_rule",
            "simulation_session",
            "simulation_frame",
            "road_state_snapshot",
            "lane_state_snapshot",
            "intersection_state_snapshot",
            "vehicle_state_snapshot",
            "control_decision",
            "control_decision_trace",
            "traffic_r_inference_log",
            "max_pressure_score",
            "strategy_fallback_event",
            "safety_constraint_event",
            "control_region",
            "control_region_intersection",
            "emergency_event",
            "emergency_route_node",
            "emergency_signal_event",
            "agent_conversation",
            "agent_message",
            "agent_tool_call",
            "operation_audit_log",
            "alert_event",
            "service_health_snapshot",
            "intersections",
            "dashboard_intersection",
            "analytics_overview"
    );

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseStatusService(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    public DatabaseStatusResponse getStatus() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, Long> tableCounts = new LinkedHashMap<>();
            List<String> missingTables = new ArrayList<>();

            for (String tableName : CORE_TABLES) {
                if (tableExists(metaData, tableName)) {
                    tableCounts.put(tableName, countRows(tableName));
                } else {
                    missingTables.add(tableName);
                }
            }

            return new DatabaseStatusResponse(
                    true,
                    metaData.getDatabaseProductName(),
                    metaData.getURL(),
                    tableCounts,
                    missingTables
            );
        } catch (SQLException ex) {
            throw new IllegalStateException("database connection failed", ex);
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        String[] schemas = {null, "public", "PUBLIC"};
        String[] tablePatterns = {tableName, tableName.toLowerCase(), tableName.toUpperCase()};
        for (String schema : schemas) {
            for (String tablePattern : tablePatterns) {
                try (ResultSet resultSet = metaData.getTables(null, schema, tablePattern, new String[]{"TABLE"})) {
                    if (resultSet.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
        return count == null ? 0L : count;
    }
}
