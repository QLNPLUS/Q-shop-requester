package com.qshop.requester;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import com.qshop.shop.Shop;
import com.qshop.shop.ShopEntryType;
import com.qshop.shop.ShopManager;

import javax.annotation.Nullable;
import java.util.UUID;

public final class RequesterBlockEntity extends BlockEntity {
    public static final int MAX_INTERVAL_TICKS = 20 * 60 * 60 * 24 * 7;
    public static final int DEFAULT_INTERVAL_TICKS = 1200;

    private final ItemStackHandler purchased = new ItemStackHandler(12) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    };
    private final ItemStackHandler supplied = new ItemStackHandler(12) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    };
    private final LazyOptional<IItemHandler> inputCapability = LazyOptional.of(
            () -> new RequesterItemHandler(supplied, true, false));
    private final LazyOptional<IItemHandler> outputCapability = LazyOptional.of(
            () -> new RequesterItemHandler(purchased, false, true));

    @Nullable private UUID owner;
    private String ownerName = "";
    private int intervalTicks = DEFAULT_INTERVAL_TICKS;
    private boolean actionBarNotifications = true;
    private boolean chatNotifications = true;
    private boolean enabled = true;
    private String shopId = "";
    private int tabIndex = 0;
    private int entryIndex = 0;
    private long nextTradeTick = -1L;

    public RequesterBlockEntity(BlockPos pos, BlockState state) {
        super(RequesterMod.REQUESTER_ENTITY.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RequesterBlockEntity box) {
        if (!(level.getServer() != null) || level.isClientSide || !box.enabled) return;
        long now = level.getGameTime();
        if (box.nextTradeTick < 0L) {
            box.nextTradeTick = now + box.intervalTicks;
            return;
        }
        if (now >= box.nextTradeTick) {
            box.nextTradeTick = now + box.intervalTicks;
            RequesterService.request(box);
        }
    }

    public ItemStackHandler purchased() { return purchased; }
    public ItemStackHandler supplied() { return supplied; }
    @Nullable public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public int intervalTicks() { return intervalTicks; }
    public boolean showActionBarNotification() { return actionBarNotifications; }
    public boolean showChatNotification() { return chatNotifications; }
    public boolean enabled() { return enabled; }
    public String shopId() { return shopId; }
    public int tabIndex() { return tabIndex; }
    public int entryIndex() { return entryIndex; }

    public ShopEntryType selectedType() {
        Shop shop = ShopManager.get(shopId);
        if (shop == null || tabIndex >= shop.tabs.size()) return ShopEntryType.BUY;
        var entries = shop.tabs.get(tabIndex).entries;
        return entryIndex >= 0 && entryIndex < entries.size()
                ? entries.get(entryIndex).type : ShopEntryType.BUY;
    }

    public void setOwner(UUID uuid, String name) {
        owner = uuid;
        ownerName = name == null ? "" : name;
        setChanged();
    }

    public boolean canEdit(Player player) {
        return player.hasPermissions(2) || owner != null && owner.equals(player.getUUID());
    }

    public boolean stillValid(Player player) {
        return level != null && level.getBlockState(worldPosition).is(RequesterMod.REQUESTER.get())
                && player.distanceToSqr(worldPosition.getX() + 0.5D,
                worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64D;
    }

    public void setSettings(int intervalTicks, boolean actionBar, boolean chat,
                            boolean enabled, String shopId, int tabIndex, int entryIndex) {
        this.intervalTicks = Math.max(20, Math.min(intervalTicks, MAX_INTERVAL_TICKS));
        this.actionBarNotifications = actionBar;
        this.chatNotifications = chat;
        this.enabled = enabled;
        this.shopId = shopId == null ? "" : shopId.substring(0, Math.min(128, shopId.length()));
        this.tabIndex = Math.max(0, tabIndex);
        this.entryIndex = Math.max(0, entryIndex);
        this.nextTradeTick = level == null ? -1L : level.getGameTime() + this.intervalTicks;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("purchased", purchased.serializeNBT());
        tag.put("supplied", supplied.serializeNBT());
        if (owner != null) tag.putUUID("owner", owner);
        tag.putString("ownerName", ownerName);
        tag.putInt("intervalTicks", intervalTicks);
        tag.putBoolean("actionBar", actionBarNotifications);
        tag.putBoolean("chat", chatNotifications);
        tag.putBoolean("enabled", enabled);
        tag.putString("shopId", shopId);
        tag.putInt("tabIndex", tabIndex);
        tag.putInt("entryIndex", entryIndex);
        if (nextTradeTick >= 0L) tag.putLong("nextTradeTick", nextTradeTick);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("purchased")) purchased.deserializeNBT(tag.getCompound("purchased"));
        if (tag.contains("supplied")) supplied.deserializeNBT(tag.getCompound("supplied"));
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("ownerName");
        intervalTicks = Math.max(20, Math.min(tag.getInt("intervalTicks"), MAX_INTERVAL_TICKS));
        if (!tag.contains("intervalTicks")) intervalTicks = DEFAULT_INTERVAL_TICKS;
        actionBarNotifications = !tag.contains("actionBar") || tag.getBoolean("actionBar");
        chatNotifications = !tag.contains("chat") || tag.getBoolean("chat");
        enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        shopId = tag.getString("shopId");
        tabIndex = Math.max(0, tag.getInt("tabIndex"));
        entryIndex = Math.max(0, tag.getInt("entryIndex"));
        nextTradeTick = tag.contains("nextTradeTick") ? tag.getLong("nextTradeTick") : -1L;
    }

    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability != ForgeCapabilities.ITEM_HANDLER) return super.getCapability(capability, side);
        return (side == Direction.DOWN ? outputCapability : inputCapability).cast();
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        inputCapability.invalidate();
        outputCapability.invalidate();
    }
}
