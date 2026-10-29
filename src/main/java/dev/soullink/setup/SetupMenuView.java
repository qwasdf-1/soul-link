package dev.soullink.setup;

import java.util.List;
import java.util.StringJoiner;

import dev.soullink.MinecraftCompat;
import dev.soullink.run.RunCoordinator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Shared content and actions for the vanilla chest-style server setup menu. */
public final class SetupMenuView {
    private static final int PREGEN_DIMENSIONS_PER_PAGE = 5;

    public enum Page {
        MAIN,
        PREGENERATION,
        ADVANCED
    }

    private SetupMenuView() {
    }

    public static void open(ServerPlayer player, RunCoordinator coordinator, Page page, int pageOffset) {
        if (!MinecraftCompat.hasOperatorPermission(player)) {
            player.sendSystemMessage(Component.literal("Only server operators can change Soul Link settings.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int offset = normalizePregenOffset(coordinator.setupSnapshot().resettableDimensions(), pageOffset);
        MinecraftCompat.openSetupMenu(player, coordinator, page, offset);
    }

    public static SimpleContainer createInventory(RunCoordinator coordinator, Page page, int pageOffset) {
        int rows = page == Page.PREGENERATION ? 6 : 3;
        SimpleContainer inventory = new SimpleContainer(rows * 9);
        render(inventory, coordinator, page, pageOffset);
        return inventory;
    }

    public static Component title(Page page) {
        return Component.literal(switch (page) {
            case MAIN -> "Soul Link Setup";
            case PREGENERATION -> "Soul Link Pregeneration";
            case ADVANCED -> "Soul Link Advanced Settings";
        });
    }

    /** Handles only left-clicks on server-side menu buttons. */
    public static void handleClick(ServerPlayer player, RunCoordinator coordinator, Page page, int pageOffset,
            int slot, int button, boolean leftClick, SimpleContainer inventory, AbstractContainerMenu menu) {
        if (!MinecraftCompat.hasOperatorPermission(player)) {
            player.sendSystemMessage(Component.literal("Operator permission is required to change Soul Link settings.")
                    .withStyle(ChatFormatting.RED));
            MinecraftCompat.closeSetupMenu(player);
            return;
        }
        if (!leftClick || button != 0 || slot < 0 || slot >= inventory.getContainerSize()) {
            menu.broadcastChanges();
            return;
        }

        Component response = null;
        SetupSnapshot settings = coordinator.setupSnapshot();
        if (page == Page.MAIN) {
            switch (slot) {
                case 10 -> response = coordinator.setSharedVitality(!settings.sharedVitality());
                case 11 -> response = coordinator.setSoulLinkHardcore(!settings.soulLinkHardcore());
                case 12 -> response = coordinator.setSharedPotions(!settings.sharedPotions());
                case 13 -> response = coordinator.setHalfHeart(!settings.halfHeart());
                case 14 -> response = coordinator.setNaturalRegeneration(!settings.naturalRegeneration());
                case 15 -> response = coordinator.setAllowLateJoin(!settings.allowLateJoin());
                case 16 -> response = coordinator.setAutoPrepare(!settings.autoPrepare());
                case 19 -> response = coordinator.setPreparedRunPoolSize(settings.preparedRunPoolSize() - 1);
                case 21 -> response = coordinator.setPreparedRunPoolSize(settings.preparedRunPoolSize() + 1);
                case 23 -> {
                    open(player, coordinator, Page.PREGENERATION, 0);
                    return;
                }
                case 24 -> {
                    open(player, coordinator, Page.ADVANCED, 0);
                    return;
                }
                case 26 -> {
                    MinecraftCompat.closeSetupMenu(player);
                    return;
                }
                default -> {
                    // Decorative slots do not change settings.
                }
            }
        } else if (page == Page.PREGENERATION) {
            if (slot == 0) {
                open(player, coordinator, Page.MAIN, 0);
                return;
            }
            if (slot == 2 && pageOffset > 0) {
                open(player, coordinator, Page.PREGENERATION, pageOffset - PREGEN_DIMENSIONS_PER_PAGE);
                return;
            }
            if (slot == 6 && pageOffset + PREGEN_DIMENSIONS_PER_PAGE < settings.resettableDimensions().size()) {
                open(player, coordinator, Page.PREGENERATION, pageOffset + PREGEN_DIMENSIONS_PER_PAGE);
                return;
            }
            if (slot == 8) {
                MinecraftCompat.closeSetupMenu(player);
                return;
            }

            int row = slot / 9;
            int column = slot % 9;
            int dimensionIndex = pageOffset + row - 1;
            if (row >= 1 && row <= 5 && dimensionIndex >= 0
                    && dimensionIndex < settings.resettableDimensions().size()) {
                Identifier dimension = settings.resettableDimensions().get(dimensionIndex);
                String name = dimension.toString();
                int radius = settings.pregeneration().getOrDefault(name, 0);
                int updated = switch (column) {
                    case 1 -> 0;
                    case 2 -> radius - 1000;
                    case 3 -> radius - 100;
                    case 4 -> radius - 10;
                    case 5 -> radius + 10;
                    case 6 -> radius + 100;
                    case 7 -> radius + 1000;
                    case 8 -> settings.defaultPregeneration().getOrDefault(name, 0);
                    default -> radius;
                };
                if (column >= 1 && column <= 8) {
                    updated = Math.max(0, Math.min(4096, updated));
                    response = coordinator.setPregenerationRadius(name, updated);
                }
            }
        } else if (page == Page.ADVANCED) {
            if (slot == 0) {
                open(player, coordinator, Page.MAIN, 0);
                return;
            }
            if (slot == 26) {
                MinecraftCompat.closeSetupMenu(player);
                return;
            }
            if (slot == 16) {
                player.sendSystemMessage(listMessage("Resettable run dimensions", settings.resettableDimensions()));
            } else if (slot == 17) {
                player.sendSystemMessage(listMessage("Persistent dimensions", settings.persistentDimensions()));
            }
        }

        if (response != null) {
            player.sendSystemMessage(response);
        }
        render(inventory, coordinator, page, pageOffset);
        menu.broadcastChanges();
    }

    private static void render(SimpleContainer inventory, RunCoordinator coordinator, Page page, int pageOffset) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            inventory.setItem(slot, named(Items.GLASS_PANE, " ", ChatFormatting.DARK_GRAY));
        }
        SetupSnapshot settings = coordinator.setupSnapshot();
        switch (page) {
            case MAIN -> renderMain(inventory, settings);
            case PREGENERATION -> renderPregeneration(inventory, settings, pageOffset);
            case ADVANCED -> renderAdvanced(inventory, settings);
        }
    }

    private static void renderMain(SimpleContainer inventory, SetupSnapshot settings) {
        inventory.setItem(10, toggle(Items.HEART_OF_THE_SEA, "Shared vitality (next run)", settings.sharedVitality()));
        inventory.setItem(11, toggle(Items.TOTEM_OF_UNDYING, "Soul Link Hardcore (next run)", settings.soulLinkHardcore()));
        inventory.setItem(12, toggle(Items.POTION, "Shared potions (next run)", settings.sharedPotions()));
        inventory.setItem(13, toggle(Items.GOLDEN_APPLE, "Half-heart mode (next run)", settings.halfHeart()));
        inventory.setItem(14, toggle(Items.REDSTONE, "Natural regeneration (immediate)", settings.naturalRegeneration()));
        inventory.setItem(15, toggle(Items.ENDER_PEARL, "Allow late joins (immediate)", settings.allowLateJoin()));
        inventory.setItem(16, toggle(Items.CLOCK, "Automatic preparation (immediate)", settings.autoPrepare()));
        inventory.setItem(19, named(Items.REDSTONE, "Decrease future-run pool", ChatFormatting.RED));
        inventory.setItem(20, named(Items.CHEST, "Future-run pool: " + settings.preparedRunPoolSize()
                + " (" + settings.readyRunCount() + " ready)", ChatFormatting.GOLD));
        inventory.setItem(21, named(Items.SLIME_BALL, "Increase future-run pool", ChatFormatting.GREEN));
        inventory.setItem(23, named(Items.MAP, "Optional pregeneration", ChatFormatting.AQUA));
        inventory.setItem(24, named(Items.BOOK, "Advanced settings (some require restart)", ChatFormatting.YELLOW));
        inventory.setItem(26, named(Items.BARRIER, "Close", ChatFormatting.RED));
    }

    private static void renderPregeneration(SimpleContainer inventory, SetupSnapshot settings, int pageOffset) {
        int pageCount = Math.max(1,
                (settings.resettableDimensions().size() + PREGEN_DIMENSIONS_PER_PAGE - 1) / PREGEN_DIMENSIONS_PER_PAGE);
        int pageNumber = pageOffset / PREGEN_DIMENSIONS_PER_PAGE + 1;
        inventory.setItem(0, named(Items.ARROW, "Back to setup", ChatFormatting.WHITE));
        if (pageOffset > 0) {
            inventory.setItem(2, named(Items.ARROW, "Previous dimensions", ChatFormatting.WHITE));
        }
        inventory.setItem(4, named(Items.MAP, "Optional pregeneration — page " + pageNumber + " / " + pageCount,
                ChatFormatting.AQUA));
        if (pageOffset + PREGEN_DIMENSIONS_PER_PAGE < settings.resettableDimensions().size()) {
            inventory.setItem(6, named(Items.ARROW, "More dimensions", ChatFormatting.WHITE));
        }
        inventory.setItem(8, named(Items.BARRIER, "Close", ChatFormatting.RED));

        for (int row = 1; row <= PREGEN_DIMENSIONS_PER_PAGE; row++) {
            int dimensionIndex = pageOffset + row - 1;
            if (dimensionIndex >= settings.resettableDimensions().size()) {
                break;
            }
            Identifier dimension = settings.resettableDimensions().get(dimensionIndex);
            String id = dimension.toString();
            int radius = settings.pregeneration().getOrDefault(id, 0);
            int base = row * 9;
            inventory.setItem(base, named(Items.COMPASS, id + ": " + (radius == 0 ? "off" : radius + " blocks"),
                    ChatFormatting.GOLD));
            inventory.setItem(base + 1, named(Items.BARRIER, "Turn off", ChatFormatting.RED));
            inventory.setItem(base + 2, named(Items.REDSTONE, "−1000 blocks", ChatFormatting.RED));
            inventory.setItem(base + 3, named(Items.REDSTONE, "−100 blocks", ChatFormatting.RED));
            inventory.setItem(base + 4, named(Items.REDSTONE, "−10 blocks", ChatFormatting.RED));
            inventory.setItem(base + 5, named(Items.SLIME_BALL, "+10 blocks", ChatFormatting.GREEN));
            inventory.setItem(base + 6, named(Items.SLIME_BALL, "+100 blocks", ChatFormatting.GREEN));
            inventory.setItem(base + 7, named(Items.SLIME_BALL, "+1000 blocks", ChatFormatting.GREEN));
            inventory.setItem(base + 8, named(Items.SLIME_BALL, "Restore default: "
                    + settings.defaultPregeneration().getOrDefault(id, 0) + " blocks", ChatFormatting.YELLOW));
        }
    }

    private static void renderAdvanced(SimpleContainer inventory, SetupSnapshot settings) {
        inventory.setItem(0, named(Items.ARROW, "Back to setup", ChatFormatting.WHITE));
        inventory.setItem(10, named(Items.COMPASS,
                "Default spawn preload: " + settings.preloadRadius() + " chunks (restart required)", ChatFormatting.YELLOW));
        inventory.setItem(11, named(Items.CLOCK,
                "Active-run construction: " + settings.activeRunConstructionIntervalTicks() + " ticks (restart required)",
                ChatFormatting.YELLOW));
        inventory.setItem(12, named(Items.CLOCK,
                "Timeouts: prepare " + settings.preparationTimeoutSeconds() + "s / drain "
                        + settings.drainTimeoutSeconds() + "s (restart required)",
                ChatFormatting.YELLOW));
        inventory.setItem(13, toggle(Items.REDSTONE_TORCH,
                "Debug commands (restart required)", settings.debugCommands()));
        inventory.setItem(16, named(Items.ENDER_EYE,
                "Resettable dimensions: " + settings.resettableDimensions().size() + " (click to list)",
                ChatFormatting.AQUA));
        inventory.setItem(17, named(Items.OBSIDIAN,
                "Persistent dimensions: " + settings.persistentDimensions().size() + " (click to list)",
                ChatFormatting.AQUA));
        inventory.setItem(22, named(Items.PAPER, "Edit config/soullink.json and restart to change these values.",
                ChatFormatting.GRAY));
        inventory.setItem(26, named(Items.BARRIER, "Close", ChatFormatting.RED));
    }

    private static ItemStack toggle(Item item, String label, boolean enabled) {
        return named(item, label + ": " + (enabled ? "ON" : "OFF"),
                enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static ItemStack named(Item item, String label, ChatFormatting color) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label).withStyle(color));
        return stack;
    }

    private static Component listMessage(String title, List<?> values) {
        StringJoiner joiner = new StringJoiner(", ");
        values.forEach(value -> joiner.add(value.toString()));
        return Component.literal(title + ": " + (values.isEmpty() ? "none" : joiner));
    }

    private static int normalizePregenOffset(List<Identifier> dimensions, int requestedOffset) {
        int maxOffset = Math.max(0, ((dimensions.size() - 1) / PREGEN_DIMENSIONS_PER_PAGE) * PREGEN_DIMENSIONS_PER_PAGE);
        int clamped = Math.max(0, Math.min(maxOffset, requestedOffset));
        return (clamped / PREGEN_DIMENSIONS_PER_PAGE) * PREGEN_DIMENSIONS_PER_PAGE;
    }
}
