package com.traffic.agent.tool;

import com.traffic.runtime.query.RuntimeQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class TrafficRuntimeAgentTools {

    private final RuntimeQueryService runtimeQueryService;

    public TrafficRuntimeAgentTools(RuntimeQueryService runtimeQueryService) {
        this.runtimeQueryService = runtimeQueryService;
    }

    @Tool(name = "get_current_simulation_state", value = "查询当前仿真整体状态，包括会话、最新帧、车辆数、速度、等待和信号状态。只读。")
    public AgentToolResult getCurrentSimulationState(String sid) {
        return AgentToolSupport.run(
                "get_current_simulation_state",
                () -> runtimeQueryService.getCurrentSimulationState(blankToNull(sid)),
                "来自 RuntimeQueryService 的当前仿真状态"
        );
    }

    @Tool(name = "get_intersection_detail", value = "查询指定路口详情，包括当前相位、movement/lane-level 快照、关联道路和 roadLink。只读。")
    public AgentToolResult getIntersectionDetail(String intersectionId, String sid, String sceneCode) {
        return AgentToolSupport.run(
                "get_intersection_detail",
                () -> runtimeQueryService.getIntersectionDetail(intersectionId, blankToNull(sid), blankToNull(sceneCode)),
                "来自 RuntimeQueryService 的路口详情"
        );
    }

    @Tool(name = "get_road_detail", value = "查询指定道路详情，包括道路快照、车辆数、排队数、平均速度、上下游路口和车道列表。只读。")
    public AgentToolResult getRoadDetail(String roadId, String sid, String sceneCode) {
        return AgentToolSupport.run(
                "get_road_detail",
                () -> runtimeQueryService.getRoadDetail(roadId, blankToNull(sid), blankToNull(sceneCode)),
                "来自 RuntimeQueryService 的道路详情"
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
