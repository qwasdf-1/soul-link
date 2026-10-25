package dev.soullink.run;

import java.util.Arrays;

import com.google.gson.JsonObject;

/**
 * Rolling cost of Soul Link's own per-tick work, kept separately for ticks with and without a background
 * preparation (vanilla's tick time also covers the staged levels themselves; see {@code /tick query}).
 */
final class TickCost {
    private static final int WINDOW = 20 * 60;

    private final Window idle = new Window();
    private final Window preparing = new Window();

    void record(long nanos, boolean whilePreparing) {
        (whilePreparing ? this.preparing : this.idle).add(nanos);
    }

    JsonObject describe() {
        JsonObject json = new JsonObject();
        json.add("idle", this.idle.describe());
        json.add("preparing", this.preparing.describe());
        return json;
    }

    private static final class Window {
        private final long[] samples = new long[WINDOW];
        private int count;
        private int next;

        void add(long nanos) {
            this.samples[this.next] = nanos;
            this.next = (this.next + 1) % WINDOW;
            this.count = Math.min(this.count + 1, WINDOW);
        }

        JsonObject describe() {
            JsonObject json = new JsonObject();
            json.addProperty("samples", this.count);
            if (this.count == 0) {
                return json;
            }
            long[] sorted = Arrays.copyOf(this.samples, this.count);
            Arrays.sort(sorted);
            json.addProperty("meanMicros", Arrays.stream(sorted).average().orElse(0) / 1000.0);
            json.addProperty("p50Micros", sorted[this.count / 2] / 1000.0);
            json.addProperty("p99Micros", sorted[(int) (this.count * 0.99)] / 1000.0);
            json.addProperty("maxMicros", sorted[this.count - 1] / 1000.0);
            return json;
        }
    }
}
