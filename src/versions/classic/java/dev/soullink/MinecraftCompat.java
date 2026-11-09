package dev.soullink;

import java.util.Set;

import dev.soullink.lobby.LobbyService;
import dev.soullink.run.RunCoordinator;
import dev.soullink.setup.SetupMenu;
import dev.soullink.setup.SetupMenuView;
import dev.terrarasa.ResourceIdentifier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.phys.Vec3;

/** Vanilla adapter for 1.21.1 and 1.21.4. */
public final class MinecraftCompat {
    public record WorldSpawn(ResourceKey<Level> dimension, BlockPos pos, float angle) {
    }

    private MinecraftCompat() {
    }

    public static long globalSeed(MinecraftServer server) {
        return server.getWorldData().worldGenOptions().seed();
    }

    public static ChunkPos chunkPos(BlockPos position) {
        return new ChunkPos(position);
    }

    public static int chunkX(ChunkPos position) {
        return position.x;
    }

    public static int chunkZ(ChunkPos position) {
        return position.z;
    }

    public static Set<String> entityTags(Entity entity) {
        return entity.getTags();
    }

    public static boolean hasOperatorPermission(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }

    public static boolean naturalRegeneration(MinecraftServer server) {
        return server.overworld().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
    }

    public static void setNaturalRegeneration(MinecraftServer server, boolean enabled) {
        server.overworld().getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(enabled, server);
    }

    public static void openSetupMenu(ServerPlayer player, RunCoordinator coordinator, SetupMenuView.Page page, int pageOffset) {
        SetupMenu.open(player, coordinator, page, pageOffset);
    }

    public static void closeSetupMenu(ServerPlayer player) {
        player.closeContainer();
    }

    public static int aliveDragonCrystals(Object fight) {
        return ((EndDragonFight) fight).getCrystalsAlive();
    }

    public static int aliveDragonCrystals(ServerLevel level) {
        return aliveDragonCrystals(level.getDragonFight());
    }

    public static void registerTicketTypes() {
        LobbyService.TICKET = TicketType.create("soullink:lobby", (a, b) -> 0);
    }

    @SuppressWarnings("unchecked")
    public static void addRegionTicket(ServerLevel level, TicketType type, ChunkPos pos, int radius) {
        level.getChunkSource().addRegionTicket((TicketType<ChunkPos>) (Object) type, pos, radius, pos);
    }

    public static void kill(ServerPlayer player) {
        for (var method : ServerPlayer.class.getMethods()) {
            if (method.getReturnType() == void.class && (method.getName().equals("kill") || method.getName().equals("method_6074") || method.getName().startsWith("m_"))) {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ServerLevel.class) {
                    try {
                        method.invoke(player, player.serverLevel());
                        return;
                    } catch (Exception ignored) {
                    }
                } else if (method.getParameterCount() == 0) {
                    try {
                        method.invoke(player);
                        return;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        player.discard();
    }

    public static ClickEvent runCommandClickEvent(String command) {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
    }

    public static HoverEvent showTextHoverEvent(Component text) {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
    }

    public static ResourceKey<Level> dimensionKey(ResourceIdentifier id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path()));
    }

    public static ResourceIdentifier dimensionId(ResourceKey<Level> key) {
        ResourceLocation loc = key.location();
        return ResourceIdentifier.of(loc.getNamespace(), loc.getPath());
    }

    public static String resourceLocationString(ResourceKey<?> key) {
        return key.location().toString();
    }

    public static String playerName(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    public static String playerGameModeName(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer().getName();
    }

    public static int getMinY(LevelHeightAccessor accessor) {
        for (var method : LevelHeightAccessor.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == int.class
                    && java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                try {
                    int val = (int) method.invoke(accessor);
                    if (val != accessor.getHeight()) {
                        return val;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return -64;
    }

    public static int getMaxY(LevelHeightAccessor accessor) {
        return getMinY(accessor) + accessor.getHeight() - 1;
    }

    public static ServerPlayer teleport(ServerPlayer player, ServerLevel destination, Vec3 position, float yRot, float xRot) {
        try {
            for (var method : ServerPlayer.class.getMethods()) {
                if (method.getParameterCount() == 1 && Entity.class.isAssignableFrom(method.getReturnType())) {
                    Class<?> paramType = method.getParameterTypes()[0];
                    for (var ctor : paramType.getConstructors()) {
                        Class<?>[] pTypes = ctor.getParameterTypes();
                        if (pTypes.length == 6
                                && pTypes[0] == ServerLevel.class
                                && pTypes[1] == Vec3.class
                                && pTypes[2] == Vec3.class
                                && pTypes[3] == float.class
                                && pTypes[4] == float.class) {
                            Class<?> postClass = pTypes[5];
                            Object post = null;
                            for (var field : paramType.getFields()) {
                                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && postClass.isAssignableFrom(field.getType())) {
                                    post = field.get(null);
                                    break;
                                }
                            }
                            if (post == null && postClass.isInterface()) {
                                post = java.lang.reflect.Proxy.newProxyInstance(paramType.getClassLoader(), new Class<?>[]{postClass}, (proxy, m, args) -> null);
                            }
                            Object transition = ctor.newInstance(destination, position, Vec3.ZERO, yRot, xRot, post);
                            Object moved = method.invoke(player, transition);
                            if (moved instanceof ServerPlayer sp) {
                                return sp;
                            }
                            return player;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Teleport failed", e);
        }
        return player;
    }

    public static WorldSpawn getInitialSpawn(MinecraftServer server) {
        return new WorldSpawn(Level.OVERWORLD, server.overworld().getSharedSpawnPos(), server.overworld().getSharedSpawnAngle());
    }

    public static void setServerRespawn(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos, float angle) {
        if (dimension.equals(Level.OVERWORLD)) {
            server.overworld().setDefaultSpawnPos(pos, angle);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.setRespawnPosition(dimension, pos, angle, true, false);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Registry<T> lookupOrThrow(RegistryAccess access, ResourceKey<? extends Registry<T>> registryKey) {
        for (var method : access.getClass().getMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ResourceKey.class
                    && Registry.class.isAssignableFrom(method.getReturnType())) {
                try {
                    method.setAccessible(true);
                    return (Registry<T>) method.invoke(access, registryKey);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke registry access method " + method, e);
                }
            }
        }
        throw new RuntimeException("Could not find registry accessor on " + access.getClass());
    }
}
