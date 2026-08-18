package com.tierlookup.client.ui;

import java.util.UUID;

import com.tierlookup.model.PlayerIdentity;

public final class PlayerNotePanel {
    public static final int DISPLAY_HEIGHT = 20;
    public static final int EDIT_HEIGHT = 27;
    private final PlayerNoteEditor editor = new PlayerNoteEditor();
    public boolean editing() {
        return editor.active();
    }
    public boolean editing(UUID playerId) {
        return editor.activeFor(playerId);
    }
    public PlayerIdentity player() {
        return editor.player();
    }
    public String value() {
        return editor.value();
    }
    public void begin(PlayerIdentity player, String value) {
        editor.begin(player, value);
    }
    public void clear() {
        editor.clear();
    }
    public boolean handleChar(Object input) {
        return editor.handleChar(input);
    }
    public PlayerNoteEditor.Result handleKey(Object input) {
        return editor.handleKey(input);
    }
    public boolean click(double mouseX, double mouseY, Object textRenderer) {
        return editor.click(mouseX, mouseY, textRenderer);
    }
    public int height(UUID playerId, String note) {
        if (editing(playerId)) {
            return EDIT_HEIGHT;
        }
        return note == null || note.isBlank() ? 0 : DISPLAY_HEIGHT;
    }
    public int draw( Object context, Object textRenderer, int x, int y, int width, UUID playerId, String note) {
        if (editing(playerId)) {
            return editor.draw(context, textRenderer, x, y, width);
        }
        if (note == null || note.isBlank()) {
            return 0;
        }
        TierUi.fill(context, x, y, x + width, y + DISPLAY_HEIGHT, 0xEE171D23);
        TierUi.noteGlyph(context, x + 4, y + 5, 0xFFFFD76A);
        TierUi.text( context, textRenderer, fit(textRenderer, note, Math.max(20, width - 23)), x + 17, y + 6, 0xFFE5E9EC);
        return DISPLAY_HEIGHT;
    }
    private static String fit(Object textRenderer, String value, int width) {
        if (TierUi.textWidth(textRenderer, value) <= width) {
            return value;
        }
        String ellipsis = "…";
        int target = Math.max(0, width - TierUi.textWidth(textRenderer, ellipsis));
        int end = value.length();
        while (end > 0 && TierUi.textWidth(textRenderer, value.substring(0, end)) > target) {
            end = value.offsetByCodePoints(end, -1);
        }
        return value.substring(0, end) + ellipsis;
    }
}
