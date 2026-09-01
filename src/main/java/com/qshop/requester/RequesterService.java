package com.qshop.requester;

import com.qshop.api.CurrencyService;
import com.qshop.api.QShopAddonApi;
import com.qshop.api.TradeResult;
import com.qshop.currency.CurrencyRegistry;
import com.qshop.data.QShopSavedData;
import com.qshop.kubejs.QShopTradeEvents;
import com.qshop.shop.Shop;
import com.qshop.shop.ShopCommand;
import com.qshop.shop.ShopEntry;
import com.qshop.shop.ShopEntryType;
import com.qshop.trade.RequirementCheck;
import com.qshop.wallet.IWallet;
import com.qshop.wallet.WalletCapability;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

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
    private static final int UNITS_PER_CYCLE = 1;
    private static final String FORGE_CAPS_KEY = "ForgeCaps";
    private static final String NEOFORGE_ATTACHMENTS_KEY = "neoforge:attachments";
    private static final String WALLET_CAPABILITY_KEY = "qshop:wallet";
    private static final Object OFFLINE_LIMIT_LOCK = new Object();

    private RequesterService() {}

    public static void request(RequesterBlockEntity box) {
        box.migrateLegacyTarget();
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
        RequesterTarget target = box.resolveTarget();
        if (target == null) {
            notifyFailure(box, owner, Component.translatable("qshop_requester.message.no_target"));
            return;
        }
        MinecraftServer server = owner.getServer();
        Shop shop = target.shop();
        ShopEntry entry = target.entry();
        if (!validEntry(entry)) {
            notifyFailure(box, owner, failureReason(TradeResult.Status.UNSUPPORTED_ENTRY));
            return;
        }
        if (!RequirementCheck.satisfied(owner, entry)) {
            notifyFailure(box, owner, failureReason(TradeResult.Status.REQUIREMENTS_NOT_MET));
            return;
        }

        TradeResult result;
        if (entry.commands.isEmpty() && entry.type != ShopEntryType.COMMAND) {
            result = switch (entry.type) {
                case BUY -> QShopAddonApi.buy(owner, box.purchased(), shop.id, target.tabIndex(),
                        target.entryIndex(), UNITS_PER_CYCLE, SOURCE, box.getBlockPos());
                case SELL -> QShopAddonApi.sell(owner, box.supplied(), shop.id, target.tabIndex(),
                        target.entryIndex(), UNITS_PER_CYCLE, SOURCE, box.getBlockPos());
                case BARTER -> QShopAddonApi.barter(owner, box.supplied(), box.purchased(), shop.id,
                        target.tabIndex(), target.entryIndex(), UNITS_PER_CYCLE, SOURCE, box.getBlockPos());
                case COMMAND -> failure(TradeResult.Status.UNSUPPORTED_ENTRY);
            };
        } else {
            result = tradeWithHandlers(box, server, owner, owner.getUUID(), shop, entry,
                    target.tabIndex(), target.entryIndex());
        }
        if (result.isSuccess()) notifySuccess(box, owner, result.getTotalItems());
        else notifyFailure(box, owner, failureReason(result.getStatus()));
    }

    private static TradeResult tradeOffline(RequesterBlockEntity box, MinecraftServer server, UUID owner) {
        box.migrateLegacyTarget();
        RequesterTarget target = box.resolveTarget();
        if (target == null) return failure(TradeResult.Status.ENTRY_NOT_FOUND);
        Shop shop = target.shop();
        ShopEntry entry = target.entry();
        if (!validEntry(entry)) return failure(TradeResult.Status.UNSUPPORTED_ENTRY);
        return tradeWithHandlers(box, server, null, owner, shop, entry,
                target.tabIndex(), target.entryIndex());
    }

    private static TradeResult tradeWithHandlers(RequesterBlockEntity box, MinecraftServer server,
                                                  ServerPlayer onlineOwner, UUID owner, Shop shop,
                                                  ShopEntry entry, int tabIndex, int entryIndex) {
        String key = limitKey(shop, tabIndex, entryIndex, entry);
        String period = entry.reset.periodKey();
        int units = availableUnits(server, owner, entry, key, period);
        if (units <= 0) return failure(TradeResult.Status.LIMIT_REACHED);
        if (onlineOwner != null && !QShopTradeEvents.postBefore(onlineOwner, shop,
                tabIndex, entryIndex, entry, UNITS_PER_CYCLE)) {
            return failure(TradeResult.Status.CANCELLED);
        }

        List<ItemStack> purchasedSnapshot = snapshot(box.purchased());
        List<ItemStack> suppliedSnapshot = snapshot(box.supplied());
        int itemsPerUnit = itemsPerUnit(entry);
        double pricePerUnit = effectivePrice(entry);
        double oldBalance = pricePerUnit > 0
                ? balance(server, onlineOwner, owner, entry.currencyId) : 0D;

        switch (entry.type) {
            case BUY -> {
                units = limitByBalance(server, onlineOwner, owner, entry.currencyId,
                        pricePerUnit, units);
                units = Math.min(units, capacityUnits(box.purchased(), entry.item, units));
                if (units <= 0) return failure(pricePerUnit > 0
                        ? TradeResult.Status.NOT_ENOUGH_CURRENCY : TradeResult.Status.NO_SPACE);
                if (!withdraw(server, onlineOwner, owner, entry.currencyId,
                        pricePerUnit * units, box.getBlockPos())) {
                    return failure(TradeResult.Status.NOT_ENOUGH_CURRENCY);
                }
                ItemStack output = entry.item.copy();
                output.setCount(entry.item.getCount() * units);
                if (insertItems(box.purchased(), output) != output.getCount()) {
                    restore(box.purchased(), purchasedSnapshot);
                    restoreCurrency(server, onlineOwner, owner, entry, oldBalance);
                    return failure(TradeResult.Status.FAILED);
                }
            }
            case SELL -> {
                units = Math.min(units, countItems(box.supplied(), entry.item) / entry.item.getCount());
                if (units <= 0) return failure(TradeResult.Status.NOT_ENOUGH_ITEMS);
                if (!extractItems(box.supplied(), entry.item, entry.item.getCount() * units)) {
                    restore(box.supplied(), suppliedSnapshot);
                    return failure(TradeResult.Status.FAILED);
                }
                if (!deposit(server, onlineOwner, owner, entry.currencyId,
                        pricePerUnit * units, box.getBlockPos())) {
                    restore(box.supplied(), suppliedSnapshot);
                    return failure(TradeResult.Status.FAILED);
                }
            }
            case BARTER -> {
                for (ItemStack give : entry.give) {
                    units = Math.min(units, countItems(box.supplied(), give) / give.getCount());
                }
                for (ItemStack receive : entry.receive) {
                    units = Math.min(units, capacityUnits(box.purchased(), receive, units));
                }
                units = limitByBalance(server, onlineOwner, owner, entry.currencyId,
                        pricePerUnit, units);
                if (units <= 0) return failure(pricePerUnit > 0
                        ? TradeResult.Status.NOT_ENOUGH_CURRENCY : TradeResult.Status.NOT_ENOUGH_ITEMS);
                if (!withdraw(server, onlineOwner, owner, entry.currencyId,
                        pricePerUnit * units, box.getBlockPos())) {
                    return failure(TradeResult.Status.NOT_ENOUGH_CURRENCY);
                }
                if (!exchangeItems(box, entry, units)) {
                    restore(box.purchased(), purchasedSnapshot);
                    restore(box.supplied(), suppliedSnapshot);
                    restoreCurrency(server, onlineOwner, owner, entry, oldBalance);
                    return failure(TradeResult.Status.FAILED);
                }
            }
            case COMMAND -> {
                if (!entry.item.isEmpty()) {
                    units = Math.min(units, countItems(box.supplied(), entry.item) / entry.item.getCount());
                    if (units <= 0) return failure(TradeResult.Status.NOT_ENOUGH_ITEMS);
                    if (!extractItems(box.supplied(), entry.item, entry.item.getCount() * units)) {
                        restore(box.supplied(), suppliedSnapshot);
                        return failure(TradeResult.Status.FAILED);
                    }
                } else {
                    units = limitByBalance(server, onlineOwner, owner, entry.currencyId,
                            pricePerUnit, units);
                    if (units <= 0) return failure(TradeResult.Status.NOT_ENOUGH_CURRENCY);
                    if (!withdraw(server, onlineOwner, owner, entry.currencyId,
                            pricePerUnit * units, box.getBlockPos())) {
                        return failure(TradeResult.Status.NOT_ENOUGH_CURRENCY);
                    }
                }
            }
        }

        if (!finishTrade(server, onlineOwner, owner, entry, key, period, units)) {
            restore(box.purchased(), purchasedSnapshot);
            restore(box.supplied(), suppliedSnapshot);
            restoreCurrency(server, onlineOwner, owner, entry, oldBalance);
            return failure(TradeResult.Status.FAILED);
        }
        if (!entry.commands.isEmpty()) {
            executeCommands(server, onlineOwner, owner, shop, entryIndex, entry, units);
        }
        if (onlineOwner != null) {
            QShopTradeEvents.postAfter(onlineOwner, shop, tabIndex, entryIndex, entry, units,
                    itemsPerUnit * units, units < UNITS_PER_CYCLE);
        }
        return TradeResult.success(UNITS_PER_CYCLE, units, itemsPerUnit * units,
                pricePerUnit * units);
    }

    private static boolean exchangeItems(RequesterBlockEntity box, ShopEntry entry, int units) {
        for (ItemStack give : entry.give) {
            if (!extractItems(box.supplied(), give, give.getCount() * units)) return false;
        }
        for (ItemStack receive : entry.receive) {
            ItemStack result = receive.copy();
            result.setCount(receive.getCount() * units);
            if (insertItems(box.purchased(), result) != result.getCount()) return false;
        }
        return true;
    }

    private static int availableUnits(MinecraftServer server, UUID owner, ShopEntry entry,
                                      String key, String period) {
        int units = UNITS_PER_CYCLE;
        if (entry.globalLimit > 0) {
            units = Math.min(units, Math.max(0,
                    entry.globalLimit - QShopSavedData.get(server).globalCounts.getCount(key, period)));
        }
        if (entry.playerLimit > 0) {
            units = Math.min(units, Math.max(0,
                    entry.playerLimit - CurrencyService.INSTANCE.getLimitCount(server, owner, key, period)));
        }
        return units;
    }

    private static int limitByBalance(MinecraftServer server, ServerPlayer onlineOwner, UUID owner,
                                      String currency, double price, int units) {
        if (price <= 0) return units;
        long available = (long) (balance(server, onlineOwner, owner, currency) / price);
        return (int) Math.min(units, Math.min(available, Integer.MAX_VALUE));
    }

    private static boolean finishTrade(MinecraftServer server, ServerPlayer onlineOwner, UUID owner,
                                       ShopEntry entry, String key, String period, int units) {
        if (entry.playerLimit > 0) {
            if (onlineOwner != null) {
                IWallet wallet = WalletCapability.get(onlineOwner);
                if (wallet == null) return false;
                wallet.addLimitCount(key, units, period);
            } else if (!addOfflineLimit(server, owner, key, period, units)) {
                return false;
            }
        }
        if (entry.globalLimit > 0) {
            QShopSavedData data = QShopSavedData.get(server);
            data.globalCounts.addCount(key, units, period);
            data.setDirty();
        }
        return true;
    }

    private static void executeCommands(MinecraftServer server, ServerPlayer onlineOwner, UUID owner,
                                        Shop shop, int entryIndex, ShopEntry entry, int units) {
        String playerName = onlineOwner == null
                ? server.getProfileCache().get(owner).map(profile -> profile.getName()).orElse(owner.toString())
                : onlineOwner.getGameProfile().getName();
        int commandRuns = entry.type == ShopEntryType.COMMAND ? units : 1;
        int commandUnits = entry.type == ShopEntryType.COMMAND ? 1 : units;
        int commandItems = entry.type == ShopEntryType.COMMAND
                ? itemsPerUnit(entry) : itemsPerUnit(entry) * units;
        String commandPrice = CurrencyRegistry.format(entry.type == ShopEntryType.COMMAND
                ? entry.price : entry.price * units);
        for (int run = 0; run < commandRuns; run++) {
            for (ShopCommand command : entry.commands) {
                if (command.command == null || command.command.isBlank()) continue;
                String text = command.command
                        .replace("%player%", playerName)
                        .replace("%player_uuid%", owner.toString())
                        .replace("%shop%", shop.id)
                        .replace("%shop_uuid%", shop.uuid == null ? "" : shop.uuid.toString())
                        .replace("%entry%", String.valueOf(entryIndex))
                        .replace("%units%", String.valueOf(commandUnits))
                        .replace("%items%", String.valueOf(commandItems))
                        .replace("%price%", commandPrice)
                        .replace("%currency%", entry.currencyId == null ? "" : entry.currencyId)
                        .replace("%multiplier%", String.valueOf(commandUnits));
                try {
                    CommandSourceStack source = onlineOwner == null
                            ? server.createCommandSourceStack().withPermission(command.op ? 4 : 0)
                            : new CommandSourceStack(onlineOwner, onlineOwner.position(),
                            onlineOwner.getRotationVector(), onlineOwner.serverLevel(),
                            command.op ? 4 : 0, playerName, onlineOwner.getDisplayName(), server, onlineOwner);
                    if (command.silent) source = source.withSuppressedOutput();
                    server.getCommands().performPrefixedCommand(source, text);
                } catch (RuntimeException ignored) {
                    // Match Q-shop: a failed reward command does not roll back the trade.
                }
            }
        }
    }

    private static boolean validEntry(ShopEntry entry) {
        if (entry == null || !Double.isFinite(entry.price) || entry.price < 0) {
            return false;
        }
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

    private static double effectivePrice(ShopEntry entry) {
        return entry.type == ShopEntryType.COMMAND && !entry.item.isEmpty() ? 0D : entry.price;
    }

    private static int itemsPerUnit(ShopEntry entry) {
        return switch (entry.type) {
            case BARTER -> entry.receive.stream().mapToInt(ItemStack::getCount).sum();
            case COMMAND -> entry.item.isEmpty() ? 1 : entry.item.getCount();
            default -> entry.item.getCount();
        };
    }

    private static String limitKey(Shop shop, int tabIndex, int entryIndex, ShopEntry entry) {
        return entry.uuid != null && !entry.uuid.isEmpty()
                ? shop.id + "|" + entry.uuid : shop.id + "|" + tabIndex + "|" + entryIndex;
    }

    private static double balance(MinecraftServer server, ServerPlayer onlineOwner, UUID owner,
                                  String currency) {
        return onlineOwner != null ? CurrencyService.INSTANCE.getBalance(onlineOwner, currency)
                : CurrencyService.INSTANCE.getBalance(server, owner, currency);
    }

    private static boolean withdraw(MinecraftServer server, ServerPlayer onlineOwner, UUID owner,
                                    String currency, double amount, BlockPos pos) {
        if (amount <= 0) return true;
        return onlineOwner != null
                ? CurrencyService.INSTANCE.withdraw(onlineOwner, currency, amount, SOURCE, pos, false)
                : CurrencyService.INSTANCE.withdraw(server, owner, currency, amount, SOURCE, pos, false);
    }

    private static boolean deposit(MinecraftServer server, ServerPlayer onlineOwner, UUID owner,
                                   String currency, double amount, BlockPos pos) {
        if (amount <= 0) return true;
        double before = balance(server, onlineOwner, owner, currency);
        double after;
        if (onlineOwner != null) {
            after = CurrencyService.INSTANCE.deposit(onlineOwner, currency, amount, SOURCE, pos, false);
        } else {
            after = CurrencyService.INSTANCE.deposit(server, owner, currency, amount, SOURCE, pos, false);
        }
        return after >= before + amount;
    }

    private static void restoreCurrency(MinecraftServer server, ServerPlayer onlineOwner, UUID owner,
                                         ShopEntry entry, double oldBalance) {
        if (effectivePrice(entry) <= 0) return;
        if (onlineOwner != null) {
            CurrencyService.INSTANCE.set(onlineOwner, entry.currencyId, oldBalance, SOURCE,
                    null, false);
        } else {
            CurrencyService.INSTANCE.set(server, owner, entry.currencyId, oldBalance, SOURCE,
                    null, false);
        }
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
            if (ItemStack.isSameItemSameComponents(stack, target)) {
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
        for (int slot = 0; slot < handler.getSlots(); slot++) snapshot.add(handler.getStackInSlot(slot).copy());
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
            if (!ItemStack.isSameItemSameComponents(stack, target)) continue;
            ItemStack extracted = handler.extractItem(slot, Math.min(remaining, stack.getCount()), false);
            remaining -= extracted.getCount();
        }
        return remaining == 0;
    }

    private static boolean addOfflineLimit(MinecraftServer server, UUID owner, String key,
                                           String period, int amount) {
        synchronized (OFFLINE_LIMIT_LOCK) {
            if (server.getPlayerList().getPlayer(owner) != null) return false;
            Path target = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
            Path temp = target.resolveSibling(target.getFileName() + ".qshop-requester.tmp");
            try {
                CompoundTag playerData = NbtIo.readCompressed(target, NbtAccounter.unlimitedHeap());
                CompoundTag attachments = playerData.getCompound(NEOFORGE_ATTACHMENTS_KEY);
                CompoundTag forgeCaps = playerData.getCompound(FORGE_CAPS_KEY);
                CompoundTag wallet = attachments.contains(WALLET_CAPABILITY_KEY)
                        ? attachments.getCompound(WALLET_CAPABILITY_KEY)
                        : forgeCaps.getCompound(WALLET_CAPABILITY_KEY);
                CompoundTag limits = wallet.getCompound("limits");
                CompoundTag value = limits.getCompound(key);
                int count = period.equals(value.getString("period")) ? value.getInt("count") : 0;
                value.putString("period", period);
                value.putInt("count", count + amount);
                limits.put(key, value);
                wallet.put("limits", limits);
                attachments.put(WALLET_CAPABILITY_KEY, wallet);
                playerData.put(NEOFORGE_ATTACHMENTS_KEY, attachments);
                NbtIo.writeCompressed(playerData, temp);
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
        return TradeResult.failure(status, UNITS_PER_CYCLE, status.name());
    }

    private static Component failureReason(TradeResult.Status status) {
        String suffix = switch (status) {
            case INVALID_ARGUMENT -> "invalid_argument";
            case SHOP_NOT_FOUND -> "shop_not_found";
            case TAB_NOT_FOUND -> "tab_not_found";
            case ENTRY_NOT_FOUND -> "entry_not_found";
            case UNSUPPORTED_ENTRY -> "unsupported_entry";
            case REQUIREMENTS_NOT_MET -> "requirements_not_met";
            case CANCELLED -> "cancelled";
            case LIMIT_REACHED -> "limit_reached";
            case NOT_ENOUGH_CURRENCY -> "not_enough_currency";
            case NOT_ENOUGH_ITEMS -> "not_enough_items";
            case NO_SPACE -> "no_space";
            case FAILED, SUCCESS -> "failed";
        };
        return Component.translatable("qshop_requester.message.failure." + suffix);
    }

    private static void notifySuccess(RequesterBlockEntity box, ServerPlayer owner, int totalItems) {
        if (box.showActionBarNotification()) owner.displayClientMessage(Component.translatable(
                "qshop_requester.message.trade_success", totalItems), true);
        if (box.showChatNotification()) owner.sendSystemMessage(Component.translatable(
                "qshop_requester.message.trade_success", totalItems));
    }

    private static void notifyFailure(RequesterBlockEntity box, ServerPlayer owner, Component reason) {
        Component message = Component.translatable("qshop_requester.message.trade_failed", reason);
        if (box.showActionBarNotification()) owner.displayClientMessage(message, true);
        if (box.showChatNotification()) owner.sendSystemMessage(message);
    }
}
