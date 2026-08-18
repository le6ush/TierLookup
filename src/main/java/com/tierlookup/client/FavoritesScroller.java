package com.tierlookup.client;

/** Pure UI state for the vertically scrolling Favorites viewport. */
public final class FavoritesScroller {
    public static final int VIEWPORT_ROWS=7;
    private int offset;
    public int offset() {
        return offset;
    }
    public void reset() {
        offset=0;
    }
    public int clamp(int size) {
        int max=maxOffset(size);
        if(offset>max)offset=max;
        if(offset<0)offset=0;
        return offset;
    }
    public boolean scroll(int delta, int size) {
        int before=clamp(size);
        offset=Math.max(0, Math.min(maxOffset(size), before+delta));
        return offset!=before||size>VIEWPORT_ROWS;
    }
    public static int maxOffset(int size) {
        return Math.max(0, size-VIEWPORT_ROWS);
    }
}
