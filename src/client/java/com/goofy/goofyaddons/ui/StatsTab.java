package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.features.bookflipper.helper.TradeHistory;
import com.goofy.goofyaddons.features.bookflipper.helper.TradeRecord;
import com.goofy.goofyaddons.utils.ActionLog;
import com.goofy.goofyaddons.utils.Clipboard;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * İstatistik sayfası. Üç alt bölüm, hepsi kendi segment kontrolünden seçilir:
 *
 *  - HISTORY  : her tamamlanan hat için tek satır (süre, spend, clean profit).
 *               Outbid sayısı yalnızca SIFIRDAN BÜYÜKSE yazılır - "outbid 0"
 *               satırı hiçbir şey anlatmıyordu, kaldırıldı.
 *  - LIVE LOG : son 300 aksiyon. Sağdaki kaydırma çubuğu fareyle SÜRÜKLENEBİLİR
 *               (tekerlekle en alta inmek çok uzun sürüyordu) ve "Copy" butonu
 *               tüm günlüğü panoya kopyalar.
 *  - PER BOOK : kitap bazında toplamlar; kolonlar sağa hizalı bir tablo. Alt
 *               satırda hangi SEVİYEDEN ne kadar harcandığı ayrıca yazar
 *               (level 1 ve level 2 hatları ayrı ayrı görünür).
 *
 * Bu sınıf yalnızca ÇİZER ve TIKLAMA alır; veri tamamen TradeHistory ve
 * ActionLog'dan okunur.
 */
public class StatsTab {

    private enum Section {
        HISTORY("History"),
        LOG("Live Log"),
        BOOKS("Per Book");

        final String title;

        Section(String title) {
            this.title = title;
        }
    }

    /** Sekme seçimi ekranlar arasında hatırlansın. */
    private static Section activeSection = Section.HISTORY;
    private static boolean booksShowSession = true;

    private static final int SEG_H = 22;
    private static final int HISTORY_ROW_H = 34;
    private static final int LOG_ROW_H = 12;
    private static final int BOOK_ROW_H = 30;

    /** Kaydırma çubuğu genişliği - sürüklenebilmesi için parmak kalınlığında. */
    private static final int BAR_W = 6;

    private int x, y, w, h;
    private int segW;
    private int bodyY, bodyH;
    private int scopeX, scopeY, scopeW, scopeH;

    private int historyScroll = 0;
    private int logScroll = 0;
    private int bookScroll = 0;

    /** Log kaydırma çubuğu sürükleniyor mu? */
    private boolean draggingLogBar = false;
    private Widgets.Button copyButton;
    private String copyFeedback = null;
    private long copyFeedbackMs = 0;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.segW = w / Section.values().length;

        this.bodyY = y + SEG_H + 18;
        this.bodyH = Math.max(30, (y + h) - bodyY);

        this.scopeW = 64;
        this.scopeH = 15;
        this.scopeX = x + w - scopeW * 2 - 4;
        this.scopeY = y + SEG_H + 2;

