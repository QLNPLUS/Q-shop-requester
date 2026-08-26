package com.qshop.requester;

import com.qshop.requester.client.RequesterScreen;
import com.qshop.client.ShopScreen;
import com.qshop.net.ClientShopEntry;
import com.qshop.net.OpenShopPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = RequesterMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RequesterClient {
    private static final Field SHOP_DATA = field(ShopScreen.class, "data");
    private static final Field SHOP_TRADE_INDEX = field(ShopScreen.class, "tradeIndex");
    private static final Field SHOP_EDIT_MODE = field(ShopScreen.class, "editMode");
    private static final Method SHOP_ACTIVE_SERVER_TAB = method(ShopScreen.class, "activeServerTabIndex");
    private static List<RequesterNetwork.TargetInfo> targets = List.of();
    private static List<RequesterNetwork.ShopInfo> shops = List.of();
    private static RequesterScreen selectionOrigin;
    private static String selectionShopId = "";

    private RequesterClient() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(RequesterMod.REQUESTER_MENU.get(), RequesterScreen::new));
    }

    public static void applyState(RequesterNetwork.SyncStatePacket packet) {
        if (Minecraft.getInstance().screen instanceof RequesterScreen screen
                && screen.getMenu().pos().equals(packet.pos())) {
            screen.getMenu().setSettings(packet.intervalTicks(), packet.actionBar(), packet.chat(),
                    packet.enabled(), packet.shopId(), packet.tabIndex(), packet.entryIndex());
            screen.refreshIntervalInput();
        }
    }

    public static void applyShops(List<RequesterNetwork.ShopInfo> receivedShops,
                                  List<RequesterNetwork.TargetInfo> received) {
        shops = List.copyOf(new ArrayList<>(receivedShops));
        targets = List.copyOf(new ArrayList<>(received));
        if (Minecraft.getInstance().screen instanceof RequesterScreen screen) {
            screen.refreshTargets(shops, targets);
        }
    }

    public static List<RequesterNetwork.TargetInfo> targets() { return targets; }
    public static List<RequesterNetwork.ShopInfo> shops() { return shops; }

    public static void beginShopSelection(RequesterScreen origin, String shopId) {
        selectionOrigin = origin;
        selectionShopId = shopId == null ? "" : shopId;
        RequesterNetwork.openShopForSelection(origin.getMenu().pos(), selectionShopId);
    }

    @SubscribeEvent
    public static void onShopSelectionInit(ScreenEvent.Init.Pre event) {
        if (selectionOrigin == null || !(event.getScreen() instanceof ShopScreen shopScreen)) return;
        try {
            OpenShopPacket data = (OpenShopPacket) SHOP_DATA.get(shopScreen);
            if (data != null && selectionShopId.equals(data.shopId)) {
                // Admin/creative users may have Q-shop's remembered edit mode
                // enabled; selection must always use normal entry clicks.
                SHOP_EDIT_MODE.setBoolean(shopScreen, false);
            }
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // Keep the normal Q-shop screen usable if its internals change.
        }
    }

    @SubscribeEvent
    public static void onShopEntryClick(ScreenEvent.MouseButtonPressed.Post event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || selectionOrigin == null || !(event.getScreen() instanceof ShopScreen shopScreen)) {
            return;
        }
        try {
            OpenShopPacket data = (OpenShopPacket) SHOP_DATA.get(shopScreen);
            if (data == null || !selectionShopId.equals(data.shopId)) return;
            int visibleEntryIndex = (int) SHOP_TRADE_INDEX.get(shopScreen);
            if (visibleEntryIndex < 0 || visibleEntryIndex >= data.entries.size()) return;
            Object rawTab = SHOP_ACTIVE_SERVER_TAB.invoke(shopScreen);
            int serverTabIndex = rawTab instanceof Number number ? number.intValue() : -1;
            ClientShopEntry entry = data.entries.get(visibleEntryIndex);
            int serverEntryIndex = entry.serverIndex;
            if (serverTabIndex < 0 || serverEntryIndex < 0) return;

            RequesterScreen origin = selectionOrigin;
            selectionOrigin = null;
            selectionShopId = "";
            origin.selectTargetFromShop(data.shopId, serverTabIndex, serverEntryIndex);
            Minecraft.getInstance().setScreen(origin);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // Q-shop is an optional compile-time integration; a changed screen
            // implementation must not crash the client.
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method method(Class<?> type, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
