package com.goofy.goofyaddons.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Minimal widget seti. Vanilla Button/EditBox kullanmıyoruz: hem görünüm tamamen
 * bize ait olsun, hem de sürüm sürüm değişen widget API'lerine bağımlı kalmayalım.
 * Hepsi sadece Draw üzerinden çizer.
 */
public final class Widgets {

    private Widgets() {
    }

    // =====================================================================
    // Buton
    // =====================================================================
    public static class Button {
        public int x, y, w, h;
        public String label;
        public Runnable onClick;
        /** Vurgu rengi (yazı + hover tonu). */
        public int accent = Theme.ACCENT;
        /** Dolu buton mu, yoksa sadece çerçeveli mi? */
        public boolean filled = false;
        public boolean enabled = true;
        public boolean visible = true;

        public Button(String label, Runnable onClick) {
            this.label = label;
            this.onClick = onClick;
        }

        public Button bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public Button accent(int accent) {
            this.accent = accent;
            return this;
        }

        public Button filled(boolean filled) {
            this.filled = filled;
            return this;
        }

        public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = enabled && Draw.inside(mouseX, mouseY, x, y, w, h);

            int bg;
            int fg;
            if (!enabled) {
                bg = Theme.INSET;
                fg = Theme.TEXT_FAINT;
            } else if (filled) {
                bg = hover ? Draw.mix(accent, 0xFFFFFFFF, 0.15f) : accent;
                fg = 0xFF10131A;
            } else {
                bg = hover ? Theme.HOVER : Theme.INSET;
                fg = accent;
            }

            Draw.roundRect(g, x, y, w, h, bg);
            if (!filled && enabled) {
                // hover'da ince bir vurgu şeridi
                Draw.rect(g, x + 1, y + h - 1, w - 2, 1, hover ? accent : Theme.STROKE);
            }
            Draw.textCentered(g, label, x + w / 2, y + (h - 8) / 2, fg);
        }

        public boolean mouseClicked(double mouseX, double mouseY) {
            if (!visible || !enabled) return false;
            if (!Draw.inside(mouseX, mouseY, x, y, w, h)) return false;
            if (onClick != null) onClick.run();
            return true;
        }
    }

    // =====================================================================
    // Metin kutusu (sayı ya da serbest metin)
    // =====================================================================
    public static class TextBox {
        public int x, y, w, h;
        public String value = "";
        public String placeholder = "";
        public boolean numeric = false;
        public boolean focused = false;
        public int maxLength = 64;
        /** Değer her değiştiğinde çağrılır (canlı kaydetmek için). */
        public Runnable onChange;

        private int caretTimer = 0;

        public TextBox(String value) {
            this.value = value == null ? "" : value;
        }

        public TextBox bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public TextBox numeric(boolean numeric) {
            this.numeric = numeric;
            return this;
        }

        public TextBox placeholder(String placeholder) {
            this.placeholder = placeholder;
            return this;
        }

        public int intValue(int fallback) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        public void tick() {
            caretTimer++;
        }

        public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            boolean hover = Draw.inside(mouseX, mouseY, x, y, w, h);
            int stroke = focused ? Theme.ACCENT : (hover ? Theme.HOVER : Theme.STROKE);
            Draw.panel(g, x, y, w, h, Theme.INSET, stroke);

            int textY = y + (h - 8) / 2;
            String shown = value.isEmpty() && !focused ? placeholder : value;
            int color = value.isEmpty() && !focused ? Theme.TEXT_FAINT : Theme.TEXT;

            String clipped = Draw.clip(shown, w - 12);
            Draw.text(g, clipped, x + 6, textY, color);

            if (focused && (caretTimer / 6) % 2 == 0) {
                int caretX = x + 6 + Draw.textWidth(clipped);
                Draw.rect(g, Math.min(caretX, x + w - 5), textY - 1, 1, 10, Theme.ACCENT);
            }
        }

        public boolean mouseClicked(double mouseX, double mouseY) {
            boolean hit = Draw.inside(mouseX, mouseY, x, y, w, h);
            focused = hit;
            if (hit) caretTimer = 0;
            return hit;
        }

        /** Backspace vb. Değer değiştiyse true döner. */
        public boolean keyPressed(int keyCode) {
            if (!focused) return false;
            // 259 = BACKSPACE, 257 = ENTER, 335 = KP_ENTER
            if (keyCode == 259) {
                if (!value.isEmpty()) {
                    value = value.substring(0, value.length() - 1);
                    if (onChange != null) onChange.run();
                }
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                focused = false;
                return true;
            }
            return false;
        }

        public boolean charTyped(char chr) {
            if (!focused) return false;
            if (value.length() >= maxLength) return true;
            if (numeric) {
                if (chr < '0' || chr > '9') return true;
            } else if (chr < 32 || chr == 127) {
                return true;
            }
            value = value + chr;
            if (onChange != null) onChange.run();
            return true;
        }
    }

    // =====================================================================
    // Aç/kapa anahtarı
    // =====================================================================
    public static class Toggle {
        public int x, y;
        public int w = 26;
        public int h = 13;
        public boolean value;
        public Runnable onChange;

        public Toggle(boolean value) {
            this.value = value;
        }

        public Toggle bounds(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            boolean hover = Draw.inside(mouseX, mouseY, x, y, w, h);
            int track = value ? Theme.GREEN_SOFT : Theme.INSET;
            Draw.roundRect(g, x, y, w, h, track);
            if (hover) Draw.rect(g, x + 1, y + h - 1, w - 2, 1, value ? Theme.GREEN : Theme.STROKE);

            int knobW = 9;
            int knobX = value ? x + w - knobW - 2 : x + 2;
            Draw.roundRect(g, knobX, y + 2, knobW, h - 4, value ? Theme.GREEN : Theme.TEXT_DIM);
        }

        public boolean mouseClicked(double mouseX, double mouseY) {
            if (!Draw.inside(mouseX, mouseY, x, y, w, h)) return false;
            value = !value;
            if (onChange != null) onChange.run();
            return true;
        }
    }
}
