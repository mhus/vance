package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.kit.KitTreeHash;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Loads a kit out of another project of the same tenant — one that is itself
 * a kit source, i.e. carries {@code _vance/kits/manifest.yaml}.
 *
 * <p>Addressed as {@code project:<name>}. No token, no clone, no network: the
 * tree is written straight out of the database by {@link KitTreeWriter}, the
 * very writer the export uses, so a kit installed from a project and the same
 * kit exported to git and installed from there are the same kit.
 *
 * <h2>The one thing this loader has that the others do not</h2>
 *
 * <p>Git, folder, library and Ode are all <em>outside</em>. They authorize
 * with a credential, and whether the caller should be seeing that content is
 * the far end's problem. A project is inside: this would otherwise be a way
 * to read any project's documents and settings by installing it as a kit into
 * one you do have access to. So the source project is enforced —
 * {@code Project READ} against the acting person, exactly as
 * {@code ForeignAccessSupport} does for the {@code foreign_doc_*} family.
 *
 * <p>The check is here rather than at the call site, against the rule that
 * enforcement belongs to callers, for a concrete reason: an {@code inherits:}
 * entry may also name a project, and those are discovered while resolving.
 * A caller cannot enforce what it has not seen yet, and every inherit layer
 * passes through {@link KitSourceLoaders#loadFrom} on its own.
 *
 * <h2>Credentials travel as ciphertext</h2>
 *
 * <p>Both ends read the same server key, so there is no reason to produce the
 * plaintext at all — see {@link de.mhus.vance.api.kit.KitSecretEncoding#SERVER}.
 * That also avoids the one exposure a vault passphrase could not have
 * prevented here anyway: the tree is built in a temporary directory on the
 * pod's disk.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectKitSourceLoader implements KitSourceLoader {

    private final ProjectService projectService;
    private final KitRecordStore recordStore;
    private final KitTreeWriter treeWriter;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override
    public boolean supports(KitSourceType type) {
        return type == KitSourceType.PROJECT;
    }

    @Override
    public KitRepoLoader.LoadedKit load(
            KitInheritDto source, KitSourceDto config, KitAccess access, Path target) {

        String sourceProject = projectNameOf(source.getUrl());
        // A project holds exactly one kit, so there is no sub-path to address
        // and no revision to pin. Refused rather than ignored: (url, path) is
        // the identity of an installation, so a silently-dropped path would
        // make two installs of the same thing look like two different kits.
        if (source.getPath() != null && !source.getPath().isBlank()) {
            throw new KitException("a project source takes no path — '" + source.getUrl()
                    + "' already names exactly one kit");
        }
        // A commit is ignored rather than refused, unlike the path above, and
        // the difference is what the field would mean. A path changes *which*
        // kit is addressed, so dropping one silently would make one
        // installation look like two. A commit addresses a version, and a
        // project has no versions — so it is not a conflicting instruction but
        // a meaningless one. Refusing it broke `reapply`, which deliberately
        // hands the recorded commit back ("write this again as it was") rather
        // than asking for anything newer. Folder sources have the same property
        // and KitRepoLoader ignores their commit for the same reason.
        if (sourceProject.equals(access.projectId())) {
            throw new KitException("project '" + sourceProject
                    + "' cannot install its own kit into itself");
        }

        ProjectDocument project = projectService
                .findByTenantAndName(access.tenantId(), sourceProject)
                .orElseThrow(() -> new KitException("project '" + sourceProject
                        + "' not found in tenant '" + access.tenantId() + "'"));
        if (project.getKind() == ProjectKind.SYSTEM) {
            throw new KitException("project '" + sourceProject
                    + "' is a SYSTEM project and cannot serve as a kit source");
        }
        permissionService.enforce(
                contextFactory.forToolSubject(access.tenantId(), access.actor()),
                new Resource.Project(access.tenantId(), project.getName()),
                Action.READ);

        KitManifestDto manifest = recordStore.loadManifest(access.tenantId(), project.getName());
        if (manifest == null) {
            throw new KitException("project '" + sourceProject + "' is not a kit source —"
                    + " it has no " + KitRecordStore.MANIFEST_PATH + ". Promote an installed kit"
                    + " there, or install into it with writeManifest, before using it as one");
        }

        KitTreeWriter.SecretMode secretMode = access.copySecrets()
                ? KitTreeWriter.SecretMode.SERVER
                : KitTreeWriter.SecretMode.SKIP;
        KitTreeWriter.Written written = treeWriter.write(
                access.tenantId(), project.getName(), manifest, target, secretMode,
                /*vaultPassword*/ null);
        if (!written.skippedSettings().isEmpty()) {
            log.info("Kit from project '{}/{}' ships without {} credential(s) on request: {}",
                    access.tenantId(), project.getName(),
                    written.skippedSettings().size(), written.skippedSettings());
        }

        // The descriptor as the tree states it, read back rather than passed
        // through: the writer lays the manifest's fields over the authored
        // kit.yaml, and everything downstream must see the same file the
        // install will consume.
        KitRepoLoader.LoadedKit loaded = KitRepoLoader.readTree(
                target, target, treeHash(target), /*fromFolder*/ true);
        log.info("Loaded kit '{}' from project '{}/{}' ({} document(s), {} setting(s))",
                loaded.descriptor().getName(), access.tenantId(), project.getName(),
                written.documents().size(), written.settings().size());
        return loaded;
    }

    /**
     * A content hash standing in for the commit.
     *
     * <p>{@code origin.commit} is "which version is installed", and a project
     * has no revisions to name — but the question an update asks ("has this
     * changed since?") still needs an answer. The canonical tree hash gives
     * one that moves exactly when the kit does, and it is the same hash a
     * signature is computed over, so there is one definition of "this tree"
     * rather than two.
     */
    private static String treeHash(Path root) {
        return KitTreeHash.of(root);
    }

    /** {@code project:alpha} → {@code alpha}. */
    static String projectNameOf(String url) {
        String trimmed = url.trim();
        if (!trimmed.startsWith(KitSourceType.PROJECT_SCHEME)) {
            throw new KitException("not a project kit source url: " + url);
        }
        String name = trimmed.substring(KitSourceType.PROJECT_SCHEME.length()).trim();
        // Leading slashes tolerated so `project://alpha` and `project:/alpha`
        // do not become a project literally named "/alpha" — the scheme has no
        // authority component, and a typo here would otherwise read as "no
        // such project" rather than as a malformed url.
        while (name.startsWith("/")) name = name.substring(1);
        if (name.isBlank()) {
            throw new KitException("project kit source url names no project: " + url);
        }
        if (name.contains("/")) {
            throw new KitException("project kit source url must name one project, got: " + url);
        }
        return name;
    }
}
