package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.BookPresets;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.bookflipper.helper.BazaarLookup;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.features.bookflipper.helper.ItemCatalog;
import com.goofy.goofyaddons.features.bookflipper.helper.TradeHistory;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Presets - modun MERKEZİ sayfası.
 *
 * Buraya bir kitap eklediğinde o kitap kütüphanene girer ve bir daha
 * döndüğünde onunla ilgili her şeyi tek bakışta görürsün:
 *
 *   satır 1  ->  isim, seviye aralığı ve butonlar
 *   satır 2  ->  CANLI BAZAAR: alış / satış fiyatı, vergi sonrası temiz kâr
 *                (miktar + yüzde), günlük hacimler
 *   satır 3  ->  SENİN GEÇMİŞİN: o kitaba toplam ne harcadın, ne kazandın,
 *                net kaç ettin, kaç adet alıp sattın
 *
 * "Activate" tek tuşla kitabı aktif listeye (config) yazar; makro bir sonraki
 * turda onu işlemeye başlar. Zaten aktifse buton "Active" olur ve tıklanmaz.
 *
 * "+ Add Book" ARTIK SADECE BURADA: aynı formu iki sayfada tutmanın anlamı yok,
 * kitap ekleme akışının tek doğru yeri kütüphane sayfasıdır.
 */
public class PresetsTab {

    private static final int ROW_H = 46;
    private static final int TOP_H = 40;

    /** Bazaar satış vergisi - BookForm ile aynı varsayım. */
    private static final double TAX = 0.0125;

    private int x, y, w, h;
    private int listY, listH;
    private int actBtnX, actBtnW, editBtnX, editBtnW, delBtnX, delBtnW, rowBtnH;
    private int scroll = 0;

    private Widgets.Button addButton;
    private Widgets.Button refreshButton;

    /** Sayfa kendi modalını yönetir; GoofyScreen sadece çizim sırasını verir. */
    private BookForm modal = null;
    private int screenW, screenH;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        listY = y + TOP_H;
        listH = Math.max(80, (y + h) - listY);

        rowBtnH = 16;
        delBtnW = 22;
        editBtnW = 40;
        actBtnW = 62;
        delBtnX = x + w - 10 - delBtnW;
        editBtnX = delBtnX - 6 - editBtnW;
        actBtnX = editBtnX - 6 - actBtnW;

        addButton = new Widgets.Button("+ Add Book", () -> openModal(null, -1))
                .bounds(x + w - 176, y + 2, 92, 22)
                .accent(Theme.ACCENT).filled(true);

