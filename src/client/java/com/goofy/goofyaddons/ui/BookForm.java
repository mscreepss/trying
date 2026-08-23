package com.goofy.goofyaddons.ui;

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
 * ID BULMA PROBLEMİ VE ÇÖZÜMÜ:
 * "wisdom" yazmak yetmez - "Wisdom" ile "Ultimate Wisdom" ayrı ürünlerdir ve
 * ID'lerinden başka farkları yoktur. Tahmin etmek yanlış hatta yol açar. Bu yüzden
 * ID hiçbir zaman tahminle doldurulmaz, iki güvenli yol vardır:
 *
 *  1) ENVANTERDEN SEC : Envanterindeki kitapların lore'u okunur, isim ve seviye
 *     birebir oradan alınır. Tahmin yok. En kesin yol.
 *  2) ARA + ONAY      : ItemCatalog'dan gelen adaylar isim + ID + anlık fiyatla
 *     birlikte listelenir; seçtiğin aday DOĞRUDAN uygulanmaz, ayrıca
 *     "Bunu mu demek istedin?" onayı sorulur.
 *
 * CANLI ÖNİZLEME: Form doldurulurken yatırım, satış geliri, vergi sonrası temiz
 * kâr ve günlük hacimler anlık bazaar verisiyle gösterilir. Geçersiz ID kırmızı
 * uyarıya döner - kaydetmeden önce fark edersin.
 */
public class BookForm {

    private enum Overlay {
        NONE,
        SEARCH,
        CONFIRM,
        INVENTORY
    }

    /** Bazaar satış vergisi. FlipCalculator ile aynı varsayım. */
    private static final double TAX = 0.0125;

    private final int editIndex; // -1 = yeni kayıt
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
    private int overlayRowH = 22;
    private int overlayScroll = 0;

    private List<ItemCatalog.Entry> searchResults = new ArrayList<>();
    private List<InventoryBook> inventoryBooks = new ArrayList<>();
    private ItemCatalog.Entry pendingChoice = null;
    private Widgets.Button confirmYes, confirmNo;

    /** Envanterde bulunan bir kitap: lore'dan birebir okunmuş isim + seviye. */
    private record InventoryBook(String name, int level, String baseId, int count) {
    }

    public BookForm(Book existing, int editIndex, Runnable onClose) {
        this.editIndex = editIndex;
        this.onClose = onClose;

        idBox = new Widgets.TextBox(existing == null ? "" : existing.id())
                .placeholder("ENCHANTMENT_ULTIMATE_WISE  (ya da ara)");
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

        saveButton = new Widgets.Button(editIndex < 0 ? "Ekle" : "Kaydet", this::save)
                .accent(Theme.ACCENT).filled(true);
        cancelButton = new Widgets.Button("Iptal", onClose).accent(Theme.TEXT_DIM);
        searchButton = new Widgets.Button("Ara", this::openSearch).accent(Theme.ACCENT);
        inventoryButton = new Widgets.Button("Envanterden Sec", this::openInventory).accent(Theme.GREEN);

        confirmYes = new Widgets.Button("Evet, bu", this::applyPendingChoice).accent(Theme.GREEN).filled(true);
        confirmNo = new Widgets.Button("Hayir, geri don", () -> overlay = Overlay.SEARCH).accent(Theme.TEXT_DIM);
    }

    // =====================================================================
    // Yerleşim
    // =====================================================================

    public void layout(int screenWidth, int screenHeight) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;

        w = Math.min(320, screenWidth - 20);
        h = Math.min(268, screenHeight - 20);
        x = (screenWidth - w) / 2;
        y = (screenHeight - h) / 2;

        int fieldX = x + 16;
        int fieldW = w - 32;
        int sideW = 92;

        int row = y + 46;
        idBox.bounds(fieldX, row, fieldW - sideW - 6, 18);
        searchButton.bounds(fieldX + fieldW - sideW, row, sideW, 18);

        row += 32;
        nameBox.bounds(fieldX, row, fieldW - sideW - 6, 18);
        inventoryButton.bounds(fieldX + fieldW - sideW, row, sideW, 18);

