package com.goofy.goofyaddons.features.bookflipper.helper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;


public class BazaarMonitor {
    private boolean running = false;
    private HttpClient client = HttpClient.newHttpClient();
    private long duration = 15000;
    private long startMs;
    private long lastUpdated;
    /**
     * THREAD-SAFE OLMALI: HTTP thread'i refresh() icinde gezip removeIf yaparken
     * tick thread'i add() / finish() / finishSell() cagiriyor. Duz ArrayList ile
     * bu ConcurrentModificationException (ya da toArray sirasinda null kuyruk ->
     * NPE) demekti; istisna thenAccept icinde sessizce yutuldugu icin o turdaki
     * geri kalan siparisler hic taranmiyordu.
     */
    private final List<BazaarMonitorItem> monitorItemList = new CopyOnWriteArrayList<>();
    private final List<Consumer<Book>> hookList = new ArrayList<>();
    private final List<Consumer<Book>> sellHookList = new ArrayList<>();

    /**
     * Izlemeye bir siparis ekler.
     *
     * isSellOrder = true olan kayitlar SATIS emirleridir ve o kitabin
     * sellLevel urununde takip edilirler. Ayni kitap + ayni tip icin eski kayit
     * varsa temizlenir - yoksa yeniden listeleyince ayni siparis iki kez
     * izlenirdi.
     */
    public void add(Book book, double price, boolean isSellOrder) {
        monitorItemList.removeIf(b -> b.book.equals(book) && b.isSellOrder == isSellOrder);
        monitorItemList.add(new BazaarMonitorItem(book, price, isSellOrder));
    }

    /**
     * ALIM siparisinin izlemesini birakir.
     *
     * DIKKAT: Sadece alim kayitlarini siler. Eskiden kitaba ait TUM kayitlari
     * siliyordu; kitap satildiktan sonra gelen bayat bir alim uyarisi, o kitabin
     * SATIS izlemesini de sessizce oldururdu.
     */
    public void finish(Book book) {
        monitorItemList.removeIf(b -> b.book.equals(book) && !b.isSellOrder);
    }

    /** SATIS siparisinin izlemesini birakir (satis doldu / iptal edildi). */
    public void finishSell(Book book) {
        monitorItemList.removeIf(b -> b.book.equals(book) && b.isSellOrder);
    }

    public void reset() {
        monitorItemList.clear();
    }

    public void hook(Consumer<Book> hook) {
        hookList.add(hook);
    }

    /** Satis emri outbid yendiginde cagrilir. */
    public void hookSell(Consumer<Book> hook) {
        sellHookList.add(hook);
    }


    public void onTick() {
        if (!running) return;
        if (!((System.currentTimeMillis() - startMs) >= duration)) return;
        if (monitorItemList.isEmpty()) return;
        startMs = System.currentTimeMillis();
        refresh();

    }

    public void start() {
        if (running) return;
        running = true;
        startMs = System.currentTimeMillis();
    }

    public void stop() {
        running = false;
    }

    public void refresh() {


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hypixel.net/v2/skyblock/bazaar"))
                .GET()
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(body ->
                        JsonParser.parseString(body).getAsJsonObject()
                )
                .thenAccept(root -> {

                    long lastUpdated = root.get("lastUpdated").getAsLong();

                    if (lastUpdated == this.lastUpdated) return;

                    this.lastUpdated = lastUpdated;

                    JsonObject products = root.getAsJsonObject("products");

                    // KOPYA UZERINDE GEZ. outbidScanner -> handleOutbid -> hook
                    // zinciri finish() / finishSell() cagirabiliyor, o da
                    // monitorItemList.removeIf yapiyor. Ayni listeyi forEach ile
                    // gezerken bu ConcurrentModificationException atardi; istisna
                    // thenAccept icinde sessizce yutuldugu icin geri kalan
                    // siparisler o turda hic taranmiyor, asagidaki temizlik de
                    // hic calismiyordu. SATIS outbid'inde bu HER SEFERINDE
                    // oluyordu - handleSellOutbid her cagrisinda finishSell yapar.
                    new ArrayList<>(monitorItemList)
                            .forEach(bazaarMonitorItem -> outbidScanner(products, bazaarMonitorItem));

                    monitorItemList.removeIf(bazaarMonitorItem -> {
                        if (bazaarMonitorItem.getOutbid()) return true;
                        return false;
                    });
                });

    }

    private void outbidScanner(JsonObject products, BazaarMonitorItem bazaarMonitorItem) {
        if (!bazaarMonitorItem.shouldCheck()) return;

        // ESKI HATA: satis emirleri de ALIM seviyesinin urununde aranıyordu.
        // Satis emri sellLevel urunundedir; yanlis urune bakildigi icin satis
        // outbid'i pratikte hic calismiyordu.
        Book book = bazaarMonitorItem.book;
        int level = bazaarMonitorItem.isSellOrder ? book.sellLevel() : book.level();
        JsonObject productID = products.getAsJsonObject(book.getLevel(level));
        if (productID == null) return;

        if (!bazaarMonitorItem.isSellOrder) {
            JsonObject entry = productID.getAsJsonArray("sell_summary").get(0).getAsJsonObject();
            int orders = entry.get("orders").getAsInt();
            double price = entry.get("pricePerUnit").getAsDouble();

            if (orders > 1 || price != bazaarMonitorItem.price) {
                bazaarMonitorItem.setOutbid(true);
                handleOutbid(bazaarMonitorItem);
            }
        } else {
            JsonObject entry = productID.getAsJsonArray("buy_summary").get(0).getAsJsonObject();
            int orders = entry.get("orders").getAsInt();
            double price = entry.get("pricePerUnit").getAsDouble();

            if (orders > 1 || price != bazaarMonitorItem.price) {
                bazaarMonitorItem.setOutbid(true);
                handleOutbid(bazaarMonitorItem);
            }
        }

    }

    private void handleOutbid(BazaarMonitorItem bazaarMonitorItem) {
        List<Consumer<Book>> hooks = bazaarMonitorItem.isSellOrder ? sellHookList : hookList;
        if (hooks.isEmpty()) return;
        hooks.getFirst().accept(bazaarMonitorItem.book);
    }



    private class BazaarMonitorItem {
        private boolean isSellOrder;
        private Book book;
        private double price;
        private boolean isOutbid = false;
        private long time;

        public BazaarMonitorItem(Book book, double price, boolean isSellOrder) {
            this.book = book;
            this.price = price;
            this.isSellOrder = isSellOrder;
            time = System.currentTimeMillis();
        }

        private void setOutbid(boolean outbid) {
            isOutbid = outbid;
        }

        private boolean getOutbid() {
            return isOutbid;
        }

        private boolean shouldCheck() {
            if (!((System.currentTimeMillis() - time) >= duration)) return false;
            time = System.currentTimeMillis();
            return true;
        }
    }

}

