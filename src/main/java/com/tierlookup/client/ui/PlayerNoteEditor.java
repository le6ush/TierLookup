package com.tierlookup.client.ui;

import java.lang.reflect.Method;

import com.tierlookup.client.MinecraftBridge;
import com.tierlookup.model.PlayerIdentity;

public final class PlayerNoteEditor {
    public enum Result {
        NONE, SAVE, CANCEL
    }
    private static final int MAX_LENGTH = 240;
    private PlayerIdentity player;
    private String value = "";
    private int cursor;
    private int anchor;
    private int fieldX = -1;
    private int fieldY = -1;
    private int fieldWidth;
    private int fieldHeight;
    public boolean active() {
        return player != null;
    }
    public boolean activeFor(java.util.UUID playerId) {
        return active() && playerId != null && playerId.equals(player.uuid());
    }
    public PlayerIdentity player() {
        return player;
    }
    public String value() {
        return value;
    }
    public void begin(PlayerIdentity player, String initialValue) {
        this.player = player;
        value = initialValue == null ? "" : initialValue;
        cursor = value.length();
        anchor = cursor;
        clearBounds();
    }
    public void clear() {
        player = null;
        value = "";
        cursor = 0;
        anchor = 0;
        clearBounds();
    }
    public boolean handleChar(Object input) {
        if (!active() || input == null) {
            return false;
        }
        try {
            boolean valid = (boolean) input.getClass().getMethod("method_74227").invoke(input);
            if (!valid) {
                return true;
            }
            String text = String.valueOf(input.getClass().getMethod("method_74226").invoke(input));
            if (!text.isEmpty()) {
                replaceSelection(text);
            }
        } catch (Throwable ignored) {
            // Input is version-adapted; an unsupported event is simply consumed while editing.
        }
        return true;
    }
    public Result handleKey(Object input) {
        if (!active() || input == null) {
            return Result.NONE;
        }
        int key;
        try {
            key = ((Number) input.getClass().getMethod("method_74228").invoke(input)).intValue();
        } catch (Throwable ignored) {
            return Result.NONE;
        }
        boolean controlDown = controlDown();
        boolean shiftDown = shiftDown();
        if (key == 256) {
            return Result.CANCEL;
        }
        if (key == 257 || key == 335) {
            return Result.SAVE;
        }
        if (controlDown && key == 65) {
            anchor = 0;
            cursor = value.length();
            return Result.NONE;
        }
        if (controlDown && key == 67) {
            copyToClipboard(selectedText());
            return Result.NONE;
        }
        if (controlDown && key == 88) {
            copyToClipboard(selectedText());
            deleteSelection();
            return Result.NONE;
        }
        if (controlDown && key == 86) {
            replaceSelection(readClipboard());
            return Result.NONE;
        }
        if (key == 259) {
            backspace();
            return Result.NONE;
        }
        if (key == 261) {
            deleteForward();
            return Result.NONE;
        }
        if (key == 263) {
            moveCursor(previousIndex(cursor), shiftDown);
            return Result.NONE;
        }
        if (key == 262) {
            moveCursor(nextIndex(cursor), shiftDown);
            return Result.NONE;
        }
        if (key == 268) {
            moveCursor(0, shiftDown);
            return Result.NONE;
        }
        if (key == 269) {
            moveCursor(value.length(), shiftDown);
            return Result.NONE;
        }
        return Result.NONE;
    }
    public boolean click(double mouseX, double mouseY, Object textRenderer) {
        if (!active() || textRenderer == null || !contains(mouseX, mouseY)) {
            return false;
        }
        int targetX = (int) Math.round(mouseX) - (fieldX + 5);
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i <= value.length(); i++) {
            int distance = Math.abs(textWidth(textRenderer, value.substring(0, i)) - targetX);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        cursor = bestIndex;
        anchor = bestIndex;
        return true;
    }
    public int draw(Object context, Object textRenderer, int x, int y, int width) {
        if (!active() || context == null || textRenderer == null) {
            return 0;
        }
        try {
            Method fill = context.getClass().getMethod( "method_25294", int.class, int.class, int.class, int.class, int.class);
            Method drawText = context.getClass().getMethod( "method_25303", textRenderer.getClass(), String.class, int.class, int.class, int.class);
            int height = 27;
            fieldX = x;
            fieldY = y;
            fieldWidth = Math.max(60, width);
            fieldHeight = height;
            fill.invoke(context, x, y, x + width, y + height, 0xEE171D23);
            fill.invoke(context, x, y, x + width, y + 1, 0xFF66D9EF);
            int textX = x + 5;
            int availableWidth = Math.max(10, width - 10);
            int firstVisible = 0;
            while (firstVisible < value.length() && textWidth(textRenderer, value.substring(firstVisible)) > availableWidth) {
                firstVisible = nextIndex(firstVisible);
            }
            int selectionStart = Math.min(cursor, anchor);
            int selectionEnd = Math.max(cursor, anchor);
            if (selectionStart != selectionEnd) {
                int visibleStart = Math.max(firstVisible, selectionStart);
                int visibleEnd = Math.max(firstVisible, selectionEnd);
                if (visibleEnd > visibleStart) {
                    int left = textWidth(textRenderer, value.substring(firstVisible, visibleStart));
                    int right = textWidth(textRenderer, value.substring(firstVisible, visibleEnd));
                    fill.invoke( context, textX + left, y + 7, Math.min(x + width - 5, textX + right), y + 20, 0x88498DB4);
                }
            }
            drawText.invoke(context, textRenderer, value.substring(firstVisible), textX, y + 9, 0xFFE8EDF1);
            if (cursorVisible() && cursor >= firstVisible) {
                int caretX = Math.min( x + width - 5, textX + textWidth(textRenderer, value.substring(firstVisible, cursor)));
                fill.invoke(context, caretX, y + 6, caretX + 1, y + 21, 0xFFFFFFFF);
            }
            return height;
        } catch (Throwable ignored) {
            return 0;
        }
    }
    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= fieldX && mouseX < fieldX + fieldWidth && mouseY >= fieldY && mouseY < fieldY + fieldHeight;
    }
    private void backspace() {
        if (deleteSelection() || cursor <= 0) {
            return;
        }
        int previous = previousIndex(cursor);
        value = value.substring(0, previous) + value.substring(cursor);
        cursor = previous;
        anchor = cursor;
    }
    private void deleteForward() {
        if (deleteSelection() || cursor >= value.length()) {
            return;
        }
        int next = nextIndex(cursor);
        value = value.substring(0, cursor) + value.substring(next);
        anchor = cursor;
    }
    private void replaceSelection(String replacement) {
        if (replacement == null || replacement.isEmpty()) {
            return;
        }
        deleteSelection();
        int room = MAX_LENGTH - value.length();
        if (room <= 0) {
            return;
        }
        String accepted = replacement.length() <= room ? replacement : replacement.substring(0, room);
        value = value.substring(0, cursor) + accepted + value.substring(cursor);
        cursor += accepted.length();
        anchor = cursor;
    }
    private boolean deleteSelection() {
        int start = Math.min(cursor, anchor);
        int end = Math.max(cursor, anchor);
        if (start == end) {
            return false;
        }
        value = value.substring(0, start) + value.substring(end);
        cursor = start;
        anchor = start;
        return true;
    }
    private String selectedText() {
        int start = Math.min(cursor, anchor);
        int end = Math.max(cursor, anchor);
        return start == end ? "" : value.substring(start, end);
    }
    private void moveCursor(int next, boolean keepSelection) {
        cursor = Math.max(0, Math.min(value.length(), next));
        if (!keepSelection) {
            anchor = cursor;
        }
    }
    private int previousIndex(int index) {
        if (index <= 0) {
            return 0;
        }
        return value.offsetByCodePoints(index, -1);
    }
    private int nextIndex(int index) {
        if (index >= value.length()) {
            return value.length();
        }
        return value.offsetByCodePoints(index, 1);
    }
    private void clearBounds() {
        fieldX = -1;
        fieldY = -1;
        fieldWidth = 0;
        fieldHeight = 0;
    }
    private static boolean cursorVisible() {
        return ((System.currentTimeMillis() / 500L) & 1L) == 0L;
    }
    private static int textWidth(Object textRenderer, String text) {
        try {
            return ((Number) textRenderer.getClass().getMethod("method_1727", String.class).invoke(textRenderer, text)).intValue();
        } catch (Throwable ignored) {
            return text == null ? 0 : text.length() * 6;
        }
    }
    private static boolean controlDown() {
        return keyDown(341) || keyDown(345);
    }
    private static boolean shiftDown() {
        return keyDown(340) || keyDown(344);
    }
    private static boolean keyDown(int key) {
        try {
            long window = MinecraftBridge.windowHandle(MinecraftBridge.client());
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            return ((int) glfw.getMethod("glfwGetKey", long.class, int.class).invoke(null, window, key)) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static String readClipboard() {
        try {
            long window = MinecraftBridge.windowHandle(MinecraftBridge.client());
            Object value = Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetClipboardString", long.class).invoke(null, window);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }
    private static void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            long window = MinecraftBridge.windowHandle(MinecraftBridge.client());
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            Method setter;
            try {
                setter = glfw.getMethod("glfwSetClipboardString", long.class, CharSequence.class);
            } catch (NoSuchMethodException ignored) {
                setter = glfw.getMethod("glfwSetClipboardString", long.class, String.class);
            }
            setter.invoke(null, window, text);
        } catch (Throwable ignored) {
        }
    }
}
