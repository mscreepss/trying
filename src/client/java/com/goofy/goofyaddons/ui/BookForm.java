package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.BookPresets;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.bookflipper.helper.BazaarLookup;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.features.bookflipper.helper.ItemCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kitap ekleme/düzenleme formu. Ayrı bir Screen değil, GoofyScreen'in üstüne
 * çizilen bir modal.
 *
 * ID BULMA: "wisdom" yazmak tek başına yetmez - "Wisdom" ile "Ultimate Wisdom"
 * ayrı ürünlerdir. Bu yüzden ID hiçbir zaman tahminle doldurulmaz:
 *
 *  1) FROM INVENTORY : Envanterindeki kitapların lore'u okunur, isim ve seviye
 *     birebir oradan alınır. Tahmin yok.
 *  2) SEARCH + ONAY  : Yazdıkça CANLI filtrelenen liste; adaylar isim + ID +
 *     anlık fiyatla birlikte görünür. Seçtiğin aday doğrudan uygulanmaz,
 *     "Did you mean this?" onayı sorulur.
 *
 * ARAMA ARTIK BAZAAR'DAN BESLENİYOR (ItemCatalog): eskiden ayrı bir istek
 * gecikince liste boş kalıyor ve arama hiçbir sonuç vermiyordu.
 */
public class BookForm {

    /** Kaydedince nereye yazilacak. */
    public enum Target {
        /** Presets sayfasi: kitap kutuphaneye eklenir. */
        PRESET,
        /** Config sayfasi: dogrudan aktif kitap listesi duzenlenir. */
        CONFIG
    }

    private enum Overlay {
        NONE,
        SEARCH,
        CONFIRM,
        INVENTORY
    }

    /** Bazaar satış vergisi. FlipCalculator ile aynı varsayım. */
    private static final double TAX = 0.0125;

    private final int editIndex; // -1 = yeni kayıt
    private final Target target;
    private final Runnable onClose;

    private final Widgets.TextBox idBox;
    private final Widgets.TextBox nameBox;
    private final Widgets.TextBox levelBox;
    private final Widgets.TextBox sellLevelBox;
    private final List<Widgets.TextBox> boxes = new ArrayList<>();

    private final Widgets.Button saveButton;
    private final Widgets.Button cancelButton;
    private final Widgets.Button searchButton;
    private final Widgets.Button inventoryButton;

    private String error = null;

    private int x, y, w, h;
    private int screenW, screenH;
    private int previewY, previewH;

    // --- katman durumu ---
    private Overlay overlay = Overlay.NONE;
    private int overlayX, overlayY, overlayW, overlayH;
    private int overlayListY, overlayListH;
    private final int overlayRowH = 24;
    private int overlayScroll = 0;

    private final Widgets.TextBox searchBox;
    private List<ItemCatalog.Entry> searchResults = new ArrayList<>();
    private List<InventoryBook> inventoryBooks = new ArrayList<>();
    private ItemCatalog.Entry pendingChoice = null;
    private final Widgets.Button confirmYes;
    private final Widgets.Button confirmNo;

    /** Envanterde bulunan bir kitap: lore'dan birebir okunmuş isim + seviye. */
    private record InventoryBook(String name, int level, String baseId, int count) {
    }

    public BookForm(Book existing, int editIndex, Runnable onClose) {
        this(existing, editIndex, Target.CONFIG, onClose);
    }

    public BookForm(Book existing, int editIndex, Target target, Runnable onClose) {
        this.editIndex = editIndex;
        this.target = target;
        this.onClose = onClose;

        idBox = new Widgets.TextBox(existing == null ? "" : existing.id())
                .placeholder("ENCHANTMENT_ULTIMATE_WISE");
        idBox.maxLength = 64;
        nameBox = new Widgets.TextBox(existing == null ? "" : existing.name()).placeholder("Ultimate Wise");
        nameBox.maxLength = 32;
        levelBox = new Widgets.TextBox(existing == null ? "1" : String.valueOf(existing.level())).numeric(true);
        levelBox.maxLength = 2;
        sellLevelBox = new Widgets.TextBox(existing == null ? "5" : String.valueOf(existing.sellLevel())).numeric(true);
        sellLevelBox.maxLength = 2;

        boxes.add(idBox);
        boxes.add(nameBox);
        boxes.add(levelBox);
        boxes.add(sellLevelBox);

        searchBox = new Widgets.TextBox("").placeholder("type to search... e.g. wisdom");
        searchBox.maxLength = 32;
        searchBox.focused = true;

        saveButton = new Widgets.Button(editIndex < 0 ? "Add" : "Save", this::save)
                .accent(Theme.ACCENT).filled(true);
        cancelButton = new Widgets.Button("Cancel", onClose).accent(Theme.TEXT_DIM);
        searchButton = new Widgets.Button("Search", this::openSearch).accent(Theme.ACCENT);
        inventoryButton = new Widgets.Button("From Inventory", this::openInventory).accent(Theme.GREEN);

        confirmYes = new Widgets.Button("Yes, use this", this::applyPendingChoice).accent(Theme.GREEN).filled(true);
        confirmNo = new Widgets.Button("No, go back", () -> overlay = Overlay.SEARCH).accent(Theme.TEXT_DIM);
    }

