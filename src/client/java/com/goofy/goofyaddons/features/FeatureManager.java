package com.goofy.goofyaddons.features;

import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;

import java.util.ArrayList;
import java.util.List;


public class FeatureManager {
    List<Feature> featureList = new ArrayList<>();
    Feature currentFeature = null;

    /**
     * Duraklatma START/STOP'tan TAMAMEN bağımsızdır: pause yalnızca tick döngüsünü
     * dondurur, görevler/sayaçlar/state olduğu gibi kalır; resume tam kaldığı
     * noktadan devam eder (yeniden start ATILMAZ).
     */
    private boolean paused = false;

    /** Kullanıcı elle duraklattı mı? Failsafe'in resume'u bunu ezemez. */
    private boolean manualPause = false;

    /** Arayüz açıldığı için otomatik duraklatıldı mı? */
    private boolean menuPause = false;

    public static final FeatureManager INSTANCE = new FeatureManager();

    private FeatureManager() {
        featureList.add(new BazaarFlipper());
    }

    public void onTick() {
        if (currentFeature == null) return;
        currentFeature.onTick();
    }

    public void start(String name) {
        currentFeature = featureList.stream().filter(feature -> feature.name().equals(name)).findFirst().orElse(null);
        if (currentFeature == null) return;
        paused = false;
        manualPause = false;
        menuPause = false;
        currentFeature.start();
    }

    public void stop() {
        if (currentFeature == null) return;
        currentFeature.stop();
        currentFeature = null;
        paused = false;
        manualPause = false;
        menuPause = false;
    }

    /** Failsafe (ScheduledReboot) tarafından kullanılır. */
    public void pause() {
        if (currentFeature == null || paused) return;
        currentFeature.pause();
        paused = true;
    }

    /** Failsafe tarafından kullanılır - kullanıcı elle duraklattıysa devam ETTİRMEZ. */
    public void resume() {
        if (currentFeature == null || !paused) return;
        if (manualPause || menuPause) return;
        currentFeature.resume();
        paused = false;
    }

    /** Arayüzdeki buton / tuş: elle duraklat-devam et. Sonuç: yeni "duraklatıldı" durumu. */
    public boolean toggleManualPause() {
        if (currentFeature == null) return false;
        if (paused) {
            manualPause = false;
            menuPause = false;
            currentFeature.resume();
            paused = false;
            return false;
        }
        currentFeature.pause();
        paused = true;
        manualPause = true;
        return true;
    }

    /**
     * Arayüz açılınca makro geçici olarak dondurulur. Makro açık bir bazaar/depo
     * ekranı beklerken bizim arayüzümüz açılırsa ekranları birbirine karıştırır;
     * ayrıca kullanıcı ayar değiştirirken makronun tıklamaya devam etmesi istenmez.
     */
    public void onGuiOpen() {
        if (currentFeature == null || paused) return;
        currentFeature.pause();
        paused = true;
        menuPause = true;
    }

    public void onGuiClose() {
        if (currentFeature == null || !menuPause) return;
        menuPause = false;
        if (manualPause) return; // kullanıcı arayüzdeyken elle duraklattıysa öyle kalsın
        currentFeature.resume();
        paused = false;
    }

    public Feature getCurrentFeature() {
        return currentFeature;
    }

    public boolean isMacroRunning() {
        return currentFeature != null;
    }

    /** Makro hem seçili hem de duraklatılmamış mı? Uptime sayacı yalnızca bu true iken ilerler. */
    public boolean isMacroActive() {
        return currentFeature != null && !paused;
    }

    public boolean isPaused() {
        return paused;
    }
}
