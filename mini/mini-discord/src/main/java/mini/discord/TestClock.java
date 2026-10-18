package mini.discord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class TestClock extends Clock {
    private Instant time;

    public TestClock(Instant start) {
        time = start;
    }

    public static TestClock deterministic() {
        return new TestClock(Instant.parse("2030-01-01T00:00:00Z"));
    }

    public synchronized void advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("Clock cannot move backwards");
        time = time.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public synchronized Instant instant() {
        return time;
    }
}
