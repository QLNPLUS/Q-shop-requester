package com.qshop.requester.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class RequesterTextures {
    public static final Identifier BACKGROUND = texture("background.png");
    public static final Identifier OWNER_BACKGROUND = texture("owner_background.png");
    public static final Identifier TABS = texture("tabs.png");
    public static final Identifier BUTTON = texture("button.png");
    public static final Identifier BUTTON_DISABLED = texture("button_disabled.png");
    public static final Identifier BUTTON_HOVER = texture("button_hover.png");
    public static final Identifier INPUT = texture("input.png");
    public static final Identifier INPUT_FOCUS = texture("input_focus.png");
    public static final Identifier DROPDOWN = texture("dropdown.png");
    public static final Identifier ENTRY_HIGHLIGHT = texture("entry_highlight.png");
    public static final Identifier CHECKBOX_ON = texture("checkbox_on.png");
    public static final Identifier CHECKBOX_ON_HOVER = texture("checkbox_on_hover.png");
    public static final Identifier CHECKBOX_OFF = texture("checkbox_off.png");
    public static final Identifier CHECKBOX_OFF_HOVER = texture("checkbox_off_hover.png");

    private RequesterTextures() {}
    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("qshop_requester", "textures/gui/" + name);
    }

    public static void background(GuiGraphicsExtractor g, int x, int y) {
        g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, 0, 0, 176, 166, 176, 166);
    }
    public static void ownerBackground(GuiGraphicsExtractor g, int x, int y, int height) {
        g.blit(RenderPipelines.GUI_TEXTURED, OWNER_BACKGROUND, x, y, 0, 0, 176, height, 176, height);
    }
    public static void tab(GuiGraphicsExtractor g, int x, int y, int column, boolean selected) {
        int sx = Math.max(0, Math.min(column, 6)) * 26;
        g.blit(RenderPipelines.GUI_TEXTURED, TABS, x, y, sx, selected ? 32 : 0, 26, 32, 182, 128);
    }
    public static void input(GuiGraphicsExtractor g, int x, int y, int width, int height, boolean focused) {
        blitNine(g, focused ? INPUT_FOCUS : INPUT, x, y, width, height, 96, 12, 2);
    }
    public static void dropdown(GuiGraphicsExtractor g, int x, int y) {
        g.blit(RenderPipelines.GUI_TEXTURED, DROPDOWN, x, y, 0, 0, 160, 94, 160, 94);
    }
    public static void entryHighlight(GuiGraphicsExtractor g, int x, int y) {
        g.blit(RenderPipelines.GUI_TEXTURED, ENTRY_HIGHLIGHT, x, y, 0, 0, 160, 18, 160, 18);
    }
    public static void checkbox(GuiGraphicsExtractor g, int x, int y, boolean checked, boolean hovered) {
        Identifier t = checked ? (hovered ? CHECKBOX_ON_HOVER : CHECKBOX_ON)
                : (hovered ? CHECKBOX_OFF_HOVER : CHECKBOX_OFF);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y, 0, 0, 12, 12, 12, 12);
    }
    public static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hovered, boolean enabled) {
        Identifier texture = !enabled ? BUTTON_DISABLED : hovered ? BUTTON_HOVER : BUTTON;
        blitNine(g, texture, x, y, w, h, 60, 16, 4);
    }
    private static void blitNine(GuiGraphicsExtractor g, Identifier t, int x, int y, int w, int h,
                                 int tw, int th, int border) {
        int iw = tw - border * 2, ih = th - border * 2;
        int mw = w - border * 2, mh = h - border * 2;
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y, 0, 0, border, border, tw, th);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - border, y, tw - border, 0, border, border, tw, th);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y + h - border, 0, th - border, border, border, tw, th);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - border, y + h - border, tw - border, th - border,
                border, border, tw, th);
        // 26.1.2 的 10 参 blit 顺序是 (x, y, u, v, width, height, srcW, srcH, texW, texH),
        // 而 1.21.1 的 10 参重载是 (x, y, width, height, u, v, srcW, srcH, texW, texH)。
        // 两者只是把 u,v 与 width,height 前后调换 —— 写错仍能编译,但九宫格中段会塌成 0 高度。
        if (mw > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, t, x + border, y, border, 0, mw, border, iw, border, tw, th);
            g.blit(RenderPipelines.GUI_TEXTURED, t, x + border, y + h - border, border, th - border, mw, border, iw, border, tw, th);
        }
        if (mh > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, t, x, y + border, 0, border, border, mh, border, ih, tw, th);
            g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - border, y + border, tw - border, border, border, mh, border, ih, tw, th);
        }
        if (mw > 0 && mh > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, t, x + border, y + border, border, border, mw, mh,
                    iw, ih, tw, th);
        }
    }
}
