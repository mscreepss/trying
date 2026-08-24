package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.features.bookflipper.helper.OnlySellMode;
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
 * GoofyAddons ana arayüzü (M tuşu açar ve kapatır).
 *
 * TASARIM NOTU - YERLEŞİM: Ekrandaki her kutunun koordinatı init() içinde bir kez
 * hesaplanıp alanlarda saklanır; hem çizim hem tıklama AYNI alanları kullanır.
 * Koordinatları iki ayrı yerde elle hesaplamak (çiz bir yerde, tıkla başka yerde)
 * bu tür arayüzlerdeki bir numaralı hata kaynağıdır.
 *
 * PENCERE GENİŞLETİLDİ: Eski hâli dar ve sıkışıktı. Artık pencere daha geniş,
 * satır yükseklikleri ve kenar boşlukları artırıldı; küçük GUI ölçeklerinde
 * taşmasın diye hâlâ ekrana göre kısılıyor.
 *
 * SAYFA YAPISI: Sol kenar çubuğunda altı sayfa var:
 *   Macro / Books / Presets / HUD / Stats / Session
 * Books'un kendi içindeki ayrım (Active Books / General) ve Stats'in içindeki
 * ayrım (History / Live Log / Per Book) sol menüye DEĞİL, içeriğin üstündeki
 * segment kontrolüne bağlı. Sol menü sade kalıyor, her alt sayfa tüm yüksekliği
 * kullanabiliyor.
 */
public class GoofyScreen extends BaseScreen {

    /** Sol kenar çubuğundaki ana sayfalar. */
    private enum Tab {
        MACRO("Macro"),
        CONFIG("Config"),
        PRESETS("Presets"),
        BETA("Beta"),
        HUD("HUD"),
        STATS("Stats"),
        SESSION("Session");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    /** Config sayfasının KENDİ İÇİNDEKİ ayrım. */
    private enum BooksTab {
        ACTIVE("Active Books"),
        GENERAL("General");

        final String title;

        BooksTab(String title) {
            this.title = title;
        }
    }

    /** Sekme seçimleri ekranlar arasında hatırlansın. */
    private static Tab activeTab = Tab.MACRO;
    private static BooksTab activeBooksTab = BooksTab.ACTIVE;

    private static final int MAX_W = 560;
    private static final int MAX_H = 396;
    private static final int HEADER_H = 40;
    private static final int SIDEBAR_W = 128;
    private static final int PAD = 20;

    // --- pencere ---
    private int winW, winH, left, top;
    private int contentX, contentY, contentW;
    private int bodyBottom;
    private int closeX, closeY;
    private final int closeSize = 18;
    private int tabX, tabY, tabW, tabH, tabGap;

    // --- macro sayfası ---
    private int cardX, cardY, cardW, cardH;
    private int keysLabelY, keysLineY, keyRowsY, keyRowH, keyBoxX, keyBoxW, keyBoxH;
    private final List<Widgets.Button> macroButtons = new ArrayList<>();
    private Widgets.Toggle onlySellToggle;
    private int onlySellRowY;
    private KeyAction capturing = null;

    // --- ortak içerik sütunu ---
    private int colX, colW;
    private int segY, segH, segW;
    private int bodyY;

    // --- books > active ---
    private int listY, listH, bookRowH;
    private int editBtnX, editBtnW, delBtnX, delBtnW, rowBtnH;
    private int switchX, switchW, switchH;
    private int bookScroll = 0;
    /** Aktif kitap listesinin sürüklenebilir kaydırma çubuğu. */
    private final Widgets.ScrollBar bookBar = new Widgets.ScrollBar();

    // --- books > general ---
    private int setRowsY, setRowH, fieldX, fieldW;
    private final List<Widgets.TextBox> settingBoxes = new ArrayList<>();
    private Widgets.TextBox speedDelayBox, minDelayBox, maxDelayBox, firstPageBox, secondPageBox;
    private Widgets.Toggle speedToggle;

