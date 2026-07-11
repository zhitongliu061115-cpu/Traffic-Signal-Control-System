package com.traffic.database.dto;

import java.util.List;
import java.util.Map;

public record DatabaseStatusResponse(
        boolean connected,
        String databaseProductName,
        String url,
        Map<String, Long> tableCounts,
        List<String> missingTables
) {
}
