package de.mhus.vance.brain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
    private final List<ProjectOwnerRequirementContributor> contributors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        projectService = mock(ProjectService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProjectOwnerRequirementContributor> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> contributors.stream());
        service = new ProjectOwnerRequirementService(documentService, projectService, provider);
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
    void contributorWithWaitingWork_setsFlagWithoutAnyDocument() {
        when(documentService.existsAnyUnderPrefixes(eq("acme"), eq("_user__trillian-x"), anyList(), any()))
                .thenReturn(false);
        contributors.add((tenant, project) -> "_user__trillian-x".equals(project));

        assertThat(service.recompute("acme", "_user__trillian-x")).isTrue();
        verify(projectService).setOwnerRequired("acme", "_user__trillian-x", true);
    }

    @Test
    void tenantProject_ignoresContributorsToo() {
        contributors.add((tenant, project) -> true);

        assertThat(service.recompute("acme", "_tenant")).isFalse();
    }

    @Test
    void tenantProject_neverRequiresAnOwner() {
        // Its scheduler and hook entries are cascade templates firing in the
        // projects that are up — they must not pin the tenant project, which
        // is never placed.
        assertThat(service.recompute("acme", "_tenant")).isFalse();

        verify(documentService, never()).existsAnyUnderPrefixes(any(), any(), anyList(), any());
        verify(projectService).setOwnerRequired("acme", "_tenant", false);
    }

    @Test
    void userHub_withScheduler_requiresAnOwner() {
        when(documentService.existsAnyUnderPrefixes(eq("acme"), eq("_user_marvin"), anyList(), any()))
                .thenReturn(true);

        assertThat(service.recompute("acme", "_user_marvin")).isTrue();
        verify(projectService).setOwnerRequired("acme", "_user_marvin", true);
    }

    @Test
    void activationSourcePaths_areTheTwoKindsThatWait() {
        // Both arm something that fires without anyone calling in — a pod
        // holding them is the only reason they happen at all.
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath("_vance/scheduler/nightly.yaml"))
                .isTrue();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath("_vance/hooks/document-changed/tag.yaml"))
                .isTrue();
    }

    @Test
    void reactiveWork_isNotAnActivationSource() {
        // An event trigger arrives from outside, and the pod that takes the
        // call brings the project up then — a cold start on the first trigger
        // instead of a pod slot all year.
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath("_vance/events/inbox.yaml"))
                .isFalse();
    }

    @Test
    void kitProvisioning_isNotAnActivationSource() {
        // It runs once at startup and is done — keeping the project placed
        // afterwards buys nothing, and since kits are the ordinary way to set a
        // project up, counting it would pin nearly every project forever.
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath("_vance/kits/provisioning.yaml"))
                .isFalse();
    }

    @Test
    void ordinaryPaths_areNotActivationSources() {
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath("documents/notes.md"))
                .isFalse();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath("_vance/recipes/analyze.yaml"))
                .isFalse();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath("_vance/kits/installed/some-kit.yaml"))
                .isFalse();
        assertThat(ProjectOwnerRequirementService.isActivationSourcePath(null)).isFalse();
    }

    // ─── boot re-check: pin work that predates the contributor ─────

    @Test
    void pinContributedWork_pinsAnUnpinnedProjectWithWork() {
        // A Trillian loop created before loops counted as waiting work: its
        // hub was never pinned, and no event will ever ask again.
        contributors.add(
                contributor(List.of(new ProjectOwnerRequirementContributor.ProjectRef("acme", "_user__trillian-x"))));
        when(projectService.findByTenantAndName("acme", "_user__trillian-x"))
                .thenReturn(java.util.Optional.of(project("_user__trillian-x", false)));
        when(documentService.existsAnyUnderPrefixes(eq("acme"), eq("_user__trillian-x"), anyList(), any()))
                .thenReturn(false);

        assertThat(service.pinContributedWork()).isEqualTo(1);
        verify(projectService).setOwnerRequired("acme", "_user__trillian-x", true);
    }

    @Test
    void pinContributedWork_leavesAlreadyPinnedAndUnknownProjectsAlone() {
        contributors.add(contributor(List.of(
                new ProjectOwnerRequirementContributor.ProjectRef("acme", "pinned"),
                new ProjectOwnerRequirementContributor.ProjectRef("acme", "gone"))));
        when(projectService.findByTenantAndName("acme", "pinned"))
                .thenReturn(java.util.Optional.of(project("pinned", true)));
        when(projectService.findByTenantAndName("acme", "gone")).thenReturn(java.util.Optional.empty());

        assertThat(service.pinContributedWork()).isZero();
        verify(projectService, never()).setOwnerRequired(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void pinContributedWork_oneFailureDoesNotStopTheOthers() {
        contributors.add(contributor(List.of(
                new ProjectOwnerRequirementContributor.ProjectRef("acme", "broken"),
                new ProjectOwnerRequirementContributor.ProjectRef("acme", "hub"))));
        when(projectService.findByTenantAndName("acme", "broken")).thenThrow(new IllegalStateException("mongo"));
        when(projectService.findByTenantAndName("acme", "hub"))
                .thenReturn(java.util.Optional.of(project("hub", false)));

        assertThat(service.pinContributedWork()).isEqualTo(1);
        verify(projectService).setOwnerRequired("acme", "hub", true);
    }

    private static ProjectOwnerRequirementContributor contributor(
            List<ProjectOwnerRequirementContributor.ProjectRef> work) {
        return new ProjectOwnerRequirementContributor() {
            @Override
            public boolean requiresOwner(String tenantId, String projectId) {
                return work.stream()
                        .anyMatch(r ->
                                r.tenantId().equals(tenantId) && r.projectId().equals(projectId));
            }

            @Override
            public List<ProjectRef> projectsWithWork() {
                return work;
            }
        };
    }

    private static de.mhus.vance.shared.project.ProjectDocument project(String name, boolean ownerRequired) {
        de.mhus.vance.shared.project.ProjectDocument doc = new de.mhus.vance.shared.project.ProjectDocument();
        doc.setTenantId("acme");
        doc.setName(name);
        doc.setOwnerRequired(ownerRequired);
        return doc;
    }
}
