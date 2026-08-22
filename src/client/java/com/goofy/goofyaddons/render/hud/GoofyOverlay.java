package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.GoofyAddons;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * ESKİ ARAYÜZ - ŞU AN DEVRE DIŞI.
 *
 * Eskiden G tuşuyla açılıp kapanan, makro durumunu ve görev listesini gösteren
 * overlay. Yerini M tuşuyla açılan GoofyScreen aldı. Sınıf bilerek silinmedi:
 * ileride tekrar istenirse GoofyAddonsClient içindeki GoofyOverlay.register()
 * satırını geri açmak yeterli.
 *
 * Konumu artık yeni Profit HUD'un altına hizalanıyor (HUD taşınınca bu da taşınır).
 */
public class GoofyOverlay {

    private static final Identifier HEADER = Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "textures/gui/header.png");

    private static final int PANEL_GAP = 6;
    private static final int PANEL_WIDTH = 220;
    private static final int HEADER_WIDTH = 160;
    private static final int HEADER_HEIGHT = HEADER_WIDTH * 192 / 1195;
    private static final int LINE_HEIGHT = 12;
    private static final int PADDING = 6;

    public static boolean visible = true;

    private GoofyOverlay() {
    }

    private static int panelX() {
        return GoofyConfig.INSTANCE == null ? 8 : GoofyConfig.INSTANCE.hudX;
    }

    private static int panelY() {
        int hudY = GoofyConfig.INSTANCE == null ? 8 : GoofyConfig.INSTANCE.hudY;
        return hudY + EconomyHud.HEIGHT + PANEL_GAP;
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "goofy_overlay"),
                GoofyOverlay::render
        );
    }

    public static void toggle() {
        visible = !visible;
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!visible) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        String stateName = "Idle";
        String activeBook = "-";
        List<String> taskLines = List.of();

        if (running && FeatureManager.INSTANCE.getCurrentFeature() instanceof BazaarFlipper flipper) {
            stateName = flipper.getStateName();
            activeBook = flipper.getActiveBookName();
            taskLines = flipper.getTaskSummary();
        }

        int panelX = panelX();
        int panelY = panelY();
        int contentLines = 2 + Math.max(taskLines.size(), 1);
        int panelHeight = HEADER_HEIGHT + PADDING * 3 + (LINE_HEIGHT * contentLines);

        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0x90252525);
        graphics.outline(panelX, panelY, PANEL_WIDTH, panelHeight, 0xFF000000);

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                HEADER,
                panelX + (PANEL_WIDTH - HEADER_WIDTH) / 2,
                panelY + PADDING,
                0, 0,
                HEADER_WIDTH, HEADER_HEIGHT,
                1195, 192,
                1195, 192
        );

        int textX = panelX + PADDING;
        int textY = panelY + PADDING * 2 + HEADER_HEIGHT;

        int statusColor = running ? 0xFF55FF55 : 0xFFFF5555;
        String statusText = "Macro: " + (running ? "Running" : "Idle") + (running ? " [" + stateName + "]" : "");
        if (running && FeatureManager.INSTANCE.isPaused()) {
            statusText = "Macro: Paused [" + stateName + "]";
            statusColor = 0xFFFFAA00;
        }
        graphics.text(minecraft.font, statusText, textX, textY, statusColor, false);
        textY += LINE_HEIGHT;

        graphics.text(minecraft.font, "Active Book: " + activeBook, textX, textY, 0xFFAAAAAA, false);
        textY += LINE_HEIGHT;

        if (taskLines.isEmpty()) {
            graphics.text(minecraft.font, "No active tasks", textX, textY, 0xFF888888, false);
        } else {
            for (String line : taskLines) {
                graphics.text(minecraft.font, line, textX, textY, 0xFFDDDDDD, false);
                textY += LINE_HEIGHT;
            }
        }
    }
}
