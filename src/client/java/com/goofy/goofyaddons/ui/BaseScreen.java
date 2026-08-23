package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.utils.ChatUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

/**
 * GoofyAddons ekranlarının ortak temeli.
 *
 * NEDEN BÖYLE: Bu Minecraft sürümünde Screen#render'in imzası bizim bildiğimizden
 * farklı ve derleyici bunu bize söylemiyor (sadece "override etmiyor" diyor).
 * Tek bir imza tahmin edip yanılırsak ekran BOŞ açılıyor. Bu yüzden makul olan
 * bütün imzalar burada aşırı yükleme olarak tanımlı; hangisi üst sınıfla eşleşirse
 * o çağrılır, hepsi aynı drawContent()'e gider. @Override bilerek yok - yanlış
 * olanlar derlemeyi kilitlemesin.
 *
 * Hiçbiri eşleşmezse ekran 10 tick sonra gerçek imzayı sohbete yazar; o satırı
 * paylaşırsan bu sınıf tek bir imzaya indirilir.
 */
public abstract class BaseScreen extends Screen implements GoofyGui {

    /** Yeni girdi API'sinde tıklama koordinat taşımıyor; konumu render'dan yakalıyoruz. */
    protected int lastMouseX = 0;
    protected int lastMouseY = 0;

    private boolean renderReached = false;
    private int idleTicks = 0;

    protected BaseScreen(Component title) {
        super(title);
    }

    /** Asıl çizim burada yapılır. */
    protected abstract void drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

    private void dispatch(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        renderReached = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        drawContent(graphics, mouseX, mouseY);
    }

    // --- olası render imzaları -------------------------------------------------

    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        dispatch(graphics, mouseX, mouseY);
    }

    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, DeltaTracker deltaTracker) {
        dispatch(graphics, mouseX, mouseY);
    }

    public void render(GuiGraphicsExtractor graphics, double mouseX, double mouseY, float partialTick) {
        dispatch(graphics, (int) mouseX, (int) mouseY);
    }

    public void render(GuiGraphicsExtractor graphics, double mouseX, double mouseY, DeltaTracker deltaTracker) {
        dispatch(graphics, (int) mouseX, (int) mouseY);
    }

    // ---------------------------------------------------------------------------

    @Override
    public void tick() {
        if (renderReached) return;
        idleTicks++;
        if (idleTicks == 10) reportRenderSignature();
    }

    /**
     * Çizim hiç çağrılmadıysa Screen sınıfındaki gerçek render imzalarını yazar.
     * Üretim jar'ında isimler intermediary olur (method_xxxxx) ama PARAMETRE
     * TİPLERİ okunur kalır - ihtiyacımız olan tek şey o.
     */
    private void reportRenderSignature() {
        ChatUtils.clientMessage("GoofyAddons: ekran cizilemedi. Asagidaki satirlari kopyalayip gonder:");
        System.out.println("[GoofyAddons] --- Screen render imza taramasi ---");

        for (Method method : Screen.class.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0) continue;
            if (params[0] != GuiGraphicsExtractor.class) continue;

            StringBuilder line = new StringBuilder(method.getName()).append("(");
            for (int i = 0; i < params.length; i++) {
                if (i > 0) line.append(", ");
                line.append(params[i].getSimpleName());
            }
            line.append(")");

            ChatUtils.clientMessage("  " + line);
            System.out.println("[GoofyAddons] " + line);
        }

        System.out.println("[GoofyAddons] --- tarama sonu ---");
    }
}
