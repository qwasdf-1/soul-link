package dev.soullink.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.terrarasa.Json;
import org.junit.jupiter.api.Test;

class SoulLinkStateTest {
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