        refreshButton = new Widgets.Button("Refresh", this::refresh)
                .bounds(x + w - 78, y + 2, 78, 22)
                .accent(Theme.GREEN);
    }

    public void setScreenSize(int screenWidth, int screenHeight) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;
        if (modal != null) modal.layout(screenWidth, screenHeight);
    }

    /**
     * Refresh = HER ŞEYİ tazele: presets.json'u diskten yeniden oku, bazaar
     * fiyatlarını önbelleği yok sayarak yeniden çek, kitap katalogunu yeniden
     * kur. Eski "Reload File" butonu sadece dosyayı okuyordu ve ne yaptığı
     * anlaşılmıyordu; artık tek buton ve adı ne yaptığını söylüyor.
     */
    private void refresh() {
        BookPresets.load();
        BazaarLookup.forceRefresh();
        ItemCatalog.refresh();
        scroll = 0;
    }

    private void openModal(Book book, int index) {
        modal = new BookForm(book, index, BookForm.Target.PRESET, () -> modal = null);
        modal.layout(screenW, screenH);
    }

    public boolean hasModal() {
        return modal != null;
    }

    public void tick() {
        if (modal != null) modal.tick();
    }

    public boolean isTyping() {
        return modal != null && modal.isTyping();
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        BazaarLookup.refreshIfStale();
        List<BookPresets.Preset> presets = BookPresets.all();

        Draw.text(g, "BOOK LIBRARY", x, y + 3, Theme.TEXT);
        Draw.text(g, presets.size() + " saved  -  add here, then activate what you want to run",
                x, y + 16, Theme.TEXT_FAINT);

        addButton.render(g, mouseX, mouseY);
        refreshButton.render(g, mouseX, mouseY);

        Draw.panel(g, x, listY, w, listH, Theme.CARD, Theme.STROKE);

        if (presets.isEmpty()) {
            int cy = listY + listH / 2;
            Draw.textCentered(g, "Your library is empty", x + w / 2, cy - 18, Theme.TEXT_DIM);
            Draw.textCentered(g, "Press '+ Add Book' to search the bazaar and save a book here.",
                    x + w / 2, cy - 2, Theme.TEXT_FAINT);
            Draw.textCentered(g, "Saved books keep their price and profit history for next time.",
                    x + w / 2, cy + 12, Theme.TEXT_FAINT);
            return;
        }

        int visible = visibleRows();
        scroll = clamp(scroll, 0, Math.max(0, presets.size() - visible));

        for (int i = 0; i < visible && (i + scroll) < presets.size(); i++) {
            renderRow(g, presets.get(i + scroll), listY + 2 + i * ROW_H, mouseX, mouseY);
        }

        int maxScroll = Math.max(0, presets.size() - visible);
        if (maxScroll > 0) {
            int barH = Math.max(16, listH * visible / Math.max(1, presets.size()));
            int barY = listY + (listH - barH) * scroll / maxScroll;
            Draw.rect(g, x + w - 3, barY, 2, barH, Theme.STROKE);
        }
    }

    private void renderRow(GuiGraphicsExtractor g, BookPresets.Preset preset, int ry, int mouseX, int mouseY) {
        boolean active = GoofyConfig.hasBook(preset.id, preset.level, -1);

        if (Draw.inside(mouseX, mouseY, x + 1, ry, w - 2, ROW_H - 2)) {
            Draw.rect(g, x + 1, ry, w - 2, ROW_H - 2, Theme.HOVER);
        }
        Draw.rect(g, x + 1, ry + 5, 2, ROW_H - 14, active ? Theme.GREEN : Theme.ACCENT_SOFT);

        // ---- satır 1: başlık + butonlar ----
        String title = preset.name + "   " + roman(preset.level) + " \u2192 " + roman(preset.sellLevel);
        Draw.text(g, Draw.clip(title, actBtnX - x - 24), x + 12, ry + 4, Theme.TEXT);

        if (active) {
            Draw.roundRect(g, actBtnX, ry + 3, actBtnW, rowBtnH, Theme.GREEN_SOFT);
            Draw.textCentered(g, "Active", actBtnX + actBtnW / 2, ry + 7, Theme.GREEN);
        } else {
            miniButton(g, "Activate", actBtnX, ry + 3, actBtnW, mouseX, mouseY, Theme.ACCENT);
        }
        miniButton(g, "Edit", editBtnX, ry + 3, editBtnW, mouseX, mouseY, Theme.ACCENT);
        miniButton(g, "x", delBtnX, ry + 3, delBtnW, mouseX, mouseY, Theme.RED);

        // ---- satır 2: canlı bazaar ----
        renderMarketLine(g, preset, ry + 20);

        // ---- satır 3: senin geçmişin ----
        renderHistoryLine(g, preset, ry + 32);
    }

    private void renderMarketLine(GuiGraphicsExtractor g, BookPresets.Preset preset, int ly) {
        BazaarLookup.Quick buy = BazaarLookup.get(preset.id + "_" + preset.level);
        BazaarLookup.Quick sell = BazaarLookup.get(preset.id + "_" + preset.sellLevel);

        if (buy == null || sell == null) {
            Draw.text(g, BazaarLookup.isReady() ? "not on the bazaar right now" : "loading bazaar prices...",
                    x + 12, ly, Theme.TEXT_FAINT);
            return;
        }

        int qty = 1 << Math.max(0, preset.sellLevel - preset.level);
        double invest = buy.sellPrice() * qty;
        double clean = sell.buyPrice() * (1 - TAX) - invest;
        double percent = invest > 0 ? clean / invest * 100.0 : 0;

        int cx = x + 12;
        cx = cell(g, cx, ly, "buy", StatsTab.coins(buy.sellPrice()), Theme.TEXT_DIM);
        cx = cell(g, cx, ly, "sell", StatsTab.coins(sell.buyPrice()), Theme.TEXT_DIM);
        cx = cell(g, cx, ly, "clean",
                (clean >= 0 ? "+" : "-") + StatsTab.coins(Math.abs(clean)) + " (" + Math.round(percent) + "%)",
                clean >= 0 ? Theme.GREEN : Theme.RED);
        cell(g, cx, ly, "vol", (buy.sellMovingWeek() / 7) + " / " + (sell.buyMovingWeek() / 7) + " per day",
                Theme.TEXT_DIM);
    }

    private void renderHistoryLine(GuiGraphicsExtractor g, BookPresets.Preset preset, int ly) {
        TradeHistory.Stats stats = TradeHistory.totalStats().get(preset.name);
        if (stats == null) {
            Draw.text(g, "no trades recorded for this book yet", x + 12, ly, Theme.TEXT_FAINT);
            return;
        }

        int cx = x + 12;
        cx = cell(g, cx, ly, "spent", StatsTab.coins(stats.spend), Theme.TEXT_DIM);
        cx = cell(g, cx, ly, "earned", StatsTab.coins(stats.revenueNet), Theme.TEXT_DIM);
        double net = stats.cleanProfit();
        cx = cell(g, cx, ly, "net",
                (net >= 0 ? "+" : "-") + StatsTab.coins(Math.abs(net)),
                net >= 0 ? Theme.GREEN : Theme.RED);
        cell(g, cx, ly, "traded", stats.bought + " bought / " + stats.sold + " sold", Theme.TEXT_DIM);
    }

    /** "label value" çifti çizer ve bir sonraki kolonun x'ini döndürür. */
    private int cell(GuiGraphicsExtractor g, int cx, int cy, String label, String value, int valueColor) {
        Draw.text(g, label, cx, cy, Theme.TEXT_FAINT);
        int vx = cx + Draw.textWidth(label) + 4;
        Draw.text(g, value, vx, cy, valueColor);
        return vx + Draw.textWidth(value) + 12;
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

    /** Modal her şeyin üstüne çizilir - GoofyScreen bunu en son çağırır. */
    public void renderModal(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (modal != null) modal.render(g, mouseX, mouseY);
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY) {
        if (modal != null) {
            modal.mouseClicked(mouseX, mouseY, 0);
            return true;
        }

        if (addButton.mouseClicked(mouseX, mouseY)) return true;
        if (refreshButton.mouseClicked(mouseX, mouseY)) return true;

        List<BookPresets.Preset> presets = BookPresets.all();
        int visible = visibleRows();

        for (int i = 0; i < visible && (i + scroll) < presets.size(); i++) {
            int index = i + scroll;
            int ry = listY + 2 + i * ROW_H;
            BookPresets.Preset preset = presets.get(index);

            if (Draw.inside(mouseX, mouseY, delBtnX, ry + 3, delBtnW, rowBtnH)) {
                BookPresets.remove(index);
                return true;
            }
            if (Draw.inside(mouseX, mouseY, editBtnX, ry + 3, editBtnW, rowBtnH)) {
                openModal(preset.toBook(), index);
                return true;
            }
            if (Draw.inside(mouseX, mouseY, actBtnX, ry + 3, actBtnW, rowBtnH)) {
                if (!GoofyConfig.hasBook(preset.id, preset.level, -1)) {
                    GoofyConfig.addBook(preset.toBook());
                }
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double direction) {
        if (modal != null) return modal.mouseScrolled(direction);
        scroll = Math.max(0, scroll - (int) Math.signum(direction));
        return true;
    }

    public boolean keyPressed(int keyCode) {
        if (modal != null) return modal.keyPressed(keyCode);
        return false;
    }

    public boolean charTyped(char chr) {
        if (modal != null) return modal.charTyped(chr);
        return false;
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
            case 7 -> "VII";
            case 8 -> "VIII";
            default -> String.valueOf(level);
        };
    }
}
