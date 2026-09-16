package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link SkillLoader#readSkillFile} — the sibling read behind the designer
 * app's skill-style previews. The contract that matters: the file comes
 * from the same cascade tier as the {@code SKILL.md} (never re-cascaded),
 * a traversal-shaped path is refused outright, and an unknown skill
 * answers empty instead of throwing.
 */
class SkillLoaderReadSkillFileTest {

    private static final String TENANT = "acme";
    private static final String ENTRY = "_vance/skills/sample/SKILL.md";
    private static final String STYLE = "_vance/skills/sample/style.css";

    private final DocumentService documentService = mock(DocumentService.class);
    private final SkillLoader loader = new SkillLoader(documentService);

    @Test
    void readsSiblingFromTheBundledResourceTier() {
        when(documentService.lookupCascade(TENANT, "p1", ENTRY))
                .thenReturn(Optional.of(new LookupResult(ENTRY, "raw", LookupResult.Source.RESOURCE, null)));

        Optional<String> css = loader.readSkillFile(TENANT, null, "p1", "sample", "style.css");

        assertThat(css).isPresent();
        assertThat(css.get()).contains("fixture-style-marker");
    }

    @Test
    void readsSiblingFromTheUserTierWithOwnProjectOnly() {
        String userProject = HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + "alice";
        DocumentDocument skillDoc = new DocumentDocument();
        DocumentDocument styleDoc = new DocumentDocument();
        when(documentService.findByPath(TENANT, userProject, ENTRY)).thenReturn(Optional.of(skillDoc));
        when(documentService.readContent(skillDoc)).thenReturn("raw");
        when(documentService.findByPath(TENANT, userProject, STYLE)).thenReturn(Optional.of(styleDoc));
        when(documentService.readContent(styleDoc)).thenReturn("user-css");

        Optional<String> css = loader.readSkillFile(TENANT, "alice", "p1", "sample", "style.css");

        assertThat(css).contains("user-css");
        verify(documentService).findByPath(TENANT, userProject, STYLE);
    }

    @Test
    void vanceTierReadsFromTheTenantProject_notTheCallingProject() {
        when(documentService.lookupCascade(TENANT, "p1", ENTRY))
                .thenReturn(Optional.of(new LookupResult(ENTRY, "raw", LookupResult.Source.VANCE, null)));
        when(documentService.findByPath(eq(TENANT), eq(HomeBootstrapService.TENANT_PROJECT_NAME), eq(STYLE)))
                .thenReturn(Optional.empty());

        assertThat(loader.readSkillFile(TENANT, "bob", "p1", "sample", "style.css"))
                .isEmpty();

        // Tier pinning: the VANCE hit must read from the tenant system
        // project, never from the calling project's same-named file.
        verify(documentService).findByPath(TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, STYLE);
        verify(documentService, org.mockito.Mockito.never()).findByPath(eq(TENANT), eq("p1"), any());
    }

    @Test
    void unknownSkillAnswersEmpty() {
        when(documentService.lookupCascade(TENANT, "p1", ENTRY)).thenReturn(Optional.empty());

        assertThat(loader.readSkillFile(TENANT, null, "p1", "sample", "style.css"))
                .isEmpty();
    }

    @Test
    void traversalShapedPathIsRefusedBeforeAnyLookup() {
        assertThat(loader.readSkillFile(TENANT, null, "p1", "sample", "../other/style.css"))
                .isEmpty();
        assertThat(loader.readSkillFile(TENANT, null, "p1", "sample", "/etc/passwd"))
                .isEmpty();
        assertThat(loader.readSkillFile(TENANT, null, "p1", "sample", "a\\b.css"))
                .isEmpty();
    }
}
