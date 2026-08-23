package com.goofy.goofyaddons.ui;

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
 * Pencere boyutu ekrana göre kısılır: küçük GUI ölçeklerinde taşma olmaz, liste
 * ve satır yükseklikleri kalan alana göre daralır.
 */
public class GoofyScreen extends BaseScreen {

    private enum Tab {
        CONTROLS("Kontroller"),
        CONFIG("Ayarlar");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    /** Sekme seçimi ekranlar arasında hatırlansın. */
    private static Tab activeTab = Tab.CONTROLS;

    private static final int MAX_W = 452;
    private static final int MAX_H = 340;
    private static final int HEADER_H = 36;
    private static final int SIDEBAR_W = 104;
    private static final int PAD = 16;

    // --- pencere ---
    private int winW, winH, left, top;
    private int contentX, contentY, contentW, contentH;
    private int closeX, closeY;
    private final int closeSize = 16;
    private int tabX, tabY, tabW, tabH, tabGap;

    // --- kontroller sekmesi ---
    private int cardX, cardY, cardW, cardH;
    private int keysLabelY, keysLineY, keyRowsY, keyRowH, keyBoxX, keyBoxW, keyBoxH;
    private final List<Widgets.Button> macroButtons = new ArrayList<>();
    private KeyAction capturing = null;

    // --- ayarlar sekmesi ---
    private int listX, listY, listW, listH, bookRowH;
    private int editBtnX, editBtnW, delBtnX, delBtnW, rowBtnH;
    private int setLabelY, setLineY, setRowsY, setRowH, fieldX, fieldW;
    private int bookScroll = 0;

    private final List<Widgets.TextBox> settingBoxes = new ArrayList<>();
    private Widgets.TextBox speedDelayBox, minDelayBox, maxDelayBox, firstPageBox, secondPageBox;
    private Widgets.Toggle speedToggle;
    private Widgets.Button addBookButton;

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

        winW = Math.min(MAX_W, this.width - 20);
        winH = Math.min(MAX_H, this.height - 20);
        left = (this.width - winW) / 2;
        top = (this.height - winH) / 2;

        contentX = left + SIDEBAR_W;
        contentY = top + HEADER_H;
        contentW = winW - SIDEBAR_W;
        contentH = winH - HEADER_H;

        closeX = left + winW - 10 - closeSize;
        closeY = top + 10;

        tabX = left + 8;
        tabY = contentY + 12;
        tabW = SIDEBAR_W - 16;
        tabH = 24;
        tabGap = 28;

        int bottom = top + winH - 10;

        // ---------------- kontroller ----------------
        cardX = contentX + PAD;
        cardW = contentW - PAD * 2;
        cardY = contentY + 12;

        // Kart yuksekligi de uyarlanabilir: tus satirlarina en az yer kalsin diye
        // once onlarin ihtiyaci ayrilir, kalani karta verilir.
        int keysMinBlock = KeyAction.values().length * 13 + 18 + 12;
        cardH = clamp(bottom - cardY - keysMinBlock, 66, 72);

        keysLabelY = cardY + cardH + 12;
        keysLineY = keysLabelY + 11;
        keyRowsY = keysLabelY + 18;

        int rowsAvail = bottom - keyRowsY;
        keyRowH = clamp(rowsAvail / KeyAction.values().length, 13, 21);
        keyBoxW = 78;
        keyBoxH = Math.min(17, keyRowH - 2);
        keyBoxX = cardX + cardW - keyBoxW;

        buildMacroButtons();

        // ---------------- ayarlar ----------------
        listX = contentX + PAD;
        listW = contentW - PAD * 2;
        listY = contentY + 34;
        bookRowH = 22;

        // Ayar satirlari ve liste yuksekligi kalan alana gore hesaplanir; kucuk GUI
        // olceklerinde (ekran 480x270'e dustugunde) alt satirlar pencereden tasmasin.
        // Ayar bloğunun yüksekliği: 12 (etiket) + 18 (çizgiden ilk satıra) +
        // 5 * setRowH (satır araları) + 16 (son satırdaki kutu) + 4 (alt boşluk).
        // listH bu bloktan ARTAN yer kadar olur, tersi değil - böylece alt satır
        // hiçbir GUI ölçeğinde pencereden taşmaz.
        int minListHeight = 44;
        setRowH = clamp((bottom - listY - minListHeight - 50) / 5, 14, 19);
        listH = clamp(bottom - listY - 50 - setRowH * 5, minListHeight, 160);

