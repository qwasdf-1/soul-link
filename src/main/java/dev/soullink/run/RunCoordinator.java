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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
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
    private @Nullable String lastActivationError;
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
        save();
        SoulLink.LOGGER.info("Soul Link ready: status {}, active run {}, prepared run {}", this.state.status,
                this.state.activeRunId, this.preparation.ready().map(m -> m.runId).orElse("none"));
    }

    /** A prepared run is only valid for the data packs it was generated under (spec 8.6, test T17). */
    public void onDataPacksReloaded() {
        String fingerprint = RegistryFingerprint.compute(this.server, this.policy);
        this.preparation.ready().ifPresent(manifest -> {
            if (!fingerprint.equals(manifest.fingerprint)) {
                SoulLink.LOGGER.info("Data packs changed; discarding prepared run {}", manifest.runId);
                this.preparation.takeReady();
                this.preparation.deleteStageData(manifest.runUuid());
                this.nextPrepareTick = 0;
            }
        });
        String active = this.preparation.activeFingerprint();
        if (active != null && !fingerprint.equals(active)) {
            this.preparation.fail(this.server, "data packs changed during preparation");
            this.nextPrepareTick = 0;
        }
    }

    public void onServerStopping() {
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
        this.preparation.tick(this.server);
        switch (this.state.status) {
            case STARTING -> tickStarting();
            case RUNNING -> tickRunning();
            default -> {
            }
        }
        rescueLobbyFallers();
        if (this.config.autoPrepare && this.state.status != SoulLinkState.Status.STARTING && this.ticks >= this.nextPrepareTick
                && !this.preparation.isPreparing() && this.preparation.ready().isEmpty()) {
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
        if (this.ticks % 10 == 0) {
            int percent = Math.round(this.preparation.progress() * 100.0F);
            actionBarAll(Component.literal("Preparing worlds… " + percent + "%").withStyle(ChatFormatting.GRAY));
        }
    }

    private void tickRunning() {
        // The run clock only runs while someone is playing it: not while the server waits for players to
        // come back after a restart, and not while everyone has disconnected.
        if (!onlineParticipants().isEmpty()) {
            this.state.elapsedTicks++;
        }
        List<ServerPlayer> participants = activeParticipants();
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
        if (this.ticks % 10 == 0) {
            Component timer = Component.literal(RunTimer.format(this.state.elapsedTicks)).withStyle(ChatFormatting.GOLD);
            for (ServerPlayer player : onlineParticipants()) {
                player.sendSystemMessage(timer, true);
            }
        }
    }

    private void rescueLobbyFallers() {
        if (this.ticks % 5 != 0) {
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

    /** {@code /start}: enter a prepared run now, or as soon as preparation finishes. */
    public Component requestStart() {
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            return Component.literal("A run is already in progress. Use /reset to restart it.").withStyle(ChatFormatting.RED);
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

    /** {@code /reset}: abandon the current run and start the next one. */
    public Component requestReset() {
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            endRun(SoulLinkState.Result.ABANDONED, "the run was reset");
        }
        return requestStart();
    }

    /** {@code /stoprun}: end the run and return everyone to the lobby. */
    public Component stopRun() {
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            endRun(SoulLinkState.Result.ABANDONED, "the run was stopped");
        }
        this.state.status = SoulLinkState.Status.LOBBY;
        moveEveryoneToLobby();
        save();
        return Component.literal("Run stopped; everyone is back in the lobby.");
    }

    /** {@code /soullink prepare [seed]}: discard any prepared run and prepare a new one. */
    public Component prepare(@Nullable Long seed, @Nullable Integer preloadRadius) {
        if (this.preparation.isPreparing()) {
            this.preparation.fail(this.server, "replaced by an explicit prepare request");
        }
        this.preparation.ready().ifPresent(manifest -> {
            this.preparation.takeReady();
            this.preparation.deleteStageData(manifest.runUuid());
        });
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
            return true;
        } catch (LifecycleException e) {
            SoulLink.LOGGER.error("Could not begin run preparation", e);
            this.nextPrepareTick = this.ticks + PREPARE_RETRY_TICKS;
            return false;
        }
    }

    private Component startPreparedRun() {
        // Phase 1: everyone into the lobby and back to baseline while their current levels are still live.
        this.swappedThisTick = true;
        moveEveryoneToLobby();
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
        // Start the next run's preparation while everyone still waits in the lobby: building its levels is
        // the one step that cannot be spread over ticks, so it belongs in this pause, not in the run.
        if (this.config.autoPrepare && !this.preparation.isPreparing() && this.preparation.ready().isEmpty()
                && beginPreparation(null)) {
            this.preparation.constructNow(this.server);
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
        this.vitality.reset(20.0F);
        this.state.sharedPotions = this.config.sharedPotions;
        this.state.participants.clear();
        ServerLevel overworld = this.server.overworld();
        for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
            SessionReset.resetPlayer(this.server, player, GameType.SURVIVAL);
            player.teleport(new TeleportTransition(overworld, spawnPointFor(player, overworld, spawn), Vec3.ZERO, 0.0F, 0.0F,
                    TeleportTransition.DO_NOTHING));
            this.state.participants.add(player.getUUID().toString());
            this.state.playerRuns.put(player.getUUID().toString(), runId);
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
                Component.literal("Defeat the Ender Dragon. Share every heartbeat."));
    }

    public void endRun(SoulLinkState.Result result, String reason) {
        if (this.state.status != SoulLinkState.Status.RUNNING) {
            return;
        }
        this.state.status = SoulLinkState.Status.ENDED;
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
                .withClickEvent(new ClickEvent.RunCommand("/start"))
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
        String uuid = player.getUUID().toString();
        String stamp = this.state.playerRuns.get(uuid);
        boolean currentParticipant = this.state.participants.contains(uuid) && this.state.activeRunId != null
                && this.state.activeRunId.equals(stamp);
        switch (this.state.status) {
            case RUNNING -> {
                if (currentParticipant) {
                    this.vitality.forget(player.getUUID());
                    if (!isRunLevel(player.level())) {
                        // Saved player data older than the run (a crash right after it started).
                        SoulLink.LOGGER.warn("{} rejoined outside the run; returning them to the run spawn",
                                player.getGameProfile().name());
                        ServerLevel overworld = this.server.overworld();
                        player.teleport(new TeleportTransition(overworld, spawnPointFor(player, overworld, runSpawn()),
                                Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
                        player.setGameMode(GameType.SURVIVAL);
                    }
                } else if (this.config.allowLateJoin) {
                    SessionReset.resetPlayer(this.server, player, GameType.SURVIVAL);
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
                if (!currentParticipant) {
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

    public void onPlayerLeave(ServerPlayer player) {
        this.vitality.forget(player.getUUID());
    }

    public void onPlayerDeath(ServerPlayer player) {
        if (this.state.status == SoulLinkState.Status.RUNNING && isParticipant(player) && isRunLevel(player.level())) {
            endRun(SoulLinkState.Result.DEFEAT, player.getGameProfile().name() + " died");
        }
    }

    public void onPlayerRespawn(ServerPlayer player) {
        switch (this.state.status) {
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
        this.config.sharedPotions = enabled;
        try {
            this.config.save(SoulLink.configPath());
        } catch (IOException e) {
            SoulLink.LOGGER.error("Could not save the Soul Link config", e);
            return Component.literal("Could not save the config: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
        return Component.literal("Shared potions " + (enabled ? "on" : "off") + " from the next run.");
    }

    public boolean isLobbyProtected(ServerPlayer player) {
        return LobbyService.isIn(player);
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
        info.append(" | Time ").append(RunTimer.format(this.state.elapsedTicks));
        if (this.state.status == SoulLinkState.Status.RUNNING) {
            info.append(String.format(" | Health %.1f Food %d", this.vitality.health(), this.vitality.food()));
        }
        info.append(" | Next world: ").append(this.preparation.ready().isPresent() ? "ready"
                : this.preparation.isPreparing() ? Math.round(this.preparation.progress() * 100) + "%" : "not prepared");
        if (this.state.bestVictoryTicks != null) {
            info.append(" | Best ").append(RunTimer.format(this.state.bestVictoryTicks));
        }
        return info.toString();
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
        json.addProperty("globalSeed", this.server.getWorldGenSettings().options().seed());
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
        JsonArray participants = new JsonArray();
        this.state.participants.forEach(participants::add);
        json.add("participants", participants);
        JsonObject shared = new JsonObject();
        shared.addProperty("health", this.vitality.health());
        shared.addProperty("food", this.vitality.food());
        shared.addProperty("saturation", this.vitality.saturation());
        json.add("shared", shared);
        JsonObject rules = new JsonObject();
        rules.addProperty("sharedPotions", this.state.sharedPotions);
        json.add("rules", rules);
        JsonObject nextRules = new JsonObject();
        nextRules.addProperty("sharedPotions", this.config.sharedPotions);
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
