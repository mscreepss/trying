package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.render.hud.EconomyHud;
import com.goofy.goofyaddons.render.hud.StateHud;
import com.goofy.goofyaddons.render.hud.TaskHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * HUD taşıma ekranı: üç HUD'un da önizlemesi çizilir ve fareyle sürüklenir.
 *
 * Bu bir Screen olduğu için açıldığı anda fare imleci serbest kalır - kamera
 * dönmez, tıklama oyuna gitmez. Menüdeki "Edit HUD Layout" butonu doğrudan bunu
 * açar; ayrı bir tuş ataması gerekmez.
 *
 * ESC (ya da menü tuşu) kaydeder ve kapatır. Paneller ekran dışına taşamaz.
 */
public class HudEditScreen extends BaseScreen {

    /** Sürüklenebilen üç panel. */
    private enum Target {
        PROFIT,
        TASKS,
        STATE
    }

    private Target dragging = null;
    private int grabOffsetX = 0;
    private int grabOffsetY = 0;

    public HudEditScreen() {
        super(Component.literal("GoofyAddons HUD Layout"));
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

    // =====================================================================
    // Ölçüler - çizim ve tıklama AYNI kaynaktan okur
    // =====================================================================

    private int panelX(Target target) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        return switch (target) {
            case PROFIT -> config.hudX;
            case TASKS -> config.taskHudX;
            case STATE -> config.stateHudX;
        };
    }

    private int panelY(Target target) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        return switch (target) {
            case PROFIT -> config.hudY;
            case TASKS -> config.taskHudY;
            case STATE -> config.stateHudY;
        };
    }

    private void setPanel(Target target, int x, int y) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        switch (target) {
            case PROFIT -> {
                config.hudX = x;
                config.hudY = y;
            }
            case TASKS -> {
                config.taskHudX = x;
                config.taskHudY = y;
            }
            case STATE -> {
                config.stateHudX = x;
                config.stateHudY = y;
            }
        }
    }

    private int panelW(Target target) {
        return switch (target) {
            case PROFIT -> EconomyHud.width();
            case TASKS -> TaskHud.width();
            case STATE -> StateHud.width();
        };
    }

    private int panelH(Target target) {
        return switch (target) {
            case PROFIT -> EconomyHud.height();
            case TASKS -> TaskHud.height();
            case STATE -> StateHud.height();
        };
    }

    private boolean isEnabled(Target target) {
        GoofyConfig config = GoofyConfig.INSTANCE;
        return switch (target) {
            case PROFIT -> config.hudVisible;
            case TASKS -> config.taskHudVisible;
            case STATE -> config.stateHudVisible;
        };
    }

    private String label(Target target) {
        return switch (target) {
            case PROFIT -> "Profit HUD";
            case TASKS -> "Task HUD";
            case STATE -> "State HUD";
        };
    }

    // =====================================================================
    // Çizim
    // =====================================================================

    @Override
    protected void drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Draw.rect(graphics, 0, 0, this.width, this.height, Theme.SCRIM);

        GoofyConfig config = GoofyConfig.INSTANCE;
        if (config == null) {
            Draw.textCentered(graphics, "Config could not be loaded",
                    this.width / 2, this.height / 2, Theme.RED);
            return;
        }

        int cx = this.width / 2;
        int cy = this.height / 2;
        Draw.textCentered(graphics, "HUD LAYOUT", cx, cy - 22, Theme.ACCENT);
        Draw.textCentered(graphics, "Drag any panel to move it", cx, cy - 6, Theme.TEXT_DIM);
        Draw.textCentered(graphics, "ESC to save and exit", cx, cy + 8, Theme.TEXT_FAINT);

        // Sürükleme, panel çizilmeden ÖNCE uygulanır: aynı karede yeni konumdan çizilsin.
        if (dragging != null) {
            int maxX = Math.max(0, this.width - panelW(dragging));
            int maxY = Math.max(0, this.height - panelH(dragging));
            setPanel(dragging,
                    clamp(mouseX - grabOffsetX, 0, maxX),
                    clamp(mouseY - grabOffsetY, 0, maxY));
        }

        for (Target target : Target.values()) {
            renderPanel(graphics, target, mouseX, mouseY);
        }
    }

    private void renderPanel(GuiGraphicsExtractor g, Target target, int mouseX, int mouseY) {
        int x = panelX(target);
        int y = panelY(target);
        int w = panelW(target);
        int h = panelH(target);

        boolean hover = Draw.inside(mouseX, mouseY, x, y, w, h);
        boolean active = hover || dragging == target;

        switch (target) {
            case PROFIT -> EconomyHud.render(g, x, y, active);
            case TASKS -> TaskHud.render(g, x, y, active);
            case STATE -> StateHud.render(g, x, y, active);
        }

        // Etiket + konum bilgisi panelin altında
        String tag = label(target) + (isEnabled(target) ? "" : "  (hidden)");
        Draw.text(g, tag, x, y + h + 3, active ? Theme.ACCENT : Theme.TEXT_FAINT);
        if (active) {
            Draw.textRight(g, "X " + x + "   Y " + y, x + w, y + h + 3, Theme.TEXT_FAINT);
        }
    }

    // =====================================================================
    // Girdi
    // =====================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (GoofyConfig.INSTANCE == null) return super.mouseClicked(event, doubleClick);

        // Ustteki panel once yakalasin diye tersten geziyoruz (State en son cizilir).
        Target[] targets = Target.values();
        for (int i = targets.length - 1; i >= 0; i--) {
            Target target = targets[i];
            int x = panelX(target);
            int y = panelY(target);
            if (!Draw.inside(lastMouseX, lastMouseY, x, y, panelW(target), panelH(target))) continue;

            dragging = target;
            grabOffsetX = lastMouseX - x;
            grabOffsetY = lastMouseY - y;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
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
