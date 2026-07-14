package com.traffic.agent.analysis;

import com.traffic.agent.analysis.AgentAnalysisDtos.DiagnosisReport;
import com.traffic.runtime.query.RuntimeQueryDtos.CurrentSimulationState;
import com.traffic.runtime.query.RuntimeQueryDtos.IntersectionDetail;
import com.traffic.runtime.query.RuntimeQueryDtos.MovementSnapshot;
import com.traffic.runtime.query.RuntimeQueryDtos.SessionSummary;
import com.traffic.runtime.query.RuntimeQueryDtos.SignalSnapshot;
import com.traffic.simulation.state.LiveSimulationStateService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CongestionDiagnosisServiceTest {

    @Test
    void diagnosisContainsConcreteEvidenceAndSafeRecommendation() {
        LiveSimulationStateService liveSimulationStateService = mock(LiveSimulationStateService.class);
        IntersectionDetail detail = new IntersectionDetail(
                "intersection-uuid",
                "jinan_3x4",
                "intersection_3",
                null,
                "intersection_3",
                "normal",
                false,
                null,
                null,
                0,
                0,
                new SignalSnapshot("intersection-uuid", "intersection_3", 1, "ETWT", 22, 94.2, "HEAVY"),
                List.of(new MovementSnapshot("E_0", 18, 21, 94.2, 2.1, List.of(4, 5, 5, 4), 120.0, 12)),
                List.of(),
                List.of()
        );
        when(liveSimulationStateService.getIntersectionDetail("intersection_3", "sid-1", null)).thenReturn(detail);

        DiagnosisReport report = new CongestionDiagnosisService(liveSimulationStateService)
                .diagnoseCongestion("intersection", "intersection_3", "sid-1", null);

        assertTrue(report.conclusion().contains("intersection_3"));
        assertTrue(report.evidence().stream().anyMatch(item -> item.contains("queue=18")));
        assertTrue(report.evidence().stream().anyMatch(item -> item.contains("avg_wait=94.2s")));
        assertFalse(report.recommendations().isEmpty());
        assertTrue(report.recommendations().stream().anyMatch(item -> item.contains("安全层")));
        assertFalse(report.humanConfirmationRequired().isEmpty());
    }

    @Test
    void networkDiagnosisWithoutFirstFrameReturnsActionableReportInsteadOfFailingTool() {
        LiveSimulationStateService liveSimulationStateService = mock(LiveSimulationStateService.class);
        when(liveSimulationStateService.getCurrentSimulationState(null)).thenReturn(new CurrentSimulationState(
                new SessionSummary("sid-1", "sid-1", "jinan_3x4", "rl", null, null, "created",
                        Instant.now(), null, null, Instant.now()),
                null,
                0,
                List.of()
        ));

        DiagnosisReport report = new CongestionDiagnosisService(liveSimulationStateService)
                .diagnoseCongestion(null, null, null, null);

        assertTrue(report.conclusion().contains("还没有"));
        assertTrue(report.evidence().stream().anyMatch(item -> item.contains("cached_frame_count=0")));
        assertTrue(report.recommendations().stream().anyMatch(item -> item.contains("启动") || item.contains("首帧")));
    }
}
