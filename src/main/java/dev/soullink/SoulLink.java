package dev.soullink;

import java.nio.file.Path;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.TicketType;

import dev.soullink.lobby.LobbyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soul Link's identity and shared constants. Each loader has its own entrypoint overlay; they share
 * {@link SoulLinkRuntime}, which records the active {@link ModPlatform} here.
 */
public final class SoulLink {

    public static final String MOD_ID = "soullink";
    public static final String CONFIG_FILE = "soullink.json";
    public static final Logger LOGGER = LoggerFactory.getLogger("Soul Link");

    private static volatile @org.jspecify.annotations.Nullable ModPlatform platform;

    /**
     * Registers the lobby ticket type. Each loader calls this at the moment the built-in registries
     * still accept additions: a Fabric entrypoint does, and NeoForge does from its registry event,
     * because its registries are frozen by the time a {@code @Mod} constructor runs.
     */
    public static void registerTicketTypes() {
        LobbyService.TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(MOD_ID, "lobby"), new TicketType(0L, TicketType.FLAG_LOADING));
    }

    private SoulLink() {
    }

    /** Records the platform of the loader currently running this mod. Call once, at mod load. */
    static void setPlatform(ModPlatform value) {
        platform = value;
    }

    /** The active loader's platform; available for the whole lifetime of a loaded mod. */
    public static ModPlatform platform() {
        ModPlatform current = platform;
        if (current == null) {
            throw new IllegalStateException("Soul Link has not been initialized by a loader");
        }
        return current;
    }

    public static Path configPath() {
        return platform().configFile(CONFIG_FILE);
    }
}
