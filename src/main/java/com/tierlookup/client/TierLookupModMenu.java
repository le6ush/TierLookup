package com.tierlookup.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu entrypoint. Mod Menu is optional at runtime; Fabric only asks for this entrypoint when it is present. */
public final class TierLookupModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TierLookupConfigScreen::new;
    }
}
