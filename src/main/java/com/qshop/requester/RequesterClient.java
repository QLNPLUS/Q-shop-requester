package com.qshop.requester;

import com.qshop.requester.client.RequesterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class RequesterClient {
    private static List<RequesterNetwork.TargetInfo> targets = List.of();

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

    public static void applyShops(List<RequesterNetwork.TargetInfo> received) {
        targets = List.copyOf(new ArrayList<>(received));
        if (Minecraft.getInstance().screen instanceof RequesterScreen screen) screen.refreshTargets(targets);
    }

    public static List<RequesterNetwork.TargetInfo> targets() { return targets; }
}
