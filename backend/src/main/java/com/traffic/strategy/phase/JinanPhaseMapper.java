package com.traffic.strategy.phase;

import java.util.List;
import java.util.Map;

public final class JinanPhaseMapper {

    public static final List<String> BUSINESS_PHASE_CODES = List.of("ETWT", "NTST", "ELWL", "NLSL");

    private static final Map<String, Integer> BUSINESS_CODE_TO_INDEX = Map.of(
            "ETWT", 1,
            "NTST", 2,
            "ELWL", 3,
            "NLSL", 4
    );

    private static final Map<Integer, String> BUSINESS_INDEX_TO_CODE = Map.of(
            1, "ETWT",
            2, "NTST",
            3, "ELWL",
            4, "NLSL"
    );

    private static final Map<String, Integer> BUSINESS_CODE_TO_CITYFLOW_PHASE = Map.of(
            "ETWT", 2,
            "NTST", 3,
            "ELWL", 4,
            "NLSL", 5
    );

    private static final Map<Integer, String> CITYFLOW_PHASE_TO_BUSINESS_CODE = Map.of(
            2, "ETWT",
            3, "NTST",
            4, "ELWL",
            5, "NLSL"
    );

    private JinanPhaseMapper() {
    }

    public static boolean isBusinessPhaseCode(String phaseCode) {
        return phaseCode != null && BUSINESS_CODE_TO_INDEX.containsKey(phaseCode);
    }

    public static int businessIndex(String phaseCode) {
        return BUSINESS_CODE_TO_INDEX.getOrDefault(phaseCode, 1);
    }

    public static String businessCode(int businessIndex) {
        return BUSINESS_INDEX_TO_CODE.getOrDefault(businessIndex, "ETWT");
    }

    public static int cityflowPhaseIndex(String phaseCode) {
        return BUSINESS_CODE_TO_CITYFLOW_PHASE.getOrDefault(phaseCode, 2);
    }

    public static String businessCodeForCityflowPhase(int cityflowPhaseIndex) {
        return CITYFLOW_PHASE_TO_BUSINESS_CODE.get(cityflowPhaseIndex);
    }
}
