package com.tierlookup.client.ui;

import java.util.ArrayList;
import java.util.List;

public final class PlayerQuickMenu {
    public enum Action {
        FAVORITE, NOTE, MESSAGE, COPY_NAME
    }
    public record Layout(int x, int y, int width, int height, int bodyY, List<Action> actions) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
        public Action actionAt(double mouseX, double mouseY) {
            if (!contains(mouseX, mouseY) || mouseY < bodyY) {
                return null;
            }
            int row = (int) ((mouseY - bodyY) / ROW_HEIGHT);
            return row >= 0 && row < actions.size() ? actions.get(row) : null;
        }
    }
    public static final int WIDTH = 142;
    public static final int ROW_HEIGHT = 15;
    private static final int HEADER_HEIGHT = 20;
    private static final int PADDING = 5;
    private PlayerQuickMenu() {
    }
    public static Layout layout( int anchorX, int anchorY, int screenWidth, int screenHeight, boolean notesEnabled) {
        ArrayList<Action> actions = new ArrayList<>();
        actions.add(Action.FAVORITE);
        if (notesEnabled) {
            actions.add(Action.NOTE);
        }
        actions.add(Action.MESSAGE);
        actions.add(Action.COPY_NAME);
        int height = HEADER_HEIGHT + actions.size() * ROW_HEIGHT + PADDING;
        int x = Math.max(4, Math.min(screenWidth - WIDTH - 4, anchorX));
        int y = Math.max(4, Math.min(screenHeight - height - 4, anchorY));
        return new Layout(x, y, WIDTH, height, y + HEADER_HEIGHT, List.copyOf(actions));
    }
    public static Layout draw( Object context,
        Object textRenderer,
        int anchorX,
        int anchorY,
        int screenWidth,
        int screenHeight,
        String playerName,
        boolean favorite,
        boolean notesEnabled,
        boolean russian,
        int mouseX,
        int mouseY,
        int accent) {
        Layout layout = layout(anchorX, anchorY, screenWidth, screenHeight, notesEnabled);
        TierUi.panel(context, layout.x(), layout.y(), layout.width(), layout.height(), 0xF311171C, accent);
        TierUi.text( context, textRenderer, fit(textRenderer, playerName, layout.width() - 14), layout.x() + 7, layout.y() + 7, 0xFFF2F5F7);
        int rowY = layout.bodyY();
        for (Action action : layout.actions()) {
            boolean hovered = mouseX >= layout.x() + 3 && mouseX < layout.x() + layout.width() - 3 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            TierUi.hoverBackground( context, layout.x() + 3, rowY, layout.width() - 6, ROW_HEIGHT, hovered);
            drawAction( context, textRenderer, layout.x() + 7, rowY + 2, action, favorite, russian);
            rowY += ROW_HEIGHT;
        }
        return layout;
    }
    private static void drawAction( Object context, Object textRenderer, int x, int y, Action action, boolean favorite, boolean russian) {
        int textX = x + 15;
        switch (action) {
            case FAVORITE -> {
                String star = favorite ? "★" : "☆";
                TierUi.text(context, textRenderer, star, x, y + 1, favorite ? 0xFFFFD76A : 0xFF9AA5AD);
                TierUi.text( context,
                    textRenderer,
                    favorite ? (russian ? "Убрать из избранного" : "Remove favorite") : (russian ? "В избранное" : "Favorite"),
                    textX,
                    y + 1,
                    0xFFE7ECEF);
            }
            case NOTE -> {
                TierUi.noteGlyph(context, x, y, 0xFFFFD76A);
                TierUi.text(context, textRenderer, russian ? "Заметка" : "Note", textX, y + 1, 0xFFE7ECEF);
            }
            case MESSAGE -> {
                TierUi.messageGlyph(context, x, y, 0xFF8EDFF0);
                TierUi.text(context, textRenderer, russian ? "Написать" : "Message", textX, y + 1, 0xFFE7ECEF);
            }
            case COPY_NAME -> {
                TierUi.copyGlyph(context, x, y, 0xFFAAB5BD);
                TierUi.text( context, textRenderer, russian ? "Копировать ник" : "Copy nickname", textX, y + 1, 0xFFE7ECEF);
            }
        }
    }
    private static String fit(Object textRenderer, String value, int width) {
        String text = value == null ? "" : value;
        if (TierUi.textWidth(textRenderer, text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int target = Math.max(0, width - TierUi.textWidth(textRenderer, ellipsis));
        int end = text.length();
        while (end > 0 && TierUi.textWidth(textRenderer, text.substring(0, end)) > target) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end) + ellipsis;
    }
}
