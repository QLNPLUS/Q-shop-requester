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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RequesterNetwork {
    private static final String PROTOCOL = "3";

    private RequesterNetwork() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RequesterNetwork::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);
        registrar.playToClient(SyncStatePacket.TYPE, SyncStatePacket.STREAM_CODEC,
                SyncStatePacket::handle);
        registrar.playToClient(SyncShopsPacket.TYPE, SyncShopsPacket.STREAM_CODEC,
                SyncShopsPacket::handle);
        registrar.playToServer(ClaimOwnerPacket.TYPE, ClaimOwnerPacket.STREAM_CODEC,
                ClaimOwnerPacket::handle);
        registrar.playToServer(SetSettingsPacket.TYPE, SetSettingsPacket.STREAM_CODEC,
                SetSettingsPacket::handle);
        registrar.playToServer(OpenShopSelectionPacket.TYPE, OpenShopSelectionPacket.STREAM_CODEC,
                OpenShopSelectionPacket::handle);
    }

    public static void sendState(ServerPlayer player, RequesterBlockEntity box) {
        box.migrateLegacyTarget();
        PacketDistributor.sendToPlayer(player, new SyncStatePacket(
                box.getBlockPos(), box.intervalTicks(), box.showActionBarNotification(),
                box.showChatNotification(), box.enabled(), box.owner(), box.ownerName(),
                box.shopUuid(), box.tabUuid(), box.entryUuid()));
    }

    public static void sendClaimOwner(BlockPos pos) {
        PacketDistributor.sendToServer(new ClaimOwnerPacket(pos));
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
                    String shopUuid = shop.uuid == null ? "" : shop.uuid.toString();
                    if (shops.stream().noneMatch(value -> value.shopUuid.equals(shopUuid))) {
                        shops.add(new ShopInfo(shopUuid, shop.id, shop.displayNameOrId(), shop.icon.copy()));
                    }
                    ItemStack display = displayItem(entry);
                    String label = entry.displayNameOrItem();
                    List<ItemStack> supplied = entry.type == ShopEntryType.SELL
                            || (entry.type == ShopEntryType.COMMAND && !entry.item.isEmpty())
                            ? List.of(entry.item) : entry.give;
                    targets.add(new TargetInfo(shopUuid, shop.id, shop.displayNameOrId(), tab.uuid,
                            tab.name, entry.uuid, entry.type, label, display.copy(), copy(supplied), copy(entry.receive),
                            entry.price, CurrencyRegistry.displayName(entry.currencyId)));
                }
            }
        }
        PacketDistributor.sendToPlayer(player, new SyncShopsPacket(shops, targets));
    }

    public static void sendSettings(BlockPos pos, int intervalTicks, boolean actionBar,
                                    boolean chat, boolean enabled, String shopUuid,
                                    String tabUuid, String entryUuid) {
        PacketDistributor.sendToServer(new SetSettingsPacket(pos, intervalTicks, actionBar, chat,
                enabled, shopUuid, tabUuid, entryUuid));
    }

    public static void openShopForSelection(BlockPos pos, String shopUuid) {
        PacketDistributor.sendToServer(new OpenShopSelectionPacket(pos, shopUuid));
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

    public record SyncStatePacket(BlockPos pos, int intervalTicks, boolean actionBar,
                                  boolean chat, boolean enabled, UUID owner, String ownerName,
                                  String shopUuid,
                                  String tabUuid, String entryUuid)  implements CustomPacketPayload{
        public static final CustomPacketPayload.Type<SyncStatePacket> TYPE = new CustomPacketPayload.Type<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RequesterMod.MODID, "sync_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncStatePacket> STREAM_CODEC =
                CustomPacketPayload.codec(SyncStatePacket::encode, SyncStatePacket::decode);

        public static void encode(SyncStatePacket p, RegistryFriendlyByteBuf b) {
            b.writeBlockPos(p.pos); b.writeVarInt(p.intervalTicks);
            b.writeBoolean(p.actionBar); b.writeBoolean(p.chat); b.writeBoolean(p.enabled);
            b.writeBoolean(p.owner != null);
            if (p.owner != null) b.writeUUID(p.owner);
            b.writeUtf(p.ownerName == null ? "" : p.ownerName, 64);
            b.writeUtf(p.shopUuid == null ? "" : p.shopUuid, 128);
            b.writeUtf(p.tabUuid == null ? "" : p.tabUuid, 128);
            b.writeUtf(p.entryUuid == null ? "" : p.entryUuid, 128);
        }
        public static SyncStatePacket decode(RegistryFriendlyByteBuf b) {
            BlockPos pos = b.readBlockPos();
            int intervalTicks = b.readVarInt();
            boolean actionBar = b.readBoolean();
            boolean chat = b.readBoolean();
            boolean enabled = b.readBoolean();
            UUID owner = b.readBoolean() ? b.readUUID() : null;
            String ownerName = b.readUtf(64);
            return new SyncStatePacket(pos, intervalTicks, actionBar, chat, enabled, owner, ownerName,
                    b.readUtf(128), b.readUtf(128), b.readUtf(128));
        }
        public static void handle(SyncStatePacket p, IPayloadContext context) {
                        context.enqueueWork(() -> RequesterClient.applyState(p));
        }


        @Override
        public CustomPacketPayload.Type<SyncStatePacket> type() {
            return TYPE;
        }
}

    public record SyncShopsPacket(List<ShopInfo> shops, List<TargetInfo> targets)  implements CustomPacketPayload{
        public static final CustomPacketPayload.Type<SyncShopsPacket> TYPE = new CustomPacketPayload.Type<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RequesterMod.MODID, "sync_shops"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncShopsPacket> STREAM_CODEC =
                CustomPacketPayload.codec(SyncShopsPacket::encode, SyncShopsPacket::decode);

        public static void encode(SyncShopsPacket p, RegistryFriendlyByteBuf b) {
            b.writeVarInt(p.shops.size());
            for (ShopInfo shop : p.shops) shop.encode(b);
            b.writeVarInt(p.targets.size());
            for (TargetInfo target : p.targets) target.encode(b);
        }
        public static SyncShopsPacket decode(RegistryFriendlyByteBuf b) {
            int shopCount = Math.min(b.readVarInt(), 10000);
            List<ShopInfo> shops = new ArrayList<>();
            for (int i = 0; i < shopCount; i++) shops.add(ShopInfo.decode(b));
            int count = Math.min(b.readVarInt(), 10000);
            List<TargetInfo> targets = new ArrayList<>();
            for (int i = 0; i < count; i++) targets.add(TargetInfo.decode(b));
            return new SyncShopsPacket(shops, targets);
        }
        public static void handle(SyncShopsPacket p, IPayloadContext context) {
                        context.enqueueWork(() -> RequesterClient.applyShops(p.shops, p.targets));
        }

        @Override
        public CustomPacketPayload.Type<SyncShopsPacket> type() {
            return TYPE;
        }
}

    public static final class ShopInfo {
        public final String shopUuid;
        public final String shopId;
        public final String shopName;
        public final ItemStack icon;

        public ShopInfo(String shopUuid, String shopId, String shopName, ItemStack icon) {
            this.shopUuid = shopUuid == null ? "" : shopUuid;
            this.shopId = shopId == null ? "" : shopId;
            this.shopName = shopName == null ? "" : shopName;
            this.icon = icon == null ? ItemStack.EMPTY : icon.copy();
        }

        private void encode(RegistryFriendlyByteBuf b) {
            b.writeUtf(shopUuid, 128);
            b.writeUtf(shopId, 128);
            b.writeUtf(shopName, 128);
            net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(b, icon);
        }

        private static ShopInfo decode(RegistryFriendlyByteBuf b) {
            return new ShopInfo(b.readUtf(128), b.readUtf(128), b.readUtf(128), net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
        }
    }

    public static final class TargetInfo {
        public final String shopUuid;
        public final String shopId;
        public final String shopName;
        public final String tabUuid;
        public final String tabName;
        public final String entryUuid;
        public final ShopEntryType type;
        public final String label;
        public final ItemStack display;
        public final List<ItemStack> give;
        public final List<ItemStack> receive;
        public final double price;
        public final String currency;

        public TargetInfo(String shopUuid, String shopId, String shopName, String tabUuid,
                          String tabName, String entryUuid, ShopEntryType type, String label, ItemStack display,
                          List<ItemStack> give, List<ItemStack> receive, double price, String currency) {
            this.shopUuid = shopUuid; this.shopId = shopId; this.shopName = shopName;
            this.tabUuid = tabUuid; this.tabName = tabName; this.entryUuid = entryUuid;
            this.type = type;
            this.label = label; this.display = display; this.give = give; this.receive = receive;
            this.price = price; this.currency = currency == null ? "" : currency;
        }

        private void encode(RegistryFriendlyByteBuf b) {
            b.writeUtf(shopUuid, 128); b.writeUtf(shopId, 128); b.writeUtf(shopName, 128);
            b.writeUtf(tabUuid == null ? "" : tabUuid, 128);
            b.writeUtf(tabName == null ? "" : tabName, 128);
            b.writeUtf(entryUuid == null ? "" : entryUuid, 128);
            b.writeEnum(type); b.writeUtf(label == null ? "" : label, 128); net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(b, display);
            writeStacks(b, give); writeStacks(b, receive); b.writeDouble(price);
            b.writeUtf(currency, 64);
        }

        private static TargetInfo decode(RegistryFriendlyByteBuf b) {
            String shopUuid = b.readUtf(128); String shopId = b.readUtf(128);
            String shopName = b.readUtf(128); String tabUuid = b.readUtf(128);
            String tabName = b.readUtf(128); String entryUuid = b.readUtf(128);
            ShopEntryType type = b.readEnum(ShopEntryType.class);
            String label = b.readUtf(128); ItemStack display = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(b);
            List<ItemStack> give = readStacks(b); List<ItemStack> receive = readStacks(b);
            return new TargetInfo(shopUuid, shopId, shopName, tabUuid, tabName, entryUuid, type, label,
                    display, give, receive, b.readDouble(), b.readUtf(64));
        }
    }

    private static void writeStacks(RegistryFriendlyByteBuf b, List<ItemStack> stacks) {
        b.writeVarInt(stacks.size()); for (ItemStack stack : stacks) net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(b, stack);
    }
    private static List<ItemStack> readStacks(RegistryFriendlyByteBuf b) {
        int count = Math.min(b.readVarInt(), 64); List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < count; i++) stacks.add(net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(b)); return stacks;

    }

    public record SetSettingsPacket(BlockPos pos, int intervalTicks, boolean actionBar,
                                    boolean chat, boolean enabled, String shopUuid,
                                    String tabUuid, String entryUuid)  implements CustomPacketPayload{
        public static final CustomPacketPayload.Type<SetSettingsPacket> TYPE = new CustomPacketPayload.Type<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RequesterMod.MODID, "set_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSettingsPacket> STREAM_CODEC =
                CustomPacketPayload.codec(SetSettingsPacket::encode, SetSettingsPacket::decode);

        public static void encode(SetSettingsPacket p, RegistryFriendlyByteBuf b) {
            b.writeBlockPos(p.pos); b.writeVarInt(p.intervalTicks); b.writeBoolean(p.actionBar);
            b.writeBoolean(p.chat); b.writeBoolean(p.enabled); b.writeUtf(p.shopUuid, 128);
            b.writeUtf(p.tabUuid, 128); b.writeUtf(p.entryUuid, 128);
        }
        public static SetSettingsPacket decode(RegistryFriendlyByteBuf b) {
            return new SetSettingsPacket(b.readBlockPos(), b.readVarInt(), b.readBoolean(),
                    b.readBoolean(), b.readBoolean(), b.readUtf(128), b.readUtf(128), b.readUtf(128));
        }
        public static void handle(SetSettingsPacket p, IPayloadContext context) {
            ServerPlayer sender = (ServerPlayer) context.player();
            if (sender != null) context.enqueueWork(() -> {
                if (sender.serverLevel().getBlockEntity(p.pos) instanceof RequesterBlockEntity box
                        && box.stillValid(sender) && box.canEdit(sender)) {
                    boolean targetEmpty = p.shopUuid.isBlank() && p.tabUuid.isBlank() && p.entryUuid.isBlank();
                    if (!targetEmpty && !selectable(sender, p.shopUuid, p.tabUuid, p.entryUuid)) {
                        sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                                "qshop_requester.message.target_unavailable"));
                        return;
                    }
                    box.setSettings(p.intervalTicks, p.actionBar, p.chat, p.enabled,
                            p.shopUuid, p.tabUuid, p.entryUuid);
                    sendState(sender, box);
                } else {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "qshop_requester.message.not_owner"));
                }
            });
        }


        @Override
        public CustomPacketPayload.Type<SetSettingsPacket> type() {
            return TYPE;
        }
}

    public record ClaimOwnerPacket(BlockPos pos)  implements CustomPacketPayload{
        public static final CustomPacketPayload.Type<ClaimOwnerPacket> TYPE = new CustomPacketPayload.Type<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RequesterMod.MODID, "claim_owner"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClaimOwnerPacket> STREAM_CODEC =
                CustomPacketPayload.codec(ClaimOwnerPacket::encode, ClaimOwnerPacket::decode);

        public static void encode(ClaimOwnerPacket packet, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(packet.pos);
        }

        public static ClaimOwnerPacket decode(RegistryFriendlyByteBuf buf) {
            return new ClaimOwnerPacket(buf.readBlockPos());
        }

        public static void handle(ClaimOwnerPacket packet, IPayloadContext context) {
                        ServerPlayer sender = (ServerPlayer) context.player();
            if (sender != null) {
                context.enqueueWork(() -> {
                    if (!(sender.serverLevel().getBlockEntity(packet.pos) instanceof RequesterBlockEntity box)
                            || !box.stillValid(sender)) return;
                    box.setOwner(sender.getUUID(), sender.getGameProfile().getName());
                    broadcastState(sender.server, box);
                });
            }
        }

        @Override
        public CustomPacketPayload.Type<ClaimOwnerPacket> type() {
            return TYPE;
        }
}

    private static boolean selectable(ServerPlayer player, String shopUuid,
                                     String tabUuid, String entryUuid) {
        RequesterTarget target = RequesterTarget.resolve(shopUuid, tabUuid, entryUuid);
        return target != null && RequirementCheck.satisfied(player, target.tab())
                && validEntry(target.entry())
                && available(player, target.shop(), target.tabIndex(), target.entryIndex(), target.entry());

    }

    public record OpenShopSelectionPacket(BlockPos pos, String shopUuid)  implements CustomPacketPayload{
        public static final CustomPacketPayload.Type<OpenShopSelectionPacket> TYPE = new CustomPacketPayload.Type<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RequesterMod.MODID, "open_shop_selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenShopSelectionPacket> STREAM_CODEC =
                CustomPacketPayload.codec(OpenShopSelectionPacket::encode, OpenShopSelectionPacket::decode);

        public static void encode(OpenShopSelectionPacket p, RegistryFriendlyByteBuf b) {
            b.writeBlockPos(p.pos);
            b.writeUtf(p.shopUuid == null ? "" : p.shopUuid, 128);
        }

        public static OpenShopSelectionPacket decode(RegistryFriendlyByteBuf b) {
            return new OpenShopSelectionPacket(b.readBlockPos(), b.readUtf(128));
        }

        public static void handle(OpenShopSelectionPacket p, IPayloadContext context) {
                        ServerPlayer sender = (ServerPlayer) context.player();
            if (sender != null) context.enqueueWork(() -> {
                if (!(sender.serverLevel().getBlockEntity(p.pos) instanceof RequesterBlockEntity box)
                        || !box.stillValid(sender) || !box.canEdit(sender)) {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "qshop_requester.message.not_owner"));
                    return;
                }
                Shop shop = ShopManager.byUuid(p.shopUuid);
                if (shop == null) {
                    sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "qshop_requester.message.shop_missing", p.shopUuid));
                    return;
                }
                ShopManager.openShop(sender, shop);
            });
        }


        @Override
        public CustomPacketPayload.Type<OpenShopSelectionPacket> type() {
            return TYPE;
        }
}
}
