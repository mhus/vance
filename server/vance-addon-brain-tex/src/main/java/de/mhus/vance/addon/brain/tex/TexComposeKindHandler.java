package de.mhus.vance.addon.brain.tex;

import de.mhus.vance.shared.document.kind.KindHandler;
import org.springframework.stereotype.Service;

/**
 * Registers the {@code tex-compose} document kind into the central
 * {@link de.mhus.vance.shared.document.KindRegistry}. Picked up by
 * Spring via the {@link TexAddon} component-scan.
 *
 * <p>The {@code tex-compose} document is a YAML build manifest parsed by
 * {@link TexService}; this class just stamps {@code "tex-compose"} as a
 * known kind so {@code doc_create(kind="tex-compose", …)} resolves
 * cleanly and the kind isn't treated as a typo by the fuzzy resolver.
 *
 * <p>Moved out of {@code vance-shared} {@code BuiltInKindHandlers} as
 * part of the addon extraction — see {@code planning/tex-addon-extraction.md}.
 */
@Service
public class TexComposeKindHandler implements KindHandler {

    @Override
    public String getName() {
        return "tex-compose";
    }
}
