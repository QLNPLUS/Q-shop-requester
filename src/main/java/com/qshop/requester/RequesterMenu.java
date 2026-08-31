package com.qshop.requester;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

import java.util.UUID;

public final class RequesterMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final RequesterBlockEntity box;
    private int intervalTicks = RequesterBlockEntity.DEFAULT_INTERVAL_TICKS;
    private boolean actionBar = true;
    private boolean chat = true;
    private boolean enabled = true;
    private UUID owner;
    private String ownerName = "";
    private String shopId = "";
    private int tabIndex;
    private int entryIndex;

    public RequesterMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, readBox(inventory, data));
    }

    private static RequesterBlockEntity readBox(Inventory inventory, FriendlyByteBuf data) {
        BlockPos pos = data.readBlockPos();
        if (inventory.player.level().getBlockEntity(pos) instanceof RequesterBlockEntity box) return box;
        return new RequesterBlockEntity(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    public RequesterMenu(int id, Inventory inventory, RequesterBlockEntity box) {
        super(RequesterMod.REQUESTER_MENU.get(), id);
        this.pos = box.getBlockPos();
        this.box = box;
        copyFromBox();

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                int index = col + row * 4;
                addSlot(new OutputSlot(box.purchased(), index, 8 + col * 18, 18 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                int index = col + row * 4;
                addSlot(new SlotItemHandler(box.supplied(), index, 98 + col * 18, 18 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 142));
    }

    private void copyFromBox() {
        intervalTicks = box.intervalTicks();
        actionBar = box.showActionBarNotification();
        chat = box.showChatNotification();
        enabled = box.enabled();
        owner = box.owner();
        ownerName = box.ownerName();
        shopId = box.shopId();
        tabIndex = box.tabIndex();
        entryIndex = box.entryIndex();
    }

    public BlockPos pos() { return pos; }
    public RequesterBlockEntity box() { return box; }
    public int intervalTicks() { return intervalTicks; }
    public boolean actionBar() { return actionBar; }
    public boolean chat() { return chat; }
    public boolean enabled() { return enabled; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public String shopId() { return shopId; }
    public int tabIndex() { return tabIndex; }
    public int entryIndex() { return entryIndex; }

    public void setSettings(int intervalTicks, boolean actionBar, boolean chat,
                            boolean enabled, String shopId, int tabIndex, int entryIndex) {
        this.intervalTicks = Math.max(20, Math.min(intervalTicks, RequesterBlockEntity.MAX_INTERVAL_TICKS));
        this.actionBar = actionBar;
        this.chat = chat;
        this.enabled = enabled;
        this.shopId = shopId == null ? "" : shopId;
        this.tabIndex = Math.max(0, tabIndex);
        this.entryIndex = Math.max(0, entryIndex);
    }

    public void setOwnerData(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName == null ? "" : ownerName;
    }

    @Override public boolean stillValid(Player player) { return box.stillValid(player); }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        ItemStack empty = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return empty;
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();
        if (index < 12) {
            if (!moveItemStackTo(original, 24, slots.size(), true)) return empty;
        } else if (index < 24) {
            if (!moveItemStackTo(original, 24, slots.size(), true)) return empty;
        } else if (!moveItemStackTo(original, 12, 24, false)) {
            return empty;
        }
        if (original.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override public void removed(Player player) {
        super.removed(player);
    }

    private static final class OutputSlot extends SlotItemHandler {
        private OutputSlot(net.minecraftforge.items.IItemHandler handler, int index, int x, int y) {
            super(handler, index, x, y);
        }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
    }
}
