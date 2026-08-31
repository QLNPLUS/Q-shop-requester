package com.qshop.requester.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qshop.requester.RequesterMenu;
import com.qshop.requester.RequesterMod;
import com.qshop.requester.RequesterNetwork;
import com.qshop.requester.RequesterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RequesterScreen extends AbstractContainerScreen<RequesterMenu> {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DARK = 0xFF555555;
    private static final int BUTTON_H = 16;
    private static final int MAX_BUTTON_W = 160;

    private enum IntervalUnit {
        SECONDS("qshop_requester.unit.seconds", 20L),
        MINUTES("qshop_requester.unit.minutes", 20L * 60L),
        HOURS("qshop_requester.unit.hours", 20L * 60L * 60L),
        GAME_DAYS("qshop_requester.unit.game_days", 24000L);

        private final String key;
        private final long ticks;
        IntervalUnit(String key, long ticks) { this.key = key; this.ticks = ticks; }
        private IntervalUnit next() { return values()[(ordinal() + 1) % values().length]; }
        private long fromTicks(long value) { return Math.max(1L, Math.round((double) value / ticks)); }
        private long toTicks(long value) { return Math.max(1L, value) * ticks; }
    }

    private int tab;
    private boolean dropdown;
    private int targetPage;
    private IntervalUnit intervalUnit = IntervalUnit.SECONDS;
    private List<RequesterNetwork.ShopInfo> shops = List.of();
    private List<RequesterNetwork.TargetInfo> targets = List.of();
    private LayeredEditBox searchInput;
    private LayeredEditBox intervalInput;

    private static final class LayeredEditBox extends EditBox {
        private boolean manualRender;
        private LayeredEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }
        private void renderManual(GuiGraphics g, int mx, int my, float partial) {
            manualRender = true; render(g, mx, my, partial); manualRender = false;
        }
        @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            if (manualRender) super.renderWidget(g, mx, my, partial);
        }
    }

    public RequesterScreen(RequesterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
        inventoryLabelY = 74;
    }

    @Override protected void init() {
        super.init();
        RequesterEmiCompat.setSettingsScreen(tab == 1);
        RequesterLayoutDebug.beginScreen();
        shops = RequesterClient.shops();
        targets = RequesterClient.targets();
        searchInput = new LayeredEditBox(font, leftPos + 10, topPos + 32, 156, 12,
                Component.translatable("qshop_requester.target.search"));
        searchInput.setMaxLength(128);
        searchInput.setBordered(false);
        searchInput.setTextColor(WHITE);
        searchInput.setTextColorUneditable(WHITE);
        searchInput.setVisible(false);
        addRenderableWidget(searchInput);
        intervalInput = new LayeredEditBox(font, leftPos + 10, topPos + 124, 92, 12,
                Component.translatable("qshop_requester.setting.interval_input"));
        intervalInput.setMaxLength(9);
        intervalInput.setFilter(value -> value.matches("\\d*"));
        intervalInput.setBordered(false);
        intervalInput.setTextColor(WHITE);
        intervalInput.setTextColorUneditable(WHITE);
        intervalInput.setValue(Long.toString(intervalUnit.fromTicks(menu.intervalTicks())));
        intervalInput.setVisible(false);
        addRenderableWidget(intervalInput);
    }

    public void refreshIntervalInput() {
        if (intervalInput != null) intervalInput.setValue(Long.toString(intervalUnit.fromTicks(menu.intervalTicks())));
    }

    public void refreshTargets(List<RequesterNetwork.ShopInfo> refreshedShops,
                               List<RequesterNetwork.TargetInfo> refreshedTargets) {
        shops = refreshedShops == null ? List.of() : List.copyOf(refreshedShops);
        targets = refreshedTargets == null ? List.of() : List.copyOf(refreshedTargets);
        targetPage = 0;
    }

    public void refreshTargets(List<RequesterNetwork.TargetInfo> refreshed) {
        targets = refreshed == null ? List.of() : List.copyOf(refreshed);
        targetPage = 0;
    }

    private void refreshSearchDropdown() {
        targetPage = 0;
        dropdown = tab == 1 && searchInput != null && !filteredShops().isEmpty();
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        // Match Q-shop sellbox: unselected tabs sit behind the page background.
        for (int page = 0; page < 2; page++) {
            if (page != tab) {
                RequesterTextures.tab(g, tabX(page), tabY(page), page, false);
            }
        }
        // The texture controls the two 4x3 container layout; do not cover its
        // middle area in code.
        RequesterTextures.background(g, leftPos, topPos);
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, this.width, this.height, 0x66000000);
        renderBg(g, partial, mx, my);
    }

    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        if (tab != 0) return;
        g.drawString(font, Component.translatable("qshop_requester.purchase"),
                layoutX(RequesterLayoutDebug.Widget.ITEM_PURCHASE_TITLE, 8),
                layoutY(RequesterLayoutDebug.Widget.ITEM_PURCHASE_TITLE, 6), DARK, false);
        g.drawString(font, Component.translatable("qshop_requester.supply"),
                layoutX(RequesterLayoutDebug.Widget.ITEM_SUPPLY_TITLE, 98),
                layoutY(RequesterLayoutDebug.Widget.ITEM_SUPPLY_TITLE, 6), DARK, false);
        g.drawString(font, Component.translatable("container.inventory"),
                layoutX(RequesterLayoutDebug.Widget.ITEM_INVENTORY, 8),
                layoutY(RequesterLayoutDebug.Widget.ITEM_INVENTORY, inventoryLabelY - 1), DARK, false);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        syncInputPosition();
        if (tab == 0) {
            super.render(g, mx, my, partial);
            flushAll(g);
        } else {
            renderBg(g, partial, mx, my);
            g.flush();
        }

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (tab == 1) {
            // Put the opaque settings page over the base container page, then
            // draw the selected tab on top of it like Q-shop sellbox.
            RequesterTextures.ownerBackground(g, leftPos, topPos);
            flushAll(g);
            renderSettings(g, mx, my);
        }
        renderSelectedTab(g);
        g.pose().popPose();

        if (tab == 1 && searchInput != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            searchInput.renderManual(g, mx, my, partial);
            g.flush();
            g.pose().popPose();
        }
        if (tab == 1 && intervalInput != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            intervalInput.renderManual(g, mx, my, partial);
            g.flush();
            g.pose().popPose();
        }
        if (dropdown) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 600);
            renderDropdown(g, mx, my);
            flushAll(g);
            g.pose().popPose();
        }
        if (tab == 0) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 700);
            super.renderTooltip(g, mx, my);
            g.flush();
            g.pose().popPose();
        }
        renderDebugOverlay(g);
    }

    private void renderSelectedTab(GuiGraphics g) {
        RequesterTextures.tab(g, tabX(tab), tabY(tab), tab, true);
        g.pose().pushPose();
        g.pose().translate(0, 0, 100);
        g.renderItem(RequesterMod.REQUESTER_ITEM.get().getDefaultInstance(),
                screenX(RequesterLayoutDebug.Widget.TAB_ITEMS, 5),
                screenY(RequesterLayoutDebug.Widget.TAB_ITEMS, -20));
        g.renderItem(new ItemStack(Items.COMPARATOR),
                screenX(RequesterLayoutDebug.Widget.TAB_SETTINGS, 32),
                screenY(RequesterLayoutDebug.Widget.TAB_SETTINGS, -20));
        g.pose().popPose();
        flushAll(g);
    }

    private int tabX(int page) {
        return page == 0
                ? screenX(RequesterLayoutDebug.Widget.TAB_ITEMS, 0)
                : screenX(RequesterLayoutDebug.Widget.TAB_SETTINGS, 27);
    }

    private int tabY(int page) {
        return page == 0
                ? screenY(RequesterLayoutDebug.Widget.TAB_ITEMS, -28)
                : screenY(RequesterLayoutDebug.Widget.TAB_SETTINGS, -28);
    }

    private void renderSettings(GuiGraphics g, int mx, int my) {
        drawText(g, Component.translatable("qshop_requester.tab.settings"),
                layoutX(RequesterLayoutDebug.Widget.SETTINGS_TITLE, 8),
                layoutY(RequesterLayoutDebug.Widget.SETTINGS_TITLE, 6), WHITE);
        drawAvatar(g,
                screenX(RequesterLayoutDebug.Widget.OWNER_AVATAR, 32),
                screenY(RequesterLayoutDebug.Widget.OWNER_AVATAR, 4), menu.owner());
        String ownerName = menu.owner() == null || menu.ownerName().isBlank()
                ? Component.translatable("qshop_requester.owner.none").getString() : menu.ownerName();
        int ownerInfoX = layoutX(RequesterLayoutDebug.Widget.OWNER_INFO, 56);
        int ownerInfoY = layoutY(RequesterLayoutDebug.Widget.OWNER_INFO, 7);
        drawText(g, trim(ownerName, 8), ownerInfoX, ownerInfoY, WHITE);
        if (menu.owner() != null) {
            boolean online = Minecraft.getInstance().getConnection() != null
                    && Minecraft.getInstance().getConnection().getPlayerInfo(menu.owner()) != null;
            drawText(g, Component.translatable(online
                            ? "qshop_requester.owner.online" : "qshop_requester.owner.offline"),
                    layoutX(RequesterLayoutDebug.Widget.OWNER_INFO, 56),
                    layoutY(RequesterLayoutDebug.Widget.OWNER_INFO, 18),
                    online ? 0xFF2E8B57 : 0xFFC0392B);
        }
        Component claimLabel = Component.translatable("qshop_requester.owner.claim");
        int claimWidth = buttonWidth(claimLabel, 20, 60);
        int claimX = screenX(RequesterLayoutDebug.Widget.OWNER_BUTTON, 108);
        int claimY = screenY(RequesterLayoutDebug.Widget.OWNER_BUTTON, 4);
        RequesterTextures.button(g, claimX, claimY, claimWidth, BUTTON_H,
                inside(mx, my, claimX, claimY, claimWidth, BUTTON_H), true);
        drawCentered(g, claimLabel.getString(),
                layoutX(RequesterLayoutDebug.Widget.OWNER_BUTTON, 108 + claimWidth / 2),
                layoutY(RequesterLayoutDebug.Widget.OWNER_BUTTON, 8), WHITE, claimWidth - 8);

        int searchX = layoutX(RequesterLayoutDebug.Widget.SEARCH_INPUT, 8);
        int searchY = layoutY(RequesterLayoutDebug.Widget.SEARCH_INPUT, 30);
        RequesterTextures.input(g, leftPos + searchX, topPos + searchY, 160, 14,
                searchInput != null && searchInput.isFocused());
        int bx = screenX(RequesterLayoutDebug.Widget.TARGET_BUTTON, 8);
        int by = screenY(RequesterLayoutDebug.Widget.TARGET_BUTTON, 45);
        RequesterTextures.button(g, bx, by, MAX_BUTTON_W, BUTTON_H,
                inside(mx, my, bx, by, MAX_BUTTON_W, BUTTON_H), !filteredShops().isEmpty());
        drawCentered(g, selectedLabel(),
                layoutX(RequesterLayoutDebug.Widget.TARGET_BUTTON, 8 + MAX_BUTTON_W / 2),
                layoutY(RequesterLayoutDebug.Widget.TARGET_BUTTON, 49), WHITE, 148);

        RequesterNetwork.TargetInfo selected = selectedTarget();
        if (selected == null) {
            drawText(g, Component.translatable("qshop_requester.target.none"),
                    layoutX(RequesterLayoutDebug.Widget.SELECTED_INFO, 30),
                    layoutY(RequesterLayoutDebug.Widget.SELECTED_INFO, 67), WHITE);
        } else {
            int infoX = screenX(RequesterLayoutDebug.Widget.SELECTED_INFO, 8);
            int infoY = screenY(RequesterLayoutDebug.Widget.SELECTED_INFO, 63);
            g.renderItem(selected.display, infoX, infoY);
            drawScrollingText(g, selected.label, infoX + 22, infoY + 3, 138);
            List<String> details = targetDetails(selected);
            for (int i = 0; i < details.size() && i < 3; i++) {
                drawScrollingText(g, details.get(i), infoX + 22, infoY + 17 + i * 14, 138);
            }
        }

        drawText(g, Component.translatable("qshop_requester.setting.interval"),
                layoutX(RequesterLayoutDebug.Widget.INTERVAL_LABEL, 8),
                layoutY(RequesterLayoutDebug.Widget.INTERVAL_LABEL, 109), WHITE);
        int intervalX = layoutX(RequesterLayoutDebug.Widget.INTERVAL_INPUT, 8);
        int intervalY = layoutY(RequesterLayoutDebug.Widget.INTERVAL_INPUT, 123);
        RequesterTextures.input(g, leftPos + intervalX, topPos + intervalY, 96, 14,
                intervalInput != null && intervalInput.isFocused());
        Component unit = Component.translatable(intervalUnit.key);
        int unitW = buttonWidth(unit, 20, 64);
        int unitX = screenX(RequesterLayoutDebug.Widget.INTERVAL_UNIT, 104);
        int unitY = screenY(RequesterLayoutDebug.Widget.INTERVAL_UNIT, 121);
        RequesterTextures.button(g, unitX, unitY, unitW, BUTTON_H,
                inside(mx, my, unitX, unitY, unitW, BUTTON_H), true);
        drawCentered(g, unit.getString(),
                layoutX(RequesterLayoutDebug.Widget.INTERVAL_UNIT, 104 + unitW / 2),
                layoutY(RequesterLayoutDebug.Widget.INTERVAL_UNIT, 125), WHITE, unitW - 8);

        drawNotification(g, mx, my, RequesterLayoutDebug.Widget.ACTION_BAR_NOTIFICATION, 8, 139,
                Component.translatable("qshop_requester.setting.action_bar"), menu.actionBar());
        drawNotification(g, mx, my, RequesterLayoutDebug.Widget.CHAT_NOTIFICATION, 8, 152,
                Component.translatable("qshop_requester.setting.chat"), menu.chat());
    }

    private void drawAvatar(GuiGraphics g, int x, int y, UUID owner) {
        ResourceLocation skin = ResourceLocation.fromNamespaceAndPath(
                "minecraft", "textures/entity/steve.png");
        if (owner != null && Minecraft.getInstance().getConnection() != null) {
            PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(owner);
            if (info != null) skin = info.getSkin().texture();
        }
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, skin);
        g.blit(skin, x, y, 20, 20, 8, 8, 8, 8, 64, 64);
        g.blit(skin, x, y, 20, 20, 40, 8, 8, 8, 64, 64);
    }

    private void renderDropdown(GuiGraphics g, int mx, int my) {
        int x = screenX(RequesterLayoutDebug.Widget.TARGET_BUTTON, 8);
        int y = screenY(RequesterLayoutDebug.Widget.TARGET_BUTTON, 46);
        RequesterTextures.dropdown(g, x, y);
        drawText(g, Component.translatable("qshop_requester.target.dropdown"),
                x - leftPos + 5, y - topPos + 4, WHITE);
        int start = targetPage * 4;
        List<RequesterNetwork.ShopInfo> visibleShops = filteredShops();
        for (int i = 0; i < Math.min(4, visibleShops.size() - start); i++) {
            RequesterNetwork.ShopInfo shop = visibleShops.get(start + i);
            int rowY = y + 18 + i * 18;
            boolean hovered = inside(mx, my, x + 2, rowY, 156, 18);
            if (hovered) RequesterTextures.entryHighlight(g, x, rowY);
            g.renderItem(shop.icon, x + 4, rowY + 1);
            drawScrollingText(g, shop.shopName, x + 23, rowY + 5, 131);
        }
    }

    private String targetText(RequesterNetwork.TargetInfo target) {
        String prefix = target.shopName + " / " + target.label;
        return prefix + " | " + String.join(" | ", targetDetails(target));
    }

    private List<String> targetDetails(RequesterNetwork.TargetInfo target) {
        return switch (target.type) {
            case BUY -> List.of(Component.translatable("qshop_requester.entry.buy").getString()
                    + ": " + formatPrice(target));
            case SELL -> List.of(Component.translatable("qshop_requester.entry.sell").getString()
                    + ": " + formatPrice(target),
                    Component.translatable("qshop_requester.need").getString()
                            + stacksLabel(target.give));
            case BARTER -> List.of(Component.translatable("qshop_requester.need").getString()
                            + stacksLabel(target.give),
                    Component.translatable("qshop_requester.receive").getString()
                            + stacksLabel(target.receive));
            case COMMAND -> target.give.isEmpty() && target.receive.isEmpty()
                    ? List.of(target.price > 0 ? formatPrice(target)
                            : Component.translatable("qshop_requester.entry.command").getString())
                    : List.of(Component.translatable("qshop_requester.need").getString()
                            + stacksLabel(target.give),
                    Component.translatable("qshop_requester.entry.command").getString());
        };
    }

    private String stacksLabel(List<ItemStack> stacks) {
        StringBuilder result = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (result.length() > 0) result.append(" + ");
            result.append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
        }
        return result.toString();
    }

    private void drawScrollingText(GuiGraphics g, String text, int x, int y, int width) {
        int textWidth = font.width(text);
        if (textWidth <= width) {
            g.drawString(font, Component.literal(text), x, y, WHITE, true);
            return;
        }
        long cycle = Math.max(2600L, (long) (textWidth - width) * 75L + 1800L);
        long phase = Util.getMillis() % cycle;
        int offset = phase < 700L ? 0 : (int) Math.min(textWidth - width, phase - 700L);
        g.flush();
        g.enableScissor(x, y - 1, x + width, y + font.lineHeight + 1);
        g.drawString(font, Component.literal(text), x - offset, y, WHITE, true);
        if (offset > 0) {
            g.drawString(font, Component.literal(text), x - offset + textWidth + 18,
                    y, WHITE, true);
        }
        g.flush();
        g.disableScissor();
    }

    private void drawNotification(GuiGraphics g, int mx, int my,
                                  RequesterLayoutDebug.Widget widget, int x, int y,
                                  Component label, boolean checked) {
        int sx = screenX(widget, x), sy = screenY(widget, y);
        RequesterTextures.checkbox(g, sx, sy, checked, inside(mx, my, sx, sy, 160, 12));
        drawText(g, label, layoutX(widget, x + 16), layoutY(widget, y + 2), WHITE);
    }

    private RequesterNetwork.TargetInfo selectedTarget() {
        for (RequesterNetwork.TargetInfo target : targets) {
            if (target.shopUuid.equals(menu.shopUuid()) && target.tabUuid.equals(menu.tabUuid())
                    && target.entryUuid.equals(menu.entryUuid())) return target;
        }
        return null;
    }

    private List<RequesterNetwork.TargetInfo> filteredTargets() {
        if (searchInput == null || searchInput.getValue().isBlank()) return targets;
        String query = searchInput.getValue().trim().toLowerCase(Locale.ROOT);
        return targets.stream().filter(target -> {
            String text = target.shopName + " " + target.shopId + " " + target.tabName + " "
                    + target.label + " " + targetText(target) + " "
                    + stacksLabel(target.give) + " " + stacksLabel(target.receive);
            return text.toLowerCase(Locale.ROOT).contains(query);
        }).toList();
    }

    private List<RequesterNetwork.ShopInfo> filteredShops() {
        if (searchInput == null || searchInput.getValue().isBlank()) return shops;
        String query = searchInput.getValue().trim().toLowerCase(Locale.ROOT);
        return shops.stream().filter(shop -> (shop.shopName + " " + shop.shopId)
                .toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private String selectedLabel() {
        RequesterNetwork.TargetInfo selected = selectedTarget();
        return selected == null ? Component.translatable("qshop_requester.target.choose").getString()
                : trim(selected.shopName + " / " + selected.label, 26);
    }

    private String formatPrice(RequesterNetwork.TargetInfo target) {
        String price = Math.abs(target.price - Math.rint(target.price)) < 0.000001D
                ? String.format(java.util.Locale.ROOT, "%.0f", target.price)
                : String.format(java.util.Locale.ROOT, "%.2f", target.price);
        return price + (target.currency.isBlank() ? "" : " " + target.currency);
    }

    private void chooseTarget(int index) {
        List<RequesterNetwork.TargetInfo> visibleTargets = filteredTargets();
        if (index < 0 || index >= visibleTargets.size()) return;
        RequesterNetwork.TargetInfo target = visibleTargets.get(index);
        menu.setSettings(menu.intervalTicks(), menu.actionBar(), menu.chat(), menu.enabled(),
                target.shopUuid, target.tabUuid, target.entryUuid);
        sendSettings();
    }

    private void chooseShop(int index) {
        List<RequesterNetwork.ShopInfo> visibleShops = filteredShops();
        if (index < 0 || index >= visibleShops.size()) return;
        RequesterNetwork.ShopInfo shop = visibleShops.get(index);
        RequesterClient.beginShopSelection(this, shop.shopId, shop.shopUuid);
    }

    public void selectTargetFromShop(String shopUuid, String tabUuid, String entryUuid) {
        menu.setSettings(menu.intervalTicks(), menu.actionBar(), menu.chat(), menu.enabled(),
                shopUuid, tabUuid, entryUuid);
        sendSettings();
    }

    private void sendSettings() {
        int interval = menu.intervalTicks();
        if (intervalInput != null && !intervalInput.getValue().isBlank()) {
            try { interval = (int) Math.min(Integer.MAX_VALUE, intervalUnit.toTicks(Long.parseLong(intervalInput.getValue()))); }
            catch (NumberFormatException ignored) { }
        }
        menu.setSettings(interval, menu.actionBar(), menu.chat(), menu.enabled(),
                menu.shopUuid(), menu.tabUuid(), menu.entryUuid());
        RequesterNetwork.sendSettings(menu.pos(), interval, menu.actionBar(), menu.chat(), menu.enabled(),
                menu.shopUuid(), menu.tabUuid(), menu.entryUuid());
    }

    private void syncInputPosition() {
        if (searchInput != null) {
            searchInput.setX(screenX(RequesterLayoutDebug.Widget.SEARCH_INPUT, 10));
            searchInput.setY(screenY(RequesterLayoutDebug.Widget.SEARCH_INPUT, 32));
        }
        if (intervalInput != null) {
            intervalInput.setX(screenX(RequesterLayoutDebug.Widget.INTERVAL_INPUT, 10));
            intervalInput.setY(screenY(RequesterLayoutDebug.Widget.INTERVAL_INPUT, 124));
        }
    }

    private void setTab(int next) {
        if (tab == 1 && next != 1) sendSettings();
        tab = next;
        RequesterEmiCompat.setSettingsScreen(next == 1);
        dropdown = false;
        if (searchInput != null) {
            searchInput.setVisible(next == 1);
            if (next == 0) searchInput.setFocused(false);
        }
        if (intervalInput != null) {
            intervalInput.setVisible(next == 1);
            if (next == 0) intervalInput.setFocused(false);
        }
        RequesterLayoutDebug.ensureSelected(next);
    }

    private void cycleUnit() {
        intervalUnit = intervalUnit.next();
        refreshIntervalInput();
        sendSettings();
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        syncInputPosition();
        if (button != 0) return tab == 0 ? super.mouseClicked(mx, my, button) : true;
        if (inside(mx, my, screenX(RequesterLayoutDebug.Widget.TAB_ITEMS, 0),
                screenY(RequesterLayoutDebug.Widget.TAB_ITEMS, -28), 26, 32)) {
            setTab(0); return true;
        }
        if (inside(mx, my, screenX(RequesterLayoutDebug.Widget.TAB_SETTINGS, 27),
                screenY(RequesterLayoutDebug.Widget.TAB_SETTINGS, -28), 26, 32)) {
            setTab(1); return true;
        }
        if (tab == 1) {
            Component claimLabel = Component.translatable("qshop_requester.owner.claim");
            int claimWidth = buttonWidth(claimLabel, 20, 60);
            int claimX = screenX(RequesterLayoutDebug.Widget.OWNER_BUTTON, 108);
            int claimY = screenY(RequesterLayoutDebug.Widget.OWNER_BUTTON, 4);
            if (inside(mx, my, claimX, claimY, claimWidth, BUTTON_H)) {
                if (searchInput != null) searchInput.setFocused(false);
                if (intervalInput != null) intervalInput.setFocused(false);
                RequesterNetwork.sendClaimOwner(menu.pos());
                return true;
            }
            if (dropdown) {
                if (handleSearchClick(mx, my, button)) return true;
                int x = screenX(RequesterLayoutDebug.Widget.TARGET_BUTTON, 8);
                int y = screenY(RequesterLayoutDebug.Widget.TARGET_BUTTON, 46);
                int start = targetPage * 4;
                List<RequesterNetwork.ShopInfo> visibleShops = filteredShops();
                for (int i = 0; i < Math.min(4, visibleShops.size() - start); i++) {
                    if (inside(mx, my, x + 2, y + 18 + i * 18, 156, 18)) {
                        chooseShop(start + i); dropdown = false; return true;
                    }
                }
                dropdown = false; return true;
            }
            int targetX = screenX(RequesterLayoutDebug.Widget.TARGET_BUTTON, 8);
            int targetY = screenY(RequesterLayoutDebug.Widget.TARGET_BUTTON, 45);
            if (inside(mx, my, targetX, targetY, MAX_BUTTON_W, BUTTON_H)) {
                if (searchInput != null) searchInput.setFocused(false);
                if (intervalInput != null) intervalInput.setFocused(false);
                dropdown = !filteredShops().isEmpty(); targetPage = 0; return true;
            }
            Component unit = Component.translatable(intervalUnit.key);
            int unitW = buttonWidth(unit, 20, 64);
            int unitX = screenX(RequesterLayoutDebug.Widget.INTERVAL_UNIT, 104);
            int unitY = screenY(RequesterLayoutDebug.Widget.INTERVAL_UNIT, 121);
            if (inside(mx, my, unitX, unitY, unitW, BUTTON_H)) { cycleUnit(); return true; }
            if (inside(mx, my, screenX(RequesterLayoutDebug.Widget.ACTION_BAR_NOTIFICATION, 8),
                    screenY(RequesterLayoutDebug.Widget.ACTION_BAR_NOTIFICATION, 139), 160, 12)) {
                menu.setSettings(menu.intervalTicks(), !menu.actionBar(), menu.chat(), menu.enabled(),
                        menu.shopUuid(), menu.tabUuid(), menu.entryUuid()); sendSettings(); return true;
            }
            if (inside(mx, my, screenX(RequesterLayoutDebug.Widget.CHAT_NOTIFICATION, 8),
                    screenY(RequesterLayoutDebug.Widget.CHAT_NOTIFICATION, 152), 160, 12)) {
                menu.setSettings(menu.intervalTicks(), menu.actionBar(), !menu.chat(), menu.enabled(),
                        menu.shopUuid(), menu.tabUuid(), menu.entryUuid()); sendSettings(); return true;
            }
            if (handleSearchClick(mx, my, button)) return true;
            if (intervalInput != null && intervalInput.mouseClicked(mx, my, button)) {
                if (searchInput != null) searchInput.setFocused(false);
                intervalInput.setFocused(true); return true;
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F8) {
            if (!RequesterLayoutDebug.isConfiguredEnabled()) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            RequesterLayoutDebug.toggle();
            RequesterLayoutDebug.ensureSelected(tab);
            return true;
        }
        if (RequesterLayoutDebug.isEnabled()) {
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                RequesterLayoutDebug.selectNext(tab, hasShiftDown());
                return true;
            }
            int dx = 0, dy = 0;
            if (keyCode == GLFW.GLFW_KEY_LEFT) dx = -1;
            if (keyCode == GLFW.GLFW_KEY_RIGHT) dx = 1;
            if (keyCode == GLFW.GLFW_KEY_UP) dy = -1;
            if (keyCode == GLFW.GLFW_KEY_DOWN) dy = 1;
            if (dx != 0 || dy != 0) {
                int step = hasAltDown() ? 1 : 5;
                RequesterLayoutDebug.moveSelected(tab, dx * step, dy * step);
                syncInputPosition();
                return true;
            }
        }
        if (tab == 1 && searchInput != null && searchInput.isFocused()) {
            if (searchInput.keyPressed(keyCode, scanCode, modifiers)) {
                refreshSearchDropdown();
                return true;
            }
        }
        if (tab == 1 && intervalInput != null && intervalInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                intervalInput.setFocused(false); sendSettings(); return true;
            }
            if (intervalInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean charTyped(char codePoint, int modifiers) {
        if (tab == 1 && searchInput != null && searchInput.isFocused()
                && searchInput.charTyped(codePoint, modifiers)) {
            refreshSearchDropdown();
            return true;
        }
        if (tab == 1 && intervalInput != null && intervalInput.isFocused()
                && intervalInput.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override public boolean mouseScrolled(double mx, double my, double deltaX, double deltaY) {
        if (tab == 1 && dropdown && filteredShops().size() > 4) {
            int maxPage = (filteredShops().size() - 1) / 4;
            targetPage = Mth.clamp(targetPage + (deltaY < 0 ? 1 : -1), 0, maxPage);
            return true;
        }
        if (tab == 1) return true;
        return super.mouseScrolled(mx, my, deltaX, deltaY);
    }

    @Override public void mouseMoved(double mx, double my) {
        // EMI can keep updating its hover target through Screen.mouseMoved even
        // when this custom settings page does not render EMI's item panel.
        if (tab == 1) return;
        super.mouseMoved(mx, my);
    }

    @Override protected void renderTooltip(GuiGraphics g, int mx, int my) {
        // Do not allow EMI or vanilla tooltip callbacks to leak into the
        // settings page after its item panel has been hidden.
        if (tab == 1) return;
        super.renderTooltip(g, mx, my);
    }

    @Override public void removed() {
        RequesterEmiCompat.setSettingsScreen(false);
        sendSettings();
        super.removed();
    }

    private void drawText(GuiGraphics g, Component text, int x, int y, int color) {
        g.drawString(font, text, leftPos + x, topPos + y, color, true);
    }
    private void drawText(GuiGraphics g, String text, int x, int y, int color) {
        drawText(g, Component.literal(text), x, y, color);
    }

    private boolean handleSearchClick(double mx, double my, int button) {
        int searchX = screenX(RequesterLayoutDebug.Widget.SEARCH_INPUT, 8);
        int searchY = screenY(RequesterLayoutDebug.Widget.SEARCH_INPUT, 30);
        if (searchInput != null && inside(mx, my, searchX, searchY, 160, 14)
                && searchInput.mouseClicked(mx, my, button)) {
            if (intervalInput != null) intervalInput.setFocused(false);
            searchInput.setFocused(true);
            refreshSearchDropdown();
            return true;
        }
        return false;
    }

    private void drawCentered(GuiGraphics g, String text, int x, int y, int color, int maxWidth) {
        String value = font.plainSubstrByWidth(text, Math.max(1, maxWidth));
        g.drawString(font, value, leftPos + x - font.width(value) / 2, topPos + y, color, true);
    }
    private int buttonWidth(Component label, int min, int max) { return Mth.clamp(font.width(label) + 12, min, max); }
    private String trim(String value, int max) { return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "..."; }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    private static void flushAll(GuiGraphics g) {
        g.flush(); Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
    }

    private void renderDebugOverlay(GuiGraphics g) {
        if (!RequesterLayoutDebug.isEnabled()) return;
        RequesterLayoutDebug.Widget widget = RequesterLayoutDebug.selected();
        int x;
        int y;
        int width;
        int height;
        if (widget == RequesterLayoutDebug.Widget.TAB_ITEMS
                || widget == RequesterLayoutDebug.Widget.TAB_SETTINGS) {
            int page = widget == RequesterLayoutDebug.Widget.TAB_ITEMS ? 0 : 1;
            x = screenX(widget, page * 27);
            y = screenY(widget, -28);
            width = 26;
            height = 32;
        } else if (widget == RequesterLayoutDebug.Widget.ITEM_PURCHASE_TITLE) {
            x = leftPos + layoutX(widget, 8); y = topPos + layoutY(widget, 6);
            width = font.width(Component.translatable("qshop_requester.purchase")); height = font.lineHeight;
        } else if (widget == RequesterLayoutDebug.Widget.ITEM_SUPPLY_TITLE) {
            x = leftPos + layoutX(widget, 98); y = topPos + layoutY(widget, 6);
            width = font.width(Component.translatable("qshop_requester.supply")); height = font.lineHeight;
        } else {
            x = screenX(widget, switch (widget) {
                case OWNER_AVATAR -> 32;
                case OWNER_INFO -> 56;
                case OWNER_BUTTON -> 108;
                default -> 8;
            });
            y = screenY(widget, switch (widget) {
                case SETTINGS_TITLE -> 6;
                case OWNER_AVATAR -> 4;
                case OWNER_INFO -> 7;
                case OWNER_BUTTON -> 4;
                case SEARCH_INPUT -> 30;
                case TARGET_BUTTON -> 45;
                case SELECTED_INFO -> 63;
                case INTERVAL_LABEL -> 109;
                case INTERVAL_INPUT -> 123;
                case INTERVAL_UNIT -> 121;
                case ACTION_BAR_NOTIFICATION -> 139;
                case CHAT_NOTIFICATION -> 152;
                default -> 0;
            });
            width = switch (widget) {
                case SETTINGS_TITLE, INTERVAL_LABEL -> 120;
                case OWNER_AVATAR -> 20;
                case OWNER_INFO -> 48;
                case OWNER_BUTTON -> 60;
                case SEARCH_INPUT, TARGET_BUTTON -> 160;
                case SELECTED_INFO -> 160;
                case INTERVAL_INPUT -> 96;
                case INTERVAL_UNIT -> 64;
                case ACTION_BAR_NOTIFICATION, CHAT_NOTIFICATION -> 160;
                default -> 20;
            };
            height = switch (widget) {
                case SETTINGS_TITLE, INTERVAL_LABEL -> font.lineHeight;
                case OWNER_INFO -> font.lineHeight * 2;
                case OWNER_BUTTON, INTERVAL_UNIT, TARGET_BUTTON -> 16;
                case SELECTED_INFO -> 42;
                default -> 14;
            };
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 900);
        RequesterLayoutDebug.renderOverlay(g, font, x, y, width, height);
        flushAll(g);
        g.pose().popPose();
    }

    private int layoutX(RequesterLayoutDebug.Widget widget, int normal) {
        return RequesterLayoutDebug.x(widget, normal);
    }

    private int layoutY(RequesterLayoutDebug.Widget widget, int normal) {
        return RequesterLayoutDebug.y(widget, normal);
    }

    private int screenX(RequesterLayoutDebug.Widget widget, int normal) {
        return leftPos + layoutX(widget, normal);
    }

    private int screenY(RequesterLayoutDebug.Widget widget, int normal) {
        return topPos + layoutY(widget, normal);
    }
}
