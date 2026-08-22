package com.goofy.goofyaddons.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * TÜM çizim çağrıları buradan geçer.
 *
 * ÖNEMLİ: Projedeki tek "Minecraft çizim API'sine dokunan" dosya budur. Eğer
 * derleyici GuiGraphicsExtractor tipinden şikayet ederse (Screen#render farklı bir
 * tip alıyorsa), sadece bu dosyadaki import + metot imzalarını ve ekranların
 * render(...) imzasını değiştirmen yeterli; geri kalan UI kodu hiç değişmez.
 *
 * Kullanılan ilkel çağrılar yalnızca üç tane: fill, outline, text.
 */
public final class Draw {

    private Draw() {
    }

    public static int textWidth(String text) {
        return Minecraft.getInstance().font.width(text);
    }

    public static int lineHeight() {
        return Minecraft.getInstance().font.lineHeight;
    }

    public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, color);
    }

    /**
     * Köşeleri 1 piksel kırpılmış dikdörtgen. Minecraft'ta gerçek yuvarlatma yok;
     * köşe pikselini atlamak bile "bloklu" hissi ciddi biçimde kırıyor.
     */
    public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w <= 2 || h <= 2) {
            rect(g, x, y, w, h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Dolgu + 1px kenarlık. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int stroke) {
        roundRect(g, x, y, w, h, stroke);
        roundRect(g, x + 1, y + 1, w - 2, h - 2, fill);
    }

    public static void hLine(GuiGraphicsExtractor g, int x, int y, int w, int color) {
        rect(g, x, y, w, 1, color);
    }

    public static void vLine(GuiGraphicsExtractor g, int x, int y, int h, int color) {
        rect(g, x, y, 1, h, color);
    }

    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(Minecraft.getInstance().font, s, x, y, color, false);
    }

    public static void textCentered(GuiGraphicsExtractor g, String s, int centerX, int y, int color) {
        text(g, s, centerX - textWidth(s) / 2, y, color);
    }

    public static void textRight(GuiGraphicsExtractor g, String s, int rightX, int y, int color) {
        text(g, s, rightX - textWidth(s), y, color);
    }

    /** Verilen genişliğe sığmıyorsa sonuna "..." koyarak kısaltır. */
    public static String clip(String s, int maxWidth) {
        if (textWidth(s) <= maxWidth) return s;
        String out = s;
        while (out.length() > 1 && textWidth(out + "...") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "...";
    }

    /** Küçük renkli etiket (durum rozeti). */
    public static void badge(GuiGraphicsExtractor g, String label, int x, int y, int textColor, int bgColor) {
        int w = textWidth(label) + 10;
        int h = 12;
        roundRect(g, x, y, w, h, bgColor);
        text(g, label, x + 5, y + 2, textColor);
    }

    public static int badgeWidth(String label) {
        return textWidth(label) + 10;
    }

    /** İçi dolu daire yerine 3x3 nokta - durum ışığı için yeterli ve keskin durur. */
    public static void dot(GuiGraphicsExtractor g, int x, int y, int color) {
        rect(g, x + 1, y, 2, 4, color);
        rect(g, x, y + 1, 4, 2, color);
    }

    public static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** İki rengi karıştırır (hover geçişleri için). */
    public static int mix(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) (aa + (ba - aa) * t);
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
