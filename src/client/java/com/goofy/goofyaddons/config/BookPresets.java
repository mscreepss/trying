package com.goofy.goofyaddons.config;

import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hazır kitap setleri.
 *
 * NEDEN JAVA'DA DEĞİL DE JSON'DA: Bu listeyi kullanıcı kendisi düzenleyecek.
 * Kodun içine gömülü olsaydı her değişiklik için modun yeniden derlenmesi
 * gerekirdi. Dosya ilk açılışta örneklerle oluşturulur, sonrası kullanıcının.
 *
 * Dosya: config/goofyaddons_presets.json
 * [
 *   { "id": "ENCHANTMENT_ULTIMATE_WISE", "name": "Ultimate Wise", "level": 1, "sellLevel": 5 }
 * ]
 *
 * BOŞ BAŞLAR: Dosya ilk açılışta BOŞ bir liste olarak ([]) oluşturulur. Örnek
 * ya da varsayılan kitap EKLENMEZ - içine ne koyacağına kullanıcı karar verir.
 *
 * Arayüzdeki "Yenile" butonu dosyayı oyunu kapatmadan yeniden okur.
 */
public final class BookPresets {

    /** Gson'un okuduğu ham satır. Book record'unun alan sırasına bağlı kalmasın diye ayrı. */
    public static class Preset {
        public String id = "";
        public String name = "";
        public int level = 1;
        public int sellLevel = 5;

        public Preset() {
        }

        public Preset(String id, String name, int level, int sellLevel) {
            this.id = id;
            this.name = name;
            this.level = level;
            this.sellLevel = sellLevel;
        }

        public Book toBook() {
            return new Book(id, level, sellLevel, name);
        }

        public boolean isValid() {
            return id != null && !id.isBlank()
                    && name != null && !name.isBlank()
                    && level >= 1 && sellLevel > level;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("goofyaddons_presets.json");

    private static List<Preset> presets = new ArrayList<>();
    private static String status = "";

    private BookPresets() {
    }

    public static void load() {
        try {
            if (!Files.exists(PATH)) {
                // Bos dosya olustur - icine hicbir sey eklemiyoruz.
                presets = new ArrayList<>();
                save();
                status = "empty - edit presets.json to add books";
                return;
            }

            List<Preset> loaded = GSON.fromJson(Files.readString(PATH),
                    new TypeToken<List<Preset>>() {
                    }.getType());

            if (loaded == null) loaded = new ArrayList<>();
            loaded.removeIf(preset -> preset == null || !preset.isValid());
            presets = loaded;
            status = presets.isEmpty() ? "empty - edit presets.json to add books"
                    : presets.size() + " saved books";
        } catch (Exception e) {
            status = "presets.json could not be read (syntax error?)";
        }
    }

    public static List<Preset> all() {
        return new ArrayList<>(presets);
    }

    public static String status() {
        return status;
    }

    public static Path path() {
        return PATH;
    }

    private static void save() {
        try {
            Files.writeString(PATH, GSON.toJson(presets));
        } catch (Exception ignored) {
        }
    }

    public static boolean has(String id, int level) {
        for (Preset preset : presets) {
            if (preset.id.equalsIgnoreCase(id) && preset.level == level) return true;
        }
        return false;
    }

    public static synchronized void addBook(com.goofy.goofyaddons.features.bookflipper.helper.Book book) {
        presets.add(new Preset(book.id(), book.name(), book.level(), book.sellLevel()));
        save();
        status = presets.size() + " saved books";
    }

    public static synchronized void replace(int index, com.goofy.goofyaddons.features.bookflipper.helper.Book book) {
        if (index < 0 || index >= presets.size()) return;
        presets.set(index, new Preset(book.id(), book.name(), book.level(), book.sellLevel()));
        save();
    }

    /** Aktif kitaplardan hazir sete kaydeder (Presets sayfasindaki dugme). */
    public static synchronized void addFromBook(com.goofy.goofyaddons.features.bookflipper.helper.Book book) {
        for (Preset preset : presets) {
            if (preset.id.equalsIgnoreCase(book.id()) && preset.level == book.level()) return;
        }
        presets.add(new Preset(book.id(), book.name(), book.level(), book.sellLevel()));
        save();
        status = presets.size() + " saved books";
    }

    public static synchronized void remove(int index) {
        if (index < 0 || index >= presets.size()) return;
        presets.remove(index);
        save();
        status = presets.isEmpty() ? "empty - edit presets.json to add books"
                : presets.size() + " saved books";
    }
}
