package com.goofy.goofyaddons.features.bookflipper;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.features.Feature;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.helper.BazaarMonitor;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.features.bookflipper.helper.FlipCalculator;
import com.goofy.goofyaddons.features.bookflipper.helper.FlipItem;
import com.goofy.goofyaddons.features.bookflipper.helper.OnlySellMode;
import com.goofy.goofyaddons.features.bookflipper.helper.TradeHistory;
import com.goofy.goofyaddons.ui.GoofyGui;
import com.goofy.goofyaddons.utils.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;

import java.lang.reflect.Field;
import java.util.*;


/**
 * BazaarFlipper - tick tabanlı bazaar kitap flip makrosu.
 *
 * ÖKSÜZ PARÇA HAKKINDA (eski "temizlik modu" TAMAMEN kaldırıldı):
 * pendingCleanupNames / activeCleanupTask / calculateTopUpAmount / findBaseLevelEntry /
 * pruneStaleCleanupFlags mekanizmasının tamamı silindi. Onun yerine öksüz parçayı
 * ÜRETEN sebepler kapatıldı:
 *
 *  1) SAYIM DİSİPLİNİ (mal sahipliği): Kitaplar fiziksel olarak birbirinden ayırt
 *     edilemediği için sahiplik ADETLE tutulur. STORE artık envanterdeki TÜM
 *     eşleşen kitapları değil, yalnızca o görevin kendi sayısı (inInventory) kadar
 *     kitabı depolar; ANVIL de depodan yalnızca kendi sayısı (inEnderChest) kadar
 *     kitap çeker. Eskiden 2to5 görevi STORE'a düştüğünde, 1to5 zincirinin taze
 *     ürettiği Wisdom II'leri de kendi stoğu sanıp ender chest'e gömüyordu; zincir
 *     yarıda kalıyor ve o kitaplar öksüz kalıyordu.
 *     ÖNEMLİ: İki zincir birbirini BEKLEMEZ. 16xI tek başına 1 adet V, 8xII tek
 *     başına 1 adet V eder - her iki havuz da tek başına tam 2'nin kuvvetidir,
 *     dolayısıyla 1to5, 2to5'in siparişinin dolmasını beklemek zorunda değildir.
 *  2) ZİNCİR SIRASINDA YENİ SİPARİŞ YOK: Zincirdeki bir isim için processData
 *     yeni görev açmaz; yoksa gelen taban seviye kitaplar havuzu tek sayıya
 *     düşürür ve her katta bir öksüz doğurur.
 *  3) DOĞRU SİPARİŞ MİKTARI: Elde zaten duran ara seviye kitaplar (config'te kendi
 *     girdisi olmayan seviyeler, ör. sellLevel=5 ve config {1,2} iken III/IV)
 *     "birim" olarak sayılıp sipariş miktarından düşülür (III = 4 birim, IV = 8).
 *     Böylece havuz toplamı her zaman tam 2'nin kuvveti olur ve birleştirme
 *     sonunda artık kalmaz. Bu bir mod değil, sadece sipariş miktarı aritmetiği.
 *  4) SAYAÇ KAYMASI: COMBINE sayaçları her state değişiminde ve pause'da sıfırlanır
 *     (çekiçte unutulan kitap = havuzda eksik = öksüz), "Claimed" mesajı sadece
 *     OUTBID penceresinde dinlenir, eski sipariş kayıtlarından gelen outbid
 *     uyarıları zinciri kesemez, removeDuplicateBooks sadece SELL'deki görevleri
 *     siler (eskiden alım fazındaki kardeş görevi de siliyordu).
 */
public class BazaarFlipper implements Feature {
    private enum State {
        START,
        STARTUP_CHECK,
        IDLE,
        FETCHING,
        BAZAAR_NAVIGATION,
        OUTBID,
        STORE,
        ANVIL,
        COMBINE,
        SELL,
        REPLACE_SELL,
        /**
         * Acilista bazaar'daki ACIK SATIS EMIRLERINI okuyup izlemeye alir.
         * BazaarMonitor yalnizca makronun kendi actigi emirleri biliyordu;
         * oyun kapanip acilinca o kayit gidiyor ve outbid hic yakalanmiyordu.
         */
        SELL_SCAN,
        /** Takilma tespit edildiginde girilen toparlanma durumu. */
        RECOVERY
    }

    private enum BookState {
        SELECTED,
        BUY_ORDER,
        OUTBID,
        STORE,
        ANVIL,
        COMBINE,
        SELL
    }

    /** Alım fazı: bu fazdayken o isim için birleştirme zinciri BAŞLAMAZ. */
    private static final EnumSet<BookState> BUY_PHASE = EnumSet.of(
            BookState.SELECTED, BookState.BUY_ORDER, BookState.OUTBID, BookState.STORE);

    /** Birleştirme/satış zinciri: bu fazdayken o isim için YENİ sipariş açılmaz. */
    private static final EnumSet<BookState> CHAIN_PHASE = EnumSet.of(
            BookState.ANVIL, BookState.COMBINE, BookState.SELL);

    public boolean enabled = false;


    private Clock clock = new Clock();
    private State state = State.IDLE;
    private State lastState = null;
    private List<FlipItem> flipItemsList = new ArrayList<>();
    private FlipCalculator flipCalculator = new FlipCalculator();
    private ScoreboardUtils scoreboardUtils = new ScoreboardUtils();
    private InventoryScanner inventoryScanner = new InventoryScanner();
    private Minecraft minecraft = Minecraft.getInstance();
    private BazaarMonitor bazaarMonitor = new BazaarMonitor();
    private int counter = 0;
    private boolean clickedOnce = false;
    private Book activeBook = null;
    private SplittableRandom splittableRandom = new SplittableRandom();
    private List<String> sellOrderName = new ArrayList<>();
    private boolean notEnoughCash = false;
    private boolean isInventoryFull = false;
    /**
     * Yer yetmedigi icin claim ertelendiginde, bu zamana kadar OUTBID'e
     * girilmez.
     *
     * isInventoryFull tek basina yetmiyor: IDLE'in ANVIL dali her turda onu
     * false'a cekiyor (bkz. asagidaki booksToAnvil blogu), yani bayrak bir
     * sonraki tura kadar bile yasamiyor. Bu yuzden zaman tabanli ayri bir
     * frenimiz var - yoksa makro saniyede birkac kez /bz komutu gonderir.
     */
    private long outbidSpaceRetryMs = 0;
    /**
     * Envanter dolu ve birlestirilecek hicbir cift yokken ANVIL'e tekrar
     * girilmeyecek zaman. Yer acmak kullanicinin isi; o zamana kadar
     * saniyede birkac /ec komutu gondermenin anlami yok.
     */
    private long anvilFullRetryMs = 0;
    private boolean didRemoveOrder = false;
    private boolean claimedItems = false;
    private boolean didReceiveItems = false;
    private boolean firstStartUp = false;
    private int counterBazaar = 0;
    private boolean useSecondPage = false;
    private boolean secondPageCheck = false;
    /** Depo dolu diye sayfa BİR KEZ çevrildi mi? (iki sayfa da doluysa sonsuz döngüyü keser) */
    private boolean storePageFlipped = false;
    /** Depo ekranı kaç tick'tir açık? (içerik paketi gelsin diye taramayı geciktirir) */
    private int storageOpenTicks = 0;
    private final Clock combineConfirmClock = new Clock();
    private boolean combineConfirmPending = false;

    // --- takilma bekcisi (state timeout + recovery) ---
    /** Bu state'e ne zaman girildi. Her tiklamada da tazelenir - is yapiliyorsa saat sifirlanir. */
    private long stateEnteredMs = 0;
    /** Herhangi bir gorevin durumu en son ne zaman degisti (arayuz/teshis icin). */
    private long lastProgressMs = 0;
    private State recoverFromState = null;
    private int recoveryCount = 0;
    /** Ust ust bu kadar toparlanma denemesinden sonra makro guvenlik icin durur. */
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    /**
     * IDLE'da HIC gorev yokken bu kadar beklenirse fiyatlar yeniden cekilir.
     *
     * DIKKAT: Bu sayac SADECE task haritasi bosken isler. Acik buy order'larin
     * dolmasini beklemek saatler surebilir ve bu TAMAMEN NORMALDIR - o durumda
     * gorevler var demektir, bekci hic devreye girmez. Yoksa gece boyu bekleyen
     * saglikli bir makroyu "takildi" sanip durdururduk.
     */
    private static final long IDLE_EMPTY_TIMEOUT_MS = 10 * 60 * 1000L;

    // --- canli log icin sayaclar ---
    private int lastOrderAmount = 0;
    /** Satisa cikarilirken okunan birim fiyat - izlemeye bu fiyatla kaydediliyor. */
    private double pendingSellPrice = 0;
    /** Satis emri outbid yendi mi? IDLE bunu gorunce REPLACE_SELL'e gecer. */

    /** Only Sell: bu baslatmada eldeki stok icin gorevler kuruldu mu? */
    private boolean onlySellSeeded = false;
    /** Only Sell: acik satis emirleri bu baslatmada okundu mu? */
    private boolean sellScanDone = false;
    /** SELL_SCAN kac kez denendi? 3'ten sonra pes edilir, sonsuz dongu olmasin. */
    private int sellScanAttempts = 0;
    /** SELL_SCAN icinde slot 50'ye kac kez tiklandi? */
    private int sellScanClicks = 0;

    /**
     * Outbid yenmis SATIS emirlerinin adlari, geldikleri sirayla.
     *
     * NEDEN LISTE: tek bir boolean bayrakti. Ayni anda iki emir outbid yerse
     * ikincisi sessizce kayboluyordu. Ayrica REPLACE_SELL hangi emri
     * duzeltecegini bilemedigi icin listedeki ILK emri iptal ediyordu - saglikli
     * emir bosuna churn ediliyor, outbid yenen hic duzelmiyordu.
     *
     * THREAD: BazaarMonitor'un HTTP thread'i yaziyor, tick thread'i okuyor.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingSellOutbids =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** REPLACE_SELL'in su an duzeltmesi gereken satis emrinin adi. */
    private String outbidSellName = null;
    private int outbidClaimedAmount = 0;
    private int storedThisVisit = 0;
    private boolean sellOrderCancelled = false;


    private final Map<Book, Task> task = new LinkedHashMap<>();

    private void debug(String msg) {
        ChatUtils.debugMessage("[" + state + "] " + msg);
    }

    private void dumpTasks() {
        debug("----- TASK DUMP -----");
        for (Map.Entry<Book, Task> e : task.entrySet()) {
            Task t = e.getValue();
            debug(e.getKey().getRomanLevel(e.getKey().level())
                    + " state=" + t.getBookState()
                    + " remaining=" + t.getAmountToOrder()
                    + " inv=" + t.inInventory
                    + " ec=" + t.inEnderChest
                    + " credit=" + t.unitCredit
                    + " early=" + t.earlyAction);
        }
        debug("---------------------");
    }


    @Override
    public void start() {
        ChatUtils.clientMessage("BazaarFlipper started");
        if (minecraft.screen != null) {
            minecraft.player.closeContainer();
            debug("Container is open, closing");
        }
        firstStartUp = true;
        onlySellSeeded = false;
        sellScanDone = false;
        sellScanAttempts = 0;
        sellScanClicks = 0;
        pendingSellOutbids.clear();
        outbidSellName = null;
        enabled = true;
        state = State.START;
        stateEnteredMs = System.currentTimeMillis();
        lastProgressMs = stateEnteredMs;
        recoverFromState = null;
        recoveryCount = 0;
        Humanizer.reset();
        ActionLog.add(ActionLog.Tag.SYSTEM, "macro started");
    }

    public BazaarFlipper() {
        ChatHook.onMessage("filled", this::handleFilledMessage);
        ChatHook.onMessage("Claimed", this::handleClaimedMessage);
        bazaarMonitor.hook(this::handleOutbid);
        bazaarMonitor.hookSell(this::handleSellOutbid);
    }

    @Override
    public String name() {
        return "BazaarFlipper";
    }

    @Override
    public void stop() {

        ChatUtils.clientMessage("BazaarFlipper stopped");

        task.clear();
        enabled = false;
        state = State.IDLE;
        lastState = null;
        flipItemsList.clear();
        activeBook = null;
        resetCombineCounters();
        clock.stop();
        bazaarMonitor.stop();
        bazaarMonitor.reset();
        isInventoryFull = false;
        outbidSpaceRetryMs = 0;
        anvilFullRetryMs = 0;
        didRemoveOrder = false;
        claimedItems = false;
        didReceiveItems = false;
        useSecondPage = false;
        secondPageCheck = false;
        stateEnteredMs = 0;
        lastProgressMs = 0;
        recoverFromState = null;
        recoveryCount = 0;
        TradeHistory.clearActive();
        onlySellSeeded = false;
        sellScanDone = false;
        sellScanAttempts = 0;
        sellScanClicks = 0;
        pendingSellOutbids.clear();
        outbidSellName = null;
        pendingSellPrice = 0;
        OnlySellMode.setPhase(OnlySellMode.Phase.OFF);
        Humanizer.reset();
        ActionLog.add(ActionLog.Tag.SYSTEM, "macro stopped");

    }

    @Override
    public void pause() {
        enabled = false;
        // Duraklatma (ör. ScheduledReboot) sırasında çekiçte yarım kalmış bir
        // birleştirme olabilir; sayaçlar devam eden tura sızarsa bot 1 kitapla
        // "2 kitap kondu" sanıp çıktı slotuna basıyor ve o kitap havuzdan düşüyor.
        resetCombineCounters();
    }

    @Override
    public void resume() {
        enabled = true;
    }

