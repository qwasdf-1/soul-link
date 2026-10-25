package dev.soullink.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.soullink.SoulLink;
import dev.terrarasa.AtomicFiles;
import dev.terrarasa.Json;
import org.jspecify.annotations.Nullable;

/**
 * Writes {@link SoulLinkState} off the server thread. The server thread only takes a snapshot (the state
 * is only touched there); serializing and writing run on one dedicated thread, so writes land in order.
 * Unchanged state is never rewritten, which keeps an idle server free of periodic disk IO.
 */
final class StateWriter {
    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Soul Link state writer");
        thread.setDaemon(true);
        return thread;
    });
    /** Only touched on the writer thread. */
    private @Nullable String lastWritten;

    StateWriter(Path file) {
        this.file = file;
    }

    /** Writes the state and waits for it to reach disk: for transitions a crash must not lose. */
    void save(SoulLinkState state) {
        Future<?> write = submit(state);
        try {
            write.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            SoulLink.LOGGER.error("Could not save Soul Link state", e.getCause());
        }
    }

    /** Writes the state in the background if it changed since the last write. */
    void saveInBackground(SoulLinkState state) {
        submit(state);
    }

    /** Flushes pending writes; the writer accepts nothing afterwards. */
    void close() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                SoulLink.LOGGER.warn("Soul Link state writer did not finish in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Future<?> submit(SoulLinkState state) {
        SoulLinkState snapshot = state.snapshot();
        return this.writer.submit(() -> {
            String json = Json.GSON.toJson(snapshot);
            if (!json.equals(this.lastWritten)) {
                AtomicFiles.writeString(this.file, json);
                this.lastWritten = json;
            }
            return null;
        });
    }
}
