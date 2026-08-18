package com.tierlookup.client.ui;

import java.lang.reflect.Method;

public final class TierUi {
    private static volatile Method fillMethod;
    private static volatile Method textMethod;
    private static volatile Method widthMethod;
    private static volatile Class<?> contextClass;
    private static volatile Class<?> textRendererClass;
    private TierUi() {
    }
    public static void panel(Object context, int x, int y, int width, int height, int background, int accent) {
        fill(context, x, y, x + width, y + height, background);
        fill(context, x, y, x + width, y + 2, accent);
    }
    public static void noteGlyph(Object context, int x, int y, int color) {
        fill(context, x + 1, y + 1, x + 8, y + 9, color);
        fill(context, x + 6, y + 1, x + 8, y + 3, 0xAA10151A);
        fill(context, x + 2, y + 4, x + 6, y + 5, 0xAA10151A);
        fill(context, x + 2, y + 6, x + 6, y + 7, 0xAA10151A);
    }
    public static void messageGlyph(Object context, int x, int y, int color) {
        fill(context, x + 1, y + 1, x + 9, y + 7, color);
        fill(context, x + 3, y + 7, x + 5, y + 9, color);
        fill(context, x + 3, y + 3, x + 7, y + 4, 0xAA10151A);
    }
    public static void copyGlyph(Object context, int x, int y, int color) {
        fill(context, x + 3, y + 1, x + 9, y + 7, color);
        fill(context, x + 1, y + 3, x + 7, y + 9, color);
        fill(context, x + 3, y + 4, x + 6, y + 5, 0xAA10151A);
    }
    public static void menuGlyph(Object context, int x, int y, int color) {
        fill(context, x + 1, y + 4, x + 3, y + 6, color);
        fill(context, x + 4, y + 4, x + 6, y + 6, color);
        fill(context, x + 7, y + 4, x + 9, y + 6, color);
    }
    public static void compareGlyph(Object context, int x, int y, int color) {
        fill(context, x + 1, y + 2, x + 7, y + 4, color);
        fill(context, x + 6, y + 1, x + 9, y + 5, color);
        fill(context, x + 3, y + 7, x + 9, y + 9, color);
        fill(context, x + 1, y + 6, x + 4, y + 10, color);
    }
    public static void hoverBackground(Object context, int x, int y, int width, int height, boolean hovered) {
        if (hovered) {
            fill(context, x, y, x + width, y + height, 0x4435444E);
        }
    }
    public static void fill(Object context, int x1, int y1, int x2, int y2, int color) {
        if (context == null || x2 <= x1 || y2 <= y1) {
            return;
        }
        try {
            Method fill = resolveFill(context);
            fill.invoke(context, x1, y1, x2, y2, color);
        } catch (Throwable ignored) {
        }
    }
    public static void text(Object context, Object textRenderer, String value, int x, int y, int color) {
        if (context == null || textRenderer == null || value == null) {
            return;
        }
        try {
            Method drawText = resolveText(context, textRenderer);
            drawText.invoke(context, textRenderer, value, x, y, color);
        } catch (Throwable ignored) {
        }
    }
    public static int textWidth(Object textRenderer, String value) {
        if (textRenderer == null || value == null) {
            return 0;
        }
        try {
            Method width = widthMethod;
            if (width == null || textRendererClass != textRenderer.getClass()) {
                synchronized (TierUi.class) {
                    if (widthMethod == null || textRendererClass != textRenderer.getClass()) {
                        textRendererClass = textRenderer.getClass();
                        widthMethod = textRendererClass.getMethod("method_1727", String.class);
                    }
                    width = widthMethod;
                }
            }
            return ((Number) width.invoke(textRenderer, value)).intValue();
        } catch (Throwable ignored) {
            return value.length() * 6;
        }
    }
    private static Method resolveFill(Object context) throws NoSuchMethodException {
        Method fill = fillMethod;
        if (fill != null && contextClass == context.getClass()) {
            return fill;
        }
        synchronized (TierUi.class) {
            if (fillMethod == null || contextClass != context.getClass()) {
                contextClass = context.getClass();
                fillMethod = contextClass.getMethod( "method_25294", int.class, int.class, int.class, int.class, int.class);
            }
            return fillMethod;
        }
    }
    private static Method resolveText(Object context, Object textRenderer) throws NoSuchMethodException {
        Method drawText = textMethod;
        if (drawText != null && contextClass == context.getClass() && textRendererClass == textRenderer.getClass()) {
            return drawText;
        }
        synchronized (TierUi.class) {
            if (contextClass != context.getClass()) {
                contextClass = context.getClass();
                fillMethod = null;
                textMethod = null;
            }
            if (textRendererClass != textRenderer.getClass()) {
                textRendererClass = textRenderer.getClass();
                widthMethod = null;
                textMethod = null;
            }
            if (textMethod == null) {
                textMethod = contextClass.getMethod( "method_25303", textRendererClass, String.class, int.class, int.class, int.class);
            }
            return textMethod;
        }
    }
}
