package dev.soullink.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParseException;
import dev.soullink.SoulLink;
import dev.terrarasa.Json;

/**
 * Soul Link's own run state, stored at {@code <world>/soullink/state.json}. It lives outside vanilla's
 * resettable saved data so lobby/meta state can never be wiped by a session reset.
 */
public final class SoulLinkState {
    public enum Status {
        /** No run in progress; players wait in the lobby. */
        LOBBY,
        /** A start was requested and waits for preparation to finish. */
        STARTING,
        RUNNING,
        /**
         * Victory or defeat. After a defeat participants spectate; after a victory they keep playing, so
         * the exit portal and credits work as in vanilla. Either way the next start is a click away.
         */
        ENDED
    }

    public enum Result {
        NONE,
        VICTORY,
        DEFEAT,
        ABANDONED
    }

    public int formatVersion = 1;
    public Status status = Status.LOBBY;
    public Result result = Result.NONE;
    public String endReason;
    /** Run whose data currently backs the canonical dimensions; null for the server's initial worlds. */
    public String activeRunId;
    /** True once players entered the active run. False after crash recovery committed an unstarted run. */
    public boolean activeRunStarted;
    public long seed;
    public int spawnX;
    public int spawnY;
    public int spawnZ;
    public long elapsedTicks;
    /** Shared potions for the active run, fixed when the run starts. */
    public boolean sharedPotions;
    public List<String> participants = new ArrayList<>();
    /** Player UUID to the run their saved player state belongs to (FR-07 for offline players). */
    public Map<String, String> playerRuns = new LinkedHashMap<>();
    public float sharedHealth = -1.0F;
    public int sharedFood = 20;
    public float sharedSaturation = 5.0F;
    public int runsStarted;
    public int victories;
    public Long bestVictoryTicks;

    /** An independent copy to serialize off the server thread; every field must be copied. */
    SoulLinkState snapshot() {
        SoulLinkState copy = new SoulLinkState();
        copy.formatVersion = this.formatVersion;
        copy.status = this.status;
        copy.result = this.result;
        copy.endReason = this.endReason;
        copy.activeRunId = this.activeRunId;
        copy.activeRunStarted = this.activeRunStarted;
        copy.seed = this.seed;
        copy.spawnX = this.spawnX;
        copy.spawnY = this.spawnY;
        copy.spawnZ = this.spawnZ;
        copy.elapsedTicks = this.elapsedTicks;
        copy.sharedPotions = this.sharedPotions;
        copy.participants = new ArrayList<>(this.participants);
        copy.playerRuns = new LinkedHashMap<>(this.playerRuns);
        copy.sharedHealth = this.sharedHealth;
        copy.sharedFood = this.sharedFood;
        copy.sharedSaturation = this.sharedSaturation;
        copy.runsStarted = this.runsStarted;
        copy.victories = this.victories;
        copy.bestVictoryTicks = this.bestVictoryTicks;
        return copy;
    }

    public static SoulLinkState load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new SoulLinkState();
        }
        try {
            SoulLinkState state = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SoulLinkState.class);
            if (state != null) {
                if (state.participants == null) {
                    state.participants = new ArrayList<>();
                }
                if (state.playerRuns == null) {
                    state.playerRuns = new LinkedHashMap<>();
                }
                return state;
            }
        } catch (IOException | JsonParseException e) {
            SoulLink.LOGGER.error("Unreadable Soul Link state {}; starting from the lobby", file, e);
        }
        return new SoulLinkState();
    }
}
