package com.goofy.goofyaddons.ui;

import com.goofy.goofyaddons.utils.ChatUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

/**
 * GoofyAddons ekranlarının ortak temeli.
 *
 * NEDEN BÖYLE (26.1.2): Bu sürümde GUI çizimi "render state extraction"
 * modeline geçti ve Screen'deki bütün render* metotları extract* olarak yeniden
 * adlandırıldı. Oyundaki imza taramasının çıktısı bunu kesinleştirdi:
 *
 *   extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor, int, int, float)
 *   extractRenderState(GuiGraphicsExtractor, int, int, float)   <-- eski "render"
 *   extractBackground(GuiGraphicsExtractor, int, int, float)    <-- eski "renderBackground"
 *   extractTransparentBackground(GuiGraphicsExtractor)
 *   extractMenuBackgroundTexture(GuiGraphicsExtractor, Identifier, ...)
 *
 * Yani eskiden yazdığımız render(...) aşırı yüklemelerinin HİÇBİRİ üst sınıftaki
 * bir metodu ezmiyordu; ekran açılıyor, tick alıyor ama tek piksel çizilmiyordu.
 * Artık tek ve doğru giriş noktası extractRenderState. @Override bilerek KONULDU:
 * imza bir gün yine değişirse ekran sessizce boş açılmak yerine derlemede patlar.
 *
 * super.extractRenderState(...) BİLEREK çağrılmıyor: bu arayüz vanilla widget
 * kullanmıyor, arka planı (scrim) kendisi çiziyor. Vanilla'nın kendi arka planını
 * da çizdirmek panelin üstüne bulanık bir katman bindiriyor.
 */
public abstract class BaseScreen extends Screen implements GoofyGui {

    /** Yeni girdi API'sinde tıklama koordinat taşımıyor; konumu çizimden yakalıyoruz. */
    protected int lastMouseX = 0;
    protected int lastMouseY = 0;

    private boolean renderReached = false;
    private int idleTicks = 0;

    protected BaseScreen(Component title) {
        super(title);
    }

    /** Asıl çizim burada yapılır. */
    protected abstract void drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

    // --- gerçek çizim giriş noktası (eski adı: render) --------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        renderReached = true;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        drawContent(graphics, mouseX, mouseY);
    }

    // ---------------------------------------------------------------------------

    @Override
    public void tick() {
        if (renderReached) return;
        idleTicks++;
        if (idleTicks == 10) reportRenderSignature();
    }

    /**
     * Güvenlik ağı: çizim yine hiç çağrılmazsa Screen'deki gerçek imzaları yazar.
     * Üretim jar'ında isimler intermediary olabilir (method_xxxxx) ama PARAMETRE
     * TİPLERİ okunur kalır - ihtiyacımız olan tek şey o.
     */
    private void reportRenderSignature() {
        ChatUtils.clientMessage("GoofyAddons: ekran cizilemedi. Asagidaki satirlari kopyalayip gonder:");
        System.out.println("[GoofyAddons] --- Screen cizim imza taramasi ---");

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
