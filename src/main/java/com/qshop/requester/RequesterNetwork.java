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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class RequesterNetwork {
    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(RequesterMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static int packetId;

    private RequesterNetwork() {}

    public static void init() {
        CHANNEL.registerMessage(packetId++, SyncStatePacket.class,
                SyncStatePacket::encode, SyncStatePacket::decode, SyncStatePacket::handle);
        CHANNEL.registerMessage(packetId++, ClaimOwnerPacket.class,
                ClaimOwnerPacket::encode, ClaimOwnerPacket::decode, ClaimOwnerPacket::handle);
        CHANNEL.registerMessage(packetId++, SyncShopsPacket.class,
                SyncShopsPacket::encode, SyncShopsPacket::decode, SyncShopsPacket::handle);
        CHANNEL.registerMessage(packetId++, SetSettingsPacket.class,
                SetSettingsPacket::encode, SetSettingsPacket::decode, SetSettingsPacket::handle);
        CHANNEL.registerMessage(packetId++, OpenShopSelectionPacket.class,
                OpenShopSelectionPacket::encode, OpenShopSelectionPacket::decode,
                OpenShopSelectionPacket::handle);
    }

    public static void sendState(ServerPlayer player, RequesterBlockEntity box) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncStatePacket(
                box.getBlockPos(), box.intervalTicks(), box.showActionBarNotification(),
                box.showChatNotification(), box.enabled(), box.owner(), box.ownerName(),
                box.shopId(), box.tabIndex(), box.entryIndex()));
    }

    public static void broadcastState(MinecraftServer server, RequesterBlockEntity box) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.distanceToSqr(box.getBlockPos().getX() + 0.5D,
                    box.getBlockPos().getY() + 0.5D, box.getBlockPos().getZ() + 0.5D) <= 64D
                    && player.containerMenu instanceof RequesterMenu menu
                    && menu.pos().equals(box.getBlockPos())) {
                sendState(player, box);
            }
        }
    }

    public static void sendShops(ServerPlayer player) {
        List<ShopInfo> shops = new ArrayList<>();
        List<TargetInfo> targets = new ArrayList<>();
        for (Shop shop : ShopManager.all()) {
            shop.ensureTabs();
            for (int ti = 0; ti < shop.tabs.size(); ti++) {
                ShopTab tab = shop.tabs.get(ti);
                if (!RequirementCheck.satisfied(player, tab)) continue;
                for (int ei = 0; ei < tab.entries.size(); ei++) {
                    ShopEntry entry = tab.entries.get(ei);
                    if (!validEntry(entry)) continue;
                    if (!available(player, shop, ti, ei, entry)) continue;
                    if (shops.stream().noneMatch(value -> value.shopId.equals(shop.id))) {
                        shops.add(new ShopInfo(shop.id, shop.displayNameOrId(), shop.icon.copy()));
                    }
                    ItemStack display = displayItem(entry);
                    String label = entry.displayNameOrItem();
                    List<ItemStack> supplied = entry.type == ShopEntryType.SELL
                            || (entry.type == ShopEntryType.COMMAND && !entry.item.isEmpty())
                            ? List.of(entry.item) : entry.give;
                    targets.add(new TargetInfo(shop.id, shop.displayNameOrId(), ti, tab.name,
                            ei, entry.type, label, display.copy(), copy(supplied), copy(entry.receive),
                            entry.price, CurrencyRegistry.displayName(entry.currencyId)));
                }
            }
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncShopsPacket(shops, targets));
    }

    public static void sendSettings(BlockPos pos, int intervalTicks, boolean actionBar,
                                    boolean chat, boolean enabled, String shopId,
                                    int tabIndex, int entryIndex) {
        CHANNEL.sendToServer(new SetSettingsPacket(pos, intervalTicks, actionBar, chat,
                enabled, shopId, tabIndex, entryIndex));
    }

    public static void openShopForSelection(BlockPos pos, String shopId) {
        CHANNEL.sendToServer(new OpenShopSelectionPacket(pos, shopId));
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }

    private static boolean validEntry(ShopEntry entry) {
        if (entry == null || !Double.isFinite(entry.price) || entry.price < 0) return false;
        boolean usesCurrency = entry.type == ShopEntryType.BUY
                || entry.type == ShopEntryType.SELL
                || entry.type == ShopEntryType.BARTER
                || (entry.type == ShopEntryType.COMMAND && entry.item.isEmpty());
        if (usesCurrency && entry.price > 0
                && (entry.currencyId == null || entry.currencyId.isBlank())) return false;
        return switch (entry.type) {
            case BUY, SELL -> !entry.item.isEmpty() && entry.item.getCount() > 0;
            case BARTER -> validStacks(entry.give) && validStacks(entry.receive);
            case COMMAND -> !entry.commands.isEmpty() || !entry.item.isEmpty() || entry.price > 0;
        };
    }

    private static boolean validStacks(List<ItemStack> stacks) {
        return stacks != null && !stacks.isEmpty()
                && stacks.stream().allMatch(stack -> stack != null && !stack.isEmpty()
                && stack.getCount() > 0);
    }

    private static ItemStack displayItem(ShopEntry entry) {
        if (!entry.displayItem.isEmpty()) return entry.displayItem.copy();
        if (!entry.item.isEmpty()) return entry.item.copy();
        if (!entry.receive.isEmpty()) return entry.receive.get(0).copy();
        if (!entry.give.isEmpty()) return entry.give.get(0).copy();
        return Items.COMMAND_BLOCK.getDefaultInstance();
    }

    private static int itemsPerUnit(ShopEntry entry) {
        return switch (entry.type) {
            case BARTER -> entry.receive.stream().mapToInt(ItemStack::getCount).sum();
            case COMMAND -> entry.item.isEmpty() ? 1 : entry.item.getCount();
            default -> entry.item.getCount();
        };
    }

    private static boolean available(ServerPlayer player, Shop shop, int tabIndex,
                                     int entryIndex, ShopEntry entry) {
        if (!RequirementCheck.satisfied(player, entry)) return false;
        boolean usesCurrency = entry.type == ShopEntryType.BUY
                || entry.type == ShopEntryType.SELL
                || entry.type == ShopEntryType.BARTER
                || (entry.type == ShopEntryType.COMMAND && entry.item.isEmpty());
        if (usesCurrency && entry.price > 0
                && (entry.currencyId == null || entry.currencyId.isBlank())) {
            return false;
        }
        int itemsPerUnit = itemsPerUnit(entry);
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
                                  boolean chat, boolean enabled, UUID owner, String ownerName,
                                  String shopId,
                                  int tabIndex, int entryIndex) {
        public static void encode(SyncStatePacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos); b.writeVarInt(p.intervalTicks);
            b.writeBoolean(p.actionBar); b.writeBoolean(p.chat); b.writeBoolean(p.enabled);
            b.writeBoolean(p.owner != null);
            if (p.owner != null) b.writeUUID(p.owner);
            b.writeUtf(p.ownerName == null ? "" : p.ownerName, 64);
            b.writeUtf(p.shopId == null ? "" : p.shopId, 128);
            b.writeVarInt(p.tabIndex); b.writeVarInt(p.entryIndex);
        }
        public static SyncStatePacket decode(FriendlyByteBuf b) {
            BlockPos pos = b.readBlockPos();
            int intervalTicks = b.readVarInt();
            boolean actionBar = b.readBoolean();
            boolean chat = b.readBoolean();
            boolean enabled = b.readBoolean();
            UUID owner = b.readBoolean() ? b.readUUID() : null;
            String ownerName = b.readUtf(64);
            return new SyncStatePacket(pos, intervalTicks, actionBar, chat, enabled,
                    owner, ownerName, b.readUtf(128), b.readVarInt(), b.readVarInt());
        }
        public static void handle(SyncStatePacket p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> enqueueClient(() -> RequesterClient.applyState(p)));
            context.setPacketHandled(true);
        }
    }

    public static void sendClaimOwner(BlockPos pos) {
        CHANNEL.sendToServer(new ClaimOwnerPacket(pos));
    }

    public record ClaimOwnerPacket(BlockPos pos) {
        public static void encode(ClaimOwnerPacket packet, FriendlyByteBuf buf) {
            buf.writeBlockPos(packet.pos);
        }

        public static ClaimOwnerPacket decode(FriendlyByteBuf buf) {
            return new ClaimOwnerPacket(buf.readBlockPos());
        }

        public static void handle(ClaimOwnerPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                context.enqueueWork(() -> {
                    if (!(sender.serverLevel().getBlockEntity(packet.pos) instanceof RequesterBlockEntity box)
                            || !box.stillValid(sender)) return;
                    box.setOwner(sender.getUUID(), sender.getGameProfile().getName());
                    broadcastState(sender.server, box);
                });
            }
            context.setPacketHandled(true);
        }
    }

    public record SyncShopsPacket(List<ShopInfo> shops, List<TargetInfo> targets) {
        public static void encode(SyncShopsPacket p, FriendlyByteBuf b) {
            b.writeVarInt(p.shops.size());
            for (ShopInfo shop : p.shops) shop.encode(b);
            b.writeVarInt(p.targets.size());
            for (TargetInfo target : p.targets) target.encode(b);
        }
        public static SyncShopsPacket decode(FriendlyByteBuf b) {
            int shopCount = Math.min(b.readVarInt(), 10000);
            List<ShopInfo> shops = new ArrayList<>();
            for (int i = 0; i < shopCount; i++) shops.add(ShopInfo.decode(b));
            int count = Math.min(b.readVarInt(), 10000);
            List<TargetInfo> targets = new ArrayList<>();
            for (int i = 0; i < count; i++) targets.add(TargetInfo.decode(b));
            return new SyncShopsPacket(shops, targets);
        }
        public static void handle(SyncShopsPacket p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> enqueueClient(() -> RequesterClient.applyShops(p.shops, p.targets)));
            context.setPacketHandled(true);
        }
    }

    public static final class ShopInfo {
        public final String shopId;
        public final String shopName;
        public final ItemStack icon;

        public ShopInfo(String shopId, String shopName, ItemStack icon) {
            this.shopId = shopId == null ? "" : shopId;
            this.shopName = shopName == null ? "" : shopName;
            this.icon = icon == null ? ItemStack.EMPTY : icon.copy();
        }

        private void encode(FriendlyByteBuf b) {
            b.writeUtf(shopId, 128);
            b.writeUtf(shopName, 128);
            b.writeItem(icon);
        }

        private static ShopInfo decode(FriendlyByteBuf b) {
            return new ShopInfo(b.readUtf(128), b.readUtf(128), b.readItem());
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
                    if (!p.shopId.isBlank()) {
                        Shop shop = ShopManager.get(p.shopId);
                        if (shop == null || !selectable(sender, shop, p.tabIndex, p.entryIndex)) {
                            sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                                    "qshop_requester.message.target_unavailable"));
                            return;
                        }
                    }
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

    private static boolean selectable(ServerPlayer player, Shop shop, int tabIndex, int entryIndex) {
        shop.ensureTabs();
        if (tabIndex < 0 || tabIndex >= shop.tabs.size()) return false;
        ShopTab tab = shop.tabs.get(tabIndex);
        if (!RequirementCheck.satisfied(player, tab) || entryIndex < 0
                || entryIndex >= tab.entries.size()) return false;
        ShopEntry entry = tab.entries.get(entryIndex);
        return validEntry(entry) && available(player, shop, tabIndex, entryIndex, entry);
    }

    public record OpenShopSelectionPacket(BlockPos pos, String shopId) {
        public static void encode(OpenShopSelectionPacket p, FriendlyByteBuf b) {
            b.writeBlockPos(p.pos);
            b.writeUtf(p.shopId == null ? "" : p.shopId, 128);
        }

        public static OpenShopSelectionPacket decode(FriendlyByteBuf b) {
            return new OpenShopSelectionPacket(b.readBlockPos(), b.readUtf(128));
        }

        public static void handle(OpenShopSelectionPacket p, Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            ServerPlayer sender = context.getSender();
            if (sender != null) context.enqueueWork(() -> {
                if (!(sender.serverLevel().getBlockEntity(p.pos) instanceof RequesterBlockEntity box)
                        || !box.stillValid(sender) || !box.canEdit(sender)) {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "qshop_requester.message.not_owner"));
                    return;
                }
                Shop shop = ShopManager.get(p.shopId);
                if (shop == null) {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "qshop_requester.message.shop_missing", p.shopId));
                    return;
                }
                ShopManager.openShop(sender, shop);
            });
            context.setPacketHandled(true);
        }
    }
}