        setLabelY = listY + listH + 12;
        setLineY = setLabelY + 11;
        setRowsY = setLabelY + 18;

        fieldW = 46;
        fieldX = listX + listW - fieldW;

        rowBtnH = 13;
        editBtnW = 34;
        delBtnW = 28;
        editBtnX = listX + listW - 8 - delBtnW - 6 - editBtnW;
        delBtnX = listX + listW - 8 - delBtnW;

        buildConfigWidgets();

        if (modal != null) modal.layout(this.width, this.height);
    }

    private void buildMacroButtons() {
        macroButtons.clear();
        int bw = (cardW - 16) / 3;
        int by = cardY + cardH - 30; // kart yuksekligi degisse de alta hizali kalir
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

        speedToggle = new Widgets.Toggle(config.speedMode);
        speedToggle.bounds(fieldX + fieldW - speedToggle.w, setRowsY + 2);
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

        speedDelayBox.bounds(fieldX, setRowsY + setRowH + 1, fieldW, 16);
        minDelayBox.bounds(fieldX, setRowsY + setRowH * 2 + 1, fieldW, 16);
        maxDelayBox.bounds(fieldX, setRowsY + setRowH * 3 + 1, fieldW, 16);
        firstPageBox.bounds(fieldX - 44, setRowsY + setRowH * 4 + 1, fieldW + 44, 16);
        secondPageBox.bounds(fieldX - 44, setRowsY + setRowH * 5 + 1, fieldW + 44, 16);

        settingBoxes.add(speedDelayBox);
        settingBoxes.add(minDelayBox);
        settingBoxes.add(maxDelayBox);
        settingBoxes.add(firstPageBox);
        settingBoxes.add(secondPageBox);

        addBookButton = new Widgets.Button("+ Kitap Ekle", () -> openModal(null, -1))
                .bounds(listX + listW - 84, contentY + 12, 84, 18)
                .accent(Theme.ACCENT);
    }