    // --- diğer sayfalar ---
    private final StatsTab statsTab = new StatsTab();
    private final SessionTab sessionTab = new SessionTab();
    private final PresetsTab presetsTab = new PresetsTab();
    private final BetaTab betaTab = new BetaTab();
    private final HudTab hudTab = new HudTab();

    private BookForm modal = null;


    public GoofyScreen() {
        super(Component.literal("GoofyAddons"));
    }

    // =====================================================================
    // Yerleşim
    // =====================================================================

    @Override
    protected void init() {
        FeatureManager.INSTANCE.onGuiOpen();

        winW = Math.min(MAX_W, this.width - 24);
        winH = Math.min(MAX_H, this.height - 24);
        left = (this.width - winW) / 2;
        top = (this.height - winH) / 2;

        contentX = left + SIDEBAR_W;
        contentY = top + HEADER_H;
        contentW = winW - SIDEBAR_W;
        bodyBottom = top + winH - 14;

        closeX = left + winW - 12 - closeSize;
        closeY = top + 11;

        tabX = left + 10;
        tabY = contentY + 14;
        tabW = SIDEBAR_W - 20;
        tabH = 26;
        tabGap = 30;

        // ---------------- macro ----------------
        cardX = contentX + PAD;
        cardW = contentW - PAD * 2;
        cardY = contentY + 14;

        int controlsBottom = top + winH - 12;
        int keysMinBlock = KeyAction.values().length * 16 + 24 + 14;
        cardH = clamp(controlsBottom - cardY - keysMinBlock, 100, 118);

        keysLabelY = cardY + cardH + 18;
        keysLineY = keysLabelY + 12;
        keyRowsY = keysLabelY + 22;

        int rowsAvail = controlsBottom - keyRowsY;
        keyRowH = clamp(rowsAvail / KeyAction.values().length, 16, 26);
        keyBoxW = 92;
        keyBoxH = Math.min(20, keyRowH - 3);
        keyBoxX = cardX + cardW - keyBoxW;

        buildMacroButtons();
        buildOnlySellToggle();

        // ---------------- ortak içerik sütunu ----------------
        colX = contentX + PAD;
        colW = contentW - PAD * 2;

        segY = contentY + 14;
        segH = 22;
        segW = colW / 2;
        bodyY = segY + segH + 18;

        // ---------------- books > active ----------------
        listY = bodyY + 26;
        listH = Math.max(60, bodyBottom - listY);
        bookRowH = 26;

        switchW = 20;
        switchH = 11;
        switchX = colX + 9;

        rowBtnH = 15;
        editBtnW = 42;
        delBtnW = 40;
        editBtnX = colX + colW - 10 - delBtnW - 8 - editBtnW;
        delBtnX = colX + colW - 10 - delBtnW;

        // ---------------- books > general ----------------
        setRowsY = bodyY + 4;
        setRowH = clamp((bodyBottom - setRowsY) / 6, 20, 34);
        fieldW = 62;
        fieldX = colX + colW - fieldW;

        buildConfigWidgets();

        // ---------------- diğer sayfalar ----------------
        int pageH = bodyBottom - segY;
        statsTab.layout(colX, segY, colW, pageH);
        sessionTab.layout(colX, segY, colW, pageH);
        presetsTab.layout(colX, segY, colW, pageH);
        presetsTab.setScreenSize(this.width, this.height);
        betaTab.layout(colX, segY, colW, pageH);
        hudTab.layout(colX, segY, colW, pageH);

        if (modal != null) modal.layout(this.width, this.height);
    }

