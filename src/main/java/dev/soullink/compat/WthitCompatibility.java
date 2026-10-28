package dev.soullink.compat;

import dev.soullink.SoulLink;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;

/** Optional compatibility hooks for WTHIT's server-side data providers. */
public final class WthitCompatibility {
    private static final String MOD_ID = "wthit";
    private static final String FLUID_PROVIDER = "mcp.mobius.waila.plugin.textile.provider.FluidStorageProvider";

    private WthitCompatibility() {}

    /**
     * Drops WTHIT's cached block lookup before canonical levels are retired.
     *
     * <p>WTHIT 20.0.0 keeps a {@code BlockApiCache} on its singleton fluid provider. That cache is bound to a
     * particular level, but the provider dereferences it before deciding whether the next request targets a
     * different level. Clearing it before a world swap prevents a post-swap tooltip request from loading a chunk
     * through a retired level. WTHIT remains optional; if its internal field changes, log the incompatibility and
     * continue the world transition.</p>
     */
    public static void beforeWorldReplacement() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return;
        }

        try {
            Class<?> providerClass = Class.forName(FLUID_PROVIDER, false, WthitCompatibility.class.getClassLoader());
            Object provider = providerClass.getField("INSTANCE").get(null);
            Field cache = providerClass.getDeclaredField("cache");
            cache.setAccessible(true);
            cache.set(provider, null);
            SoulLink.LOGGER.debug("Cleared WTHIT's fluid block lookup cache before world replacement");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            SoulLink.LOGGER.warn(
                    "Could not clear WTHIT's fluid block lookup cache before world replacement; "
                            + "the WTHIT/C2ME transition workaround is unavailable",
                    e);
        }
    }
}