    /**
     * max <= min olursa BazaarFlipper.randomizer() çalışamaz. Kod tarafında da
     * koruma var ama kullanıcıya doğrusunu göstermek için burada da düzeltiyoruz.
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
            if (box.value.isEmpty()) return; // yazarken tamamen silinebilir, kaydetmeyelim
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

        if (activeTab == Tab.CONTROLS) {
            renderControls(graphics, mouseX, mouseY);
        } else {
            renderConfig(graphics, mouseX, mouseY);
        }

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

        List<Book> books = config.books;
        Draw.text(g, "AKTIF KITAPLAR", listX, contentY + 17, Theme.TEXT_FAINT);
        Draw.text(g, books.size() + " hat", listX + Draw.textWidth("AKTIF KITAPLAR") + 8, contentY + 17, Theme.TEXT_FAINT);
        addBookButton.render(g, mouseX, mouseY);

        Draw.panel(g, listX, listY, listW, listH, Theme.CARD, Theme.STROKE);

        int visible = visibleBookRows();
        int maxScroll = Math.max(0, books.size() - visible);
        if (bookScroll > maxScroll) bookScroll = maxScroll;

        if (books.isEmpty()) {
            Draw.textCentered(g, "Henuz kitap yok - '+ Kitap Ekle' ile basla",
                    listX + listW / 2, listY + listH / 2 - 4, Theme.TEXT_FAINT);
        }

        for (int i = 0; i < visible && (i + bookScroll) < books.size(); i++) {
            Book book = books.get(i + bookScroll);
            int ry = listY + 1 + i * bookRowH;

            if (Draw.inside(mouseX, mouseY, listX + 1, ry, listW - 2, bookRowH - 1)) {
                Draw.rect(g, listX + 1, ry, listW - 2, bookRowH - 1, Theme.HOVER);
            }
            Draw.rect(g, listX + 1, ry + 4, 2, bookRowH - 9, Theme.ACCENT_SOFT);

            String title = book.name() + "   " + roman(book.level()) + " \u2192 " + roman(book.sellLevel());
            int textRoom = editBtnX - listX - 18;
            Draw.text(g, Draw.clip(title, textRoom), listX + 10, ry + 3, Theme.TEXT);
            Draw.text(g, Draw.clip(book.id(), textRoom), listX + 10, ry + 12, Theme.TEXT_FAINT);

            miniButton(g, "Edit", editBtnX, ry + 5, editBtnW, mouseX, mouseY, Theme.ACCENT);
            miniButton(g, "Sil", delBtnX, ry + 5, delBtnW, mouseX, mouseY, Theme.RED);
        }

        if (maxScroll > 0) {
            int barH = Math.max(12, listH * visible / Math.max(1, books.size()));
            int barY = listY + (listH - barH) * bookScroll / maxScroll;
            Draw.rect(g, listX + listW - 3, barY, 2, barH, Theme.STROKE);
        }

        Draw.text(g, "GENEL AYARLAR", listX, setLabelY, Theme.TEXT_FAINT);
        Draw.hLine(g, listX, setLineY, listW, Theme.STROKE);

        settingRow(g, "Speed Mode", "envanter tiklamalarinda sabit gecikme", setRowsY);
        settingRow(g, "Speed Delay", "ms", setRowsY + setRowH);
        settingRow(g, "Min Delay", "ms", setRowsY + setRowH * 2);
        settingRow(g, "Max Delay", "ms", setRowsY + setRowH * 3);
        settingRow(g, "Depo Sayfa 1", "komut", setRowsY + setRowH * 4);
        settingRow(g, "Depo Sayfa 2", "komut", setRowsY + setRowH * 5);

        speedToggle.render(g, mouseX, mouseY);
        for (Widgets.TextBox box : settingBoxes) box.render(g, mouseX, mouseY);
    }

    private int visibleBookRows() {
        return Math.max(1, (listH - 2) / bookRowH);
    }

    private void settingRow(GuiGraphicsExtractor g, String title, String hint, int y) {
        Draw.text(g, title, listX, y + 5, Theme.TEXT);
        Draw.text(g, hint, listX + Draw.textWidth(title) + 8, y + 5, Theme.TEXT_FAINT);
    }

    private void miniButton(GuiGraphicsExtractor g, String label, int x, int y, int w, int mouseX, int mouseY, int accent) {
        boolean hover = Draw.inside(mouseX, mouseY, x, y, w, rowBtnH);
        Draw.roundRect(g, x, y, w, rowBtnH, hover ? Theme.HOVER : Theme.INSET);
        Draw.textCentered(g, label, x + w / 2, y + 3, hover ? accent : Theme.TEXT_DIM);
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

        if (activeTab == Tab.CONTROLS) {
            controlsClicked(mouseX, mouseY);
        } else {
            configClicked(mouseX, mouseY);
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

        if (addBookButton.mouseClicked(mouseX, mouseY)) return;
        if (speedToggle.mouseClicked(mouseX, mouseY)) return;

        for (Widgets.TextBox box : settingBoxes) box.mouseClicked(mouseX, mouseY);

        int visible = visibleBookRows();
        for (int i = 0; i < visible && (i + bookScroll) < config.books.size(); i++) {
            int index = i + bookScroll;
            int ry = listY + 1 + i * bookRowH;

            if (Draw.inside(mouseX, mouseY, editBtnX, ry + 5, editBtnW, rowBtnH)) {
                openModal(config.books.get(index), index);
                return;
            }
            if (Draw.inside(mouseX, mouseY, delBtnX, ry + 5, delBtnW, rowBtnH)) {
                GoofyConfig.removeBook(index);
                return;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (modal == null && activeTab == Tab.CONFIG && GoofyConfig.INSTANCE != null) {
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

        // Tuş atama modu: bir sonraki tuş atanır, ESC atamayı kaldırır.
        if (capturing != null) {
            capturing.setKey(keyCode == 256 ? -1 : keyCode);
            capturing = null;
            return true;
        }

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
        for (Widgets.TextBox box : settingBoxes) {
            if (box.charTyped(chr)) return true;
        }
        return super.charTyped(event);
    }

    private boolean anyBoxFocused() {
        for (Widgets.TextBox box : settingBoxes) {
            if (box.focused) return true;
        }
        return false;
    }

    private void clearBoxFocus() {
        for (Widgets.TextBox box : settingBoxes) box.focused = false;
    }
}
