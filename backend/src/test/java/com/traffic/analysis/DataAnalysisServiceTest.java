package com.traffic.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.traffic.analysis.dto.DataAnalysisBootstrapResponse;
import com.traffic.analysis.dto.DataAnalysisLiveUpdateResponse;
import com.traffic.analysis.service.DataAnalysisService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DataAnalysisServiceTest {

    @Autowired
    private DataAnalysisService dataAnalysisService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void loadsDatabaseBootstrapAndReadsLiveUpdatesInCursorOrder() {
        DataAnalysisBootstrapResponse bootstrap = dataAnalysisService.loadBootstrapData();

        assertEquals(0L, bootstrap.liveCursor());
        assertEquals(5000, bootstrap.livePollIntervalMs());
        assertEquals(5, bootstrap.metricTrends().size());
        assertEquals(5, bootstrap.strategyMetrics().size());
        assertFalse(bootstrap.records().isEmpty());
        assertEquals("1,180 辆", bootstrap.metrics().get(0).value());
        assertTrue(bootstrap.dailySeries().stream().allMatch(point -> point.electricity() < 2_000));
        assertEquals(1_320.0, bootstrap.composition().stream().mapToDouble(item -> item.value()).sum(), 0.001);
        assertEquals(List.of(160.0, 410.0, 590.0, 720.0), bootstrap.hourlySeries().stream()
                .map(DataAnalysisBootstrapResponse.HourlyPointDto::electricity)
                .toList());
        assertTrue(bootstrap.records().subList(0, 8).stream()
                .allMatch(record -> "Traffic-R1".equals(record.control_strategy())));
        assertTrue(bootstrap.records().stream()
                .filter(record -> "warning".equals(record.device_status()))
                .allMatch(record -> "FixedTime".equals(record.control_strategy())));

        DataAnalysisLiveUpdateResponse first = dataAnalysisService.loadNextUpdate(0).orElseThrow();
        DataAnalysisLiveUpdateResponse second = dataAnalysisService.loadNextUpdate(first.cursor()).orElseThrow();

        assertEquals(1L, first.cursor());
        assertEquals(2L, second.cursor());
        assertTrue(second.sampleCount() > first.sampleCount());
        assertEquals(5, first.metrics().size());
        assertEquals(4, first.statusDistribution().size());
        assertEquals(6, first.composition().size());
        int firstPassedVehicles = jdbcTemplate.queryForObject(
                "select passed_vehicles from analytics_live_update where sequence_no = 1",
                Integer.class
        );
        int secondPassedVehicles = jdbcTemplate.queryForObject(
                "select passed_vehicles from analytics_live_update where sequence_no = 2",
                Integer.class
        );
        assertEquals((1180 + firstPassedVehicles) + " 辆", first.metrics().get(0).value());
        assertEquals((1180 + firstPassedVehicles + secondPassedVehicles) + " 辆", second.metrics().get(0).value());

        int expectedCumulativeTraffic = 1180;
        for (long sequence = 1; sequence <= 12; sequence++) {
            int passedVehicles = jdbcTemplate.queryForObject(
                    "select passed_vehicles from analytics_live_update where sequence_no = ?",
                    Integer.class,
                    sequence
            );
            int cumulativeTraffic = jdbcTemplate.queryForObject(
                    "select cumulative_traffic from analytics_live_update where sequence_no = ?",
                    Integer.class,
                    sequence
            );
            assertTrue(passedVehicles >= 3 && passedVehicles <= 6);
            expectedCumulativeTraffic += passedVehicles;
            assertEquals(expectedCumulativeTraffic, cumulativeTraffic);
        }

        List<DataAnalysisLiveUpdateResponse> strategyWindow = new ArrayList<>();
        long cursor = 0;
        for (int index = 0; index < 9; index++) {
            DataAnalysisLiveUpdateResponse update = dataAnalysisService.loadNextUpdate(cursor).orElseThrow();
            strategyWindow.add(update);
            cursor = update.cursor();
        }
        assertTrue(strategyWindow.subList(0, 8).stream()
                .allMatch(update -> "Traffic-R1".equals(update.record().control_strategy())));
        assertEquals("MaxPressure", strategyWindow.get(8).record().control_strategy());
        assertEquals(10_000L, jdbcTemplate.queryForObject(
                "select count(*) from analytics_live_update",
                Long.class
        ));
        assertEquals(3, jdbcTemplate.queryForObject(
                "select min(passed_vehicles) from analytics_live_update",
                Integer.class
        ));
        assertEquals(6, jdbcTemplate.queryForObject(
                "select max(passed_vehicles) from analytics_live_update",
                Integer.class
        ));
        assertTrue(jdbcTemplate.queryForObject(
                "select count(distinct passed_vehicles) from analytics_live_update where sequence_no <= 12",
                Integer.class
        ) >= 3);
        double fixedTimeQueue = jdbcTemplate.queryForObject(
                "select avg(queue_length) from analytics_live_update where control_strategy = 'FixedTime'",
                Double.class
        );
        double maxPressureQueue = jdbcTemplate.queryForObject(
                "select avg(queue_length) from analytics_live_update where control_strategy = 'MaxPressure'",
                Double.class
        );
        double trafficR1Queue = jdbcTemplate.queryForObject(
                "select avg(queue_length) from analytics_live_update where control_strategy = 'Traffic-R1'",
                Double.class
        );
        long fixedTimeCongestion = jdbcTemplate.queryForObject(
                "select count(*) from analytics_live_update where control_strategy = 'FixedTime' and device_status = 'warning'",
                Long.class
        );
        long adaptiveCongestion = jdbcTemplate.queryForObject(
                "select count(*) from analytics_live_update where control_strategy in ('MaxPressure', 'Traffic-R1') and device_status = 'warning'",
                Long.class
        );

        assertTrue(fixedTimeQueue > maxPressureQueue);
        assertTrue(maxPressureQueue > trafficR1Queue);
        assertTrue(fixedTimeCongestion > adaptiveCongestion);
    }
}
