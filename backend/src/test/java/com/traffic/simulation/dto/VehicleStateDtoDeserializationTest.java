package com.traffic.simulation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleStateDtoDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesLaneLinkDrivableFields() throws Exception {
        String json = """
                {
                  "id":"vehicle-1",
                  "roadId":"road-a",
                  "lane":1,
                  "x":10.0,
                  "y":5.0,
                  "angle":90.0,
                  "speed":6.0,
                  "drivableId":"road-a_1_TO_road-b_0",
                  "drivableType":"lane_link",
                  "distance":5.0,
                  "nextRoadId":"road-b",
                  "nextLane":0
                }
                """;

        VehicleStateDto vehicle = objectMapper.readValue(json, VehicleStateDto.class);

        assertThat(vehicle.drivableType()).isEqualTo("lane_link");
        assertThat(vehicle.drivableId()).isEqualTo("road-a_1_TO_road-b_0");
        assertThat(vehicle.distance()).isEqualTo(5.0);
        assertThat(vehicle.nextRoadId()).isEqualTo("road-b");
        assertThat(vehicle.nextLane()).isZero();
    }
}
