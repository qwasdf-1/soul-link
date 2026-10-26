package dev.soullink.command;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.soullink.mixin.EnderDragonFightAccessor;
import dev.soullink.MinecraftCompat;
import dev.soullink.run.RunCoordinator;
import dev.terrarasa.FaultInjection;
import dev.terrarasa.Json;
import dev.terrarasa.LifecycleException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Player commands match Soul Link Speedrun's; {@code /soullink} holds operator and diagnostic commands. */
public final class SoulLinkCommands {
    private SoulLinkCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, boolean debugCommands) {
        dispatcher.register(Commands.literal("start").executes(ctx -> reply(ctx, RunCoordinator::requestStart)));
        dispatcher.register(Commands.literal("reset").executes(ctx -> reply(ctx, RunCoordinator::requestReset)));
        dispatcher.register(Commands.literal("runinfo")
                .executes(ctx -> reply(ctx, coordinator -> Component.literal(coordinator.runInfo()))));
        dispatcher.register(Commands.literal("stoprun")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> reply(ctx, RunCoordinator::stopRun)));

        var soullink = Commands.literal("soullink")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(ctx -> json(ctx, () -> coordinator().describe())))
                .then(Commands.literal("rules")
                        .then(Commands.literal("sharedPotions")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> reply(ctx, coordinator -> coordinator.setSharedPotions(
                                                BoolArgumentType.getBool(ctx, "enabled")))))))
                .then(Commands.literal("prepare")
                        .executes(ctx -> reply(ctx, coordinator -> coordinator.prepare(null, null)))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(ctx -> reply(ctx, coordinator -> coordinator.prepare(LongArgumentType.getLong(ctx, "seed"), null)))
                                .then(Commands.argument("preloadRadius", IntegerArgumentType.integer(0, 32))
                                        .executes(ctx -> reply(ctx, coordinator -> coordinator.prepare(
                                                LongArgumentType.getLong(ctx, "seed"),
                                                IntegerArgumentType.getInteger(ctx, "preloadRadius")))))));
        if (debugCommands) {
            soullink.then(Commands.literal("debug")
                    .then(Commands.literal("lifecycle").executes(ctx -> json(ctx, () -> coordinator().describe())))
                    .then(Commands.literal("fail")
                            .then(Commands.argument("point", StringArgumentType.word())
                                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                            java.util.stream.Stream.concat(FaultInjection.POINTS.stream(), java.util.stream.Stream.of("none")), builder))
                                    .executes(SoulLinkCommands::armFault)))
                    .then(Commands.literal("gc").executes(ctx -> json(ctx, () -> {
                        System.gc();
                        JsonObject json = new JsonObject();
                        json.addProperty("retiredLevelsReachable", coordinator().activation().reachableRetiredLevels());
                        Runtime runtime = Runtime.getRuntime();
                        json.addProperty("usedHeapMiB", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
                        return json;
                    })))
                    .then(Commands.literal("probe").executes(ctx -> json(ctx, () -> probeRunSpawn(ctx.getSource().getServer())))
                            .then(Commands.argument("dimension", DimensionArgument.dimension())
                                    .then(Commands.argument("x", IntegerArgumentType.integer())
                                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                                    .executes(ctx -> {
                                                        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
                                                        BlockPos center = new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), 0,
                                                                IntegerArgumentType.getInteger(ctx, "z"));
                                                        return json(ctx, () -> SeedProbe.probe(ctx.getSource().getServer(), level, center, false));
                                                    })))))
                    .then(Commands.literal("end").executes(ctx -> json(ctx, () -> describeEnd(ctx.getSource().getServer()))))
                    .then(Commands.literal("spawners").executes(ctx -> json(ctx, () -> SpawnerProbe.describe(ctx.getSource().getServer().overworld()))))
                    .then(Commands.literal("player")
                            .then(Commands.argument("target", EntityArgument.player())
                                    .executes(ctx -> {
                                        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                        return json(ctx, () -> describePlayer(ctx.getSource().getServer(), target));
                                    }))));
        }
        dispatcher.register(soullink);
    }

    private static RunCoordinator coordinator() {
        RunCoordinator coordinator = RunCoordinator.active();
        if (coordinator == null) {
            throw new IllegalStateException("Soul Link is not running");
        }
        return coordinator;
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, Function<RunCoordinator, Component> action) {
        Component message = action.apply(coordinator());
        ctx.getSource().sendSuccess(() -> message, false);
        return 1;
    }

    private static int json(CommandContext<CommandSourceStack> ctx, Supplier<JsonObject> body) {
        String text = Json.COMPACT.toJson(body.get());
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int armFault(CommandContext<CommandSourceStack> ctx) {
        String point = StringArgumentType.getString(ctx, "point");
        try {
            FaultInjection.arm(point.equals("none") ? null : point);
        } catch (LifecycleException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Fault armed: " + point), false);
        return 1;
    }

    /** The Overworld around the run spawn, including far samples. */
    private static JsonObject probeRunSpawn(MinecraftServer server) {
        var state = coordinator().state();
        return SeedProbe.probe(server, server.overworld(), new BlockPos(state.spawnX, state.spawnY, state.spawnZ), true);
    }

    /** The canonical End's vanilla dragon fight: gateway order (seed-derived), exit portal, kill flags. */
    private static JsonObject describeEnd(MinecraftServer server) {
        JsonObject json = new JsonObject();
        ServerLevel end = server.getLevel(Level.END);
        Object fight = end != null ? end.getDragonFight() : null;
        if (fight == null) {
            return json;
        }
        EnderDragonFightAccessor accessor = (EnderDragonFightAccessor) fight;
        JsonArray gateways = new JsonArray();
        accessor.soullink$gateways().forEach(gateways::add);
        json.add("gateways", gateways);
        BlockPos exit = accessor.soullink$exitPortalLocation();
        if (exit != null) {
            JsonArray position = new JsonArray();
            position.add(exit.getX());
            position.add(exit.getY());
            position.add(exit.getZ());
            json.add("exitPortal", position);
        }
        json.addProperty("dragonKilled", accessor.soullink$dragonKilled());
        json.addProperty("previouslyKilled", accessor.soullink$previouslyKilled());
        json.addProperty("aliveCrystals", MinecraftCompat.aliveDragonCrystals(fight));
        ServerBossEvent bar = accessor.soullink$bossBar();
        JsonObject bossBar = new JsonObject();
        bossBar.addProperty("visible", bar.isVisible());
        bossBar.addProperty("progress", bar.getProgress());
        JsonArray viewers = new JsonArray();
        bar.getPlayers().forEach(player -> viewers.add(player.getGameProfile().name()));
        bossBar.add("players", viewers);
        json.add("bossBar", bossBar);
        UUID tracked = accessor.soullink$dragonUuid();
        if (tracked != null && end.getEntity(tracked) instanceof EnderDragon dragon) {
            JsonObject described = new JsonObject();
            described.addProperty("health", dragon.getHealth());
            described.addProperty("maxHealth", dragon.getMaxHealth());
            described.addProperty("dying", dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING);
            described.addProperty("deathTime", dragon.dragonDeathTime);
            json.add("dragon", described);
        }
        int orbs = 0;
        int experience = 0;
        for (Entity entity : end.getAllEntities()) {
            if (entity instanceof ExperienceOrb orb) {
                orbs++;
                experience += orb.getValue();
            }
        }
        json.addProperty("experienceOrbs", orbs);
        json.addProperty("experienceOrbValue", experience);
        // Vanilla places the egg on top of the exit portal's pillar after the first kill of a fight.
        BlockPos podium = accessor.soullink$origin();
        for (int y = end.getMinY(); y < end.getMaxY(); y++) {
            if (end.getBlockState(podium.atY(y)).is(Blocks.DRAGON_EGG)) {
                json.addProperty("dragonEggY", y);
            }
        }
        return json;
    }

    private static JsonObject describePlayer(MinecraftServer server, ServerPlayer player) {
        JsonObject json = new JsonObject();
        json.addProperty("name", player.getGameProfile().name());
        json.addProperty("dimension", player.level().dimension().identifier().toString());
        json.addProperty("x", player.getX());
        json.addProperty("y", player.getY());
        json.addProperty("z", player.getZ());
        json.addProperty("health", player.getHealth());
        json.addProperty("food", player.getFoodData().getFoodLevel());
        // Drives phantom spawning; part of the statistics a new run resets.
        json.addProperty("timeSinceRest", player.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST)));
        json.addProperty("saturation", player.getFoodData().getSaturationLevel());
        json.addProperty("gameMode", player.gameMode().getName());
        json.addProperty("alive", player.isAlive());
        json.addProperty("xpLevel", player.experienceLevel);
        int items = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            items += player.getInventory().getItem(slot).getCount();
        }
        json.addProperty("inventoryItems", items);
        json.addProperty("enderChestEmpty", player.getEnderChestInventory().isEmpty());
        json.addProperty("effects", player.getActiveEffects().size());
        JsonObject effects = new JsonObject();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("amplifier", effect.getAmplifier());
            entry.addProperty("duration", effect.getDuration());
            effects.add(effect.getEffect().getRegisteredName(), entry);
        }
        json.add("effectDetails", effects);
        json.addProperty("absorption", player.getAbsorptionAmount());
        json.addProperty("seenCredits", player.seenCredits);
        JsonArray tags = new JsonArray();
        MinecraftCompat.entityTags(player).forEach(tags::add);
        json.add("tags", tags);
        int done = 0;
        for (var holder : server.getAdvancements().getAllAdvancements()) {
            if (player.getAdvancements().getOrStartProgress(holder).isDone()) {
                done++;
            }
        }
        json.addProperty("advancementsDone", done);
        int nonzeroStats = 0;
        for (int value : ((dev.soullink.mixin.StatsCounterAccessor) server.getPlayerList().getPlayerStats(player))
                .soullink$stats().values()) {
            if (value != 0) {
                nonzeroStats++;
            }
        }
        json.addProperty("nonzeroStats", nonzeroStats);
        json.addProperty("levelIdentity", Integer.toHexString(System.identityHashCode(player.level())));
        return json;
    }
}
