package dev.soullink.run;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.soullink.SoulLink;
import dev.soullink.SoulLinkConfig;
import dev.soullink.MinecraftCompat;
import dev.soullink.compat.WthitCompatibility;
import dev.soullink.game.SharedEffects;
import dev.soullink.game.SharedVitality;
import dev.soullink.lobby.LobbyService;
import dev.terrarasa.ActivationJournal;
import dev.terrarasa.CanonicalActivationService;
import dev.terrarasa.DimensionPolicy;
import dev.terrarasa.FaultInjection;
import dev.terrarasa.LifecycleException;
import dev.terrarasa.LivePregeneration;
import dev.terrarasa.RegistryFingerprint;
import dev.terrarasa.RunManifest;
import dev.terrarasa.WorldPaths;
import dev.terrarasa.WorldPreparationService;
import dev.terrarasa.session.SessionReset;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Owns the run lifecycle and admission control (the spec's ResetCoordinator). Gameplay rules call into
 * it; world lifecycle mechanics stay in the {@code lifecycle} package (FR-12).
 *
 * <p>Starting a run: every player is moved to the lobby and reset to baseline while their current level
 * is still live, the prepared run is activated on the canonical keys, server-owned state is reset, and
 * only then do players enter the new Overworld at the prepared spawn.
 */
public final class RunCoordinator {
    private static final String LOBBY_STAMP = "lobby";
    private static final int PREPARE_RETRY_TICKS = 20 * 30;
    private static final int STATE_SAVE_INTERVAL_TICKS = 20 * 5;
    private static final int TIMER_DISPLAY_INTERVAL_TICKS = 2;
    private static final int LOBBY_RESCUE_INTERVAL_TICKS = 20 * 5;
    private static final int PREPARATION_NOTICE_INTERVAL_TICKS = 20 * 30;

    /** The coordinator of the running server; set from server start to server stop. */
    private static @Nullable RunCoordinator active;

    private final MinecraftServer server;
    private final SoulLinkConfig config;
    private final DimensionPolicy policy;
    private final WorldPaths paths;
    private final Path statePath;
    private final StateWriter stateWriter;
    private final WorldPreparationService preparation;
    private final CanonicalActivationService activation;
    private final SharedVitality vitality = new SharedVitality();
    private final SharedEffects effects = new SharedEffects();
    private final TickCost tickCost = new TickCost();
    /** {@code state.participants} as parsed ids: membership is checked every tick. */
    private final Set<UUID> participantIds = new HashSet<>();
    private final Set<ResourceKey<Level>> runLevels;
    private boolean swappedThisTick;
    private SoulLinkState state = new SoulLinkState();
    private long ticks;
    private long nextPrepareTick;
    private long lastPreparationNoticeTick = Long.MIN_VALUE;
    private @Nullable String lastPreparationNotice;
    private @Nullable String lastActivationError;
    private @Nullable String lastReportedPreparationFailure;
    /** Vanilla's own world spawn as found at startup, before Soul Link points it at the lobby. */
    private LevelData.@Nullable RespawnData startupSpawn;

    public RunCoordinator(MinecraftServer server, SoulLinkConfig config) {
        this.server = server;
        this.config = config;
        this.policy = config.dimensionPolicy();
        this.runLevels = this.policy.resettable().stream().map(CanonicalActivationService::dimensionKey)
                .collect(Collectors.toUnmodifiableSet());
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        this.paths = new WorldPaths(worldRoot);
        // Soul Link's own state lives outside both vanilla's resettable saved data and the library's files.
        this.statePath = worldRoot.resolve(SoulLink.MOD_ID).resolve("state.json");
        this.stateWriter = new StateWriter(this.statePath);
        this.preparation = new WorldPreparationService(this.paths, config.preloadRadius, config.pregenerationRadii(),
                Duration.ofSeconds(config.preparationTimeoutSeconds), Duration.ofSeconds(config.drainTimeoutSeconds),
                player -> LobbyService.teleport(server, player));
        this.preparation.setReadyLimit(config.preparedRunPoolSize);
        this.activation = new CanonicalActivationService(this.paths, Duration.ofSeconds(config.drainTimeoutSeconds));
    }

    public static @Nullable RunCoordinator active() {
        return active;
    }

    public static void setActive(@Nullable RunCoordinator coordinator) {
        active = coordinator;
    }

    // ------------------------------------------------------------------------------------------------
    // Server lifecycle

    public void onServerStarted() {
        this.state = SoulLinkState.load(this.statePath);
        reconcileWithJournal();
        syncParticipantIds();
        LobbyService.initialize(this.server);
        this.startupSpawn = this.server.getWorldData().overworldData().getRespawnData();
        this.preparation.loadFromDisk(this.server, this.policy);
        if (this.state.status == SoulLinkState.Status.RUNNING || this.state.status == SoulLinkState.Status.ENDED) {
            this.server.setRespawnData(runRespawnData());
            this.vitality.restore(this.state.sharedHealth, this.state.sharedFood, this.state.sharedSaturation);
        } else {
            this.server.setRespawnData(LobbyService.respawnData(this.server));
        }
        // On a fresh server, preparation doubles as the initial start request. tickStarting() enters the
        // first run as soon as its spawn is ready; subsequent runs still wait for /soullink start.
        if (this.state.status == SoulLinkState.Status.LOBBY && this.state.runsStarted == 0
                && this.state.activeRunId == null) {
            this.state.status = SoulLinkState.Status.STARTING;
        }
        save();
        SoulLink.LOGGER.info("Soul Link ready: status {}, active run {}, prepared run {}", this.state.status,
                this.state.activeRunId, this.preparation.ready().map(m -> m.runId).orElse("none"));
    }

    /** A prepared run is only valid for the data packs it was generated under (spec 8.6, test T17). */
    public void onDataPacksReloaded() {
        String fingerprint = RegistryFingerprint.compute(this.server, this.policy);
        for (RunManifest manifest : this.preparation.readyRuns()) {
            if (!fingerprint.equals(manifest.fingerprint)) {
                SoulLink.LOGGER.info("Data packs changed; discarding prepared run {}", manifest.runId);
                this.preparation.discardReady(manifest.runUuid());
                this.nextPrepareTick = 0;
            }
        }
        String active = this.preparation.activeFingerprint();
        if (active != null && !fingerprint.equals(active)) {
            this.preparation.fail(this.server, "data packs changed during preparation");
            this.nextPrepareTick = 0;
        }
    }

    public void onServerStopping() {
        this.preparation.onServerStopping(this.server);
        save();
        this.stateWriter.close();
    }

    /** A crash between journal COMMITTED and our state save leaves a committed, never-entered run. */
    private void reconcileWithJournal() {
        try {
            Optional<ActivationJournal> journal = ActivationJournal.load(this.paths);
            if (journal.isPresent() && journal.get().phase == ActivationJournal.Phase.COMMITTED
                    && !journal.get().runId.equals(this.state.activeRunId)) {
                ActivationJournal committed = journal.get();
                SoulLink.LOGGER.warn("Adopting committed run {} that was never entered", committed.runId);
                this.state.activeRunId = committed.runId;
                this.state.activeRunStarted = false;
                this.state.seed = committed.newSeed;
                this.state.spawnX = committed.spawnX;
                this.state.spawnY = committed.spawnY;
                this.state.spawnZ = committed.spawnZ;
                this.state.status = SoulLinkState.Status.LOBBY;
                this.state.result = SoulLinkState.Result.NONE;
                this.state.participants.clear();
                syncParticipantIds();
            }
        } catch (IOException e) {
            SoulLink.LOGGER.error("Could not read the activation journal", e);
        }
    }

    public void tick() {
        long started = System.nanoTime();
        boolean preparing = this.preparation.isPreparing();
        this.swappedThisTick = false;
        tickWork();
        // A tick that swapped worlds is not steady-state cost; activation reports its own timings.
        if (!this.swappedThisTick) {
            this.tickCost.record(System.nanoTime() - started, preparing || this.preparation.isPreparing());
        }
    }

    private void tickWork() {
        this.ticks++;
        int readyBefore = this.preparation.readyCount();
        boolean runWorldInUse = this.state.status == SoulLinkState.Status.RUNNING
                || this.state.status == SoulLinkState.Status.ENDED;
        this.preparation.setLevelConstructionIntervalTicks(runWorldInUse
                ? this.config.activeRunConstructionIntervalTicks
                : 1);
        this.preparation.tick(this.server);
        reportNewPreparationFailure();
        if (this.preparation.readyCount() > readyBefore
                && this.state.status != SoulLinkState.Status.STARTING) {
            String readyMessage = this.preparation.readyCount() + " of " + this.preparation.readyLimit()
                    + " future run worlds are ready; start the next with /soullink start.";
            SoulLink.LOGGER.info(readyMessage);
            sendOperatorNotice(Component.literal(readyMessage)
                    .withStyle(ChatFormatting.GREEN));
        }
        switch (this.state.status) {
            case STARTING -> tickStarting();
            case RUNNING -> tickRunning();
            default -> {
            }
        }
        if (this.state.status != SoulLinkState.Status.STARTING) {
            reportBackgroundPreparationProgress();
        }
        rescueLobbyFallers();
        if (this.config.autoPrepare && this.state.status != SoulLinkState.Status.STARTING && this.ticks >= this.nextPrepareTick
                && !this.preparation.isPreparing() && this.preparation.readyCount() < this.preparation.readyLimit()) {
            beginPreparation(null);
        }
        if (this.ticks % STATE_SAVE_INTERVAL_TICKS == 0) {
            // Mostly the run timer, so a crash loses at most a few seconds of it. Written off-thread, and
            // only when something changed.
            this.stateWriter.saveInBackground(this.state);
        }
    }

    private void tickStarting() {
        if (this.preparation.ready().isPresent()) {
            startPreparedRun();
            return;
        }
        // Players are waiting: a run that is already playable stops pregenerating; the rest continues live.
        this.preparation.finishSoon();
        if (!this.preparation.isPreparing() && this.ticks >= this.nextPrepareTick) {
            beginPreparation(null);
        }
        reportPreparationProgress(true);
    }

    private void tickRunning() {
        // The run clock only runs while someone is playing it: not while the server waits for players to
        // come back after a restart, and not while everyone has disconnected.
        if (!this.state.timerPaused && !onlineParticipants().isEmpty()) {
            this.state.elapsedTicks++;
        }
        List<ServerPlayer> participants = activeParticipants();
        if (this.state.sharedVitality) {
            if (this.vitality.tick(participants)) {
                endRun(SoulLinkState.Result.DEFEAT, "the shared soul ran out of health");
                for (ServerPlayer player : participants) {
                    player.kill(player.level());
                }
                return;
            }
            this.state.sharedHealth = this.vitality.health();
            this.state.sharedFood = this.vitality.food();
            this.state.sharedSaturation = this.vitality.saturation();
        }
        if (this.ticks % TIMER_DISPLAY_INTERVAL_TICKS == 0) {
            String display = this.state.timerPaused
                    ? "Paused — " + RunTimer.format(this.state.elapsedTicks)
                    : RunTimer.format(this.state.elapsedTicks);
            Component timer = Component.literal(display).withStyle(ChatFormatting.GOLD);
            for (ServerPlayer player : onlineParticipants()) {
                player.sendSystemMessage(timer, true);
            }
        }
    }

    private void rescueLobbyFallers() {
        if (this.ticks % LOBBY_RESCUE_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
            if (LobbyService.isIn(player) && player.getY() < 0.0) {
                LobbyService.teleport(this.server, player);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Commands

    /** {@code /soullink start}: enter a prepared run now, or as soon as preparation finishes. */
    public Component requestStart() {
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            return Component.literal("A run is already in progress. Use /soullink reset to restart it.").withStyle(ChatFormatting.RED);
        }
        if (this.state.status == SoulLinkState.Status.STARTING) {
            return Component.literal("The next run is already being prepared.");
        }
        if (this.state.activeRunId != null && !this.state.activeRunStarted) {
            moveEveryoneToLobby();
            enterRun();
            return Component.literal("Entering the prepared run.");
        }
        if (this.preparation.ready().isPresent()) {
            return startPreparedRun();
        }
        this.state.status = SoulLinkState.Status.STARTING;
        if (!this.preparation.isPreparing()) {
            this.nextPrepareTick = 0;
            beginPreparation(null);
        }
        save();
        return Component.literal("Preparing a fresh world; the run starts automatically when it is ready.");
    }

    /** {@code /soullink reset}: abandon the current run and start the next one. */
    public Component requestReset() {
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            endRun(SoulLinkState.Result.ABANDONED, "the run was reset");
        }
        return requestStart();
    }

    /** {@code /soullink stop}: end the run and return everyone to the lobby. */
    public Component stopRun() {
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            endRun(SoulLinkState.Result.ABANDONED, "the run was stopped");
        }
        this.state.status = SoulLinkState.Status.LOBBY;
        this.state.timerPaused = false;
        moveEveryoneToLobby();
        save();
        return Component.literal("Run stopped; everyone is back in the lobby.");
    }

    public Component pauseTimer() {
        if (this.state.status != SoulLinkState.Status.RUNNING) {
            return Component.literal("A run is not in progress.").withStyle(ChatFormatting.RED);
        }
        if (this.state.timerPaused) {
            return Component.literal("The run timer is already paused.");
        }
        this.state.timerPaused = true;
        save();
        broadcast(Component.literal("The run timer is paused. Gameplay continues normally."));
        return Component.literal("Run timer paused; the world and gameplay continue normally.");
    }

    public Component resumeTimer() {
        if (this.state.status != SoulLinkState.Status.RUNNING) {
            return Component.literal("A run is not in progress.").withStyle(ChatFormatting.RED);
        }
        if (!this.state.timerPaused) {
            return Component.literal("The run timer is not paused.");
        }
        this.state.timerPaused = false;
        save();
        broadcast(Component.literal("The run timer has resumed."));
        return Component.literal("Run timer resumed.");
    }

    /** {@code /soullink prepare [seed]}: discard any prepared run and prepare a new one. */
    public Component prepare(@Nullable Long seed, @Nullable Integer preloadRadius) {
        if (this.preparation.isPreparing()) {
            String message = this.state.status == SoulLinkState.Status.STARTING
                    ? "The automatic run is already preparing to start. Wait until it enters play before preparing a replacement."
                    : "A future run is already being prepared. Wait for it to finish before replacing the prepared run pool.";
            return Component.literal(message).withStyle(ChatFormatting.RED);
        }
        this.preparation.discardAllReady();
        this.nextPrepareTick = Long.MAX_VALUE;
        boolean started = beginPreparation(seed, preloadRadius);
        this.nextPrepareTick = this.ticks + PREPARE_RETRY_TICKS;
        return started ? Component.literal("Preparing a run" + (seed != null ? " with seed " + seed : ""))
                : Component.literal("Preparation failed: " + this.preparation.lastFailure()).withStyle(ChatFormatting.RED);
    }

    // ------------------------------------------------------------------------------------------------
    // Run transitions

    private boolean beginPreparation(@Nullable Long seed) {
        return beginPreparation(seed, null);
    }

    private boolean beginPreparation(@Nullable Long seed, @Nullable Integer preloadRadius) {
        try {
            long chosen = seed != null ? seed : RandomSupport.generateUniqueSeed();
            if (preloadRadius != null) {
                this.preparation.begin(this.server, chosen, this.policy, preloadRadius);
            } else {
                this.preparation.begin(this.server, chosen, this.policy);
            }
            this.lastReportedPreparationFailure = null;
            this.lastPreparationNotice = null;
            this.lastPreparationNoticeTick = Long.MIN_VALUE;
            return true;
        } catch (LifecycleException e) {
            SoulLink.LOGGER.error("Could not begin run preparation", e);
            this.nextPrepareTick = this.ticks + PREPARE_RETRY_TICKS;
            return false;
        }
    }

    private void reportNewPreparationFailure() {
        String failure = this.preparation.lastFailure();
        if (failure == null || failure.equals(this.lastReportedPreparationFailure)) {
            return;
        }
        this.lastReportedPreparationFailure = failure;
        Component message = Component.literal("Soul Link run preparation failed: " + failure
                + ". Check the server log; recovery: run /soullink prepare. Report bugs at "
                + "https://github.com/qwasdf-1/soul-link/issues")
                .withStyle(ChatFormatting.RED);
        sendOperatorNotice(message);
    }

    private void reportBackgroundPreparationProgress() {
        if (this.preparation.isPreparing()) {
            reportPreparationProgress(false);
        }
    }

    private void reportPreparationProgress(boolean toAllPlayers) {
        if (!this.preparation.isPreparing()) {
            return;
        }
        String message = this.preparation.progressDescription();
        String phase = message.startsWith("Pregenerating run worlds…") ? "Pregenerating run worlds…" : message;
        if (!phase.equals(this.lastPreparationNotice)
                || this.ticks - this.lastPreparationNoticeTick >= PREPARATION_NOTICE_INTERVAL_TICKS) {
            SoulLink.LOGGER.info("Run preparation: {}", message);
            Component component = Component.literal(message).withStyle(ChatFormatting.GRAY);
            if (toAllPlayers) {
                actionBarAll(component);
            } else {
                sendOperatorNotice(component);
            }
            this.lastPreparationNotice = phase;
            this.lastPreparationNoticeTick = this.ticks;
        }
    }

    private void sendOperatorNotice(Component message) {
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            if (MinecraftCompat.hasOperatorPermission(player)) {
                player.sendSystemMessage(message, false);
            }
        }
    }

    private Component startPreparedRun() {
        // Phase 1: everyone into the lobby and back to baseline while their current levels are still live.
        broadcast(Component.literal("World swap starting: moving everyone to the lobby and replacing the run worlds. "
                + "This can take a few seconds.").withStyle(ChatFormatting.GRAY));
        this.swappedThisTick = true;
        moveEveryoneToLobby();
        WthitCompatibility.beforeWorldReplacement();
        RunManifest manifest = this.preparation.takeReady();
        CanonicalActivationService.Activation result;
        try {
            result = this.activation.activate(this.server, manifest, this.policy);
        } catch (LifecycleException e) {
            this.lastActivationError = e.getMessage();
            if (stillAdoptable(manifest)) {
                this.preparation.restoreReady(manifest);
            } else {
                this.preparation.deleteStageData(manifest.runUuid());
            }
            this.state.status = SoulLinkState.Status.LOBBY;
            save();
            broadcast(Component.literal("Could not start the run: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return Component.literal("Activation failed: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        this.lastActivationError = null;
        SessionReset.resetServerState(this.server);
        this.state.activeRunId = result.runId().toString();
        this.state.activeRunStarted = false;
        this.state.seed = result.seed();
        this.state.spawnX = result.spawn().getX();
        this.state.spawnY = result.spawn().getY();
        this.state.spawnZ = result.spawn().getZ();
        save();
        // Keep the next run filling in the background. Terra Rasa paces each synchronous dimension
        // construction while this run world is active; lobby/startup preparation uses the faster pace.
        if (this.config.autoPrepare && !this.preparation.isPreparing()
                && this.preparation.readyCount() < this.preparation.readyLimit()) {
            // The next server tick advances preparation at the current run's configured cadence.
            beginPreparation(null);
        }
        enterRun();
        return Component.literal("Run started in " + result.timingsMillis().getOrDefault("total", 0L) + " ms.");
    }

    private boolean stillAdoptable(RunManifest manifest) {
        return RegistryFingerprint.compute(this.server, this.policy).equals(manifest.fingerprint)
                && manifest.dimensions.stream().map(d -> d.target).toList()
                        .equals(this.policy.resettable().stream().map(Object::toString).toList());
    }

    private void enterRun() {
        String runId = this.state.activeRunId;
        BlockPos spawn = runSpawn();
        this.server.setRespawnData(runRespawnData());
        this.vitality.reset(this.config.halfHeart ? 1.0F : 20.0F);
        this.state.sharedVitality = this.config.sharedVitality;
        this.state.soulLinkHardcore = this.config.soulLinkHardcore;
        this.state.sharedHealth = this.state.sharedVitality ? this.vitality.health() : -1.0F;
        this.state.sharedPotions = this.config.sharedPotions;
        this.state.halfHeart = this.config.halfHeart;
        this.state.playerMaxHealthBases.clear();
        this.state.timerPaused = false;
        this.state.participants.clear();
        this.state.eliminatedParticipants.clear();
        ServerLevel overworld = this.server.overworld();
        for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
            SessionReset.resetPlayer(this.server, player, GameType.SURVIVAL);
            if (this.state.halfHeart) {
                rememberMaxHealthBase(player);
            }
            player.teleport(new TeleportTransition(overworld, spawnPointFor(player, overworld, spawn), Vec3.ZERO, 0.0F, 0.0F,
                    TeleportTransition.DO_NOTHING));
            this.state.participants.add(player.getUUID().toString());
            this.state.playerRuns.put(player.getUUID().toString(), runId);
        }
        if (this.state.halfHeart) {
            // Save restoration values before lowering max health, so a crash mid-transition can recover.
            save();
            for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
                applyHalfHeart(player);
            }
        }
        syncParticipantIds();
        this.state.status = SoulLinkState.Status.RUNNING;
        this.state.result = SoulLinkState.Result.NONE;
        this.state.endReason = null;
        this.state.activeRunStarted = true;
        this.state.elapsedTicks = 0;
        this.state.runsStarted++;
        save();
        // Vanilla saves player data every five minutes; a crash before that would bring participants back
        // as they were in the lobby. A handful of small files, once per run.
        this.server.getPlayerList().saveAll();
        title(Component.literal("Soul Link").withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.literal("Fresh run ready. Defeat the Ender Dragon and share every heartbeat."));
        broadcast(Component.literal("New Soul Link run is ready. Good luck!").withStyle(ChatFormatting.GREEN));
    }

    public void endRun(SoulLinkState.Result result, String reason) {
        if (this.state.status != SoulLinkState.Status.RUNNING) {
            return;
        }
        this.state.status = SoulLinkState.Status.ENDED;
        this.state.timerPaused = false;
        this.state.result = result;
        this.state.endReason = reason;
        String time = RunTimer.format(this.state.elapsedTicks);
        if (result == SoulLinkState.Result.VICTORY) {
            this.state.victories++;
            if (this.state.bestVictoryTicks == null || this.state.elapsedTicks < this.state.bestVictoryTicks) {
                this.state.bestVictoryTicks = this.state.elapsedTicks;
            }
        }
        if (result != SoulLinkState.Result.VICTORY) {
            for (ServerPlayer player : onlineParticipants()) {
                if (player.isAlive()) {
                    player.setGameMode(GameType.SPECTATOR);
                }
            }
        }
        save();
        if (result == SoulLinkState.Result.ABANDONED) {
            return;
        }
        boolean victory = result == SoulLinkState.Result.VICTORY;
        title(Component.literal(victory ? "Victory!" : "Run failed").withStyle(victory ? ChatFormatting.GOLD : ChatFormatting.RED),
                Component.literal(time));
        MutableComponent next = Component.literal("[Start next run]").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("/soullink start"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Generate a fresh world and start"))));
        broadcast(Component.literal((victory ? "The dragon is dead! " : "Run over: " + reason + ". ") + "Time: " + time + " ")
                .append(next));
    }

    private void moveEveryoneToLobby() {
        this.server.setRespawnData(LobbyService.respawnData(this.server));
        for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
            sendToLobby(player);
        }
    }

    private ServerPlayer sendToLobby(ServerPlayer player) {
        ServerPlayer current = player;
        if (current.isDeadOrDying() || current.isRemoved()) {
            current = this.server.getPlayerList().respawn(current, false, Entity.RemovalReason.KILLED);
        }
        restoreMaxHealthBase(current);
        SessionReset.resetPlayer(this.server, current, GameType.ADVENTURE);
        if (!LobbyService.isIn(current)) {
            current = LobbyService.teleport(this.server, current);
        }
        this.vitality.forget(current.getUUID());
        this.state.playerRuns.put(current.getUUID().toString(), LOBBY_STAMP);
        return current;
    }

    // ------------------------------------------------------------------------------------------------
    // Gameplay events

    public void onPlayerJoin(ServerPlayer player) {
        sendOperatorOnboarding(player);
        String uuid = player.getUUID().toString();
        String stamp = this.state.playerRuns.get(uuid);
        boolean currentParticipant = this.state.participants.contains(uuid) && this.state.activeRunId != null
                && this.state.activeRunId.equals(stamp);
        switch (this.state.status) {
            case RUNNING -> {
                if (currentParticipant) {
                    this.vitality.forget(player.getUUID());
                    if (this.state.eliminatedParticipants.contains(uuid)) {
                        placeEliminatedSpectator(player);
                    } else if (!isRunLevel(player.level())) {
                        // Saved player data older than the run (a crash right after it started).
                        SoulLink.LOGGER.warn("{} rejoined outside the run; returning them to the run spawn",
                                player.getGameProfile().name());
                        ServerLevel overworld = this.server.overworld();
                        player.teleport(new TeleportTransition(overworld, spawnPointFor(player, overworld, runSpawn()),
                                Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
                        player.setGameMode(GameType.SURVIVAL);
                    }
                    applyHalfHeart(player);
                } else if (this.config.allowLateJoin) {
                    SessionReset.resetPlayer(this.server, player, GameType.SURVIVAL);
                    if (this.state.halfHeart) {
                        rememberMaxHealthBase(player);
                        save();
                        applyHalfHeart(player);
                    }
                    ServerLevel overworld = this.server.overworld();
                    player.teleport(new TeleportTransition(overworld, spawnPointFor(player, overworld, runSpawn()),
                            Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
                    if (this.state.sharedPotions) {
                        activeParticipants().stream().findFirst().ifPresent(teammate -> this.effects.syncJoiner(player, teammate));
                    }
                    this.state.participants.add(uuid);
                    this.participantIds.add(player.getUUID());
                    this.state.playerRuns.put(uuid, this.state.activeRunId);
                    save();
                } else {
                    sendToLobby(player);
                }
            }
            case ENDED -> {
                if (!currentParticipant || !this.state.activeRunStarted) {
                    sendToLobby(player);
                } else if (this.state.result != SoulLinkState.Result.VICTORY) {
                    player.setGameMode(GameType.SPECTATOR);
                }
            }
            default -> {
                if (!LOBBY_STAMP.equals(stamp) || !LobbyService.isIn(player)) {
                    sendToLobby(player);
                }
            }
        }
    }

    private void sendOperatorOnboarding(ServerPlayer player) {
        if (!MinecraftCompat.hasOperatorPermission(player) || this.state.onboardedOperators.contains(player.getUUID().toString())) {
            return;
        }
        player.sendSystemMessage(Component.literal("Soul Link runs can link health, hunger, and saturation. By default, "
                + "damage adds together and simultaneous healing only applies the largest individual heal. Operators can "
                + "change shared vitality with /soullink rules sharedVitality. With it off, vanilla respawn rules apply; "
                + "Soul Link Hardcore can end the run on the first death."));
        player.sendSystemMessage(Component.literal("Each run uses a fresh world. The first run starts automatically once its "
                + "spawn is ready; later runs use /soullink start."));
        player.sendSystemMessage(Component.literal("Use /soullink help for commands. Operators can inspect /soullink status "
                + "and configure pregeneration with /soullink setup."));
        this.state.onboardedOperators.add(player.getUUID().toString());
        save();
    }

    public void onPlayerLeave(ServerPlayer player) {
        this.vitality.forget(player.getUUID());
    }

    public void onPlayerDeath(ServerPlayer player) {
        if (this.state.status == SoulLinkState.Status.RUNNING && isParticipant(player) && isRunLevel(player.level())) {
            boolean vanillaHardcore = this.server.getWorldData().isHardcore();
            if (!this.state.sharedVitality && !this.state.soulLinkHardcore && vanillaHardcore) {
                String uuid = player.getUUID().toString();
                if (!this.state.eliminatedParticipants.contains(uuid)) {
                    this.state.eliminatedParticipants.add(uuid);
                }
            }
            DeathRules.Outcome outcome = DeathRules.afterDeath(this.state.sharedVitality,
                    this.state.soulLinkHardcore, vanillaHardcore,
                    this.state.participants, this.state.eliminatedParticipants);
            if (outcome == DeathRules.Outcome.END_RUN) {
                endRun(SoulLinkState.Result.DEFEAT, player.getGameProfile().name() + " died");
            } else if (outcome == DeathRules.Outcome.TEAM_WIPE) {
                endRun(SoulLinkState.Result.DEFEAT, "the team was eliminated");
            } else {
                save();
                if (vanillaHardcore) {
                    int remaining = this.state.participants.size() - this.state.eliminatedParticipants.size();
                    broadcast(Component.literal(player.getGameProfile().name() + " was eliminated. " + remaining
                            + " of " + this.state.participants.size() + " participants remain.")
                            .withStyle(ChatFormatting.YELLOW));
                } else {
                    broadcast(Component.literal(player.getGameProfile().name() + " died. The run continues.")
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
        }
    }

    public void onPlayerRespawn(ServerPlayer player) {
        switch (this.state.status) {
            case RUNNING -> {
                if (this.state.eliminatedParticipants.contains(player.getUUID().toString())) {
                    placeEliminatedSpectator(player);
                } else {
                    applyHalfHeart(player);
                }
            }
            case ENDED -> {
                // A victorious participant returning through the exit portal keeps playing.
                if (isParticipant(player) && this.state.result != SoulLinkState.Result.VICTORY) {
                    player.setGameMode(GameType.SPECTATOR);
                }
            }
            case LOBBY, STARTING -> sendToLobby(player);
            default -> {
            }
        }
    }

    public void onDragonKilled(ServerLevel level) {
        if (this.state.status == SoulLinkState.Status.RUNNING && level.dimension().equals(Level.END)) {
            endRun(SoulLinkState.Result.VICTORY, "the Ender Dragon was defeated");
        }
    }

    public void onEffectChanged(ServerPlayer player, MobEffectInstance effect, @Nullable Entity source) {
        if (sharesEffects(player)) {
            this.effects.onEffectChanged(effect, source, teammatesOf(player));
        }
    }

    public void onEffectsRemoved(ServerPlayer player, Collection<MobEffectInstance> removed) {
        if (sharesEffects(player)) {
            this.effects.onEffectsRemoved(removed, teammatesOf(player));
        }
    }

    private boolean sharesEffects(ServerPlayer player) {
        return this.state.status == SoulLinkState.Status.RUNNING && this.state.sharedPotions
                && !this.effects.isMirroring() && isActiveParticipant(player);
    }

    private List<ServerPlayer> teammatesOf(ServerPlayer player) {
        return activeParticipants().stream().filter(other -> other != player).toList();
    }

    /** Sets a run rule for the next run and persists it to the config file. */
    public Component setSharedPotions(boolean enabled) {
        boolean previous = this.config.sharedPotions;
        this.config.sharedPotions = enabled;
        try {
            this.config.save(SoulLink.configPath());
        } catch (IOException e) {
            this.config.sharedPotions = previous;
            SoulLink.LOGGER.error("Could not save the Soul Link config", e);
            return Component.literal("Could not save the config: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        return Component.literal("Shared potions " + (enabled ? "on" : "off") + " from the next run.");
    }

    /** Persists shared health, food, and saturation for the next run. */
    public Component setSharedVitality(boolean enabled) {
        boolean previous = this.config.sharedVitality;
        this.config.sharedVitality = enabled;
        try {
            this.config.save(SoulLink.configPath());
        } catch (IOException e) {
            this.config.sharedVitality = previous;
            SoulLink.LOGGER.error("Could not save the Soul Link config", e);
            return Component.literal("Could not save the config: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        return Component.literal("Shared vitality " + (enabled ? "on" : "off") + " from the next run.");
    }

    /** Persists the one-death defeat rule for future runs. */
    public Component setSoulLinkHardcore(boolean enabled) {
        boolean previous = this.config.soulLinkHardcore;
        this.config.soulLinkHardcore = enabled;
        try {
            this.config.save(SoulLink.configPath());
        } catch (IOException e) {
            this.config.soulLinkHardcore = previous;
            SoulLink.LOGGER.error("Could not save the Soul Link config", e);
            return Component.literal("Could not save the config: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        return Component.literal("Soul Link Hardcore " + (enabled ? "on" : "off") + " from the next run.");
    }

    /** Changes vanilla's world-persisted naturalRegeneration rule immediately. */
    public Component setNaturalRegeneration(boolean enabled) {
        MinecraftCompat.setNaturalRegeneration(this.server, enabled);
        return Component.literal("Natural regeneration " + (enabled ? "enabled" : "disabled") + ".");
    }

    /** Persists the half-heart setting for the next run. */
    public Component setHalfHeart(boolean enabled) {
        boolean previous = this.config.halfHeart;
        this.config.halfHeart = enabled;
        try {
            this.config.save(SoulLink.configPath());
        } catch (IOException e) {
            this.config.halfHeart = previous;
            SoulLink.LOGGER.error("Could not save the Soul Link config", e);
            return Component.literal("Could not save the config: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        return Component.literal("Half-heart mode " + (enabled ? "on" : "off") + " from the next run.");
    }

    private void rememberMaxHealthBase(ServerPlayer player) {
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute != null) {
            this.state.playerMaxHealthBases.putIfAbsent(player.getUUID().toString(), attribute.getBaseValue());
        }
    }

    private void applyHalfHeart(ServerPlayer player) {
        if (!this.state.halfHeart) {
            return;
        }
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute != null && attribute.getBaseValue() != 1.0) {
            attribute.setBaseValue(1.0);
        }
        if (player.getHealth() > 1.0F) {
            player.setHealth(1.0F);
        }
    }

    private void restoreMaxHealthBase(ServerPlayer player) {
        Double baseValue = this.state.playerMaxHealthBases.remove(player.getUUID().toString());
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (baseValue != null && attribute != null) {
            attribute.setBaseValue(baseValue);
        }
    }

    public Component setupInfo() {
        StringBuilder info = new StringBuilder("Soul Link setup\nOptional pregeneration radii (blocks):");
        for (Identifier dimension : this.policy.resettable()) {
            int radius = this.config.pregeneration.getOrDefault(dimension.toString(), 0);
            info.append("\n  ").append(dimension).append(": ").append(radius == 0 ? "off" : radius);
        }
        info.append("\nPrepared future runs: ").append(this.preparation.readyCount()).append(" / ")
                .append(this.preparation.readyLimit())
                .append(" (the active run is not counted; maximum ")
                .append(WorldPreparationService.MAX_READY_RUNS).append(").")
                .append("\nSet the pool size with /soullink setup pool <futureRuns>.")
                .append("\nSet a radius with /soullink setup pregen <dimension> <radiusBlocks>; 0 disables it.")
                .append("\nChanges apply to preparations begun after the setting is changed.")
                .append("\nActive-run construction interval: ")
                .append(this.config.activeRunConstructionIntervalTicks)
                .append(" ticks per staged dimension; set activeRunConstructionIntervalTicks in config/soullink.json and restart.")
                .append("\nautoPrepare controls whether Soul Link fills the pool in the background.");
        return Component.literal(info.toString());
    }

    public Component setPreparedRunPoolSize(int futureRuns) {
        if (futureRuns < 1 || futureRuns > WorldPreparationService.MAX_READY_RUNS) {
            return Component.literal("The future run pool size must be between 1 and "
                    + WorldPreparationService.MAX_READY_RUNS + ".").withStyle(ChatFormatting.RED);
        }
        int previous = this.config.preparedRunPoolSize;
        this.config.preparedRunPoolSize = futureRuns;
        try {
            this.config.save(SoulLink.configPath());
        } catch (IOException e) {
            this.config.preparedRunPoolSize = previous;
            SoulLink.LOGGER.error("Could not save the Soul Link config", e);
            return Component.literal("Could not save the prepared run pool setting: " + e.getMessage())
                    .withStyle(ChatFormatting.RED);
        }
        this.preparation.setReadyLimit(futureRuns);
        return Component.literal("Prepared future run pool set to " + futureRuns
                + "; the active run does not count. Existing newest queued runs are discarded if the pool shrinks.");
    }

    public Component setPregenerationRadius(String dimensionName, int radiusBlocks) {
        Identifier dimension;
        try {
            dimension = Identifier.parse(dimensionName);
        } catch (RuntimeException e) {
            return Component.literal("Invalid dimension id: " + dimensionName).withStyle(ChatFormatting.RED);
        }
        if (!this.policy.resettable().contains(dimension)) {
            return Component.literal("" + dimension + " is not a resettable run dimension.").withStyle(ChatFormatting.RED);
        }
        if (radiusBlocks < 0 || radiusBlocks > 4096) {
            return Component.literal("Pregeneration radius must be between 0 and 4096 blocks.").withStyle(ChatFormatting.RED);
        }
        var updated = new java.util.LinkedHashMap<>(this.config.pregeneration);
        updated.put(dimension.toString(), radiusBlocks);
        var previous = this.config.pregeneration;
        this.config.pregeneration = updated;
        try {
            this.config.save(SoulLink.configPath());
        } catch (IOException e) {
            this.config.pregeneration = previous;
            SoulLink.LOGGER.error("Could not save the Soul Link config", e);
            return Component.literal("Could not save the pregeneration setting: " + e.getMessage())
                    .withStyle(ChatFormatting.RED);
        }
        this.preparation.setPregenerationRadii(this.config.pregenerationRadii());
        return Component.literal("Pregeneration for " + dimension + " set to "
                + (radiusBlocks == 0 ? "off" : radiusBlocks + " blocks") + " for new preparations.");
    }

    public boolean isLobbyProtected(ServerPlayer player) {
        return LobbyService.isIn(player);
    }

    /** Cancels all lobby damage and immediately returns players hit by void damage to the platform. */
    public boolean shouldCancelLobbyDamage(ServerPlayer player, DamageSource source) {
        if (!LobbyService.isIn(player)) {
            return false;
        }
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            LobbyService.teleport(this.server, player);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers

    private boolean isRunLevel(Level level) {
        return this.runLevels.contains(level.dimension());
    }

    private boolean isParticipant(ServerPlayer player) {
        return this.participantIds.contains(player.getUUID());
    }

    private void syncParticipantIds() {
        this.participantIds.clear();
        this.state.participants.forEach(id -> this.participantIds.add(UUID.fromString(id)));
    }

    /** Participants who are alive and playing in the run's dimensions: the players whose state is linked. */
    private List<ServerPlayer> activeParticipants() {
        List<ServerPlayer> players = new ArrayList<>(this.participantIds.size());
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            if (isActiveParticipant(player)) {
                players.add(player);
            }
        }
        return players;
    }

    private boolean isActiveParticipant(ServerPlayer player) {
        return isParticipant(player) && player.isAlive() && isRunLevel(player.level()) && !player.isSpectator()
                && !player.isCreative();
    }

    private List<ServerPlayer> onlineParticipants() {
        List<ServerPlayer> players = new ArrayList<>(this.participantIds.size());
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            if (this.participantIds.contains(player.getUUID())) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * Where vanilla would place a new player at this world spawn: a collision-free spot within the
     * {@code respawn_radius} game rule. The spawn block itself can be inside terrain.
     */
    private static Vec3 spawnPointFor(ServerPlayer player, ServerLevel level, BlockPos spawn) {
        return Vec3.atBottomCenterOf(player.adjustSpawnLocation(level, spawn));
    }

    private void placeEliminatedSpectator(ServerPlayer player) {
        player.setGameMode(GameType.SPECTATOR);
        if (!isRunLevel(player.level())) {
            ServerLevel overworld = this.server.overworld();
            player.teleport(new TeleportTransition(overworld, spawnPointFor(player, overworld, runSpawn()), Vec3.ZERO,
                    0.0F, 0.0F, TeleportTransition.DO_NOTHING));
        }
    }

    private BlockPos runSpawn() {
        return new BlockPos(this.state.spawnX, this.state.spawnY, this.state.spawnZ);
    }

    private LevelData.RespawnData runRespawnData() {
        return LevelData.RespawnData.of(Level.OVERWORLD, runSpawn(), 0.0F, 0.0F);
    }

    private void broadcast(Component message) {
        this.server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private void actionBarAll(Component message) {
        this.server.getPlayerList().broadcastSystemMessage(message, true);
    }

    private void title(Component title, Component subtitle) {
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitle));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
        }
    }

    private void save() {
        this.stateWriter.save(this.state);
    }

    public SoulLinkState state() {
        return this.state;
    }

    public WorldPreparationService preparation() {
        return this.preparation;
    }

    public CanonicalActivationService activation() {
        return this.activation;
    }

    public DimensionPolicy policy() {
        return this.policy;
    }

    public WorldPaths paths() {
        return this.paths;
    }

    public String runInfo() {
        StringBuilder info = new StringBuilder("Status: ").append(this.state.status);
        if (this.state.activeRunId != null) {
            info.append(" | Run ").append(this.state.activeRunId, 0, 8);
        }
        info.append(" | Time ").append(this.state.timerPaused ? "Paused — " : "")
                .append(RunTimer.format(this.state.elapsedTicks));
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            if (this.state.sharedVitality) {
                info.append(String.format(" | Shared health %.1f Food %d", this.vitality.health(), this.vitality.food()));
            } else {
                info.append(" | Shared vitality off");
            }
        }
        info.append(" | Next world: ").append(this.preparation.progressDescription());
        info.append(" | Prepared: ").append(this.preparation.readyCount()).append('/')
                .append(this.preparation.readyLimit());
        if (this.state.bestVictoryTicks != null) {
            info.append(" | Best ").append(RunTimer.format(this.state.bestVictoryTicks));
        }
        return info.toString();
    }

    /** Readable operator status; raw JSON remains available under the debug command. */
    public Component statusInfo() {
        StringBuilder info = new StringBuilder("Soul Link status\n")
                .append("State: ").append(this.state.status);
        if (this.state.activeRunId != null) {
            info.append("\nRun: ").append(this.state.activeRunId, 0, Math.min(8, this.state.activeRunId.length()));
        }
        info.append("\nElapsed: ").append(RunTimer.format(this.state.elapsedTicks));
        if (this.state.timerPaused) {
            info.append(" (timer paused; gameplay continues)");
        }
        info.append("\nParticipants: ").append(onlineParticipants().size()).append(" online / ")
                .append(this.state.participants.size()).append(" in this run");
        info.append("\nPrepared future runs: ").append(this.preparation.readyCount()).append(" / ")
                .append(this.preparation.readyLimit());
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            if (this.state.sharedVitality) {
                info.append(String.format("\nShared health: %.1f | Food: %d", this.vitality.health(), this.vitality.food()));
            }
        }
        info.append("\nShared vitality: current run ").append(this.state.sharedVitality ? "on" : "off")
                .append(", next run ").append(this.config.sharedVitality ? "on" : "off");
        info.append("\nSoul Link Hardcore: current run ").append(this.state.soulLinkHardcore ? "on" : "off")
                .append(", next run ").append(this.config.soulLinkHardcore ? "on" : "off");
        if (!this.state.eliminatedParticipants.isEmpty()) {
            info.append("\nEliminated: ").append(this.state.eliminatedParticipants.size()).append(" / ")
                    .append(this.state.participants.size());
        }
        info.append("\nShared potions: current run ").append(this.state.sharedPotions ? "on" : "off")
                .append(", next run ").append(this.config.sharedPotions ? "on" : "off");
        info.append("\nHalf-heart: current run ").append(this.state.halfHeart ? "on" : "off")
                .append(", next run ").append(this.config.halfHeart ? "on" : "off");
        info.append("\nNext world: ").append(this.preparation.progressDescription());
        info.append("\nNatural regeneration: ")
                .append(MinecraftCompat.naturalRegeneration(this.server) ? "enabled" : "disabled");
        if (this.state.bestVictoryTicks != null) {
            info.append("\nBest time: ").append(RunTimer.format(this.state.bestVictoryTicks));
        }
        if (this.lastActivationError != null) {
            info.append("\nLast activation error: ").append(this.lastActivationError);
        }
        if (this.preparation.lastFailure() != null) {
            info.append("\nLast preparation error: ").append(this.preparation.lastFailure())
                    .append("\nRecovery: check the server log, then try /soullink prepare.");
        }
        return Component.literal(info.toString());
    }

    /** Machine-readable lifecycle dump for {@code /soullink debug lifecycle} (spec 9.1). */
    public JsonObject describe() {
        JsonObject json = new JsonObject();
        json.addProperty("status", this.state.status.name());
        json.addProperty("result", this.state.result.name());
        if (this.state.endReason != null) {
            json.addProperty("endReason", this.state.endReason);
        }
        json.addProperty("activeRunId", this.state.activeRunId);
        json.addProperty("activeRunStarted", this.state.activeRunStarted);
        json.addProperty("runSeed", this.state.seed);
        json.addProperty("globalSeed", MinecraftCompat.globalSeed(this.server));
        json.addProperty("overworldSeed", this.server.overworld().getSeed());
        JsonArray spawn = new JsonArray();
        spawn.add(this.state.spawnX);
        spawn.add(this.state.spawnY);
        spawn.add(this.state.spawnZ);
        json.add("spawn", spawn);
        if (this.startupSpawn != null) {
            JsonObject startup = new JsonObject();
            startup.addProperty("dimension", this.startupSpawn.dimension().identifier().toString());
            startup.addProperty("x", this.startupSpawn.pos().getX());
            startup.addProperty("y", this.startupSpawn.pos().getY());
            startup.addProperty("z", this.startupSpawn.pos().getZ());
            json.add("startupSpawn", startup);
        }
        json.addProperty("elapsedTicks", this.state.elapsedTicks);
        json.addProperty("timerPaused", this.state.timerPaused);
        JsonArray participants = new JsonArray();
        this.state.participants.forEach(participants::add);
        json.add("participants", participants);
        JsonArray eliminated = new JsonArray();
        this.state.eliminatedParticipants.forEach(eliminated::add);
        json.add("eliminatedParticipants", eliminated);
        JsonObject shared = new JsonObject();
        shared.addProperty("enabled", this.state.sharedVitality);
        if (this.state.sharedVitality) {
            shared.addProperty("health", this.vitality.health());
            shared.addProperty("food", this.vitality.food());
            shared.addProperty("saturation", this.vitality.saturation());
        }
        json.add("shared", shared);
        JsonObject rules = new JsonObject();
        rules.addProperty("sharedVitality", this.state.sharedVitality);
        rules.addProperty("soulLinkHardcore", this.state.soulLinkHardcore);
        rules.addProperty("sharedPotions", this.state.sharedPotions);
        rules.addProperty("halfHeart", this.state.halfHeart);
        rules.addProperty("naturalRegeneration", MinecraftCompat.naturalRegeneration(this.server));
        json.add("rules", rules);
        JsonObject nextRules = new JsonObject();
        nextRules.addProperty("sharedVitality", this.config.sharedVitality);
        nextRules.addProperty("soulLinkHardcore", this.config.soulLinkHardcore);
        nextRules.addProperty("sharedPotions", this.config.sharedPotions);
        nextRules.addProperty("halfHeart", this.config.halfHeart);
        json.add("nextRules", nextRules);
        json.add("tickCost", this.tickCost.describe());
        json.addProperty("runsStarted", this.state.runsStarted);
        json.addProperty("victories", this.state.victories);
        json.add("preparation", this.preparation.describe());
        json.add("pregeneration", LivePregeneration.describe());
        json.add("activation", this.activation.describe());
        try {
            ActivationJournal.load(this.paths).ifPresent(journal -> {
                JsonObject journalJson = new JsonObject();
                journalJson.addProperty("runId", journal.runId);
                journalJson.addProperty("phase", journal.phase.name());
                if (journal.failure != null) {
                    journalJson.addProperty("failure", journal.failure);
                }
                json.add("journal", journalJson);
            });
        } catch (IOException e) {
            json.addProperty("journalError", e.toString());
        }
        json.add("levels", CanonicalActivationService.describeLevels(this.server, this.policy));
        if (this.lastActivationError != null) {
            json.addProperty("lastActivationError", this.lastActivationError);
        }
        json.addProperty("faultArmed", FaultInjection.armed());
        return json;
    }
}
