package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.features.bookflipper.helper.TradeHistory;
import com.goofy.goofyaddons.features.bookflipper.helper.TradeRecord;
import com.goofy.goofyaddons.utils.ActionLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * İstatistik sayfası. Üç alt bölüm, hepsi kendi segment kontrolünden seçilir:
 *
 *  - GECMIS   : her tamamlanan hat için tek satır (outbid sayısı, süre, spend,
 *               clean profit). Süre = ilk buy order -> sell order açılışı.
 *  - CANLI LOG: son 300 aksiyon, en yeni üstte. Önceki oturumdan kalanlar bir
 *               ayraç satırının altında durur.
 *  - KITAP    : kitap bazında toplamlar; SESSION / TOTAL olarak ikiye ayrık.
 *
 * Bu sınıf yalnızca ÇİZER ve TIKLAMA alır; veri tamamen TradeHistory ve
 * ActionLog'dan okunur, burada hiçbir şey hesaplanıp saklanmaz.
 */
public class StatsTab {

    private enum Section {
        HISTORY("Gecmis"),
        LOG("Canli Log"),
        BOOKS("Kitap");

        final String title;

        Section(String title) {
            this.title = title;
        }
    }

    /** Sekme seçimi ekranlar arasında hatırlansın. */
    private static Section activeSection = Section.HISTORY;
    private static boolean booksShowSession = true;

    private static final int SEG_H = 20;
    private static final int HISTORY_ROW_H = 30;
    private static final int LOG_ROW_H = 11;
    private static final int BOOK_ROW_H = 26;

    private int x, y, w, h;
    private int segW;
    private int bodyY, bodyH;
    private int scopeX, scopeY, scopeW, scopeH;

    private int historyScroll = 0;
    private int logScroll = 0;
    private int bookScroll = 0;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.segW = w / Section.values().length;

        this.bodyY = y + SEG_H + 12;
        this.bodyH = Math.max(30, (y + h) - bodyY);

        // Session / Total ikilisi Kitap bölümünün kendi başlık satırında durur.
        this.scopeW = 58;
        this.scopeH = 14;
        this.scopeX = x + w - scopeW * 2 - 4;
        this.scopeY = y + SEG_H + (12 - scopeH) / 2 - 1;
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        renderSegments(g, mouseX, mouseY);