    /**
     * Start / Pause / Stop butonlari.
     *
     * ESKIDEN kartin sol ve sag kenarina yapisiyorlardi (ic bosluk yoktu) ve
     * "sigmamis" gibi duruyordu. Artik kartin icinde 14px kenar boslugu var,
     * aralarinda 10px var ve yukseklikleri kartla orantili.
     */
    private void buildMacroButtons() {
        macroButtons.clear();
        int inset = 14;
        int gap = 10;
        int usable = cardW - inset * 2;
        int bw = (usable - gap * 2) / 3;
        int bh = 24;
        int by = cardY + cardH - bh - 12;
        int bx = cardX + inset;

        macroButtons.add(new Widgets.Button("Start", GoofyKeybinds::startMacro)
                .bounds(bx, by, bw, bh).accent(Theme.GREEN).filled(true));
        macroButtons.add(new Widgets.Button("Pause", GoofyKeybinds::togglePause)
                .bounds(bx + bw + gap, by, bw, bh).accent(Theme.YELLOW));
        // Son buton artan pikseli de alsin - sagda 1-2px bosluk kalmasin.
        macroButtons.add(new Widgets.Button("Stop", GoofyKeybinds::stopMacro)
                .bounds(bx + (bw + gap) * 2, by, usable - (bw + gap) * 2, bh).accent(Theme.RED));
    }

    /**
     * Only Sell anahtari makro kartinin icinde durur: makro CALISIRKEN da
     * acilip kapanabilsin diye. Tus atamasi da var (Macro sayfasindaki liste),
     * ama atama zorunlu degil - buradan tiklamak yeterli.
     */
    private void buildOnlySellToggle() {
        onlySellRowY = cardY + 48;
        onlySellToggle = new Widgets.Toggle(OnlySellMode.isEnabled());
        onlySellToggle.bounds(cardX + cardW - 14 - onlySellToggle.w, onlySellRowY - 2);
        onlySellToggle.onChange = () -> {
            OnlySellMode.setEnabled(onlySellToggle.value);
            GoofyKeybinds.announceOnlySell(onlySellToggle.value);
        };
    }

    private void buildConfigWidgets() {
        settingBoxes.clear();
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) return;

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

        int boxH = 18;
        speedDelayBox.bounds(fieldX, rowControlY(1, boxH), fieldW, boxH);
        minDelayBox.bounds(fieldX, rowControlY(2, boxH), fieldW, boxH);
        maxDelayBox.bounds(fieldX, rowControlY(3, boxH), fieldW, boxH);
        firstPageBox.bounds(fieldX - 58, rowControlY(4, boxH), fieldW + 58, boxH);
        secondPageBox.bounds(fieldX - 58, rowControlY(5, boxH), fieldW + 58, boxH);

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
        presetsTab.tick();
        if (modal != null) modal.tick();
    }

    /**
     * Menü tuşu (M) bu ekranı kapatmadan önce buraya bakar: bir metin kutusuna
     * yazılıyorsa kapatmaz, yoksa "m" harfi hiç yazılamazdı.
     */
    public boolean isTyping() {
        if (modal != null && modal.isTyping()) return true;
        if (presetsTab.isTyping()) return true;
        return anyBoxFocused();
    }

    /**
     * Tus atama modundayken menu tusuna basilirsa: once atamayi iptal et, ekrani
     * KAPATMA. Ikinci basista normal sekilde kapanir.
     *
     * Bu bir emniyet kemeri: atama modu Screen#keyPressed'e bagli ve o yolun
     * calismadigi bir surumde kullanici atama modunda kilitli kalabilirdi.
     */
    public boolean consumeCapture() {
        if (capturing == null) return false;
        capturing = null;
        return true;
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
            case MACRO -> renderMacro(graphics, mouseX, mouseY);
            case CONFIG -> renderBooks(graphics, mouseX, mouseY);
            case PRESETS -> presetsTab.render(graphics, mouseX, mouseY);
            case BETA -> betaTab.render(graphics, mouseX, mouseY);
            case HUD -> hudTab.render(graphics, mouseX, mouseY);
            case STATS -> statsTab.render(graphics, mouseX, mouseY);
            case SESSION -> sessionTab.render(graphics, mouseX, mouseY);
        }

