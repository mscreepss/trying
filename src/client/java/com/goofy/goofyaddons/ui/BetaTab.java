package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.BookPresets;
import com.goofy.goofyaddons.config.GoofyConfig;
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
 * VARSAYILAN SIRALAMA NEDEN "SCORE"?
 * Sadece temiz kâra göre sıralayınca en üste 263M kâr eden ama günlük alış
 * hacmi 0 olan kitaplar çıkıyordu - o kitabı pratikte hiç alamazsın, yani kâr
 * kâğıt üstünde kalıyor. Score bunu düzeltir:
 *
 *   craftsPerDay = min(gunluk alis hacmi / gereken adet, gunluk satis hacmi)
 *   score        = temiz kar * craftsPerDay        (gunde beklenen coin)
 *
 * Alış hacmi 0 ise craftsPerDay 0 olur, score 0 olur, kitap dibe iner.
 * Kullanıcı yine de saf kâra göre sıralamak isterse üstteki "profit" çipine
 * basabilir.
 */
public class BetaTab {

    /** Bazaar satış vergisi. */
    private static final double TAX = 0.0125;

    /** Hesaplanmış tek satır. */
    private record Row(String name, String baseId, int buyLevel, int sellLevel, int qty,
                       double buyPrice, long buyDaily,
                       double sellPrice, long sellDaily,
                       double clean, double percent,
                       double craftsPerDay, double score) {
    }

    /** Sıralama ölçütleri. Etiketler arayüzdeki çiplerin üstünde yazan metin. */
    private enum SortKey {
        SCORE("score"),
        PROFIT("profit"),
        PERCENT("profit %"),
        BUY_VOL("buy vol"),
        SELL_VOL("sell vol");

        final String label;

        SortKey(String label) {
            this.label = label;
        }
    }

    private static final int ROW_H = 36;
    private static final int TOP_H = 54;

    // --- satır sonundaki "..." menüsü ---
    //
    // ESKIDEN her satirda yan yana duran [+ Save] ve [Hide] butonlari vardi ve
    // satir 2'deki "per day ~1.2B" yazisi onlarin ALTINA kaciyordu. Artik satir
    // sonunda 20px'lik tek bir nokta butonu duruyor; UZERINE GELINCE iki secenek
    // onun solunda opak bir kutu icinde aciliyor. Boylece normal halde yazilara
    // 68px daha yer kaliyor ve hicbir sey ust uste binmiyor.
    private static final int MENU_W = 20;
    private static final int MENU_H = 16;
    private static final int POPUP_SAVE_W = 46;
    private static final int POPUP_HIDE_W = 38;
    private static final int POPUP_GAP = 4;
    private static final int POPUP_PAD = 5;

    /** Kara liste satirindaki "Restore" butonu - orada yer bol, menuye gerek yok. */
    private static final int RESTORE_W = 62;

    private int x, y, w, h;
    private int listY, listH;
    private int rowBtnH;
    private int menuX, popupX, popupW, saveBtnX, hideBtnX;
    private int contentRight;
    private int scroll = 0;

    private Widgets.Button refreshButton;
    private Widgets.Button blacklistButton;

    private final Widgets.ScrollBar bar = new Widgets.ScrollBar();

    private List<Row> rows = new ArrayList<>();
    private int builtFrom = -1;

    private SortKey sortKey = SortKey.SCORE;
    private boolean descending = true;

    /** Kara liste görünümü açık mı? (aynı liste alanı iki iş görüyor) */
    private boolean showBlacklist = false;

    /**
     * Menüsü açık olan satırın listedeki mutlak sırası (-1 = hiçbiri).
     *
     * Menü "..." butonunun üstüne gelince AÇILIR, ama kutunun tamamından
     * çıkılana kadar AÇIK KALIR. Sadece butonun üstünü sayarsak fareyi
     * "+ Save"e götürmek için butondan ayrılmak gerekir ve kutu tam o anda
     * kapanır - tıklanamaz.
     */
    private int openRow = -1;

    /** Çip alanları - her karede çizerken doldurulur, tıklamada okunur. */
    private final List<int[]> chipBounds = new ArrayList<>();
    private final List<SortKey> chipKeys = new ArrayList<>();

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        listY = y + TOP_H;
        listH = Math.max(80, (y + h) - listY);

