package com.traffic.common.util;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class TimeUtils {

    private static final ZoneOffset DEFAULT_OFFSET = ZoneOffset.ofHours(8);

    private TimeUtils() {
    }

    public static String nowRfc3339() {
        return OffsetDateTime.now(DEFAULT_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
