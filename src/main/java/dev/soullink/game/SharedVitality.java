package dev.soullink.game;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.StringJoiner;

import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * Shared health, hunger and saturation (the Soul Link). Each tick, every participant's change since the
 * last sync is folded into one shared pool, which is then written back to everyone.
 *
 * <p>Damage and food changes add up across players. Healing takes the largest single gain, so two
 * players regenerating on the same tick heal the pool once, not twice.
 */
public final class SharedVitality {
    private final Map<UUID, Snapshot> lastSynced = new HashMap<>();
    private float health = -1.0F;
    private int food = 20;
    private float saturation = 5.0F;

    public void restore(float health, int food, float saturation) {
        this.health = health;
        this.food = food;
        this.saturation = saturation;
        this.lastSynced.clear();
    }

    public void reset(float maxHealth) {
        restore(maxHealth, 20, 5.0F);
    }

    public float health() {
        return this.health;
    }

    public int food() {
        return this.food;
    }

    public float saturation() {
        return this.saturation;
    }

    public void forget(UUID player) {
        this.lastSynced.remove(player);
    }

    /** Returns true when the shared pool is depleted. */
    public boolean tick(List<ServerPlayer> participants) {
        if (participants.isEmpty()) {
            return false;
        }
        if (this.health < 0.0F) {
            ServerPlayer first = participants.getFirst();
            restore(first.getHealth(), first.getFoodData().getFoodLevel(), first.getFoodData().getSaturationLevel());
        }
        if (allInSync(participants)) {
            // The common case: nobody was hurt, healed or got hungrier since the last tick.
            return false;
        }
        List<Delta> deltas = new java.util.ArrayList<>();
        Map<UUID, Float> damageTaken = new HashMap<>();
        for (ServerPlayer player : participants) {
            Snapshot last = this.lastSynced.get(player.getUUID());
            if (last == null) {
                continue;
            }
            Delta delta = new Delta(player.getHealth() - last.health(),
                    player.getFoodData().getFoodLevel() - last.food(),
                    player.getFoodData().getSaturationLevel() - last.saturation());
            if (delta.health() < 0.0F) {
                damageTaken.put(player.getUUID(), -delta.health());
            }
            deltas.add(delta);
        }
        float maxHealth = participants.stream().map(ServerPlayer::getMaxHealth).min(Float::compare).orElse(20.0F);
        Pool pool = fold(new Pool(this.health, this.food, this.saturation), maxHealth, deltas);
        this.health = pool.health();
        this.food = pool.food();
        this.saturation = pool.saturation();
        boolean someoneHurt = !damageTaken.isEmpty();
        if (someoneHurt) {
            StringJoiner names = new StringJoiner(", ");
            float damage = 0.0F;
            for (ServerPlayer player : participants) {
                Float taken = damageTaken.get(player.getUUID());
                if (taken != null) {
                    names.add(player.getGameProfile().name());
                    damage += taken;
                }
            }
            String message = String.format(java.util.Locale.ROOT,
                    "%s lost %.1f health. Shared health: %.1f/%.1f.", names, damage, this.health, maxHealth);
            Component component = Component.literal(message);
            for (ServerPlayer player : participants) {
                player.sendSystemMessage(component, false);
            }
        }
        if (this.health <= 0.0F) {
            return true;
        }
        for (ServerPlayer player : participants) {
            if (someoneHurt && !damageTaken.containsKey(player.getUUID())) {
                // Someone else was hurt: show the shared hit.
                player.connection.send(new ClientboundHurtAnimationPacket(player));
                player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 1.0F);
            }
            if (Math.abs(player.getHealth() - this.health) > 1.0E-4F) {
                player.setHealth(Math.min(this.health, player.getMaxHealth()));
            }
            player.getFoodData().setFoodLevel(this.food);
            player.getFoodData().setSaturation(this.saturation);
            this.lastSynced.put(player.getUUID(), new Snapshot(player.getHealth(), this.food, this.saturation));
        }
        return false;
    }

    private boolean allInSync(List<ServerPlayer> participants) {
        for (ServerPlayer player : participants) {
            Snapshot last = this.lastSynced.get(player.getUUID());
            if (last == null || last.health() != player.getHealth() || last.food() != player.getFoodData().getFoodLevel()
                    || last.saturation() != player.getFoodData().getSaturationLevel()) {
                return false;
            }
        }
        return true;
    }

    /** Folds per-player changes into the pool: damage and food add up, healing takes the largest gain. */
    public static Pool fold(Pool pool, float maxHealth, List<Delta> deltas) {
        float damage = 0.0F;
        float heal = 0.0F;
        int food = 0;
        float saturation = 0.0F;
        for (Delta delta : deltas) {
            if (delta.health() < 0.0F) {
                damage -= delta.health();
            } else {
                heal = Math.max(heal, delta.health());
            }
            food += delta.food();
            saturation += delta.saturation();
        }
        float health = Mth.clamp(pool.health() - damage + heal, 0.0F, maxHealth);
        int newFood = Mth.clamp(pool.food() + food, 0, 20);
        float newSaturation = Mth.clamp(pool.saturation() + saturation, 0.0F, newFood);
        return new Pool(health, newFood, newSaturation);
    }

    public record Pool(float health, int food, float saturation) {
    }

    public record Delta(float health, int food, float saturation) {
    }

    private record Snapshot(float health, int food, float saturation) {
    }
}
