package com.tierlookup.client;

/**
* Central compile-time/user-surface feature gates.
*
* Full Mode and tier history are kept for a future redesign, but are not reachable
* from the normal client UI in the current product line. Keeping the gate in one place prevents dormant
* code from accidentally leaking back through a stray action region or shortcut.
*/ public final class ClientFeatures {
    public static final boolean FULL_MODE_VISIBLE=false;
    public static final boolean TIER_HISTORY_VISIBLE=false;
    public static final boolean ENHANCED_TAB_AVAILABLE=true;
    private ClientFeatures() {
    }
}
