package de.mhus.vance.brain.webgrab;

import de.mhus.vance.brain.access.IntegrationScopeProfile;
import de.mhus.vance.brain.access.IntegrationSurface;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scope profile for a "save this page as a document" integration.
 *
 * <p>Exactly one surface, and that is the argument for the endpoint existing.
 * The generic {@code POST /documents} and {@code /documents/upload} would do
 * the same job — and a profile naming them would hand a browser extension the
 * right to create any document at any path in the project, plus the obligation
 * to convert HTML itself.
 *
 * <p>Notably absent: any way to <em>read</em>. A grab writes what the browser
 * already has; it never needs to see what is already in the project. That
 * asymmetry is why this profile and {@code links-capture} stay separate rather
 * than becoming one "browser extension" profile — a token can carry both, and
 * then the union is the person's decision instead of a decision baked into a
 * profile nobody re-reads.
 */
@Component
public class WebGrabScopeProfile implements IntegrationScopeProfile {

    @Override
    public String id() {
        return "web-grab";
    }

    @Override
    public String label() {
        return "Save pages as documents";
    }

    @Override
    public List<IntegrationSurface> surfaces() {
        return List.of(IntegrationSurface.of("POST", "/grab"));
    }
}
