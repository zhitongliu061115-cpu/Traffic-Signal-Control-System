package com.traffic.roadnet.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoadnetResponseDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesLaneWidthsAndLaneLinkPoints() throws Exception {
        String json = """
                {
                  "sceneId": "scene",
                  "intersections": [{"id":"center","x":0,"y":0,"virtual":false}],
                  "roads": [{
                    "id":"road-a","from":"west","to":"center",
                    "points":[{"x":-10,"y":0},{"x":0,"y":0}],
                    "laneCount":1,
                    "lanes":[{"index":0,"width":4.0}]
                  }],
                  "roadLinks": [{
                    "intersectionId":"center","index":0,
                    "fromRoadId":"road-a","toRoadId":"road-b","type":"go_straight",
                    "laneLinks":[{
                      "id":"road-a_0_TO_road-b_0",
                      "startLaneIndex":0,"endLaneIndex":0,
                      "points":[{"x":-1,"y":0},{"x":1,"y":0}]
                    }]
                  }],
                  "phases": []
                }
                """;

        RoadnetResponse response = objectMapper.readValue(json, RoadnetResponse.class);

        assertThat(response.roads().get(0).lanes().get(0).width()).isEqualTo(4.0);
        assertThat(response.roadLinks().get(0).laneLinks().get(0).id())
                .isEqualTo("road-a_0_TO_road-b_0");
        assertThat(response.roadLinks().get(0).laneLinks().get(0).points()).hasSize(2);
    }
}

