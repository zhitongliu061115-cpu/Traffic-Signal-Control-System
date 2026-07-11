package com.traffic.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse;
import com.traffic.simulation.dto.IntersectionStateDto;
import com.traffic.simulation.dto.RoadStateDto;
import com.traffic.simulation.dto.SignalStateDto;
import com.traffic.simulation.dto.SimFrameData;
import com.traffic.simulation.dto.SimulationMetricsDto;
import com.traffic.simulation.telemetry.SimulationTelemetryRepository;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@JdbcTest
@Import({DataAnalysisRepository.class, SimulationTelemetryRepository.class})
@ActiveProfiles("test")
class DataAnalysisRepositoryTest {

    @Autowired
    private DataAnalysisRepository repository;

    @Autowired
    private SimulationTelemetryRepository telemetryRepository;

    @Test
    void derivesCurrentAnalysisFromDashboardTables() {
        DataAnalysisBootstrapResponse data = repository.loadBootstrapData();

        assertThat(data.sampleCount()).isEqualTo(29);
        assertThat(data.sampleRate()).isEqualTo(12);
        assertThat(data.healthScore()).isEqualTo(52);
        assertThat(data.metrics())
                .extracting(DataAnalysisBootstrapResponse.MonitoringMetricDto::value)
                .containsExactly("9,372 辆", "13.6 辆", "35.6 秒", "66.7%", "6 条");
        assertThat(data.statusDistribution())
                .extracting(DataAnalysisBootstrapResponse.StatusBucketDto::count)
                .containsExactly(4, 3, 1, 4);
        assertThat(data.records()).hasSize(12);
        assertThat(data.records().get(0).building_type()).isEqualTo("DB-A01");
        assertThat(data.records().get(0).building_id()).isEqualTo("西藏中路-南京东路");
        assertThat(data.dataSource()).isEqualTo("dashboard");
        assertThat(data.liveSid()).isNull();
        assertThat(data.strategyMetrics()).isEmpty();
    }

    @Test
    void usesPersistedSimulationTelemetryForLiveAnalysisAndStrategyMetrics() {
        var runId = telemetryRepository.createRun("sid-max-pressure", "jinan_3x4", "max-pressure", 1.0);
        telemetryRepository.markStarted(runId);
        SimFrameData frame = new SimFrameData(
                30.0,
                "running",
                List.of(),
                List.of(new RoadStateDto("road_1", 18, 6, 31.5, "slow")),
                Map.of(),
                List.of(new IntersectionStateDto("intersection_1", 6, 24.5, "slow")),
                List.of(new SignalStateDto("intersection_1", 2, "northsouth_straight")),
                new SimulationMetricsDto(18, 18, 120, 6, 31.5, 24.5, 120),
                List.of(),
                List.of()
        );
        telemetryRepository.saveFrame(runId, 10, frame);

        DataAnalysisBootstrapResponse data = repository.loadBootstrapData();

        assertThat(data.dataSource()).isEqualTo("simulation");
        assertThat(data.activeStrategy()).isEqualTo("max-pressure");
        assertThat(data.liveSid()).isEqualTo("sid-max-pressure");
        assertThat(data.sampleCount()).isEqualTo(1);
        assertThat(data.metrics())
                .extracting(DataAnalysisBootstrapResponse.MonitoringMetricDto::value)
                .containsExactly("9,492 辆", "6.0 辆", "24.5 秒", "100.0%", "0 条");
        assertThat(data.records()).singleElement().satisfies(record -> {
            assertThat(record.building_type()).isEqualTo("intersection_1");
            assertThat(record.control_strategy()).isEqualTo("max-pressure");
            assertThat(record.device_id()).isEqualTo("northsouth_straight");
        });
        assertThat(data.strategyMetrics()).hasSize(6);
        assertThat(data.strategyMetrics())
                .filteredOn(metric -> metric.label().equals("平均等待时间"))
                .singleElement()
                .satisfies(metric -> assertThat(metric.values()).containsEntry("max-pressure", 24.5));

        SimFrameData drainedFrame = new SimFrameData(
                3600.0,
                "finished",
                List.of(),
                List.of(new RoadStateDto("road_1", 2, 0, 42.0, "free")),
                Map.of(),
                List.of(new IntersectionStateDto("intersection_1", 0, 0, "free")),
                List.of(new SignalStateDto("intersection_1", 2, "northsouth_straight")),
                new SimulationMetricsDto(2, 2, 200, 0, 42.0, 0, 150),
                List.of(),
                List.of()
        );
        telemetryRepository.saveFrame(runId, 11, drainedFrame);
        telemetryRepository.markFinished(runId);
        DataAnalysisBootstrapResponse finished = repository.loadBootstrapData();
        assertThat(finished.liveSid()).isNull();
        assertThat(finished.metrics().get(0).value()).isEqualTo("9,572 辆");
        assertThat(finished.hourlySeries().get(0).electricity()).isEqualTo(18);
        var currentDailyPoint = finished.dailySeries().get(finished.dailySeries().size() - 1);
        assertThat(currentDailyPoint.date())
                .isEqualTo(LocalDate.now().format(DateTimeFormatter.ofPattern("MM-dd")));
        assertThat(currentDailyPoint.electricity()).isEqualTo(9_572);
        assertThat(currentDailyPoint.occupancy()).isEqualTo(12.25);
    }
}
