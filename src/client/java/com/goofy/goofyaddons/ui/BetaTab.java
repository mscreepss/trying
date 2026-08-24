package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.BookPresets;
import com.goofy.goofyaddons.features.bookflipper.helper.BazaarLookup;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.features.bookflipper.helper.ItemCatalog;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Beta - kâr tarayıcısı.
 *
 * Bazaar'daki TÜM büyü kitaplarını gezer ve her biri için "level 1'den (ve ayrı
 * olarak level 2'den) o kitabın EN YÜKSEK seviyesine kadar birleştirirsem ne
 * kazanırım" sorusunu hesaplar.
 *
 * CRAFT MATEMATİĞİ: her seviye bir öncekinden 2 adet ister. Yani taban
 * seviyeden hedefe kadar gereken adet = 2^(hedef - taban).
 *   6 seviyeli kitap, level 1'den: 2^5 = 32 adet
 *   6 seviyeli kitap, level 2'den: 2^4 = 16 adet
 * Hedef seviye SABİT 5 DEĞİL - her kitabın bazaar'daki gerçek en yüksek
 * seviyesi kullanılır.
 *
 * Zarar eden hatlar listeye hiç girmez. Sıralama temiz kâra göre, en yüksekten
 * aşağıya.
 */
public class BetaTab {

    /** Bazaar satış vergisi. */
    private static final double TAX = 0.0125;

    /** Hesaplanmış tek satır. */
    private record Row(String name, String baseId, int buyLevel, int sellLevel, int qty,
                       double buyPrice, long buyDaily,
                       double sellPrice, long sellDaily,
                       double clean, double percent) {
    }

    private static final int ROW_H = 32;
    private static final int TOP_H = 40;

    private int x, y, w, h;
    private int listY, listH;
    private int saveBtnX, saveBtnW, rowBtnH;
    private int scroll = 0;

    private Widgets.Button refreshButton;

    private List<Row> rows = new ArrayList<>();
    private int builtFrom = -1;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        listY = y + TOP_H;
        listH = Math.max(80, (y + h) - listY);

        rowBtnH = 16;
        saveBtnW = 52;
        saveBtnX = x + w - 10 - saveBtnW;

        refreshButton = new Widgets.Button("Refresh", this::refresh)
                .bounds(x + w - 78, y + 2, 78, 22)
                .accent(Theme.GREEN);
    }

    /** Her şeyi sıfırlar ve bazaar'dan yeniden çeker. */
    private void refresh() {
        rows = new ArrayList<>();
        builtFrom = -1;
        scroll = 0;
        BazaarLookup.forceRefresh();
        ItemCatalog.refresh();
    }

    // =====================================================================
    // Hesap
    // =====================================================================

    /**
     * Bazaar verisi değiştiyse tabloyu yeniden kurar.
     *
     * Her karede değil, yalnızca ürün sayısı değiştiğinde çalışır - birkaç yüz
     * kitap x 2 seviye kadar hesap var, bunu her karede yapmak gereksiz.
     */
    private void rebuildIfNeeded() {
        if (!BazaarLookup.isReady()) return;
        int version = BazaarLookup.productIds().size();
        if (builtFrom == version) return;

        List<Row> fresh = new ArrayList<>();

        for (ItemCatalog.Entry entry : ItemCatalog.all()) {
            int maxLevel = ItemCatalog.maxLevel(entry.baseId());
            if (maxLevel < 2) continue;

            BazaarLookup.Quick sell = BazaarLookup.get(entry.baseId() + "_" + maxLevel);
            if (sell == null || sell.buyPrice() <= 0) continue;

            for (int buyLevel = 1; buyLevel <= 2; buyLevel++) {
                if (buyLevel >= maxLevel) continue;

                BazaarLookup.Quick buy = BazaarLookup.get(entry.baseId() + "_" + buyLevel);
                if (buy == null || buy.sellPrice() <= 0) continue;

                int qty = 1 << (maxLevel - buyLevel);
                double cost = buy.sellPrice() * qty;
                if (cost <= 0) continue;

                double clean = sell.buyPrice() * (1 - TAX) - cost;
                // Zarar edenler listeye hic girmez.
                if (clean <= 0) continue;

                fresh.add(new Row(entry.displayName(), entry.baseId(), buyLevel, maxLevel, qty,
                        buy.sellPrice(), buy.sellMovingWeek() / 7,
                        sell.buyPrice(), sell.buyMovingWeek() / 7,
                        clean, clean / cost * 100.0));
            }
        }

        fresh.sort(Comparator.comparingDouble(Row::clean).reversed());
        rows = fresh;
        builtFrom = version;
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        BazaarLookup.refreshIfStale();
        rebuildIfNeeded();

        Draw.text(g, "PROFIT SCANNER", x, y + 3, Theme.TEXT);
        Draw.text(g, rows.isEmpty()
                        ? "reading the bazaar..."
                        : rows.size() + " profitable lines  -  sorted by clean profit, losses hidden",
                x, y + 16, Theme.TEXT_FAINT);

        refreshButton.render(g, mouseX, mouseY);

        Draw.panel(g, x, listY, w, listH, Theme.CARD, Theme.STROKE);

        if (rows.isEmpty()) {
            int cy = listY + listH / 2;
            Draw.textCentered(g, BazaarLookup.isReady() ? "No profitable book right now" : "Loading bazaar data...",
                    x + w / 2, cy - 8, Theme.TEXT_DIM);
            Draw.textCentered(g, "Press 'Refresh' to pull fresh prices.", x + w / 2, cy + 6, Theme.TEXT_FAINT);
            return;
        }

        int visible = visibleRows();
        scroll = clamp(scroll, 0, Math.max(0, rows.size() - visible));

        for (int i = 0; i < visible && (i + scroll) < rows.size(); i++) {
            renderRow(g, rows.get(i + scroll), listY + 2 + i * ROW_H, mouseX, mouseY);
        }

        int maxScroll = Math.max(0, rows.size() - visible);
        if (maxScroll > 0) {
            int barH = Math.max(16, listH * visible / Math.max(1, rows.size()));
            int barY = listY + (listH - barH) * scroll / maxScroll;
            Draw.rect(g, x + w - 3, barY, 2, barH, Theme.STROKE);
        }
    }

    private void renderRow(GuiGraphicsExtractor g, Row row, int ry, int mouseX, int mouseY) {
        boolean saved = BookPresets.has(row.baseId(), row.buyLevel());

        if (Draw.inside(mouseX, mouseY, x + 1, ry, w - 2, ROW_H - 2)) {
            Draw.rect(g, x + 1, ry, w - 2, ROW_H - 2, Theme.HOVER);
        }
        Draw.rect(g, x + 1, ry + 4, 2, ROW_H - 12, Theme.ACCENT_SOFT);

        // satır 1: başlık + gereken adet + kaydet
        String title = row.name() + "   " + row.buyLevel() + " \u2192 " + row.sellLevel();
        Draw.text(g, Draw.clip(title, saveBtnX - x - 90), x + 12, ry + 3, Theme.TEXT);
        Draw.text(g, "needs " + row.qty() + "x",
                x + 12 + Draw.textWidth(Draw.clip(title, saveBtnX - x - 90)) + 12, ry + 3, Theme.TEXT_FAINT);

        if (saved) {
            Draw.textRight(g, "saved", saveBtnX + saveBtnW, ry + 6, Theme.TEXT_FAINT);
        } else {
            miniButton(g, "+ Save", saveBtnX, ry + 2, saveBtnW, mouseX, mouseY, Theme.GREEN);
        }

        // satır 2: fiyatlar, hacimler, kâr
        int cx = x + 12;
        cx = cell(g, cx, ry + 17, "buy", StatsTab.coins(row.buyPrice()) + "  " + row.buyDaily() + "/d", Theme.TEXT_DIM);
        cx = cell(g, cx, ry + 17, "sell", StatsTab.coins(row.sellPrice()) + "  " + row.sellDaily() + "/d", Theme.TEXT_DIM);
        cell(g, cx, ry + 17, "profit",
                "+" + StatsTab.coins(row.clean()) + "  (" + Math.round(row.percent()) + "%)", Theme.GREEN);
    }

    private int cell(GuiGraphicsExtractor g, int cx, int cy, String label, String value, int valueColor) {
        Draw.text(g, label, cx, cy, Theme.TEXT_FAINT);
        int vx = cx + Draw.textWidth(label) + 4;
        Draw.text(g, value, vx, cy, valueColor);
        return vx + Draw.textWidth(value) + 14;
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
        if (refreshButton.mouseClicked(mouseX, mouseY)) return true;

        int visible = visibleRows();
        for (int i = 0; i < visible && (i + scroll) < rows.size(); i++) {
            Row row = rows.get(i + scroll);
            int ry = listY + 2 + i * ROW_H;
            if (!Draw.inside(mouseX, mouseY, saveBtnX, ry + 2, saveBtnW, rowBtnH)) continue;
            if (BookPresets.has(row.baseId(), row.buyLevel())) return true;
            BookPresets.addBook(new Book(row.baseId(), row.buyLevel(), row.sellLevel(), row.name()));
            return true;
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
}
