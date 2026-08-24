package com.goofy.goofyaddons.failsafes;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.utils.ActionLog;
import com.goofy.goofyaddons.utils.ChatUtils;
import com.goofy.goofyaddons.utils.Humanizer;

/**
 * Session planlayıcı (BETA).
 *
 * Makro rastgele bir süre (workMin-workMax dk) çalışır, sonra rastgele bir süre
 * (breakMin-breakMax dk) mola verir, sonra kaldığı yerden devam eder. Süreler de
 * çan eğrisiyle seçilir - hep tam ortadan seçilse bu da kendi başına bir imza olurdu.
 *
 * NEDEN FAILSAFE KLASÖRÜNDE: ScheduledReboot zaten tam olarak aynı işi yapıyor -
 * makroyu duraklat, bekle, devam ettir. Aynı rayı kullanıyoruz, yeni altyapı yok.
 *
 * MOLA = FeatureManager.pause(): görevler, sayaçlar ve state olduğu gibi kalır,
 * devam edince kaldığı noktadan sürer. Kullanıcı bu sırada elle duraklattıysa
 * FeatureManager.resume() zaten devam ETTİRMEZ - planlayıcı kullanıcıyı ezemez.
 */
public class SessionPlanner implements Failsafe {

    private enum Phase {
        WORK,
        BREAK
    }

    /** İki tick arası bu kadar boşluk varsa makro yeniden başlamış sayılır. */
    private static final long RESTART_GAP_MS = 10_000;

    private Phase phase = Phase.WORK;
    private long phaseStartMs = 0;
    private long phaseLengthMs = 0;
    private long lastTickMs = 0;

    @Override
    public String name() {
        return "SessionPlanner";
    }

    @Override
    public void onTick() {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) return;

        long now = System.currentTimeMillis();

        if (!config.sessionPlannerEnabled) {
            // Kapalıyken mola içinde kalmayalım - kullanıcı ortasında kapatabilir.
            if (phase == Phase.BREAK) resumeNow("planner disabled");
            phaseStartMs = 0;
            lastTickMs = now;
            return;
        }

        // FailsafeManager yalnızca makro çalışırken tick atıyor. Uzun bir boşluk
        // varsa makro durdurulup yeniden başlatılmış demektir: tur sıfırlanır.
        if (phaseStartMs == 0 || now - lastTickMs > RESTART_GAP_MS) {
            startWork(now);
        }
        lastTickMs = now;

        if (now - phaseStartMs < phaseLengthMs) return;

        if (phase == Phase.WORK) {
            phase = Phase.BREAK;
            phaseStartMs = now;
            phaseLengthMs = minutesToMs(config.breakMinMinutes, config.breakMaxMinutes);
            FeatureManager.INSTANCE.pause();
            String text = "break started - " + (phaseLengthMs / 60000) + " min";
            ChatUtils.clientMessage("SessionPlanner: " + text);
            ActionLog.add(ActionLog.Tag.SESSION, text);
            return;
        }

        resumeNow("break over - back to work");
        startWork(now);
    }

    private void resumeNow(String reason) {
        FeatureManager.INSTANCE.resume();
        Humanizer.restRecovered();
        ChatUtils.clientMessage("SessionPlanner: " + reason);
        ActionLog.add(ActionLog.Tag.SESSION, reason);
    }

    private void startWork(long now) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        phase = Phase.WORK;
        phaseStartMs = now;
        phaseLengthMs = minutesToMs(config.workMinMinutes, config.workMaxMinutes);
        ActionLog.add(ActionLog.Tag.SESSION,
                "work block started - " + (phaseLengthMs / 60000) + " min");
    }

    private long minutesToMs(int minMinutes, int maxMinutes) {
        int min = Math.max(1, minMinutes);
        int max = Math.max(min + 1, maxMinutes);
        // Dakika değil SANİYE cinsinden çan eğrisi: "tam 27 dakika" yerine
        // "27 dk 14 sn" gibi değerler çıksın.
        return Humanizer.gaussian(min * 60, max * 60) * 1000L;
    }

    // ------------------------------------------------------------------ arayüz için

    /** Arayüzde gösterilecek durum satırı. */
    public String statusLine() {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null || !config.sessionPlannerEnabled) return "off";
        if (phaseStartMs == 0) return "starts when the macro starts";

        long remaining = Math.max(0, phaseLengthMs - (System.currentTimeMillis() - phaseStartMs));
        long minutes = remaining / 60000;
        long seconds = (remaining % 60000) / 1000;
        String label = phase == Phase.WORK ? "working" : "on break";
        return label + " - " + minutes + "m " + String.format("%02d", seconds) + "s left";
    }
}
