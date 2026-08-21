package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Does this project need an owner pod" is derived from the documents that
 * would need one to run. The predicate decides whether the boot self-pull and
 * the master distributor pick a project up, so both directions have to be
 * right — a project that gains a scheduler must be kept alive, and one that
 * loses its last must stop costing a pod slot.
 */
class ProjectOwnerRequirementServiceTest {

    private DocumentService documentService;
    private ProjectService projectService;
    private ProjectOwnerRequirementService service;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        projectService = mock(ProjectService.class);
        service = new ProjectOwnerRequirementService(documentService, projectService);
    }

    @Test
    void backgroundWorkPresent_setsFlag() {
        when(documentService.existsAnyUnderPrefixes(eq("acme"), eq("test1"), anyList(), any()))
                .thenReturn(true);

        assertThat(service.recompute("acme", "test1")).isTrue();
        verify(projectService, times(1)).setOwnerRequired("acme", "test1", true);
    }

    @Test
    void lastBackgroundDocumentGone_clearsFlag() {
        when(documentService.existsAnyUnderPrefixes(eq("acme"), eq("test1"), anyList(), any()))
                .thenReturn(false);

        assertThat(service.recompute("acme", "test1")).isFalse();
        verify(projectService, times(1)).setOwnerRequired("acme", "test1", false);
    }

    @Test
    void podlessProject_isNotTracked() {
        // Podless projects never take a lease, so nothing would read the flag —
        // and their background work lives wherever the WS lands by design.
        assertThat(service.recompute("acme", "_user_marvin")).isFalse();

        verify(documentService, never()).existsAnyUnderPrefixes(any(), any(), anyList(), any());
        verify(projectService, never()).setOwnerRequired(anyString(), anyString(), any(Boolean.class));
    }

    @Test
    void activationSourcePaths_areTheTwoKindsThatWait() {
        // Both arm something that fires without anyone calling in — a pod
        // holding them is the only reason they happen at all.
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(
                "_vance/scheduler/nightly.yaml")).isTrue();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(
                "_vance/hooks/document-changed/tag.yaml")).isTrue();
    }

    @Test
    void reactiveWork_isNotAnActivationSource() {
        // An event trigger arrives from outside, and the pod that takes the
        // call brings the project up then — a cold start on the first trigger
        // instead of a pod slot all year.
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(
                "_vance/events/inbox.yaml")).isFalse();
    }

    @Test
    void kitProvisioning_isNotAnActivationSource() {
        // It runs once at startup and is done — keeping the project placed
        // afterwards buys nothing, and since kits are the ordinary way to set a
        // project up, counting it would pin nearly every project forever.
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(
                "_vance/kits/provisioning.yaml")).isFalse();
    }

    @Test
    void ordinaryPaths_areNotActivationSources() {
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(
                "documents/notes.md")).isFalse();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(
                "_vance/recipes/analyze.yaml")).isFalse();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(
                "_vance/kits/installed/some-kit.yaml")).isFalse();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(null)).isFalse();
    }
}
