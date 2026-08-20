package de.mhus.vance.addon.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication.PromptInjectContext;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The app-context block the engine sees for an open feed.
 *
 * <p>The interesting part is the marked entry: „look at the selected one" is a
 * sentence the model can only act on if the turn already told it which one.
 * Asking the browser instead would block the sampling loop on a tab that may
 * be asleep.
 */
class FeedsApplicationPromptInjectTest {

    private static final String MANIFEST = """
            $meta:
              kind: application
              app: feeds
            title: News
            feeds:
              streams:
              - source: hrafnagud
              pageSize: 20
            """;

    private final DocumentService documentService = mock(DocumentService.class);
    private FeedsApplication application;

    @BeforeEach
    void setUp() {
        application = new FeedsApplication(
                documentService, mock(DocumentLinkBuilder.class),
                mock(SecurityContextFactory.class));
        DocumentDocument doc = new DocumentDocument();
        doc.setMimeType("application/yaml");
        when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(any())).thenReturn(MANIFEST);
    }

    @Test
    void promptInject_namesTheMarkedEntryAndHowToReadIt() {
        String block = application.promptInject(new PromptInjectContext(
                "acme", "news", "apps/feeds", null, null,
                "hrafnagud/6a86 — Verstappen commits to Red Bull"));

        assertThat(block).contains("Verstappen commits to Red Bull");
        assertThat(block).contains("feed_item");
    }

    @Test
    void promptInject_withoutASelection_saysNothingAboutOne() {
        String block = application.promptInject(new PromptInjectContext(
                "acme", "news", "apps/feeds", null, null, null));

        assertThat(block).contains("hrafnagud");
        assertThat(block).doesNotContain("marked");
        assertThat(block).doesNotContain("feed_item");
    }
}
