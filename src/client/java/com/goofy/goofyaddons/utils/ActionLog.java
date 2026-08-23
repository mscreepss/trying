package com.goofy.goofyaddons.utils;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Makronun canlı aksiyon günlüğü.
 *
 * TASARIM (donma olmasın diye):
 *  - Hafızada yalnızca son {@value #MEMORY_LINES} satır tutulur (halka tampon).
 *  - Diske HER SATIRDA yazılmaz; satırlar bir tampona birikir ve en fazla
 *    {@value #FLUSH_INTERVAL_MS} ms'de bir TEK seferde eklenir (append).
 *  - Asıl disk işi ayrı bir arka plan thread'inde yapılır - render/tick thread'i
 *    hiçbir zaman dosya beklemez.
 *  - Dosya {@value #DISK_MAX} satırı geçince son {@value #DISK_KEEP} satır
 *    bırakılıp gerisi tek hamlede silinir. "Her yeni satırda en eskiyi sil"
 *    yaklaşımı her satırda dosyayı baştan yazmak demek olurdu; oyun takılırdı.
 *  - Oyun açılışında son {@value #DISK_KEEP} satır geri yüklenir ve üstüne bir
 *    ayraç satırı eklenir: o satırın üstündekiler önceki oturuma aittir.
 */
public final class ActionLog {

    public enum Tag {
        BUY,
        SELL,
        OUTBID,
        STORE,
        ANVIL,
        COMBINE,
        RECOVERY,
        SESSION,
        SYSTEM,
        SEPARATOR
    }

    public record Entry(String time, Tag tag, String message) {
    }

    private static final int MEMORY_LINES = 300;
    private static final int DISK_MAX = 1000;
    private static final int DISK_KEEP = 300;
    private static final long FLUSH_INTERVAL_MS = 2000;
    private static final int FLUSH_LINE_THRESHOLD = 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Path LOG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("goofyaddons_log.txt");

    private static final Object LOCK = new Object();
    private static final Deque<Entry> memory = new ArrayDeque<>();
    private static final List<String> pending = new ArrayList<>();

    private static ExecutorService diskWorker;
    private static long lastFlushMs = 0;
    private static int diskLineCount = 0;
    private static boolean ready = false;

    private ActionLog() {
    }

    /** Oyun açılışında bir kez (GoofyAddonsClient). */
    public static void init() {
        synchronized (LOCK) {
            if (ready) return;
            ready = true;
        }
        diskWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GoofyAddons-ActionLog");
            t.setDaemon(true);
            return t;
        });

        loadFromDisk();
        add(Tag.SEPARATOR, "yeni oturum - " + LocalDateTime.now().format(STAMP)
                + "  (ustu onceki oturum)");
    }

    /** Yeni bir satır ekler. Diske yazma işi ertelenir - bu çağrı hızlıdır. */
    public static void add(Tag tag, String message) {
        if (message == null) return;
        Entry entry = new Entry(LocalDateTime.now().format(TIME), tag, message);

        synchronized (LOCK) {
            memory.addFirst(entry);
            while (memory.size() > MEMORY_LINES) memory.removeLast();
            pending.add(entry.time() + "\t" + tag.name() + "\t" + message);
        }
    }

    /** Her client tick'inde çağrılır; tamponu zamanı gelince diske aktarır. */
    public static void onTick() {
        if (!ready) return;

        List<String> batch = null;
        synchronized (LOCK) {
            if (pending.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (pending.size() < FLUSH_LINE_THRESHOLD && now - lastFlushMs < FLUSH_INTERVAL_MS) return;
            lastFlushMs = now;
            batch = new ArrayList<>(pending);
            pending.clear();
        }

        final List<String> toWrite = batch;
        diskWorker.execute(() -> writeBatch(toWrite));
    }

    /** En yeniden en eskiye doğru satırlar (arayüz bunu çizer). */
    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(memory);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            memory.clear();
            pending.clear();
        }
        if (diskWorker == null) return;
        diskWorker.execute(() -> {
            try {
                Files.deleteIfExists(LOG_PATH);
                diskLineCount = 0;
            } catch (Exception ignored) {
            }
        });
    }

    // ---------------------------------------------------------------- disk işleri

    private static void writeBatch(List<String> batch) {
        try {
            Files.write(LOG_PATH, batch, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            diskLineCount += batch.size();
            if (diskLineCount > DISK_MAX) compact();
        } catch (Exception ignored) {
            // Log yazamamak makroyu durdurmaz - en fazla geçmiş eksik kalır.
        }
    }

    /**
     * Dosya şişince tek hamlede budama: son {@value #DISK_KEEP} satır kalır.
     * Arka plan thread'inde çalışır, oyun bunu hiç hissetmez.
     */
    private static void compact() {
        try {
            List<String> all = Files.readAllLines(LOG_PATH, StandardCharsets.UTF_8);
            if (all.size() <= DISK_KEEP) {
                diskLineCount = all.size();
                return;
            }
            List<String> keep = new ArrayList<>(all.subList(all.size() - DISK_KEEP, all.size()));
            Files.write(LOG_PATH, keep, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            diskLineCount = keep.size();
        } catch (Exception ignored) {
        }
    }

    private static void loadFromDisk() {
        try {
            if (!Files.exists(LOG_PATH)) return;
            List<String> all = Files.readAllLines(LOG_PATH, StandardCharsets.UTF_8);
            diskLineCount = all.size();

            int from = Math.max(0, all.size() - DISK_KEEP);
            synchronized (LOCK) {
                for (int i = from; i < all.size(); i++) {
                    Entry parsed = parse(all.get(i));
                    if (parsed == null) continue;
                    memory.addFirst(parsed);
                    while (memory.size() > MEMORY_LINES) memory.removeLast();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Entry parse(String line) {
        String[] parts = line.split("\t", 3);
        if (parts.length < 3) return null;
        Tag tag;
        try {
            tag = Tag.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            tag = Tag.SYSTEM;
        }
        return new Entry(parts[0], tag, parts[2]);
    }
}
