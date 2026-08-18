package com.tierlookup.client;

/** Fallback logger for failures that occur before the game logger is available. */
public final class BootstrapLog {
    private BootstrapLog() {
    }

    public static void error(String where, Throwable error) {
        if(error==null) {
            System.err.println("[TierLookup] "+where);
            return;
        }
        String message=String.valueOf(error.getMessage());
        System.err.println("[TierLookup] "+where+": "+error.getClass().getSimpleName()+(message.isBlank()?"":": "+message));
    }
}
