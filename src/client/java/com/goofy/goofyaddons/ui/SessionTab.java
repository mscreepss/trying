package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.failsafes.FailsafeManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Session planlayıcı sayfası (sol menüde KENDİ sekmesi - Config'in içinde değil).
 *
 * Makro rastgele bir süre çalışır, rastgele bir süre mola verir, kaldığı yerden
 * devam eder. Süre aralıklarının hepsi buradan girilir.
 *
 * BETA: Varsayılan kapalı. Kullanıcı açıkça açmadan hiçbir şey yapmaz.
 */
public class SessionTab {

    private static final int ROW_H = 24;
    private static final int BOX_W = 46;

    private int x, y, w, h;
    private int cardY, cardH;
    private int rowsY;
    private int fieldRightX;

    private final List<Widgets.TextBox> boxes = new ArrayList<>();
    private Widgets.TextBox workMinBox, workMaxBox, breakMinBox, breakMaxBox;
    private Widgets.Toggle enabledToggle;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        cardY = y;
        cardH = 56;
        rowsY = cardY + cardH + 18;
        fieldRightX = x + w;

        build();
    }

    private void build() {
        boxes.clear();
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) return;

        enabledToggle = new Widgets.Toggle(config.sessionPlannerEnabled);
        enabledToggle.bounds(x + w - 12 - enabledToggle.w, cardY + 13);
        enabledToggle.onChange = () -> {
            config.sessionPlannerEnabled = enabledToggle.value;
            GoofyConfig.save();
        };

        workMinBox = minuteBox(config.workMinMinutes, value -> {
            config.workMinMinutes = Math.max(1, value);
            fixRanges(config);
        });
        workMaxBox = minuteBox(config.workMaxMinutes, value -> {
            config.workMaxMinutes = Math.max(2, value);
            fixRanges(config);
        });
        breakMinBox = minuteBox(config.breakMinMinutes, value -> {
            config.breakMinMinutes = Math.max(1, value);
            fixRanges(config);
        });
        breakMaxBox = minuteBox(config.breakMaxMinutes, value -> {
            config.breakMaxMinutes = Math.max(2, value);
            fixRanges(config);
        });

        int pairRight = fieldRightX;
        int maxX = pairRight - BOX_W;
        int minX = maxX - 14 - BOX_W;

        workMinBox.bounds(minX, rowsY + 2, BOX_W, 16);
        workMaxBox.bounds(maxX, rowsY + 2, BOX_W, 16);
        breakMinBox.bounds(minX, rowsY + ROW_H + 2, BOX_W, 16);
        breakMaxBox.bounds(maxX, rowsY + ROW_H + 2, BOX_W, 16);

        boxes.add(workMinBox);
        boxes.add(workMaxBox);
        boxes.add(breakMinBox);
        boxes.add(breakMaxBox);
    }

    /**
     * min >= max olursa Humanizer.gaussian sonsuza kadar aynı değeri döndürür ve
     * "rastgele" özelliği ölür. Kod tarafında koruma var ama kullanıcıya doğrusunu
     * göstermek için burada da düzeltiyoruz (delay kutularındaki mantığın aynısı).
     */
    private void fixRanges(GoofyConfig config) {
        if (config.workMaxMinutes <= config.workMinMinutes) {
            config.workMaxMinutes = config.workMinMinutes + 10;
            if (workMaxBox != null && !workMaxBox.focused) {
                workMaxBox.value = String.valueOf(config.workMaxMinutes);
            }
        }
        if (config.breakMaxMinutes <= config.breakMinMinutes) {
            config.breakMaxMinutes = config.breakMinMinutes + 5;
            if (breakMaxBox != null && !breakMaxBox.focused) {
                breakMaxBox.value = String.valueOf(config.breakMaxMinutes);
            }
        }
    }

    private Widgets.TextBox minuteBox(int value, java.util.function.IntConsumer apply) {
        Widgets.TextBox box = new Widgets.TextBox(String.valueOf(value)).numeric(true);
        box.maxLength = 3;
        box.onChange = () -> {
            if (box.value.isEmpty()) return;
            apply.accept(box.intValue(0));
            GoofyConfig.save();
        };
        return box;
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null || enabledToggle == null) {
            Draw.textCentered(g, "Config yuklenemedi", x + w / 2, y + 40, Theme.RED);
            return;
        }

        // --- durum kartı ---
        Draw.panel(g, x, cardY, w, cardH, Theme.CARD, Theme.STROKE);

        Draw.text(g, "Session Planlayici", x + 12, cardY + 11, Theme.TEXT);
        String beta = "BETA";
        int betaX = x + 12 + Draw.textWidth("Session Planlayici") + 8;
        Draw.badge(g, beta, betaX, cardY + 9, Theme.YELLOW, Theme.YELLOW_SOFT);

        enabledToggle.render(g, mouseX, mouseY);

        Draw.text(g, "Makro belirli araliklarla kendi kendine mola verir,",
                x + 12, cardY + 26, Theme.TEXT_FAINT);
        Draw.text(g, "sonra kaldigi yerden devam eder.",
                x + 12, cardY + 36, Theme.TEXT_FAINT);

        String status = FailsafeManager.INSTANCE.getSessionPlanner().statusLine();
        Draw.textRight(g, status, x + w - 12, cardY + 36,
                config.sessionPlannerEnabled ? Theme.ACCENT : Theme.TEXT_FAINT);

        // --- süre satırları ---
        Draw.text(g, "SURELER (dakika)", x, rowsY - 14, Theme.TEXT_FAINT);
        Draw.hLine(g, x, rowsY - 3, w, Theme.STROKE);

        row(g, mouseX, mouseY, 0, "Calisma suresi", "her turda bu aralikta rastgele");
        row(g, mouseX, mouseY, 1, "Mola suresi", "her molada bu aralikta rastgele");

        for (Widgets.TextBox box : boxes) box.render(g, mouseX, mouseY);

        // "min - max" arasindaki tire
        int dashX = workMinBox.x + workMinBox.w + 4;
        Draw.text(g, "-", dashX, rowsY + 6, Theme.TEXT_FAINT);
        Draw.text(g, "-", dashX, rowsY + ROW_H + 6, Theme.TEXT_FAINT);

        // --- alt not ---
        int noteY = rowsY + ROW_H * 2 + 14;
        Draw.text(g, "NOT", x, noteY, Theme.TEXT_FAINT);
        Draw.hLine(g, x, noteY + 11, w, Theme.STROKE);
        Draw.text(g, "Mola sirasinda gorevler ve sayaclar silinmez; makro tam",
                x, noteY + 18, Theme.TEXT_DIM);
        Draw.text(g, "kaldigi noktadan devam eder. Elle duraklattiysan (Pause)",
                x, noteY + 28, Theme.TEXT_DIM);
        Draw.text(g, "planlayici seni ezmez, makro duraklatilmis kalir.",
                x, noteY + 38, Theme.TEXT_DIM);
    }

    private void row(GuiGraphicsExtractor g, int mouseX, int mouseY, int index, String title, String hint) {
        int ry = rowsY + index * ROW_H;
        if (Draw.inside(mouseX, mouseY, x, ry, w, ROW_H)) {
            Draw.rect(g, x - 4, ry, w + 8, ROW_H, Theme.CARD);
        }
        Draw.text(g, title, x, ry + 6, Theme.TEXT);
        Draw.text(g, hint, x + Draw.textWidth(title) + 8, ry + 6, Theme.TEXT_FAINT);
        if (index == 0) Draw.hLine(g, x, ry + ROW_H - 1, w, Theme.STROKE);
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY) {
        if (enabledToggle == null) return false;
        if (enabledToggle.mouseClicked(mouseX, mouseY)) return true;
        boolean hit = false;
        for (Widgets.TextBox box : boxes) {
            if (box.mouseClicked(mouseX, mouseY)) hit = true;
        }
        return hit;
    }

    public boolean keyPressed(int keyCode) {
        for (Widgets.TextBox box : boxes) {
            if (box.keyPressed(keyCode)) return true;
        }
        return false;
    }

    public boolean charTyped(char chr) {
        for (Widgets.TextBox box : boxes) {
            if (box.charTyped(chr)) return true;
        }
        return false;
    }

    public boolean anyFocused() {
        for (Widgets.TextBox box : boxes) {
            if (box.focused) return true;
        }
        return false;
    }

    public void clearFocus() {
        for (Widgets.TextBox box : boxes) box.focused = false;
    }

    public void tick() {
        for (Widgets.TextBox box : boxes) box.tick();
    }
}
