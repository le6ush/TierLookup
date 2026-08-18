package com.tierlookup.core;

/** Explicit high-level client state. States are orthogonal to rendering animations. */
public enum AppState {
    IDLE, TARGET, SEARCH, COMPARE, SYNC, KIT_SESSION, FULL_MODE
}
