package dev.soullink.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;

import dev.terrarasa.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoulLinkStateTest {
    @TempDir
    Path temp;

    @Test
    void oldSavedRunsDefaultToSharedVitalityEnabled() {
        SoulLinkState restored = Json.GSON.fromJson("{\"formatVersion\":1,\"status\":\"RUNNING\"}", SoulLinkState.class);
        assertTrue(restored.sharedVitality);
    }

    @Test
    void malformedParticipantIdsAreDroppedWithoutBreakingStateLoad() throws IOException {
        SoulLinkState state = new SoulLinkState();
        state.status = SoulLinkState.Status.RUNNING;
        state.activeRunId = "de01a8ed-a44b-4b73-a67e-1fc64ac2df19";
        state.participants.add("de01a8ed-a44b-4b73-a67e-1fc64ac2df19");
        state.participants.add("not-a-player-id");
        Path file = this.temp.resolve("state.json");
        Files.writeString(file, Json.GSON.toJson(state));

        SoulLinkState restored = SoulLinkState.load(file);

        assertEquals(List.of("de01a8ed-a44b-4b73-a67e-1fc64ac2df19"), restored.participants);
        assertEquals(SoulLinkState.Status.RUNNING, restored.status);
    }

    @Test
    void eliminatedPlayersMustBelongToTheActiveRun() throws IOException {
        SoulLinkState state = new SoulLinkState();
        state.status = SoulLinkState.Status.RUNNING;
        state.activeRunId = "de01a8ed-a44b-4b73-a67e-1fc64ac2df19";
        state.participants.add("de01a8ed-a44b-4b73-a67e-1fc64ac2df19");
        state.eliminatedParticipants.add("de01a8ed-a44b-4b73-a67e-1fc64ac2df19");
        state.eliminatedParticipants.add("e78e67b5-80b2-49ea-b584-c83b81053b90");
        Path file = this.temp.resolve("state.json");
        Files.writeString(file, Json.GSON.toJson(state));

        SoulLinkState restored = SoulLinkState.load(file);

        assertEquals(List.of("de01a8ed-a44b-4b73-a67e-1fc64ac2df19"), restored.eliminatedParticipants);
    }

    @Test
    void malformedActiveRunIdentityFallsBackToLobby() throws IOException {
        SoulLinkState state = new SoulLinkState();
        state.status = SoulLinkState.Status.RUNNING;
        state.activeRunId = "../outside";
        state.participants.add("de01a8ed-a44b-4b73-a67e-1fc64ac2df19");
        Path file = this.temp.resolve("state.json");
        Files.writeString(file, Json.GSON.toJson(state));

        SoulLinkState restored = SoulLinkState.load(file);

        assertEquals(SoulLinkState.Status.LOBBY, restored.status);
        assertNull(restored.activeRunId);
        assertTrue(restored.participants.isEmpty());
    }

    @Test
    void snapshotCopiesEveryPersistedField() throws IllegalAccessException {
        SoulLinkState state = new SoulLinkState();
        String defaults = Json.GSON.toJson(new SoulLinkState());
        for (Field field : SoulLinkState.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                field.set(state, distinctValue(field.getType()));
            }
        }
        String original = Json.GSON.toJson(state);
        // Every field was changed, so a field the snapshot forgets shows up as a difference below.
        assertNotEquals(defaults, original);

        SoulLinkState snapshot = state.snapshot();
        assertEquals(original, Json.GSON.toJson(snapshot));
        assertNotSame(state.participants, snapshot.participants);
        assertNotSame(state.playerRuns, snapshot.playerRuns);
        state.participants.add("later");
        assertEquals(original, Json.GSON.toJson(snapshot), "the snapshot is independent of later changes");
    }

    private static Object distinctValue(Class<?> type) {
        if (type == int.class) {
            return 7;
        }
        if (type == long.class || type == Long.class) {
            return 7L;
        }
        if (type == float.class) {
            return 7.5F;
        }
        if (type == boolean.class) {
            return true;
        }
        if (type == String.class) {
            return "value";
        }
        if (type == SoulLinkState.Status.class) {
            return SoulLinkState.Status.ENDED;
        }
        if (type == SoulLinkState.Result.class) {
            return SoulLinkState.Result.VICTORY;
        }
        if (type == List.class) {
            return new ArrayList<>(List.of("a", "b"));
        }
        if (type == Map.class) {
            return new LinkedHashMap<>(Map.of("k", "v"));
        }
        throw new AssertionError("add a distinct value for " + type);
    }
}
