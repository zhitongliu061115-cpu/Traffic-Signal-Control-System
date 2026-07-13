package com.traffic.simulation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalStateDtoDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesAuthoritativePhaseTiming() throws Exception {
        String json = """
                {
                  "intersectionId": "intersection_1_1",
                  "phaseIndex": 3,
                  "phaseCode": "NTST",
                  "remainingSec": 8.5,
                  "phaseStartedAt": 12.0,
                  "appliedDurationSec": 10.0
                }
                """;

        SignalStateDto signal = objectMapper.readValue(json, SignalStateDto.class);

        assertThat(signal.remainingSec()).isEqualTo(8.5);
        assertThat(signal.phaseStartedAt()).isEqualTo(12.0);
        assertThat(signal.appliedDurationSec()).isEqualTo(10.0);
    }

    @Test
    void keepsTimingUnknownWhenOlderFramesOmitIt() throws Exception {
        String json = """
                {
                  "intersectionId": "intersection_1_1",
                  "phaseIndex": 3,
                  "phaseCode": "NTST"
                }
                """;

        SignalStateDto signal = objectMapper.readValue(json, SignalStateDto.class);

        assertThat(signal.remainingSec()).isNull();
        assertThat(signal.phaseStartedAt()).isNull();
        assertThat(signal.appliedDurationSec()).isNull();
    }
}
