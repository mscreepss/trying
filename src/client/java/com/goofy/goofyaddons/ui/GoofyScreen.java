package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.BookPresets;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.keybinds.GoofyKeybinds;
import com.goofy.goofyaddons.keybinds.KeyAction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * GoofyAddons ana arayüzü (M tuşu).
 *
 * TASARIM NOTU - YERLEŞİM: Ekrandaki her kutunun koordinatı init() içinde bir kez
 * hesaplanıp alanlarda saklanır; hem çizim hem tıklama AYNI alanları kullanır.
 * Koordinatları iki ayrı yerde elle hesaplamak (çiz bir yerde, tıkla başka yerde)
 * bu tür arayüzlerdeki bir numaralı hata kaynağıdır.
 *
 * SEKME YAPISI: Sol kenar çubuğunda dört ANA sayfa var:
 *   Kontroller / Config / Istatistik / Session
 * Config'in kendi içindeki ayrım (Aktif Kitaplar / Genel Ayarlar) sol menüye
 * DEĞİL, içeriğin en üstündeki segment kontrolüne bağlı; aynı şekilde
 * Istatistik'in kendi içinde de Gecmis / Canli Log / Kitap segmentleri var.
 * Böylece sol menü sade kalıyor ve her alt sayfa tüm yüksekliği kullanabiliyor.
 *
 * Pencere boyutu ekrana göre kısılır: küçük GUI ölçeklerinde taşma olmaz.
 */
public class GoofyScreen extends BaseScreen {

    /** Sol kenar çubuğundaki ana sayfalar. */
    private enum Tab {
        CONTROLS("Kontroller"),
        CONFIG("Config"),
        STATS("Istatistik"),
        SESSION("Session");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    /** Config sayfasının KENDİ İÇİNDEKİ ayrım (segment kontrolü). */
    private enum ConfigTab {
        BOOKS("Aktif Kitaplar"),
        GENERAL("Genel Ayarlar");

        final String title;

        ConfigTab(String title) {
            this.title = title;
        }
    }

    /** Sekme seçimleri ekranlar arasında hatırlansın. */
    private static Tab activeTab = Tab.CONTROLS;
    private static ConfigTab activeConfigTab = ConfigTab.BOOKS;

    private static final int MAX_W = 468;
    private static final int MAX_H = 348;
    private static final int HEADER_H = 36;
    private static final int SIDEBAR_W = 104;
    private static final int PAD = 16;

    // --- pencere ---
    private int winW, winH, left, top;
    private int contentX, contentY, contentW;
    private int bodyBottom;
    private int closeX, closeY;
    private final int closeSize = 16;
    private int tabX, tabY, tabW, tabH, tabGap;

    // --- kontroller sayfası ---
    private int cardX, cardY, cardW, cardH;
    private int keysLabelY, keysLineY, keyRowsY, keyRowH, keyBoxX, keyBoxW, keyBoxH;
    private final List<Widgets.Button> macroButtons = new ArrayList<>();
    private KeyAction capturing = null;

    // --- config: ortak ---
    private int colX, colW;
    private int segY, segH, segW;
    private int bodyY;

    // --- config > aktif kitaplar ---
    private int listY, listH, bookRowH;
    private int editBtnX, editBtnW, delBtnX, delBtnW, rowBtnH;
    private int switchX, switchW, switchH;
    private int bookScroll = 0;
    private Widgets.Button addBookButton;
    private Widgets.Button presetButton;

    // --- config > genel ayarlar ---
    private int setRowsY, setRowH, fieldX, fieldW;
    private final List<Widgets.TextBox> settingBoxes = new ArrayList<>();
    private Widgets.TextBox speedDelayBox, minDelayBox, maxDelayBox, firstPageBox, secondPageBox;
    private Widgets.Toggle speedToggle;

    // --- diğer sayfalar ---
    private final StatsTab statsTab = new StatsTab();
    private final SessionTab sessionTab = new SessionTab();

    // --- katmanlar ---
    private BookForm modal = null;
    private boolean presetsOpen = false;
    private int presetScroll = 0;
    private int presetX, presetY, presetW, presetH;
    private final int presetRowH = 24;
    private Widgets.Button presetReloadButton, presetCloseButton;


    public GoofyScreen() {
        super(Component.literal("GoofyAddons"));
    }

    // =====================================================================
    // Yerleşim
    // =====================================================================

