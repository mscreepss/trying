package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Kitap ekleme/düzenleme formu. Ayrı bir Screen değil, GoofyScreen'in üstüne
 * çizilen bir modal - ekran yığmadan, arka planı kararan modern bir diyalog.
 *
 * Doldurulan alanlar doğrudan config'e Book kaydı olarak yazılır:
 *   { "id": "...", "level": 1, "sellLevel": 5, "name": "..." }
 */
public class BookForm {

    private final int editIndex; // -1 = yeni kayıt
    private final Runnable onClose;

    private final Widgets.TextBox idBox;
    private final Widgets.TextBox nameBox;
    private final Widgets.TextBox levelBox;
    private final Widgets.TextBox sellLevelBox;
    private final List<Widgets.TextBox> boxes = new ArrayList<>();

    private final Widgets.Button saveButton;
    private final Widgets.Button cancelButton;

    private String error = null;

    private int x, y, w, h;
    private int screenW, screenH;

    public BookForm(Book existing, int editIndex, Runnable onClose) {
        this.editIndex = editIndex;
        this.onClose = onClose;

        idBox = new Widgets.TextBox(existing == null ? "" : existing.id()).placeholder("ENCHANTMENT_ULTIMATE_WISDOM");
        idBox.maxLength = 64;
        nameBox = new Widgets.TextBox(existing == null ? "" : existing.name()).placeholder("Wisdom");
        nameBox.maxLength = 32;
        levelBox = new Widgets.TextBox(existing == null ? "1" : String.valueOf(existing.level())).numeric(true);
        levelBox.maxLength = 2;
        sellLevelBox = new Widgets.TextBox(existing == null ? "5" : String.valueOf(existing.sellLevel())).numeric(true);
        sellLevelBox.maxLength = 2;

        boxes.add(idBox);
        boxes.add(nameBox);
        boxes.add(levelBox);
        boxes.add(sellLevelBox);

        saveButton = new Widgets.Button(editIndex < 0 ? "Ekle" : "Kaydet", this::save).accent(Theme.ACCENT).filled(true);
        cancelButton = new Widgets.Button("Iptal", onClose).accent(Theme.TEXT_DIM);
    }

    public void layout(int screenWidth, int screenHeight) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;
        w = 260;
        h = 186;
        x = (screenWidth - w) / 2;
        y = (screenHeight - h) / 2;

        int fieldX = x + 16;
        int fieldW = w - 32;
        int row = y + 46;

        idBox.bounds(fieldX, row, fieldW, 18);
        row += 34;
        nameBox.bounds(fieldX, row, fieldW, 18);
        row += 34;
        int half = (fieldW - 10) / 2;
        levelBox.bounds(fieldX, row, half, 18);
        sellLevelBox.bounds(fieldX + half + 10, row, half, 18);

        int buttonY = y + h - 30;
        cancelButton.bounds(fieldX, buttonY, (fieldW - 8) / 2, 20);
        saveButton.bounds(fieldX + (fieldW - 8) / 2 + 8, buttonY, (fieldW - 8) / 2, 20);
    }

    public void tick() {
        for (Widgets.TextBox box : boxes) box.tick();
    }

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // arka planı karart (modal hissi)
        Draw.rect(g, 0, 0, screenW, screenH, 0x99000000);

        Draw.panel(g, x, y, w, h, Theme.WINDOW, Theme.STROKE);
        Draw.rect(g, x + 1, y + 1, w - 2, 1, Theme.ACCENT);

        Draw.text(g, editIndex < 0 ? "Yeni Kitap" : "Kitabi Duzenle", x + 16, y + 16, Theme.TEXT);
        Draw.text(g, "Bazaar urun kimligi ve seviyeler", x + 16, y + 28, Theme.TEXT_FAINT);

        label(g, "ID", idBox);
        label(g, "NAME", nameBox);
        label(g, "LEVEL", levelBox);
        label(g, "SELL LEVEL", sellLevelBox);

        for (Widgets.TextBox box : boxes) box.render(g, mouseX, mouseY);

        if (error != null) {
            Draw.text(g, Draw.clip(error, w - 32), x + 16, y + h - 44, Theme.RED);
        }

        cancelButton.render(g, mouseX, mouseY);
        saveButton.render(g, mouseX, mouseY);
    }

    private void label(GuiGraphicsExtractor g, String text, Widgets.TextBox box) {
        Draw.text(g, text, box.x, box.y - 11, Theme.TEXT_FAINT);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        for (Widgets.TextBox box : boxes) box.mouseClicked(mouseX, mouseY);
        if (saveButton.mouseClicked(mouseX, mouseY)) return true;
        if (cancelButton.mouseClicked(mouseX, mouseY)) return true;
        return true; // modal: arkadaki hiçbir şeye tıklama geçmesin
    }

    /** true dönerse tuş yutuldu. */
    public boolean keyPressed(int keyCode) {
        if (keyCode == 256) { // ESC
            onClose.run();
            return true;
        }
        if (keyCode == 258) { // TAB - sıradaki alana geç
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

    private void save() {
        String id = idBox.value.trim().toUpperCase().replace(' ', '_');
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

        Book book = new Book(id, level, sellLevel, name);
        if (editIndex < 0) {
            GoofyConfig.addBook(book);
        } else {
            GoofyConfig.replaceBook(editIndex, book);
        }
        onClose.run();
    }
}
