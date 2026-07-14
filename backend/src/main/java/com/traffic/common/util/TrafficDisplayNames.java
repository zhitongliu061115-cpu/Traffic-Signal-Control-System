package com.traffic.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrafficDisplayNames {

    private static final Pattern GRID_INTERSECTION = Pattern.compile("^intersection_(\\d+)_(\\d+)$");

    private static final Map<String, String> SHANGHAI_INTERSECTIONS = Map.ofEntries(
            Map.entry("1:1", "常德路-南京西路"),
            Map.entry("2:1", "茂名北路-南京西路"),
            Map.entry("3:1", "黄陂北路-南京西路"),
            Map.entry("4:1", "西藏中路-南京东路"),
            Map.entry("1:2", "常熟路-淮海中路"),
            Map.entry("2:2", "瑞金二路-淮海中路"),
            Map.entry("3:2", "黄陂南路-淮海中路"),
            Map.entry("4:2", "西藏南路-淮海东路"),
            Map.entry("1:3", "襄阳南路-建国西路"),
            Map.entry("2:3", "瑞金二路-建国中路"),
            Map.entry("3:3", "黄陂南路-建国东路"),
            Map.entry("4:3", "西藏南路-建国东路")
    );

    private static final Map<String, String> HANGZHOU_COLS = Map.of(
            "1", "曙光路/黄龙",
            "2", "武林/湖墅",
            "3", "中河/建国",
            "4", "钱江/秋涛"
    );

    private static final Map<String, String> HANGZHOU_ROWS = Map.of(
            "1", "文二/学院",
            "2", "体育场/凤起",
            "3", "庆春/解放",
            "4", "望江/钱江"
    );

    private TrafficDisplayNames() {
    }

    public static String sceneName(String sceneId) {
        if ("jinan_3x4".equals(sceneId)) {
            return "上海";
        }
        if ("hangzhou_4_4".equals(sceneId)) {
            return "杭州";
        }
        return sceneId == null || sceneId.isBlank() ? "未知场景" : sceneId;
    }

    public static String intersectionName(String sceneId, String intersectionId) {
        Matcher matcher = matcher(intersectionId);
        if (!matcher.matches()) {
            return fallback(intersectionId, "未知路口");
        }
        String key = matcher.group(1) + ":" + matcher.group(2);
        if ("jinan_3x4".equals(sceneId)) {
            return SHANGHAI_INTERSECTIONS.getOrDefault(key, fallback(intersectionId, "上海路口"));
        }
        if ("hangzhou_4_4".equals(sceneId)) {
            String col = HANGZHOU_COLS.get(matcher.group(1));
            String row = HANGZHOU_ROWS.get(matcher.group(2));
            if (col != null && row != null) {
                return row + " × " + col;
            }
        }
        return fallback(intersectionId, "路口");
    }

    public static String roadName(String sceneId, String roadId, String fromIntersectionId, String toIntersectionId) {
        String fromName = intersectionName(sceneId, fromIntersectionId);
        String toName = intersectionName(sceneId, toIntersectionId);
        if (fromName.equals(fallback(fromIntersectionId, "未知路口"))
                || toName.equals(fallback(toIntersectionId, "未知路口"))) {
            return fallback(roadId, "道路");
        }
        return fromName + " 至 " + toName;
    }

    private static Matcher matcher(String intersectionId) {
        return GRID_INTERSECTION.matcher(intersectionId == null ? "" : intersectionId);
    }

    private static String fallback(String value, String label) {
        return value == null || value.isBlank() ? label : value;
    }
}