        rowBtnH = MENU_H;
        // Sag kenar: kaydirma cubugu icin 12px bosluk birakilir.
        menuX = x + w - 12 - MENU_W;
        popupW = POPUP_PAD * 2 + POPUP_SAVE_W + POPUP_GAP + POPUP_HIDE_W;
        popupX = menuX - popupW - 2;
        saveBtnX = popupX + POPUP_PAD;
        hideBtnX = saveBtnX + POPUP_SAVE_W + POPUP_GAP;
        contentRight = menuX - 10;

        refreshButton = new Widgets.Button("Refresh", this::refresh)
                .bounds(x + w - 74, y + 1, 74, 20)
                .accent(Theme.GREEN);

        blacklistButton = new Widgets.Button("Blacklist", this::toggleBlacklistView)
                .bounds(x + w - 74 - 6 - 78, y + 1, 78, 20)
                .accent(Theme.YELLOW);
    }

    /** Her şeyi sıfırlar ve bazaar'dan yeniden çeker. */
    private void refresh() {
        rows = new ArrayList<>();
        builtFrom = -1;
        scroll = 0;
        openRow = -1;
        BazaarLookup.forceRefresh();
        ItemCatalog.refresh();
    }

    private void toggleBlacklistView() {
        showBlacklist = !showBlacklist;
        scroll = 0;
        openRow = -1;
        bar.release();
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
            // Kara listedekiler hic hesaplanmaz bile.
            if (GoofyConfig.isBlacklisted(entry.baseId())) continue;

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

                long buyDaily = buy.sellMovingWeek() / 7;
                long sellDaily = sell.buyMovingWeek() / 7;

                // Gunde kac tane tamamlanabilir: hem alim hem satim tarafi sinirlar.
                double craftsPerDay = Math.min(buyDaily / (double) qty, (double) sellDaily);
                double score = clean * craftsPerDay;

                fresh.add(new Row(entry.displayName(), entry.baseId(), buyLevel, maxLevel, qty,
                        buy.sellPrice(), buyDaily,
                        sell.buyPrice(), sellDaily,
                        clean, clean / cost * 100.0,
                        craftsPerDay, score));
            }
        }

        rows = fresh;
        builtFrom = version;
        applySort();
    }

    /** Aktif ölçüte ve yöne göre listeyi yerinde sıralar. */
    private void applySort() {
        Comparator<Row> comparator = switch (sortKey) {
            case SCORE -> Comparator.comparingDouble(Row::score);
            case PROFIT -> Comparator.comparingDouble(Row::clean);
            case PERCENT -> Comparator.comparingDouble(Row::percent);
            case BUY_VOL -> Comparator.comparingDouble(r -> r.buyDaily());
            case SELL_VOL -> Comparator.comparingDouble(r -> r.sellDaily());
        };
        if (descending) comparator = comparator.reversed();
        // Esitlikte kar kirar - ayni hacimli iki kitap rastgele siralanmasin.
        rows = new ArrayList<>(rows);
        rows.sort(comparator.thenComparing(Comparator.comparingDouble(Row::clean).reversed()));
    }

    private void clickSort(SortKey key) {
        if (sortKey == key) {
            descending = !descending;
        } else {
            sortKey = key;
            descending = true;
        }
        scroll = 0;
        openRow = -1;
        applySort();
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        BazaarLookup.refreshIfStale();
        rebuildIfNeeded();

        List<String> hidden = GoofyConfig.blacklist();

        Draw.text(g, showBlacklist ? "BLACKLIST" : "PROFIT SCANNER", x, y + 3, Theme.TEXT);

        // Alt yazi butonlarin ALTINA degil SOLUNA yaziliyor - butonlar y+1'den
        // y+21'e kadar uzaniyor, o yuzden metin genisligi siniri sart.
        String subtitle = showBlacklist
                ? (hidden.isEmpty()
                ? "nothing hidden - use 'Hide' on a scanner row"
                : hidden.size() + " book(s) hidden from the scanner")
                : (rows.isEmpty()
                ? "reading the bazaar..."
                : rows.size() + " profitable lines"
                + (hidden.isEmpty() ? "" : "  -  " + hidden.size() + " blacklisted"));
        Draw.text(g, Draw.clip(subtitle, w - 170), x, y + 16, Theme.TEXT_FAINT);

        blacklistButton.label = showBlacklist ? "Back" : "Blacklist" + (hidden.isEmpty() ? "" : " " + hidden.size());
        refreshButton.render(g, mouseX, mouseY);
        blacklistButton.render(g, mouseX, mouseY);

        if (!showBlacklist) renderSortChips(g, mouseX, mouseY);

        Draw.panel(g, x, listY, w, listH, Theme.CARD, Theme.STROKE);

        if (showBlacklist) {
            renderBlacklist(g, hidden, mouseX, mouseY);
            return;
        }

        if (rows.isEmpty()) {
            int cy = listY + listH / 2;
            Draw.textCentered(g, BazaarLookup.isReady() ? "No profitable book right now" : "Loading bazaar data...",
                    x + w / 2, cy - 8, Theme.TEXT_DIM);
            Draw.textCentered(g, "Press 'Refresh' to pull fresh prices.", x + w / 2, cy + 6, Theme.TEXT_FAINT);
            return;
        }

        int visible = visibleRows();
        bar.bounds(x, listY, w, listH);
        bar.setContent(rows.size(), visible);
        if (bar.isDragging()) scroll = bar.drag(mouseY);
        scroll = clamp(scroll, 0, bar.maxScroll());

        for (int i = 0; i < visible && (i + scroll) < rows.size(); i++) {
            renderRow(g, rows.get(i + scroll), i + scroll, listY + 2 + i * ROW_H, mouseX, mouseY);
        }

        bar.render(g, scroll, mouseX, mouseY);
    }

    /** Sıralama çipleri. Aktif olanın yanında yön oku var. */
    private void renderSortChips(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        chipBounds.clear();
        chipKeys.clear();

        int cx = x;
        int cy = y + 31;
        Draw.text(g, "sort", cx, cy + 4, Theme.TEXT_FAINT);
        cx += Draw.textWidth("sort") + 8;

        for (SortKey key : SortKey.values()) {
            boolean active = sortKey == key;
            String label = key.label + (active ? (descending ? "  \u2193" : "  \u2191") : "");
            int cw = Draw.textWidth(label) + 14;
            boolean hover = Draw.inside(mouseX, mouseY, cx, cy, cw, 16);

            Draw.roundRect(g, cx, cy, cw, 16, active ? Theme.ACCENT_SOFT : (hover ? Theme.HOVER : Theme.INSET));
            Draw.textCentered(g, label, cx + cw / 2, cy + 4, active ? Theme.ACCENT : Theme.TEXT_DIM);

            chipBounds.add(new int[]{cx, cy, cw, 16});
            chipKeys.add(key);
            cx += cw + 5;
        }
    }

    private void renderRow(GuiGraphicsExtractor g, Row row, int index, int ry, int mouseX, int mouseY) {
        boolean saved = BookPresets.has(row.baseId(), row.buyLevel());
        int btnY = rowBtnY(ry);

        // Ac: "..." butonunun ustunde. Kapa: kutunun tamamindan cikinca.
        if (Draw.inside(mouseX, mouseY, menuX, btnY, MENU_W, MENU_H)) {
            openRow = index;
        } else if (openRow == index && !insideMenuZone(mouseX, mouseY, ry)) {
            openRow = -1;
        }
        boolean open = openRow == index;

        if (Draw.inside(mouseX, mouseY, x + 1, ry, w - 2, ROW_H - 2)) {
            Draw.rect(g, x + 1, ry, w - 2, ROW_H - 2, Theme.HOVER);
        }
        // Kaydedilmis kitabin sol seridi YESIL - menuyu acmadan da anlasilsin.
        Draw.rect(g, x + 1, ry + 4, 2, ROW_H - 12, saved ? Theme.GREEN : Theme.ACCENT_SOFT);

        // --- satir 1: isim, hat, gereken adet ... sagda temiz kar
        String profit = "+" + StatsTab.coins(row.clean()) + "  (" + Math.round(row.percent()) + "%)";
        int profitW = Draw.textWidth(profit);
        Draw.textRight(g, profit, contentRight, ry + 4, Theme.GREEN);

        String head = row.name() + "   " + row.buyLevel() + " \u2192 " + row.sellLevel();
        int headMax = contentRight - profitW - 14 - (x + 12) - Draw.textWidth("x" + row.qty()) - 8;
        String clipped = Draw.clip(head, Math.max(40, headMax));
        Draw.text(g, clipped, x + 12, ry + 4, Theme.TEXT);
        Draw.text(g, "x" + row.qty(), x + 12 + Draw.textWidth(clipped) + 8, ry + 4, Theme.TEXT_FAINT);

        // --- satir 2: fiyatlar, gunluk hacimler, gunluk beklenen kazanc
        int cx = x + 12;
        cx = cell(g, cx, ry + 19, "buy", StatsTab.coins(row.buyPrice()) + "  " + row.buyDaily() + "/d",
                Theme.TEXT_DIM, contentRight);
        cx = cell(g, cx, ry + 19, "sell", StatsTab.coins(row.sellPrice()) + "  " + row.sellDaily() + "/d",
                Theme.TEXT_DIM, contentRight);
        cell(g, cx, ry + 19, "per day", perDay(row), row.score() > 0 ? Theme.ACCENT : Theme.RED, contentRight);

        // --- satir sonu: "..." butonu, uzerine gelince secenekler
        if (open) {
            // Opak kutu: altindaki yazilari tamamen kapatir, ust uste binme olmaz.
            Draw.panel(g, popupX, btnY - 3, popupW, MENU_H + 6, Theme.INSET, Theme.STROKE);
            if (saved) {
                Draw.textCentered(g, "saved", saveBtnX + POPUP_SAVE_W / 2, btnY + 4, Theme.GREEN);
            } else {
                miniButton(g, "+ Save", saveBtnX, btnY, POPUP_SAVE_W, mouseX, mouseY, Theme.GREEN);
            }
            miniButton(g, "Hide", hideBtnX, btnY, POPUP_HIDE_W, mouseX, mouseY, Theme.RED);
        }

        Draw.roundRect(g, menuX, btnY, MENU_W, MENU_H, open ? Theme.HOVER : Theme.INSET);
        int dotColor = open ? Theme.ACCENT : Theme.TEXT_DIM;
        int dotX = menuX + (MENU_W - 12) / 2;
        int dotY = btnY + MENU_H / 2 - 1;
        for (int d = 0; d < 3; d++) {
            Draw.rect(g, dotX + d * 5, dotY, 2, 2, dotColor);
        }
    }

    private int rowBtnY(int ry) {
        return ry + (ROW_H - 2 - MENU_H) / 2;
    }

    /** Restore butonu, "..." butonuyla aynı sağ kenara hizalanır. */
    private int restoreX() {
        return menuX + MENU_W - RESTORE_W;
    }

    /** A\u00e7\u0131k men\u00fcn\u00fcn "ka\u00e7madan" gezinebilece\u011fin alan: kutu + "..." butonu. */
    private boolean insideMenuZone(double mouseX, double mouseY, int ry) {
        return Draw.inside(mouseX, mouseY, popupX, ry, (menuX + MENU_W) - popupX, ROW_H - 2);
    }

    /** "gunde ~kac craft, ~ne kadar coin" ozeti. */
    private static String perDay(Row row) {
        if (row.craftsPerDay() < 0.05) return "not tradable";
        String crafts = row.craftsPerDay() >= 10
                ? String.valueOf(Math.round(row.craftsPerDay()))
                : String.format("%.1f", row.craftsPerDay());
        return crafts + "x  ~" + StatsTab.coins(row.score());
    }

    private void renderBlacklist(GuiGraphicsExtractor g, List<String> hidden, int mouseX, int mouseY) {
        if (hidden.isEmpty()) {
            int cy = listY + listH / 2;
            Draw.textCentered(g, "The blacklist is empty", x + w / 2, cy - 8, Theme.TEXT_DIM);
            Draw.textCentered(g, "Hide a book in the scanner and it shows up here.",
                    x + w / 2, cy + 6, Theme.TEXT_FAINT);
            return;
        }

        int rowH = 22;
        int visible = Math.max(1, (listH - 4) / rowH);
        bar.bounds(x, listY, w, listH);
        bar.setContent(hidden.size(), visible);
        if (bar.isDragging()) scroll = bar.drag(mouseY);
        scroll = clamp(scroll, 0, bar.maxScroll());

        for (int i = 0; i < visible && (i + scroll) < hidden.size(); i++) {
            String baseId = hidden.get(i + scroll);
            int ry = listY + 2 + i * rowH;

            if (Draw.inside(mouseX, mouseY, x + 1, ry, w - 2, rowH - 2)) {
                Draw.rect(g, x + 1, ry, w - 2, rowH - 2, Theme.HOVER);
            }
            Draw.text(g, Draw.clip(ItemCatalog.displayNameOf(baseId), w - RESTORE_W - 40),
                    x + 12, ry + 6, Theme.TEXT_DIM);
            miniButton(g, "Restore", restoreX(), ry + 3, RESTORE_W, mouseX, mouseY, Theme.GREEN);
        }

        bar.render(g, scroll, mouseX, mouseY);
    }

    /**
     * "etiket değer" çifti çizer ve bir sonraki kolonun x'ini döndürür.
     * limit = yazının geçemeyeceği sağ sınır; taşan değer kırpılır.
     */
    private int cell(GuiGraphicsExtractor g, int cx, int cy, String label, String value,
                     int valueColor, int limit) {
        if (cx >= limit) return cx;
        Draw.text(g, label, cx, cy, Theme.TEXT_FAINT);
        int vx = cx + Draw.textWidth(label) + 4;
        String shown = Draw.clip(value, Math.max(8, limit - vx));
        Draw.text(g, shown, vx, cy, valueColor);
        return vx + Draw.textWidth(shown) + 12;
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
        if (blacklistButton.mouseClicked(mouseX, mouseY)) return true;
        if (bar.mouseClicked(mouseX, mouseY)) return true;

        if (showBlacklist) return blacklistClicked(mouseX, mouseY);

        for (int i = 0; i < chipBounds.size(); i++) {
            int[] b = chipBounds.get(i);
            if (Draw.inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
                clickSort(chipKeys.get(i));
                return true;
            }
        }

        int visible = visibleRows();
        for (int i = 0; i < visible && (i + scroll) < rows.size(); i++) {
            Row row = rows.get(i + scroll);
            int ry = listY + 2 + i * ROW_H;

            // Yalnizca menusu ACIK satirda tiklanacak bir sey var - kutuyu da
            // sadece o satirda ciziyoruz, ikisi birebir ayni kosul.
            if (openRow != i + scroll) continue;

            int by = rowBtnY(ry);
            if (Draw.inside(mouseX, mouseY, hideBtnX, by, POPUP_HIDE_W, MENU_H)) {
                GoofyConfig.blacklist(row.baseId());
                // Ayni kitabin level 1 / level 2 satirlarinin ikisi de gitmeli.
                rows = new ArrayList<>(rows);
                rows.removeIf(r -> r.baseId().equals(row.baseId()));
                scroll = clamp(scroll, 0, Math.max(0, rows.size() - visible));
                return true;
            }
            if (Draw.inside(mouseX, mouseY, saveBtnX, by, POPUP_SAVE_W, MENU_H)) {
                if (BookPresets.has(row.baseId(), row.buyLevel())) return true;
                BookPresets.addBook(new Book(row.baseId(), row.buyLevel(), row.sellLevel(), row.name()));
                return true;
            }
            // Kutunun bosluguna ya da "..." butonuna basildi - tiklamayi yut.
            return true;
        }
        return false;
    }

    private boolean blacklistClicked(double mouseX, double mouseY) {
        List<String> hidden = GoofyConfig.blacklist();
        int rowH = 22;
        int visible = Math.max(1, (listH - 4) / rowH);

        for (int i = 0; i < visible && (i + scroll) < hidden.size(); i++) {
            int ry = listY + 2 + i * rowH;
            if (!Draw.inside(mouseX, mouseY, restoreX(), ry + 3, RESTORE_W, MENU_H)) continue;
            GoofyConfig.unBlacklist(hidden.get(i + scroll));
            // Geri alinan kitap taramada yeniden gorunsun diye tablo yeniden kurulur.
            builtFrom = -1;
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        bar.release();
    }

    public boolean mouseScrolled(double direction) {
        scroll = Math.max(0, scroll - (int) Math.signum(direction));
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
