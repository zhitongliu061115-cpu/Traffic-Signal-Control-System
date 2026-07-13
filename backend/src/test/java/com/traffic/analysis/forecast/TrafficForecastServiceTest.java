package com.traffic.analysis.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.traffic.analysis.forecast.TrafficForecastDtos.ForecastIntersection;
import com.traffic.analysis.forecast.TrafficForecastDtos.ForecastResponse;
import com.traffic.analysis.forecast.TrafficForecastDtos.ForecastTimelinePoint;
import com.traffic.analysis.forecast.TrafficForecastDtos.Observation;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrafficForecastServiceTest {

    @Test
    void returnsUnavailableWhenIntersectionHistoryIsIncomplete() {
        TrafficForecastProperties properties = properties();
        StubRepository repository = new StubRepository(List.of(observation("intersection_1_1", "00:00")));
        StubClient client = new StubClient(properties, null);

        ForecastResponse response = new TrafficForecastService(properties, repository, client).loadForecast();

        assertFalse(response.available());
        assertTrue(response.message().contains("当前满足 0 个路口"));
        assertEquals(0, client.calls);
    }

    @Test
    void callsModelWhenEveryIntersectionHasACompleteWindow() {
        TrafficForecastProperties properties = properties();
        StubRepository repository = new StubRepository(List.of(
                observation("intersection_1_1", "00:00"),
                observation("intersection_1_1", "00:01"),
                observation("intersection_1_1", "00:02"),
                observation("intersection_1_1", "00:03"),
                observation("intersection_1_2", "00:00"),
                observation("intersection_1_2", "00:01"),
                observation("intersection_1_2", "00:02"),
                observation("intersection_1_2", "00:03")
        ));
        ForecastResponse modelResponse = new ForecastResponse(
                true,
                "ok",
                "lgbm-test",
                "LightGBM",
                "2026-07-13T10:00:00Z",
                "2026-07-13T09:59:00",
                "SYNTHETIC:4",
                List.of(new ForecastIntersection(
                        "intersection_1_1", "路口 1-1", 600, 5, 25, "畅通", "free"
                )),
                List.of(new ForecastTimelinePoint(2, "+2分钟", 600, 5, 25, "畅通", "free"))
        );
        StubClient client = new StubClient(properties, modelResponse);

        ForecastResponse response = new TrafficForecastService(properties, repository, client).loadForecast();

        assertTrue(response.available());
        assertEquals("lgbm-test", response.modelVersion());
        assertEquals(1, client.calls);
    }

    private TrafficForecastProperties properties() {
        TrafficForecastProperties properties = new TrafficForecastProperties();
        properties.setExpectedIntersections(2);
        properties.setRecentLookbackMinutes(2);
        properties.setHistoryDays(2);
        properties.setCacheTtlSeconds(0);
        return properties;
    }

    private Observation observation(String intersectionId, String minute) {
        return new Observation(
                intersectionId,
                "2026-07-13T" + minute + ":00",
                "SYNTHETIC",
                600,
                5,
                25,
                40,
                60,
                "东西直行",
                "Traffic-R1",
                "normal"
        );
    }

    private static final class StubRepository extends TrafficForecastRepository {
        private final List<Observation> observations;

        private StubRepository(List<Observation> observations) {
            super(null);
            this.observations = observations;
        }

        @Override
        public List<Observation> findPredictionObservations(int historyDays, int recentLookbackMinutes) {
            return observations;
        }
    }

    private static final class StubClient extends TrafficForecastClient {
        private final ForecastResponse response;
        private int calls;

        private StubClient(TrafficForecastProperties properties, ForecastResponse response) {
            super(properties);
            this.response = response;
        }

        @Override
        public ForecastResponse predict(TrafficForecastDtos.PredictRequest request) {
            calls++;
            return response;
        }
    }
}
