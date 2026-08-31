package com.qshop.requester;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
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

    @Nullable private UUID owner;
    private String ownerName = "";
    private int intervalTicks = DEFAULT_INTERVAL_TICKS;
    private boolean actionBarNotifications = true;
    private boolean chatNotifications = true;
    private boolean enabled = true;
    private String shopUuid = "";
    private String tabUuid = "";
    private String entryUuid = "";
    // Legacy fields are read only to migrate requester blocks created before 1.1.1.
    private String legacyShopId = "";
    private int legacyTabIndex = 0;
    private int legacyEntryIndex = 0;
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
    public String shopUuid() { return shopUuid; }
    public String tabUuid() { return tabUuid; }
    public String entryUuid() { return entryUuid; }

    /** Convert the old index-based target once Q-shop has loaded its live data. */
    public boolean migrateLegacyTarget() {
        if (!shopUuid.isBlank() || !tabUuid.isBlank() || !entryUuid.isBlank()
                || legacyShopId.isBlank()) return false;
        Shop shop = ShopManager.get(legacyShopId);
        if (shop == null) return false;
        shop.ensureTabs();
        if (legacyTabIndex < 0 || legacyTabIndex >= shop.tabs.size()) return false;
        var tab = shop.tabs.get(legacyTabIndex);
        if (legacyEntryIndex < 0 || legacyEntryIndex >= tab.entries.size()) return false;
        var entry = tab.entries.get(legacyEntryIndex);
        tab.ensureUuid();
        entry.ensureUuid();
        shopUuid = shop.uuid == null ? "" : shop.uuid.toString();
        tabUuid = tab.uuid;
        entryUuid = entry.uuid;
        legacyShopId = "";
        legacyTabIndex = 0;
        legacyEntryIndex = 0;
        setChanged();
        return true;
    }

    public RequesterTarget resolveTarget() {
        return RequesterTarget.resolve(shopUuid, tabUuid, entryUuid);
    }

    public ShopEntryType selectedType() {
        RequesterTarget target = resolveTarget();
        return target == null ? ShopEntryType.BUY : target.entry().type;
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
                            boolean enabled, String shopUuid, String tabUuid, String entryUuid) {
        this.intervalTicks = Math.max(20, Math.min(intervalTicks, MAX_INTERVAL_TICKS));
        this.actionBarNotifications = actionBar;
        this.chatNotifications = chat;
        this.enabled = enabled;
        this.shopUuid = bounded(shopUuid);
        this.tabUuid = bounded(tabUuid);
        this.entryUuid = bounded(entryUuid);
        this.legacyShopId = "";
        this.legacyTabIndex = 0;
        this.legacyEntryIndex = 0;
        this.nextTradeTick = level == null ? -1L : level.getGameTime() + this.intervalTicks;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("purchased", purchased.serializeNBT(registries));
        tag.put("supplied", supplied.serializeNBT(registries));
        if (owner != null) tag.putUUID("owner", owner);
        tag.putString("ownerName", ownerName);
        tag.putInt("intervalTicks", intervalTicks);
        tag.putBoolean("actionBar", actionBarNotifications);
        tag.putBoolean("chat", chatNotifications);
        tag.putBoolean("enabled", enabled);
        tag.putString("shopUuid", shopUuid);
        tag.putString("tabUuid", tabUuid);
        tag.putString("entryUuid", entryUuid);
        if (shopUuid.isBlank() && !legacyShopId.isBlank()) {
            tag.putString("shopId", legacyShopId);
            tag.putInt("tabIndex", legacyTabIndex);
            tag.putInt("entryIndex", legacyEntryIndex);
        }
        if (nextTradeTick >= 0L) tag.putLong("nextTradeTick", nextTradeTick);
    }

    @Override public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("purchased")) purchased.deserializeNBT(registries, tag.getCompound("purchased"));
        if (tag.contains("supplied")) supplied.deserializeNBT(registries, tag.getCompound("supplied"));
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("ownerName");
        intervalTicks = Math.max(20, Math.min(tag.getInt("intervalTicks"), MAX_INTERVAL_TICKS));
        if (!tag.contains("intervalTicks")) intervalTicks = DEFAULT_INTERVAL_TICKS;
        actionBarNotifications = !tag.contains("actionBar") || tag.getBoolean("actionBar");
        chatNotifications = !tag.contains("chat") || tag.getBoolean("chat");
        enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        shopUuid = tag.getString("shopUuid");
        tabUuid = tag.getString("tabUuid");
        entryUuid = tag.getString("entryUuid");
        legacyShopId = tag.getString("shopId");
        legacyTabIndex = Math.max(0, tag.getInt("tabIndex"));
        legacyEntryIndex = Math.max(0, tag.getInt("entryIndex"));
        nextTradeTick = tag.contains("nextTradeTick") ? tag.getLong("nextTradeTick") : -1L;
    }

    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(128, value.length()));
    }

}
