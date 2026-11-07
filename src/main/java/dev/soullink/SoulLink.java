package dev.soullink;

import java.nio.file.Path;

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
