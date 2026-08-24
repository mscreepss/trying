package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.GoofyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD sayfası - üç HUD tek yerden yönetilir.
 *
 * TUŞ ATAMASI YOK: HUD'lar buradaki anahtarlarla açılıp kapanır, konumları da
 * "Edit HUD Layout" butonuyla açılan sürükle-bırak ekranından ayarlanır. Bu
 * ekran bir Screen olduğu için fare imleci serbest kalır; kamera dönmez.
 */
public class HudTab {

    /** Tek satır: başlık + açıklama + anahtar. */
    private static class Row {
        final String title;
        final String description;
        final Widgets.Toggle toggle;

        Row(String title, String description, Widgets.Toggle toggle) {
            this.title = title;
            this.description = description;
            this.toggle = toggle;
        }
    }

    private static final int ROW_H = 34;

    private int x, y, w, h;
    private int cardY, cardH;
    private int rowsY;

    private final List<Row> rows = new ArrayList<>();
    private Widgets.Button editLayoutButton;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        cardY = y;
        cardH = 54;
        rowsY = cardY + cardH + 26;

        build();
    }

    private void build() {
        rows.clear();
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) return;

        editLayoutButton = new Widgets.Button("Edit HUD Layout",
                () -> Minecraft.getInstance().setScreen(new HudEditScreen()))
                .bounds(x + w - 130 - 12, cardY + 16, 130, 22)
                .accent(Theme.ACCENT).filled(true);

        rows.add(row("Profit HUD", "Spend, earn, profit and uptime",
                config.hudVisible, value -> config.hudVisible = value, 0));
        rows.add(row("Task HUD", "What each book line is doing right now",
                config.taskHudVisible, value -> config.taskHudVisible = value, 1));
        rows.add(row("State HUD", "One line: what the macro is doing",
                config.stateHudVisible, value -> config.stateHudVisible = value, 2));
    }

    private Row row(String title, String description, boolean value,
                    java.util.function.Consumer<Boolean> apply, int index) {
        Widgets.Toggle toggle = new Widgets.Toggle(value);
        toggle.bounds(x + w - toggle.w, rowsY + index * ROW_H + (ROW_H - toggle.h) / 2 - 4);
        toggle.onChange = () -> {
            apply.accept(toggle.value);
            GoofyConfig.save();
        };
        return new Row(title, description, toggle);
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (GoofyConfig.INSTANCE == null || editLayoutButton == null) {
            Draw.textCentered(g, "Config could not be loaded", x + w / 2, y + 40, Theme.RED);
            return;
        }

        Draw.panel(g, x, cardY, w, cardH, Theme.CARD, Theme.STROKE);
        Draw.text(g, "HUD Layout", x + 14, cardY + 14, Theme.TEXT);
        Draw.text(g, "Free the mouse and drag any HUD where you want it.",
                x + 14, cardY + 30, Theme.TEXT_FAINT);
        editLayoutButton.render(g, mouseX, mouseY);

        Draw.text(g, "VISIBILITY", x, rowsY - 16, Theme.TEXT_FAINT);
        Draw.hLine(g, x, rowsY - 5, w, Theme.STROKE);

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int ry = rowsY + i * ROW_H;

            if (Draw.inside(mouseX, mouseY, x, ry, w, ROW_H)) {
                Draw.rect(g, x - 6, ry, w + 12, ROW_H, Theme.CARD);
            }

            Draw.text(g, row.title, x, ry + 7, row.toggle.value ? Theme.TEXT : Theme.TEXT_DIM);
            Draw.text(g, row.description, x, ry + 19, Theme.TEXT_FAINT);
            row.toggle.render(g, mouseX, mouseY);

            if (i < rows.size() - 1) Draw.hLine(g, x, ry + ROW_H - 1, w, Theme.STROKE);
        }

        int noteY = rowsY + rows.size() * ROW_H + 22;
        Draw.text(g, "NOTE", x, noteY, Theme.TEXT_FAINT);
        Draw.hLine(g, x, noteY + 11, w, Theme.STROKE);
        Draw.text(g, "HUDs have no key binding on purpose - everything is handled here.",
                x, noteY + 19, Theme.TEXT_DIM);
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY) {
        if (editLayoutButton != null && editLayoutButton.mouseClicked(mouseX, mouseY)) return true;
        for (Row row : rows) {
            if (row.toggle.mouseClicked(mouseX, mouseY)) return true;
        }
        return false;
    }
}
