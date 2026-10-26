package dev.soullink.command;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;

/**
 * Debug view of a level's special spawners (phantoms, patrols, cats, sieges, wandering traders): which
 * instances the level ticks and their countdown fields. Vanilla exposes none of this, and it is test-only,
 * so it reads the private fields by name instead of adding mixins to the mod.
 */
final class SpawnerProbe {
    private SpawnerProbe() {
    }

    static JsonObject describe(ServerLevel level) {
        JsonObject json = new JsonObject();
        json.addProperty("level", Integer.toHexString(System.identityHashCode(level)));
        JsonArray spawners = new JsonArray();
        for (CustomSpawner spawner : customSpawners(level)) {
            JsonObject described = new JsonObject();
            described.addProperty("type", spawner.getClass().getSimpleName());
            described.addProperty("identity", Integer.toHexString(System.identityHashCode(spawner)));
            JsonObject counters = new JsonObject();
            for (Field field : spawner.getClass().getDeclaredFields()) {
                if (field.getType() == int.class && !Modifier.isStatic(field.getModifiers())) {
                    counters.addProperty(field.getName(), (Integer) read(field, spawner));
                }
            }
            described.add("counters", counters);
            spawners.add(described);
        }
        json.add("spawners", spawners);
        // The wandering-trader saved-data type is private to a different package in this game family.
        return json;
    }

    @SuppressWarnings("unchecked")
    private static List<CustomSpawner> customSpawners(ServerLevel level) {
        try {
            return (List<CustomSpawner>) read(ServerLevel.class.getDeclaredField("customSpawners"), level);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("ServerLevel.customSpawners is gone; update the debug probe", e);
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + field, e);
        }
    }
}
