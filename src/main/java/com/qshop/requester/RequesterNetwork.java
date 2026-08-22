package com.qshop.requester;

import com.qshop.api.CurrencyService;
import com.qshop.currency.CurrencyRegistry;
import com.qshop.data.QShopSavedData;
import com.qshop.shop.Shop;
import com.qshop.shop.ShopEntry;
import com.qshop.shop.ShopEntryType;
import com.qshop.shop.ShopManager;
import com.qshop.shop.ShopTab;
import com.qshop.trade.RequirementCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class RequesterNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(RequesterMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static int packetId;

    private RequesterNetwork() {}

    public static void init() {
        CHANNEL.registerMessage(packetId++, SyncStatePacket.class,
                SyncStatePacket::encode, SyncStatePacket::decode, SyncStatePacket::handle);
        CHANNEL.registerMessage(packetId++, SyncShopsPacket.class,
                SyncShopsPacket::encode, SyncShopsPacket::decode, SyncShopsPacket::handle);
        CHANNEL.registerMessage(packetId++, SetSettingsPacket.class,
                SetSettingsPacket::encode, SetSettingsPacket::decode, SetSettingsPacket::handle);
    }

    public static void sendState(ServerPlayer player, RequesterBlockEntity box) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncStatePacket(
                box.getBlockPos(), box.intervalTicks(), box.showActionBarNotification(),
                box.showChatNotification(), box.enabled(), box.shopId(), box.tabIndex(), box.entryIndex()));
    }

    public static void sendShops(ServerPlayer player) {
        List<TargetInfo> targets = new ArrayList<>();
        for (Shop shop : ShopManager.all()) {
            shop.ensureTabs();
            for (int ti = 0; ti < shop.tabs.size(); ti++) {
                ShopTab tab = shop.tabs.get(ti);
                if (!RequirementCheck.satisfied(player, tab)) continue;
                for (int ei = 0; ei < tab.entries.size(); ei++) {
                    ShopEntry entry = tab.entries.get(ei);
                    if (entry.type != ShopEntryType.BUY && entry.type != ShopEntryType.BARTER) continue;
                    if (!entry.commands.isEmpty()) continue;
                    if (entry.type == ShopEntryType.BUY && entry.item.isEmpty()) continue;
                    if (entry.type == ShopEntryType.BARTER
                            && (entry.give.isEmpty() || entry.receive.isEmpty())) continue;
                    if (!available(player, shop, ti, ei, entry)) continue;
                    ItemStack display = !entry.displayItem.isEmpty() ? entry.displayItem
                            : entry.type == ShopEntryType.BUY ? entry.item : entry.receive.get(0);
                    String label = entry.displayNameOrItem();
                    targets.add(new TargetInfo(shop.id, shop.displayNameOrId(), ti, tab.name,
                            ei, entry.type, label, display.copy(), copy(entry.give), copy(entry.receive),
                            entry.price, CurrencyRegistry.displayName(entry.currencyId)));
                }
            }
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncShopsPacket(targets));
    }

    public static void sendSettings(BlockPos pos, int intervalTicks, boolean actionBar,
                                    boolean chat, boolean enabled, String shopId,
                                    int tabIndex, int entryIndex) {
        CHANNEL.sendToServer(new SetSettingsPacket(pos, intervalTicks, actionBar, chat,
                enabled, shopId, tabIndex, entryIndex));
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }

    private static boolean available(ServerPlayer player, Shop shop, int tabIndex,
                                     int entryIndex, ShopEntry entry) {
        if (!RequirementCheck.satisfied(player, entry)) return false;
        if (!Double.isFinite(entry.price) || entry.price < 0
                || (entry.price > 0 && (entry.currencyId == null || entry.currencyId.isBlank()))) {
            return false;
        }
        int itemsPerUnit = entry.type == ShopEntryType.BUY
                ? entry.item.getCount()
                : entry.receive.stream().mapToInt(ItemStack::getCount).sum();
        if (itemsPerUnit <= 0) return false;
        String key = entry.uuid != null && !entry.uuid.isEmpty()
                ? shop.id + "|" + entry.uuid
                : shop.id + "|" + tabIndex + "|" + entryIndex;
        String period = entry.reset.periodKey();
        if (entry.globalLimit > 0) {
            int used = QShopSavedData.get(player.getServer()).globalCounts.getCount(key, period);
            if (entry.globalLimit - used < 1) return false;
        }
        if (entry.playerLimit > 0) {
            int used = CurrencyService.INSTANCE.getLimitCount(player.getServer(), player.getUUID(), key, period);
            if (entry.playerLimit - used < 1) {
                return false;
            }
        }
        return true;
    }

    private static void enqueueClient(Runnable action) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> action.run());
    }

    public record SyncStatePacket(BlockPos pos, int intervalTicks, boolean actionBar,
                                  boolean chat, boolean enabled, String shopId,
                                  int tabIndex, int entryIndex) {
        public static void encode(SyncStatePacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos); b.writeVarInt(p.intervalTicks);
            b.writeBoolean(p.actionBar); b.writeBoolean(p.chat); b.writeBoolean(p.enabled);
            b.writeUtf(p.shopId == null ? "" : p.shopId, 128);
            b.writeVarInt(p.tabIndex); b.writeVarInt(p.entryIndex);
        }
        public static SyncStatePacket decode(FriendlyByteBuf b) {
            return new SyncStatePacket(b.readBlockPos(), b.readVarInt(), b.readBoolean(),
                    b.readBoolean(), b.readBoolean(), b.readUtf(128), b.readVarInt(), b.readVarInt());
        }
        public static void handle(SyncStatePacket p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> enqueueClient(() -> RequesterClient.applyState(p)));
            context.setPacketHandled(true);
        }
    }

    public record SyncShopsPacket(List<TargetInfo> targets) {
        public static void encode(SyncShopsPacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.targets.size());
            for (TargetInfo target : p.targets) target.encode(b);
        }
        public static SyncShopsPacket decode(FriendlyByteBuf b) {
            int count = Math.min(b.readVarInt(), 10000);
            List<TargetInfo> targets = new ArrayList<>();
            for (int i = 0; i < count; i++) targets.add(TargetInfo.decode(b));
            return new SyncShopsPacket(targets);
        }
        public static void handle(SyncShopsPacket p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> enqueueClient(() -> RequesterClient.applyShops(p.targets)));
            context.setPacketHandled(true);
        }
    }

    public static final class TargetInfo {
        public final String shopId;
        public final String shopName;
        public final int tabIndex;
        public final String tabName;
        public final int entryIndex;
        public final ShopEntryType type;
        public final String label;
        public final ItemStack display;
        public final List<ItemStack> give;
        public final List<ItemStack> receive;
        public final double price;
        public final String currency;

        public TargetInfo(String shopId, String shopName, int tabIndex, String tabName,
                          int entryIndex, ShopEntryType type, String label, ItemStack display,
                          List<ItemStack> give, List<ItemStack> receive, double price, String currency) {
            this.shopId = shopId; this.shopName = shopName; this.tabIndex = tabIndex;
            this.tabName = tabName; this.entryIndex = entryIndex; this.type = type;
            this.label = label; this.display = display; this.give = give; this.receive = receive;
            this.price = price; this.currency = currency == null ? "" : currency;
        }

        private void encode(FriendlyByteBuf b) {
            b.writeUtf(shopId, 128); b.writeUtf(shopName, 128); b.writeVarInt(tabIndex);
            b.writeUtf(tabName == null ? "" : tabName, 128); b.writeVarInt(entryIndex);
            b.writeEnum(type); b.writeUtf(label == null ? "" : label, 128); b.writeItem(display);
            writeStacks(b, give); writeStacks(b, receive); b.writeDouble(price);
            b.writeUtf(currency, 64);
        }

        private static TargetInfo decode(FriendlyByteBuf b) {
            String shopId = b.readUtf(128); String shopName = b.readUtf(128);
            int tabIndex = b.readVarInt(); String tabName = b.readUtf(128);
            int entryIndex = b.readVarInt(); ShopEntryType type = b.readEnum(ShopEntryType.class);
            String label = b.readUtf(128); ItemStack display = b.readItem();
            List<ItemStack> give = readStacks(b); List<ItemStack> receive = readStacks(b);
            return new TargetInfo(shopId, shopName, tabIndex, tabName, entryIndex, type, label,
                    display, give, receive, b.readDouble(), b.readUtf(64));
        }
    }

    private static void writeStacks(FriendlyByteBuf b, List<ItemStack> stacks) {
        b.writeVarInt(stacks.size()); for (ItemStack stack : stacks) b.writeItem(stack);
    }
    private static List<ItemStack> readStacks(FriendlyByteBuf b) {
        int count = Math.min(b.readVarInt(), 64); List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < count; i++) stacks.add(b.readItem()); return stacks;
    }

    public record SetSettingsPacket(BlockPos pos, int intervalTicks, boolean actionBar,
                                    boolean chat, boolean enabled, String shopId,
                                    int tabIndex, int entryIndex) {
        public static void encode(SetSettingsPacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos); b.writeVarInt(p.intervalTicks); b.writeBoolean(p.actionBar);
            b.writeBoolean(p.chat); b.writeBoolean(p.enabled); b.writeUtf(p.shopId, 128);
            b.writeVarInt(p.tabIndex); b.writeVarInt(p.entryIndex);
        }
        public static SetSettingsPacket decode(FriendlyByteBuf b) {
            return new SetSettingsPacket(b.readBlockPos(), b.readVarInt(), b.readBoolean(),
                    b.readBoolean(), b.readBoolean(), b.readUtf(128), b.readVarInt(), b.readVarInt());
        }
        public static void handle(SetSettingsPacket p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get(); ServerPlayer sender = context.getSender();
            if (sender != null) context.enqueueWork(() -> {
                if (sender.serverLevel().getBlockEntity(p.pos) instanceof RequesterBlockEntity box
                        && box.stillValid(sender) && box.canEdit(sender)) {
                    box.setSettings(p.intervalTicks, p.actionBar, p.chat, p.enabled,
                            p.shopId, p.tabIndex, p.entryIndex);
                    sendState(sender, box);
                } else {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "qshop_requester.message.not_owner"));
                }
            });
            context.setPacketHandled(true);
        }
    }
}