        // Modal'lar her seyin ustune cizilir.
        if (activeTab == Tab.PRESETS) presetsTab.renderModal(graphics, mouseX, mouseY);
        if (modal != null) modal.render(graphics, mouseX, mouseY);
    }

    private void renderHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.text(g, "GoofyAddons", left + PAD, top + 15, Theme.TEXT);
        Draw.text(g, "BazaarFlipper", left + PAD + Draw.textWidth("GoofyAddons") + 10, top + 15, Theme.TEXT_FAINT);

        boolean hover = Draw.inside(mouseX, mouseY, closeX, closeY, closeSize, closeSize);
        Draw.roundRect(g, closeX, closeY, closeSize, closeSize, hover ? Theme.RED_SOFT : Theme.INSET);
        Draw.textCentered(g, "x", closeX + closeSize / 2, closeY + 5, hover ? Theme.RED : Theme.TEXT_DIM);

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
                Draw.rect(g, tabX, ty + 6, 2, tabH - 12, Theme.ACCENT);
            } else if (hover) {
                Draw.roundRect(g, tabX, ty, tabW, tabH, Theme.HOVER);
            }
            Draw.text(g, tabs[i].title, tabX + 12, ty + (tabH - 8) / 2, selected ? Theme.TEXT : Theme.TEXT_DIM);
        }

        Draw.text(g, KeyAction.MENU.keyName() + " opens / closes",
                left + 12, top + winH - 22, Theme.TEXT_FAINT);
    }

    // ------------------------------------------------------------------ MACRO

    private void renderMacro(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        boolean paused = FeatureManager.INSTANCE.isPaused();

        Draw.panel(g, cardX, cardY, cardW, cardH, Theme.CARD, Theme.STROKE);

        String status = !running ? "STOPPED" : (paused ? "PAUSED" : "RUNNING");
        int statusColor = !running ? Theme.TEXT_FAINT : (paused ? Theme.YELLOW : Theme.GREEN);
        int statusBg = !running ? Theme.INSET : (paused ? Theme.YELLOW_SOFT : Theme.GREEN_SOFT);

        Draw.text(g, "Macro status", cardX + 14, cardY + 13, Theme.TEXT_DIM);
        Draw.badge(g, status, cardX + cardW - 14 - Draw.badgeWidth(status), cardY + 11, statusColor, statusBg);

        String detail = "Press Start to begin";
        if (running && FeatureManager.INSTANCE.getCurrentFeature() instanceof BazaarFlipper flipper) {
            detail = flipper.getFriendlyState();
            String book = flipper.getActiveBookName();
            if (book != null && !book.equals("-")) detail = detail + "   -   " + book;
        }
        Draw.text(g, Draw.clip(detail, cardW - 28), cardX + 14, cardY + 29, Theme.TEXT_FAINT);

        // --- Only Sell satiri ---
        Draw.hLine(g, cardX + 14, cardY + 43, cardW - 28, Theme.STROKE);
        boolean onlySell = OnlySellMode.isEnabled();
        onlySellToggle.value = onlySell; // tustan degistirilmis olabilir
        Draw.text(g, "Only Sell", cardX + 14, onlySellRowY, onlySell ? Theme.YELLOW : Theme.TEXT_DIM);
        Draw.text(g, OnlySellMode.statusLine(),
                cardX + 14 + Draw.textWidth("Only Sell") + 12, onlySellRowY,
                onlySell ? Theme.TEXT_FAINT : Theme.TEXT_FAINT);
        onlySellToggle.render(g, mouseX, mouseY);

        macroButtons.get(1).label = paused ? "Resume" : "Pause";
        macroButtons.get(0).enabled = !running;
        macroButtons.get(1).enabled = running;
        macroButtons.get(2).enabled = running;
        for (Widgets.Button button : macroButtons) button.render(g, mouseX, mouseY);

        Draw.text(g, "KEY BINDINGS", cardX, keysLabelY, Theme.TEXT_FAINT);
        Draw.textRight(g, "click a key box, then press a key - ESC clears it",
                cardX + cardW, keysLabelY, Theme.TEXT_FAINT);
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
        int descSpace = cardW - keyBoxW - labelW - 30;
        if (descSpace > 40) {
            Draw.text(g, Draw.clip(action.description(), descSpace), cardX + labelW + 14, textY, Theme.TEXT_FAINT);
        }

        int stroke = isCapturing ? Theme.ACCENT : (hover ? Theme.HOVER : Theme.STROKE);
        Draw.panel(g, keyBoxX, y, keyBoxW, keyBoxH, isCapturing ? Theme.SELECTED : Theme.INSET, stroke);

        String label = isCapturing ? "Press a key..." : action.keyName();
        int color = isCapturing ? Theme.ACCENT : (action.getKey() < 0 ? Theme.TEXT_FAINT : Theme.TEXT);
        Draw.textCentered(g, Draw.clip(label, keyBoxW - 10), keyBoxX + keyBoxW / 2, textY, color);
    }

    // ------------------------------------------------------------------ BOOKS

    private void renderBooks(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null || speedToggle == null) {
            Draw.textCentered(g, "Config could not be loaded", contentX + contentW / 2, contentY + 50, Theme.RED);
            return;
        }

        renderBooksSegments(g, mouseX, mouseY, config);

        if (activeBooksTab == BooksTab.ACTIVE) {
            renderActiveBooks(g, mouseX, mouseY, config);
        } else {
            renderGeneral(g, mouseX, mouseY);
        }
    }

    private void renderBooksSegments(GuiGraphicsExtractor g, int mouseX, int mouseY, GoofyConfig config) {
        Draw.panel(g, colX, segY, colW, segH, Theme.INSET, Theme.STROKE);

        BooksTab[] tabs = BooksTab.values();
        for (int i = 0; i < tabs.length; i++) {
            int sx = segX(i);
            int sw = segWidth(i);
            boolean selected = tabs[i] == activeBooksTab;
            boolean hover = Draw.inside(mouseX, mouseY, sx, segY, sw, segH);

            if (selected) {
                Draw.roundRect(g, sx + 2, segY + 2, sw - 4, segH - 4, Theme.SELECTED);
                Draw.rect(g, sx + 8, segY + segH - 3, sw - 16, 1, Theme.ACCENT);
            } else if (hover) {
                Draw.roundRect(g, sx + 2, segY + 2, sw - 4, segH - 4, Theme.HOVER);
            }

            String label = tabs[i].title;
            if (tabs[i] == BooksTab.ACTIVE) label = label + "  " + config.books.size();

            int color = selected ? Theme.TEXT : (hover ? Theme.TEXT_DIM : Theme.TEXT_FAINT);
            Draw.textCentered(g, Draw.clip(label, sw - 12), sx + sw / 2, segY + (segH - 8) / 2, color);
        }
    }

    private int segX(int index) {
        return colX + index * segW;
    }

    private int segWidth(int index) {
        return index == BooksTab.values().length - 1 ? colW - segW * index : segW;
    }

    private void renderActiveBooks(GuiGraphicsExtractor g, int mouseX, int mouseY, GoofyConfig config) {
        List<Book> books = config.books;

        int enabled = 0;
        for (Book book : books) {
            if (GoofyConfig.isBookEnabled(book)) enabled++;
        }
        Draw.text(g, enabled + " of " + books.size() + " lines enabled", colX, bodyY + 6, Theme.TEXT_FAINT);
        Draw.textRight(g, "add books from the Presets page", colX + colW, bodyY + 6, Theme.TEXT_FAINT);

        Draw.panel(g, colX, listY, colW, listH, Theme.CARD, Theme.STROKE);

        int visible = visibleBookRows();
        bookBar.bounds(colX, listY, colW, listH);
        bookBar.setContent(books.size(), visible);
        if (bookBar.isDragging()) bookScroll = bookBar.drag(mouseY);
        bookScroll = clamp(bookScroll, 0, bookBar.maxScroll());

        if (books.isEmpty()) {
            Draw.textCentered(g, "No active books", colX + colW / 2, listY + listH / 2 - 12, Theme.TEXT_DIM);
            Draw.textCentered(g, "Open the Presets page, add a book there, then press 'Activate'.",
                    colX + colW / 2, listY + listH / 2 + 2, Theme.TEXT_FAINT);
            return;
        }

        for (int i = 0; i < visible && (i + bookScroll) < books.size(); i++) {
            Book book = books.get(i + bookScroll);
            int ry = listY + 2 + i * bookRowH;
            boolean on = GoofyConfig.isBookEnabled(book);

            if (Draw.inside(mouseX, mouseY, colX + 1, ry, colW - 2, bookRowH - 1)) {
                Draw.rect(g, colX + 1, ry, colW - 2, bookRowH - 1, Theme.HOVER);
            }

            // aktif/pasif anahtari
            int sy = ry + (bookRowH - switchH) / 2;
            boolean switchHover = Draw.inside(mouseX, mouseY, switchX, sy, switchW, switchH);
            Draw.roundRect(g, switchX, sy, switchW, switchH, on ? Theme.GREEN_SOFT : Theme.INSET);
            int knobX = on ? switchX + switchW - 8 : switchX + 2;
            Draw.roundRect(g, knobX, sy + 2, 6, switchH - 4,
                    on ? Theme.GREEN : (switchHover ? Theme.TEXT_DIM : Theme.TEXT_FAINT));

            int textColor = on ? Theme.TEXT : Theme.TEXT_FAINT;
            String title = book.name() + "   " + roman(book.level()) + " \u2192 " + roman(book.sellLevel());
            int textLeft = switchX + switchW + 12;
            int textRoom = editBtnX - textLeft - 12;
            Draw.text(g, Draw.clip(title, textRoom), textLeft, ry + 5, textColor);
            Draw.text(g, Draw.clip(book.id(), textRoom), textLeft, ry + 15, Theme.TEXT_FAINT);

            miniButton(g, "Edit", editBtnX, ry + 5, editBtnW, mouseX, mouseY, Theme.ACCENT);
            miniButton(g, "Delete", delBtnX, ry + 5, delBtnW, mouseX, mouseY, Theme.RED);
        }

        bookBar.render(g, bookScroll, mouseX, mouseY);
    }

    private int visibleBookRows() {
        return Math.max(1, (listH - 4) / bookRowH);
    }

    private void renderGeneral(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        settingRow(g, mouseX, mouseY, 0, "Speed Mode", "use one fixed click delay");
        settingRow(g, mouseX, mouseY, 1, "Speed Delay", "ms");
        settingRow(g, mouseX, mouseY, 2, "Min Delay", "ms");
        settingRow(g, mouseX, mouseY, 3, "Max Delay", "ms");
        settingRow(g, mouseX, mouseY, 4, "Storage Page 1", "command");
        settingRow(g, mouseX, mouseY, 5, "Storage Page 2", "command");

        speedToggle.render(g, mouseX, mouseY);
        for (Widgets.TextBox box : settingBoxes) box.render(g, mouseX, mouseY);
    }

    private void settingRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int rowIndex, String title, String hint) {
        int y = setRowsY + rowIndex * setRowH;
        int textY = y + (setRowH - 8) / 2;

        if (Draw.inside(mouseX, mouseY, colX, y, colW, setRowH)) {
            Draw.rect(g, colX - 6, y, colW + 12, setRowH, Theme.CARD);
        }

        Draw.text(g, title, colX, textY, Theme.TEXT);
        Draw.text(g, hint, colX + Draw.textWidth(title) + 12, textY, Theme.TEXT_FAINT);

        if (rowIndex < 5) Draw.hLine(g, colX, y + setRowH - 1, colW, Theme.STROKE);
    }

    private void miniButton(GuiGraphicsExtractor g, String label, int x, int y, int w, int mouseX, int mouseY, int accent) {
        boolean hover = Draw.inside(mouseX, mouseY, x, y, w, rowBtnH);
        Draw.roundRect(g, x, y, w, rowBtnH, hover ? Theme.HOVER : Theme.INSET);
        Draw.textCentered(g, label, x + w / 2, y + 4, hover ? accent : Theme.TEXT_DIM);
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
        if (activeTab == Tab.PRESETS && presetsTab.hasModal()) {
            presetsTab.mouseClicked(mouseX, mouseY);
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
            case MACRO -> macroClicked(mouseX, mouseY);
            case CONFIG -> booksClicked(mouseX, mouseY);
            case PRESETS -> presetsTab.mouseClicked(mouseX, mouseY);
            case BETA -> betaTab.mouseClicked(mouseX, mouseY);
            case HUD -> hudTab.mouseClicked(mouseX, mouseY);
            case STATS -> statsTab.mouseClicked(mouseX, mouseY);
            case SESSION -> sessionTab.mouseClicked(mouseX, mouseY);
        }
    }

    private void macroClicked(double mouseX, double mouseY) {
        if (onlySellToggle != null && onlySellToggle.mouseClicked(mouseX, mouseY)) return;

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

    private void booksClicked(double mouseX, double mouseY) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null || speedToggle == null) return;

        BooksTab[] tabs = BooksTab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (Draw.inside(mouseX, mouseY, segX(i), segY, segWidth(i), segH)) {
                activeBooksTab = tabs[i];
                clearBoxFocus();
                return;
            }
        }

        if (activeBooksTab == BooksTab.ACTIVE) {
            activeBooksClicked(mouseX, mouseY, config);
        } else {
            generalClicked(mouseX, mouseY);
        }
    }

    private void activeBooksClicked(double mouseX, double mouseY, GoofyConfig config) {
        if (bookBar.mouseClicked(mouseX, mouseY)) return;

        int visible = visibleBookRows();
        for (int i = 0; i < visible && (i + bookScroll) < config.books.size(); i++) {
            int index = i + bookScroll;
            int ry = listY + 2 + i * bookRowH;
            Book book = config.books.get(index);

            int sy = ry + (bookRowH - switchH) / 2;
            if (Draw.inside(mouseX, mouseY, switchX - 4, sy - 4, switchW + 8, switchH + 8)) {
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
    public boolean mouseReleased(MouseButtonEvent event) {
        // TUM kaydirma cubuklarinin surukleme bitisi buradan geliyor. Hangi
        // sayfada oldugumuza bakmadan hepsi birakiliyor: sayfa degistirirken
        // fare basili kalmis olabilir, o cubuk yapisik kalmasin.
        statsTab.mouseReleased();
        presetsTab.mouseReleased();
        betaTab.mouseReleased();
        bookBar.release();
        if (modal != null) modal.mouseReleased();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (modal != null) return modal.mouseScrolled(scrollY);

        switch (activeTab) {
            case STATS -> {
                return statsTab.mouseScrolled(scrollY);
            }
            case PRESETS -> {
                return presetsTab.mouseScrolled(scrollY);
            }
            case BETA -> {
                return betaTab.mouseScrolled(scrollY);
            }
            case CONFIG -> {
                if (activeBooksTab == BooksTab.ACTIVE && GoofyConfig.INSTANCE != null) {
                    int maxScroll = Math.max(0, GoofyConfig.INSTANCE.books.size() - visibleBookRows());
                    bookScroll = clamp(bookScroll - (int) Math.signum(scrollY), 0, maxScroll);
                    return true;
                }
            }
            default -> {
            }
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
        if (activeTab == Tab.PRESETS && presetsTab.hasModal()) return presetsTab.keyPressed(keyCode);

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
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = Events.character(event);
        if (chr == 0) return super.charTyped(event);

        if (modal != null) return modal.charTyped(chr);
        if (activeTab == Tab.PRESETS && presetsTab.hasModal()) return presetsTab.charTyped(chr);
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
