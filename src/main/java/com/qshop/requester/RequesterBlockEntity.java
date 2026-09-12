package com.qshop.requester;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
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
        if (!(level.getServer() != null) || level.isClientSide() || !box.enabled
                || level.hasNeighborSignal(pos)) return;
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
        return player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) || owner != null && owner.equals(player.getUUID());
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

    // 26.1.2:saveAdditional/loadAdditional 改用 ValueOutput/ValueInput,不再暴露 CompoundTag。
    // ItemStackHandler 在 26.1.2 实现了 ValueIOSerializable(serialize/deserialize),
    // 直接委托给它,无需字节转换。UUID 存成两个 long,避免引入 Codec。
    @Override protected void saveAdditional(ValueOutput tag) {
        super.saveAdditional(tag);
        purchased.serialize(tag.child("purchased"));
        supplied.serialize(tag.child("supplied"));
        if (owner != null) putUuid(tag, "owner", owner);
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

    @Override public void loadAdditional(ValueInput tag) {
        super.loadAdditional(tag);
        tag.child("purchased").ifPresent(purchased::deserialize);
        tag.child("supplied").ifPresent(supplied::deserialize);
        owner = readUuid(tag, "owner");
        ownerName = tag.getStringOr("ownerName", "");
        intervalTicks = Math.max(20, Math.min(tag.getIntOr("intervalTicks", DEFAULT_INTERVAL_TICKS), MAX_INTERVAL_TICKS));
        actionBarNotifications = tag.getBooleanOr("actionBar", true);
        chatNotifications = tag.getBooleanOr("chat", true);
        enabled = tag.getBooleanOr("enabled", true);
        shopUuid = tag.getStringOr("shopUuid", "");
        tabUuid = tag.getStringOr("tabUuid", "");
        entryUuid = tag.getStringOr("entryUuid", "");
        legacyShopId = tag.getStringOr("shopId", "");
        legacyTabIndex = Math.max(0, tag.getIntOr("tabIndex", 0));
        legacyEntryIndex = Math.max(0, tag.getIntOr("entryIndex", 0));
        nextTradeTick = tag.getLongOr("nextTradeTick", -1L);
    }


    // 26.1.2 的 ValueOutput/ValueInput 没有 putUUID/hasUUID/getUUID,改用 Minecraft
    // 自带的 UUIDUtil.CODEC(Codec.INT_STREAM,即 4 个 int)。这与旧版
    // CompoundTag.putUUID 写入的 IntArrayTag 表示一致,因此存档格式不变、
    // 已放置的方块仍能读回 owner。
    private static void putUuid(ValueOutput tag, String name, UUID value) {
        tag.store(name, net.minecraft.core.UUIDUtil.CODEC, value);
    }

    private static UUID readUuid(ValueInput tag, String name) {
        return tag.read(name, net.minecraft.core.UUIDUtil.CODEC).orElse(null);
    }

    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(128, value.length()));
    }

}
