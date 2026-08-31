package de.mhus.vance.addon.brain.links;

import de.mhus.vance.brain.access.IntegrationScopeProfile;
import de.mhus.vance.brain.access.IntegrationSurface;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scope profile for a "save this page to my link list" integration — a browser
 * extension, a share sheet, a shell alias.
 *
 * <p>Exactly the three capture routes, which is a narrower grant than it looks:
 * a token holding this profile <b>cannot read the list</b>. It can ask about one
 * URL it already knows, ask for the group names, and save. That is the whole
 * capability, and it maps one-to-one onto what such a tool does — badge,
 * dropdown, save.
 *
 * <p>Notably absent:
 *
 * <ul>
 *   <li><b>{@code GET /scan}</b> — the full list. A capture tool asked for it
 *       only ever to answer "is this page already saved", and
 *       {@code /entry/lookup} answers that with one row. Handing over every
 *       entry to get one bit was the wrong grant and the wrong shape.</li>
 *   <li><b>{@code POST /entry}</b> and its {@code PATCH}/{@code DELETE}
 *       siblings — the same path, separated only by the method. This is why
 *       {@link IntegrationSurface} carries the method at all: a path-only
 *       profile would hand a capture tool the delete button.</li>
 *   <li><b>{@code POST /groups}</b> — the same path as reading them, one verb
 *       apart, and it rewrites the headings. Reading a dropdown's contents must
 *       not carry the right to redefine them.</li>
 *   <li><b>{@code /entry/viewed}</b> — a capture tool ticks nothing off.
 *       Nothing it knows says a human read a page.</li>
 *   <li><b>{@code /reorder}, {@code /group/rename}, {@code /rebuild}</b> —
 *       curation, done by the person in the app.</li>
 * </ul>
 *
 * <p>Capture is idempotent on the URL and reports which of the two happened, so
 * a tool stays correct when the same page is saved twice without ever needing a
 * delete.
 */
@Component
public class LinksCaptureScopeProfile implements IntegrationScopeProfile {

    @Override
    public String id() {
        return "links-capture";
    }

    @Override
    public String label() {
        return "Save links to a link list";
    }

    @Override
    public List<IntegrationSurface> surfaces() {
        return List.of(
                IntegrationSurface.of("GET", "/addon/links/groups"),
                IntegrationSurface.of("GET", "/addon/links/entry/lookup"),
                IntegrationSurface.of("POST", "/addon/links/capture"));
    }
}
