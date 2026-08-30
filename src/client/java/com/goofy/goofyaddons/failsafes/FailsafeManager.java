package com.goofy.goofyaddons.failsafes;

import com.goofy.goofyaddons.features.FeatureManager;

import java.util.ArrayList;
import java.util.List;

public class FailsafeManager {
    List<Failsafe> failsafes = new ArrayList<>();

    public static FailsafeManager INSTANCE = new FailsafeManager();

    /** Arayüz durum satırını okuyabilsin diye ayrıca tutuluyor. */
    private final SessionPlanner sessionPlanner = new SessionPlanner();

    private FailsafeManager() {
        failsafes.add(new ScheduledReboot());
        failsafes.add(new BazaarOutage());
        failsafes.add(sessionPlanner);
    }

    public SessionPlanner getSessionPlanner() {
        return sessionPlanner;
    }

    public void onTick() {
        if (!FeatureManager.INSTANCE.isMacroRunning()) return;
        failsafes.stream().forEach(failsafe -> failsafe.onTick());
    }


}
