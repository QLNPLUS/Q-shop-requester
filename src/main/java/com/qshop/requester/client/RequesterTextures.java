package com.qshop.requester.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class RequesterTextures {
    public static final ResourceLocation BACKGROUND = texture("background.png");
    public static final ResourceLocation OWNER_BACKGROUND = texture("owner_background.png");
    public static final ResourceLocation TABS = texture("tabs.png");
    public static final ResourceLocation BUTTON = texture("button.png");
    public static final ResourceLocation BUTTON_DISABLED = texture("button_disabled.png");
    public static final ResourceLocation BUTTON_HOVER = texture("button_hover.png");
    public static final ResourceLocation INPUT = texture("input.png");
    public static final ResourceLocation INPUT_FOCUS = texture("input_focus.png");
    public static final ResourceLocation DROPDOWN = texture("dropdown.png");
    public static final ResourceLocation ENTRY_HIGHLIGHT = texture("entry_highlight.png");
    public static final ResourceLocation CHECKBOX_ON = texture("checkbox_on.png");
    public static final ResourceLocation CHECKBOX_ON_HOVER = texture("checkbox_on_hover.png");
    public static final ResourceLocation CHECKBOX_OFF = texture("checkbox_off.png");
    public static final ResourceLocation CHECKBOX_OFF_HOVER = texture("checkbox_off_hover.png");

    private RequesterTextures() {}
    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("qshop_requester", "textures/gui/" + name);
    }

    public static void background(GuiGraphics g, int x, int y) { g.blit(BACKGROUND, x, y, 0, 0, 176, 166, 176, 166); }
    public static void ownerBackground(GuiGraphics g, int x, int y) { g.blit(OWNER_BACKGROUND, x, y, 0, 0, 176, 166, 176, 166); }
    public static void tab(GuiGraphics g, int x, int y, int column, boolean selected) {
        int sx = Math.max(0, Math.min(column, 6)) * 26;
        g.blit(TABS, x, y, sx, selected ? 32 : 0, 26, 32, 182, 128);
    }
    public static void input(GuiGraphics g, int x, int y, int width, int height, boolean focused) {
        blitNine(g, focused ? INPUT_FOCUS : INPUT, x, y, width, height, 96, 12, 2);
    }
    public static void dropdown(GuiGraphics g, int x, int y) { g.blit(DROPDOWN, x, y, 0, 0, 160, 94, 160, 94); }
    public static void entryHighlight(GuiGraphics g, int x, int y) {
        g.blit(ENTRY_HIGHLIGHT, x, y, 0, 0, 160, 18, 160, 18);
    }
    public static void checkbox(GuiGraphics g, int x, int y, boolean checked, boolean hovered) {
        ResourceLocation t = checked ? (hovered ? CHECKBOX_ON_HOVER : CHECKBOX_ON)
                : (hovered ? CHECKBOX_OFF_HOVER : CHECKBOX_OFF);
        g.blit(t, x, y, 0, 0, 12, 12, 12, 12);
    }
    public static void button(GuiGraphics g, int x, int y, int w, int h, boolean hovered, boolean enabled) {
        ResourceLocation texture = !enabled ? BUTTON_DISABLED : hovered ? BUTTON_HOVER : BUTTON;
        blitNine(g, texture, x, y, w, h, 60, 16, 4);
    }
    private static void blitNine(GuiGraphics g, ResourceLocation t, int x, int y, int w, int h,
                                 int tw, int th, int border) {
        int iw = tw - border * 2, ih = th - border * 2;
        int mw = w - border * 2, mh = h - border * 2;
        g.blit(t, x, y, 0, 0, border, border, tw, th);
        g.blit(t, x + w - border, y, tw - border, 0, border, border, tw, th);
        g.blit(t, x, y + h - border, 0, th - border, border, border, tw, th);
        g.blit(t, x + w - border, y + h - border, tw - border, th - border,
                border, border, tw, th);
        if (mw > 0) {
            g.blit(t, x + border, y, mw, border, border, 0, iw, border, tw, th);
            g.blit(t, x + border, y + h - border, mw, border, border, th - border, iw, border, tw, th);
        }
        if (mh > 0) {
            g.blit(t, x, y + border, border, mh, 0, border, border, ih, tw, th);
            g.blit(t, x + w - border, y + border, border, mh, tw - border, border, border, ih, tw, th);
        }
        if (mw > 0 && mh > 0) g.blit(t, x + border, y + border, mw, mh,
                border, border, iw, ih, tw, th);
    }
}
