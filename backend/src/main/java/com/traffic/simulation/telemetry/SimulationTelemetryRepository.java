package com.traffic.simulation.telemetry;

import com.traffic.simulation.dto.IntersectionStateDto;
import com.traffic.simulation.dto.RoadStateDto;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SimulationMetricsDto;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SimulationTelemetryRepository {

    private final JdbcTemplate jdbcTemplate;

    public SimulationTelemetryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID createRun(String sid, String sceneId, String controllerType, Double speed) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into simulation_run (id, sid, scene_id, controller_type, speed, status)
                values (?, ?, ?, ?, ?, 'created')
                """, runId, sid, sceneId, controllerType, speed);
        return runId;
    }

    public void markStarted(UUID runId) {
        jdbcTemplate.update("""
                update simulation_run
                set status = 'running', started_at = coalesce(started_at, ?)
                where id = ?
                """, Timestamp.from(Instant.now()), runId);
    }

    public void markPaused(UUID runId) {
        jdbcTemplate.update("update simulation_run set status = 'paused' where id = ?", runId);
    }

    public void markFinished(UUID runId) {
        jdbcTemplate.update("""
                update simulation_run
                set status = 'finished', ended_at = coalesce(ended_at, ?)
                where id = ?
                """, Timestamp.from(Instant.now()), runId);
    }

    @Transactional
    public void saveFrame(UUID runId, long seq, SimFrameData frame) {
        UUID sampleId = UUID.randomUUID();
        SimulationMetricsDto metrics = metrics(frame);
        jdbcTemplate.update("""
                insert into simulation_metric_sample (
                    id, run_id, seq, sim_time, vehicle_count, active_vehicle_count,
                    scheduled_departure_count, queue_count, avg_speed, avg_wait, throughput
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sampleId,
                runId,
                seq,
                frame.simTime(),
                metrics.vehicleCount(),
                metrics.activeVehicleCount(),
                metrics.scheduledDepartureCount(),
                metrics.queueCount(),
                metrics.avgSpeed(),
                metrics.avgWait(),
                metrics.throughput()
        );

        List<RoadStateDto> roads = frame.roads() == null ? List.of() : frame.roads();
        jdbcTemplate.batchUpdate("""
                insert into simulation_road_sample (
                    sample_id, road_id, vehicle_count, queue_count, avg_speed, level
                ) values (?, ?, ?, ?, ?, ?)
                """, roads, roads.size(), (ps, road) -> {
            ps.setObject(1, sampleId);
            ps.setString(2, road.id());
            ps.setInt(3, road.vehicleCount());
            ps.setInt(4, road.queueCount());
            ps.setDouble(5, road.avgSpeed());
            ps.setString(6, road.level());
        });

        Map<String, String> phases = new HashMap<>();
        if (frame.signals() != null) {
            for (SignalStateDto signal : frame.signals()) {
                phases.put(signal.intersectionId(), signal.phaseCode());
            }
        }
        List<IntersectionStateDto> intersections = frame.intersections() == null ? List.of() : frame.intersections();
        jdbcTemplate.batchUpdate("""
                insert into simulation_intersection_sample (
                    sample_id, intersection_id, vehicle_count, queue_count, avg_wait, level, phase_code
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, intersections, intersections.size(), (ps, intersection) -> {
            ps.setObject(1, sampleId);
            ps.setString(2, intersection.id());
            ps.setInt(3, intersectionVehicleCount(frame, intersection.id()));
            ps.setInt(4, intersection.queueCount());
            ps.setDouble(5, intersection.avgWait());
            ps.setString(6, intersection.level());
            ps.setString(7, phases.get(intersection.id()));
        });
    }

    private int intersectionVehicleCount(SimFrameData frame, String intersectionId) {
        if (frame.laneStates() == null || frame.laneStates().get(intersectionId) == null
                || frame.laneStates().get(intersectionId).lanes() == null) {
            return 0;
        }
        return frame.laneStates().get(intersectionId).lanes().values().stream()
                .filter(lane -> lane.cells() != null)
                .flatMap(lane -> lane.cells().stream())
                .mapToInt(Integer::intValue)
                .sum();
    }

    private SimulationMetricsDto metrics(SimFrameData frame) {
        if (frame.metrics() != null) {
            return frame.metrics();
        }
        List<RoadStateDto> roads = frame.roads() == null ? List.of() : frame.roads();
        List<IntersectionStateDto> intersections = frame.intersections() == null ? List.of() : frame.intersections();
        int vehicleCount = roads.stream().mapToInt(RoadStateDto::vehicleCount).sum();
        int queueCount = roads.stream().mapToInt(RoadStateDto::queueCount).sum();
        double avgSpeed = roads.stream().mapToDouble(RoadStateDto::avgSpeed).average().orElse(0);
        double avgWait = intersections.stream().mapToDouble(IntersectionStateDto::avgWait).average().orElse(0);
        return new SimulationMetricsDto(vehicleCount, null, null, queueCount, avgSpeed, avgWait, 0);
    }
}
