package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitSourceProjectDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Which projects of this tenant can be installed from.
 *
 * <p>The counterpart to {@link KitLibraryService}, which answers the same
 * question for a library: what is on offer, before anyone has typed a url.
 * Kept apart from {@link KitService} because it is a query and owns nothing —
 * and because it needs {@link DocumentService} and {@link PermissionService},
 * neither of which the install pipeline has any business holding.
 *
 * <p>Answered from the presence of a well-known document rather than from a
 * registry: being a kit source <em>is</em> having
 * {@code _vance/kits/manifest.yaml}, so a list built any other way could
 * disagree with what the loader will actually accept.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitSourceProjectService {

    private final DocumentService documentService;
    private final KitRecordStore recordStore;
    private final ProjectService projectService;
    private final PermissionService permissionService;

    /**
     * Every kit-source project {@code subject} may read, kit name first.
     *
     * <p>Three kinds of entry are left out, each for its own reason:
     *
     * <ul>
     *   <li><b>Projects the subject cannot read.</b> Filtered per hit, not by
     *       the endpoint's own gate — otherwise the list itself would disclose
     *       which projects exist. Same rule the loader enforces when the
     *       install actually runs, so the picker cannot offer something that
     *       is then refused.</li>
     *   <li><b>Kits that refuse a direct install</b> — {@code installable:
     *       false} (an abstract base, usable only through {@code inherits:})
     *       or {@code artifact: true} (a tuning bundle, apply-only). Listing
     *       those means offering an option that has to fail. This is the kit's
     *       own statement about itself, read from the authored descriptor; it
     *       is deliberately <em>not</em> a "draft" filter, because installing
     *       an unfinished kit to try it out is the whole point of installing
     *       from a project rather than from git.</li>
     *   <li><b>SYSTEM projects.</b> {@code _vance} and the per-user hubs are
     *       not somebody's authoring workspace, and the loader refuses them
     *       anyway.</li>
     * </ul>
     */
    public List<KitSourceProjectDto> list(String tenantId, SecurityContext subject) {
        List<KitSourceProjectDto> out = new ArrayList<>();
        for (DocumentDocument marker : documentService.findByPathAcrossProjects(
                tenantId, KitRecordStore.MANIFEST_PATH)) {
            String projectId = marker.getProjectId();
            if (projectId == null || projectId.isBlank()) continue;

            Optional<ProjectDocument> project =
                    projectService.findByTenantAndName(tenantId, projectId);
            if (project.isEmpty() || project.get().getKind() == ProjectKind.SYSTEM) continue;
            if (!permissionService.check(
                    subject, new Resource.Project(tenantId, projectId), Action.READ)) {
                continue;
            }

            KitManifestDto manifest = recordStore.loadManifest(tenantId, projectId);
            if (manifest == null || manifest.getKit() == null
                    || manifest.getKit().getName() == null) {
                // The marker exists but does not parse, or says nothing usable.
                // loadManifest already logged it; a broken source belongs out
                // of a picker rather than in it as an entry that cannot work.
                continue;
            }
            if (!directInstallAllowed(tenantId, projectId)) continue;

            out.add(KitSourceProjectDto.builder()
                    .kitName(manifest.getKit().getName())
                    .kitDescription(manifest.getKit().getDescription())
                    .version(manifest.getKit().getVersion())
                    .projectId(projectId)
                    .projectTitle(project.get().getTitle())
                    .sourceUrl(KitSourceType.PROJECT_SCHEME + projectId)
                    .build());
        }
        out.sort(Comparator.comparing(KitSourceProjectDto::getKitName)
                .thenComparing(KitSourceProjectDto::getProjectId));
        return out;
    }

    /**
     * Whether the kit in this project says it may be installed directly.
     *
     * <p>An <em>absent</em> descriptor counts as yes. That is the shape of a
     * project promoted before the descriptor was kept as a document: the
     * manifest is there, the flags are not, and hiding it would remove a
     * working source over a missing file. The next update of that kit writes
     * the descriptor and the check starts applying.
     */
    private boolean directInstallAllowed(String tenantId, String projectId) {
        KitDescriptorDto descriptor = recordStore.loadDescriptor(tenantId, projectId);
        if (descriptor == null) return true;
        if (!descriptor.isInstallable() || descriptor.isArtifact()) {
            log.debug("KitSourceProjectService: '{}/{}' left out — installable={} artifact={}",
                    tenantId, projectId, descriptor.isInstallable(), descriptor.isArtifact());
            return false;
        }
        return true;
    }
}
