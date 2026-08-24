package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.BookPresets;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Presets sayfası - kendi sekmesi, config'in içinde değil.
 *
 * BOŞ BAŞLAR: Buraya otomatik hiçbir şey eklenmez. İçini iki yoldan
 * doldurabilirsin:
 *   - "Save Active Books"  : şu an Books sayfasındaki hatları dosyaya yazar
 *   - config/goofyaddons_presets.json dosyasını elle düzenleyip "Reload File"
 *
 * Bir satıra tıklamak onu aktif kitap listesine ekler; zaten ekliyse "added"
 * yazar ve tıklama bir şey yapmaz.
 */
public class PresetsTab {

    private static final int ROW_H = 26;
    private static final int HEADER_H = 26;

    private int x, y, w, h;
    private int listY, listH;
    private int addBtnX, addBtnW, removeBtnX, removeBtnW, rowBtnH;
    private int scroll = 0;

    private Widgets.Button reloadButton;
    private Widgets.Button saveActiveButton;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        listY = y + HEADER_H + 18;
        listH = Math.max(60, (y + h) - listY);

        rowBtnH = 15;
        removeBtnW = 52;
        addBtnW = 44;
        removeBtnX = x + w - 10 - removeBtnW;
        addBtnX = removeBtnX - 8 - addBtnW;

        reloadButton = new Widgets.Button("Reload File", () -> {
            BookPresets.load();
            scroll = 0;
        }).bounds(x + w - 202, y + 2, 96, 20).accent(Theme.ACCENT);

        saveActiveButton = new Widgets.Button("Save Active Books", this::saveActive)
                .bounds(x + w - 100, y + 2, 100, 20).accent(Theme.GREEN);
    }

    private void saveActive() {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) return;
        for (Book book : config.books) BookPresets.addFromBook(book);
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<BookPresets.Preset> presets = BookPresets.all();

        Draw.text(g, "SAVED PRESETS", x, y + 2, Theme.TEXT);
        Draw.text(g, BookPresets.status(), x, y + 14, Theme.TEXT_FAINT);

        reloadButton.render(g, mouseX, mouseY);
        saveActiveButton.render(g, mouseX, mouseY);

        Draw.hLine(g, x, listY - 8, w, Theme.STROKE);
        Draw.panel(g, x, listY, w, listH, Theme.CARD, Theme.STROKE);

        if (presets.isEmpty()) {
            int cy = listY + listH / 2;
            Draw.textCentered(g, "This page is empty", x + w / 2, cy - 16, Theme.TEXT_DIM);
            Draw.textCentered(g, "Use 'Save Active Books' to store your current setup,",
                    x + w / 2, cy, Theme.TEXT_FAINT);
            Draw.textCentered(g, "or edit config/goofyaddons_presets.json and press 'Reload File'.",
                    x + w / 2, cy + 12, Theme.TEXT_FAINT);
            return;
        }

        int visible = visibleRows();
        scroll = clamp(scroll, 0, Math.max(0, presets.size() - visible));

        for (int i = 0; i < visible && (i + scroll) < presets.size(); i++) {
            BookPresets.Preset preset = presets.get(i + scroll);
            int ry = listY + 2 + i * ROW_H;
            boolean already = GoofyConfig.hasBook(preset.id, preset.level, -1);

            if (Draw.inside(mouseX, mouseY, x + 1, ry, w - 2, ROW_H - 1)) {
                Draw.rect(g, x + 1, ry, w - 2, ROW_H - 1, Theme.HOVER);
            }
            Draw.rect(g, x + 1, ry + 5, 2, ROW_H - 11, Theme.ACCENT_SOFT);

            String title = preset.name + "   " + roman(preset.level) + " \u2192 " + roman(preset.sellLevel);
            int room = addBtnX - x - 24;
            Draw.text(g, Draw.clip(title, room), x + 12, ry + 4, already ? Theme.TEXT_FAINT : Theme.TEXT);
            Draw.text(g, Draw.clip(preset.id, room), x + 12, ry + 14, Theme.TEXT_FAINT);

            if (already) {
                Draw.textRight(g, "added", addBtnX + addBtnW, ry + 9, Theme.TEXT_FAINT);
            } else {
                miniButton(g, "Add", addBtnX, ry + 5, addBtnW, mouseX, mouseY, Theme.GREEN);
            }
            miniButton(g, "Remove", removeBtnX, ry + 5, removeBtnW, mouseX, mouseY, Theme.RED);
        }

        int maxScroll = Math.max(0, presets.size() - visible);
        if (maxScroll > 0) {
            int barH = Math.max(14, listH * visible / Math.max(1, presets.size()));
            int barY = listY + (listH - barH) * scroll / maxScroll;
            Draw.rect(g, x + w - 3, barY, 2, barH, Theme.STROKE);
        }
    }

    private void miniButton(GuiGraphicsExtractor g, String label, int bx, int by, int bw,
                            int mouseX, int mouseY, int accent) {
        boolean hover = Draw.inside(mouseX, mouseY, bx, by, bw, rowBtnH);
        Draw.roundRect(g, bx, by, bw, rowBtnH, hover ? Theme.HOVER : Theme.INSET);
        Draw.textCentered(g, label, bx + bw / 2, by + 4, hover ? accent : Theme.TEXT_DIM);
    }

    private int visibleRows() {
        return Math.max(1, (listH - 4) / ROW_H);
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY) {
        if (reloadButton.mouseClicked(mouseX, mouseY)) return true;
        if (saveActiveButton.mouseClicked(mouseX, mouseY)) return true;

        List<BookPresets.Preset> presets = BookPresets.all();
        int visible = visibleRows();

        for (int i = 0; i < visible && (i + scroll) < presets.size(); i++) {
            int index = i + scroll;
            int ry = listY + 2 + i * ROW_H;
            BookPresets.Preset preset = presets.get(index);

            if (Draw.inside(mouseX, mouseY, removeBtnX, ry + 5, removeBtnW, rowBtnH)) {
                BookPresets.remove(index);
                return true;
            }
            if (Draw.inside(mouseX, mouseY, addBtnX, ry + 5, addBtnW, rowBtnH)) {
                if (!GoofyConfig.hasBook(preset.id, preset.level, -1)) {
                    GoofyConfig.addBook(preset.toBook());
                }
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double direction) {
        scroll = Math.max(0, scroll - (int) Math.signum(direction));
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            default -> String.valueOf(level);
        };
    }
}
