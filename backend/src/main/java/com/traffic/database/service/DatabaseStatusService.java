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
            "intersections",
            "lanes",
            "traffic_snapshots",
            "signal_plans",
            "signal_phases",
            "emergency_events",
            "algorithm_runs"
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
        try (ResultSet resultSet = metaData.getTables(null, "public", tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
        return count == null ? 0L : count;
    }
}
