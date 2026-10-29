package dev.soullink.setup;

import dev.soullink.MinecraftCompat;
import dev.soullink.run.RunCoordinator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** Server-side handler backed by a vanilla generic chest screen on every client. */
public final class SetupMenu extends ChestMenu {
    private final RunCoordinator coordinator;
    private final SetupMenuView.Page page;
    private final int pageOffset;
    private final SimpleContainer inventory;

    private SetupMenu(int syncId, Inventory playerInventory, SimpleContainer inventory,
            RunCoordinator coordinator, SetupMenuView.Page page, int pageOffset) {
        super(page == SetupMenuView.Page.PREGENERATION ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3,
                syncId, playerInventory, inventory, page == SetupMenuView.Page.PREGENERATION ? 6 : 3);
        this.coordinator = coordinator;
        this.page = page;
        this.pageOffset = pageOffset;
        this.inventory = inventory;
    }

    public static void open(ServerPlayer player, RunCoordinator coordinator, SetupMenuView.Page page, int pageOffset) {
        SimpleContainer inventory = SetupMenuView.createInventory(coordinator, page, pageOffset);
        player.openMenu(new SimpleMenuProvider((syncId, playerInventory, openedPlayer) ->
                new SetupMenu(syncId, playerInventory, inventory, coordinator, page, pageOffset),
                SetupMenuView.title(page)));
    }

    @Override
    public void clicked(int slot, int button, ClickType clickType, Player player) {
        if (slot < 0 || slot >= this.inventory.getContainerSize()) {
            super.clicked(slot, button, clickType, player);
            return;
        }
        if (player instanceof ServerPlayer serverPlayer && this.stillValid(player)) {
            SetupMenuView.handleClick(serverPlayer, this.coordinator, this.page, this.pageOffset,
                    slot, button, clickType == ClickType.PICKUP, this.inventory, this);
        } else {
            this.broadcastChanges();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player instanceof ServerPlayer serverPlayer && MinecraftCompat.hasOperatorPermission(serverPlayer);
    }
}