    // =====================================================================
    // Yerleşim
    // =====================================================================

    public void layout(int screenWidth, int screenHeight) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;

        w = Math.min(380, screenWidth - 24);
        h = Math.min(300, screenHeight - 24);
        x = (screenWidth - w) / 2;
        y = (screenHeight - h) / 2;

        int fieldX = x + 18;
        int fieldW = w - 36;
        int sideW = 104;

        int row = y + 54;
        idBox.bounds(fieldX, row, fieldW - sideW - 8, 20);
        searchButton.bounds(fieldX + fieldW - sideW, row, sideW, 20);

        row += 38;
        nameBox.bounds(fieldX, row, fieldW - sideW - 8, 20);
        inventoryButton.bounds(fieldX + fieldW - sideW, row, sideW, 20);

        row += 38;
        int half = (fieldW - 12) / 2;
        levelBox.bounds(fieldX, row, half, 20);
        sellLevelBox.bounds(fieldX + half + 12, row, half, 20);

        previewY = row + 32;
        previewH = 74;

        int buttonY = y + h - 34;
        cancelButton.bounds(fieldX, buttonY, (fieldW - 10) / 2, 22);
        saveButton.bounds(fieldX + (fieldW - 10) / 2 + 10, buttonY, (fieldW - 10) / 2, 22);

        // --- katman ---
        overlayW = Math.min(360, screenWidth - 48);
        overlayH = Math.min(250, screenHeight - 48);
        overlayX = (screenWidth - overlayW) / 2;
        overlayY = (screenHeight - overlayH) / 2;

