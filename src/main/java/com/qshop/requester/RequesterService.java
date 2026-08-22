package com.qshop.requester;

import com.qshop.api.CurrencyService;
import com.qshop.api.QShopAddonApi;
import com.qshop.api.TradeResult;
import com.qshop.data.QShopSavedData;
import com.qshop.shop.Shop;
import com.qshop.shop.ShopEntry;
import com.qshop.shop.ShopEntryType;
import com.qshop.shop.ShopManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RequesterService {
    public static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath(
            RequesterMod.MODID, "auto_request");
    private static final int MAX_UNITS_PER_CYCLE = 64;
    private static final String FORGE_CAPS_KEY = "ForgeCaps";
    private static final String WALLET_CAPABILITY_KEY = "qshop:wallet";
    private static final Object OFFLINE_LIMIT_LOCK = new Object();

    private RequesterService() {}

    public static void request(RequesterBlockEntity box) {
        if (box.owner() == null || box.getLevel() == null || box.getLevel().getServer() == null) return;
        MinecraftServer server = box.getLevel().getServer();
        ServerPlayer owner = server.getPlayerList().getPlayer(box.owner());
        if (owner != null) {
            requestOnline(box, owner);
        } else {
            tradeOffline(box, server, box.owner());
        }
    }

    private static void requestOnline(RequesterBlockEntity box, ServerPlayer owner) {
        if (box.shopId().isBlank()) {
            notifyFailure(box, owner, "qshop_requester.message.no_target");
            return;
        }
        TradeResult result = box.selectedType() == ShopEntryType.BARTER
                ? QShopAddonApi.barter(owner, box.supplied(), box.purchased(), box.shopId(),
                box.tabIndex(), box.entryIndex(), MAX_UNITS_PER_CYCLE, SOURCE, box.getBlockPos())
                : QShopAddonApi.buy(owner, box.purchased(), box.shopId(), box.tabIndex(),
                box.entryIndex(), MAX_UNITS_PER_CYCLE, SOURCE, box.getBlockPos());
        if (result.isSuccess()) notifySuccess(box, owner, result.getTotalItems());
        else notifyFailure(box, owner, result.getStatus().name());
    }

    private static TradeResult tradeOffline(RequesterBlockEntity box, MinecraftServer server, UUID owner) {
        if (box.shopId().isBlank()) return failure(TradeResult.Status.INVALID_ARGUMENT);
        Shop shop = ShopManager.get(box.shopId());
        if (shop == null) return failure(TradeResult.Status.SHOP_NOT_FOUND);
        shop.ensureTabs();
        if (box.tabIndex() < 0 || box.tabIndex() >= shop.tabs.size()) {
            return failure(TradeResult.Status.TAB_NOT_FOUND);
        }
        var entries = shop.tabs.get(box.tabIndex()).entries;
        if (box.entryIndex() < 0 || box.entryIndex() >= entries.size()) {
            return failure(TradeResult.Status.ENTRY_NOT_FOUND);
        }
        ShopEntry entry = entries.get(box.entryIndex());
        if (!validEntry(entry)) return failure(TradeResult.Status.UNSUPPORTED_ENTRY);

        String key = limitKey(shop, box.tabIndex(), box.entryIndex(), entry);
        String period = entry.reset.periodKey();
        int units = availableUnits(server, owner, entry, key, period);
        if (units <= 0) return failure(TradeResult.Status.LIMIT_REACHED);
        return entry.type == ShopEntryType.BARTER
                ? tradeOfflineBarter(box, server, owner, entry, key, period, units)
                : tradeOfflineBuy(box, server, owner, entry, key, period, units);
    }

    private static int availableUnits(MinecraftServer server, UUID owner, ShopEntry entry,
                                      String key, String period) {
        int units = MAX_UNITS_PER_CYCLE;
        if (entry.globalLimit > 0) {
            int used = QShopSavedData.get(server).globalCounts.getCount(key, period);
            units = Math.min(units, Math.max(0, entry.globalLimit - used));
        }
        if (entry.playerLimit > 0) {
            int used = CurrencyService.INSTANCE.getLimitCount(server, owner, key, period);
            units = Math.min(units, Math.max(0, entry.playerLimit - used));
        }
        return units;
    }

    private static TradeResult tradeOfflineBuy(RequesterBlockEntity box, MinecraftServer server,
                                               UUID owner, ShopEntry entry, String key,
                                               String period, int units) {
        List<ItemStack> purchasedSnapshot = snapshot(box.purchased());
        int itemsPerUnit = entry.item.getCount();
        long byBalance = entry.price > 0
                ? (long) (CurrencyService.INSTANCE.getBalance(server, owner, entry.currencyId) / entry.price)
                : Integer.MAX_VALUE;
        units = (int) Math.min(units, Math.min(byBalance, Integer.MAX_VALUE));
        units = Math.min(units, capacityUnits(box.purchased(), entry.item, units));
        if (units <= 0) {
            return failure(entry.price > 0
                    ? TradeResult.Status.NOT_ENOUGH_CURRENCY : TradeResult.Status.NO_SPACE);
        }

        int totalItems = itemsPerUnit * units;
        double oldBalance = entry.price > 0
                ? CurrencyService.INSTANCE.getBalance(server, owner, entry.currencyId) : 0D;
        double totalPrice = entry.price * units;
        if (totalPrice > 0 && !CurrencyService.INSTANCE.withdraw(server, owner, entry.currencyId,
                totalPrice, SOURCE, box.getBlockPos(), false)) {
            return failure(TradeResult.Status.NOT_ENOUGH_CURRENCY);
        }
        ItemStack output = entry.item.copy();
        output.setCount(totalItems);
        int inserted = insertItems(box.purchased(), output);
        if (inserted != totalItems) {
            restore(box.purchased(), purchasedSnapshot);
            refund(server, owner, entry, oldBalance, box.getBlockPos());
            return failure(TradeResult.Status.FAILED);
        }
        return finishOfflineTrade(box, server, owner, entry, key, period, units, totalItems,
                totalPrice, oldBalance, purchasedSnapshot, null);
    }

    private static TradeResult tradeOfflineBarter(RequesterBlockEntity box, MinecraftServer server,
                                                  UUID owner, ShopEntry entry, String key,
                                                  String period, int units) {
        List<ItemStack> purchasedSnapshot = snapshot(box.purchased());
        List<ItemStack> suppliedSnapshot = snapshot(box.supplied());
        for (ItemStack give : entry.give) {
            units = Math.min(units, countItems(box.supplied(), give) / give.getCount());
        }
        for (ItemStack receive : entry.receive) {
            units = Math.min(units, capacityUnits(box.purchased(), receive, units));
        }
        long byBalance = entry.price > 0
                ? (long) (CurrencyService.INSTANCE.getBalance(server, owner, entry.currencyId) / entry.price)
                : Integer.MAX_VALUE;
        units = (int) Math.min(units, Math.min(byBalance, Integer.MAX_VALUE));
        if (units <= 0) {
            return failure(entry.price > 0
                    ? TradeResult.Status.NOT_ENOUGH_CURRENCY : TradeResult.Status.NOT_ENOUGH_ITEMS);
        }

        double totalPrice = entry.price * units;
        double oldBalance = entry.price > 0
                ? CurrencyService.INSTANCE.getBalance(server, owner, entry.currencyId) : 0D;
        if (totalPrice > 0 && !CurrencyService.INSTANCE.withdraw(server, owner, entry.currencyId,
                totalPrice, SOURCE, box.getBlockPos(), false)) {
            return failure(TradeResult.Status.NOT_ENOUGH_CURRENCY);
        }

        for (ItemStack give : entry.give) {
            int amount = give.getCount() * units;
            if (!extractItems(box.supplied(), give, amount)) {
                restore(box.purchased(), purchasedSnapshot);
                restore(box.supplied(), suppliedSnapshot);
                refund(server, owner, entry, oldBalance, box.getBlockPos());
                return failure(TradeResult.Status.FAILED);
            }
        }

        for (ItemStack receive : entry.receive) {
            int amount = receive.getCount() * units;
            ItemStack result = receive.copy();
            result.setCount(amount);
            int count = insertItems(box.purchased(), result);
            if (count != amount) {
                restore(box.purchased(), purchasedSnapshot);
                restore(box.supplied(), suppliedSnapshot);
                refund(server, owner, entry, oldBalance, box.getBlockPos());
                return failure(TradeResult.Status.FAILED);
            }
        }

        int tradedUnits = units;
        int totalItems = entry.receive.stream().mapToInt(s -> s.getCount() * tradedUnits).sum();
        return finishOfflineTrade(box, server, owner, entry, key, period, units, totalItems,
                totalPrice, oldBalance, purchasedSnapshot, suppliedSnapshot);
    }

    private static TradeResult finishOfflineTrade(RequesterBlockEntity box, MinecraftServer server,
                                                  UUID owner, ShopEntry entry, String key,
                                                  String period, int units, int totalItems,
                                                  double totalPrice, double oldBalance,
                                                  List<ItemStack> purchasedSnapshot,
                                                  List<ItemStack> suppliedSnapshot) {
        if (entry.playerLimit > 0 && !addOfflineLimit(server, owner, key, period, units)) {
            restore(box.purchased(), purchasedSnapshot);
            if (suppliedSnapshot != null) restore(box.supplied(), suppliedSnapshot);
            refund(server, owner, entry, oldBalance, box.getBlockPos());
            return failure(TradeResult.Status.FAILED);
        }
        if (entry.globalLimit > 0) {
            QShopSavedData data = QShopSavedData.get(server);
            data.globalCounts.addCount(key, units, period);
            data.setDirty();
        }
        return TradeResult.success(units, units, totalItems, totalPrice);
    }

    private static boolean validEntry(ShopEntry entry) {
        if ((entry.type != ShopEntryType.BUY && entry.type != ShopEntryType.BARTER)
                || !entry.commands.isEmpty() || !Double.isFinite(entry.price) || entry.price < 0
                || (entry.price > 0 && (entry.currencyId == null || entry.currencyId.isBlank()))) {
            return false;
        }
        if (entry.type == ShopEntryType.BUY) return !entry.item.isEmpty() && entry.item.getCount() > 0;
        return !entry.give.isEmpty() && !entry.receive.isEmpty()
                && entry.give.stream().allMatch(s -> !s.isEmpty() && s.getCount() > 0)
                && entry.receive.stream().allMatch(s -> !s.isEmpty() && s.getCount() > 0);
    }

    private static String limitKey(Shop shop, int tabIndex, int entryIndex, ShopEntry entry) {
        return entry.uuid != null && !entry.uuid.isEmpty()
                ? shop.id + "|" + entry.uuid : shop.id + "|" + tabIndex + "|" + entryIndex;
    }

    private static int capacityUnits(IItemHandler handler, ItemStack prototype, int maxUnits) {
        int low = 0;
        int high = maxUnits;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            ItemStack stack = prototype.copy();
            stack.setCount(prototype.getCount() * middle);
            if (capacity(handler, stack) >= stack.getCount()) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    private static int capacity(IItemHandler handler, ItemStack prototype) {
        int remaining = prototype.getCount();
        int accepted = 0;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack probe = prototype.copy();
            probe.setCount(remaining);
            ItemStack rest = handler.insertItem(slot, probe, true);
            int count = remaining - rest.getCount();
            accepted += count;
            remaining -= count;
        }
        return accepted;
    }

    private static int countItems(IItemHandler handler, ItemStack target) {
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (ItemStack.isSameItemSameTags(stack, target)) {
                count = Math.min(Integer.MAX_VALUE, count + stack.getCount());
            }
        }
        return count;
    }

    private static int insertItems(IItemHandler handler, ItemStack stack) {
        int remaining = stack.getCount();
        int inserted = 0;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack part = stack.copy();
            part.setCount(remaining);
            ItemStack rest = handler.insertItem(slot, part, false);
            int count = remaining - rest.getCount();
            inserted += count;
            remaining -= count;
        }
        return inserted;
    }

    private static List<ItemStack> snapshot(ItemStackHandler handler) {
        List<ItemStack> snapshot = new ArrayList<>(handler.getSlots());
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            snapshot.add(handler.getStackInSlot(slot).copy());
        }
        return snapshot;
    }

    private static void restore(ItemStackHandler handler, List<ItemStack> snapshot) {
        if (snapshot == null) return;
        for (int slot = 0; slot < handler.getSlots() && slot < snapshot.size(); slot++) {
            handler.setStackInSlot(slot, snapshot.get(slot).copy());
        }
    }

    private static boolean extractItems(IItemHandler handler, ItemStack target, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!ItemStack.isSameItemSameTags(stack, target)) continue;
            ItemStack extracted = handler.extractItem(slot, Math.min(remaining, stack.getCount()), false);
            remaining -= extracted.getCount();
        }
        return remaining == 0;
    }

    private static void refund(MinecraftServer server, UUID owner, ShopEntry entry,
                               double balance, BlockPos pos) {
        if (entry.price > 0) {
            CurrencyService.INSTANCE.set(server, owner, entry.currencyId, balance, SOURCE, pos, false);
        }
    }

    private static boolean addOfflineLimit(MinecraftServer server, UUID owner, String key,
                                           String period, int amount) {
        synchronized (OFFLINE_LIMIT_LOCK) {
            if (server.getPlayerList().getPlayer(owner) != null) return false;
            Path target = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
            Path temp = target.resolveSibling(target.getFileName() + ".qshop-requester.tmp");
            try {
                CompoundTag playerData = NbtIo.readCompressed(target.toFile());
                CompoundTag forgeCaps = playerData.getCompound(FORGE_CAPS_KEY);
                CompoundTag wallet = forgeCaps.getCompound(WALLET_CAPABILITY_KEY);
                CompoundTag limits = wallet.getCompound("limits");
                CompoundTag value = limits.getCompound(key);
                int count = period.equals(value.getString("period")) ? value.getInt("count") : 0;
                value.putString("period", period);
                value.putInt("count", count + amount);
                limits.put(key, value);
                wallet.put("limits", limits);
                forgeCaps.put(WALLET_CAPABILITY_KEY, wallet);
                playerData.put(FORGE_CAPS_KEY, forgeCaps);
                NbtIo.writeCompressed(playerData, temp.toFile());
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (IOException | RuntimeException ignored) {
                try { Files.deleteIfExists(temp); } catch (IOException ignoredCleanup) { }
                return false;
            }
        }
    }

    private static TradeResult failure(TradeResult.Status status) {
        return TradeResult.failure(status, MAX_UNITS_PER_CYCLE, status.name());
    }

    private static void notifySuccess(RequesterBlockEntity box, ServerPlayer owner, int totalItems) {
        if (box.showActionBarNotification()) owner.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "qshop_requester.message.trade_success", totalItems), true);
        if (box.showChatNotification()) owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "qshop_requester.message.trade_success", totalItems));
    }

    private static void notifyFailure(RequesterBlockEntity box, ServerPlayer owner, String reason) {
        net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.translatable(
                "qshop_requester.message.trade_failed", reason);
        if (box.showActionBarNotification()) owner.displayClientMessage(message, true);
        if (box.showChatNotification()) owner.sendSystemMessage(message);
    }
}
