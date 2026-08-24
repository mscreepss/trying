package com.goofy.goofyaddons.utils;

import org.lwjgl.glfw.GLFW;

/**
 * Pano (kopyala / yapıştır).
 *
 * NEDEN DOĞRUDAN GLFW: Minecraft'ın kendi pano yardımcısının alan/metot adı
 * sürümden sürüme değişiyor; AWT ise bazı sistemlerde oyunu kilitleyebiliyor.
 * GLFW ise LWJGL'in parçası ve bu projede zaten kullanılıyor (InputConstants).
 *
 * glfwGetCurrentContext() çağıran thread'in aktif penceresini verir; arayüz kodu
 * render thread'inde çalıştığı için bu her zaman Minecraft'ın penceresidir.
 * Pencere bulunamazsa (0) hiçbir şey yapılmaz - çökme olmaz.
 */
public final class Clipboard {

    private Clipboard() {
    }

    public static void set(String text) {
        if (text == null) return;
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return;
        try {
            GLFW.glfwSetClipboardString(window, text);
        } catch (Exception ignored) {
        }
    }

    /** Pano boşsa ya da okunamazsa boş string. */
    public static String get() {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return "";
        try {
            String text = GLFW.glfwGetClipboardString(window);
            return text == null ? "" : text;
        } catch (Exception e) {
            return "";
        }
    }
}