        searchBox.bounds(overlayX + 14, overlayY + 30, overlayW - 28, 20);
        overlayListY = overlayY + 58;
        overlayListH = overlayH - 58 - 14;
    }

    public void tick() {
        for (Widgets.TextBox box : boxes) box.tick();
        searchBox.tick();
    }

    /** Menü tuşu bu form açıkken formu kapatmasın diye. */
    public boolean isTyping() {
        if (overlay == Overlay.SEARCH) return true;
        for (Widgets.TextBox box : boxes) {
            if (box.focused) return true;
        }
        return false;
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        BazaarLookup.refreshIfStale();

        Draw.rect(g, 0, 0, screenW, screenH, 0x99000000);

        Draw.panel(g, x, y, w, h, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, x + 1, y + 1, w - 2, 1, Theme.ACCENT);

        Draw.text(g, (editIndex < 0 ? "New Book" : "Edit Book")
                + (target == Target.PRESET ? "  (preset)" : ""), x + 18, y + 18, Theme.TEXT);
        Draw.text(g, "You don't have to type the ID by hand", x + 18, y + 32, Theme.TEXT_FAINT);

        label(g, "BAZAAR ID", idBox);
        label(g, "NAME  (as shown in the item lore)", nameBox);
        label(g, "BUY LEVEL", levelBox);
        label(g, "SELL LEVEL", sellLevelBox);

        for (Widgets.TextBox box : boxes) box.render(g, mouseX, mouseY);
        searchButton.render(g, mouseX, mouseY);
        inventoryButton.render(g, mouseX, mouseY);

        renderPreview(g);

        if (error != null) {
            Draw.text(g, Draw.clip(error, w - 36), x + 18, y + h - 50, Theme.RED);
        }

        cancelButton.render(g, mouseX, mouseY);
        saveButton.render(g, mouseX, mouseY);

        switch (overlay) {
            case SEARCH -> renderSearchOverlay(g, mouseX, mouseY);
            case CONFIRM -> renderConfirmOverlay(g, mouseX, mouseY);
            case INVENTORY -> renderInventoryOverlay(g, mouseX, mouseY);
            case NONE -> {
            }
        }
    }

    private void label(GuiGraphicsExtractor g, String text, Widgets.TextBox box) {
        Draw.text(g, text, box.x, box.y - 12, Theme.TEXT_FAINT);
    }

    // ------------------------------------------------------------------ önizleme

    private void renderPreview(GuiGraphicsExtractor g) {
        int px = x + 18;
        int pw = w - 36;
        Draw.panel(g, px, previewY, pw, previewH, Theme.CARD, Theme.STROKE);

        String baseId = normalizedId();
        int level = levelBox.intValue(-1);
        int sellLevel = sellLevelBox.intValue(-1);

        if (baseId.isEmpty()) {
            Draw.text(g, "Enter an ID, or use 'Search' / 'From Inventory'", px + 12, previewY + 12, Theme.TEXT_FAINT);
            Draw.text(g, ItemCatalog.status(), px + 12, previewY + 26, Theme.TEXT_FAINT);
            return;
        }
        if (level < 1 || sellLevel <= level) {
            Draw.text(g, "Check the buy / sell level values", px + 12, previewY + 12, Theme.YELLOW);
            return;
        }
        if (!BazaarLookup.isReady()) {
            Draw.text(g, "Loading bazaar prices...", px + 12, previewY + 12, Theme.TEXT_FAINT);
            return;
        }

        BazaarLookup.Quick buy = BazaarLookup.get(baseId + "_" + level);
        BazaarLookup.Quick sell = BazaarLookup.get(baseId + "_" + sellLevel);

        if (buy == null || sell == null) {
            Draw.text(g, "Not found on the bazaar", px + 12, previewY + 12, Theme.RED);
            Draw.text(g, Draw.clip(baseId + "_" + level, pw - 24), px + 12, previewY + 26, Theme.TEXT_FAINT);
            Draw.text(g, "Use 'Search' to pick the right product", px + 12, previewY + 40, Theme.TEXT_FAINT);
            return;
        }

        int qty = 1 << (sellLevel - level);
        double invest = buy.sellPrice() * qty;
        double revenue = sell.buyPrice();
        double clean = revenue * (1 - TAX) - invest;
        double percent = invest > 0 ? clean / invest * 100.0 : 0;

        // Hacim: alinacak seviyenin haftalik instant-sell hacmi = "sold to me",
        // hedef seviyenin haftalik instant-buy hacmi = "bought from me".
        long dailyIn = buy.sellMovingWeek() / 7;
        long dailyOut = sell.buyMovingWeek() / 7;

        int line = previewY + 10;
        Draw.text(g, Draw.clip(displayTitle(level, sellLevel), pw - 24), px + 12, line, Theme.TEXT);

        line += 14;
        Draw.text(g, "Needed", px + 12, line, Theme.TEXT_FAINT);
        Draw.textRight(g, qty + " x " + StatsTab.coins(buy.sellPrice()) + "  =  " + StatsTab.coins(invest),
                px + pw - 12, line, Theme.TEXT);

        line += 12;
        Draw.text(g, "Sells at (" + roman(sellLevel) + ")", px + 12, line, Theme.TEXT_FAINT);
        Draw.textRight(g, StatsTab.coins(revenue) + "  -  1.25% tax", px + pw - 12, line, Theme.TEXT);

        line += 12;
        Draw.text(g, "Clean profit", px + 12, line, Theme.TEXT_FAINT);
        String cleanText = (clean >= 0 ? "+" : "-") + StatsTab.coins(Math.abs(clean))
                + "   (" + Math.round(percent) + "%)";
        Draw.textRight(g, cleanText, px + pw - 12, line, clean >= 0 ? Theme.GREEN : Theme.RED);

        line += 15;
        Draw.text(g, "Sold to me " + dailyIn + "/day", px + 12, line, Theme.TEXT_DIM);
        Draw.textRight(g, "Bought from me " + dailyOut + "/day", px + pw - 12, line, Theme.TEXT_DIM);
    }

    private String displayTitle(int level, int sellLevel) {
        String name = nameBox.value;
        if (name.isBlank()) {
            String catalogName = ItemCatalog.displayNameOf(normalizedId());
            name = catalogName == null ? normalizedId() : catalogName;
        }
        return name + "  " + roman(level) + " \u2192 " + roman(sellLevel);
    }

    // ------------------------------------------------------------------ arama katmanı

    private void renderSearchOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // Canli filtre: sonuclar HER KAREDE kutunun icerigine gore hesaplaniyor,
        // yani yazdikca liste aninda daraliyor.
        searchResults = ItemCatalog.search(searchBox.value, 80);

        Draw.rect(g, 0, 0, screenW, screenH, 0xBB000000);
        Draw.panel(g, overlayX, overlayY, overlayW, overlayH, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, overlayX + 1, overlayY + 1, overlayW - 2, 1, Theme.ACCENT);

        Draw.text(g, "Search enchanted books", overlayX + 14, overlayY + 14, Theme.TEXT);
        Draw.textRight(g, ItemCatalog.status(), overlayX + overlayW - 14, overlayY + 14, Theme.TEXT_FAINT);

        searchBox.render(g, mouseX, mouseY);
        Draw.panel(g, overlayX + 12, overlayListY, overlayW - 24, overlayListH, Theme.CARD, Theme.STROKE);

        if (searchResults.isEmpty()) {
            String message = BazaarLookup.isReady() ? "No matching book" : "Waiting for bazaar data...";
            Draw.textCentered(g, message, overlayX + overlayW / 2, overlayListY + overlayListH / 2 - 6, Theme.TEXT_FAINT);
            Draw.textCentered(g, "ESC to close", overlayX + overlayW / 2, overlayListY + overlayListH / 2 + 8, Theme.TEXT_FAINT);
            return;
        }

        int visible = Math.max(1, (overlayListH - 4) / overlayRowH);
        overlayScroll = clamp(overlayScroll, 0, Math.max(0, searchResults.size() - visible));

        for (int i = 0; i < visible && (i + overlayScroll) < searchResults.size(); i++) {
            ItemCatalog.Entry entry = searchResults.get(i + overlayScroll);
            int ry = overlayListY + 2 + i * overlayRowH;
            boolean hover = Draw.inside(mouseX, mouseY, overlayX + 13, ry, overlayW - 26, overlayRowH - 1);
            if (hover) Draw.rect(g, overlayX + 13, ry, overlayW - 26, overlayRowH - 1, Theme.HOVER);

            Draw.text(g, Draw.clip(entry.displayName(), overlayW - 150), overlayX + 24, ry + 3, Theme.TEXT);
            Draw.text(g, Draw.clip(entry.baseId(), overlayW - 150), overlayX + 24, ry + 13, Theme.TEXT_FAINT);

            BazaarLookup.Quick quick = BazaarLookup.get(entry.baseId() + "_1");
            String price = quick == null ? "-" : "buy " + StatsTab.coins(quick.sellPrice());
            Draw.textRight(g, price, overlayX + overlayW - 24, ry + 8, Theme.ACCENT);
        }

        int maxScroll = Math.max(0, searchResults.size() - visible);
        if (maxScroll > 0) {
            int barH = Math.max(14, overlayListH * visible / Math.max(1, searchResults.size()));
            int barY = overlayListY + (overlayListH - barH) * overlayScroll / maxScroll;
            Draw.rect(g, overlayX + overlayW - 15, barY, 2, barH, Theme.STROKE);
        }
    }

    // ------------------------------------------------------------------ onay katmanı

    private void renderConfirmOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.rect(g, 0, 0, screenW, screenH, 0xCC000000);

        int ch = 162;
        int cy = (screenH - ch) / 2;
        Draw.panel(g, overlayX, cy, overlayW, ch, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, overlayX + 1, cy + 1, overlayW - 2, 1, Theme.YELLOW);

        Draw.text(g, "Did you mean this?", overlayX + 18, cy + 18, Theme.TEXT);
        Draw.text(g, "Several products can share a similar name - double check.",
                overlayX + 18, cy + 32, Theme.TEXT_FAINT);

        if (pendingChoice != null) {
            Draw.panel(g, overlayX + 18, cy + 48, overlayW - 36, 56, Theme.CARD, Theme.STROKE);
            Draw.text(g, Draw.clip(pendingChoice.displayName(), overlayW - 58),
                    overlayX + 28, cy + 57, Theme.TEXT);
            Draw.text(g, Draw.clip(pendingChoice.baseId(), overlayW - 58),
                    overlayX + 28, cy + 70, Theme.ACCENT);

            BazaarLookup.Quick quick = BazaarLookup.get(pendingChoice.baseId() + "_1");
            String price = quick == null
                    ? "no bazaar price found"
                    : "buy " + StatsTab.coins(quick.sellPrice()) + "   sell " + StatsTab.coins(quick.buyPrice());
            Draw.text(g, price, overlayX + 28, cy + 85, Theme.TEXT_DIM);
        }

        int by = cy + ch - 30;
        int bw = (overlayW - 44) / 2;
        confirmNo.bounds(overlayX + 18, by, bw, 22);
        confirmYes.bounds(overlayX + 26 + bw, by, bw, 22);
        confirmNo.render(g, mouseX, mouseY);
        confirmYes.render(g, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ envanter katmanı

    private void renderInventoryOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.rect(g, 0, 0, screenW, screenH, 0xBB000000);
        Draw.panel(g, overlayX, overlayY, overlayW, overlayH, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, overlayX + 1, overlayY + 1, overlayW - 2, 1, Theme.GREEN);

        Draw.text(g, "Books in your inventory", overlayX + 14, overlayY + 14, Theme.TEXT);
        Draw.textRight(g, "read from the item lore", overlayX + overlayW - 14, overlayY + 14, Theme.TEXT_FAINT);

        int listY = overlayY + 34;
        int listH = overlayH - 34 - 14;
        Draw.panel(g, overlayX + 12, listY, overlayW - 24, listH, Theme.CARD, Theme.STROKE);

        if (inventoryBooks.isEmpty()) {
            Draw.textCentered(g, "No enchanted book found in your inventory",
                    overlayX + overlayW / 2, listY + listH / 2 - 8, Theme.TEXT_FAINT);
            Draw.textCentered(g, "Put one in your inventory and try again",
                    overlayX + overlayW / 2, listY + listH / 2 + 6, Theme.TEXT_FAINT);
            return;
        }

        int visible = Math.max(1, (listH - 4) / overlayRowH);
        overlayScroll = clamp(overlayScroll, 0, Math.max(0, inventoryBooks.size() - visible));

        for (int i = 0; i < visible && (i + overlayScroll) < inventoryBooks.size(); i++) {
            InventoryBook entry = inventoryBooks.get(i + overlayScroll);
            int ry = listY + 2 + i * overlayRowH;
            boolean hover = Draw.inside(mouseX, mouseY, overlayX + 13, ry, overlayW - 26, overlayRowH - 1);
            if (hover) Draw.rect(g, overlayX + 13, ry, overlayW - 26, overlayRowH - 1, Theme.HOVER);

            Draw.text(g, Draw.clip(entry.name() + "  " + roman(entry.level()), overlayW - 120),
                    overlayX + 24, ry + 3, Theme.TEXT);
            Draw.text(g, Draw.clip(entry.baseId(), overlayW - 120), overlayX + 24, ry + 13, Theme.TEXT_FAINT);
            Draw.textRight(g, "x" + entry.count(), overlayX + overlayW - 24, ry + 8, Theme.ACCENT);
        }
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;

        switch (overlay) {
            case SEARCH -> {
                searchBox.mouseClicked(mouseX, mouseY);
                clickList(mouseX, mouseY, overlayListY, overlayListH, searchResults.size(), index -> {
                    pendingChoice = searchResults.get(index);
                    overlay = Overlay.CONFIRM;
                });
                return true;
            }
            case INVENTORY -> {
                clickList(mouseX, mouseY, overlayY + 34, overlayH - 34 - 14, inventoryBooks.size(), index -> {
                    InventoryBook entry = inventoryBooks.get(index);
                    idBox.value = entry.baseId();
                    nameBox.value = entry.name();
                    levelBox.value = "1";
                    int max = ItemCatalog.maxLevel(entry.baseId());
                    sellLevelBox.value = String.valueOf(max > 1 ? max : 5);
                    error = null;
                    overlay = Overlay.NONE;
                });
                return true;
            }
            case CONFIRM -> {
                if (confirmYes.mouseClicked(mouseX, mouseY)) return true;
                if (confirmNo.mouseClicked(mouseX, mouseY)) return true;
                return true;
            }
            case NONE -> {
            }
        }

        for (Widgets.TextBox box : boxes) box.mouseClicked(mouseX, mouseY);
        if (searchButton.mouseClicked(mouseX, mouseY)) return true;
        if (inventoryButton.mouseClicked(mouseX, mouseY)) return true;
        if (saveButton.mouseClicked(mouseX, mouseY)) return true;
        if (cancelButton.mouseClicked(mouseX, mouseY)) return true;
        return true; // modal: arkadaki hiçbir şeye tıklama geçmesin
    }

    private void clickList(double mouseX, double mouseY, int listY, int listH, int size,
                           java.util.function.IntConsumer onPick) {
        int visible = Math.max(1, (listH - 4) / overlayRowH);

        for (int i = 0; i < visible && (i + overlayScroll) < size; i++) {
            int ry = listY + 2 + i * overlayRowH;
            if (!Draw.inside(mouseX, mouseY, overlayX + 13, ry, overlayW - 26, overlayRowH - 1)) continue;
            onPick.accept(i + overlayScroll);
            return;
        }
    }

    public boolean mouseScrolled(double direction) {
        if (overlay != Overlay.SEARCH && overlay != Overlay.INVENTORY) return false;
        overlayScroll = Math.max(0, overlayScroll - (int) Math.signum(direction));
        return true;
    }

    /** true dönerse tuş yutuldu. */
    public boolean keyPressed(int keyCode) {
        if (overlay != Overlay.NONE) {
            if (keyCode == 256) { // ESC - sadece katmanı kapat
                overlay = Overlay.NONE;
                return true;
            }
            if (overlay == Overlay.CONFIRM && (keyCode == 257 || keyCode == 335)) {
                applyPendingChoice();
                return true;
            }
            if (overlay == Overlay.SEARCH) {
                searchBox.focused = true;
                if (searchBox.keyPressed(keyCode)) overlayScroll = 0;
            }
            return true;
        }

        if (keyCode == 256) { // ESC
            onClose.run();
            return true;
        }
        if (keyCode == 258) { // TAB
            focusNext();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // ENTER
            save();
            return true;
        }
        for (Widgets.TextBox box : boxes) {
            if (box.keyPressed(keyCode)) return true;
        }
        return true;
    }

    public boolean charTyped(char chr) {
        if (overlay == Overlay.SEARCH) {
            searchBox.focused = true;
            searchBox.charTyped(chr);
            overlayScroll = 0;
            return true;
        }
        if (overlay != Overlay.NONE) return true;
        for (Widgets.TextBox box : boxes) {
            if (box.charTyped(chr)) return true;
        }
        return true;
    }

    private void focusNext() {
        int focused = -1;
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).focused) {
                focused = i;
                break;
            }
        }
        for (Widgets.TextBox box : boxes) box.focused = false;
        boxes.get((focused + 1) % boxes.size()).focused = true;
    }

    // =====================================================================
    // Aksiyonlar
    // =====================================================================

    private void openSearch() {
        // Bazaar verisi yoksa hemen tetikle - arama onu bekliyor.
        BazaarLookup.refreshIfStale();
        searchBox.value = idBox.value.isBlank() ? nameBox.value : "";
        searchBox.focused = true;
        overlayScroll = 0;
        overlay = Overlay.SEARCH;
        error = null;
    }

    /**
     * Secilen adayi forma yazar.
     *
     * ESKI HATA: isim yalnizca kutu BOSSA dolduruluyordu; ikinci bir kitap
     * secildiginde eski isim oldugu gibi kaliyordu ve yanlis lore eslesmesi
     * yuzunden hat hic calismiyordu. Artik HER secimde uc alan da yeniden yazilir.
     */
    private void applyPendingChoice() {
        if (pendingChoice == null) {
            overlay = Overlay.NONE;
            return;
        }
        idBox.value = pendingChoice.baseId();
        nameBox.value = pendingChoice.displayName();

        // Varsayilanlar: alim her zaman level 1, satis o kitabin bazaar'daki
        // EN YUKSEK seviyesi. Her kitap 5 seviye degil - sabit 5 yazmak yanlis
        // miktar hesabina yol acardi.
        levelBox.value = "1";
        int max = ItemCatalog.maxLevel(pendingChoice.baseId());
        sellLevelBox.value = String.valueOf(max > 1 ? max : 5);

        for (Widgets.TextBox box : boxes) box.selectedAll = false;
        pendingChoice = null;
        overlay = Overlay.NONE;
        error = null;
    }

    private void openInventory() {
        inventoryBooks = scanInventory();
        overlayScroll = 0;
        overlay = Overlay.INVENTORY;
        error = null;
    }

    /**
     * Envanterdeki büyü kitaplarını okur.
     *
     * NEDEN LORE: Kitabın SkyBlock ID'si NBT'de duruyor ama NBT okuma API'si
     * sürümden sürüme değişiyor. Lore okuma ise bu projede zaten her yerde
     * kullanılıyor (InventoryScanner) ve kanıtlanmış durumda. Lore satırı zaten
     * "Ultimate Wise V" biçiminde, yani ihtiyacımız olan isim + seviye orada.
     */
    private List<InventoryBook> scanInventory() {
        List<InventoryBook> result = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return result;

        Inventory playerInv = minecraft.player.getInventory();
        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (menu == null) return result;

        Map<String, InventoryBook> found = new LinkedHashMap<>();

        for (Slot slot : menu.slots) {
            if (slot.container != playerInv) continue;
            ItemStack item = slot.getItem();
            if (item.isEmpty()) continue;

            ItemLore lore = item.get(DataComponents.LORE);
            if (lore == null) continue;

            for (Component component : lore.lines()) {
                String text = component.getString().trim();
                int level = romanValue(tail(text));
                if (level <= 0) continue;

                String name = head(text);
                if (name.isBlank() || name.length() > 32) continue;

                String baseId = "ENCHANTMENT_" + name.toUpperCase().replace(' ', '_');
                String key = baseId + "|" + level;
                InventoryBook previous = found.get(key);
                found.put(key, new InventoryBook(name, level, baseId,
                        previous == null ? 1 : previous.count() + 1));
                break; // her eşya için ilk geçerli satır yeter
            }
        }

        result.addAll(found.values());
        return result;
    }

    private static String tail(String text) {
        int space = text.lastIndexOf(' ');
        return space < 0 ? "" : text.substring(space + 1);
    }

    private static String head(String text) {
        int space = text.lastIndexOf(' ');
        return space < 0 ? "" : text.substring(0, space);
    }

    private String normalizedId() {
        return idBox.value.trim().toUpperCase().replace(' ', '_');
    }

    private void save() {
        String id = normalizedId();
        String name = nameBox.value.trim();
        int level = levelBox.intValue(-1);
        int sellLevel = sellLevelBox.intValue(-1);

        if (id.isEmpty()) {
            error = "ID cannot be empty.";
            return;
        }
        if (name.isEmpty()) {
            error = "Name cannot be empty (the name shown in the lore).";
            return;
        }
        if (level < 1 || level > 9) {
            error = "Buy level must be between 1 and 9.";
            return;
        }
        if (sellLevel <= level || sellLevel > 10) {
            error = "Sell level must be higher than the buy level.";
            return;
        }

        int max = ItemCatalog.maxLevel(id);
        if (max > 0 && sellLevel > max) {
            error = "This book only goes up to level " + max + " on the bazaar.";
            return;
        }

        if (target == Target.CONFIG && GoofyConfig.hasBook(id, level, editIndex)) {
            error = "This ID + level is already configured.";
            return;
        }
        if (target == Target.PRESET && editIndex < 0 && BookPresets.has(id, level)) {
            error = "This ID + level is already saved in presets.";
            return;
        }
        // KAYDETMEDEN DOGRULAMA: bazaar verisi yuklendiyse ID'nin gercekten var
        // oldugunu kontrol et. Yanlis ID sessizce calismayan bir hat demek.
        if (BazaarLookup.isReady() && !BazaarLookup.exists(id + "_" + level)) {
            error = "Not found on the bazaar: " + id + "_" + level;
            return;
        }

        Book book = new Book(id, level, sellLevel, name);
        if (target == Target.PRESET) {
            if (editIndex < 0) {
                BookPresets.addBook(book);
            } else {
                BookPresets.replace(editIndex, book);
            }
        } else if (editIndex < 0) {
            GoofyConfig.addBook(book);
        } else {
            GoofyConfig.replaceBook(editIndex, book);
        }
        onClose.run();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int romanValue(String roman) {
        return switch (roman) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> 0;
        };
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
