package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.ui.Draw;
import com.goofy.goofyaddons.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Üç HUD'un ortak görsel dili.
 *
 * Vanilla'nın taş dokulu / bevel'li kutularına hiç benzemesin diye hepsi aynı
 * kalıptan çıkıyor: koyu düz panel, 1px ince kenarlık, solda ince renk şeridi,
 * üstte küçük büyük-harf başlık. Tek yerden değiştirilebilsin diye burada.
 */
public final class HudStyle {

    public static final int PAD_X = 9;
    public static final int PAD_Y = 7;
    public static final int LINE = 11;
    public static final int HEADER_H = 14;

    private HudStyle() {
    }

    /** Panel + kenarlık + sol renk şeridi. highlight = taşıma modunda vurgu. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h,
                             int accent, boolean highlight) {
        Draw.panel(g, x, y, w, h, Theme.HUD_BG, highlight ? Theme.ACCENT : Theme.HUD_STROKE);
        Draw.rect(g, x + 1, y + 2, 2, h - 4, accent);
    }

    /** Sol üstte küçük başlık, sağ üstte küçük not. */
    public static void header(GuiGraphicsExtractor g, int x, int y, int w,
                              String title, String note, int accent) {
        Draw.text(g, title, x + PAD_X, y + PAD_Y, accent);
        if (note != null && !note.isEmpty()) {
            Draw.textRight(g, note, x + w - PAD_X, y + PAD_Y, Theme.TEXT_FAINT);
        }
        Draw.hLine(g, x + PAD_X, y + PAD_Y + 11, w - PAD_X * 2, Theme.STROKE);
    }

    /** Solda etiket, sağda değer - iki kolon hizalı satır. */
    public static void row(GuiGraphicsExtractor g, int left, int right, int y,
                           String label, String value, int labelColor, int valueColor) {
        Draw.text(g, label, left, y, labelColor);
        Draw.textRight(g, value, right, y, valueColor);
    }

    /** Küçük durum ışığı. */
    public static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
        Draw.dot(g, x, y, color);
    }
}