    @Override
    protected void init() {
        FeatureManager.INSTANCE.onGuiOpen();

        winW = Math.min(MAX_W, this.width - 20);
        winH = Math.min(MAX_H, this.height - 20);
        left = (this.width - winW) / 2;
        top = (this.height - winH) / 2;

        contentX = left + SIDEBAR_W;
        contentY = top + HEADER_H;
        contentW = winW - SIDEBAR_W;
        bodyBottom = top + winH - 12;

        closeX = left + winW - 10 - closeSize;
        closeY = top + 10;

        tabX = left + 8;
        tabY = contentY + 12;
        tabW = SIDEBAR_W - 16;
        tabH = 24;
        tabGap = 28;

        // ---------------- kontroller ----------------
        cardX = contentX + PAD;
        cardW = contentW - PAD * 2;
        cardY = contentY + 12;

        int controlsBottom = top + winH - 10;
        int keysMinBlock = KeyAction.values().length * 13 + 18 + 12;
        cardH = clamp(controlsBottom - cardY - keysMinBlock, 66, 72);

        keysLabelY = cardY + cardH + 12;
        keysLineY = keysLabelY + 11;
        keyRowsY = keysLabelY + 18;

        int rowsAvail = controlsBottom - keyRowsY;
        keyRowH = clamp(rowsAvail / KeyAction.values().length, 13, 21);
        keyBoxW = 78;
        keyBoxH = Math.min(17, keyRowH - 2);
        keyBoxX = cardX + cardW - keyBoxW;

        buildMacroButtons();

        // ---------------- ortak içerik sütunu ----------------
        colX = contentX + PAD;
        colW = contentW - PAD * 2;

        segY = contentY + 12;
        segH = 20;
        segW = colW / 2;
        bodyY = segY + segH + 14;

        // ---------------- config > aktif kitaplar ----------------
        listY = bodyY + 22;
        listH = Math.max(44, bodyBottom - listY);
        bookRowH = 22;

        switchW = 18;
        switchH = 10;
        switchX = colX + 7;

        rowBtnH = 13;
        editBtnW = 34;
        delBtnW = 28;
        editBtnX = colX + colW - 8 - delBtnW - 6 - editBtnW;
        delBtnX = colX + colW - 8 - delBtnW;

        // ---------------- config > genel ayarlar ----------------
        setRowsY = bodyY + 2;
        setRowH = clamp((bodyBottom - setRowsY) / 6, 16, 30);
        fieldW = 54;
        fieldX = colX + colW - fieldW;

        buildConfigWidgets();

        // ---------------- diğer sayfalar ----------------
        statsTab.layout(colX, segY, colW, bodyBottom - segY);
        sessionTab.layout(colX, segY, colW, bodyBottom - segY);

        // ---------------- hazır setler katmanı ----------------
        presetW = Math.min(320, this.width - 40);
        presetH = Math.min(220, this.height - 40);
        presetX = (this.width - presetW) / 2;
        presetY = (this.height - presetH) / 2;
        presetReloadButton = new Widgets.Button("Dosyayi Yenile", () -> {
            BookPresets.load();
            presetScroll = 0;
        }).bounds(presetX + 14, presetY + presetH - 28, (presetW - 36) / 2, 20).accent(Theme.ACCENT);
        presetCloseButton = new Widgets.Button("Kapat", () -> presetsOpen = false)
                .bounds(presetX + 22 + (presetW - 36) / 2, presetY + presetH - 28, (presetW - 36) / 2, 20)
                .accent(Theme.TEXT_DIM);

        if (modal != null) modal.layout(this.width, this.height);
    }

    private void buildMacroButtons() {
        macroButtons.clear();
        int bw = (cardW - 16) / 3;
        int by = cardY + cardH - 30;
        macroButtons.add(new Widgets.Button("Start", GoofyKeybinds::startMacro)
                .bounds(cardX, by, bw, 22).accent(Theme.GREEN).filled(true));
        macroButtons.add(new Widgets.Button("Pause", GoofyKeybinds::togglePause)
                .bounds(cardX + bw + 8, by, bw, 22).accent(Theme.YELLOW));
        macroButtons.add(new Widgets.Button("Stop", GoofyKeybinds::stopMacro)
                .bounds(cardX + (bw + 8) * 2, by, bw, 22).accent(Theme.RED));
    }

    private void buildConfigWidgets() {
        settingBoxes.clear();
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) return;

        addBookButton = new Widgets.Button("+ Kitap Ekle", () -> openModal(null, -1))
                .bounds(colX + colW - 82, bodyY, 82, 18)
                .accent(Theme.ACCENT);

