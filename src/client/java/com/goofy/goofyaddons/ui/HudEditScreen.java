package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.render.hud.EconomyHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * HUD taşıma modu: HUD'un bir önizlemesi çizilir ve fare ile sürüklenebilir.
 * ESC / ENTER kaydeder ve kapatır. Ekran dışına taşmaması için sınırlandırılır.
 */
public class HudEditScreen extends Screen implements GoofyGui {

    private boolean dragging = false;
    private int grabOffsetX = 0;
    private int grabOffsetY = 0;

    // Yeni girdi API'sinde tiklama koordinat tasimiyor; konumu render'dan yakaliyoruz.
    private int lastMouseX = 0;
    private int lastMouseY = 0;

    public HudEditScreen() {
        super(Component.literal("GoofyAddons HUD"));
    }

    @Override
    protected void init() {
        FeatureManager.INSTANCE.onGuiOpen();
    }

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

    // Screen#render son parametresi float mu DeltaTracker mi degisebiliyor:
    // iki asiri yukleme de var, hangisi eslesirse o cagrilir. @Override bilerek yok.
    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        draw(graphics, mouseX, mouseY);
    }

    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, DeltaTracker deltaTracker) {
        draw(graphics, mouseX, mouseY);
    }

    private void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        Draw.rect(graphics, 0, 0, this.width, this.height, Theme.SCRIM);

        // ekran ortasına yardım metni
        int cx = this.width / 2;
        int cy = this.height / 2;
        Draw.textCentered(graphics, "HUD TASIMA MODU", cx, cy - 18, Theme.ACCENT);
        Draw.textCentered(graphics, "Paneli surukleyerek istedigin yere birak", cx, cy - 4, Theme.TEXT_DIM);
        Draw.textCentered(graphics, "ESC ile kaydet ve cik", cx, cy + 10, Theme.TEXT_FAINT);

        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) return;

        if (dragging) {
            config.hudX = clamp(mouseX - grabOffsetX, 0, this.width - EconomyHud.WIDTH);
            config.hudY = clamp(mouseY - grabOffsetY, 0, this.height - EconomyHud.HEIGHT);
        }

        boolean hover = Draw.inside(mouseX, mouseY, config.hudX, config.hudY, EconomyHud.WIDTH, EconomyHud.HEIGHT);
        EconomyHud.render(graphics, config.hudX, config.hudY, hover || dragging);

        // konum bilgisi
        Draw.text(graphics, "X " + config.hudX + "   Y " + config.hudY,
                config.hudX, config.hudY + EconomyHud.HEIGHT + 4, Theme.TEXT_FAINT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config != null
                && Draw.inside(lastMouseX, lastMouseY, config.hudX, config.hudY, EconomyHud.WIDTH, EconomyHud.HEIGHT)) {
            dragging = true;
            grabOffsetX = lastMouseX - config.hudX;
            grabOffsetY = lastMouseY - config.hudY;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            GoofyConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}