    @Override
    public void onTick() {

        if (!enabled) return;
        // Relog / sunucu yeniden başlatması sırasında player null olabiliyor;
        // korumasız her çağrı tick döngüsünde exception (ve crash) demek.
        if (minecraft.player == null || minecraft.level == null) return;

        bazaarMonitor.onTick();
        updateOnlySellPhase();
        lastStateCheck();
        watchdog();
        // watchdog makroyu tamamen durdurmus olabilir; durdurulmus makro tiklamaya devam etmesin.
        if (!enabled) return;

        // MIKRO MOLA: birkac saniyeligine hicbir sey yapilmaz. State, gorevler ve
        // sayaclar oldugu gibi kalir; sadece bu tick'lerde tiklama yok. En uzun mola
        // 8 sn, en kisa state zaman asimi 25 sn - bekci yanlislikla tetiklenmez.
        if (Humanizer.isResting()) return;

        // FIRSATÇI DEPO TARAMASI: depo hangi sebeple açılırsa açılsın (STORE, ANVIL,
        // STARTUP_CHECK) o açılışta BİR KEZ taranır ve hiçbir görevin sayacında
        // olmayan kitaplar sahibi olabilecek göreve yazılır. Böylece sipariş öncesi
        // ayrı bir "depoyu kontrol et" turu atmaya gerek kalmaz - hız kaybı olmaz.
        // 3 tick beklenir: ekran başlığı geldikten sonra içerik paketi 1-2 tick
        // gecikebiliyor, hemen tarasak boş konteyner görürdük.
        if (isStorageOpen()) {
            storageOpenTicks++;
            if (storageOpenTicks == 3) syncOpenStoragePage();
        } else {
            storageOpenTicks = 0;
        }

        switch (state) {
            case START -> {
                debug("[START] Refreshing flipCalculator");
                flipCalculator.Refresh();
                ChatUtils.clientMessage("BazaarFlipper: [START] Switching to FETCHING");
                state = State.FETCHING;
                bazaarMonitor.start();
            }

            case STARTUP_CHECK -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    ChatUtils.clientMessage("BazaarFlipper: [STARTUP_CHECK] Started checks");
                    if (secondPageCheck) {
                        openEnderChest(true);
                        return;
                    }
                    openEnderChest(false);
                }

                if (isStorageOpen()) clock.start(randomizer());
                if ((isStorageOpen()) && clock.shouldFire()) {
                    List<Book> bookList = new ArrayList<>();
                    bookList.addAll(booksInState(BookState.SELECTED));

                    // Depoda (bu sayfada) duran, hiçbir görevin stoğu olmayan ara seviye
                    // artıkları birim olarak sipariş miktarından düş. Envanterdeki
                    // artıklar processData'da zaten düşüldüğü için burada SADECE
                    // container taranır - çift sayım olmasın.
                    creditLeftoverUnitsFromContainer(bookList);

                    for (Book book : bookList) {
                        debug("BazaarFlipper: [STARTUP_CHECK] book: " + book.name());
                        List<Integer> size = inventoryScanner.findLoreContainer(book.getRomanLevel(book.level()));
                        debug("BazaarFlipper: [STARTUP_CHECK] Found book: " + book.name() + " Amount: " + size.size() + "In Container");
                        task.get(book).addInEnderChest(size.size());
                        if (!secondPageCheck) {
                            size = inventoryScanner.findLoreInv(book.getRomanLevel(book.level()));
                            debug("BazaarFlipper: [STARTUP_CHECK] Found book: " + book.name() + " Amount: " + size.size() + "In Inventory");
                            task.get(book).addInInventory(size.size());
                        } else {
                            task.get(book).setShouldCheckSecondPage(true);
                        }


                        // ONLY SELL: siparis miktari 0 oldugu icin her gorev daha
                        // ILK sayfada "tamamlandi" gorunur. Burada ANVIL'e yollarsak
                        // bu kitap SELECTED olmaktan cikar ve IKINCI sayfa onun icin
                        // hic taranmaz - depoda 2. sayfada duran kitaplar gorunmez
                        // olurdu. Yonlendirme iki sayfa da bitince, tek seferde
                        // finishOnlySellStartup() icinde yapilir.
                        if (OnlySellMode.isEnabled()) continue;

                        if (task.get(book).isCompleted()) {
                            editStateBook(book, BookState.ANVIL);
                            continue;
                        }

                        if (task.get(book).shouldStore()) {
                            editStateBook(book, BookState.STORE);
                            task.get(book).setEarlyStore(true);
                        }
                    }

                    if (secondPageCheck) {
                        debug("BazaarFlipper: [STARTUP_CHECK] Switching to IDLE, firstStartup = false");
                        firstStartUp = false;
                        finishOnlySellStartup();
                        // Only Sell'de once acik satis emirlerini okuyup izlemeye
                        // aliriz; yoksa onceki oturumdan kalan emirlerin outbid'i
                        // hic yakalanmaz.
                        state = needsSellScan() ? State.SELL_SCAN : State.IDLE;
                        minecraft.player.closeContainer();
                        return;
                    }
                    secondPageCheck = true;
                    minecraft.player.closeContainer();

                }
            }

            case IDLE -> {

                if (firstStartUp) {
                    debug("BazaarFlipper: [IDLE] switching to Startup checks");
                    state = State.STARTUP_CHECK;
                    return;
                }

                if (notEnoughCash) {
                    debug("notEnoughCash is true");
                    if (!task.isEmpty()) {
                        debug("BazaarFlipper: [IDLE] task isn't empty");
                        notEnoughCash = false;
                        return;
                    }
                    debug("Starting clock");
                    clock.start(60000);
                    if (clock.shouldFire()) {
                        debug("1 Minute clock ended, switching to REPLACE_SELL");
                        state = State.REPLACE_SELL;
                    }
                    return;
                }

                // ONLY SELL: alim tarafi tamamen bittiyse ve satis emrimiz
                // outbid yendiyse, acik satislari guncel fiyattan yeniden
                // listelemek icin REPLACE_SELL'e gec.
                // Acilis taramasi yarim kaldiysa (RECOVERY, ekran acilmadi, sunucu
                // gecikti) burada tekrar denenir. Uc denemeden sonra pes edilir -
                // aksi halde SELL_SCAN -> zaman asimi -> RECOVERY -> IDLE -> SELL_SCAN
                // sonsuz donerdi. Ayrica makro calisirken Only Sell'i sonradan
                // acan kullanici da taramayi bu yoldan alir.
                if (needsSellScan()) {
                    if (sellScanAttempts >= 3) {
                        sellScanDone = true;
                        ActionLog.add(ActionLog.Tag.SELL, "could not read the open sell orders - giving up");
                    } else {
                        sellScanAttempts++;
                        sellScanClicks = 0;
                        // Bu yeniden deneme BILINCLI bir karar, "takildi" degil.
                        // Sayaci sifirlamazsak ucuncu denemeden once watchdog
                        // recoveryCount'u 3'e tasiyip makroyu tamamen durdurur ve
                        // asagidaki "pes et" dali hic calismaz.
                        recoveryCount = 0;
                        state = State.SELL_SCAN;
                        return;
                    }
                }

                // TEK DOGRU KAYNAK KUYRUK. Eskiden ayrica bir boolean bayrak
                // vardi ve "bayrak = kuyruk bos mu" atamasi tick thread'inde,
                // add() ise HTTP thread'inde calisiyordu: tam arada gelen bir
                // outbid bayragi sifirlatip kuyrukta unutuluyordu.
                if (!pendingSellOutbids.isEmpty() && OnlySellMode.sellOutbidActive()) {
                    outbidSellName = pendingSellOutbids.poll();
                    sellOrderName.clear();
                    ActionLog.add(ActionLog.Tag.SELL, (outbidSellName == null ? "a sell order" : outbidSellName)
                            + " was outbid - relisting at the current price");
                    ChatUtils.clientMessage("Sell order was outbid - relisting.");
                    state = State.REPLACE_SELL;
                    return;
                }

                Book outbidBook = firstBookInState(BookState.OUTBID);
                if (outbidBook != null && !isInventoryFull
                        && System.currentTimeMillis() >= outbidSpaceRetryMs) {
                    debug("Found outbid books, switching to OUTBID");
                    state = State.OUTBID;
                    didRemoveOrder = false;
                    didReceiveItems = false;
                    claimedItems = false;
                    counterBazaar = 0;
                    outbidClaimedAmount = 0;
                    return;
                }

                Book selectedBook = firstBookInState(BookState.SELECTED);
                if (selectedBook != null) {
                    // Fırsatçı depo taraması sırasında bu görev çoktan dolmuş olabilir.
                    // "16 lazımdı, elimde 17 var" durumunda eskiden -1 adetlik anlamsız
                    // bir sipariş açılıyordu; artık doğrudan çekiç turuna geçiyoruz.
                    if (task.get(selectedBook).isCompleted()) {
                        debug(selectedBook.getRomanLevel(selectedBook.level())
                                + " icin siparise gerek yok (eksik=" + task.get(selectedBook).getAmountToOrder()
                                + "), ANVIL'e geciliyor");
                        editStateBook(selectedBook, BookState.ANVIL);
                        return;
                    }
                    debug("Found selected books, switching to BAZAAR_NAVIGATION");
                    activeBook = selectedBook;
                    debug("Active book set to: " + activeBook);
                    state = State.BAZAAR_NAVIGATION;
                    return;
                }

                Book bookToStore = firstBookInState(BookState.STORE);
                if (bookToStore != null) {

                    state = State.STORE;
                    isInventoryFull = false;
                    useSecondPage = false;
                    storePageFlipped = false;
                    storedThisVisit = 0;
                    return;
                }


                if (System.currentTimeMillis() < anvilFullRetryMs) {
                    // Envanter dolu ve birlestirilecek cift yok: ANVIL'i, yani depo
                    // acma denemelerini bekletiyoruz - yoksa her turda bir /ec komutu
                    // gider.
                    //
                    // AMA COMBINE VE SELL'I BEKLETMIYORUZ. Bu iki is envanterde YER
                    // ACAR; tam da bekledigimiz sey odur. Hepsini birden durdurmak,
                    // elinde satilmaya hazir kitap olan bir gorevi de kilitler ve
                    // makro kendi kendini kalici olarak bloke ederdi.
                    if (!booksInState(BookState.COMBINE).isEmpty()) {
                        state = State.COMBINE;
                    } else if (!booksInState(BookState.SELL).isEmpty()) {
                        state = State.SELL;
                    }
                    // Hicbiri yoksa IDLE'da sessizce bekle - komut gonderilmez.
                    return;
                }

                // Zincir, kardeş görevin (ör. 2to5) siparişinin dolmasını BEKLEMEZ.
                // Sadece o isme ait kitaplar şu anda envanterde depolanmayı bekliyorsa
                // (BookState.STORE) birkaç tick geciktirilir; yoksa birleştirme sırasında
                // üretilen ara seviye kitaplarla fiziksel olarak karışırlar. IDLE zaten
                // STORE'u ANVIL'den önce işlediği için bu gecikme birkaç tıklama sürer.
                List<Book> booksToAnvil = chainReadyBooksInState(BookState.ANVIL);
                if (booksToAnvil.isEmpty() && !booksInState(BookState.ANVIL).isEmpty()) {
                    debug("ANVIL kisa sureli bekliyor: ayni isimden depolanmayi bekleyen kitap var");
                    return;
                }

                if (!booksToAnvil.isEmpty()) {
                    isInventoryFull = false;
                    boolean shouldCheck = false;
                    for (Book book : booksToAnvil) {
                        if (task.get(book).shouldCheckEnderChest()) {
                            shouldCheck = true;
                            continue;
                        }

                        ActionLog.add(ActionLog.Tag.ANVIL, book.name() + ": entered anvil with "
                                + Math.max(0, task.get(book).inInventory) + " books");
                        editStateBook(book, BookState.COMBINE);
                    }
                    if (shouldCheck) {
                        state = State.ANVIL;
                    } else {
                        state = State.COMBINE;
                    }

                }

            }

            case FETCHING -> {

                // ONLY SELL: gorev kurulumu FIYAT VERISINE BAGLI OLMAMALI.
                // flipItemsList yalnizca KARLI hatlari icerir. Hicbir kitap su an
                // karli degilse - ki Only Sell'i tam da o zaman aciyorsun - liste
                // sonsuza kadar bos kalir, processData hic calismaz, gorevler hic
                // kurulmaz ve 60 sn sonra FETCHING zaman asimina ugrar. Uc kez
                // ust uste olunca watchdog makroyu tamamen durdurur.
                // Only Sell'de bu durakta yapacak bir sey yok: tohumla ve gec.
                if (OnlySellMode.blocksNewOrders()) {
                    if (firstStartUp) seedOnlySellTasks();
                    state = State.IDLE;
                    return;
                }

                if (!flipItemsList.isEmpty()) {
                    processData();
                    state = State.IDLE;
                }

                clock.start(5000);
                if (clock.shouldFire()) flipItemsList = flipCalculator.getFlipItemsList();
            }

