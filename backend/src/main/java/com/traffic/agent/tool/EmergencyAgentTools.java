package com.traffic.agent.tool;

import com.traffic.agent.service.AgentEmergencyToolService;
import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class EmergencyAgentTools {

    private final RuntimeQueryService runtimeQueryService;
    private final AgentEmergencyToolService emergencyToolService;

    public EmergencyAgentTools(
            RuntimeQueryService runtimeQueryService,
            AgentEmergencyToolService emergencyToolService
    ) {
        this.runtimeQueryService = runtimeQueryService;
        this.emergencyToolService = emergencyToolService;
    }

    @Tool(name = "get_emergency_events", value = "Query persisted emergency events. Read-only.")
    public AgentToolResult getEmergencyEvents(String sid, String status, Integer limit) {
        return AgentToolSupport.run(
                "get_emergency_events",
                () -> runtimeQueryService.getEmergencyEvents(blankToNull(sid), blankToNull(status), normalizeLimit(limit)),
                "Persisted emergency events from database"
        );
    }

    @Tool(name = "get_emergency_vehicle_status", value = "Query live emergency vehicle status, route progress, ETA and green-wave state. Read-only.")
    public AgentToolResult getEmergencyVehicleStatus(String sid, String vehicleId, Integer limit) {
        return AgentToolSupport.run(
                "get_emergency_vehicle_status",
                () -> emergencyToolService.getEmergencyVehicleStatus(blankToNull(sid), blankToNull(vehicleId), normalizeLimit(limit)),
                "Live emergency vehicle status from simulation cache plus persisted emergency events"
        );
    }

    @Tool(name = "draft_emergency_dispatch", value = "Draft an emergency dispatch route and green-wave plan. Draft-only; does not execute control.")
    public AgentToolResult draftEmergencyDispatch(
            String sid,
            String startIntersection,
            String endIntersection,
            String evId,
            String evType,
            Integer priority
    ) {
        return AgentToolSupport.run(
                "draft_emergency_dispatch",
                () -> emergencyToolService.draftEmergencyDispatch(
                        blankToNull(sid),
                        blankToNull(startIntersection),
                        blankToNull(endIntersection),
                        blankToNull(evId),
                        blankToNull(evType),
                        priority
                ),
                "Draft-only emergency route and green-wave plan; no CityFlow command is sent"
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }
}
