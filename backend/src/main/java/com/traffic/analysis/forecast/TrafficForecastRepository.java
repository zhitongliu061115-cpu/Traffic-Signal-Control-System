package com.traffic.analysis.forecast;

import com.traffic.analysis.forecast.TrafficForecastDtos.Observation;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrafficForecastRepository {

    private final JdbcTemplate jdbcTemplate;

    public TrafficForecastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Observation> findPredictionObservations(int historyDays, int recentLookbackMinutes) {
        if (historyDays < 1) {
            throw new IllegalArgumentException("historyDays must be positive");
        }
        if (recentLookbackMinutes < 1) {
            throw new IllegalArgumentException("recentLookbackMinutes must be positive");
        }
        LocalDateTime latest = jdbcTemplate.query(
                """
                select max(observed_at)
                from traffic_forecast_observation
                where quality_status = 'VALID'
                """,
                rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toLocalDateTime() : null
        );
        if (latest == null) {
            return List.of();
        }
        LocalDateTime earliestRecent = latest.minusMinutes(recentLookbackMinutes - 1L);
        String historicalPlaceholders = String.join(", ", Collections.nCopies(historyDays, "?"));
        String query = """
                with preferred as (
                    select intersection_id, observed_at, observation_source,
                           inflow_vehicles_per_hour, queue_length_vehicles, average_wait_seconds,
                           average_speed_kmh, saturation_percent, phase_name, control_strategy,
                           device_status,
                           row_number() over (
                               partition by intersection_id, observed_at
                               order by case observation_source
                                   when 'REAL' then 0
                                   when 'IMPORTED' then 1
                                   when 'SYNTHETIC' then 2
                                   else 3
                               end
                           ) as source_rank
                    from traffic_forecast_observation
                    where quality_status = 'VALID'
                      and (observed_at between ? and ? or observed_at in (%s))
                )
                select intersection_id, observed_at, observation_source,
                       inflow_vehicles_per_hour, queue_length_vehicles, average_wait_seconds,
                       average_speed_kmh, saturation_percent, phase_name, control_strategy,
                       device_status
                from preferred
                where source_rank = 1
                order by intersection_id, observed_at
                """.formatted(historicalPlaceholders);
        return jdbcTemplate.query(
                query,
                ps -> {
                    ps.setTimestamp(1, Timestamp.valueOf(earliestRecent));
                    ps.setTimestamp(2, Timestamp.valueOf(latest));
                    for (int day = 1; day <= historyDays; day++) {
                        ps.setTimestamp(day + 2, Timestamp.valueOf(latest.minusDays(day)));
                    }
                },
                (rs, rowNum) -> new Observation(
                        rs.getString("intersection_id"),
                        rs.getTimestamp("observed_at").toLocalDateTime().toString(),
                        rs.getString("observation_source"),
                        rs.getDouble("inflow_vehicles_per_hour"),
                        rs.getDouble("queue_length_vehicles"),
                        rs.getDouble("average_wait_seconds"),
                        rs.getDouble("average_speed_kmh"),
                        rs.getDouble("saturation_percent"),
                        rs.getString("phase_name"),
                        rs.getString("control_strategy"),
                        rs.getString("device_status")
                )
        );
    }
}
