package dev.gsr.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.gsr.ui.GsrOptionsScreen;

/** Mod Menu "Configure" button. Declared as the "modmenu" entrypoint: never loaded without Mod Menu. */
public final class ModMenuCompat implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return GsrOptionsScreen::new;
    }
}
