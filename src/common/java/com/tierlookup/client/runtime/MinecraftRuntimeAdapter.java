package com.tierlookup.client.runtime;

import java.util.List;

import com.tierlookup.model.PlayerIdentity;

/**
* Version-sensitive Minecraft client surface used by TierLookup.
* Keep intermediary/Mojang mapping details in version source sets, never in common UI/service code.
*/ public interface MinecraftRuntimeAdapter {
    record TabEntry(PlayerIdentity player, String displayText, Object styledText, int vanillaIndex) {
        public TabEntry(PlayerIdentity player, String displayText) {
            this(player, displayText, null, 0);
        }
    }
    /** Reports the runtime features provided by the active Minecraft adapter. */
    record RuntimeCapabilities(boolean tabRoster, boolean styledTabNames, boolean controlsKeyLookup, boolean prefilledChat, boolean screenInput) {
        public boolean coreClientUi() {
            return tabRoster&&controlsKeyLookup&&prefilledChat&&screenInput;
        }
    }
    String runtimeId();
    default List<String> supportedVersions() {
        return List.of();
    }
    default boolean supportsVersion(String version) {
        return version!=null&&supportedVersions().contains(version);
    }
    boolean playerListPressed(Object client);
    List<TabEntry> tabEntries(Object client);
    boolean keyBindingInControls(Object client, Object key, String bindingId);
    /** Opens vanilla chat with initial unsent text; used by TAB context actions. */ default boolean openPrefilledChat(Object client, String initial) {
        return false;
    }
    /** Re-grab gameplay mouse after a modal TierLookup screen returned to no Minecraft Screen. */ default boolean restoreGameplayControl(Object client) {
        return false;
    }
    default RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(false, false, false, false, false);
    }
}
