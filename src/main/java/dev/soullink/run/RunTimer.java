package dev.soullink.run;

/** Speedrun timer formatting from server ticks (20 ticks per second). */
public final class RunTimer {
    private RunTimer() {
    }

    public static String format(long ticks) {
        long millis = Math.max(0L, ticks) * 50L;
        long hours = millis / 3_600_000L;
        long minutes = millis / 60_000L % 60L;
        long seconds = millis / 1_000L % 60L;
        long tenths = millis / 100L % 10L;
        return hours > 0
                ? String.format("%d:%02d:%02d.%d", hours, minutes, seconds, tenths)
                : String.format("%02d:%02d.%d", minutes, seconds, tenths);
    }
}