        presetButton = new Widgets.Button("Hazir Setler", () -> {
            BookPresets.load();
            presetScroll = 0;
            presetsOpen = true;
        }).bounds(colX + colW - 82 - 6 - 76, bodyY, 76, 18).accent(Theme.GREEN);

        speedToggle = new Widgets.Toggle(config.speedMode);
        speedToggle.bounds(fieldX + fieldW - speedToggle.w, rowControlY(0, speedToggle.h));
        speedToggle.onChange = () -> {
            config.speedMode = speedToggle.value;
            GoofyConfig.save();
        };

        speedDelayBox = numberBox(config.speedModeDelay, value -> config.speedModeDelay = Math.max(20, value));
        minDelayBox = numberBox(config.minActionDelay, value -> {
            config.minActionDelay = Math.max(20, value);
            fixDelayRange(config);
        });
        maxDelayBox = numberBox(config.maxActionDelay, value -> {
            config.maxActionDelay = Math.max(30, value);
            fixDelayRange(config);
        });

        firstPageBox = new Widgets.TextBox(config.firstPage).placeholder("ec");
        firstPageBox.onChange = () -> {
            config.firstPage = firstPageBox.value;
            GoofyConfig.save();
        };
        secondPageBox = new Widgets.TextBox(config.secondPage).placeholder("ec 2");
        secondPageBox.onChange = () -> {
            config.secondPage = secondPageBox.value;
            GoofyConfig.save();
        };

        int boxH = 16;
        speedDelayBox.bounds(fieldX, rowControlY(1, boxH), fieldW, boxH);
        minDelayBox.bounds(fieldX, rowControlY(2, boxH), fieldW, boxH);
        maxDelayBox.bounds(fieldX, rowControlY(3, boxH), fieldW, boxH);
        firstPageBox.bounds(fieldX - 46, rowControlY(4, boxH), fieldW + 46, boxH);
        secondPageBox.bounds(fieldX - 46, rowControlY(5, boxH), fieldW + 46, boxH);

        settingBoxes.add(speedDelayBox);
        settingBoxes.add(minDelayBox);
        settingBoxes.add(maxDelayBox);
        settingBoxes.add(firstPageBox);
        settingBoxes.add(secondPageBox);
    }

    private int rowControlY(int rowIndex, int controlHeight) {
        return setRowsY + rowIndex * setRowH + (setRowH - controlHeight) / 2;
    }

    /**
     * max <= min olursa gecikme hesabı çalışamaz. Kod tarafında da koruma var ama
     * kullanıcıya doğrusunu göstermek için burada da düzeltiyoruz.
     */
    private void fixDelayRange(GoofyConfig config) {
        if (config.maxActionDelay <= config.minActionDelay) {
            config.maxActionDelay = config.minActionDelay + 50;
            if (maxDelayBox != null && !maxDelayBox.focused) {
                maxDelayBox.value = String.valueOf(config.maxActionDelay);
            }
        }
    }

    private Widgets.TextBox numberBox(int value, java.util.function.IntConsumer apply) {
        Widgets.TextBox box = new Widgets.TextBox(String.valueOf(value)).numeric(true);
        box.maxLength = 5;
        box.onChange = () -> {
            if (box.value.isEmpty()) return;
            apply.accept(box.intValue(0));
            GoofyConfig.save();
        };
        return box;
    }