            case BAZAAR_NAVIGATION -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container open, opening bazaar for " + activeBook.name());
                    openBazaar(activeBook.name().replace("Ultimate", ""));
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    List<Integer> slots = inventoryScanner.findContainer(activeBook.getRomanLevel(activeBook.level()));
                    debug("Bazaar open, clicking slot " + slots + " for " + activeBook.getRomanLevel(activeBook.level()));
                    if (slots.isEmpty()) return;
                    click(slots.getFirst(), false);
                }

                if (containerCheck(activeBook.name())) clock.start(randomizer());
                if (containerCheck(activeBook.name()) && clock.shouldFire()) {
                    debug("book container open, clicking slot 15");
                    click(15, false);
                }

                if (containerCheck("How many do you want")) clock.start(randomizer());
                if (containerCheck("How many do you want") && clock.shouldFire()) {
                    debug("qty prompt open, clicking slot 16");
                    click(16, false);
                }
                if (minecraft.screen instanceof SignEditScreen) clock.start(randomizer());
                if (minecraft.screen instanceof SignEditScreen && clock.shouldFire()) {
                    debug("sign screen detected, handling sign");
                    handleSign();
                }

                if (containerCheck("How much do you want to pay")) clock.start(randomizer());
                if (containerCheck("How much do you want to pay") && clock.shouldFire()) {
                    debug("clicking slot 12 to confirm price, book=" + activeBook);
                    bazaarMonitor.add(activeBook, inventoryScanner.getUnitPrice(12), false);
                    click(12, false);
                }

                if (containerCheck("Confirm")) clock.start(randomizer());
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    debug("confirming buy order for " + activeBook);
                    click(13, false);
                    ActionLog.add(ActionLog.Tag.BUY, activeBook.getRomanLevel(activeBook.level())
                            + " x" + lastOrderAmount + " buy order placed");
                    if (shouldStore(activeBook)) {
                        editStateBook(activeBook, BookState.STORE);
                        state = State.IDLE;
                        return;
                    }
                    editStateBook(activeBook, BookState.BUY_ORDER);
                    state = State.IDLE;

                }

            }

            case OUTBID -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening bazaar for Wise");
                    openBazaar("Wise");
                }

                if (containerCheck("Wise")) clock.start(randomizer());
                if (containerCheck("Wise") && clock.shouldFire()) {
                    debug("Wise open, clicking slot 50");
                    click(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {

                    Book bookToHandle = firstBookInState(BookState.OUTBID);

                    if (bookToHandle == null) {
                        minecraft.player.closeContainer();
                        state = State.IDLE;
                        return;
                    }

                    if (claimedItems) {
                        if (didReceiveItems) {
                            claimedItems = false;
                            didReceiveItems = false;
                            return;
                        }
                        return;
                    }


                    List<Integer> slots = inventoryScanner.findContainer("BUY " + bookToHandle.getRomanLevel(bookToHandle.level()));
                    debug("found " + slots.size() + " slots for " + bookToHandle);

                    if (slots.isEmpty()) {
                        if (!task.get(bookToHandle).isCompleted() && !didRemoveOrder && counterBazaar < 3) {
                            counterBazaar++;
                            return;
                        }

                        // Sipariş ortada yok (doldu ya da iptal edildi): izlemeyi bırak.
                        // Eskiden monitor kaydı kalıyordu ve saatler sonra sahte bir
                        // outbid uyarısı gönderip görevi birleştirme zincirinin
                        // ortasında OUTBID'e atıyordu - havuz bölünüp öksüz doğuyordu.
                        bazaarMonitor.finish(bookToHandle);

                        int remainingOrder = Math.max(0, task.get(bookToHandle).getAmountToOrder());
                        ActionLog.add(ActionLog.Tag.OUTBID, bookToHandle.getRomanLevel(bookToHandle.level())
                                + ": " + outbidClaimedAmount + " claimed, "
                                + (remainingOrder == 0 ? "line complete" : remainingOrder + " being re-ordered"));
                        outbidClaimedAmount = 0;

                        editStateBook(bookToHandle, task.get(bookToHandle).isCompleted() ? BookState.ANVIL : BookState.SELECTED);
                        didRemoveOrder = false;
                        counterBazaar = 0;
                        return;

                    }


                    if (!slots.isEmpty()) {
                        int amount = inventoryScanner.checkOrder(slots.getFirst());
                        debug("order amount=" + amount + ", clicking slot " + slots.getFirst());
                        if (amount > inventoryScanner.getEmptyInventorySlots()) {
                            // SONSUZ DONGU DUZELTMESI (OUTBID <-> STORE).
                            //
                            // Eski kod bu gorevi kosulsuz STORE'a yolluyordu. Ama STORE
                            // yalnizca storeTask.inInventory > 0 olan kitaplari depolar;
                            // outbid olan gorev ise tam da mallarini HENUZ ALMAMIS
                            // gorevdir, envanterinde genelde 0 kitap vardir. STORE
                            // hicbir sey depolayamiyor, earlyAction bayragi yuzunden
                            // gorevi aninda OUTBID'e geri atiyor ve makro
                            //   OUTBID -> STORE -> OUTBID -> ...
                            // seklinde sonsuza kadar donuyordu. Her turda click(50)
                            // cagrildigi icin stateEnteredMs surekli sifirlaniyor,
                            // yani watchdog bu donguyu HIC goremiyordu.
                            //
                            // Envanteri dolduran kitaplar baska gorevlere ait; onlari
                            // bu gorev depolayamaz.
                            if (task.get(bookToHandle).inInventory > 0) {
                                task.get(bookToHandle).setEarlyAction(true);
                                editStateBook(bookToHandle, BookState.STORE);
                                state = State.STORE;
                                isInventoryFull = true;
                                storePageFlipped = false;
                                minecraft.player.closeContainer();
                                return;
                            }

                            // Depolayacak kendi kitabimiz yok, yani yer acamayiz.
                            // SIPARISE DOKUNMUYORUZ: bazaarda oldugu gibi duruyor,
                            // mallar kaybolmuyor, sonra claim edilebilir. Zincir
                            // (COMBINE) ilerledikce envanterde yer acilir.
                            debug("claim icin yer yok (gereken=" + amount
                                    + ", bos=" + inventoryScanner.getEmptyInventorySlots()
                                    + "), siparis bekletiliyor");
                            ActionLog.add(ActionLog.Tag.OUTBID, bookToHandle.getRomanLevel(bookToHandle.level())
                                    + ": inventory too full to claim " + amount + " - waiting for space");
                            outbidSpaceRetryMs = System.currentTimeMillis() + 15_000;
                            isInventoryFull = true;
                            minecraft.player.closeContainer();
                            state = State.IDLE;
                            return;
                        }
                        click(slots.getFirst(), false);
                        if (amount == 0) {
                            debug("amount=0, returning early");
                            return;
                        }

                        claimedItems = true;


                        task.get(bookToHandle).addInInventory(amount);
                        outbidClaimedAmount += amount;
                    }
                }

                if (containerCheck("Order")) clock.start(randomizer());
                if (containerCheck("Order") && clock.shouldFire()) {
                    didRemoveOrder = true;
                    List<Integer> slot = inventoryScanner.findContainer("Cancel Order");
                    if (slot.isEmpty()) return;
                    debug("Order screen open, clicking slot " + slot.getFirst());
                    click(slot.getFirst(), false);

                }
            }

            case STORE -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening ender chest");
                    if (useSecondPage) {
                        openEnderChest(true);
                        return;
                    }
                    openEnderChest(false);

                }

                    if (isStorageOpen()) clock.start(speedMode());
                    if ((isStorageOpen()) && clock.shouldFire()) {
                    Book bookToHandle = firstBookInState(BookState.STORE);

                    if (bookToHandle == null) {
                        minecraft.player.closeContainer();
                        state = State.IDLE;
                        return;
                    }

                    Task storeTask = task.get(bookToHandle);
                    List<Integer> slots = new ArrayList<>();
                    slots.addAll(inventoryScanner.findLoreInv(bookToHandle.getRomanLevel(bookToHandle.level())));

                    // SAYIM DİSİPLİNİ: envanterdeki her eşleşen kitabı değil, SADECE bu
                    // görevin kendi sayısı kadarını depola. ESKİ KOD hepsini gömüyordu;
                    // 1to5 zinciri Wisdom II üretmişken 2to5 görevi STORE'a düşerse o
                    // kitaplar da depoya gidiyor, zincir yarıda kalıyor ve öksüz parça
                    // doğuyordu. Kitaplar birbirinin aynı olduğu için hangi fiziksel
                    // kitabın taşındığı önemsiz; önemli olan ADEDİN doğru olması.
                    if (!slots.isEmpty() && storeTask.inInventory > 0) {
                        if (inventoryScanner.getEmptyContainerSlots() == 0) {
                            // ESKİ KOD burada koşulsuz useSecondPage = true yapıyordu.
                            // İki sayfa da doluysa aynı dolu sayfayı sonsuza kadar
                            // açıp kapatıyordu (log: "no container, opening ender chest"
                            // satırının saniyede 2-3 kez tekrarlaması). Artık sayfa
                            // yalnızca BİR KEZ çevrilir, ikisi de doluysa pes edilir.
                            if (!storePageFlipped) {
                                storePageFlipped = true;
                                useSecondPage = !useSecondPage;
                                storeTask.setShouldCheckSecondPage(useSecondPage);
                                debug("bu depo sayfasi dolu, diger sayfaya geciliyor (secondPage=" + useSecondPage + ")");
                                minecraft.player.closeContainer();
                                return;
                            }
                            ChatUtils.clientMessage("Depo tamamen dolu! " + bookToHandle.name()
                                    + " kitaplari envanterde tutuluyor. Depoda yer acilmadan depolama yapilamaz.");
                            storeTask.setEarlyAction(false);
                            isInventoryFull = false;
                            editStateBook(bookToHandle, BookState.BUY_ORDER);
                            minecraft.player.closeContainer();
                            state = State.IDLE;
                            return;
                        }

                        click(slots.getFirst(), true);
                        debug("storing " + bookToHandle.name() + " at slot " + slots.getFirst() + " (kalan kendi payi: " + (storeTask.inInventory - 1) + ")");
                        storeTask.addInInventory(-1);
                        storeTask.addInEnderChest(1);
                        storePageFlipped = false;
                        storedThisVisit++;
                        return;
                    }

                    // Kendi payımız bitti (ya da envanterde bu kitaptan kalmadı).
                    if (storedThisVisit > 0) {
                        ActionLog.add(ActionLog.Tag.STORE, bookToHandle.getRomanLevel(bookToHandle.level())
                                + ": " + storedThisVisit + " moved to storage, "
                                + Math.max(0, storeTask.getAmountToOrder()) + " still on buy order");
                        storedThisVisit = 0;
                    }

                    if (storeTask.isEarlyAction()) {
                        editStateBook(bookToHandle, BookState.OUTBID);
                        storeTask.setEarlyAction(false);
                        return;
                    }

                    if (storeTask.isEarlyStore()) {
                        editStateBook(bookToHandle, BookState.SELECTED);
                        storeTask.setEarlyStore(false);
                        return;
                    }

                    editStateBook(bookToHandle, BookState.BUY_ORDER);
                    debug("kendi payimiz depolandi, BUY_ORDER'a geciliyor");
                }
            }

            case ANVIL -> {
                Book bookToHandle = chainReadyBookInState(BookState.ANVIL);

                if (bookToHandle == null) {
                    minecraft.player.closeContainer();
                    state = State.COMBINE;
                    return;
                }

                if (!isStorageOpen()) clock.start(randomizer());
                if (!isStorageOpen() && clock.shouldFire()) {
                    debug("no ender chest, opening it");
                    if (task.get(bookToHandle).isShouldCheckSecondPage()) {
                        openEnderChest(true);
                        return;
                    }
                    openEnderChest(false);

                }

                    if (isStorageOpen()) clock.start(speedMode());
                    if ((isStorageOpen()) && clock.shouldFire()) {
                    Task currentTask = task.get(bookToHandle);
                    List<Integer> slots = new ArrayList<>();

                    slots.addAll(inventoryScanner.findLoreContainer(bookToHandle.getRomanLevel(bookToHandle.level())));

                    // Depoda kaç tane olduğu değil, BİZİM kaç tane çekeceğimiz önemli:
                    // slots listesi bir önceki turdan kalmış ya da başka bir göreve ait
                    // kitapları da içerebilir, hepsini sığdırmaya çalışıp boşuna
                    // COMBINE'a kaçmayalım.
                    int booksToPull = Math.min(slots.size(), Math.max(0, currentTask.inEnderChest));

                    // HEPSI SIGMAZSA SIGDIGI KADARINI CEK.
                    //
                    // ESKI KOD "hepsi sigmiyorsa hicbirini alma" diyordu ve tam da
                    // kilitlenmenin sebebi buydu: 6 kitap cekilecek, 5 slot bos ->
                    // hicbiri alinmiyor, hicbir sey birlesmiyor, hic yer acilmiyor.
                    // Oysa asagidaki blok zaten TICK BASINA BIR kitap cekiyor;
                    // buradaki kontrol sadece bir on kontrol. Tek bir slot bile
                    // bossa cekmeye devam etmek her zaman ilerleme demek.
                    if (booksToPull > 0 && inventoryScanner.getEmptyInventorySlots() == 0) {
                        // Envanter GERCEKTEN dolu. Yer acmanin tek yolu birlestirmek.
                        //
                        // Cift once bu gorevde aranir, yoksa DIGER ANVIL gorevlerinde.
                        // Sadece bu goreve bakmak yetmiyordu: chainReadyBookInState
                        // her zaman haritadaki ILK ANVIL gorevini donduruyor, o gorevin
                        // cifti yoksa makro ona kilitlenip digerlerini hic denemiyor ve
                        //   ANVIL -> COMBINE -> SELL -> IDLE -> ANVIL
                        // dongusu aynen geri geliyordu.
                        Book combinable = hasCombinablePairInInventory(bookToHandle)
                                ? bookToHandle
                                : firstBookWithCombinablePair();

                        if (combinable != null) {
                            debug("envanter dolu, once " + combinable.name() + " birlestiriliyor");
                            // Birlestirme bitince depoda kalani almak icin ANVIL'e
                            // donebilsin: tek seferlik recheck hakkini iade ediyoruz.
                            task.get(combinable).setAnvilRecheckAttempted(false);
                            editStateBook(combinable, BookState.COMBINE);
                            state = State.COMBINE;
                            minecraft.player.closeContainer();
                            return;
                        }

                        // ONLY SELL SONSUZ DONGU EMNIYETI.
                        //
                        // Only Sell'de STORE fazi yok, yani hicbir sey yer acamaz.
                        // Uc denemeden sonra bu hat birakilir.
                        if (OnlySellMode.isEnabled()) {
                            currentTask.anvilFullBounces++;
                            if (currentTask.anvilFullBounces >= 3) {
                                ChatUtils.clientMessage(bookToHandle.name()
                                        + ": envanter dolu, bu hat birakildi. Yer acip makroyu yeniden baslat.");
                                ActionLog.add(ActionLog.Tag.ANVIL, bookToHandle.name()
                                        + ": inventory full, line dropped - free some slots and restart");
                                task.remove(bookToHandle);
                                state = State.IDLE;
                                minecraft.player.closeContainer();
                                return;
                            }
                        }

                        // GERCEK CIKMAZ: envanter tamamen dolu ve hicbir gorevde
                        // birlestirilecek cift yok. Hicbir sey yer acamaz.
                        //
                        // Burada state = State.COMBINE demek eski hatayi tekrarlamak
                        // olurdu (COMBINE is bulamaz, SELL'e, oradan IDLE'a duser ve
                        // saniyede birkac /ec komutu gonderilir). Bunun yerine
                        // kullaniciyi uyarip bekliyoruz - envanterde yer acmak
                        // insanin isi.
                        ChatUtils.clientMessage("Envanter dolu ve birlestirilecek cift yok. "
                                + "Birkac slot acin - makro 30 saniyede bir tekrar deneyecek.");
                        ActionLog.add(ActionLog.Tag.ANVIL, bookToHandle.name()
                                + ": inventory full, nothing to combine - waiting for free slots");
                        anvilFullRetryMs = System.currentTimeMillis() + 30_000;
                        minecraft.player.closeContainer();
                        state = State.IDLE;
                        return;
                    }
                    currentTask.anvilFullBounces = 0;

                    debug("found " + slots.size() + " book slots in ender chest, kendi payimiz: " + booksToPull);

                    // SAYIM DİSİPLİNİ: depodaki her eşleşen kitabı değil, SADECE bu
                    // görevin kendi sayısı (inEnderChest) kadarını çek. Aksi halde
                    // kardeş görevin (ör. 2to5) depodaki stoğu da havuza karışıyor,
                    // toplam 2'nin kuvveti olmaktan çıkıyor ve zincir sonunda artık
                    // (öksüz parça) kalıyordu.
                    if (!slots.isEmpty() && currentTask.inEnderChest > 0) {
                        debug("pulling slot " + slots.getFirst() + " from ender chest (kalan kendi payi: " + (currentTask.inEnderChest - 1) + ")");
                        click(slots.getFirst(), true);
                        currentTask.addInInventory(1);
                        currentTask.addInEnderChest(-1);
                        // İlerleme kaydettik: bir sonraki takılmada iki sayfa da yeniden
                        // taranabilsin ve COMBINE tekrar bir kez ANVIL'e yollayabilsin.
                        currentTask.setOtherPageChecked(false);
                        currentTask.setAnvilRecheckAttempted(false);
                        return;
                    }

                    // Taban seviye bitti. Depoda bu isme ait, hiçbir görevin stoğu
                    // olmayan ara seviye artık varsa (III/IV) onları da envantere al -
                    // birleştirme havuzuna katılsınlar, depoda çürümesinler. Sayaçlara
                    // dokunulmaz, çünkü bu artıklar sipariş miktarından zaten düşüldü.
                    List<Integer> leftovers = leftoverContainerSlots(bookToHandle);
                    if (!leftovers.isEmpty() && inventoryScanner.getEmptyInventorySlots() > 0) {
                        debug("pulling leftover intermediate book from slot " + leftovers.getFirst());
                        click(leftovers.getFirst(), true);
                        currentTask.setOtherPageChecked(false);
                        currentTask.setAnvilRecheckAttempted(false);
                        return;
                    }

                    // Bu sayfada bize ait kitap yok. DİĞER sayfaya (ec / ec 2) bir kez bak.
                    // ESKİ KOD sadece shouldCheckSecondPage bayrağı açıksa sayfa
                    // değiştiriyordu; bayrak kapalıyken kitaplar 2. sayfada kalmışsa
                    // bot onları hiç göremiyor, sayaç "ec=2" derken depoda 0 buluyor ve
                    // ANVIL <-> COMBINE arasında sonsuza kadar gidip geliyordu.
                    if (!currentTask.isOtherPageChecked()) {
                        currentTask.setOtherPageChecked(true);
                        currentTask.setShouldCheckSecondPage(!currentTask.isShouldCheckSecondPage());
                        debug("bu sayfada yok, diger sayfaya bakiliyor (secondPage=" + currentTask.isShouldCheckSecondPage() + ")");
                        minecraft.player.closeContainer();
                        return;
                    }

                    // Her iki sayfa da tarandı. FİZİKSEL GERÇEK SAYAÇTAN ÜSTÜNDÜR:
                    // depoda bize ait kitap yoksa sayacı sıfırla, yoksa COMBINE
                    // "depoda hâlâ kitap var" sanıp bizi tekrar buraya yollar.
                    if (currentTask.inEnderChest > 0) {
                        debug("sayac ec=" + currentTask.inEnderChest + " diyor ama iki sayfada da yok, sayac gercege gore sifirlaniyor");
                        currentTask.clearEnderChest();
                    }
                    // Ara seviye artik bayragi da burada duser: iki sayfa da
                    // tarandi, depoda alinacak bir sey kalmadi. Dusmezse
                    // shouldCheckEnderChest() surekli true doner ve gorev
                    // ANVIL <-> COMBINE arasinda gidip gelir.
                    currentTask.storageLeftover = false;

                    ActionLog.add(ActionLog.Tag.ANVIL, bookToHandle.name() + ": entered anvil with "
                            + Math.max(0, currentTask.inInventory) + " books");
                    editStateBook(bookToHandle, BookState.COMBINE);
                }
            }

            case COMBINE -> {

                Book bookToHandle = chainReadyBookInState(BookState.COMBINE);

                if (bookToHandle == null) {
                    state = State.SELL;
                    minecraft.player.closeContainer();
                    return;
                }

                int level = 0;
                for (int i = bookToHandle.level(); i < bookToHandle.sellLevel(); i++) {
                    if (inventoryScanner.locate(bookToHandle.getRomanLevel(i)).size() >= 2) {
                        level = i;
                        break;
                    }
                }

                if (!containerCheck("Anvil")) clock.start(randomizer());
                if (!containerCheck("Anvil") && clock.shouldFire()) {
                    debug("no anvil open, opening it");
                    openAnvil();
                }

                if (containerCheck("Anvil") && counter < 2) clock.start(speedMode());
                if (containerCheck("Anvil") && counter < 2 && clock.shouldFire()) {
                    if (level == 0) {
                        // SIRA ONEMLI: once "depoda hala stogum var mi", sonra
                        // "envanterde satis seviyesinde kitap var mi".
                        //
                        // Kitaplar fiziksel olarak birbirinin AYNISI; locate() kimin
                        // kitabi oldugunu ayirt edemez. Satis kontrolu once yapilirsa,
                        // depoda hala 6 kitabi olan bir gorev, KARDES gorevin envanterde
                        // bekleyen satis seviyesindeki kitabini gorup "isim bitti" sanip
                        // SELL'e geciyor; SELL de gorevi siliyor. Sonuc: depodaki 6 kitap
                        // takipsiz kaliyor ve bir sonraki turda AYNI kitap icin ikinci kez
                        // para harcaniyor. Depoda stok varken bir gorev asla bitmis sayilmaz.
                        if (task.get(bookToHandle).inEnderChest > 0 && !task.get(bookToHandle).isAnvilRecheckAttempted()) {
                            // Depoyu SADECE BİR KEZ yeniden kontrol et. Sınırsız
                            // denemek sonsuz ANVIL <-> COMBINE döngüsü demek: sayaç
                            // "depoda kitap var" derken depo boşsa bot iki state
                            // arasında saatlerce gidip geliyordu.
                            task.get(bookToHandle).setAnvilRecheckAttempted(true);
                            debug("no pair to combine but ec=" + task.get(bookToHandle).inEnderChest
                                    + ", sending back to ANVIL to pull the rest (tek seferlik)");
                            editStateBook(bookToHandle, BookState.ANVIL);
                        } else if (task.get(bookToHandle).inEnderChest <= 0
                                && !inventoryScanner.locate(bookToHandle.getRomanLevel(bookToHandle.sellLevel())).isEmpty()) {
                            // ec <= 0 SARTI KURALIN TAMAMLAYICISI: depoda stogu olan
                            // bir gorev ASLA satisa gecmez. Ustteki dal recheck hakki
                            // varken ANVIL'e yolluyor; hak bittiyse ve depoda hala stok
                            // gorunuyorsa buraya dusmesi gerekir, satisa degil. Aksi
                            // halde kardes gorevin kitabini kendi kitabi sanip gorevi
                            // siler, depodaki stok takipsiz kalir ve ayni kitap ikinci
                            // kez satin alinir.
                            debug("no pair to combine, sell-level copy confirmed in inventory, switching to SELL");
                            editStateBook(bookToHandle, BookState.SELL);
                        } else {
                            // Buraya normalde HİÇ düşülmemeli: havuz her zaman 2'nin
                            // kuvveti olacak şekilde sipariş ediliyor ve zincir kilidi
                            // sayesinde ortasından mal çekilmiyor. Yine de düşüldüyse
                            // fiziksel bir kayıp var demektir (relog, manuel müdahale,
                            // envanter dolduğu için yarım kalan zincir...). Sonsuz
                            // döngüye girmemek için görevi bırakıyoruz; kalan parçalar
                            // makro yeniden başlatıldığında STARTUP_CHECK tarafından
                            // birim olarak sayılıp sipariş miktarından düşülecek.
                            ChatUtils.clientMessage(bookToHandle.name() + " icin havuz eksik kaldi, gorev birakiliyor. Kalan ara seviye kitaplar makro yeniden baslatildiginda siparis miktarindan dusulecek.");
                            debug("dead end for " + bookToHandle.name() + " - physical shortage, dropping task");
                            ActionLog.add(ActionLog.Tag.COMBINE, bookToHandle.name()
                                    + ": not enough books left, line dropped");
                            TradeHistory.abandon(bookToHandle);
                            task.remove(bookToHandle);
                        }
                        return;
                    }


                    List<Integer> book = inventoryScanner.findLoreInv(bookToHandle.getRomanLevel(level));

                    if (!book.isEmpty()) {
                        if (inventoryScanner.findMisMatch(bookToHandle.getRomanLevel(level))) {
                            // Çekiçten çıkıyoruz: içeride yarım kalmış kitap olabilir.
                            // Sayaçları sıfırla ki bir sonraki turda "2 kitap kondu"
                            // sanıp tek kitapla çıktıya basmayalım.
                            resetCombineCounters();
                            minecraft.player.closeContainer();
                            return;
                        }
                        counter++;
                        click(book.getFirst(), true);
                        return;
                    } else {
                        List<Integer> bookInContainer = inventoryScanner.findLoreContainer(bookToHandle.getRomanLevel(level));
                        if (bookInContainer.size() >= 2) counter++;
                    }
                }

                // Sayıcı 2'ye ulaştıysa (2 kitap da konduysa) ve onay beklenmiyorsa saati başlat
                if (counter == 2 && !combineConfirmPending) {
                    combineConfirmClock.start(speedMode());
                    combineConfirmPending = true;
                }

                // Onay bekleniyorsa ve saatin süresi dolduysa tıklama işlemini yap
                if (counter == 2 && combineConfirmPending && combineConfirmClock.shouldFire()) {
                    debug("counter==2, clicking anvil output slot 22 with normal click");
                    click(22, false);

                    if (clickedOnce) {
                        clickedOnce = false;
                        counter = 0;
                        combineConfirmPending = false;
                        return;
                    }
                    clickedOnce = true;
                    combineConfirmClock.start(speedMode());
                }
            }

            case SELL -> {
                List<Integer> slots = new ArrayList<>();
                List<Book> bookList = (booksInState(BookState.SELL));
                if (bookList.isEmpty()) {
                    debug("bookstoSell empty, switching to IDLE");
                    state = State.FETCHING;
                    return;
                }
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening bazaar for tomato");
                    openBazaar("tomato");
                }

                if (containerCheck("tomato")) clock.start(randomizer());
                if (containerCheck("tomato") && clock.shouldFire()) {
                    debug("tomato bazaar open, clicking slot 50");
                    click(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {

                    for (Book book : bookList) {
                        // ESKİ KOD: getRomanLevel(5) sabitti; sellLevel 5 değilse satış
                        // slotu hiç bulunamıyor ve kitap sonsuza kadar elde kalıyordu.
                        slots.addAll(inventoryScanner.findContainer("SELL " + book.getRomanLevel(book.sellLevel())));
                    }
                    debug("found " + slots.size() + " sell slots");

                    if (!slots.isEmpty()) {
                        debug("clicking sell slot " + slots.getFirst());
                        click(slots.getFirst(), false);
                    }
                    if (slots.isEmpty()) {
                        debug("no slots found, clicking on: " + bookList.getFirst().name());
                        List<Integer> slot = inventoryScanner.findLoreInv(bookList.getFirst().getRomanLevel(bookList.getFirst().sellLevel()));
                        if (slot.isEmpty()) {
                            // bookList her tick'te yeniden üretilen GEÇİCİ bir liste;
                            // ondan silmek gerçek durumu değiştirmiyordu ve aynı kitap
                            // sonsuz döngüye giriyordu. Gerçek durumu ANVIL'e çekiyoruz.
                            debug("sell-level copy not found for " + bookList.getFirst().name() + ", sending back to ANVIL to recheck ender chest");
                            editStateBook(bookList.getFirst(), BookState.ANVIL);
                            bookList.removeFirst();
                            return;
                        }
                        click(slot.getFirst(), false);
                    }
                }

                if (containerCheck("Order")) clock.start(randomizer());
                if (containerCheck("Order") && clock.shouldFire()) {
                    List<Integer> slot = inventoryScanner.findContainer("Cancel Order");
                    if (slot.isEmpty()) return;
                    debug("Order screen open, clicking slot " + slot.getFirst());
                    click(slot.getFirst(), false);
                    sellOrderCancelled = true;
                }

                if (!bookList.isEmpty() && containerCheck(bookList.getFirst().name())) clock.start(randomizer());
                if (!bookList.isEmpty() && containerCheck(bookList.getFirst().name()) && clock.shouldFire()) {
                    debug("book screen open, clicking slot 16");
                    click(16, false);
                }

                if (containerCheck("At what price are you selling")) clock.start(randomizer());
                if (containerCheck("At what price are you selling") && clock.shouldFire()) {
                    debug("price prompt, clicking slot 12");
                    // Fiyati TIKLAMADAN ONCE oku: satis emrini bu fiyatla
                    // izlemeye alacagiz, outbid tespiti buna gore yapiliyor.
                    pendingSellPrice = inventoryScanner.getUnitPrice(12);
                    click(12, false);
                }

                if (containerCheck("Confirm")) clock.start(randomizer());
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    Book soldBook = bookList.getFirst();
                    debug("confirm prompt, clicking slot 13 and removing " + soldBook + " from sell list");
                    click(13, false);

                    int listedAmount = inventoryScanner.locate(soldBook.getRomanLevel(soldBook.sellLevel())).size();
                    ActionLog.add(ActionLog.Tag.SELL, soldBook.getRomanLevel(soldBook.sellLevel())
                            + ": " + (sellOrderCancelled ? "open sell order cancelled, " : "")
                            + "listed " + Math.max(1, listedAmount) + " in total");
                    sellOrderCancelled = false;

                    // Satis emrini izlemeye al - outbid yenirse haberimiz olsun.
                    if (pendingSellPrice > 0) {
                        bazaarMonitor.add(soldBook, pendingSellPrice, true);
                        pendingSellPrice = 0;
                    }

                    // SURE OLCUMU BURADA BITER: ilk buy order -> sell order acilisi.
                    // Ayni isimden birden fazla hat ayni satis emrinde birlesebilir,
                    // hepsinin kaydi birlikte kapanir.
                    for (Book sameName : booksInState(BookState.SELL)) {
                        if (!sameName.name().equals(soldBook.name())) continue;
                        TradeHistory.sellOrderPlaced(sameName);
                    }

                    Task soldTask = task.get(soldBook);

                    // ONLY SELL HATTI BURADA KAPANIR - asagidaki dala GIRMEZ.
                    //
                    // NEDEN: Only Sell gorevlerinde amountToOrder = 0, yani
                    // getAmountToOrder() = 0 - (depo + envanter) neredeyse her zaman
                    // negatiftir ve bu dal calisir. Icerideki addInInventory(-qty)
                    // TAM havuz kadar dusuyor; ama havuzun bir kismi unitCredit
                    // olarak sayilmissa (depodaki ara seviye kitaplar) sayac EKSIYE
                    // duser. O zaman getAmountToOrder() ARTIYA doner, isCompleted()
                    // false olur ve IDLE gorevi BAZAAR_NAVIGATION'a yollar:
                    // "hicbir sey satin alma" modu gercek parayla alim yapar.
                    //
                    // Depoda hala stok kaldiysa kaybolmaz; makroyu yeniden
                    // baslatinca STARTUP_CHECK onu bulur ve yeni bir tur baslar.
                    if (soldTask != null && OnlySellMode.isEnabled()) {
                        ActionLog.add(ActionLog.Tag.SELL, soldBook.name() + ": only sell line finished");

                        // AYNI ISIMDEKI TUM SELL GOREVLERI birlikte kapanir.
                        // removeDuplicateBooks'a guvenemeyiz: o metot
                        // getAmountToOrder() < 0 olan gorevleri saymiyor, Only
                        // Sell gorevlerinde ise amountToOrder 0 oldugu icin stok
                        // tutan her gorev zaten < 0'dir - yani hicbiri sayilmaz ve
                        // hicbiri silinmez. Kardes hat (or. 2to5) SELL'de asili
                        // kalir, elinde kitap yokken kendi satis emrini iptal edip
                        // yeniden acar ya da "havuz eksik kaldi" diye dusulur.
                        for (Book sameName : booksInState(BookState.SELL)) {
                            if (!sameName.name().equals(soldBook.name())) continue;
                            task.remove(sameName);
                        }
                        task.remove(soldBook);
                        return;
                    }

                    if (soldTask != null && soldTask.getAmountToOrder() < 0) {
                        soldTask.addInInventory(-soldBook.getQtyAmount(soldBook.level()));
                        editStateBook(soldBook, BookState.SELECTED);
                        // Gorev yasamaya devam ediyor: yeni bir olcum turu baslasin.
                        TradeHistory.begin(soldBook);
                        return;
                    }

                    // Aynı isimden birden fazla görev aynı anda satış seviyesine
                    // ulaştıysa (1to5 + 2to5 havuzu birlikte birleştiği için normal),
                    // tek satış emri hepsini kapsar; o görevleri toplu kaldır.
                    removeDuplicateBooks(task);
                    task.remove(soldBook);
                    bookList.removeFirst();

                }
            }

            /*
             * Acilista BIR KEZ calisir: bazaar > Manage Orders ekranini acar,
             * oradaki SELL satirlarini okur ve her birini BazaarMonitor'e satis
             * emri olarak kaydeder. REPLACE_SELL ile birebir ayni navigasyon
             * (tomato bazaar -> slot 50), fark su ki burada hicbir sey iptal
             * edilmez; sadece okuma yapilir.
             */
            case SELL_SCAN -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("[SELL_SCAN] opening bazaar to read open sell orders");
                    openBazaar("tomato");
                }

                if (containerCheck("tomato")) clock.start(randomizer());
                if (containerCheck("tomato") && clock.shouldFire()) {
                    // SONSUZ DONGU EMNIYETI: click() her cagrisinda stateEnteredMs'i
                    // sifirliyor, yani watchdog bu ikili tekrar ettikce ASLA
                    // tetiklenmez. Slot 50 herhangi bir sebeple "Manage Orders"
                    // acmiyorsa (arayuz degisti, ekran gecikti) makro burada
                    // sonsuza kadar tiklardi. Bes denemede acilmadiysa vazgecilir.
                    if (sellScanClicks >= 5) {
                        debug("[SELL_SCAN] siparis ekrani acilmadi, tarama birakiliyor");
                        ActionLog.add(ActionLog.Tag.SELL, "could not open the order screen - sell orders not watched");
                        sellScanDone = true;
                        state = State.IDLE;
                        minecraft.player.closeContainer();
                        return;
                    }
                    sellScanClicks++;
                    debug("[SELL_SCAN] tomato bazaar open, clicking slot 50");
                    click(50, false);
                }

                // "!tomato" emniyeti: containerCheck ICERIYOR bakiyor. Urun ekrani
                // da "Bazaar" kelimesini tasisaydi bu blok daha siparis ekrani
                // acilmadan calisir, hicbir emir bulamaz ve taramayi "bitti"
                // sayardi. Iki kosul birden aranınca o ihtimal kapaniyor.
                if (containerCheck("Bazaar") && !containerCheck("tomato")) clock.start(randomizer());
                if (containerCheck("Bazaar") && !containerCheck("tomato") && clock.shouldFire()) {
                    registerOpenSellOrders();
                    sellScanDone = true;
                    state = State.IDLE;
                    minecraft.player.closeContainer();
                }
            }

            case REPLACE_SELL -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening bazaar for tomato");
                    openBazaar("tomato");
                }

                if (containerCheck("tomato")) clock.start(randomizer());
                if (containerCheck("tomato") && clock.shouldFire()) {
                    debug("tomato bazaar open, clicking slot 50");
                    click(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    List<Integer> slots = new ArrayList<>();

                    slots.addAll(inventoryScanner.getSellOrder());
                    if (slots.isEmpty()) {
                        List<Integer> slot = new ArrayList<>();
                        for (String string : sellOrderName) {
                            slot.addAll(inventoryScanner.findLoreInv(string));
                        }

                        if (!slot.isEmpty()) {
                            click(slot.getFirst(), false);
                            return;
                        }

                        // Bayat hedef kalmasin: temizlenmezse notEnoughCash
                        // yolundan girilen bir sonraki REPLACE_SELL yanlis emri
                        // hedef alir.
                        outbidSellName = null;
                        state = State.FETCHING;
                        minecraft.player.closeContainer();
                        return;

                    }

                    // OUTBID YENEN emri sec, listedeki ilkini degil.
                    //
                    // ESKI DAVRANIS: her zaman slots.getFirst(). Birden fazla acik
                    // satis emri varsa saglikli olan iptal edilip yeniden aciliyor,
                    // outbid yenen ise hic duzelmiyordu. Ustelik getSellOrder() adinda
                    // "SELL" gecen HER urunu dondurdugu icin config'te olmayan alakasiz
                    // bir emir de iptal edilebiliyordu.
                    // endsWith, contains DEGIL: "SELL 16x Ultimate Wise VI" adi
                    // "Ultimate Wise V" ile eslesir ve outbid yenmemis baska bir
                    // emri (hatta elle acilmis alakasiz bir emri) iptal ederdik.
                    int chosen = slots.getFirst();
                    if (outbidSellName != null) {
                        for (int slot : slots) {
                            String name = inventoryScanner.getName(slot).replace("SELL ", "").trim();
                            if (!name.endsWith(outbidSellName)) continue;
                            chosen = slot;
                            break;
                        }
                    }

                    sellOrderName.add(inventoryScanner.getName(chosen).replace("SELL ", ""));

                    click(chosen, false);

                }

                if (containerCheck("Order")) clock.start(randomizer());
                if (containerCheck("Order") && clock.shouldFire()) {
                    List<Integer> slot = inventoryScanner.findContainer("Cancel Order");
                    if (slot.isEmpty()) return;
                    debug("Order screen open, clicking slot " + slot.getFirst());
                    click(slot.getFirst(), false);
                }

                if (!sellOrderName.isEmpty() && containerCheck(sellOrderName.getFirst())) clock.start(randomizer());
                if (!sellOrderName.isEmpty() && containerCheck(sellOrderName.getFirst()) && clock.shouldFire()) {
                    debug("book screen open, clicking slot 16");
                    click(16, false);
                }

                if (containerCheck("At what price are you selling")) clock.start(randomizer());
                if (containerCheck("At what price are you selling") && clock.shouldFire()) {
                    debug("price prompt, clicking slot 12");
                    pendingSellPrice = inventoryScanner.getUnitPrice(12);
                    click(12, false);
                }

                if (containerCheck("Confirm")) clock.start(randomizer());
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    debug("confirm prompt, clicking slot 13 and removing " + sellOrderName.getFirst() + " from sell list");
                    click(13, false);

                    // Yeniden listelenen emri de izlemeye al: bir daha outbid
                    // yenirse yine yakalayalim.
                    Book relisted = findBookBySellName(sellOrderName.getFirst());
                    if (relisted != null && pendingSellPrice > 0) {
                        bazaarMonitor.add(relisted, pendingSellPrice, true);
                    }
                    pendingSellPrice = 0;

                    ActionLog.add(ActionLog.Tag.SELL, sellOrderName.getFirst() + " relisted at the current price");
                    sellOrderName.clear();
                    outbidSellName = null;
                    state = State.FETCHING;

                }


            }

            case RECOVERY -> {
                // Toparlanma: acik ekrani kapat, birkac yuz ms bekle, tum gecici
                // bayraklari temizle ve akisi bastan dagit. Gorevler (task haritasi)
                // SILINMEZ - sadece "yarim kalmis ekran/sayac" durumu temizlenir.
                if (minecraft.screen instanceof AbstractSignEditScreen) {
                    // Tabela ekrani konteyner degildir; closeContainer onu kapatmaz.
                    minecraft.setScreen(null);
                    return;
                }
                if (isContainerOpen()) {
                    minecraft.player.closeContainer();
                    return;
                }

                clock.start(1200);
                if (clock.shouldFire()) {
                    resetCombineCounters();
                    activeBook = null;
                    didRemoveOrder = false;
                    claimedItems = false;
                    didReceiveItems = false;
                    isInventoryFull = false;
                    outbidSpaceRetryMs = 0;
                    anvilFullRetryMs = 0;
                    useSecondPage = false;
                    storePageFlipped = false;
                    secondPageCheck = false;
                    outbidClaimedAmount = 0;
                    storedThisVisit = 0;
                    sellOrderCancelled = false;

                    // ACILIS ORTASINDA TOPARLANDIYSAK Only Sell gorevlerini
                    // atariz. RECOVERY secondPageCheck'i sifirliyor, yani
                    // STARTUP_CHECK 1. sayfayi bastan tarayacak. Only Sell'de
                    // gorevler iki sayfa boyunca SELECTED kaldigi icin ayni
                    // kitaplar IKINCI kez sayilir: inEnderChest ve unitCredit
                    // ikiye katlanir. inEnderChest ANVIL'de kendini duzeltiyor
                    // ama unitCredit duzelmiyor - eli bos bir gorev "stok var"
                    // gorunup cekici bosuna acardi. Temiz sayfadan baslamak
                    // bedava: FETCHING yeniden tohumlar.
                    if (firstStartUp && OnlySellMode.isEnabled()) {
                        task.clear();
                        onlySellSeeded = false;
                    }

                    Humanizer.reset();
                    ActionLog.add(ActionLog.Tag.RECOVERY, "recovered - restarting the flow");
                    state = State.START;
                }
            }
        }
    }

    public String getStateName() {
        return state.name();
    }

    public String getActiveBookName() {
        return activeBook != null ? activeBook.getRomanLevel(activeBook.level()) : "-";
    }

    /**
     * HUD'un cizdigi tek satirlik gorev bilgisi.
     *
     * onOrder = hala bazaar'da bekleyen adet, owned = elde + depoda + ara seviye
     * kredisi, target = o hattin toplam hedefi (2'nin kuvveti).
     */
    public record TaskInfo(String name, int level, int sellLevel, String phase,
                           int onOrder, int owned, int target) {
    }

    /** Kod adlarini degil, oyuncunun bildigi terimleri dondurur. */
    public List<TaskInfo> getTaskInfo() {
        List<TaskInfo> lines = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            Book book = entry.getKey();
            Task t = entry.getValue();

            int target = book.getQtyAmount(book.level());
            int owned = Math.max(0, t.inEnderChest + t.inInventory + t.unitCredit);
            int onOrder = Math.max(0, t.getAmountToOrder());

            lines.add(new TaskInfo(book.name(), book.level(), book.sellLevel(),
                    phaseName(t.getBookState()), onOrder, Math.min(owned, target), target));
        }
        return lines;
    }

    private static String phaseName(BookState state) {
        return switch (state) {
            case SELECTED -> "queued";
            case BUY_ORDER -> "buy order";
            case OUTBID -> "outbid";
            case STORE -> "storing";
            case ANVIL -> "fetching";
            case COMBINE -> "combining";
            case SELL -> "selling";
        };
    }

    /** Ana state'in oyuncu tarafindan anlasilir adi (State HUD burayi okur). */
    public String getFriendlyState() {
        return switch (state) {
            case START, FETCHING -> "Scanning prices";
            case STARTUP_CHECK -> "Checking storage";
            case IDLE -> "Idle";
            case BAZAAR_NAVIGATION -> "Buying";
            case OUTBID -> "Outbid";
            case STORE -> "Storing";
            case ANVIL -> "Fetching";
            case COMBINE -> "Anvil";
            case SELL -> "Selling";
            case SELL_SCAN -> "Reading sells";
            case REPLACE_SELL -> "Relisting";
            case RECOVERY -> "Recovering";
        };
    }

    public List<String> getTaskSummary() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            Book book = entry.getKey();
            Task t = entry.getValue();
            lines.add(book.getRomanLevel(book.level()) + ": " + t.getBookState()
                    + " (remaining=" + t.getAmountToOrder() + ")");
        }
        return lines;
    }

    private boolean shouldStore(Book book) {
        return task.get(book).shouldStore();
    }

    private void resetCombineCounters() {
        counter = 0;
        clickedOnce = false;
        combineConfirmPending = false;
        combineConfirmClock.stop();
    }

    private void lastStateCheck() {
        if (state != lastState) {
            debug("state changed: " + lastState + " -> " + state);
            clock.stop();
            // COMBINE yarıda kesildiyse (SELL'e geçiş, outbid, mismatch, reboot...)
            // sayaçlar bir sonraki tura sızmasın.
            resetCombineCounters();
            lastState = state;
            stateEnteredMs = System.currentTimeMillis();
        }
    }

    private List<Book> booksInState(BookState target) {
        List<Book> result = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target) result.add(entry.getKey());
        }
        return result;
    }

    private List<Book> booksInState(BookState target, BookState target2) {
        List<Book> result = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target) result.add(entry.getKey());
        }

        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target2) result.add(entry.getKey());
        }
        return result;
    }


    private void editStateBook(Book book, BookState target) {
        Task t = task.get(book);
        if (t == null) {
            debug("Attempted state change for missing task: " + book);
            return;
        }
        BookState old = t.getBookState();
        t.setBookState(target);
        // Gorev ilerledi: hem state bekcisi hem "hic ilerleme yok" sayaci sifirlanir.
        stateEnteredMs = System.currentTimeMillis();
        lastProgressMs = stateEnteredMs;
        debug("Book state changed: " + book + " | " + old + " -> " + target
                + " remaining=" + t.getAmountToOrder()
                + " inv=" + t.inInventory
                + " ec=" + t.inEnderChest);
        dumpTasks();
    }

    private Book firstBookInState(BookState target) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target) return entry.getKey();
        }
        return null;
    }

    /**
     * Birleştirme zinciri, KARDEŞ GÖREVİN SİPARİŞİNİ BEKLEMEZ - her görev tek başına
     * zaten tam 2'nin kuvveti kadar birim toplar (16xI -> 1 adet V, 8xII -> 1 adet V).
     * Tek beklediği şey, o isme ait kitapların envanterde depolanmayı bekliyor
     * olmaması: aksi halde birleştirme sırasında üretilen ara seviye kitaplarla
     * fiziksel olarak karışırlar. IDLE zaten STORE'u ANVIL'den önce işlediği için
     * bu bekleme birkaç tıklama sürer, sipariş dolmasını beklemez.
     */
    private Book chainReadyBookInState(BookState target) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() != target) continue;
            if (hasUnstoredBooksForName(entry.getKey().name())) continue;
            return entry.getKey();
        }
        return null;
    }

    private List<Book> chainReadyBooksInState(BookState target) {
        List<Book> result = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() != target) continue;
            if (hasUnstoredBooksForName(entry.getKey().name())) continue;
            result.add(entry.getKey());
        }
        return result;
    }

    /**
     * ENVANTERDE (depoda degil) birlestirilebilir bir cift var mi?
     *
     * COMBINE'in kendi testiyle birebir ayni mantik (bkz. case COMBINE): taban
     * seviyeden satis seviyesine kadar bak, ayni seviyeden 2 tane varsa
     * birlestirilebilir. findLoreInv bilerek kullanildi - locate/findLoreContainer
     * acik olan sandigi da sayabilir; ANVIL bu kontrolu ender chest EKRANI
     * ACIKKEN yapiyor, yani depodaki kitaplari envanterdekiyle karistirirsak
     * gorevi bosuna COMBINE'a yollariz ve orada birlestirecek bir sey bulamaz.
     */
    private boolean hasCombinablePairInInventory(Book book) {
        for (int i = book.level(); i < book.sellLevel(); i++) {
            if (inventoryScanner.findLoreInv(book.getRomanLevel(i)).size() >= 2) return true;
        }
        return false;
    }

    /**
     * ANVIL durumundaki gorevler icinde envanterinde birlestirilebilir cift
     * OLAN ilkini dondurur.
     *
     * Envanter tamamen dolduysa tek cikis yolu birlestirmek. chainReadyBookInState
     * her zaman haritadaki ILK ANVIL gorevini donduruyor; o gorevin cifti yoksa
     * makro ona kilitlenip cifti olan digerlerini hic denemiyordu.
     */
    private Book firstBookWithCombinablePair() {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() != BookState.ANVIL) continue;
            if (hasUnstoredBooksForName(entry.getKey().name())) continue;
            if (hasCombinablePairInInventory(entry.getKey())) return entry.getKey();
        }
        return null;
    }

    /** Bu isme ait, şu an envanterde depolanmayı bekleyen kitap var mı? */
    private boolean hasUnstoredBooksForName(String name) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (!entry.getKey().name().equals(name)) continue;
            if (entry.getValue().getBookState() != BookState.STORE) continue;
            if (entry.getValue().inInventory > 0) return true;
        }
        return false;
    }

    /** Bu isim şu an birleştirme/satış zincirinde mi? */
    private boolean isNameInChain(String name) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (!entry.getKey().name().equals(name)) continue;
            if (CHAIN_PHASE.contains(entry.getValue().getBookState())) return true;
        }
        return false;
    }

    private void removeDuplicateBooks(Map<Book, Task> tasks) {
        Map<String, Integer> counts = new HashMap<>();
        List<Book> stateBooks = new ArrayList<>();

        stateBooks.addAll(booksInState(BookState.SELL));

        for (Book book : stateBooks) {
            if (task.get(book).getAmountToOrder() < 0) continue;
            counts.merge(book.name(), 1, Integer::sum);
        }

        // ESKİ KOD: isim sayısı >1 ise o isimden TÜM görevleri siliyordu - alım
        // fazındaki (kitapları çoktan satın alınmış) kardeş görev de siliniyor ve
        // o kitaplar sahipsiz kalıyordu. Artık sadece SELL'deki görevler silinir.
        tasks.entrySet().removeIf(entry ->
                entry.getValue().getBookState() == BookState.SELL
                        && counts.getOrDefault(entry.getKey().name(), 0) > 1
        );
    }

    /**
     * Config'te bu isim için tanımlı seviyeler (ör. "Ultimate Wise" -> {1, 2}).
     * Bu seviyelerdeki kitaplar bir görevin meşru stoğudur, artık değildir.
     */
    private Set<Integer> configuredLevelsFor(String name) {
        Set<Integer> levels = new HashSet<>();
        for (Book b : GoofyConfig.INSTANCE.books) {
            if (!b.name().equals(name)) continue;
            // Kapatilmis hat "yapilandirilmis" degildir. Sayilsaydi o seviye
            // leftoverLevels'tan dislanir ama onu sahiplenen bir gorev de
            // olmazdi: depodaki o kitaplar hicbir zaman goruilmez, hicbir zaman
            // cekilmez, sonsuza kadar depoda kalirdi.
            if (!GoofyConfig.isBookEnabled(b)) continue;
            levels.add(b.level());
        }
        return levels;
    }

    /** Bu kitap, ismi için config'te tanımlı EN DÜŞÜK seviye mi? (artık kredisi ona yazılır) */
    private boolean isLowestConfiguredLevel(Book book) {
        for (Book b : GoofyConfig.INSTANCE.books) {
            if (!b.name().equals(book.name())) continue;
            // KAPATILMIS hat "yapilandirilmis" sayilmaz. Sayilsaydi: level 1
            // kapali + level 2 acikken hicbir gorev "en dusuk seviye" olmaz,
            // ara seviye artiklar hic kredilenmez ve Only Sell'de o gorev
            // "elin bos" diye silinir - depodaki kitaplar orada kalirdi.
            if (!GoofyConfig.isBookEnabled(b)) continue;
            if (b.level() < book.level()) return false;
        }
        return true;
    }

    /**
     * Taban ile satış seviyesi arasında kalan ve config'te KENDİ girdisi olmayan
     * seviyeler. Bu seviyelerdeki kitaplar hiçbir görevin stoğu değildir; önceki
     * turlardan kalmışlardır (sellLevel=5 ve config {1,2} iken: 3 ve 4).
     */
    private List<Integer> leftoverLevels(Book book) {
        Set<Integer> configured = configuredLevelsFor(book.name());
        List<Integer> levels = new ArrayList<>();
        for (int i = book.level() + 1; i < book.sellLevel(); i++) {
            if (configured.contains(i)) continue;
            levels.add(i);
        }
        return levels;
    }

    /** Envanterdeki artıkların TABAN SEVİYE cinsinden birim değeri (III = 4, IV = 8...). */
    private int leftoverUnitsInInventory(Book book) {
        int units = 0;
        for (int i : leftoverLevels(book)) {
            int count = inventoryScanner.findLoreInv(book.getRomanLevel(i)).size();
            if (count == 0) continue;
            units += count * (1 << (i - book.level()));
        }
        return units;
    }

    /** Depoda (açık olan sayfada) duran artık kitapların slotları. */
    private List<Integer> leftoverContainerSlots(Book book) {
        List<Integer> slots = new ArrayList<>();
        for (int i : leftoverLevels(book)) {
            slots.addAll(inventoryScanner.findLoreContainer(book.getRomanLevel(i)));
        }
        return slots;
    }

    /**
     * Depodaki artıkları birim olarak sipariş miktarından düşer. Sadece o ismin
     * en düşük seviyeli görevine yazılır ki iki paralel görev aynı artığı iki kez
     * saymasın. Envanterdeki artıklar processData'da düşüldüğü için burada
     * yalnızca container taranır.
     */
    private void creditLeftoverUnitsFromContainer(List<Book> bookList) {
        for (Book book : bookList) {
            if (!isLowestConfiguredLevel(book)) continue;
            Task t = task.get(book);
            if (t == null) continue;

            int units = 0;
            for (int i : leftoverLevels(book)) {
                int count = inventoryScanner.findLoreContainer(book.getRomanLevel(i)).size();
                if (count == 0) continue;
                units += count * (1 << (i - book.level()));
            }
            if (units == 0) continue;

            t.addUnitCredit(units);
            // Bu kitaplar DEPODA duruyor: ANVIL'in ugrayip onlari cekmesi gerek.
            t.storageLeftover = true;
            debug(book.name() + " icin depoda " + units + " birimlik ara seviye kitap bulundu, siparis miktarindan dusuldu");
        }
    }

    /**
     * ONLY SELL açıkken başlatıldığında, config'teki her hat için SİPARİŞ
     * MİKTARI 0 olan bir görev açar.
     *
     * NEDEN 0: getAmountToOrder() = 0 - (depodaki + envanterdeki), yani her
     * zaman <= 0, yani isCompleted() her zaman true. IDLE bu görevleri SELECTED
     * dalında görür görmez ANVIL'e yollar - BAZAAR_NAVIGATION'a, yani yeni
     * sipariş açmaya, hiçbir koşulda giremezler. "Alma, sadece elindekini bitir"
     * kuralı böylece görev seviyesinde garanti altına alınır.
     *
     * Depoda ne olduğunu henüz bilmediğimiz için HER hat için görev açılır;
     * eli boş çıkanlar STARTUP_CHECK bitince finishOnlySellStartup() ile silinir.
     */
    private void seedOnlySellTasks() {
        if (onlySellSeeded || GoofyConfig.INSTANCE == null) return;
        onlySellSeeded = true;

        for (Book book : GoofyConfig.INSTANCE.books) {
            if (!GoofyConfig.isBookEnabled(book)) continue;
            if (task.containsKey(book)) continue;

            Task fresh = new Task(0);

            // Envanterde duran ARA SEVIYE kitaplari birim olarak yaz. Depodakiler
            // STARTUP_CHECK icinde creditLeftoverUnitsFromContainer ile eklenir;
            // orada bilerek sadece konteyner taraniyor, cift sayim olmasin diye.
            if (isLowestConfiguredLevel(book)) {
                int leftover = leftoverUnitsInInventory(book);
                if (leftover > 0) {
                    fresh.addUnitCredit(leftover);
                    debug("only sell: " + book.name() + " icin envanterde " + leftover + " birim ara seviye var");
                }
            }

            task.put(book, fresh);
            debug("only sell: " + book.getRomanLevel(book.level()) + " icin 0 siparisli gorev acildi");
        }

        ActionLog.add(ActionLog.Tag.SYSTEM,
                "only sell: checking storage for " + task.size() + " book line(s)");
    }

    /**
     * STARTUP_CHECK iki depo sayfasını da taradıktan sonra çalışır.
     *
     * 1) Eli tamamen boş görevleri siler - ne envanterde, ne depoda, ne de ara
     *    seviye kredisinde tek kitabı olmayan hat için yapacak iş yoktur;
     *    silinmezse makro sahibi olmadığı kitap için boşuna çekiç açardı.
     * 2) Kalan her görevi ANVIL'e yollar. ANVIL gerekiyorsa depodan çeker,
     *    COMBINE birleştirir, SELL satar.
     */
    private void finishOnlySellStartup() {
        if (!OnlySellMode.isEnabled()) return;

        List<Book> empty = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            Task t = entry.getValue();
            if (t.inEnderChest + t.inInventory + t.getUnitCredit() > 0) continue;
            empty.add(entry.getKey());
        }
        for (Book book : empty) {
            debug("only sell: " + book.getRomanLevel(book.level()) + " icin elde kitap yok, gorev kapatildi");
            task.remove(book);
        }

        if (task.isEmpty()) {
            ActionLog.add(ActionLog.Tag.SYSTEM, "only sell: no stock left - just watching sell orders");
            return;
        }

        for (Book book : new ArrayList<>(task.keySet())) {
            Task t = task.get(book);
            ActionLog.add(ActionLog.Tag.ANVIL, book.name() + " " + book.getRomanLevel(book.level())
                    + ": " + (t.inInventory + t.inEnderChest) + " on hand, combining");
            editStateBook(book, BookState.ANVIL);
        }
        ActionLog.add(ActionLog.Tag.SYSTEM, "only sell: " + task.size() + " line(s) have stock to finish");
    }

    /** Açılışta bir kez açık satış emirlerini okumamız gerekiyor mu? */
    private boolean needsSellScan() {
        return OnlySellMode.isEnabled() && !sellScanDone;
    }

    /**
     * AÇIK OLAN "Manage Orders" ekranındaki SATIŞ emirlerini izlemeye alır.
     *
     * NEDEN GEREKLİ: BazaarMonitor yalnızca makronun BU OTURUMDA kendi açtığı
     * satış emirlerini biliyordu (SELL / REPLACE_SELL içindeki add çağrıları).
     * Oyunu kapatıp açınca o liste sıfırlanıyor, önceki oturumda bırakılmış
     * satış emirleri hiç izlenmiyor ve outbid asla tespit edilmiyordu.
     */
    private void registerOpenSellOrders() {
        List<Integer> slots = inventoryScanner.getSellOrder();
        if (slots.isEmpty()) {
            ActionLog.add(ActionLog.Tag.SELL, "no open sell order found");
            return;
        }

        int watched = 0;
        for (int slot : slots) {
            String sellName = inventoryScanner.getName(slot).replace("SELL ", "").trim();
            Book book = findBookBySellName(sellName);
            if (book == null) {
                debug("[SELL_SCAN] '" + sellName + "' config'te yok, atlandi");
                continue;
            }

            double price = inventoryScanner.getUnitPrice(slot);
            if (price <= 0) {
                // Fiyat okunamadi. 0 ile kaydetsek monitor her turda "fiyat
                // degismis" sanip bos yere iptal-yeniden listele turu atardi.
                ActionLog.add(ActionLog.Tag.SELL, sellName + ": unit price unreadable, not watched");
                continue;
            }

            bazaarMonitor.add(book, price, true);
            watched++;
            debug("[SELL_SCAN] izlemeye alindi: " + sellName + " @ " + price);
        }

        ActionLog.add(ActionLog.Tag.SELL, "watching " + watched + " open sell order(s) for outbids");
    }

    private void processData() {
        if (flipItemsList.isEmpty()) return;

        // ONLY SELL: yeni HAT acilmaz. Mevcut gorevler (SELECTED dahil) normal
        // isler - outbid yenen bir siparis SELECTED'a doner ve yeniden acilmasi
        // GEREKIR, yoksa havuz tek sayiya duser ve zincir yarim kalir.
        //
        // Only Sell'de buraya zaten girilmez: FETCHING bu modu kendi basinda
        // karsilar ve seedOnlySellTasks()'i cagirip IDLE'a gecer. Bu satir yine
        // de dursun - processData baska bir yerden cagrilirsa emniyet olsun.
        if (OnlySellMode.blocksNewOrders()) return;
        debug("item check passed");
        double purse = scoreboardUtils.getPurse();
        debug("purse = " + purse);

        double cost = flipItemsList.stream().mapToDouble(FlipItem::totalCost).min().orElse(-1);

        if (cost != -1) {
            if (cost > purse) {
                notEnoughCash = true;
            }
        }

        for (FlipItem flipItem : flipItemsList) {
            Book book = flipItem.book();
            debug("Checking Flipitem " + book.name());

            if (task.containsKey(book)) continue;

            // O isim birleştirme/satış zincirindeyken de sipariş AÇILIR - kardeş hattın
            // (ör. Wisdom I) beklemesi gereksiz. Sadece SAYIMLAR yapılmaz: zincir şu an
            // envanterde ara seviye kitaplar (III/IV) ve taban seviye kitaplar
            // dolaştırıyor olabilir, onları "sahipsiz stok" sanıp siparişi azaltırsak
            // havuz tek sayıya düşer. Sipariş dolduğunda o kitaplar çoktan tükenmiş olur.
            boolean nameInChain = isNameInChain(book.name());
            if (nameInChain) {
                debug(book.name() + " zincirde: siparis aciliyor ama eldeki stok sayilmiyor");
            }

            int fullAmount = book.getQtyAmount(book.level());

            // Elde zaten duran ara seviye artıklar birim olarak düşülür ki toplam
            // havuz tam 2'nin kuvveti olsun ve zincir sonunda artık kalmasın.
            int credit = (isLowestConfiguredLevel(book) && !nameInChain) ? leftoverUnitsInInventory(book) : 0;
            int amount = Math.max(0, fullAmount - credit);

            double unitCost = flipItem.totalCost() / fullAmount;
            double actualCost = unitCost * amount;

            if (amount > 0 && purse < actualCost) continue;

            debug("User has enough money " + book.name());
            purse -= actualCost;
            debug("new purse = " + purse);

            Task newTask = new Task(amount);

            // Envanterde bu kitaptan (TAM taban seviyede) sahipsiz duran varsa onları
            // da say - böylece 8 adet lazımken elde 1 tane dururken 8 yerine 7 sipariş
            // açılır ve havuz tek sayıya kaymaz. STARTUP_CHECK aynı sayımı ilk açılışta
            // yaptığı için orada tekrar saymayalım diye firstStartUp'ta atlanır.
            int onHand = (firstStartUp || nameInChain) ? 0 : inventoryScanner.findLoreInv(book.getRomanLevel(book.level())).size();
            if (onHand > 0) {
                newTask.addInInventory(onHand);
                debug(book.name() + " icin envanterde " + onHand + " adet taban seviye kitap bulundu, siparis o kadar azaltildi");
            }

            task.put(book, newTask);
            TradeHistory.begin(book);
            ActionLog.add(ActionLog.Tag.BUY, book.getRomanLevel(book.level())
                    + " line opened - target " + amount);

            if (credit > 0) {
                ChatUtils.clientMessage(book.name() + " icin elde " + credit + " birim ara seviye kitap var, siparis " + fullAmount + " yerine " + amount + " adet aciliyor.");
            }

            if (newTask.isCompleted()) {
                // Sipariş gerekmiyor, elde yeterli var: doğrudan zincire gir.
                editStateBook(book, BookState.ANVIL);
            } else if (newTask.shouldStore()) {
                // Elde kısmi stok var: önce onu depola, sonra kalanı sipariş et.
                editStateBook(book, BookState.STORE);
                newTask.setEarlyStore(true);
            }

            debug("new task created size:" + task.size());
        }

    }


    /**
     * AÇIK OLAN depo sayfasını tarar ve hiçbir görevin sayacında olmayan kitapları
     * sahibi olabilecek göreve yazar. Tek yönlü ve güvenlidir:
     *
     *  - Sadece EKLER, asla eksiltmez. Bir sayfada kitap görmemek "o kitap yok"
     *    demek değildir (diğer sayfada olabilir). Sayaç şişmesi zaten ANVIL'de
     *    iki sayfa da tarandıktan sonra clearEnderChest() ile düzeltiliyor.
     *  - "Bu sayfadaki fiziksel adet > tüm görevlerin toplam iddiası" ise aradaki
     *    fark KESİNLİKLE sahipsizdir, çünkü toplam iddia iki sayfayı birden kapsar.
     *    En kötü ihtimalle az sayar, asla fazla saymaz. Bu yüzden tekrar tekrar
     *    çağrılması güvenlidir: kitap deftere girdiği anda "iddia" da artar.
     *  - Hiçbir görev ihtiyacından fazlasını almaz: 16 gerekirken depoda 17 varsa
     *    16'sı sayaca girer, 1 tanesi sahipsiz kalır ve eksik 0 olur (eskiden -1).
     *
     * Sadece TABAN seviyeler taranır; ara seviye artıklar STARTUP_CHECK'te birim
     * olarak kredilendiği için burada tekrar sayılırsa çift sayım olurdu.
     */
    private void syncOpenStoragePage() {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            Book book = entry.getKey();
            Task t = entry.getValue();

            int needed = t.getAmountToOrder();
            if (needed <= 0) continue; // bu görev zaten dolu, kitap almasına gerek yok

            int physicalHere = inventoryScanner.findLoreContainer(book.getRomanLevel(book.level())).size();
            if (physicalHere <= 0) continue;

            // Aynı isim + aynı taban seviyeye sahip başka görev yok (harita anahtarı
            // Book), yani bu seviyedeki iddia sadece bu görevin iddiasıdır.
            int unowned = physicalHere - t.inEnderChest;
            if (unowned <= 0) continue;

            int take = Math.min(unowned, needed);
            t.addInEnderChest(take);
            ChatUtils.clientMessage("Depoda " + take + "x " + book.getRomanLevel(book.level())
                    + " sahipsiz kitap bulundu, siparis miktarindan dusuldu (kalan eksik: "
                    + t.getAmountToOrder() + ").");
        }
    }

    private void openBazaar(String name) {
        if (containerCheck("bazaar")) return;
        debug("sending command for " + name);
        minecraft.player.connection.sendCommand("bz " + name);
    }

    private void openAnvil() {
        if (containerCheck("Anvil")) return;
        debug("openAnvil");
        minecraft.player.connection.sendCommand("Anvil");
    }

    private void openEnderChest(boolean useSecondPage) {
        if (isStorageOpen()) return;
        debug("openEnderChest");
        if (useSecondPage) {
            minecraft.player.connection.sendCommand(GoofyConfig.INSTANCE.secondPage);
            return;
        }
        minecraft.player.connection.sendCommand(GoofyConfig.INSTANCE.firstPage);
    }

    private void handleSign() {
        Task signTask = task.get(activeBook);
        if (signTask == null) {
            debug("sign icin gorev yok, iptal");
            minecraft.setScreen(null);
            state = State.IDLE;
            return;
        }

        // Tabelaya gelene kadar (depo taraması sayesinde) hedef dolmuş olabilir.
        // Negatif/sıfır miktar yazmak Hypixel'de ya hata verir ya da havuzu tek
        // sayıya kaydırırdı; artık sipariş hiç açılmıyor.
        int orderAmount = signTask.getAmountToOrder();
        if (orderAmount <= 0) {
            debug("siparis gerekmiyor (eksik=" + orderAmount + "), tabela iptal ediliyor");
            minecraft.setScreen(null);
            editStateBook(activeBook, BookState.ANVIL);
            state = State.IDLE;
            return;
        }

        lastOrderAmount = orderAmount;
        String amountToOrder = String.valueOf(orderAmount);
        if (minecraft.screen instanceof AbstractSignEditScreen signScreen) {
            debug("writing amount=" + amountToOrder + " for book=" + activeBook);
            try {
                Field messagesField = AbstractSignEditScreen.class.getDeclaredField("messages");
                messagesField.setAccessible(true);
                String[] messages = (String[]) messagesField.get(signScreen);
                messages[0] = amountToOrder;
                minecraft.setScreen(null);
            } catch (Exception e) {
                debug("reflection failed - " + e.getMessage());
                e.printStackTrace();
            }
        }
    }


    private boolean containerCheck(String name) {
        if (minecraft.screen == null || isOwnScreen()) return false;
        String title = minecraft.screen.getTitle().getString();
        return title.toLowerCase().contains(name.toLowerCase());
    }

    /**
     * GoofyAddons'un kendi arayüzü (M menüsü, HUD taşıma) açıkken makro bunu bir
     * bazaar/depo konteyneri sanmamalı. Menü açılınca makro zaten duraklatılıyor,
     * bu ikinci emniyet kemeri.
     */
    private boolean isOwnScreen() {
        return minecraft.screen instanceof GoofyGui;
    }

    /**
     * Depo ekranı (ender chest ya da herhangi bir sırt çantası) açık mı?
     * ESKİ KOD sadece "Jumbo Backpack" ve "Greater Backpack" başlıklarına bakıyordu;
     * config'te firstPage/secondPage başka bir çanta tipine ("backpack 1" gibi)
     * ayarlanmışsa (Large/Small/Medium...) bot ekranın açıldığını hiç fark etmiyor,
     * komutu boşuna tekrar tekrar gönderiyordu.
     */
    private boolean isStorageOpen() {
        return containerCheck("Ender Chest") || containerCheck("Backpack");
    }

    private boolean isContainerOpen() {
        return minecraft.screen != null && !isOwnScreen();
    }

    private void handleClaimedMessage(String string) {
        // ESKİ KOD: HER "Claimed" mesajı didReceiveItems'i açıyordu - satış claim'i,
        // başka bir kitabın claim'i, her şey. OUTBID'deki kilit erken açılıp aynı
        // sipariş iki kez okunuyor, sayaç şişiyor ve görev fiziksel olarak eksikken
        // "tamamlandı" sayılıp birleştirmeye giriyordu (garanti öksüz parça).
        if (state != State.OUTBID || !claimedItems) return;
        didReceiveItems = true;
    }

    /**
     * Only Sell fazini her tick yeniden hesaplar.
     *
     * Alim fazinda (SELECTED / BUY_ORDER / OUTBID / STORE) tek bir gorev bile
     * varsa FINISHING; hicbiri kalmadiysa SELL_ONLY. SELL_ONLY'ye gecildigi anda
     * uptime sayaci durur ve satis outbid takibi devreye girer.
     */
    private void updateOnlySellPhase() {
        if (!OnlySellMode.isEnabled()) {
            OnlySellMode.setPhase(OnlySellMode.Phase.OFF);
            // Mod kapatildi: satis emri taramasi "yapildi" sayilmasin. Kullanici
            // ayni oturumda tekrar acarsa emirler yeniden okunmali, yoksa o
            // acilista hicbir satis emri izlenmez.
            sellScanDone = false;
            sellScanAttempts = 0;
            return;
        }

        // Only Sell'de alim yapilmiyor; eski bir "para yetmiyor" bayragi
        // takili kalirsa IDLE 60 sn sonra bosuna REPLACE_SELL'e kacar.
        notEnoughCash = false;

        // ACILIS BITENE KADAR FAZ HESAPLANMAZ. Gorevler daha yeni kuruluyor ve
        // depo henuz sayilmadi; bos haritaya bakip "alim bitti" desek uptime
        // makro baslar baslamaz duruyor ve sohbete yanlis mesaj dusuyordu.
        // Kullanicinin logunda 15:28:47'de ucu de ayni saniyede gorunmesinin
        // sebebi buydu.
        if (firstStartUp) {
            OnlySellMode.setPhase(OnlySellMode.Phase.FINISHING);
            return;
        }

        boolean buying = false;
        for (Task t : task.values()) {
            if (BUY_PHASE.contains(t.getBookState())) {
                buying = true;
                break;
            }
        }

        OnlySellMode.Phase next = buying ? OnlySellMode.Phase.FINISHING : OnlySellMode.Phase.SELL_ONLY;
        if (next != OnlySellMode.phase()) {
            ActionLog.add(ActionLog.Tag.SYSTEM, next == OnlySellMode.Phase.SELL_ONLY
                    ? "only sell: buying finished - uptime paused, watching sell orders"
                    : "only sell: finishing open buy orders");
            if (next == OnlySellMode.Phase.SELL_ONLY) {
                ChatUtils.clientMessage("Only Sell: no buy orders left. Uptime paused, now only selling.");
            }
        }
        OnlySellMode.setPhase(next);
    }

    /** Config'te bu satis adina ("Wisdom V") sahip kitabi bulur. */
    private Book findBookBySellName(String sellName) {
        if (sellName == null || GoofyConfig.INSTANCE == null) return null;
        String needle = sellName.trim();

        for (Book book : GoofyConfig.INSTANCE.books) {
            if (book.getRomanLevel(book.sellLevel()).equals(needle)) return book;
        }
        // Manage Orders ekranindaki ad basinda adet ("16x ") gibi ekler
        // tasiyabiliyor. SONDAN eslestiririz - contains kullansaydik
        // "Ultimate Wise VI" adi "Ultimate Wise V" ile eslesirdi ve iki ayri
        // emir ayni Book'a baglanip birbirinin fiyatini ezerdi (BazaarMonitor
        // (book, isSellOrder) ikilisine gore tekillestiriyor). Sonuc: saglikli
        // emir "fiyat degismis" sanilip bosuna iptal edilip yeniden acilirdi.
        for (Book book : GoofyConfig.INSTANCE.books) {
            if (needle.endsWith(book.getRomanLevel(book.sellLevel()))) return book;
        }
        return null;
    }

    /**
     * Satis emri outbid yendi. Islemi IDLE yapar - burada sadece kayit.
     *
     * DIKKAT: bu metot BazaarMonitor'un HTTP thread'inden cagriliyor. Sadece
     * thread-safe kuyruga yazar; gorev haritasina veya state'e dokunmaz.
     */
    private void handleSellOutbid(Book book) {
        String name = book.getRomanLevel(book.sellLevel());
        pendingSellOutbids.add(name);
        ActionLog.add(ActionLog.Tag.OUTBID, name + " sell order was outbid");
        bazaarMonitor.finishSell(book);
    }

    private void handleOutbid(Book book) {
        Task t = task.get(book);
        if (t == null || !BUY_PHASE.contains(t.getBookState())) {
            // Görev artık alım fazında değil: eski bir sipariş kaydından gelen bu
            // uyarı, birleştirme zincirini ortasından keserdi.
            debug("stale outbid ignored for " + book);
            bazaarMonitor.finish(book);
            return;
        }
        debug("Found outbid:" + book.getRomanLevel(book.level()));
        TradeHistory.outbid(book);
        ActionLog.add(ActionLog.Tag.OUTBID, book.getRomanLevel(book.level()) + " was outbid");
        editStateBook(book, BookState.OUTBID);
    }


    private void handleFilledMessage(String string) {
        List<Book> booksInState = new ArrayList<>();
        booksInState.addAll(booksInState(BookState.BUY_ORDER, BookState.STORE));

        String stripped = string
                .replace("[Bazaar] Your Buy Order for ", "")
                .replace(" was filled!", "");

        stripped = stripped.substring(stripped.indexOf(' ') + 1);

        debug("stripped=" + stripped);

        for (Book book : booksInState) {
            if (!stripped.equals(book.getRomanLevel(book.level()))) continue;
            ActionLog.add(ActionLog.Tag.BUY, book.getRomanLevel(book.level()) + " buy order filled");
            editStateBook(book, BookState.OUTBID);
            bazaarMonitor.finish(book);
        }
    }

    private int randomizer() {
        int min = GoofyConfig.INSTANCE.minActionDelay;
        int max = GoofyConfig.INSTANCE.maxActionDelay;

        // KRİTİK: Delay'ler artık arayüzden elle girilebiliyor. nextInt(min, max)
        // max <= min iken IllegalArgumentException fırlatır ve bu HER TICK olur -
        // makro anında çöker. Bozuk aralıkta sabit bir değere düşüyoruz.
        if (max <= min) return Humanizer.fixedDelay(Math.max(50, min));

        // Duz rastgele yerine can egrisi + yorulma + mikro mola (Humanizer).
        // Aralik ayni kaliyor, sadece dagilim insan davranisina benziyor.
        return Humanizer.delay(min, max);
    }


    private int speedMode() {
        if (GoofyConfig.INSTANCE.speedMode) return Humanizer.fixedDelay(GoofyConfig.INSTANCE.speedModeDelay);
        return randomizer();
    }

    // =====================================================================
    // Takilma bekcisi
    // =====================================================================

    /**
     * Her state'in bir kum saati vardir. State degisince ya da bir tiklama
     * yapilinca saat sifirlanir; yani "is yapiliyorsa" sure islemez. Saat dolarsa
     * makro takilmis demektir: RECOVERY'e dusulur.
     *
     * 0 = bekci kapali. IDLE bilerek kapalidir: notEnoughCash durumunda 1 dakika
     * BEKLEMEK zaten tasarimin parcasi.
     */
    private long stateTimeoutMs(State target) {
        return switch (target) {
            case START, IDLE, RECOVERY -> 0;
            case FETCHING -> 60_000;
            case STARTUP_CHECK -> 30_000;
            case BAZAAR_NAVIGATION -> 25_000;
            case OUTBID -> 30_000;
            case STORE -> 30_000;
            case ANVIL -> 30_000;
            case COMBINE -> 30_000;
            case SELL -> 30_000;
            case SELL_SCAN -> 25_000;
            case REPLACE_SELL -> 45_000;
        };
    }

    private void watchdog() {
        if (state == State.RECOVERY) return;

        long now = System.currentTimeMillis();
        if (stateEnteredMs == 0) stateEnteredMs = now;
        if (lastProgressMs == 0) lastProgressMs = now;

        long limit = stateTimeoutMs(state);
        boolean stateStuck = limit > 0 && (now - stateEnteredMs) > limit;

        // IDLE'in kendi zaman asimi YOKTUR (siparislerin dolmasini beklemek normal).
        // Tek istisna: ortada hic gorev yoksa ve uzun suredir hicbir sey olmuyorsa
        // fiyatlari yeniden cekmek gerekir.
        boolean idleEmpty = state == State.IDLE
                && task.isEmpty()
                && !OnlySellMode.isEnabled()
                && !notEnoughCash
                && (now - stateEnteredMs) > IDLE_EMPTY_TIMEOUT_MS;

        if (!stateStuck && !idleEmpty) return;

        if (state == recoverFromState) {
            recoveryCount++;
        } else {
            recoverFromState = state;
            recoveryCount = 1;
        }

        String reason = stateStuck
                ? "no progress in " + state + " for " + (limit / 1000) + "s"
                : "no tasks at all for 10 minutes - refreshing prices";

        if (recoveryCount >= MAX_RECOVERY_ATTEMPTS) {
            ActionLog.add(ActionLog.Tag.RECOVERY, "stuck " + MAX_RECOVERY_ATTEMPTS
                    + " times in the same place - stopping the macro: " + reason);
            ChatUtils.clientMessage("Stuck " + MAX_RECOVERY_ATTEMPTS
                    + " times in the same place (" + reason + "). Stopping the macro to be safe.");
            FeatureManager.INSTANCE.stop();
            return;
        }

        ActionLog.add(ActionLog.Tag.RECOVERY, reason + " - recovering ("
                + recoveryCount + "/" + MAX_RECOVERY_ATTEMPTS + ")");
        ChatUtils.clientMessage("Stall detected: " + reason + ". Recovering...");

        state = State.RECOVERY;
        stateEnteredMs = now;
        lastProgressMs = now;
    }

    /**
     * Tiklamalarin TEK gecis noktasi. Buradan gecmesinin sebebi bekcinin saatini
     * sifirlamak: gercekten is yapilan bir state zaman asimina ugramasin.
     */
    private void click(int slot, boolean shift) {
        stateEnteredMs = System.currentTimeMillis();
        // Mikro mola sayaci SADECE burada ilerler - gecikme fonksiyonu her tick
        // cagriliyor, oraya baglansaydi mola saniyede bir tetiklenirdi.
        Humanizer.noteAction();
        InventoryUtils.clickSlot(slot, shift);
    }


    private class Task {
        private BookState bookState = BookState.SELECTED;
        private int amountToOrder;
        private int inEnderChest;
        private int inInventory;
        /** Elde zaten duran ara seviye kitapların taban seviye cinsinden birim değeri. */
        private int unitCredit;
        private boolean shouldCheckSecondPage = false;
        private boolean earlyAction = false;
        private boolean earlyStore = false;
        /** COMBINE takıldığında depo bir kez yeniden kontrol edildi mi? */
        private boolean anvilRecheckAttempted = false;
        /** ANVIL, bu takılmada depo sayfalarının ikisine de baktı mı? */
        private boolean otherPageChecked = false;

        private boolean isAnvilRecheckAttempted() {
            return anvilRecheckAttempted;
        }

        private void setAnvilRecheckAttempted(boolean anvilRecheckAttempted) {
            this.anvilRecheckAttempted = anvilRecheckAttempted;
        }

        private boolean isOtherPageChecked() {
            return otherPageChecked;
        }

        private void setOtherPageChecked(boolean otherPageChecked) {
            this.otherPageChecked = otherPageChecked;
        }

        /** Depoda bize ait kitap kalmadığı fiziksel olarak doğrulandığında çağrılır. */
        private void clearEnderChest() {
            this.inEnderChest = 0;
        }

        private boolean isShouldCheckSecondPage() {
            return shouldCheckSecondPage;
        }

        private void setShouldCheckSecondPage(boolean shouldCheckSecondPage) {
            this.shouldCheckSecondPage = shouldCheckSecondPage;
        }

        private boolean isEarlyAction() {
            return earlyAction;
        }

        private void setEarlyAction(boolean earlyAction) {
            this.earlyAction = earlyAction;
        }

        private Task(int amountToOrder) {
            this.amountToOrder = amountToOrder;
        }

        private BookState getBookState() {
            return bookState;
        }

        private void setBookState(BookState bookState) {
            this.bookState = bookState;
        }

        private void addInEnderChest(int inEnderChest) {
            this.inEnderChest += inEnderChest;
        }

        private void addInInventory(int inInventory) {
            this.inInventory += inInventory;
        }

        private void addUnitCredit(int units) {
            this.unitCredit += units;
            this.amountToOrder = Math.max(0, this.amountToOrder - units);
        }

        private int getUnitCredit() {
            return unitCredit;
        }

        private int getAmountToOrder() {
            return amountToOrder - (inEnderChest + inInventory);
        }

        /** Depoda bu göreve ait ARA SEVİYE kitap görüldü mü? */
        private boolean storageLeftover = false;
        /** ANVIL "envanter dolu" deyip kac kez geri dondu? (Only Sell dongu emniyeti) */
        private int anvilFullBounces = 0;

        private boolean shouldCheckEnderChest() {
            // inEnderChest yalnizca TABAN SEVIYE kitaplari sayar. Depoda sadece
            // ara seviye (III, IV...) kitap varsa bu sayac 0'dir ama kitaplar
            // orada durur. ANVIL'e ugramazsak IDLE dogrudan COMBINE'a gecer,
            // cekic bos elle acilir ve hat "havuz eksik" diye dusurulur - kitaplar
            // depoda curur. ANVIL ise leftoverContainerSlots ile onlari cekebiliyor.
            if (storageLeftover) return true;
            return inEnderChest > 0;
        }

        private boolean isCompleted() {
            return getAmountToOrder() <= 0;
        }

        private boolean shouldStore() {
            return inInventory > 0;
        }

        private boolean isEarlyStore() {
            return earlyStore;
        }

        private void setEarlyStore(boolean earlyStore) {
            this.earlyStore = earlyStore;
        }
    }
}
