package com.cherrytest.cli;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

@FunctionalInterface
public interface RunIdGenerator {
    String next();

    static RunIdGenerator systemClock() {
        return () -> fromInstant(Instant.now());
    }

    static RunIdGenerator fixed(String id) {
        return () -> id;
    }

    static String fromInstant(Instant instant) {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        int suffix = instant.get(java.time.temporal.ChronoField.NANO_OF_SECOND) & 0xffffff;
        return String.format(
                Locale.ROOT,
                "%04d%02d%02d-%02d%02d%02d-%06x",
                utc.getYear(),
                utc.getMonthValue(),
                utc.getDayOfMonth(),
                utc.getHour(),
                utc.getMinute(),
                utc.getSecond(),
                suffix);
    }
}
