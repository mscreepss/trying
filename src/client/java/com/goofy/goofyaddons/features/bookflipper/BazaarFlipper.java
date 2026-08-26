package com.goofy.goofyaddons.features.bookflipper;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.features.Feature;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.helper.BazaarMonitor;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.features.bookflipper.helper.BookLedger;
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
 *     eslesen kitaplari degil, yalnizca DEFTERDE kendi adresine yazili olanlari
 *     depolar; ANVIL de depodan yalnizca kendi defterinde yazili slotlari
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
        /**
         * Outbid yenen bir SATIS emrini iptal edip guncel fiyattan yeniden acar.
         * Kendi ic adim makinesi var (Relist) - ayni ekran akisin iki farkli
         * yerinde ciktigi icin "hangi ekran acik" sorusu tek basina yetmiyor.
         */
        RELIST,
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
    private boolean notEnoughCash = false;
    private boolean isInventoryFull = false;
    private boolean didRemoveOrder = false;
    private boolean claimedItems = false;
    private boolean didReceiveItems = false;
    private boolean firstStartUp = false;
    private int counterBazaar = 0;
    private boolean useSecondPage = false;
    private boolean secondPageCheck = false;
    /** Depo dolu diye sayfa BİR KEZ çevrildi mi? (iki sayfa da doluysa sonsuz döngüyü keser) */
    /**
     * Bu depolama ziyaretinde DOLU OLDUGUNU GORDUGUMUZ sayfalar.
     *
     * ESKI HALI tek bir boolean'di ("sayfayi bir kez cevirdim mi"). Uc ayri
     * yerden sifirlaniyordu ve "cevirdim" ile "diger sayfanin dolu oldugunu
     * gordum" ayni sey sanilıyordu. Sonuc: mod 2. sayfa BOSKEN bile pes edip
     * 1. sayfayi acip kapatmaya devam ediyordu.
     *
     * Artik kanit tutuluyor: bir sayfa ancak DOLU GORULDUYSE buraya girer ve
     * iki sayfa da girmeden asla pes edilmez. Ayni sayfa iki kez eklenemedigi
     * icin sonsuz gidip gelme de imkansiz.
     */
    private final Set<BookLedger.Place> storeFullPages = new HashSet<>();
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

    /** Only Sell: bu baslatmada eldeki stok icin gorevler kuruldu mu? */
    private boolean onlySellSeeded = false;
    /** SU AN acik olan depo sayfasi - openEnderChest her cagrildiginda guncellenir. */
    private BookLedger.Place currentStoragePage = BookLedger.Place.STORAGE_1;

    /** STORE: bir onceki tikta depoya atilan kitabin envanter adresi (-1 = yok). */
    private int pendingStoreAddress = -1;
    /** STORE: tiklamadan onceki sandik hali - kitabin nereye dustugunu bulmak icin. */
    private final Set<Integer> storeSnapshot = new HashSet<>();

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
     * ikincisi sessizce kayboluyordu. Ayrica yenileme hangi emri
     * duzeltecegini bilemedigi icin listedeki ILK emri iptal ediyordu - saglikli
     * emir bosuna churn ediliyor, outbid yenen hic duzelmiyordu.
     *
     * THREAD: BazaarMonitor'un HTTP thread'i yaziyor, tick thread'i okuyor.
     */

    /**
     * RELIST alt adimlari.
     *
     * NEDEN AYRI BIR ADIM SAYACI: akista "Manage Orders" ekrani IKI KEZ
     * geciyor - once emri bulmak icin, sonra iptalden sonra kitabi envanterden
     * secmek icin. Ekran basligina bakarak hangisinde oldugumuzu ayirt etmek
     * imkansiz. Ustelik basliklar birbirini kapsiyor: "Your Bazaar Orders"
     * hem "Bazaar" hem "Order" aramasiyla eslesiyor. Adim burada tutulunca
     * bu belirsizliklerin hicbiri kalmiyor.
     */
    private enum Relist {
        OPEN_ORDERS,
        FIND_ORDER,
        CANCEL,
        OPEN_PRODUCT,
        CREATE_OFFER,
        SET_PRICE,
        CONFIRM
    }

    private Relist relistStep = Relist.OPEN_ORDERS;
    /** Su an emri yenilenen kitap. */
    private Book relistBook = null;
    /** O kitabin satis seviyesindeki tam adi ("Ultimate Wise V"). */
    private String relistName = null;
    /** Yenilenmeyi bekleyen kitaplar. */
    private final Deque<Book> relistQueue = new ArrayDeque<>();
    /** Ayni kitap art arda yenilenmesin diye son yenileme zamani. */
    private final Map<String, Long> lastRelistMs = new HashMap<>();
    /** Bu adimda kac tick beklendi - takilirsa vazgecmek icin. */
    private int relistWaits = 0;
    /** Iptalden ONCE envanterde bu kitaptan kac tane vardi. */
    private int relistInvBefore = 0;
    /** Bu kitabin emri IPTAL EDILDI mi? (edildiyse asla yarim birakilmaz) */
    private boolean relistCancelled = false;
    /** Emri ekranda kac kez aradik? (bos ekrandan sonuc cikarmamak icin) */
    private int relistFindTries = 0;
    /** Bu kitap icin kac kez bastan denendi. */
    private int relistRetries = 0;

    private static final int RELIST_MAX_RETRIES = 3;
    /** Kuyrukta bundan uzun bekleyen outbid uyarisi bayat sayilir. */
    private static final long RELIST_STALE_MS = 10 * 60_000;
    /** Her kuyruk girdisinin eklendigi an (satis adina gore). */
    private final Map<String, Long> relistQueuedMs = new HashMap<>();

    /** Ayni satis emri en fazla bu araliktan sik yenilenmez. */
    private static final long RELIST_COOLDOWN_MS = 60_000;
    /** Bir adimda bu kadar tick bekledikten sonra pes edilir. */
    private static final int RELIST_MAX_WAITS = 300;


    /**
     * Outbid yenmis ALIM siparisleri. BazaarMonitor HTTP thread'inden haber
     * veriyor; gorev haritasina orada dokunmak ConcurrentModificationException
     * demek, o yuzden once kuyruga yazilir, tick thread'i isler.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Book> pendingBuyOutbids =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Outbid yenmis SATIS emirleri - ayni sebeple thread-safe kuyruk. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Book> pendingSellOutbidBooks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
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
                    + " eksik=" + missingUnits(e.getKey())
                    + " siparis=" + t.onOrder
                    + " " + BookLedger.summary(e.getKey())
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
        // Defter diskten okunur: onceki oturumda nerede ne biraktigimizi
        // biliyoruz. STARTUP_CHECK bunu gercekle karsilastirip hizalayacak.
        BookLedger.load();
        pendingStoreAddress = -1;
        storeSnapshot.clear();
        storeFullPages.clear();
        relistQueue.clear();
        relistQueuedMs.clear();
        pendingSellOutbidBooks.clear();
        lastRelistMs.clear();
        relistBook = null;
        relistName = null;
        relistStep = Relist.OPEN_ORDERS;
        relistWaits = 0;
        onlySellSeeded = false;
        sellScanDone = false;
        sellScanAttempts = 0;
        sellScanClicks = 0;
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
        // DEFTER SILINMEZ. Makro dursa da kitaplar depoda/envanterde duruyor;
        // kaydi atarsak yeniden baslattigimizda hepsi "sahipsiz" gorunur ve
        // hangi hatta ait olduklari bilgisi kaybolur.
        BookLedger.save();
        pendingStoreAddress = -1;
        storeSnapshot.clear();
        storeFullPages.clear();
        relistQueue.clear();
        relistQueuedMs.clear();
        pendingSellOutbidBooks.clear();
        relistBook = null;
        relistName = null;
        relistStep = Relist.OPEN_ORDERS;
        relistWaits = 0;
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
        // Outbid haberleri HTTP thread'inden kuyruga dusuyor; gorev haritasina
        // yalnizca burada, tick thread'inde dokunuluyor.
        drainBuyOutbids();
        drainSellOutbids();
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
            if (storageOpenTicks == 3) adoptOnOpenStoragePage();
        } else {
            storageOpenTicks = 0;
        }

        // STORE iki fazli calisiyor: bir tikta kitabi atiyor, sonraki tikta
        // nereye dustugunu buluyor. Arada state degistiyse (RECOVERY, outbid,
        // liste bosaldi) bu bayraklar bayatlar ve BIR SONRAKI depolama ziyaretinde
        // alakasiz bir kaydi siler. O yuzden STORE disinda her tick temizlenir.
        if (state != State.STORE && pendingStoreAddress >= 0) {
            pendingStoreAddress = -1;
            storeSnapshot.clear();
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

                // Sandigin BASLIGI gelince icerigi henuz gelmemis olabiliyor.
                // Hemen okursak bos sandik goruruz ve defteri yanlislikla
                // siliveririz - bu yuzden birkac tick beklenir. Menuyu ne zaman
                // kapatacagimiza biz karar verdigimiz icin beklemek serbest.
                if (isStorageOpen()) clock.start(randomizer());
                if ((isStorageOpen()) && clock.shouldFire()) {
                    if (storageOpenTicks < 3) return;

                    // ARTIK SAYMIYOR, DOGRULUYOR.
                    //
                    // Eski hali her acilista depoyu bastan sayip sayaclara
                    // ekliyordu. Artik defter diskten geliyor ve bu tur onun
                    // gercekle uyusup uyusmadigini kontrol ediyor: acik sayfanin
                    // kaydi silinip ekranda GERCEKTEN ne varsa yeniden yaziliyor.
                    // Boylece defter gercege hizalanir, ustune eklenmez.
                    BookLedger.Place page = currentStoragePage;

                    for (Book book : booksInState(BookState.SELECTED)) {
                        int found = resyncStoragePage(book, page);
                        debug("[STARTUP] " + book.name() + " " + page + ": "
                                + (found < 0 ? "sayfa bos gorundu, kayitlar korundu" : found + " kitap dogrulandi"));

                        if (!secondPageCheck) {
                            int inv = resyncInventory(book);
                            debug("[STARTUP] " + book.name() + " envanter: " + inv + " kitap dogrulandi");
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
                        // Para yok, gorev de yok: acik satis emirlerini guncel
                        // fiyattan yeniden listele ki dolsunlar. Eskiden bunu
                        // REPLACE_SELL yapiyordu ama o state hedefi kaybedince
                        // listedeki ILK emri iptal ediyordu - elle acilmis
                        // alakasiz bir emri bile. Artik dogrulanmis RELIST akisi
                        // kullaniliyor ve her emir adiyla hedefleniyor.
                        List<Book> watched = bazaarMonitor.sellBooks();
                        if (watched.isEmpty()) {
                            debug("yeniden listelenecek satis emri yok");
                            notEnoughCash = false;
                            return;
                        }
                        for (Book book : watched) queueRelist(book);
                        if (!relistQueue.isEmpty()) {
                            ActionLog.add(ActionLog.Tag.SELL,
                                    "not enough coins - relisting " + relistQueue.size() + " sell order(s)");
                            state = State.RELIST;
                        }
                    }
                    return;
                }

                // ONLY SELL: alim tarafi tamamen bittiyse ve satis emrimiz
                // outbid yendiyse, acik satislari guncel fiyattan yeniden
                // listelemek icin RELIST'e gec.
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
                // SATIS NOBETI: tum hatlar bittiyse (SELL_ONLY) ve yenilenmesi
                // gereken bir satis emri varsa RELIST devreye girer.
                if (OnlySellMode.sellOutbidActive() && !relistQueue.isEmpty()) {
                                state = State.RELIST;
                    return;
                }

                Book outbidBook = firstBookInState(BookState.OUTBID);
                if (outbidBook != null && !isInventoryFull) {
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
                    if (isCompleted(selectedBook)) {
                        debug(selectedBook.getRomanLevel(selectedBook.level())
                                + " icin siparise gerek yok (eksik=" + missingUnits(selectedBook)
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
                    storeFullPages.clear();
                    storedThisVisit = 0;
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
                        if (hasStorage(book)) {
                            shouldCheck = true;
                            continue;
                        }

                        ActionLog.add(ActionLog.Tag.ANVIL, book.name() + ": entered anvil with "
                                + heldUnits(book) + " units");
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
                    // SIPARIS ARTIK GERCEKTEN ACILDI - kaydi simdi tut. Tabelada
                    // tutsaydik, arada takilan bir tur "acilmamis siparisi acilmis"
                    // sayar ve hat bos elle zincire girerdi.
                    Task placed = task.get(activeBook);
                    if (placed != null) placed.onOrder = lastOrderAmount;
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
                            // ESYALAR GELDI. Eskiden tooltip'te yazan sayi koru
                            // korune deftere ekleniyordu ("5 yaziyordu, demek ki
                            // 5 geldi"). Artik envanter TARANIYOR: kitaplar
                            // gercekten nerede, oradan yaziliyor.
                            Task ct = task.get(bookToHandle);
                            int added = registerClaimed(bookToHandle,
                                    ct == null ? targetUnits(bookToHandle) : Math.max(1, ct.onOrder));
                            if (ct != null) ct.onOrder = Math.max(0, ct.onOrder - added);
                            outbidClaimedAmount += added;

                            if (added == 0) {
                                ledgerWarn(bookToHandle, "claim sonrasi envanterde yeni kitap bulunamadi");
                            }

                            claimedItems = false;
                            didReceiveItems = false;
                            return;
                        }
                        return;
                    }


                    List<Integer> slots = inventoryScanner.findContainer("BUY " + bookToHandle.getRomanLevel(bookToHandle.level()));
                    debug("found " + slots.size() + " slots for " + bookToHandle);

                    if (slots.isEmpty()) {
                        if (!isCompleted(bookToHandle) && !didRemoveOrder && counterBazaar < 8) {
                            counterBazaar++;
                            return;
                        }

                        // Sipariş ortada yok (doldu ya da iptal edildi): izlemeyi bırak.
                        // Eskiden monitor kaydı kalıyordu ve saatler sonra sahte bir
                        // outbid uyarısı gönderip görevi birleştirme zincirinin
                        // ortasında OUTBID'e atıyordu - havuz bölünüp öksüz doğuyordu.
                        bazaarMonitor.finish(bookToHandle);

                        // Siparis ekranda yok: bazaar'da bekleyen bir sey kalmadi.
                        Task gone = task.get(bookToHandle);
                        if (gone != null) gone.onOrder = 0;

                        int remainingOrder = missingUnits(bookToHandle);
                        ActionLog.add(ActionLog.Tag.OUTBID, bookToHandle.getRomanLevel(bookToHandle.level())
                                + ": " + outbidClaimedAmount + " claimed, "
                                + (remainingOrder == 0 ? "line complete" : remainingOrder + " being re-ordered"));
                        outbidClaimedAmount = 0;

                        editStateBook(bookToHandle, isCompleted(bookToHandle) ? BookState.ANVIL : BookState.SELECTED);
                        didRemoveOrder = false;
                        counterBazaar = 0;
                        return;

                    }


                    if (!slots.isEmpty()) {
                        int amount = inventoryScanner.checkOrder(slots.getFirst());
                        debug("order amount=" + amount + ", clicking slot " + slots.getFirst());
                        if (amount > inventoryScanner.getEmptyInventorySlots()) {
                            task.get(bookToHandle).setEarlyAction(true);
                            editStateBook(bookToHandle, BookState.STORE);
                            state = State.STORE;
                            isInventoryFull = true;
                            storeFullPages.clear();
                            minecraft.player.closeContainer();
                            return;
                        }
                        click(slots.getFirst(), false);
                        if (amount == 0) {
                            debug("amount=0, returning early");
                            return;
                        }

                        // Sayi burada deftere YAZILMAZ. Esyalar geldigini
                        // bildiren "Claimed" mesaji dusunce envanter taranip
                        // gercekten ne geldiyse o yazilir (yukaridaki blok).
                        claimedItems = true;
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
                    BookLedger.Place page = currentStoragePage;
                    String baseName = bookToHandle.getRomanLevel(bookToHandle.level());

                    // FAZ 2: bir onceki tikta bir kitap attik; simdi NEREYE
                    // dustugunu bulup deftere yaziyoruz. Onceden sandikta olmayan
                    // ve baska hatta ait olmayan slot bizimkidir.
                    if (pendingStoreAddress >= 0) {
                        boolean landed = false;
                        for (int slot : inventoryScanner.findLoreContainer(baseName)) {
                            if (storeSnapshot.contains(slot)) continue;
                            if (BookLedger.ownedByOther(bookToHandle, page, slot)) continue;
                            BookLedger.add(bookToHandle, page, slot, bookToHandle.level());
                            BookLedger.remove(bookToHandle, BookLedger.Place.INVENTORY, pendingStoreAddress);
                            debug("stored -> " + page + " slot " + slot);
                            landed = true;
                            break;
                        }
                        if (!landed) {
                            // Tiklama dusmus olabilir. Envanterdeki adres hala
                            // duruyorsa kayit da duruyor, bir sonraki turda
                            // yeniden denenir; durmuyorsa kitap kayip demektir.
                            if (!inventoryScanner.inventorySlotHas(pendingStoreAddress, baseName)) {
                                BookLedger.remove(bookToHandle, BookLedger.Place.INVENTORY, pendingStoreAddress);
                                ledgerWarn(bookToHandle, "depoya atilan kitap ne envanterde ne sandikta bulundu");
                            }
                        }
                        pendingStoreAddress = -1;
                        storeSnapshot.clear();
                        return;
                    }

                    // FAZ 1: defterdeki kendi TABAN SEVIYE kitaplarindan birini at.
                    // Ara seviye kitaplar depolanmaz - onlar cekic isi.
                    BookLedger.Holding next = null;
                    for (BookLedger.Holding h : BookLedger.of(bookToHandle, BookLedger.Place.INVENTORY)) {
                        if (h.level() != bookToHandle.level()) continue;
                        next = h;
                        break;
                    }

                    if (next != null) {
                        if (inventoryScanner.getEmptyContainerSlots() == 0) {
                            // BU sayfanin dolu oldugunu GORDUK - kanit defterine yaz.
                            storeFullPages.add(page);

                            BookLedger.Place other = page == BookLedger.Place.STORAGE_1
                                    ? BookLedger.Place.STORAGE_2
                                    : BookLedger.Place.STORAGE_1;

                            if (!storeFullPages.contains(other)) {
                                // Diger sayfayi HENUZ dolu gormedik. Bos olabilir -
                                // bakmadan pes etmek kullanicinin bildirdigi hataydi:
                                // depo 2 bombosken depo 1 acilip kapaniyordu.
                                useSecondPage = other == BookLedger.Place.STORAGE_2;
                                debug("bu sayfa (" + page + ") dolu, " + other + " deneniyor");
                                minecraft.player.closeContainer();
                                return;
                            }

                            // IKI SAYFAYI DA DOLU GORDUK.
                            if (inventoryScanner.getEmptyInventorySlots() == 0) {
                                // Envanter de dolu: makronun yapabilecegi hicbir sey
                                // yok. Devam etmek OUTBID <-> STORE arasinda sonsuz
                                // bir tur demek - saniyede birkac kez /ec komutu.
                                ChatUtils.clientMessage("Depo VE envanter dolu - makro durduruldu. "
                                        + "Yer acip yeniden baslat.");
                                ActionLog.add(ActionLog.Tag.SYSTEM,
                                        "storage and inventory both full - macro stopped");
                                minecraft.player.closeContainer();
                                FeatureManager.INSTANCE.stop();
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

                        int clickId = inventoryScanner.inventoryClickId(next.slot());
                        if (clickId < 0 || !inventoryScanner.inventorySlotHas(next.slot(), baseName)) {
                            // Defter "burada bir kitap var" diyor ama yok.
                            // Kaydi sil, alarmi calistir, dongude kalma.
                            BookLedger.remove(bookToHandle, BookLedger.Place.INVENTORY, next.slot());
                            ledgerWarn(bookToHandle, "envanter " + next.slot() + " bos cikti, kayit silindi");
                            return;
                        }

                        // Tiklamadan ONCE sandigin halini not al ki kitabin
                        // hangi slota dustugunu bir sonraki tikta bulabilelim.
                        storeSnapshot.clear();
                        storeSnapshot.addAll(inventoryScanner.findLoreContainer(baseName));
                        pendingStoreAddress = next.slot();

                        click(clickId, true);
                        debug("storing " + bookToHandle.name() + " from inv address " + next.slot());
                        // Bu sayfaya kitap sigdi: "dolu" kanidi artik gecersiz.
                        storeFullPages.clear();
                        storedThisVisit++;
                        return;
                    }

                    // Kendi payımız bitti (ya da envanterde bu kitaptan kalmadı).
                    if (storedThisVisit > 0) {
                        ActionLog.add(ActionLog.Tag.STORE, bookToHandle.getRomanLevel(bookToHandle.level())
                                + ": " + storedThisVisit + " moved to storage, "
                                + onOrderOf(bookToHandle) + " still on buy order");
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

                Task currentTask = task.get(bookToHandle);
                boolean wantSecondPage = currentTask.pullFrom == BookLedger.Place.STORAGE_2;

                if (!isStorageOpen()) clock.start(randomizer());
                if (!isStorageOpen() && clock.shouldFire()) {
                    debug("no ender chest, opening " + currentTask.pullFrom);
                    openEnderChest(wantSecondPage);
                    return;
                }

                if (isStorageOpen()) clock.start(speedMode());
                if (isStorageOpen() && clock.shouldFire()) {
                    // Acik olan sayfayi kullan, istedigimizi degil: /ec komutu
                    // beklenenden farkli bir sayfa acmis olabilir.
                    BookLedger.Place page = currentStoragePage;

                    // DEFTERE GORE CEK: hangi sayfada, hangi slotta oldugunu
                    // biliyoruz. Once o slotta gercekten beklenen kitap var mi
                    // diye DOGRULARIZ - defter yaniliyorsa sessizce yanlis slota
                    // tiklamak yerine kaydi duzeltiriz.
                    List<BookLedger.Holding> here = BookLedger.of(bookToHandle, page);

                    if (!here.isEmpty() && inventoryScanner.getEmptyInventorySlots() <= 0) {
                        // Envanterde yer yok. STORE fazi yer acar; Only Sell'de
                        // STORE olmadigi icin hicbir sey acmaz ve
                        // ANVIL -> COMBINE -> SELL -> FETCHING -> IDLE -> ANVIL
                        // turu sonsuza kadar doner. Watchdog goremez, cunku state
                        // her tick degisiyor. Uc denemede hat birakilir.
                        // SAYAC HER MODDA CALISIR. Eskiden yalnizca Only Sell'de
                        // vardi; normal modda cikis yolu olmayan bir dongu kaliyordu:
                        // ANVIL -> COMBINE -> SELL -> FETCHING -> IDLE -> ANVIL,
                        // her turda bir /ec komutu. State her tick degistigi icin
                        // watchdog da goremiyordu.
                        {
                            currentTask.anvilFullBounces++;
                            if (currentTask.anvilFullBounces >= 3) {
                                ChatUtils.clientMessage(bookToHandle.name()
                                        + ": envanter dolu, bu hat birakildi. Yer acip makroyu yeniden baslat.");
                                ActionLog.add(ActionLog.Tag.ANVIL, bookToHandle.name()
                                        + ": inventory full, line paused - free some slots and restart");
                                // Kitaplar hala sandikta: defter KALIR, yoksa
                                // yeniden baslatinca sahipleri kaybolur.
                                task.remove(bookToHandle);
                                state = State.IDLE;
                                minecraft.player.closeContainer();
                                return;
                            }
                        }
                        state = State.COMBINE;
                        minecraft.player.closeContainer();
                        return;
                    }
                    currentTask.anvilFullBounces = 0;

                    for (BookLedger.Holding h : here) {
                        String loreName = bookToHandle.getRomanLevel(h.level());

                        if (!inventoryScanner.containerSlotHas(h.slot(), loreName)) {
                            // Defter yaniliyor. Kaydi sil ve ALARMI CALDIR -
                            // oksuz kitabin izini ancak boyle surebiliriz.
                            BookLedger.remove(bookToHandle, page, h.slot());
                            ledgerWarn(bookToHandle, page + " slot " + h.slot()
                                    + " beklenen " + loreName + " degil, kayit silindi");
                            return;
                        }

                        debug("pulling " + loreName + " from " + page + " slot " + h.slot());
                        click(h.slot(), true);
                        // Kitap artik envantere gecti; adresini bir sonraki
                        // taramada ogrenecegiz, simdilik depo kaydini dusur.
                        BookLedger.remove(bookToHandle, page, h.slot());
                        currentTask.anvilAdoptions = 0;
                        currentTask.setAnvilRecheckAttempted(false);
                        return;
                    }

                    // Bu sayfada bize ait kayit kalmadi. Sayfayi bir kez daha
                    // tarayip SAHIPSIZ kitap var mi diye bakariz - baska bir
                    // seansta ya da elle birakilmis kitaplar burada sahiplenilir.
                    // Sahipsiz kitap avi - ama SINIRLI. Tikladigimiz kitabi
                    // sunucu geri cevirirse kayit silinir, kitap sandikta kalir,
                    // burada yeniden sahiplenilir ve sonsuz tikla-sil-sahiplen
                    // dongusu olusur. Iki denemeden sonra bu sayfayla isimiz biter.
                    int adopted = currentTask.anvilAdoptions >= 2 ? 0
                            : adoptUnownedHere(bookToHandle, page);
                    if (adopted > 0) {
                        currentTask.anvilAdoptions++;
                        ledgerWarn(bookToHandle, page + " sayfasinda " + adopted + " sahipsiz kitap bulundu");
                        return;
                    }

                    // Diger sayfaya bir kez bak.
                    if (!currentTask.bothPagesChecked) {
                        currentTask.bothPagesChecked = true;
                        currentTask.pullFrom = wantSecondPage
                                ? BookLedger.Place.STORAGE_1
                                : BookLedger.Place.STORAGE_2;
                        debug("bu sayfada is bitti, diger sayfaya bakiliyor: " + currentTask.pullFrom);
                        minecraft.player.closeContainer();
                        return;
                    }

                    currentTask.bothPagesChecked = false;
                    currentTask.anvilAdoptions = 0;
                    currentTask.pullFrom = BookLedger.Place.STORAGE_1;

                    ActionLog.add(ActionLog.Tag.ANVIL, bookToHandle.name() + ": entered anvil with "
                            + heldUnits(bookToHandle) + " units");
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
                        // "Birleştirilecek çift yok" != "kitap satış seviyesinde hazır".
                        // Önce gerçekten envanterde satış seviyesinde kitap var mı bak.
                        if (!inventoryScanner.locate(bookToHandle.getRomanLevel(bookToHandle.sellLevel())).isEmpty()) {
                            debug("no pair to combine, sell-level copy confirmed in inventory, switching to SELL");
                            // ZINCIR KILIDINI BIRAKMADAN ONCE artiklari deftere yaz.
                            // Elde eslesmemis bir Wisdom 4 kaldiysa ADRESI VE SAHIBI
                            // olsun ki kardes hat onu kendi kitabi sanip almasin ve
                            // biz eksigimizi tam olarak hesaplayabilelim.
                            registerCombineLeftovers(bookToHandle);
                            editStateBook(bookToHandle, BookState.SELL);
                        } else if (hasStorage(bookToHandle) && !task.get(bookToHandle).isAnvilRecheckAttempted()) {
                            // Depoyu SADECE BİR KEZ yeniden kontrol et. Sınırsız
                            // denemek sonsuz ANVIL <-> COMBINE döngüsü demek: sayaç
                            // "depoda kitap var" derken depo boşsa bot iki state
                            // arasında saatlerce gidip geliyordu.
                            task.get(bookToHandle).setAnvilRecheckAttempted(true);
                            task.get(bookToHandle).bothPagesChecked = false;
                            debug("no pair to combine AND no sell-level copy found for " + bookToHandle.name() + ", sending back to ANVIL to recheck ender chest (tek seferlik)");
                            editStateBook(bookToHandle, BookState.ANVIL);
                        } else {
                            // Buraya normalde HİÇ düşülmemeli: havuz her zaman 2'nin
                            // kuvveti olacak şekilde sipariş ediliyor ve zincir kilidi
                            // sayesinde ortasından mal çekilmiyor. Yine de düşüldüyse
                            // fiziksel bir kayıp var demektir (relog, manuel müdahale,
                            // envanter dolduğu için yarım kalan zincir...). Sonsuz
                            // döngüye girmemek için görevi bırakıyoruz; kalan parçalar
                            // makro yeniden başlatıldığında STARTUP_CHECK tarafından
                            // birim olarak sayılıp sipariş miktarından düşülecek.
                            // ESKIDEN HAT BURADA COPE ATILIYORDU. Artik atilmiyor:
                            // elde ne kaldiysa deftere yazilir, eksik birim TAM
                            // olarak hesaplanir ve o kadar tamamlama siparisi acilir.
                            // Oksuz parca boylece bir cikmaz sokak degil, sadece bir
                            // ara durum olur - hat onu yiyip bitirir.
                            registerCombineLeftovers(bookToHandle);
                            int missing = missingUnits(bookToHandle);

                            if (missing <= 0 && heldUnits(bookToHandle) <= 0) {
                                // ELDE HICBIR SEY YOKSA kapat. Elde kitap varken
                                // asla kapatma: kaydi silmek o kitaplari sahipsiz
                                // birakir - yani tam da onlemeye calistigimiz sey.
                                ActionLog.add(ActionLog.Tag.COMBINE, bookToHandle.name()
                                        + ": nothing left to combine, line closed");
                                TradeHistory.abandon(bookToHandle);
                                dropLine(bookToHandle);
                                return;
                            }

                            if (missing <= 0) {
                                // Birim olarak tam ama cekic ciftleyemedi. Normalde
                                // olmamali (2'nin kuvvetleri her zaman ciftlenir).
                                // Sessizce dusurmek yerine bir kez daha depoya bak.
                                ledgerWarn(bookToHandle, "havuz tam ama ciftlenemedi - depo yeniden taraniyor");
                                task.get(bookToHandle).setAnvilRecheckAttempted(false);
                                task.get(bookToHandle).bothPagesChecked = false;
                                editStateBook(bookToHandle, BookState.ANVIL);
                                return;
                            }

                            if (OnlySellMode.blocksNewOrders()) {
                                // ONLY SELL'DE ALIM YOK. Bu dal SELECTED'a
                                // donduruyor, IDLE de SELECTED'i siparise
                                // yolluyor - yani "hicbir sey alma" modu gercek
                                // parayla alim yapardi. Kitaplar defterde kaliyor,
                                // mod kapatilinca hat kaldigi yerden devam eder.
                                ChatUtils.clientMessage(bookToHandle.name() + " icin havuz eksik ("
                                        + missing + " birim) - Only Sell acik, alim yapilmiyor.");
                                ActionLog.add(ActionLog.Tag.COMBINE, bookToHandle.name()
                                        + ": pool short by " + missing + ", only sell - line parked");
                                task.remove(bookToHandle);
                                return;
                            }

                            ChatUtils.clientMessage(bookToHandle.name() + " icin havuz eksik: "
                                    + missing + " birim tamamlama siparisi aciliyor.");
                            ActionLog.add(ActionLog.Tag.COMBINE, bookToHandle.name()
                                    + ": pool short by " + missing + ", topping up");
                            ledgerWarn(bookToHandle, "cekic bitti ama havuz eksik (" + missing + " birim)");
                            task.get(bookToHandle).setAnvilRecheckAttempted(false);
                            editStateBook(bookToHandle, BookState.SELECTED);
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
                    // Only Sell'de onOrder her zaman 0'dir, yani hat asla yeni
                    // siparis acamaz. Satis yapildiktan sonra hatti yasatmanin
                    // anlami yok: elde kalan varsa defterde duruyor ve makroyu
                    // yeniden baslatinca STARTUP_CHECK onu bulup yeni tur baslatir.
                    if (soldTask != null && OnlySellMode.isEnabled()) {
                        // Satilanlar elimizden cikti; elde HALA kitap var mi bak.
                        // Bakmadan kapatirsak (32'lik iki setlik havuzda oldugu
                        // gibi) kalan kitaplar sahipsiz kalirdi.
                        // KARDES HATLARI ONCE HALLET. Eskiden soldBook'un elinde
                        // kitap kalinca erken donuluyordu ve ayni satis emrini
                        // paylasan kardes hat (2to5) SELL durumunda ASILI
                        // kaliyordu: elinde satilacak kitap yokken SELL state'i
                        // onu tekrar tekrar islemeye calisiyor, watchdog makroyu
                        // durduruyordu. Ustelik gorev listesi hic bosalmadigi icin
                        // SELL_ONLY fazina da asla gecilmiyor, yani satis nobeti
                        // hic devreye girmiyordu.
                        for (Book sameName : booksInState(BookState.SELL)) {
                            if (!sameName.name().equals(soldBook.name())) continue;
                            if (sameName.equals(soldBook)) continue;
                            registerCombineLeftovers(sameName);
                            if (heldUnits(sameName) > 0) {
                                editStateBook(sameName, BookState.ANVIL);
                            } else {
                                dropLine(sameName);
                            }
                        }

                        registerCombineLeftovers(soldBook);
                        if (heldUnits(soldBook) > 0) {
                            debug("only sell: " + soldBook.name() + " elde " + heldUnits(soldBook)
                                    + " birim kaldi, zincire devam");
                            editStateBook(soldBook, BookState.ANVIL);
                            return;
                        }

                        ActionLog.add(ActionLog.Tag.SELL, soldBook.name()
                                + ": only sell line finished - not reopening");

                        // AYNI ISIMDEKI TUM SELL GOREVLERI birlikte kapanir.
                        // removeDuplicateBooks'a guvenemeyiz: o metot
                        // getAmountToOrder() < 0 olan gorevleri saymiyor, Only
                        // Sell gorevlerinde ise amountToOrder 0 oldugu icin stok
                        // tutan her gorev zaten < 0'dir - yani hicbiri sayilmaz ve
                        // hicbiri silinmez. Kardes hat (or. 2to5) SELL'de asili
                        // kalir, elinde kitap yokken kendi satis emrini iptal edip
                        // yeniden acar ya da "havuz eksik kaldi" diye dusulur.
                        dropLine(soldBook);
                        return;
                    }

                    // Satis emri acildi: satilan kitaplar artik elimizde degil.
                    // Defterden dus, sonra elde HALA birim kaldiysa (32 kitaplik
                    // iki setlik havuz gibi) hat yasamaya devam etsin.
                    registerCombineLeftovers(soldBook);
                    if (soldTask != null && heldUnits(soldBook) > 0) {
                        editStateBook(soldBook, BookState.SELECTED);
                        // Gorev yasamaya devam ediyor: yeni bir olcum turu baslasin.
                        TradeHistory.begin(soldBook);
                        return;
                    }

                    // Aynı isimden birden fazla görev aynı anda satış seviyesine
                    // ulaştıysa (1to5 + 2to5 havuzu birlikte birleştiği için normal),
                    // tek satış emri hepsini kapsar; o görevleri toplu kaldır.
                    removeDuplicateBooks(task);
                    dropLine(soldBook);
                    bookList.removeFirst();

                }
            }

            /*
             * Acilista BIR KEZ calisir: bazaar > Manage Orders ekranini acar,
             * oradaki SELL satirlarini okur ve her birini BazaarMonitor'e satis
             * emri olarak kaydeder. RELIST ile birebir ayni navigasyon
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

            /*
             * SATIS EMRI YENILEME.
             *
             * Bir satis emri outbid yendiginde: emri bul, iptal et (kitaplar
             * envantere doner), urun sayfasini ac, guncel fiyattan yeniden
             * listele, yeni fiyati izlemeye al.
             *
             * TEK EMRE DOKUNUR. Silinen REPLACE_SELL'deki hata buydu: iptalden sonra
             * siparis ekranina donunce listede BASKA satis emirleri de goruluyor,
             * "liste bos degil" diye onlari da tek tek iptal ediyordu - yedi
             * hattin yedi emri de iptal edilip yalnizca biri yeniden aciliyordu.
             * Burada hedef ADIYLA sabitlenmis durumda ve iptalden sonraki adim
             * ekrana degil, adim sayacina bakiyor.
             */
            case RELIST -> {
                // Sirada kitap yoksa kuyruktan al.
                if (relistBook == null) {
                    Book next = relistQueue.poll();
                    if (next == null) {
                        endRelist("kuyruk bos");
                        return;
                    }
                    // BAYAT GIRDI: outbid uyarisi FINISHING fazinda gelip
                    // saatlerce beklemis olabilir. O kadar eski bir uyariya gore
                    // saglikli bir emri iptal etmenin anlami yok - emir hala
                    // outbid'se monitor 15 saniyede bir yeniden haber verir.
                    Long queuedAt = relistQueuedMs.remove(next.getRomanLevel(next.sellLevel()));
                    if (queuedAt != null && System.currentTimeMillis() - queuedAt > RELIST_STALE_MS) {
                        debug("[RELIST] bayat kuyruk girdisi atlandi: " + next.name());
                        return;
                    }
                    relistBook = next;
                    relistName = next.getRomanLevel(next.sellLevel());
                    relistStep = Relist.OPEN_ORDERS;
                    relistWaits = 0;
                    debug("[RELIST] " + relistName + " icin baslaniyor");
                }

                // Herhangi bir adimda cok uzun beklediysek birak - yarim kalmis
                // bir iptal en kotu senaryo, o yuzden asla sessizce donmuyoruz.
                if (relistWaits++ > RELIST_MAX_WAITS) {
                    // IPTAL ETTIKTEN SONRA PES ETMEK KITAPLARI OLDURUR.
                    //
                    // Emri iptal ettiysek kitaplar envanterde duruyor ve satista
                    // degiller. Burada vazgecersek onlari bir daha kimse aramaz:
                    // izleme birakilmis, gorev listesi bos, ve defter taramalari
                    // sellLevel'i hic gezmiyor. Bu yuzden iptalden sonra HER ZAMAN
                    // listeleme adimindan yeniden denenir.
                    relistRetries++;
                    if (relistRetries <= RELIST_MAX_RETRIES) {
                        debug("[RELIST] adim " + relistStep + " takildi, yeniden deneniyor ("
                                + relistRetries + "/" + RELIST_MAX_RETRIES + ")");
                        relistStep = Relist.OPEN_ORDERS;
                        relistWaits = 0;
                        if (isContainerOpen()) minecraft.player.closeContainer();
                        return;
                    }

                    if (relistCancelled) {
                        // Denemeler bitti ama kitaplar hala elde. Kuyrukta birak
                        // ki soguma suresi sonrasi tekrar denensin - sessizce
                        // kaybolmasindansa gec listelensin.
                        ChatUtils.clientMessage(relistName + " yeniden listelenemedi - kitaplar envanterde, "
                                + "daha sonra yeniden denenecek.");
                        ActionLog.add(ActionLog.Tag.SELL, relistName
                                + ": relist failed, books held in inventory - will retry");
                        Book retry = relistBook;
                        finishRelistBook();
                        lastRelistMs.put(relistName == null ? "" : relistName, System.currentTimeMillis());
                        relistQueue.add(retry);
                        return;
                    }

                    ledgerWarn(relistBook, "satis emri yenilenemedi (adim: " + relistStep + ")");
                    ChatUtils.clientMessage(relistName + " satis emri yenilenemedi - elle kontrol et.");
                    finishRelistBook();
                    return;
                }

                switch (relistStep) {

                    case OPEN_ORDERS -> {
                        // Beklenmedik bir ekran acik olabilir - ozellikle bir
                        // onceki kitabin onayindan sonra urun sayfasinda kalmis
                        // oluruz. Hicbir kosula uymayan ekranda beklersek adim
                        // zaman asimina kadar takilirdik; kapatip bastan basla.
                        if (isContainerOpen()
                                && !containerCheck("tomato")
                                && !containerCheck("Bazaar")) {
                            clock.start(randomizer());
                            if (clock.shouldFire()) {
                                debug("[RELIST] beklenmedik ekran, kapatiliyor");
                                minecraft.player.closeContainer();
                            }
                            return;
                        }

                        if (!isContainerOpen()) clock.start(randomizer());
                        if (!isContainerOpen() && clock.shouldFire()) {
                            debug("[RELIST] bazaar aciliyor");
                            openBazaar("tomato");
                            return;
                        }
                        if (containerCheck("tomato")) clock.start(randomizer());
                        if (containerCheck("tomato") && clock.shouldFire()) {
                            debug("[RELIST] urun ekrani acik, slot 50 (Manage Orders)");
                            click(50, false);
                            return;
                        }
                        // Siparis ekrani: basligi "Bazaar" iceriyor ama urun
                        // ekrani DEGIL. Iki kosul birden aranmazsa urun ekranini
                        // siparis ekrani sanardik.
                        if (containerCheck("Bazaar") && !containerCheck("tomato")) {
                            // Emri zaten iptal ettiysek aramaya gerek yok - emir
                            // artik yok. Dogrudan kitabi listeleme adimina gec.
                            relistStep = relistCancelled ? Relist.OPEN_PRODUCT : Relist.FIND_ORDER;
                            relistWaits = 0;
                            relistFindTries = 0;
                        }
                    }

                    case FIND_ORDER -> {
                        if (!(containerCheck("Bazaar") && !containerCheck("tomato"))) return;
                        clock.start(randomizer());
                        if (!clock.shouldFire()) return;

                        int target = findSellOrderSlot(relistName);
                        if (target < 0) {
                            // BOS EKRANDAN SONUC CIKARMA. Ekranin basligi
                            // geldiginde icerigi henuz gelmemis olabiliyor;
                            // hemen "emir gitmis" dersek gercekten outbid yenmis
                            // bir emri sessizce birakiriz. Birkac kez denenir.
                            if (relistFindTries++ < 3) {
                                debug("[RELIST] emir henuz gorunmedi, tekrar bakiliyor ("
                                        + relistFindTries + "/3)");
                                return;
                            }
                            debug("[RELIST] " + relistName + " icin acik emir yok, atlaniyor");
                            ActionLog.add(ActionLog.Tag.SELL, relistName + ": open order gone, nothing to relist");
                            bazaarMonitor.finishSell(relistBook);
                            finishRelistBook();
                            return;
                        }

                        // Iptal edince kitaplar envantere doner - yer yoksa
                        // kaybolabilirler. Once yer oldugundan emin ol.
                        // Yer kontrolu EMIR BUYUKLUGUNE gore. Tek bos slot yeter
                        // demek, 16 kitaplik bir emri iptal edip yarisini
                        // kaybetmek demek olabilir.
                        int needed = sellOrderSize(target);
                        if (inventoryScanner.getEmptyInventorySlots() < needed) {
                            ChatUtils.clientMessage("Envanter dolu (" + needed + " slot lazim) - "
                                    + relistName + " satis emri yenilenemiyor. Yer ac.");
                            ActionLog.add(ActionLog.Tag.SELL, relistName + ": needs " + needed
                                    + " free slots, relist skipped");
                            finishRelistBook();
                            return;
                        }

                        // IPTAL ONCESI envanterde bu kitaptan kac tane var?
                        // OPEN_PRODUCT bu sayinin ARTMASINI bekleyecek. Yoksa
                        // baska bir sebeple elde duran ayni kitaba, iptal daha
                        // tamamlanmadan tiklardik.
                        relistInvBefore = inventoryScanner.findLoreInvAddressed(relistName).size();

                        debug("[RELIST] emir bulundu, slot " + target
                                + " tiklaniyor (envanterde simdi " + relistInvBefore + " tane)");
                        click(target, false);
                        relistStep = Relist.CANCEL;
                        relistWaits = 0;
                    }

                    case CANCEL -> {
                        List<Integer> cancel = inventoryScanner.findContainer("Cancel Order");
                        if (cancel.isEmpty()) return;   // emir detayi henuz acilmadi
                        clock.start(randomizer());
                        if (!clock.shouldFire()) return;

                        debug("[RELIST] Cancel Order tiklaniyor");
                        click(cancel.getFirst(), false);
                        // Bu andan itibaren kitaplar satista DEGIL. Yenileme
                        // tamamlanana kadar hicbir yol pes edemez.
                        relistCancelled = true;
                        relistStep = Relist.OPEN_PRODUCT;
                        relistWaits = 0;
                    }

                    case OPEN_PRODUCT -> {
                        // EKRAN GUARDI SART: bu adim envanterdeki kitaba tikliyor.
                        // Bazaar ekrani kapaliyken (ornegin failsafe hub'a isinip
                        // ekrani kapattiysa) ayni tiklama kitabi imlece ALIR,
                        // urun sayfasini acmaz - kitap yere dusebilir.
                        if (!(containerCheck("Bazaar") && !containerCheck("tomato"))) return;

                        // Iptal edildi, kitaplar envanterde. Urun sayfasini acmak
                        // icin envanterdeki kitaba tiklanir.
                        List<int[]> hits = inventoryScanner.findLoreInvAddressed(relistName);
                        // Sadece "var mi" degil, "ARTTI mi" - iptal edilen
                        // kitaplarin gercekten geldigini boyle biliyoruz.
                        if (hits.size() <= relistInvBefore) return;
                        clock.start(randomizer());
                        if (!clock.shouldFire()) return;

                        debug("[RELIST] kitap envanterde, urun sayfasi aciliyor");
                        click(hits.getFirst()[1], false);
                        relistStep = Relist.CREATE_OFFER;
                        relistWaits = 0;
                    }

                    case CREATE_OFFER -> {
                        if (!containerCheck(relistBook.name())) return;
                        clock.start(randomizer());
                        if (!clock.shouldFire()) return;

                        debug("[RELIST] urun sayfasi acik, slot 16 (Sell Offer)");
                        click(16, false);
                        relistStep = Relist.SET_PRICE;
                        relistWaits = 0;
                    }

                    case SET_PRICE -> {
                        if (!containerCheck("At what price are you selling")) return;
                        clock.start(randomizer());
                        if (!clock.shouldFire()) return;

                        // Fiyati TIKLAMADAN ONCE oku: outbid tespiti bu fiyata gore.
                        pendingSellPrice = inventoryScanner.getUnitPrice(12);
                        debug("[RELIST] fiyat okundu: " + pendingSellPrice);
                        click(12, false);
                        relistStep = Relist.CONFIRM;
                        relistWaits = 0;
                    }

                    case CONFIRM -> {
                        if (!containerCheck("Confirm")) return;
                        clock.start(randomizer());
                        if (!clock.shouldFire()) return;

                        debug("[RELIST] onaylaniyor");
                        click(13, false);

                        if (pendingSellPrice > 0) {
                            bazaarMonitor.add(relistBook, pendingSellPrice, true);
                        } else {
                            // Fiyat okunamadi: 0 ile izlersek her turda "fiyat
                            // degismis" sanip sonsuz yenileme dongusune girerdik.
                            bazaarMonitor.finishSell(relistBook);
                            ledgerWarn(relistBook, "yeni satis fiyati okunamadi, izleme birakildi");
                        }
                        pendingSellPrice = 0;

                        ActionLog.add(ActionLog.Tag.SELL, relistName + ": relisted at the current price");
                        ChatUtils.clientMessage(relistName + " satis emri guncel fiyattan yeniden acildi.");
                        lastRelistMs.put(relistName, System.currentTimeMillis());
                        finishRelistBook();
                    }
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
                    useSecondPage = false;
                    storeFullPages.clear();
                    secondPageCheck = false;
                    outbidClaimedAmount = 0;
                    storedThisVisit = 0;
                    sellOrderCancelled = false;
                    // Yarim kalmis bir yenileme varsa bastan baslasin - iptal
                    // edilmis ama yeniden listelenmemis bir emir birakmayalim.
                                if (relistBook != null) {
                        relistStep = Relist.OPEN_ORDERS;
                        relistWaits = 0;
                    }

                    // ACILIS ORTASINDA TOPARLANDIYSAK Only Sell gorevlerini
                    // atariz; FETCHING yeniden tohumlar. Defter SILINMEZ -
                    // kitaplar hala yerinde duruyor, yalnizca gorev nesneleri
                    // sifirdan kuruluyor ki STARTUP_CHECK temiz bir dogrulama
                    // turu yapabilsin.
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
            int owned = heldUnits(book);
            int onOrder = onOrderOf(book);

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
            case RELIST -> "Relisting";
            case RECOVERY -> "Recovering";
        };
    }

    public List<String> getTaskSummary() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            Book book = entry.getKey();
            Task t = entry.getValue();
            lines.add(book.getRomanLevel(book.level()) + ": " + t.getBookState()
                    + " (eksik=" + missingUnits(book) + ")");
        }
        return lines;
    }

    private boolean shouldStore(Book book) {
        return hasStorableInInventory(book);
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
                + " eksik=" + missingUnits(book)
                + " " + BookLedger.summary(book));
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
            // Cekic korumasi: kardes hattin envanterde kitabi varken
            // birlestirmeye girme, yoksa onun kitaplarini yutariz.
            if (target == BookState.COMBINE && siblingHoldsInventory(entry.getKey())) continue;
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

    /** Bu isme ait, şu an envanterde depolanmayı bekleyen kitap var mı? */
    private boolean hasUnstoredBooksForName(String name) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (!entry.getKey().name().equals(name)) continue;
            if (entry.getValue().getBookState() != BookState.STORE) continue;
            if (hasStorableInInventory(entry.getKey())) return true;
        }
        return false;
    }

    /**
     * Bu isimden BASKA bir hattin envanterde kitabi var mi?
     *
     * COMBINE defter kullanmiyor, envanterde ne gorurse cekice atiyor. Kardes
     * hattin (or. 2to5) envanterde bekleyen bir Wisdom III'u varsa, 1to5
     * birlestirmeye girdiginde onu da yutar - kardes hat kitabini kaybeder ve
     * yerine yenisini satin alir. Zincir kilidi bu yuzden yalnizca STORE'a
     * degil, envanterde kitabi olan HER kardes hatta bakar.
     */
    private boolean siblingHoldsInventory(Book line) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            Book other = entry.getKey();
            if (other.equals(line)) continue;
            if (!other.name().equals(line.name())) continue;
            if (!BookLedger.of(other, BookLedger.Place.INVENTORY).isEmpty()) return true;
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
            counts.merge(book.name(), 1, Integer::sum);
        }

        // ESKİ KOD: isim sayısı >1 ise o isimden TÜM görevleri siliyordu - alım
        // fazındaki (kitapları çoktan satın alınmış) kardeş görev de siliniyor ve
        // o kitaplar sahipsiz kalıyordu. Artık sadece SELL'deki görevler silinir.
        List<Book> doomed = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : tasks.entrySet()) {
            if (entry.getValue().getBookState() != BookState.SELL) continue;
            if (counts.getOrDefault(entry.getKey().name(), 0) <= 1) continue;
            doomed.add(entry.getKey());
        }
        // Gorevle birlikte defter kaydi da gitmeli, yoksa kapanmis bir hattin
        // adresleri defterde kalir ve sonraki hat onlari "baskasinin" sanar.
        for (Book book : doomed) dropLine(book);
    }




    /*
     * ARTIK KREDI MAKINESI SILINDI.
     *
     * Eskiden burada leftoverUnitsInInventory / leftoverContainerSlots /
     * creditLeftoverUnitsFromContainer vardi: ara seviye kitaplari sayip
     * "birim kredisi" olarak siparis miktarindan dusuyorlardi. Hepsi tahmin
     * uzerine kuruluydu ve sahiplik bilgisi yoktu.
     *
     * Artik ara seviye kitaplar da defterde, KENDI SEVIYELERIYLE ve ADRESLERIYLE
     * duruyor. Birim degeri BookLedger.units() icinde hesaplaniyor, ayri bir
     * kredi kavramina gerek kalmadi.
     */

    /**
     * ONLY SELL açıkken başlatıldığında config'teki her hat için görev açar.
     *
     * Gorevler SADECE processData'da doguyordu ve Only Sell orayi bastan
     * kesiyordu; sonuc olarak gorev haritasi bos kaliyor, makro depoya bakip
     * oldugu yerde duruyordu. Burada acilan gorevlerin onOrder'i 0'dir ve
     * hicbir zaman artmaz, yani bu hatlar hicbir kosulda yeni siparis acamaz.
     */
    private void seedOnlySellTasks() {
        if (onlySellSeeded || GoofyConfig.INSTANCE == null) return;
        onlySellSeeded = true;

        for (Book book : GoofyConfig.INSTANCE.books) {
            if (!GoofyConfig.isBookEnabled(book)) continue;
            if (task.containsKey(book)) continue;
            task.put(book, new Task());
            debug("only sell: " + book.getRomanLevel(book.level()) + " icin gorev acildi");
        }

        ActionLog.add(ActionLog.Tag.SYSTEM,
                "only sell: checking storage for " + task.size() + " book line(s)");
    }

    /**
     * STARTUP_CHECK iki depo sayfasini da dogruladiktan sonra calisir.
     *
     * Defterde tek bir kitabi bile olmayan hatlar kapatilir; kalanlar cekice
     * yollanir. Only Sell'de yeni siparis acilmadigi icin eksik tamamlanmaz,
     * elde ne varsa o birlestirilip satilir.
     */
    private void finishOnlySellStartup() {
        if (!OnlySellMode.isEnabled()) return;

        for (Book book : new ArrayList<>(task.keySet())) {
            if (heldUnits(book) > 0) continue;
            debug("only sell: " + book.getRomanLevel(book.level()) + " icin elde kitap yok, gorev kapatildi");
            dropLine(book);
        }

        if (task.isEmpty()) {
            ActionLog.add(ActionLog.Tag.SYSTEM, "only sell: no stock left - just watching sell orders");
            return;
        }

        for (Book book : new ArrayList<>(task.keySet())) {
            ActionLog.add(ActionLog.Tag.ANVIL, book.name() + " " + book.getRomanLevel(book.level())
                    + ": " + heldUnits(book) + " units on hand, combining");
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
     * satış emirlerini biliyordu (SELL / RELIST içindeki add çağrıları).
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

            // GOREVI ONCE AC, SONRA ELDEKINI SAY.
            // Defter Book'a gore calisiyor, o yuzden sahiplenme gorev acildiktan
            // sonra yapilir. Zincirdeki bir isim icin eldeki stok sayilmaz:
            // o kitaplar su an birlestirme havuzunda dolasiyor, sahipsiz degil.
            Task newTask = new Task();
            task.put(book, newTask);

            int credit = 0;
            if (!nameInChain) {
                // Envanterde SAHIPSIZ duran (taban ya da ara seviye) kitaplari
                // bu hatta yaz. Boylece 16 lazimken elde 1 tane dururken 15
                // siparis acilir ve havuz tam 2'nin kuvveti kalir.
                adoptUnownedInventory(book);
                credit = heldUnits(book);
            }

            int amount = Math.max(0, fullAmount - credit);

            double unitCost = flipItem.totalCost() / fullAmount;
            double actualCost = unitCost * amount;

            if (amount > 0 && purse < actualCost) {
                // Para yetmiyor: gorevi kapat ama DEFTERI SILME. Depoda duran
                // kitaplarin sahiplik kaydi silinirse bir sonraki turda o kitaplar
                // sahipsiz gorunur ve hat elde 16 tane varken 16 tane daha siparis
                // eder - havuz 32'ye cikip 2'nin kuvveti olmaktan cikardi.
                task.remove(book);
                continue;
            }

            debug("User has enough money " + book.name());
            purse -= actualCost;
            debug("new purse = " + purse);

            TradeHistory.begin(book);
            ActionLog.add(ActionLog.Tag.BUY, book.getRomanLevel(book.level())
                    + " line opened - target " + amount);

            if (credit > 0) {
                ChatUtils.clientMessage(book.name() + " icin elde " + credit
                        + " birim kitap var, siparis " + fullAmount + " yerine " + amount + " adet aciliyor.");
            }

            if (isCompleted(book)) {
                // Siparis gerekmiyor, elde yeterli var: dogrudan zincire gir.
                editStateBook(book, BookState.ANVIL);
            } else if (hasStorableInInventory(book)) {
                // Elde kismi stok var: once onu depola, sonra kalani siparis et.
                editStateBook(book, BookState.STORE);
                newTask.setEarlyStore(true);
            }

            debug("new task created size:" + task.size());
        }

    }


    /**
     * Depo hangi sebeple acilirsa acilsin, o sayfada SAHIPSIZ duran kitaplari
     * eksigi olan hatlara yazar.
     *
     * ESKI HALI sayac tabanliydi ve yalnizca EKLIYORDU, hicbir zaman
     * eksiltmiyordu - sisen sayac kendi kendine duzelmiyordu. Artik adres
     * tabanli: bir slot ya bir hatta kayitlidir ya sahipsizdir, arada gri alan
     * yok. Yine yalnizca ekler, ama eksiltme isi de artik dogrulama turlarinda
     * (resyncStoragePage) yapiliyor.
     */
    private void adoptOnOpenStoragePage() {
        for (Book book : new ArrayList<>(task.keySet())) {
            if (missingUnits(book) <= 0) continue;

            int adopted = adoptUnownedHere(book, currentStoragePage);
            if (adopted <= 0) continue;

            ChatUtils.clientMessage("Depoda " + adopted + "x " + book.name()
                    + " sahipsiz kitap bulundu, hatta yazildi (kalan eksik: "
                    + missingUnits(book) + ").");
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
        // HANGI SAYFAYI ACTIGIMIZI KAYDET. Defter adresleri sayfaya gore
        // anlamli; acik sayfayi tahmin edersek kitaplari yanlis sayfaya
        // yazariz ve ANVIL onlari bir daha asla bulamaz.
        currentStoragePage = placeOf(useSecondPage);
        debug("openEnderChest -> " + currentStoragePage);
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
        int orderAmount = missingUnits(activeBook);
        if (orderAmount <= 0) {
            debug("siparis gerekmiyor (eksik=" + orderAmount + "), tabela iptal ediliyor");
            minecraft.setScreen(null);
            editStateBook(activeBook, BookState.ANVIL);
            state = State.IDLE;
            return;
        }

        // onOrder BURADA YAZILMAZ. Tabela ile onay arasinda makro takilirsa
        // (25 sn zaman asimi -> RECOVERY) siparis hic acilmamis olur ama
        // onOrder dolu kalir: eksik 0 gorunur, hat bos elle zincire girer ve
        // sessizce kapanir. Kayit siparis GERCEKTEN acildiginda, Confirm
        // tiklamasinda tutuluyor.
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
        // takili kalirsa IDLE 60 sn sonra bosuna yeniden listelemeye kacar.
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

        // YENI TANIM: faz artik "alim fazinda gorev var mi" degil, "HIC gorev
        // kaldi mi" sorusuna bakiyor. Kullanicinin istedigi davranis bu: mevcut
        // gorevler sonuna kadar (alim, depolama, birlestirme, satis) normal
        // isliyor; hepsi bitince satis nobeti devreye giriyor.
        boolean anyTaskLeft = !task.isEmpty();
        OnlySellMode.Phase next = anyTaskLeft ? OnlySellMode.Phase.FINISHING : OnlySellMode.Phase.SELL_ONLY;
        if (next != OnlySellMode.phase()) {
            ActionLog.add(ActionLog.Tag.SYSTEM, next == OnlySellMode.Phase.SELL_ONLY
                    ? "only sell: all lines finished - watching sell orders for outbids"
                    : "only sell: finishing the open lines");
            if (next == OnlySellMode.Phase.SELL_ONLY) {
                ChatUtils.clientMessage("Only Sell: tum hatlar bitti. Artik yalnizca satis emirleri izleniyor.");
            }
        }
        OnlySellMode.setPhase(next);
    }

    /**
     * Claim sonrasi envanteri tarar ve YENI kitaplari bu hattin defterine yazar.
     *
     * Zaten herhangi bir hatta kayitli adresler atlanir: kendi eski kitabimizi
     * ikinci kez saymayalim, kardes hattin kitabini da calmayalim.
     *
     * @return deftere yeni yazilan kitap sayisi
     */
    private int registerClaimed(Book book, int max) {
        int added = 0;
        String baseName = book.getRomanLevel(book.level());

        for (int[] hit : inventoryScanner.findLoreInvAddressed(baseName)) {
            // EN FAZLA SIPARIS KADAR. Sinirsiz olsaydi kardes hattin cekicte
            // yeni dovdugu ara seviye kitaplar (henuz defterde degiller) bu
            // hatta yazilirdi: kardes hat kendi kitabini kaybeder, bu hat da
            // gelmemis kitaplari gelmis sanip siparisini eksik gosterirdi.
            if (added >= max) break;
            if (heldUnits(book) >= targetUnits(book)) break;
            int address = hit[0];
            if (BookLedger.isOwned(BookLedger.Place.INVENTORY, address)) continue;
            BookLedger.add(book, BookLedger.Place.INVENTORY, address, book.level());
            added++;
        }
        return added;
    }

    /**
     * ACIK olan "Manage Orders" ekraninda bu ada sahip SATIS emrinin slotu.
     *
     * TAM ESLESME: ad sonundan karsilastirilir. contains kullansaydik
     * "SELL 16x Ultimate Wise VI" adi "Ultimate Wise V" ile eslesir ve
     * yanlis emri iptal ederdik. getSellOrder() adinda "SELL" gecen HER
     * urunu dondurdugu icin bu titizlik sart - elle acilmis alakasiz bir
     * emri iptal etmek cok kolay.
     *
     * @return slot, ya da bu emir ekranda yoksa -1
     */
    private int findSellOrderSlot(String sellName) {
        if (sellName == null) return -1;
        for (int slot : inventoryScanner.getSellOrder()) {
            String name = inventoryScanner.getName(slot).replace("SELL ", "").trim();
            if (name.endsWith(sellName)) return slot;
        }
        return -1;
    }

    /**
     * Satis emrindeki adet. Ad "SELL 16x Ultimate Wise V" gibi bir onek
     * tasiyorsa oradan okunur; okunamazsa 1 varsayilir ama en az 2 slot
     * istenir - bir kitap eksik dusmesindense bir tur beklemek iyidir.
     */
    private int sellOrderSize(int slot) {
        String name = inventoryScanner.getName(slot).replace("SELL ", "").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)x\\s").matcher(name);
        if (!m.find()) return 2;
        try {
            return Math.max(1, Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /** Bir kitabin satis emrini yenileme kuyruguna alir. */
    private void queueRelist(Book book) {
        if (book == null) return;
        String name = book.getRomanLevel(book.sellLevel());

        // SOGUMA SURESI: ayni emri saniyeler icinde tekrar tekrar yenilemek
        // hem kuyruk sirasini kaybettirir hem bot gibi gorunur. Bazaar fiyati
        // saliniyorsa monitor art arda "outbid" diyebilir.
        Long last = lastRelistMs.get(name);
        if (last != null && System.currentTimeMillis() - last < RELIST_COOLDOWN_MS) {
            debug("[RELIST] " + name + " soguma suresinde, atlaniyor");
            return;
        }
        // ADA GORE TEKILLESTIR. Kardes hatlar (1to5 ve 2to5) ayri Book
        // nesneleridir ama AYNI satis emrini paylasirlar. Nesneye gore
        // tekillestirseydik ikisi de kuyruga girer, ikincisi birincinin yeni
        // actigi saglikli emri iptal ederdi.
        for (Book queued : relistQueue) {
            if (queued.getRomanLevel(queued.sellLevel()).equals(name)) return;
        }
        if (relistName != null && relistName.equals(name)) return;

        relistQueue.add(book);
        relistQueuedMs.put(name, System.currentTimeMillis());
    }

    /** Sıradaki kitaba geç; kuyruk bittiyse RELIST'ten çık. */
    private void finishRelistBook() {
        relistBook = null;
        relistName = null;
        relistStep = Relist.OPEN_ORDERS;
        relistWaits = 0;
        relistInvBefore = 0;
        relistCancelled = false;
        relistFindTries = 0;
        relistRetries = 0;
        pendingSellPrice = 0;

        if (relistQueue.isEmpty()) {
            endRelist("hepsi bitti");
        }
    }

    /** RELIST'ten cik: ekrani kapat, IDLE'a don. */
    private void endRelist(String why) {
        debug("[RELIST] bitti (" + why + ")");
        relistBook = null;
        relistName = null;
        relistStep = Relist.OPEN_ORDERS;
        relistWaits = 0;
        relistInvBefore = 0;
        relistCancelled = false;
        relistFindTries = 0;
        relistRetries = 0;
        pendingSellPrice = 0;
        if (isContainerOpen()) minecraft.player.closeContainer();
        state = State.IDLE;
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
        pendingSellOutbidBooks.add(book);
        ActionLog.add(ActionLog.Tag.OUTBID, name + " sell order was outbid");
        // Izlemeyi birak: yeniden listeledikten sonra yeni fiyatla tekrar
        // eklenecek. Birakilmazsa ayni emir icin ust uste uyari gelir.
        bazaarMonitor.finishSell(book);
    }

    /** Kuyrukta bekleyen SATIS outbid'lerini tick thread'inde isler. */
    private void drainSellOutbids() {
        Book book;
        while ((book = pendingSellOutbidBooks.poll()) != null) {
            queueRelist(book);
        }
    }

    /**
     * ALIM siparisi outbid yendi.
     *
     * DIKKAT: BazaarMonitor'un HTTP thread'inden cagriliyor. Burada gorev
     * haritasina DOKUNULMAZ - editStateBook -> dumpTasks zinciri haritayi
     * geziyor, tick thread'i ayni anda put/remove yapiyor ve bu
     * ConcurrentModificationException atiyordu. Istisna CompletableFuture
     * icinde sessizce yutuldugu icin o turdaki diger siparisler hic taranmiyor,
     * outbid bayragi da temizlenmedigi icin ayni uyari her turda tekrar
     * ediyordu. Artik yalnizca thread-safe kuyruga yazilir; isi IDLE yapar.
     */
    private void handleOutbid(Book book) {
        pendingBuyOutbids.add(book);
    }

    /** Kuyrukta bekleyen alim outbid'lerini TICK thread'inde isler. */
    private void drainBuyOutbids() {
        Book book;
        while ((book = pendingBuyOutbids.poll()) != null) {
            Task t = task.get(book);
            if (t == null || !BUY_PHASE.contains(t.getBookState())) {
                // Görev artık alım fazında değil: eski bir sipariş kaydından gelen bu
                // uyarı, birleştirme zincirini ortasından keserdi.
                debug("stale outbid ignored for " + book);
                bazaarMonitor.finish(book);
                continue;
            }
            debug("Found outbid:" + book.getRomanLevel(book.level()));
            TradeHistory.outbid(book);
            ActionLog.add(ActionLog.Tag.OUTBID, book.getRomanLevel(book.level()) + " was outbid");
            editStateBook(book, BookState.OUTBID);
        }
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
            // RELIST kendi adim sayacini tutuyor (RELIST_MAX_WAITS) ve takilirsa
            // kendi kendine cikiyor; watchdog'un ikinci kez karismasina gerek yok.
            case RELIST -> 0;
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


    /**
     * Bir kitap hattinin DURUMU.
     *
     * ESKIDEN burada "envanterde 6, depoda 10" gibi sayaclar vardi. Kitaplar
     * birbirinin ayni oldugu icin o sayaclar bir kurguydu ve kaydiklarinda
     * kimse fark etmiyordu. Artik elde ne oldugu BookLedger'da ADRESLERIYLE
     * duruyor; burada yalnizca sayilamayan sey kaliyor: bazaar'da bekleyen
     * siparis.
     */
    private class Task {
        private BookState bookState = BookState.SELECTED;

        /** Acik alim siparisinde bekleyen TABAN SEVIYE adet. */
        private int onOrder = 0;

        private boolean earlyAction = false;
        private boolean earlyStore = false;
        /** COMBINE takildiginda depo bir kez yeniden kontrol edildi mi? */
        private boolean anvilRecheckAttempted = false;
        /** ANVIL su an hangi depo sayfasindan cekiyor. */
        private BookLedger.Place pullFrom = BookLedger.Place.STORAGE_1;
        /** ANVIL iki sayfaya da bakti mi? (tek turda sonsuz sayfa cevirmeyi keser) */
        private boolean bothPagesChecked = false;
        /** ANVIL "envanter dolu" deyip kac kez geri dondu? */
        private int anvilFullBounces = 0;
        /** ANVIL bu ziyarette kac kez sahipsiz kitap sahiplendi? (dongu emniyeti) */
        private int anvilAdoptions = 0;

        private BookState getBookState() {
            return bookState;
        }

        private void setBookState(BookState bookState) {
            this.bookState = bookState;
        }

        private boolean isAnvilRecheckAttempted() {
            return anvilRecheckAttempted;
        }

        private void setAnvilRecheckAttempted(boolean value) {
            this.anvilRecheckAttempted = value;
        }

        private boolean isEarlyAction() {
            return earlyAction;
        }

        private void setEarlyAction(boolean value) {
            this.earlyAction = value;
        }

        private boolean isEarlyStore() {
            return earlyStore;
        }

        private void setEarlyStore(boolean value) {
            this.earlyStore = value;
        }
    }

    // =====================================================================
    // HAVUZ MATEMATIGI
    //
    // Tek kural: elimdekini taban seviye cinsinden say, hedeften cikar, farki
    // siparis et. Boylece "kitap kayboldu" ile "kitap yari yolda kaldi" ayni
    // hesaba iner - ikisi de "su kadar birim eksigim var" demektir.
    // =====================================================================

    /** Hattin hedefi kac taban seviye kitap. 1->5 icin 16. */
    private int targetUnits(Book book) {
        return book.getQtyAmount(book.level());
    }

    /** Elde FIZIKSEL olarak duran birim - defterden okunur, tahmin yok. */
    private int heldUnits(Book book) {
        return BookLedger.units(book);
    }

    private int onOrderOf(Book book) {
        Task t = task.get(book);
        return t == null ? 0 : Math.max(0, t.onOrder);
    }

    /** Daha kac birim eksik. Tamamlama siparisi TAM bu kadar acilir. */
    private int missingUnits(Book book) {
        return Math.max(0, targetUnits(book) - heldUnits(book) - onOrderOf(book));
    }

    /** Havuz tamam mi: bekleyen siparis yok ve elde hedef kadar birim var. */
    private boolean isCompleted(Book book) {
        return onOrderOf(book) <= 0 && heldUnits(book) >= targetUnits(book);
    }

    /** Depoda bu hatta kayitli kitap var mi? */
    private boolean hasStorage(Book book) {
        return !BookLedger.of(book, BookLedger.Place.STORAGE_1).isEmpty()
                || !BookLedger.of(book, BookLedger.Place.STORAGE_2).isEmpty();
    }

    /** Envanterde bu hatta ait TABAN SEVIYE kitap var mi? (depolanabilecek olan) */
    private boolean hasStorableInInventory(Book book) {
        for (BookLedger.Holding h : BookLedger.of(book, BookLedger.Place.INVENTORY)) {
            if (h.level() == book.level()) return true;
        }
        return false;
    }

    /** Depo sayfasinin defterdeki karsiligi. */
    private BookLedger.Place placeOf(boolean secondPage) {
        return secondPage ? BookLedger.Place.STORAGE_2 : BookLedger.Place.STORAGE_1;
    }

    /**
     * ACIK olan depo sayfasini bu hat icin bastan yazar.
     *
     * Once o sayfanin defteri bosaltilir, sonra ekranda GERCEKTEN ne varsa
     * yeniden yazilir. Yani defter gercege hizalanir, ustune eklenmez.
     * Baska hatta kayitli slotlara dokunulmaz.
     *
     * @param budget en fazla kac sahipsiz kitap sahiplenilecek (-1 = sinirsiz)
     * @return bu sayfada bu hat adina yazilan kitap sayisi
     */
    private int resyncStoragePage(Book book, BookLedger.Place place) {
        // BOS GORUNEN SAYFAYI SILME.
        //
        // clearPlace geri donusu olmayan bir islem. Sandik icerigi henuz
        // gelmemisse, /ec beklenenden baska bir sayfa acmissa ya da komut
        // dusmusse ekranda hicbir kitap gormeyiz - ve o sayfanin TUM kayitlarini
        // silersek fiziksel olarak orada duran kitaplar sahipsiz kalir. Kayit
        // varken bos sayfa gormek bir SUPHEDIR, gercek degil: dokunma, uyar.
        int visible = 0;
        for (int lvl = book.level(); lvl < book.sellLevel(); lvl++) {
            visible += inventoryScanner.findLoreContainer(book.getRomanLevel(lvl)).size();
        }
        if (visible == 0 && !BookLedger.of(book, place).isEmpty()) {
            ledgerWarn(book, place + " bos gorundu ama kayit var - kayitlar korundu");
            return -1;
        }

        BookLedger.clearPlace(book, place);

        int written = 0;
        for (int lvl = book.level(); lvl < book.sellLevel(); lvl++) {
            for (int slot : inventoryScanner.findLoreContainer(book.getRomanLevel(lvl))) {
                if (heldUnits(book) >= targetUnits(book)) return written;
                if (BookLedger.ownedByOther(book, place, slot)) continue;
                BookLedger.add(book, place, slot, lvl);
                written++;
            }
        }
        return written;
    }

    /**
     * Envanteri bu hat icin bastan yazar. Cekic slotlari zaten disarida -
     * findLoreInvAddressed yalnizca oyuncunun envanterine bakar.
     */
    private int resyncInventory(Book book) {
        BookLedger.clearPlace(book, BookLedger.Place.INVENTORY);

        int written = 0;
        for (int lvl = book.level(); lvl < book.sellLevel(); lvl++) {
            for (int[] hit : inventoryScanner.findLoreInvAddressed(book.getRomanLevel(lvl))) {
                if (heldUnits(book) >= targetUnits(book)) return written;
                int address = hit[0];
                if (BookLedger.ownedByOther(book, BookLedger.Place.INVENTORY, address)) continue;
                BookLedger.add(book, BookLedger.Place.INVENTORY, address, lvl);
                written++;
            }
        }
        return written;
    }

    /**
     * Envanterde SAHIPSIZ duran kitaplari bu hatta yazar.
     *
     * Ekleyicidir - hicbir kaydi silmez. Depodan yeni cekilen kitaplar,
     * relogdan sonra ortada kalanlar ve elle birakilmis parcalar boyle
     * sahiplenilir. Baska hatta kayitli adreslere dokunmaz.
     */
    private int adoptUnownedInventory(Book book) {
        int added = 0;
        for (int lvl = book.level(); lvl < book.sellLevel(); lvl++) {
            for (int[] hit : inventoryScanner.findLoreInvAddressed(book.getRomanLevel(lvl))) {
                // HEDEFTEN FAZLASINI ALMA. Sinirsiz sahiplenirse ilk sirada
                // gezen hat (1to5) paylasilan tum ara seviye kitaplari kapiyor,
                // kardes hat (2to5) elini bos buluyor ve depoda duran kitaplar
                // icin yeniden siparis aciyordu. Ustelik 32 birim toplayan hat
                // 2'nin kuvveti olmaktan cikip zincir sonunda artik biraktiriyordu.
                if (heldUnits(book) >= targetUnits(book)) return added;
                if (BookLedger.isOwned(BookLedger.Place.INVENTORY, hit[0])) continue;
                BookLedger.add(book, BookLedger.Place.INVENTORY, hit[0], lvl);
                added++;
            }
        }
        return added;
    }

    /** ACIK olan sayfadaki sahipsiz kitaplari bu hatta yazar. Ekleyicidir. */
    private int adoptUnownedHere(Book book, BookLedger.Place page) {
        int added = 0;
        for (int lvl = book.level(); lvl < book.sellLevel(); lvl++) {
            for (int slot : inventoryScanner.findLoreContainer(book.getRomanLevel(lvl))) {
                if (heldUnits(book) >= targetUnits(book)) return added;
                if (BookLedger.isOwned(page, slot)) continue;
                BookLedger.add(book, page, slot, lvl);
                added++;
            }
        }
        return added;
    }

    /**
     * Cekic turu bittiginde elde ne kaldiysa deftere yazar.
     *
     * BU METOT OKSUZ SORUNUNUN KALBI. Birlestirme sirasinda kitaplar surekli
     * yer ve seviye degistiriyor, o yuzden COMBINE defteri kullanmiyor. Ama
     * cekicten CIKARKEN elde kalan her parcanin yeniden bir ADRESI ve bir
     * SAHIBI olmali:
     *
     *   - Sahibi olmali ki kardes hat (2to5) onu kendi kitabi sanip almasin.
     *   - Adresi olmali ki eksik hesabi dogru ciksin: elde 1 tane Wisdom 4
     *     varsa o 8 birim eder, hedef 16 ise tam 8 birim eksigiz demektir.
     *
     * Onceki envanter kayitlari silinip yeniden yazilir - cekicten sonra eski
     * adresler zaten gecersiz.
     */
    private void registerCombineLeftovers(Book book) {
        BookLedger.clearPlace(book, BookLedger.Place.INVENTORY);

        int written = 0;
        for (int lvl = book.level(); lvl < book.sellLevel(); lvl++) {
            for (int[] hit : inventoryScanner.findLoreInvAddressed(book.getRomanLevel(lvl))) {
                if (BookLedger.ownedByOther(book, BookLedger.Place.INVENTORY, hit[0])) continue;
                BookLedger.add(book, BookLedger.Place.INVENTORY, hit[0], lvl);
                written++;
            }
        }
        if (written > 0) {
            debug("[LEDGER] " + book.name() + " cekic sonrasi " + written
                    + " artik parca deftere yazildi (" + BookLedger.summary(book) + ")");
        }
    }

    /** Hat kapandi: hem gorev hem defter kaydi silinir. */
    private void dropLine(Book book) {
        task.remove(book);
        BookLedger.clearLine(book);
    }

    /**
     * Defter beklediginden farkli bir sey gordu - sessizce gecme, yaz.
     *
     * Oksuz kitabin nereden dogdugunu ancak boyle yakalayabiliriz: eski
     * sistemde sayac kaydiginda hicbir alarm calmiyordu.
     */
    private void ledgerWarn(Book book, String what) {
        debug("[LEDGER] " + book.name() + " " + book.getRomanLevel(book.level()) + ": " + what);
        ActionLog.add(ActionLog.Tag.RECOVERY, book.name() + ": " + what);
    }
}