        copyButton = new Widgets.Button("Copy", this::copyLog)
                .bounds(x + w - 54, y + SEG_H + 1, 54, 16)
                .accent(Theme.ACCENT);
    }

    private void copyLog() {
        List<ActionLog.Entry> lines = ActionLog.snapshot();
        StringBuilder out = new StringBuilder();
        // En eskiden en yeniye: metin olarak okunurken dogal sira budur.
        for (int i = lines.size() - 1; i >= 0; i--) {
            ActionLog.Entry entry = lines.get(i);
            out.append(entry.time()).append("  ")
                    .append(entry.tag().name()).append("  ")
                    .append(entry.message()).append('\n');
        }
        Clipboard.set(out.toString());
        copyFeedback = lines.size() + " lines copied";
        copyFeedbackMs = System.currentTimeMillis();
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
                Draw.rect(g, sx + 8, y + SEG_H - 3, sw - 16, 1, Theme.ACCENT);
            } else if (hover) {
                Draw.roundRect(g, sx + 2, y + 2, sw - 4, SEG_H - 4, Theme.HOVER);
            }

            int color = selected ? Theme.TEXT : (hover ? Theme.TEXT_DIM : Theme.TEXT_FAINT);
            Draw.textCentered(g, Draw.clip(sections[i].title, sw - 10), sx + sw / 2, y + (SEG_H - 8) / 2, color);
        }
    }

    // ------------------------------------------------------------------ HISTORY

    private void renderHistory(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<TradeRecord> records = TradeHistory.records();

        Draw.text(g, records.size() + " completed lines", x, y + SEG_H + 4, Theme.TEXT_FAINT);
        Draw.textRight(g, "duration = first buy order -> sell order placed",
                x + w, y + SEG_H + 4, Theme.TEXT_FAINT);

        Draw.panel(g, x, bodyY, w, bodyH, Theme.CARD, Theme.STROKE);

        if (records.isEmpty()) {
            Draw.textCentered(g, "No completed lines yet", x + w / 2, bodyY + bodyH / 2 - 4, Theme.TEXT_FAINT);
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
            Draw.rect(g, x + 1, ry + 5, 2, HISTORY_ROW_H - 12, Theme.ACCENT_SOFT);

            String title = record.name + "   " + roman(record.level) + " \u2192 " + roman(record.sellLevel);
            Draw.text(g, Draw.clip(title, w / 2), x + 12, ry + 4, Theme.TEXT);

            // "outbid 0" hicbir sey anlatmiyordu - sadece gercekten outbid
            // yendiyse yaziliyor.
            String meta = duration(record.durationMs());
            if (record.outbidCount > 0) meta = "outbid " + record.outbidCount + "   -   " + meta;
            Draw.textRight(g, meta, x + w - 10, ry + 4, Theme.TEXT_DIM);

            Draw.text(g, "Spend", x + 12, ry + 17, Theme.TEXT_FAINT);
            Draw.text(g, coins(record.spend), x + 12 + Draw.textWidth("Spend") + 8, ry + 17, Theme.TEXT);

            String cleanLabel = "Clean Profit";
            if (record.revenuePending) {
                Draw.textRight(g, cleanLabel + "  waiting for sale", x + w - 10, ry + 17, Theme.TEXT_FAINT);
            } else {
                double clean = record.cleanProfit();
                String value = (clean >= 0 ? "+" : "-") + coins(Math.abs(clean));
                int color = clean >= 0 ? Theme.GREEN : Theme.RED;
                Draw.textRight(g, value, x + w - 10, ry + 17, color);
                Draw.textRight(g, cleanLabel, x + w - 10 - Draw.textWidth(value) - 8, ry + 17, Theme.TEXT_FAINT);
            }

            if (i + 1 < visible && (i + historyScroll + 1) < records.size()) {
                Draw.hLine(g, x + 10, ry + HISTORY_ROW_H - 2, w - 20, Theme.STROKE);
            }
        }

        renderScrollbar(g, records.size(), visible, historyScroll, false);
    }

    // ------------------------------------------------------------------ LIVE LOG

    private void renderLog(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<ActionLog.Entry> lines = ActionLog.snapshot();
        int visible = Math.max(1, (bodyH - 6) / LOG_ROW_H);

        // Surukleme: cubuk tutulduysa her karede fare konumundan kaydirma hesaplanir.
        if (draggingLogBar) {
            int maxScroll = Math.max(0, lines.size() - visible);
            if (maxScroll > 0) {
                int barH = thumbHeight(lines.size(), visible);
                int travel = Math.max(1, bodyH - barH);
                double ratio = (double) (mouseY - bodyY - barH / 2) / travel;
                logScroll = clamp((int) Math.round(ratio * maxScroll), 0, maxScroll);
            }
        }

        String note = copyFeedback != null && System.currentTimeMillis() - copyFeedbackMs < 2500
                ? copyFeedback
                : "newest first  -  drag the bar to scroll";
        Draw.text(g, "last " + lines.size() + " actions", x, y + SEG_H + 4, Theme.TEXT_FAINT);
        Draw.textRight(g, note, x + w - 60, y + SEG_H + 4, Theme.TEXT_FAINT);
        copyButton.render(g, mouseX, mouseY);

        Draw.panel(g, x, bodyY, w, bodyH, Theme.CARD, Theme.STROKE);

        if (lines.isEmpty()) {
            Draw.textCentered(g, "No actions yet", x + w / 2, bodyY + bodyH / 2 - 4, Theme.TEXT_FAINT);
            return;
        }

        logScroll = clamp(logScroll, 0, Math.max(0, lines.size() - visible));

        for (int i = 0; i < visible && (i + logScroll) < lines.size(); i++) {
            ActionLog.Entry entry = lines.get(i + logScroll);
            int ry = bodyY + 4 + i * LOG_ROW_H;

            if (entry.tag() == ActionLog.Tag.SEPARATOR) {
                int textW = Draw.textWidth(entry.message());
                int lineW = Math.max(4, (w - 26 - textW) / 2);
                Draw.hLine(g, x + 10, ry + 4, lineW, Theme.STROKE);
                Draw.text(g, entry.message(), x + 14 + lineW, ry, Theme.TEXT_FAINT);
                Draw.hLine(g, x + 18 + lineW + textW, ry + 4, lineW, Theme.STROKE);
                continue;
            }

            Draw.text(g, entry.time(), x + 10, ry, Theme.TEXT_FAINT);
            int tagX = x + 10 + Draw.textWidth("00:00:00") + 10;
            Draw.text(g, entry.tag().name(), tagX, ry, tagColor(entry.tag()));

            int msgX = tagX + Draw.textWidth("RECOVERY") + 10;
            Draw.text(g, Draw.clip(entry.message(), x + w - 14 - msgX), msgX, ry, Theme.TEXT_DIM);
        }

        renderScrollbar(g, lines.size(), visible, logScroll, true);
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

    // ------------------------------------------------------------------ PER BOOK

    private void renderBooks(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Map<String, TradeHistory.Stats> stats = booksShowSession
                ? TradeHistory.sessionStats()
                : TradeHistory.totalStats();

        Draw.text(g, booksShowSession ? "this session" : "all time", x, y + SEG_H + 4, Theme.TEXT_FAINT);
        renderScopeToggle(g, mouseX, mouseY);

        // --- kolon başlıkları: satırlarla BİREBİR aynı x'leri kullanır ---
        int headerY = bodyY - 12;
        Draw.text(g, "BOOK", x + 12, headerY, Theme.TEXT_FAINT);
        Draw.textRight(g, "BOUGHT", colBought(), headerY, Theme.TEXT_FAINT);
        Draw.textRight(g, "SOLD", colSold(), headerY, Theme.TEXT_FAINT);
        Draw.textRight(g, "SPEND", colSpend(), headerY, Theme.TEXT_FAINT);
        Draw.textRight(g, "PROFIT", colProfit(), headerY, Theme.TEXT_FAINT);
        Draw.textRight(g, "CLEAN", colClean(), headerY, Theme.TEXT_FAINT);

        Draw.panel(g, x, bodyY, w, bodyH, Theme.CARD, Theme.STROKE);

        List<String> names = new ArrayList<>(stats.keySet());
        if (names.isEmpty()) {
            Draw.textCentered(g, booksShowSession ? "Nothing traded this session" : "Nothing recorded yet",
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
            Draw.rect(g, x + 1, ry + 5, 2, BOOK_ROW_H - 12, Theme.ACCENT_SOFT);

            Draw.text(g, Draw.clip(name, colBought() - x - 30), x + 12, ry + 4, Theme.TEXT);
            Draw.textRight(g, String.valueOf(stat.bought), colBought(), ry + 4, Theme.TEXT_DIM);
            Draw.textRight(g, String.valueOf(stat.sold), colSold(), ry + 4, Theme.TEXT_DIM);
            Draw.textRight(g, coins(stat.spend), colSpend(), ry + 4, Theme.TEXT);
            Draw.textRight(g, signed(stat.profit()), colProfit(), ry + 4, color(stat.profit()));
            Draw.textRight(g, signed(stat.cleanProfit()), colClean(), ry + 4, color(stat.cleanProfit()));

            // SEVIYE AYRIMI: ayni isim icin level 1 ve level 2 hatlari ayri
            // ayri takip ediliyor; hangisine ne kadar harcandigi burada.
            Draw.text(g, levelBreakdown(stat), x + 12, ry + 16, Theme.TEXT_FAINT);

            if (i + 1 < visible && (i + bookScroll + 1) < names.size()) {
                Draw.hLine(g, x + 10, ry + BOOK_ROW_H - 2, w - 20, Theme.STROKE);
            }
        }

        renderScrollbar(g, names.size(), visible, bookScroll, false);
    }

    private String levelBreakdown(TradeHistory.Stats stat) {
        if (stat.spendByLevel == null || stat.spendByLevel.isEmpty()) return "no per-level data yet";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, Double> entry : stat.spendByLevel.entrySet()) {
            if (!out.isEmpty()) out.append("    ");
            int bought = stat.boughtByLevel == null ? 0 : stat.boughtByLevel.getOrDefault(entry.getKey(), 0);
            out.append("level ").append(entry.getKey()).append(": ")
                    .append(coins(entry.getValue()))
                    .append(" / ").append(bought).append("x");
        }
        return out.toString();
    }

    private int colBought() {
        return x + (int) (w * 0.46);
    }

    private int colSold() {
        return x + (int) (w * 0.56);
    }

    private int colSpend() {
        return x + (int) (w * 0.72);
    }

    private int colProfit() {
        return x + (int) (w * 0.87);
    }

    private int colClean() {
        return x + w - 12;
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

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY) {
        Section[] sections = Section.values();
        for (int i = 0; i < sections.length; i++) {
            if (Draw.inside(mouseX, mouseY, segX(i), y, segWidth(i), SEG_H)) {
                activeSection = sections[i];
                draggingLogBar = false;
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

        if (activeSection == Section.LOG) {
            if (copyButton.mouseClicked(mouseX, mouseY)) return true;
            // Cubuga (ya da izine) basildiysa surukleme baslar.
            if (Draw.inside(mouseX, mouseY, barX(), bodyY, BAR_W, bodyH)) {
                draggingLogBar = true;
                return true;
            }
        }
        return false;
    }

    /** Sürükleme burada biter - HudEditScreen ile aynı kalıp. */
    public void mouseReleased() {
        draggingLogBar = false;
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

    private int barX() {
        return x + w - BAR_W - 2;
    }

    private int thumbHeight(int total, int visible) {
        return Math.max(18, bodyH * visible / Math.max(1, total));
    }

    /** draggable = true ise kalın, tutulabilir bir çubuk çizilir. */
    private void renderScrollbar(GuiGraphicsExtractor g, int total, int visible, int scroll, boolean draggable) {
        int maxScroll = Math.max(0, total - visible);
        if (maxScroll <= 0) return;

        int barH = thumbHeight(total, visible);
        int barY = bodyY + (bodyH - barH) * scroll / maxScroll;

        if (!draggable) {
            Draw.rect(g, x + w - 3, barY, 2, barH, Theme.STROKE);
            return;
        }

        Draw.roundRect(g, barX(), bodyY, BAR_W, bodyH, Theme.INSET);
        Draw.roundRect(g, barX(), barY, BAR_W, barH, draggingLogBar ? Theme.ACCENT : Theme.HOVER);
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

    /** 2_472_000 -> "41m 12s" */
    public static String duration(long ms) {
        if (ms <= 0) return "-";
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + String.format("%02d", secs) + "s";
        return secs + "s";
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            default -> String.valueOf(level);
        };
    }
}
