package com.goofy.goofyaddons.ui;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

/**
 * Minecraft'ın yeni girdi olayları (KeyEvent / CharacterEvent) record tipleri ve
 * alan adları sürümden sürüme değişebiliyor. Derleyicinin bize verdiği kesin bilgi
 * PARAMETRE TİPLERİ; alan adları değil. Bu yüzden tuş kodunu / karakteri
 * reflection ile çıkarıyoruz:
 *
 *   1. Bilinen isimler denenir (key, keyCode, codepoint...)
 *   2. Record ise ilk int/char bileşeni alınır (bildirim sırası garantilidir)
 *   3. Son çare: parametresiz ilk int/char döndüren metot
 *
 * Böylece accessor adı ne olursa olsun arayüz çalışır; derlemeyi kilitlemez.
 */
public final class Events {

    private Events() {
    }

    /** KeyEvent -> GLFW tuş kodu. */
    public static int keyCode(Object event) {
        return extractInt(event, "key", "keyCode", "getKey", "keycode", "code");
    }

    /** CharacterEvent -> yazılan karakter. */
    public static char character(Object event) {
        int value = extractInt(event, "codepoint", "codePoint", "character", "chr", "getCodepoint", "getCharacter");
        if (value <= 0 || value > Character.MAX_VALUE) return 0;
        return (char) value;
    }

    private static int extractInt(Object event, String... preferredNames) {
        if (event == null) return -1;
        Class<?> type = event.getClass();

        for (String name : preferredNames) {
            Integer value = tryInvoke(event, type, name);
            if (value != null) return value;
        }

        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Class<?> componentType = component.getType();
                if (componentType != int.class && componentType != char.class) continue;
                Integer value = tryInvoke(event, type, component.getName());
                if (value != null) return value;
            }
        }

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0) continue;
            if (method.getDeclaringClass() == Object.class) continue;
            if (method.getName().equals("hashCode")) continue;
            Class<?> returnType = method.getReturnType();
            if (returnType != int.class && returnType != char.class) continue;
            Integer value = tryInvoke(event, type, method.getName());
            if (value != null) return value;
        }

        return -1;
    }

    private static Integer tryInvoke(Object event, Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            if (method.getParameterCount() != 0) return null;
            Object result = method.invoke(event);
            if (result instanceof Integer intValue) return intValue;
            if (result instanceof Character charValue) return (int) charValue;
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