    private void openModal(Book book, int index) {
        modal = new BookForm(book, index, () -> modal = null);
        modal.layout(this.width, this.height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // =====================================================================
    // Yaşam döngüsü
    // =====================================================================

    @Override
    public void onClose() {
        GoofyConfig.save();
        FeatureManager.INSTANCE.onGuiClose();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        for (Widgets.TextBox box : settingBoxes) box.tick();
        sessionTab.tick();
        if (modal != null) modal.tick();
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    @Override
    protected void drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Draw.rect(graphics, 0, 0, this.width, this.height, Theme.SCRIM);

        Draw.panel(graphics, left, top, winW, winH, Theme.WINDOW, Theme.STROKE);
        Draw.rect(graphics, left + 1, top + 1, winW - 2, 1, Theme.ACCENT);

        renderHeader(graphics, mouseX, mouseY);
        renderSidebar(graphics, mouseX, mouseY);

        switch (activeTab) {
            case CONTROLS -> renderControls(graphics, mouseX, mouseY);
            case CONFIG -> renderConfig(graphics, mouseX, mouseY);
            case STATS -> statsTab.render(graphics, mouseX, mouseY);
            case SESSION -> sessionTab.render(graphics, mouseX, mouseY);
        }

        if (presetsOpen) renderPresets(graphics, mouseX, mouseY);
        if (modal != null) modal.render(graphics, mouseX, mouseY);
    }

    private void renderHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.text(g, "GoofyAddons", left + PAD, top + 13, Theme.TEXT);
        Draw.text(g, "BazaarFlipper", left + PAD + Draw.textWidth("GoofyAddons") + 8, top + 13, Theme.TEXT_FAINT);

        boolean hover = Draw.inside(mouseX, mouseY, closeX, closeY, closeSize, closeSize);
        Draw.roundRect(g, closeX, closeY, closeSize, closeSize, hover ? Theme.RED_SOFT : Theme.INSET);
        Draw.textCentered(g, "x", closeX + closeSize / 2, closeY + 4, hover ? Theme.RED : Theme.TEXT_DIM);

        Draw.hLine(g, left + 1, top + HEADER_H - 1, winW - 2, Theme.STROKE);
    }

    private void renderSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.rect(g, left + 1, top + HEADER_H, SIDEBAR_W - 1, winH - HEADER_H - 1, Theme.CARD);
        Draw.vLine(g, left + SIDEBAR_W, top + HEADER_H, winH - HEADER_H - 1, Theme.STROKE);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = tabY + i * tabGap;
            boolean selected = tabs[i] == activeTab;
            boolean hover = Draw.inside(mouseX, mouseY, tabX, ty, tabW, tabH);

            if (selected) {
                Draw.roundRect(g, tabX, ty, tabW, tabH, Theme.SELECTED);
                Draw.rect(g, tabX, ty + 5, 2, tabH - 10, Theme.ACCENT);
            } else if (hover) {
                Draw.roundRect(g, tabX, ty, tabW, tabH, Theme.HOVER);
            }
            Draw.text(g, tabs[i].title, tabX + 10, ty + (tabH - 8) / 2, selected ? Theme.TEXT : Theme.TEXT_DIM);
        }