        row += 32;
        int half = (fieldW - 10) / 2;
        levelBox.bounds(fieldX, row, half, 18);
        sellLevelBox.bounds(fieldX + half + 10, row, half, 18);

        previewY = row + 28;
        previewH = 66;

        int buttonY = y + h - 30;
        cancelButton.bounds(fieldX, buttonY, (fieldW - 8) / 2, 20);
        saveButton.bounds(fieldX + (fieldW - 8) / 2 + 8, buttonY, (fieldW - 8) / 2, 20);

        overlayW = Math.min(300, screenWidth - 40);
        overlayH = Math.min(210, screenHeight - 40);
        overlayX = (screenWidth - overlayW) / 2;
        overlayY = (screenHeight - overlayH) / 2;

        int cy = overlayY + overlayH - 30;
        int cw = (overlayW - 40) / 2;
        confirmNo.bounds(overlayX + 16, cy, cw, 20);
        confirmYes.bounds(overlayX + 24 + cw, cy, cw, 20);
    }

    public void tick() {
        for (Widgets.TextBox box : boxes) box.tick();
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        BazaarLookup.refreshIfStale();

        Draw.rect(g, 0, 0, screenW, screenH, 0x99000000);

        Draw.panel(g, x, y, w, h, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, x + 1, y + 1, w - 2, 1, Theme.ACCENT);

        Draw.text(g, editIndex < 0 ? "Yeni Kitap" : "Kitabi Duzenle", x + 16, y + 16, Theme.TEXT);
        Draw.text(g, "ID'yi elle yazmak zorunda degilsin", x + 16, y + 28, Theme.TEXT_FAINT);

        label(g, "ID", idBox);
        label(g, "NAME  (lore'da gorunen ad)", nameBox);
        label(g, "LEVEL", levelBox);
        label(g, "SELL LEVEL", sellLevelBox);

        for (Widgets.TextBox box : boxes) box.render(g, mouseX, mouseY);
        searchButton.render(g, mouseX, mouseY);
        inventoryButton.render(g, mouseX, mouseY);

        renderPreview(g);

        if (error != null) {
            Draw.text(g, Draw.clip(error, w - 32), x + 16, y + h - 44, Theme.RED);
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
        Draw.text(g, text, box.x, box.y - 11, Theme.TEXT_FAINT);
    }

    // ------------------------------------------------------------------ önizleme

    private void renderPreview(GuiGraphicsExtractor g) {
        int px = x + 16;
        int pw = w - 32;
        Draw.panel(g, px, previewY, pw, previewH, Theme.CARD, Theme.STROKE);

        String baseId = normalizedId();
        int level = levelBox.intValue(-1);
        int sellLevel = sellLevelBox.intValue(-1);

        if (baseId.isEmpty()) {
            Draw.text(g, "ID gir ya da 'Ara' / 'Envanterden Sec' kullan", px + 10, previewY + 10, Theme.TEXT_FAINT);
            Draw.text(g, ItemCatalog.status(), px + 10, previewY + 22, Theme.TEXT_FAINT);
            return;
        }
        if (level < 1 || sellLevel <= level) {
            Draw.text(g, "Level / Sell Level degerlerini kontrol et", px + 10, previewY + 10, Theme.YELLOW);
            return;
        }
        if (!BazaarLookup.isReady()) {
            Draw.text(g, "Bazaar fiyatlari yukleniyor...", px + 10, previewY + 10, Theme.TEXT_FAINT);
            return;
        }

        BazaarLookup.Quick buy = BazaarLookup.get(baseId + "_" + level);
        BazaarLookup.Quick sell = BazaarLookup.get(baseId + "_" + sellLevel);

        if (buy == null || sell == null) {
            Draw.text(g, "Bu ID bazaar'da yok", px + 10, previewY + 10, Theme.RED);
            Draw.text(g, Draw.clip(baseId + "_" + level, pw - 20), px + 10, previewY + 22, Theme.TEXT_FAINT);
            Draw.text(g, "'Ara' ile dogru urunu sec", px + 10, previewY + 34, Theme.TEXT_FAINT);
            return;
        }

        int qty = 1 << (sellLevel - level);
        double invest = buy.sellPrice() * qty;
        double revenue = sell.buyPrice();
        double clean = revenue * (1 - TAX) - invest;
        double percent = invest > 0 ? clean / invest * 100.0 : 0;

        // Hacim: kullanicinin istedigi tanim.
        //   "bana satilan"  = alinacak seviyenin haftalik instant-sell hacmi / 7
        //   "benden alinan" = hedef seviyenin haftalik instant-buy hacmi / 7
        long dailyIn = buy.sellMovingWeek() / 7;
        long dailyOut = sell.buyMovingWeek() / 7;

        int line = previewY + 8;
        Draw.text(g, Draw.clip(displayTitle(level, sellLevel), pw - 20), px + 10, line, Theme.TEXT);

        line += 12;
        Draw.text(g, "Gereken", px + 10, line, Theme.TEXT_FAINT);
        Draw.textRight(g, qty + " x " + StatsTab.coins(buy.sellPrice()) + "  =  " + StatsTab.coins(invest),
                px + pw - 10, line, Theme.TEXT);

        line += 11;
        Draw.text(g, "Satis (" + roman(sellLevel) + ")", px + 10, line, Theme.TEXT_FAINT);
        Draw.textRight(g, StatsTab.coins(revenue) + "  -  %1.25 vergi", px + pw - 10, line, Theme.TEXT);

        line += 11;
        Draw.text(g, "Clean Profit", px + 10, line, Theme.TEXT_FAINT);
        String cleanText = (clean >= 0 ? "+" : "-") + StatsTab.coins(Math.abs(clean))
                + "   (%" + Math.round(percent) + ")";
        Draw.textRight(g, cleanText, px + pw - 10, line, clean >= 0 ? Theme.GREEN : Theme.RED);

        line += 13;
        Draw.text(g, "Bana satilan " + dailyIn + "/gun", px + 10, line, Theme.TEXT_DIM);
        Draw.textRight(g, "Benden alinan " + dailyOut + "/gun", px + pw - 10, line, Theme.TEXT_DIM);
    }

    private String displayTitle(int level, int sellLevel) {
        String name = nameBox.value.isBlank()
                ? (ItemCatalog.displayNameOf(normalizedId()) == null ? normalizedId() : ItemCatalog.displayNameOf(normalizedId()))
                : nameBox.value;
        return name + "  " + roman(level) + " \u2192 " + roman(sellLevel);
    }

    // ------------------------------------------------------------------ arama katmanı

    private void renderSearchOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.rect(g, 0, 0, screenW, screenH, 0xBB000000);
        Draw.panel(g, overlayX, overlayY, overlayW, overlayH, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, overlayX + 1, overlayY + 1, overlayW - 2, 1, Theme.ACCENT);

        Draw.text(g, "Arama sonuclari", overlayX + 14, overlayY + 14, Theme.TEXT);
        Draw.textRight(g, ItemCatalog.status(), overlayX + overlayW - 14, overlayY + 14, Theme.TEXT_FAINT);

        int listY = overlayY + 32;
        int listH = overlayH - 32 - 14;
        Draw.panel(g, overlayX + 12, listY, overlayW - 24, listH, Theme.CARD, Theme.STROKE);

        if (searchResults.isEmpty()) {
            Draw.textCentered(g, ItemCatalog.isReady() ? "Eslesen kitap yok" : "Katalog yuklenmedi",
                    overlayX + overlayW / 2, listY + listH / 2 - 4, Theme.TEXT_FAINT);
            Draw.textCentered(g, "ESC ile kapat", overlayX + overlayW / 2, listY + listH / 2 + 8, Theme.TEXT_FAINT);
            return;
        }

        int visible = Math.max(1, (listH - 4) / overlayRowH);
        overlayScroll = Math.max(0, Math.min(overlayScroll, Math.max(0, searchResults.size() - visible)));

        for (int i = 0; i < visible && (i + overlayScroll) < searchResults.size(); i++) {
            ItemCatalog.Entry entry = searchResults.get(i + overlayScroll);
            int ry = listY + 2 + i * overlayRowH;
            boolean hover = Draw.inside(mouseX, mouseY, overlayX + 13, ry, overlayW - 26, overlayRowH - 1);
            if (hover) Draw.rect(g, overlayX + 13, ry, overlayW - 26, overlayRowH - 1, Theme.HOVER);

            Draw.text(g, Draw.clip(entry.displayName(), overlayW - 120), overlayX + 22, ry + 2, Theme.TEXT);
            Draw.text(g, Draw.clip(entry.baseId(), overlayW - 120), overlayX + 22, ry + 11, Theme.TEXT_FAINT);

            BazaarLookup.Quick quick = BazaarLookup.get(entry.baseId() + "_1");
            String price = quick == null ? "-" : "alis " + StatsTab.coins(quick.sellPrice());
            Draw.textRight(g, price, overlayX + overlayW - 22, ry + 6, Theme.ACCENT);
        }
    }

    // ------------------------------------------------------------------ onay katmanı

    private void renderConfirmOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.rect(g, 0, 0, screenW, screenH, 0xCC000000);

        int ch = 150;
        int cy = (screenH - ch) / 2;
        Draw.panel(g, overlayX, cy, overlayW, ch, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, overlayX + 1, cy + 1, overlayW - 2, 1, Theme.YELLOW);

        Draw.text(g, "Bunu mu demek istedin?", overlayX + 16, cy + 16, Theme.TEXT);
        Draw.text(g, "Ayni isimde birden fazla urun olabilir - kontrol et.",
                overlayX + 16, cy + 28, Theme.TEXT_FAINT);

        if (pendingChoice != null) {
            Draw.panel(g, overlayX + 16, cy + 44, overlayW - 32, 52, Theme.CARD, Theme.STROKE);
            Draw.text(g, Draw.clip(pendingChoice.displayName(), overlayW - 52),
                    overlayX + 26, cy + 52, Theme.TEXT);
            Draw.text(g, Draw.clip(pendingChoice.baseId(), overlayW - 52),
                    overlayX + 26, cy + 64, Theme.ACCENT);

            BazaarLookup.Quick quick = BazaarLookup.get(pendingChoice.baseId() + "_1");
            String price = quick == null
                    ? "bazaar fiyati bulunamadi"
                    : "alis " + StatsTab.coins(quick.sellPrice()) + "   satis " + StatsTab.coins(quick.buyPrice());
            Draw.text(g, price, overlayX + 26, cy + 78, Theme.TEXT_DIM);
        }

        int by = cy + ch - 28;
        int bw = (overlayW - 40) / 2;
        confirmNo.bounds(overlayX + 16, by, bw, 20);
        confirmYes.bounds(overlayX + 24 + bw, by, bw, 20);
        confirmNo.render(g, mouseX, mouseY);
        confirmYes.render(g, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ envanter katmanı

    private void renderInventoryOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.rect(g, 0, 0, screenW, screenH, 0xBB000000);
        Draw.panel(g, overlayX, overlayY, overlayW, overlayH, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, overlayX + 1, overlayY + 1, overlayW - 2, 1, Theme.GREEN);

        Draw.text(g, "Envanterindeki kitaplar", overlayX + 14, overlayY + 14, Theme.TEXT);
        Draw.textRight(g, "lore'dan birebir okundu", overlayX + overlayW - 14, overlayY + 14, Theme.TEXT_FAINT);

        int listY = overlayY + 32;
        int listH = overlayH - 32 - 14;
        Draw.panel(g, overlayX + 12, listY, overlayW - 24, listH, Theme.CARD, Theme.STROKE);

        if (inventoryBooks.isEmpty()) {
            Draw.textCentered(g, "Envanterinde buyu kitabi bulunamadi",
                    overlayX + overlayW / 2, listY + listH / 2 - 8, Theme.TEXT_FAINT);
            Draw.textCentered(g, "Kitabi envanterine al ve tekrar dene",
                    overlayX + overlayW / 2, listY + listH / 2 + 4, Theme.TEXT_FAINT);
            return;
        }

        int visible = Math.max(1, (listH - 4) / overlayRowH);
        overlayScroll = Math.max(0, Math.min(overlayScroll, Math.max(0, inventoryBooks.size() - visible)));

        for (int i = 0; i < visible && (i + overlayScroll) < inventoryBooks.size(); i++) {
            InventoryBook entry = inventoryBooks.get(i + overlayScroll);
            int ry = listY + 2 + i * overlayRowH;
            boolean hover = Draw.inside(mouseX, mouseY, overlayX + 13, ry, overlayW - 26, overlayRowH - 1);
            if (hover) Draw.rect(g, overlayX + 13, ry, overlayW - 26, overlayRowH - 1, Theme.HOVER);

            Draw.text(g, Draw.clip(entry.name() + "  " + roman(entry.level()), overlayW - 100),
                    overlayX + 22, ry + 2, Theme.TEXT);
            Draw.text(g, Draw.clip(entry.baseId(), overlayW - 100), overlayX + 22, ry + 11, Theme.TEXT_FAINT);
            Draw.textRight(g, "x" + entry.count(), overlayX + overlayW - 22, ry + 6, Theme.ACCENT);
        }
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;

        switch (overlay) {
            case SEARCH -> {
                clickList(mouseX, mouseY, searchResults.size(), index -> {
                    pendingChoice = searchResults.get(index);
                    overlay = Overlay.CONFIRM;
                });
                return true;
            }
            case INVENTORY -> {
                clickList(mouseX, mouseY, inventoryBooks.size(), index -> {
                    InventoryBook entry = inventoryBooks.get(index);
                    idBox.value = entry.baseId();
                    nameBox.value = entry.name();
                    levelBox.value = String.valueOf(entry.level());
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

    private void clickList(double mouseX, double mouseY, int size, java.util.function.IntConsumer onPick) {
        int listY = overlayY + 32;
        int listH = overlayH - 32 - 14;
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
            if (keyCode == 257 || keyCode == 335) { // ENTER
                if (overlay == Overlay.CONFIRM) applyPendingChoice();
                return true;
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
        String query = idBox.value.isBlank() ? nameBox.value : idBox.value;
        if (query.isBlank()) {
            error = "Once aramak istedigin kelimeyi ID ya da NAME kutusuna yaz.";
            return;
        }
        if (!ItemCatalog.isReady()) ItemCatalog.refresh();
        searchResults = ItemCatalog.search(query, 60);
        overlayScroll = 0;
        overlay = Overlay.SEARCH;
        error = null;
    }

    private void applyPendingChoice() {
        if (pendingChoice == null) {
            overlay = Overlay.NONE;
            return;
        }
        idBox.value = pendingChoice.baseId();
        if (nameBox.value.isBlank()) nameBox.value = pendingChoice.displayName();
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
            error = "ID bos olamaz.";
            return;
        }
        if (name.isEmpty()) {
            error = "Name bos olamaz (lore'daki gorunen ad).";
            return;
        }
        if (level < 1 || level > 9) {
            error = "Level 1-9 arasinda olmali.";
            return;
        }
        if (sellLevel <= level || sellLevel > 10) {
            error = "Sell Level, Level'dan buyuk olmali.";
            return;
        }
        if (GoofyConfig.hasBook(id, level, editIndex)) {
            error = "Bu ID + Level zaten tanimli.";
            return;
        }
        // KAYDETMEDEN DOGRULAMA: bazaar verisi yuklendiyse ID'nin gercekten var
        // oldugunu kontrol et. Yanlis ID sessizce calismayan bir hat demek.
        if (BazaarLookup.isReady() && !BazaarLookup.exists(id + "_" + level)) {
            error = "Bazaar'da bulunamadi: " + id + "_" + level;
            return;
        }

        Book book = new Book(id, level, sellLevel, name);
        if (editIndex < 0) {
            GoofyConfig.addBook(book);
        } else {
            GoofyConfig.replaceBook(editIndex, book);
        }
        onClose.run();
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
