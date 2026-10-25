package dev.soullink;

import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Soul Link's identity and shared constants. The Fabric entrypoint is {@link SoulLinkInitializer}. */
public final class SoulLink {
    public static final String MOD_ID = "soullink";
    public static final Logger LOGGER = LoggerFactory.getLogger("Soul Link");

    private SoulLink() {
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("soullink.json");
    }
}