        Draw.text(g, "M ile ac / kapa", left + 12, top + winH - 20, Theme.TEXT_FAINT);
    }

    // ------------------------------------------------------------------ CONTROLS

    private void renderControls(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        boolean paused = FeatureManager.INSTANCE.isPaused();

        Draw.panel(g, cardX, cardY, cardW, cardH, Theme.CARD, Theme.STROKE);

        String status = !running ? "DURDURULDU" : (paused ? "DURAKLATILDI" : "CALISIYOR");
        int statusColor = !running ? Theme.TEXT_FAINT : (paused ? Theme.YELLOW : Theme.GREEN);
        int statusBg = !running ? Theme.INSET : (paused ? Theme.YELLOW_SOFT : Theme.GREEN_SOFT);

        Draw.text(g, "Makro Durumu", cardX + 12, cardY + 11, Theme.TEXT_DIM);
        Draw.badge(g, status, cardX + cardW - 12 - Draw.badgeWidth(status), cardY + 9, statusColor, statusBg);

        String detail = "Baslatmak icin Start";
        if (running && FeatureManager.INSTANCE.getCurrentFeature() instanceof BazaarFlipper flipper) {
            detail = flipper.getStateName() + "   " + flipper.getActiveBookName();
        }
        Draw.text(g, Draw.clip(detail, cardW - 24), cardX + 12, cardY + 25, Theme.TEXT_FAINT);

        macroButtons.get(1).label = paused ? "Resume" : "Pause";
        macroButtons.get(0).enabled = !running;
        macroButtons.get(1).enabled = running;
        macroButtons.get(2).enabled = running;
        for (Widgets.Button button : macroButtons) button.render(g, mouseX, mouseY);

        Draw.text(g, "TUS ATAMALARI", cardX, keysLabelY, Theme.TEXT_FAINT);
        Draw.textRight(g, "atamayi kaldirmak icin ESC", cardX + cardW, keysLabelY, Theme.TEXT_FAINT);
        Draw.hLine(g, cardX, keysLineY, cardW, Theme.STROKE);

        KeyAction[] actions = KeyAction.values();
        for (int i = 0; i < actions.length; i++) {
            renderKeyRow(g, actions[i], keyRowsY + i * keyRowH, mouseX, mouseY);
        }
    }

    private void renderKeyRow(GuiGraphicsExtractor g, KeyAction action, int y, int mouseX, int mouseY) {
        boolean hover = Draw.inside(mouseX, mouseY, keyBoxX, y, keyBoxW, keyBoxH);
        boolean isCapturing = capturing == action;
        int textY = y + (keyBoxH - 8) / 2;

        Draw.text(g, action.title(), cardX, textY, Theme.TEXT);

        int labelW = Draw.textWidth(action.title());
        int descSpace = cardW - keyBoxW - labelW - 24;
        if (descSpace > 30) {
            Draw.text(g, Draw.clip(action.description(), descSpace), cardX + labelW + 10, textY, Theme.TEXT_FAINT);
        }

        int stroke = isCapturing ? Theme.ACCENT : (hover ? Theme.HOVER : Theme.STROKE);
        Draw.panel(g, keyBoxX, y, keyBoxW, keyBoxH, isCapturing ? Theme.SELECTED : Theme.INSET, stroke);

        String label = isCapturing ? "Tusa bas..." : action.keyName();
        int color = isCapturing ? Theme.ACCENT : (action.getKey() < 0 ? Theme.TEXT_FAINT : Theme.TEXT);
        Draw.textCentered(g, Draw.clip(label, keyBoxW - 8), keyBoxX + keyBoxW / 2, textY, color);
    }

    // ------------------------------------------------------------------ CONFIG

    private void renderConfig(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null || addBookButton == null) {
            Draw.textCentered(g, "Config yuklenemedi", contentX + contentW / 2, contentY + 40, Theme.RED);
            return;
        }

        renderConfigSegments(g, mouseX, mouseY, config);

        if (activeConfigTab == ConfigTab.BOOKS) {
            renderBooksTab(g, mouseX, mouseY, config);
        } else {
            renderGeneralTab(g, mouseX, mouseY);
        }
    }

    private void renderConfigSegments(GuiGraphicsExtractor g, int mouseX, int mouseY, GoofyConfig config) {
        Draw.panel(g, colX, segY, colW, segH, Theme.INSET, Theme.STROKE);

        ConfigTab[] tabs = ConfigTab.values();
        for (int i = 0; i < tabs.length; i++) {
            int sx = segX(i);
            int sw = segWidth(i);
            boolean selected = tabs[i] == activeConfigTab;
            boolean hover = Draw.inside(mouseX, mouseY, sx, segY, sw, segH);

            if (selected) {
                Draw.roundRect(g, sx + 2, segY + 2, sw - 4, segH - 4, Theme.SELECTED);
                Draw.rect(g, sx + 6, segY + segH - 3, sw - 12, 1, Theme.ACCENT);
            } else if (hover) {
                Draw.roundRect(g, sx + 2, segY + 2, sw - 4, segH - 4, Theme.HOVER);
            }

            String label = tabs[i].title;
            if (tabs[i] == ConfigTab.BOOKS) label = label + "  " + config.books.size();

            int color = selected ? Theme.TEXT : (hover ? Theme.TEXT_DIM : Theme.TEXT_FAINT);
            Draw.textCentered(g, Draw.clip(label, sw - 10), sx + sw / 2, segY + (segH - 8) / 2, color);
        }
    }

    private int segX(int index) {
        return colX + index * segW;
    }

    private int segWidth(int index) {
        return index == ConfigTab.values().length - 1 ? colW - segW * index : segW;
    }

    // -- alt sekme: AKTIF KITAPLAR --

    private void renderBooksTab(GuiGraphicsExtractor g, int mouseX, int mouseY, GoofyConfig config) {
        List<Book> books = config.books;

        int enabled = 0;
        for (Book book : books) {
            if (GoofyConfig.isBookEnabled(book)) enabled++;
        }
        Draw.text(g, enabled + " / " + books.size() + " hat aktif", colX, bodyY + 5, Theme.TEXT_FAINT);

        presetButton.render(g, mouseX, mouseY);
        addBookButton.render(g, mouseX, mouseY);

        Draw.panel(g, colX, listY, colW, listH, Theme.CARD, Theme.STROKE);

        int visible = visibleBookRows();
        int maxScroll = Math.max(0, books.size() - visible);
        if (bookScroll > maxScroll) bookScroll = maxScroll;

        if (books.isEmpty()) {
            Draw.textCentered(g, "Henuz kitap yok - '+ Kitap Ekle' ya da 'Hazir Setler'",
                    colX + colW / 2, listY + listH / 2 - 4, Theme.TEXT_FAINT);
            return;
        }

        for (int i = 0; i < visible && (i + bookScroll) < books.size(); i++) {
            Book book = books.get(i + bookScroll);
            int ry = listY + 1 + i * bookRowH;
            boolean on = GoofyConfig.isBookEnabled(book);

            if (Draw.inside(mouseX, mouseY, colX + 1, ry, colW - 2, bookRowH - 1)) {
                Draw.rect(g, colX + 1, ry, colW - 2, bookRowH - 1, Theme.HOVER);
            }

            // aktif/pasif anahtari
            int sy = ry + (bookRowH - switchH) / 2;
            boolean switchHover = Draw.inside(mouseX, mouseY, switchX, sy, switchW, switchH);
            Draw.roundRect(g, switchX, sy, switchW, switchH, on ? Theme.GREEN_SOFT : Theme.INSET);
            int knobX = on ? switchX + switchW - 7 : switchX + 2;
            Draw.roundRect(g, knobX, sy + 2, 5, switchH - 4,
                    on ? Theme.GREEN : (switchHover ? Theme.TEXT_DIM : Theme.TEXT_FAINT));

            int textColor = on ? Theme.TEXT : Theme.TEXT_FAINT;
            String title = book.name() + "   " + roman(book.level()) + " \u2192 " + roman(book.sellLevel());
            int textLeft = switchX + switchW + 8;
            int textRoom = editBtnX - textLeft - 8;
            Draw.text(g, Draw.clip(title, textRoom), textLeft, ry + 3, textColor);
            Draw.text(g, Draw.clip(book.id(), textRoom), textLeft, ry + 12, Theme.TEXT_FAINT);

            miniButton(g, "Edit", editBtnX, ry + 5, editBtnW, mouseX, mouseY, Theme.ACCENT);
            miniButton(g, "Sil", delBtnX, ry + 5, delBtnW, mouseX, mouseY, Theme.RED);
        }

        if (maxScroll > 0) {
            int barH = Math.max(12, listH * visible / Math.max(1, books.size()));
            int barY = listY + (listH - barH) * bookScroll / maxScroll;
            Draw.rect(g, colX + colW - 3, barY, 2, barH, Theme.STROKE);
        }
    }

    private int visibleBookRows() {
        return Math.max(1, (listH - 2) / bookRowH);
    }

    // -- alt sekme: GENEL AYARLAR --

    private void renderGeneralTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        settingRow(g, mouseX, mouseY, 0, "Speed Mode", "sabit gecikme kullan");
        settingRow(g, mouseX, mouseY, 1, "Speed Delay", "ms");
        settingRow(g, mouseX, mouseY, 2, "Min Delay", "ms");
        settingRow(g, mouseX, mouseY, 3, "Max Delay", "ms");
        settingRow(g, mouseX, mouseY, 4, "Depo Sayfa 1", "komut");
        settingRow(g, mouseX, mouseY, 5, "Depo Sayfa 2", "komut");

        speedToggle.render(g, mouseX, mouseY);
        for (Widgets.TextBox box : settingBoxes) box.render(g, mouseX, mouseY);
    }

    private void settingRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int rowIndex, String title, String hint) {
        int y = setRowsY + rowIndex * setRowH;
        int textY = y + (setRowH - 8) / 2;

        if (Draw.inside(mouseX, mouseY, colX, y, colW, setRowH)) {
            Draw.rect(g, colX - 4, y, colW + 8, setRowH, Theme.CARD);
        }

        Draw.text(g, title, colX, textY, Theme.TEXT);
        Draw.text(g, hint, colX + Draw.textWidth(title) + 8, textY, Theme.TEXT_FAINT);

        if (rowIndex < 5) Draw.hLine(g, colX, y + setRowH - 1, colW, Theme.STROKE);
    }

    private void miniButton(GuiGraphicsExtractor g, String label, int x, int y, int w, int mouseX, int mouseY, int accent) {
        boolean hover = Draw.inside(mouseX, mouseY, x, y, w, rowBtnH);
        Draw.roundRect(g, x, y, w, rowBtnH, hover ? Theme.HOVER : Theme.INSET);
        Draw.textCentered(g, label, x + w / 2, y + 3, hover ? accent : Theme.TEXT_DIM);
    }

    // ------------------------------------------------------------------ HAZIR SETLER

    private void renderPresets(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.rect(g, 0, 0, this.width, this.height, 0xBB000000);
        Draw.panel(g, presetX, presetY, presetW, presetH, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, presetX + 1, presetY + 1, presetW - 2, 1, Theme.GREEN);

        Draw.text(g, "Hazir Setler", presetX + 14, presetY + 14, Theme.TEXT);
        Draw.textRight(g, BookPresets.status(), presetX + presetW - 14, presetY + 14, Theme.TEXT_FAINT);
        Draw.text(g, "config/goofyaddons_presets.json - istedigin gibi duzenleyebilirsin",
                presetX + 14, presetY + 26, Theme.TEXT_FAINT);

        int listY = presetY + 40;
        int listH = presetH - 40 - 34;
        Draw.panel(g, presetX + 12, listY, presetW - 24, listH, Theme.CARD, Theme.STROKE);

        List<BookPresets.Preset> presets = BookPresets.all();
        if (presets.isEmpty()) {
            Draw.textCentered(g, "presets.json bos ya da okunamadi",
                    presetX + presetW / 2, listY + listH / 2 - 4, Theme.TEXT_FAINT);
        } else {
            int visible = Math.max(1, (listH - 4) / presetRowH);
            presetScroll = clamp(presetScroll, 0, Math.max(0, presets.size() - visible));

            for (int i = 0; i < visible && (i + presetScroll) < presets.size(); i++) {
                BookPresets.Preset preset = presets.get(i + presetScroll);
                int ry = listY + 2 + i * presetRowH;
                boolean already = GoofyConfig.hasBook(preset.id, preset.level, -1);
                boolean hover = Draw.inside(mouseX, mouseY, presetX + 13, ry, presetW - 26, presetRowH - 1);
                if (hover && !already) Draw.rect(g, presetX + 13, ry, presetW - 26, presetRowH - 1, Theme.HOVER);

                String title = preset.name + "   " + roman(preset.level) + " \u2192 " + roman(preset.sellLevel);
                Draw.text(g, Draw.clip(title, presetW - 120), presetX + 22, ry + 3, already ? Theme.TEXT_FAINT : Theme.TEXT);
                Draw.text(g, Draw.clip(preset.id, presetW - 120), presetX + 22, ry + 12, Theme.TEXT_FAINT);
                Draw.textRight(g, already ? "ekli" : "+ ekle", presetX + presetW - 22, ry + 7,
                        already ? Theme.TEXT_FAINT : Theme.GREEN);
            }
        }

        presetReloadButton.render(g, mouseX, mouseY);
        presetCloseButton.render(g, mouseX, mouseY);
    }

    private void presetsClicked(double mouseX, double mouseY) {
        if (presetReloadButton.mouseClicked(mouseX, mouseY)) return;
        if (presetCloseButton.mouseClicked(mouseX, mouseY)) return;

        List<BookPresets.Preset> presets = BookPresets.all();
        int listY = presetY + 40;
        int listH = presetH - 40 - 34;
        int visible = Math.max(1, (listH - 4) / presetRowH);

        for (int i = 0; i < visible && (i + presetScroll) < presets.size(); i++) {
            int ry = listY + 2 + i * presetRowH;
            if (!Draw.inside(mouseX, mouseY, presetX + 13, ry, presetW - 26, presetRowH - 1)) continue;

            BookPresets.Preset preset = presets.get(i + presetScroll);
            if (GoofyConfig.hasBook(preset.id, preset.level, -1)) return;
            GoofyConfig.addBook(preset.toBook());
            return;
        }
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

    // =====================================================================
    // Girdi
    // =====================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        handleClick(lastMouseX, lastMouseY);
        return true;
    }

    private void handleClick(double mouseX, double mouseY) {
        if (modal != null) {
            modal.mouseClicked(mouseX, mouseY, 0);
            return;
        }
        if (presetsOpen) {
            presetsClicked(mouseX, mouseY);
            return;
        }

        if (Draw.inside(mouseX, mouseY, closeX, closeY, closeSize, closeSize)) {
            onClose();
            return;
        }

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (Draw.inside(mouseX, mouseY, tabX, tabY + i * tabGap, tabW, tabH)) {
                activeTab = tabs[i];
                capturing = null;
                clearBoxFocus();
                return;
            }
        }

        switch (activeTab) {
            case CONTROLS -> controlsClicked(mouseX, mouseY);
            case CONFIG -> configClicked(mouseX, mouseY);
            case STATS -> statsTab.mouseClicked(mouseX, mouseY);
            case SESSION -> sessionTab.mouseClicked(mouseX, mouseY);
        }
    }

    private void controlsClicked(double mouseX, double mouseY) {
        for (Widgets.Button b : macroButtons) {
            if (b.mouseClicked(mouseX, mouseY)) return;
        }

        KeyAction[] actions = KeyAction.values();
        for (int i = 0; i < actions.length; i++) {
            if (Draw.inside(mouseX, mouseY, keyBoxX, keyRowsY + i * keyRowH, keyBoxW, keyBoxH)) {
                capturing = (capturing == actions[i]) ? null : actions[i];
                return;
            }
        }
        capturing = null;
    }

    private void configClicked(double mouseX, double mouseY) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null || addBookButton == null) return;

        ConfigTab[] tabs = ConfigTab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (Draw.inside(mouseX, mouseY, segX(i), segY, segWidth(i), segH)) {
                activeConfigTab = tabs[i];
                clearBoxFocus();
                return;
            }
        }

        if (activeConfigTab == ConfigTab.BOOKS) {
            booksClicked(mouseX, mouseY, config);
        } else {
            generalClicked(mouseX, mouseY);
        }
    }

    private void booksClicked(double mouseX, double mouseY, GoofyConfig config) {
        if (presetButton.mouseClicked(mouseX, mouseY)) return;
        if (addBookButton.mouseClicked(mouseX, mouseY)) return;

        int visible = visibleBookRows();
        for (int i = 0; i < visible && (i + bookScroll) < config.books.size(); i++) {
            int index = i + bookScroll;
            int ry = listY + 1 + i * bookRowH;
            Book book = config.books.get(index);

            int sy = ry + (bookRowH - switchH) / 2;
            if (Draw.inside(mouseX, mouseY, switchX - 3, sy - 3, switchW + 6, switchH + 6)) {
                GoofyConfig.setBookEnabled(book, !GoofyConfig.isBookEnabled(book));
                return;
            }
            if (Draw.inside(mouseX, mouseY, editBtnX, ry + 5, editBtnW, rowBtnH)) {
                openModal(book, index);
                return;
            }
            if (Draw.inside(mouseX, mouseY, delBtnX, ry + 5, delBtnW, rowBtnH)) {
                GoofyConfig.removeBook(index);
                return;
            }
        }
    }

    private void generalClicked(double mouseX, double mouseY) {
        if (speedToggle.mouseClicked(mouseX, mouseY)) return;
        for (Widgets.TextBox box : settingBoxes) box.mouseClicked(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (modal != null) return modal.mouseScrolled(scrollY);

        if (presetsOpen) {
            presetScroll = Math.max(0, presetScroll - (int) Math.signum(scrollY));
            return true;
        }

        if (activeTab == Tab.STATS) return statsTab.mouseScrolled(scrollY);

        if (activeTab == Tab.CONFIG && activeConfigTab == ConfigTab.BOOKS && GoofyConfig.INSTANCE != null) {
            int maxScroll = Math.max(0, GoofyConfig.INSTANCE.books.size() - visibleBookRows());
            bookScroll = clamp(bookScroll - (int) Math.signum(scrollY), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = Events.keyCode(event);
        if (handleKey(keyCode)) return true;
        return super.keyPressed(event);
    }

    private boolean handleKey(int keyCode) {
        if (modal != null) return modal.keyPressed(keyCode);

        if (presetsOpen) {
            if (keyCode == 256) presetsOpen = false;
            return true;
        }

        // Tuş atama modu: bir sonraki tuş atanır, ESC atamayı kaldırır.
        if (capturing != null) {
            capturing.setKey(keyCode == 256 ? -1 : keyCode);
            capturing = null;
            return true;
        }

        if (activeTab == Tab.SESSION && sessionTab.keyPressed(keyCode)) return true;

        for (Widgets.TextBox box : settingBoxes) {
            if (box.keyPressed(keyCode)) return true;
        }

        // Menüyü açan tuşla kapatmak en doğal davranış (bir metin kutusuna
        // yazmıyorsak - yoksa "m" harfi yazamazdık).
        if (keyCode == KeyAction.MENU.getKey() && !anyBoxFocused()) {
            onClose();
            return true;
        }

        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = Events.character(event);
        if (chr == 0) return super.charTyped(event);

        if (modal != null) return modal.charTyped(chr);
        if (presetsOpen) return true;
        if (activeTab == Tab.SESSION && sessionTab.charTyped(chr)) return true;
        for (Widgets.TextBox box : settingBoxes) {
            if (box.charTyped(chr)) return true;
        }
        return super.charTyped(event);
    }

    private boolean anyBoxFocused() {
        for (Widgets.TextBox box : settingBoxes) {
            if (box.focused) return true;
        }
        return sessionTab.anyFocused();
    }

    private void clearBoxFocus() {
        for (Widgets.TextBox box : settingBoxes) box.focused = false;
        sessionTab.clearFocus();
    }
}
