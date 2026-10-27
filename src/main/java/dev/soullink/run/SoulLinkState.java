package dev.soullink.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    /** Shared health and food rule captured when the active run starts. */
    public boolean sharedVitality = true;
    /** One-death defeat rule captured when the active run starts. */
    public boolean soulLinkHardcore;
    /** Shared potions for the active run, fixed when the run starts. */
    public boolean sharedPotions;
    /** Half-heart rule fixed when the run starts. */
    public boolean halfHeart;
    /** Saved max-health base values to restore after the half-heart run ends. */
    public Map<String, Double> playerMaxHealthBases = new LinkedHashMap<>();
    /** Pauses only elapsedTicks; the world and shared vitality continue running. */
    public boolean timerPaused;
    public List<String> participants = new ArrayList<>();
    /** Participants eliminated by vanilla hardcore in an unlinked run. */
    public List<String> eliminatedParticipants = new ArrayList<>();
    /** Operator UUIDs that already received the one-time onboarding message. */
    public List<String> onboardedOperators = new ArrayList<>();
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
        copy.sharedVitality = this.sharedVitality;
        copy.soulLinkHardcore = this.soulLinkHardcore;
        copy.sharedPotions = this.sharedPotions;
        copy.halfHeart = this.halfHeart;
        copy.playerMaxHealthBases = new LinkedHashMap<>(this.playerMaxHealthBases);
        copy.timerPaused = this.timerPaused;
        copy.participants = new ArrayList<>(this.participants);
        copy.eliminatedParticipants = new ArrayList<>(this.eliminatedParticipants);
        copy.onboardedOperators = new ArrayList<>(this.onboardedOperators);
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
                boolean sanitized = false;
                if (state.formatVersion != 1) {
                    SoulLink.LOGGER.error("Unsupported Soul Link state version {} in {}; starting from the lobby",
                            state.formatVersion, file);
                    return new SoulLinkState();
                }
                if (state.status == null) {
                    state.status = Status.LOBBY;
                    sanitized = true;
                }
                if (state.result == null) {
                    state.result = Result.NONE;
                    sanitized = true;
                }
                if (state.participants == null) {
                    state.participants = new ArrayList<>();
                    sanitized = true;
                } else {
                    List<String> valid = canonicalUuids(state.participants);
                    sanitized |= !valid.equals(state.participants);
                    state.participants = valid;
                }
                if (state.eliminatedParticipants == null) {
                    state.eliminatedParticipants = new ArrayList<>();
                    sanitized = true;
                } else {
                    List<String> valid = canonicalUuids(state.eliminatedParticipants);
                    valid.removeIf(uuid -> !state.participants.contains(uuid));
                    sanitized |= !valid.equals(state.eliminatedParticipants);
                    state.eliminatedParticipants = valid;
                }
                if (state.playerRuns == null) {
                    state.playerRuns = new LinkedHashMap<>();
                    sanitized = true;
                } else {
                    Map<String, String> valid = new LinkedHashMap<>();
                    state.playerRuns.forEach((playerId, runId) -> {
                        if (isCanonicalUuid(playerId) && ("lobby".equals(runId) || isCanonicalUuid(runId))) {
                            valid.put(playerId, runId);
                        }
                    });
                    sanitized |= !valid.equals(state.playerRuns);
                    state.playerRuns = valid;
                }
                if (state.onboardedOperators == null) {
                    state.onboardedOperators = new ArrayList<>();
                    sanitized = true;
                } else {
                    List<String> valid = canonicalUuids(state.onboardedOperators);
                    sanitized |= !valid.equals(state.onboardedOperators);
                    state.onboardedOperators = valid;
                }
                if (state.playerMaxHealthBases == null) {
                    state.playerMaxHealthBases = new LinkedHashMap<>();
                    sanitized = true;
                } else {
                    Map<String, Double> valid = new LinkedHashMap<>();
                    state.playerMaxHealthBases.forEach((playerId, base) -> {
                        if (isCanonicalUuid(playerId) && base != null && Double.isFinite(base) && base > 0.0) {
                            valid.put(playerId, base);
                        }
                    });
                    sanitized |= !valid.equals(state.playerMaxHealthBases);
                    state.playerMaxHealthBases = valid;
                }
                if (state.activeRunId != null && !isCanonicalUuid(state.activeRunId)) {
                    // A corrupt run identity cannot safely be reconciled or used to admit rejoining players.
                    state.status = Status.LOBBY;
                    state.result = Result.NONE;
                    state.endReason = null;
                    state.activeRunId = null;
                    state.activeRunStarted = false;
                    state.participants.clear();
                    state.eliminatedParticipants.clear();
                    state.sharedHealth = -1.0F;
                    sanitized = true;
                }
                if ((state.status == Status.RUNNING || state.status == Status.ENDED) && state.activeRunId == null) {
                    state.status = Status.LOBBY;
                    state.result = Result.NONE;
                    state.activeRunStarted = false;
                    state.participants.clear();
                    state.eliminatedParticipants.clear();
                    state.sharedHealth = -1.0F;
                    sanitized = true;
                }
                if (state.elapsedTicks < 0 || state.runsStarted < 0 || state.victories < 0) {
                    state.elapsedTicks = Math.max(0, state.elapsedTicks);
                    state.runsStarted = Math.max(0, state.runsStarted);
                    state.victories = Math.max(0, state.victories);
                    sanitized = true;
                }
                if (state.bestVictoryTicks != null && state.bestVictoryTicks < 0) {
                    state.bestVictoryTicks = null;
                    sanitized = true;
                }
                if (sanitized) {
                    SoulLink.LOGGER.warn("Sanitized invalid fields in Soul Link state {}; continuing from the safest recoverable state", file);
                }
                return state;
            }
        } catch (IOException | JsonParseException e) {
            SoulLink.LOGGER.error("Unreadable Soul Link state {}; starting from the lobby", file, e);
        }
        return new SoulLinkState();
    }

    private static List<String> canonicalUuids(List<String> values) {
        LinkedHashSet<String> valid = new LinkedHashSet<>();
        for (String value : values) {
            if (isCanonicalUuid(value)) {
                valid.add(value);
            }
        }
        return new ArrayList<>(valid);
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
