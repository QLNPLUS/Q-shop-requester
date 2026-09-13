package com.qshop.requester.client;

import com.qshop.requester.RequesterMenu;
import com.qshop.requester.RequesterMod;
import com.qshop.requester.RequesterNetwork;
import com.qshop.requester.RequesterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
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
        private void renderManual(GuiGraphicsExtractor g, int mx, int my, float partial) {
            manualRender = true;
            extractRenderState(g, mx, my, partial);
            manualRender = false;
        }
        // 26.1.2:AbstractWidget 的虚方法是 extractWidgetRenderState;
        // extractRenderState 变为 final 的外层入口,不能再被覆写。
        // EditBox 把该方法的可见性放宽为 public,覆写时不能收窄。
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
            if (manualRender) super.extractWidgetRenderState(g, mx, my, partial);
        }
    }

    public RequesterScreen(RequesterMenu menu, Inventory inventory, Component title) {
        // 26.1.2:imageWidth/imageHeight 在 AbstractContainerScreen 里已是 final,
        // 不能再在构造器体内赋值,改由带尺寸的父类构造器传入。
        super(menu, inventory, title, 176, 166);
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
        searchInput.setVisible(tab == 1);
        addRenderableWidget(searchInput);
        intervalInput = new LayeredEditBox(font, leftPos + 10, topPos + 124, 92, 12,
                Component.translatable("qshop_requester.setting.interval_input"));
        intervalInput.setMaxLength(9);
        intervalInput.setFilter(value -> value.matches("\\d*"));
        intervalInput.setBordered(false);
        intervalInput.setTextColor(WHITE);
        intervalInput.setTextColorUneditable(WHITE);
        intervalInput.setValue(Long.toString(intervalUnit.fromTicks(menu.intervalTicks())));
        intervalInput.setVisible(tab == 1);
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

    // 26.1.2:AbstractContainerScreen 不再有 renderBg/renderBackground 这对虚方法。
    // 背景改由 Screen.extractBackground 负责 —— 框架在 extractRenderState 之前、更低的
    // stratum 里**两个分页都会调用**它,所以把原来的"暗色蒙版 + 未选中页签 + 页面底图"
    // 整块合并到这里,并且蒙版对两个分页都要画:
    // 1.21.1 的分支写法是 `tab == 0 ? super.render(...) : renderBg(...)`,
    // 而蒙版只在 super.render → Screen.render → renderBackground 那条路上,
    // 于是设置页从来没有蒙版 —— 切到设置页时面板外的世界会突然变亮。
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
        g.fill(0, 0, this.width, this.height, 0x66000000);
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

    // 26.1.2:renderLabels 改名为 extractLabels,且同样在平移过 (leftPos, topPos) 的
    // 局部坐标系里调用,所以这里的 layoutX/layoutY 不需要再加 leftPos/topPos。
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
        if (tab != 0) return;
        g.text(font, Component.translatable("qshop_requester.purchase"),
                layoutX(RequesterLayoutDebug.Widget.ITEM_PURCHASE_TITLE, 8),
                layoutY(RequesterLayoutDebug.Widget.ITEM_PURCHASE_TITLE, 6), DARK, false);
        g.text(font, Component.translatable("qshop_requester.supply"),
                layoutX(RequesterLayoutDebug.Widget.ITEM_SUPPLY_TITLE, 98),
                layoutY(RequesterLayoutDebug.Widget.ITEM_SUPPLY_TITLE, 6), DARK, false);
        g.text(font, Component.translatable("container.inventory"),
                layoutX(RequesterLayoutDebug.Widget.ITEM_INVENTORY, 8),
                layoutY(RequesterLayoutDebug.Widget.ITEM_INVENTORY, inventoryLabelY - 1), DARK, false);
    }

    // 26.1.2:render 改名为 extractRenderState。它只负责"内容",背景由 extractBackground
    // 在更低的 stratum 先画好;叠放次序改用 nextStratum() 表达。
    // 旧代码里的 g.flush()/flushAll() 已无对应 API:26.1.2 的 GUI 提交改为 stratum 模型,
    // 不再由调用方手动冲刷缓冲。
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        syncInputPosition();
        // 背景(含蒙版)已由框架在更低的 stratum 调用 extractBackground 画好,两个分页都是,
        // 所以这里不再重复调用,否则蒙版会被叠两次(0x66 上面再压一层 0x66)。
        // 物品页额外需要容器本体(标签/槽位/carried item);设置页不画,由下面不透明的
        // ownerBackground 整块顶替。
        if (tab == 0) {
            super.extractRenderState(g, mx, my, partial);
        }

        g.pose().pushMatrix();
        g.nextStratum();
        if (tab == 1) {
            // Put the opaque settings page over the base container page, then
            // draw the selected tab on top of it like Q-shop sellbox.
            RequesterTextures.ownerBackground(g, leftPos, topPos);
            renderSettings(g, mx, my);
        }
        renderSelectedTab(g);
        g.pose().popMatrix();

        if (tab == 1 && searchInput != null) {
            g.pose().pushMatrix();
            g.nextStratum();
            searchInput.renderManual(g, mx, my, partial);
            g.pose().popMatrix();
        }
        if (tab == 1 && intervalInput != null) {
            g.pose().pushMatrix();
            g.nextStratum();
            intervalInput.renderManual(g, mx, my, partial);
            g.pose().popMatrix();
        }
        if (dropdown) {
            g.pose().pushMatrix();
            g.nextStratum();
            renderDropdown(g, mx, my);
            g.pose().popMatrix();
        }
        // 物品页的 tooltip 由 super.extractRenderState -> extractTooltip 递交,
        // 设置页由下面的覆写拦掉,所以这里不再重复调用。
        renderDebugOverlay(g);
    }

    private void renderSelectedTab(GuiGraphicsExtractor g) {
        RequesterTextures.tab(g, tabX(tab), tabY(tab), tab, true);
        g.pose().pushMatrix();
        g.nextStratum();
        g.item(RequesterMod.REQUESTER_ITEM.get().getDefaultInstance(),
                screenX(RequesterLayoutDebug.Widget.TAB_ITEMS, 5),
                screenY(RequesterLayoutDebug.Widget.TAB_ITEMS, -20));
        g.item(new ItemStack(Items.COMPARATOR),
                screenX(RequesterLayoutDebug.Widget.TAB_SETTINGS, 32),
                screenY(RequesterLayoutDebug.Widget.TAB_SETTINGS, -20));
        g.pose().popMatrix();
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

    private void renderSettings(GuiGraphicsExtractor g, int mx, int my) {
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
            g.item(selected.display, infoX, infoY);
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

    private void drawAvatar(GuiGraphicsExtractor g, int x, int y, UUID owner) {
        Identifier skin = Identifier.fromNamespaceAndPath(
                "minecraft", "textures/entity/steve.png");
        if (owner != null && Minecraft.getInstance().getConnection() != null) {
            PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(owner);
            // 26.1.2:PlayerSkin.texture() 已拆分为 body()/cape()/elytra(),
            // 各自返回 ClientAsset.Texture,取 Identifier 用 texturePath()。
            if (info != null) skin = info.getSkin().body().texturePath();
        }
        // 26.1.2:RenderSystem.setShader/setShaderTexture 已移除,管线由 blit 首参指定。
        // 注意 26.1.2 的 10 参重载顺序是 (x, y, u, v, width, height, srcW, srcH, texW, texH),
        // 与 1.21.1 的 (x, y, width, height, u, v, srcW, srcH, texW, texH) 不同,不能只加首参。
        g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8, 8, 20, 20, 8, 8, 64, 64);
        g.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40, 8, 20, 20, 8, 8, 64, 64);
    }

    private void renderDropdown(GuiGraphicsExtractor g, int mx, int my) {
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
            g.item(shop.icon, x + 4, rowY + 1);
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

    private void drawScrollingText(GuiGraphicsExtractor g, String text, int x, int y, int width) {
        int textWidth = font.width(text);
        if (textWidth <= width) {
            g.text(font, Component.literal(text), x, y, WHITE, true);
            return;
        }
        long cycle = Math.max(2600L, (long) (textWidth - width) * 75L + 1800L);
        long phase = Util.getMillis() % cycle;
        int offset = phase < 700L ? 0 : (int) Math.min(textWidth - width, phase - 700L);
        g.enableScissor(x, y - 1, x + width, y + font.lineHeight + 1);
        g.text(font, Component.literal(text), x - offset, y, WHITE, true);
        if (offset > 0) {
            g.text(font, Component.literal(text), x - offset + textWidth + 18,
                    y, WHITE, true);
        }
        g.disableScissor();
    }

    private void drawNotification(GuiGraphicsExtractor g, int mx, int my,
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

    // 26.1.2:输入事件改为记录类型(MouseButtonEvent/KeyEvent/CharacterEvent),
    // 不再逐个传 (x, y, button) 或 (keyCode, scanCode, modifiers)。
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        syncInputPosition();
        if (button != 0) return tab == 0 ? super.mouseClicked(event, doubled) : true;
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
                if (handleSearchClick(event, doubled)) return true;
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
            if (handleSearchClick(event, doubled)) return true;
            if (intervalInput != null && intervalInput.mouseClicked(event, doubled)) {
                if (searchInput != null) searchInput.setFocused(false);
                intervalInput.setFocused(true); return true;
            }
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_F8) {
            if (!RequesterLayoutDebug.isConfiguredEnabled()) {
                return super.keyPressed(event);
            }
            RequesterLayoutDebug.toggle();
            RequesterLayoutDebug.ensureSelected(tab);
            return true;
        }
        if (RequesterLayoutDebug.isEnabled()) {
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                RequesterLayoutDebug.selectNext(tab, event.hasShiftDown());
                return true;
            }
            int dx = 0, dy = 0;
            if (keyCode == GLFW.GLFW_KEY_LEFT) dx = -1;
            if (keyCode == GLFW.GLFW_KEY_RIGHT) dx = 1;
            if (keyCode == GLFW.GLFW_KEY_UP) dy = -1;
            if (keyCode == GLFW.GLFW_KEY_DOWN) dy = 1;
            if (dx != 0 || dy != 0) {
                int step = event.hasAltDown() ? 1 : 5;
                RequesterLayoutDebug.moveSelected(tab, dx * step, dy * step);
                syncInputPosition();
                return true;
            }
        }
        if (tab == 1 && searchInput != null && searchInput.isFocused()) {
            if (searchInput.keyPressed(event)) {
                refreshSearchDropdown();
                return true;
            }
        }
        if (tab == 1 && intervalInput != null && intervalInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                intervalInput.setFocused(false); sendSettings(); return true;
            }
            if (intervalInput.keyPressed(event)) return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean charTyped(CharacterEvent charEvent) {
        if (tab == 1 && searchInput != null && searchInput.isFocused()
                && searchInput.charTyped(charEvent)) {
            refreshSearchDropdown();
            return true;
        }
        if (tab == 1 && intervalInput != null && intervalInput.isFocused()
                && intervalInput.charTyped(charEvent)) return true;
        return super.charTyped(charEvent);
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

    // 26.1.2:renderTooltip 改名为 extractTooltip(仍在 AbstractContainerScreen 上,protected)。
    @Override protected void extractTooltip(GuiGraphicsExtractor g, int mx, int my) {
        // Do not allow EMI or vanilla tooltip callbacks to leak into the
        // settings page after its item panel has been hidden.
        if (tab == 1) return;
        super.extractTooltip(g, mx, my);
    }

    @Override public void removed() {
        RequesterEmiCompat.setSettingsScreen(false);
        sendSettings();
        super.removed();
    }

    private void drawText(GuiGraphicsExtractor g, Component text, int x, int y, int color) {
        g.text(font, text, leftPos + x, topPos + y, color, true);
    }
    private void drawText(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        drawText(g, Component.literal(text), x, y, color);
    }

    private boolean handleSearchClick(MouseButtonEvent event, boolean doubled) {
        double mx = event.x();
        double my = event.y();
        int searchX = screenX(RequesterLayoutDebug.Widget.SEARCH_INPUT, 8);
        int searchY = screenY(RequesterLayoutDebug.Widget.SEARCH_INPUT, 30);
        if (searchInput != null && inside(mx, my, searchX, searchY, 160, 14)
                && searchInput.mouseClicked(event, doubled)) {
            if (intervalInput != null) intervalInput.setFocused(false);
            searchInput.setFocused(true);
            refreshSearchDropdown();
            return true;
        }
        return false;
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int x, int y, int color, int maxWidth) {
        String value = font.plainSubstrByWidth(text, Math.max(1, maxWidth));
        g.text(font, value, leftPos + x - font.width(value) / 2, topPos + y, color, true);
    }
    private int buttonWidth(Component label, int min, int max) { return Mth.clamp(font.width(label) + 12, min, max); }
    private String trim(String value, int max) { return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "..."; }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void renderDebugOverlay(GuiGraphicsExtractor g) {
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
        g.pose().pushMatrix();
        g.nextStratum();
        RequesterLayoutDebug.renderOverlay(g, font, x, y, width, height);
        g.pose().popMatrix();
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
