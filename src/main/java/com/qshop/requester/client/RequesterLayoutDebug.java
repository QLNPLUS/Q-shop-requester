package com.qshop.requester.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import com.qshop.requester.RequesterConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** Layout offsets for the requester GUI, matching Q-shop sellbox's adjustment workflow. */
public final class RequesterLayoutDebug {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "qshop_requester_layout.json";
    private static final int MAX_OFFSET = 512;
    private static final EnumMap<Widget, Position> DEFAULTS = defaults();
    private static final EnumMap<Widget, Position> POSITIONS = new EnumMap<>(Widget.class);
    private static Widget selected = Widget.ITEM_PURCHASE_TITLE;
    private static boolean enabled;
    private static boolean loaded;

    public enum Widget {
        ITEM_PURCHASE_TITLE("Purchased items title", 0),
        ITEM_SUPPLY_TITLE("Player supplies title", 0),
        ITEM_INVENTORY("Inventory label", 0),
        TAB_ITEMS("Items tab", -1),
        TAB_SETTINGS("Settings tab", -1),
        SETTINGS_TITLE("Settings title", 1),
        OWNER_AVATAR("Owner avatar", 1),
        OWNER_INFO("Owner name/status", 1),
        OWNER_BUTTON("Claim owner button", 1),
        SEARCH_INPUT("Target search input", 1),
        TARGET_BUTTON("Target button", 1),
        SELECTED_INFO("Selected target info", 1),
        INTERVAL_LABEL("Interval label", 1),
        INTERVAL_INPUT("Interval input", 1),
        INTERVAL_UNIT("Interval unit button", 1),
        ACTION_BAR_NOTIFICATION("Action Bar checkbox", 1),
        CHAT_NOTIFICATION("Chat checkbox", 1);

        private final String label;
        private final int tab;

        Widget(String label, int tab) {
            this.label = label;
            this.tab = tab;
        }

        public String label() { return label; }
        private boolean visibleOn(int currentTab) { return tab < 0 || tab == currentTab; }
    }

    private RequesterLayoutDebug() {}

    public static void beginScreen() {
        loaded = false;
        load();
    }

    public static boolean isConfiguredEnabled() { return RequesterConfig.layoutDebugEnabled(); }
    public static boolean isEnabled() { return enabled && isConfiguredEnabled(); }

    public static void toggle() {
        if (!isConfiguredEnabled()) return;
        load();
        enabled = !enabled;
    }

    public static Widget selected() { return selected; }

    public static void ensureSelected(int tab) {
        if (selected.visibleOn(tab)) return;
        for (Widget widget : Widget.values()) {
            if (widget.visibleOn(tab)) {
                selected = widget;
                return;
            }
        }
    }

    public static void selectNext(int tab, boolean reverse) {
        List<Widget> visible = new ArrayList<>();
        for (Widget widget : Widget.values()) if (widget.visibleOn(tab)) visible.add(widget);
        if (visible.isEmpty()) return;
        int index = visible.indexOf(selected);
        int next = index < 0 ? 0 : Math.floorMod(index + (reverse ? -1 : 1), visible.size());
        selected = visible.get(next);
    }

    public static int x(Widget widget, int normal) { return normal + position(widget).x(); }
    public static int y(Widget widget, int normal) { return normal + position(widget).y(); }

    public static void moveSelected(int tab, int dx, int dy) {
        if (!selected.visibleOn(tab)) selectNext(tab, false);
        Position current = position(selected);
        POSITIONS.put(selected, new Position(clamp(current.x() + dx), clamp(current.y() + dy)));
        save();
    }

    public static void renderOverlay(GuiGraphics graphics, Font font, int x, int y, int width, int height) {
        int right = x + Math.max(1, width);
        int bottom = y + Math.max(1, height);
        graphics.fill(x, y, right, y + 1, 0xFFFFD54F);
        graphics.fill(x, bottom - 1, right, bottom, 0xFFFFD54F);
        graphics.fill(x, y, x + 1, bottom, 0xFFFFD54F);
        graphics.fill(right - 1, y, right, bottom, 0xFFFFD54F);
        String label = "F8 Debug | Tab: " + selected.label() + " | arrows: 5px | Alt: 1px";
        String offset = "offset " + position(selected).x() + ", " + position(selected).y();
        int textWidth = Math.max(font.width(label), font.width(offset));
        graphics.fill(2, 2, textWidth + 8, font.lineHeight * 2 + 7, 0xCC111111);
        graphics.drawString(font, Component.literal(label), 4, 4, 0xFFFFD54F, false);
        graphics.drawString(font, Component.literal(offset), 4, 4 + font.lineHeight,
                0xFFFFFFFF, false);
    }

    private static EnumMap<Widget, Position> defaults() {
        EnumMap<Widget, Position> values = new EnumMap<>(Widget.class);
        for (Widget widget : Widget.values()) values.put(widget, new Position(0, 0));
        values.put(Widget.SETTINGS_TITLE, new Position(0, 1));
        values.put(Widget.OWNER_AVATAR, new Position(-5, 3));
        values.put(Widget.OWNER_INFO, new Position(-6, -1));
        values.put(Widget.OWNER_BUTTON, new Position(-28, 5));
        values.put(Widget.SEARCH_INPUT, new Position(0, 1));
        values.put(Widget.TARGET_BUTTON, new Position(0, 2));
        values.put(Widget.SELECTED_INFO, new Position(0, 1));
        values.put(Widget.INTERVAL_LABEL, new Position(0, -1));
        values.put(Widget.INTERVAL_INPUT, new Position(0, -4));
        values.put(Widget.INTERVAL_UNIT, new Position(1, -3));
        values.put(Widget.ACTION_BAR_NOTIFICATION, new Position(0, -4));
        values.put(Widget.CHAT_NOTIFICATION, new Position(0, -3));
        return values;
    }

    private static Position position(Widget widget) {
        return POSITIONS.getOrDefault(widget, DEFAULTS.get(widget));
    }

    private static int clamp(int value) { return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, value)); }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        POSITIONS.clear();
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("widgets")) return;
            JsonObject widgets = root.getAsJsonObject().getAsJsonObject("widgets");
            for (Widget widget : Widget.values()) {
                JsonElement raw = widgets.get(widget.name().toLowerCase(Locale.ROOT));
                if (raw == null || !raw.isJsonObject()) continue;
                JsonObject value = raw.getAsJsonObject();
                POSITIONS.put(widget, new Position(clamp(readInt(value, "x")), clamp(readInt(value, "y"))));
            }
        } catch (Exception exception) {
            System.err.println("[qshop_requester] Could not read layout debug JSON: " + exception.getMessage());
        }
    }

    private static int readInt(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsInt() : 0;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("description", "Component offsets relative to the normal requester GUI layout.");
        JsonObject widgets = new JsonObject();
        for (Widget widget : Widget.values()) {
            Position value = position(widget);
            JsonObject offset = new JsonObject();
            offset.addProperty("x", value.x());
            offset.addProperty("y", value.y());
            widgets.add(widget.name().toLowerCase(Locale.ROOT), offset);
        }
        root.add("widgets", widgets);
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) { GSON.toJson(root, writer); }
        } catch (IOException exception) {
            System.err.println("[qshop_requester] Could not save layout debug JSON: " + exception.getMessage());
        }
    }

    private record Position(int x, int y) {}
}