        switch (activeSection) {
            case HISTORY -> renderHistory(g, mouseX, mouseY);
            case LOG -> renderLog(g, mouseX, mouseY);
            case BOOKS -> renderBooks(g, mouseX, mouseY);
        }
    }

    private void renderSegments(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.panel(g, x, y, w, SEG_H, Theme.INSET, Theme.STROKE);

        Section[] sections = Section.values();
        for (int i = 0; i < sections.length; i++) {
            int sx = segX(i);
            int sw = segWidth(i);
            boolean selected = sections[i] == activeSection;
            boolean hover = Draw.inside(mouseX, mouseY, sx, y, sw, SEG_H);

            if (selected) {
                Draw.roundRect(g, sx + 2, y + 2, sw - 4, SEG_H - 4, Theme.SELECTED);
                Draw.rect(g, sx + 6, y + SEG_H - 3, sw - 12, 1, Theme.ACCENT);
            } else if (hover) {
                Draw.roundRect(g, sx + 2, y + 2, sw - 4, SEG_H - 4, Theme.HOVER);
            }

            int color = selected ? Theme.TEXT : (hover ? Theme.TEXT_DIM : Theme.TEXT_FAINT);
            Draw.textCentered(g, Draw.clip(sections[i].title, sw - 8), sx + sw / 2, y + (SEG_H - 8) / 2, color);
        }
    }

    // ------------------------------------------------------------------ GECMIS

    private void renderHistory(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<TradeRecord> records = TradeHistory.records();

        Draw.text(g, records.size() + " tamamlanan hat", x, y + SEG_H + 2, Theme.TEXT_FAINT);
        Draw.textRight(g, "sure = ilk buy order -> sell order acilisi", x + w, y + SEG_H + 2, Theme.TEXT_FAINT);

        Draw.panel(g, x, bodyY, w, bodyH, Theme.CARD, Theme.STROKE);

        if (records.isEmpty()) {
            Draw.textCentered(g, "Henuz tamamlanan hat yok", x + w / 2, bodyY + bodyH / 2 - 4, Theme.TEXT_FAINT);
            return;
        }

        int visible = Math.max(1, (bodyH - 4) / HISTORY_ROW_H);
        historyScroll = clamp(historyScroll, 0, Math.max(0, records.size() - visible));

        for (int i = 0; i < visible && (i + historyScroll) < records.size(); i++) {
            TradeRecord record = records.get(i + historyScroll);
            int ry = bodyY + 2 + i * HISTORY_ROW_H;

            if (Draw.inside(mouseX, mouseY, x + 1, ry, w - 2, HISTORY_ROW_H - 1)) {
                Draw.rect(g, x + 1, ry, w - 2, HISTORY_ROW_H - 1, Theme.HOVER);
            }
            Draw.rect(g, x + 1, ry + 4, 2, HISTORY_ROW_H - 10, Theme.ACCENT_SOFT);

            String title = record.name + "   " + roman(record.level) + " \u2192 " + roman(record.sellLevel);
            Draw.text(g, Draw.clip(title, w / 2), x + 10, ry + 3, Theme.TEXT);
            Draw.textRight(g, "outbid " + record.outbidCount + "   -   " + duration(record.durationMs()),
                    x + w - 8, ry + 3, Theme.TEXT_DIM);

            Draw.text(g, "Spend", x + 10, ry + 15, Theme.TEXT_FAINT);
            Draw.text(g, coins(record.spend), x + 10 + Draw.textWidth("Spend") + 6, ry + 15, Theme.TEXT);

            String cleanLabel = "Clean Profit";
            if (record.revenuePending) {
                Draw.textRight(g, cleanLabel + "  satis bekleniyor...", x + w - 8, ry + 15, Theme.TEXT_FAINT);
            } else {
                double clean = record.cleanProfit();
                String value = (clean >= 0 ? "+" : "-") + coins(Math.abs(clean));
                int color = clean >= 0 ? Theme.GREEN : Theme.RED;
                Draw.textRight(g, value, x + w - 8, ry + 15, color);
                Draw.textRight(g, cleanLabel, x + w - 8 - Draw.textWidth(value) - 6, ry + 15, Theme.TEXT_FAINT);
            }

            if (i + 1 < visible && (i + historyScroll + 1) < records.size()) {
                Draw.hLine(g, x + 8, ry + HISTORY_ROW_H - 2, w - 16, Theme.STROKE);
            }
        }

        renderScrollbar(g, records.size(), visible, historyScroll);
    }

    // ------------------------------------------------------------------ CANLI LOG

    private void renderLog(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<ActionLog.Entry> lines = ActionLog.snapshot();

        Draw.text(g, "son " + lines.size() + " aksiyon", x, y + SEG_H + 2, Theme.TEXT_FAINT);
        Draw.textRight(g, "en yeni ustte", x + w, y + SEG_H + 2, Theme.TEXT_FAINT);

        Draw.panel(g, x, bodyY, w, bodyH, Theme.CARD, Theme.STROKE);

        if (lines.isEmpty()) {
            Draw.textCentered(g, "Henuz aksiyon yok", x + w / 2, bodyY + bodyH / 2 - 4, Theme.TEXT_FAINT);
            return;
        }

        int visible = Math.max(1, (bodyH - 6) / LOG_ROW_H);
        logScroll = clamp(logScroll, 0, Math.max(0, lines.size() - visible));

        for (int i = 0; i < visible && (i + logScroll) < lines.size(); i++) {
            ActionLog.Entry entry = lines.get(i + logScroll);
            int ry = bodyY + 4 + i * LOG_ROW_H;

            if (entry.tag() == ActionLog.Tag.SEPARATOR) {
                int textW = Draw.textWidth(entry.message());
                int lineW = Math.max(4, (w - 20 - textW) / 2);
                Draw.hLine(g, x + 8, ry + 4, lineW, Theme.STROKE);
                Draw.text(g, entry.message(), x + 8 + lineW + 4, ry, Theme.TEXT_FAINT);
                Draw.hLine(g, x + 12 + lineW + textW, ry + 4, lineW, Theme.STROKE);
                continue;
            }

            Draw.text(g, entry.time(), x + 8, ry, Theme.TEXT_FAINT);
            int tagX = x + 8 + Draw.textWidth("00:00:00") + 8;
            Draw.text(g, entry.tag().name(), tagX, ry, tagColor(entry.tag()));

            int msgX = tagX + Draw.textWidth("RECOVERY") + 8;
            Draw.text(g, Draw.clip(entry.message(), x + w - 8 - msgX), msgX, ry, Theme.TEXT_DIM);
        }

        renderScrollbar(g, lines.size(), visible, logScroll);
    }

    private int tagColor(ActionLog.Tag tag) {
        return switch (tag) {
            case BUY -> Theme.ACCENT;
            case SELL -> Theme.GREEN;
            case OUTBID -> Theme.YELLOW;
            case RECOVERY -> Theme.RED;
            case SESSION -> Theme.YELLOW;
            case ANVIL, COMBINE -> Theme.TEXT;
            default -> Theme.TEXT_FAINT;
        };
    }

    // ------------------------------------------------------------------ KITAP

    private void renderBooks(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Map<String, TradeHistory.Stats> stats = booksShowSession
                ? TradeHistory.sessionStats()
                : TradeHistory.totalStats();

        Draw.text(g, booksShowSession ? "bu oturum" : "tum zamanlar", x, y + SEG_H + 2, Theme.TEXT_FAINT);
        renderScopeToggle(g, mouseX, mouseY);

        Draw.panel(g, x, bodyY, w, bodyH, Theme.CARD, Theme.STROKE);

        List<String> names = new ArrayList<>(stats.keySet());
        if (names.isEmpty()) {
            Draw.textCentered(g, booksShowSession
                            ? "Bu oturumda henuz islem yok"
                            : "Henuz kayitli islem yok",
                    x + w / 2, bodyY + bodyH / 2 - 4, Theme.TEXT_FAINT);
            return;
        }

        int visible = Math.max(1, (bodyH - 4) / BOOK_ROW_H);
        bookScroll = clamp(bookScroll, 0, Math.max(0, names.size() - visible));

        for (int i = 0; i < visible && (i + bookScroll) < names.size(); i++) {
            String name = names.get(i + bookScroll);
            TradeHistory.Stats stat = stats.get(name);
            int ry = bodyY + 2 + i * BOOK_ROW_H;

            if (Draw.inside(mouseX, mouseY, x + 1, ry, w - 2, BOOK_ROW_H - 1)) {
                Draw.rect(g, x + 1, ry, w - 2, BOOK_ROW_H - 1, Theme.HOVER);
            }
            Draw.rect(g, x + 1, ry + 4, 2, BOOK_ROW_H - 10, Theme.ACCENT_SOFT);

            Draw.text(g, Draw.clip(name, w / 2), x + 10, ry + 2, Theme.TEXT);
            Draw.textRight(g, "alim " + stat.bought + "   satim " + stat.sold,
                    x + w - 8, ry + 2, Theme.TEXT_DIM);

            int col = x + 10;
            col = statCell(g, col, ry + 13, "Spend", coins(stat.spend), Theme.TEXT);
            col = statCell(g, col, ry + 13, "Profit", signed(stat.profit()), color(stat.profit()));
            statCell(g, col, ry + 13, "Clean", signed(stat.cleanProfit()), color(stat.cleanProfit()));

            if (i + 1 < visible && (i + bookScroll + 1) < names.size()) {
                Draw.hLine(g, x + 8, ry + BOOK_ROW_H - 2, w - 16, Theme.STROKE);
            }
        }

        renderScrollbar(g, names.size(), visible, bookScroll);
    }

    private void renderScopeToggle(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String[] labels = {"Session", "Total"};
        for (int i = 0; i < labels.length; i++) {
            int bx = scopeX + i * scopeW;
            boolean selected = (i == 0) == booksShowSession;
            boolean hover = Draw.inside(mouseX, mouseY, bx, scopeY, scopeW, scopeH);

            Draw.roundRect(g, bx, scopeY, scopeW, scopeH,
                    selected ? Theme.SELECTED : (hover ? Theme.HOVER : Theme.INSET));
            Draw.textCentered(g, labels[i], bx + scopeW / 2, scopeY + (scopeH - 8) / 2,
                    selected ? Theme.ACCENT : Theme.TEXT_DIM);
        }
    }

    private int statCell(GuiGraphicsExtractor g, int cx, int cy, String label, String value, int valueColor) {
        Draw.text(g, label, cx, cy, Theme.TEXT_FAINT);
        int vx = cx + Draw.textWidth(label) + 5;
        Draw.text(g, value, vx, cy, valueColor);
        return vx + Draw.textWidth(value) + 14;
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY) {
        Section[] sections = Section.values();
        for (int i = 0; i < sections.length; i++) {
            if (Draw.inside(mouseX, mouseY, segX(i), y, segWidth(i), SEG_H)) {
                activeSection = sections[i];
                return true;
            }
        }

        if (activeSection == Section.BOOKS) {
            for (int i = 0; i < 2; i++) {
                if (Draw.inside(mouseX, mouseY, scopeX + i * scopeW, scopeY, scopeW, scopeH)) {
                    booksShowSession = (i == 0);
                    bookScroll = 0;
                    return true;
                }
            }
        }
        return false;
    }

    public boolean mouseScrolled(double direction) {
        int step = (int) Math.signum(direction);
        if (step == 0) return false;

        switch (activeSection) {
            case HISTORY -> historyScroll = Math.max(0, historyScroll - step);
            case LOG -> logScroll = Math.max(0, logScroll - step);
            case BOOKS -> bookScroll = Math.max(0, bookScroll - step);
        }
        return true;
    }

    // =====================================================================
    // Yardımcılar
    // =====================================================================

    private void renderScrollbar(GuiGraphicsExtractor g, int total, int visible, int scroll) {
        int maxScroll = Math.max(0, total - visible);
        if (maxScroll <= 0) return;
        int barH = Math.max(12, bodyH * visible / Math.max(1, total));
        int barY = bodyY + (bodyH - barH) * scroll / maxScroll;
        Draw.rect(g, x + w - 3, barY, 2, barH, Theme.STROKE);
    }

    private int segX(int index) {
        return x + index * segW;
    }

    private int segWidth(int index) {
        return index == Section.values().length - 1 ? w - segW * index : segW;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int color(double value) {
        if (value > 0) return Theme.GREEN;
        if (value < 0) return Theme.RED;
        return Theme.TEXT_DIM;
    }

    private static String signed(double value) {
        return (value >= 0 ? "+" : "-") + coins(Math.abs(value));
    }

    /** 19_800_000 -> "19.8M", 743_000 -> "743K" */
    public static String coins(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000d) return trim(value / 1_000_000_000d) + "B";
        if (abs >= 1_000_000d) return trim(value / 1_000_000d) + "M";
        if (abs >= 1_000d) return trim(value / 1_000d) + "K";
        return String.valueOf(Math.round(value));
    }

    private static String trim(double value) {
        String text = String.format("%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** 2_472_000 -> "41dk 12sn" */
    public static String duration(long ms) {
        if (ms <= 0) return "-";
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "sa " + minutes + "dk";
        if (minutes > 0) return minutes + "dk " + String.format("%02d", secs) + "sn";
        return secs + "sn";
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
