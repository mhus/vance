package de.mhus.vance.brain.project;

import de.mhus.vance.api.projects.ProjectCopyReportDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Makes a new project that starts out looking like an existing one: its
 * documents and its settings, nothing else.
 *
 * <h2>Why this is not the maintenance seam</h2>
 *
 * <p>Delete and rename go through {@code ProjectDataHandler} because they have
 * to be <em>complete</em> — a missing handler leaves rows behind that the next
 * project of the same name inherits, and nothing announces that. A copy is the
 * mirror image: it has to be <em>selective</em>, and a forgotten copier is
 * visible the moment somebody looks for the thing that is not there. So the
 * list of what travels is spelled out here, in one place, rather than
 * distributed over twenty-odd handlers that would answer "nothing" each.
 *
 * <p>That also lets the copy run through the ordinary services instead of
 * going at Mongo directly: {@link DocumentService#create} applies the header
 * parse, the kind detection, the lock seed and the write authorization, and
 * {@link SettingService} keeps the audit trail. The maintenance handlers can
 * do none of that — they exist so the admin shell can work without a brain.
 *
 * <h2>What is deliberately left out</h2>
 *
 * <ul>
 *   <li><b>Mounted documents</b> ({@code _ext/}). Their rows carry no
 *       {@code storageId} and their {@code _id} is a hash over the <em>source
 *       project</em>, so a copy would be a dead row under a foreign id. The
 *       mount <em>configuration</em> is an ordinary document and does travel;
 *       the copy re-materialises the mount the first time somebody lists it.
 *   <li><b>Trash and logs.</b> Neither is content anyone means to duplicate.
 *   <li><b>Archives.</b> The copy is a new lineage, not a second head on the
 *       old one — the same call {@code foreign_doc_copy} makes.
 *   <li><b>Permission grants.</b> Everything else here answers "what did the
 *       project look like"; a grant answers "who may come in". Carrying them
 *       silently is the same failure as leaving them behind on a delete, only
 *       pointed forwards. The creator gets PROJECT-ADMIN through the ordinary
 *       create path and that is all.
 *   <li><b>Sessions, chat, think-processes, inbox threads, RAG embeddings,
 *       usage and quota records, the workspace folder.</b> History and derived
 *       state; an inbox thread would additionally still point its
 *       {@code documentRef} at the <em>original</em> document.
 * </ul>
 *
 * <h2>The copy starts suspended</h2>
 *
 * <p>Scheduler and hook definitions are ordinary documents under
 * {@code _vance/}, so they travel — which means a copy left RUNNING starts
 * firing the original's timers within the minute. Suspending it makes
 * starting the copy a decision rather than a side effect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectCopyService {

    /**
     * Log documents. Mirrors {@code DocumentService}'s own publish-exclusion
     * prefix, which is package-private there.
     */
    private static final String LOGS_PREFIX = "_vance/logs/";

    private final ProjectService projectService;
    private final ProjectLifecycleService lifecycleService;
    private final DocumentService documentService;
    private final SettingService settingService;

    /**
     * Copies {@code sourceName} into a new project {@code targetName}.
     *
     * <p>Failure policy is the same one {@code create} already uses for a
     * failed kit install: the project exists once it is created, and a
     * document that could not be copied is reported rather than rolled back.
     * A half-filled project the operator can see and delete beats an
     * exception that leaves an unknown amount of work done.
     *
     * @param subject who is asking — threaded into every document write, so
     *     the reserved-prefix rule is enforced against the real caller
     * @throws ProjectService.ProjectNotFoundException if the source is gone
     * @throws ProjectService.ProjectAlreadyExistsException if the target exists
     * @throws ProjectService.SystemProjectProtectedException if the source is
     *     SYSTEM — a per-user hub or {@code _vance} has no meaningful copy
     */
    public ProjectCopyReportDto copy(
            String tenantId,
            String sourceName,
            String targetName,
            @Nullable String title,
            @Nullable String projectGroupId,
            boolean includeSecrets,
            SecurityContext subject) {

        ProjectDocument source = projectService.findByTenantAndName(tenantId, sourceName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + sourceName + "' not found in tenant '" + tenantId + "'"));
        if (source.getKind() == ProjectKind.SYSTEM) {
            throw new ProjectService.SystemProjectProtectedException(
                    "Project '" + sourceName + "' is SYSTEM — cannot copy");
        }
        if (sourceName.equals(targetName)) {
            throw new IllegalArgumentException("Source and target are the same project");
        }

        String actorName = subject.subjectId();
        lifecycleService.create(
                tenantId,
                targetName,
                title != null && !title.isBlank() ? title : source.getTitle(),
                projectGroupId != null && !projectGroupId.isBlank()
                        ? projectGroupId : source.getProjectGroupId(),
                source.getTeamIds(),
                ProjectKind.NORMAL,
                actorName);

        ProjectCopyReportDto.ProjectCopyReportDtoBuilder report =
                ProjectCopyReportDto.builder().sourceName(sourceName);

        copyDocuments(tenantId, sourceName, targetName, subject, report);
        copySettings(tenantId, sourceName, targetName, includeSecrets, actorName, report);

        report.notCopied(List.of(
                "permission grants — grant access to the copy explicitly",
                "sessions, chat history and think-processes",
                "inbox threads (they still refer to the original's documents)",
                "document versions / archives",
                "mounted documents under _ext/ (the mount configuration was copied)",
                "RAG embeddings and summaries (rebuilt on demand)",
                "usage, quota and image-call records",
                "the workspace folder"));
        report.statusNote(suspendCopy(tenantId, targetName));

        // {@code project} is filled in by the caller, which owns the
        // document→DTO mapping for every other project endpoint too.
        ProjectCopyReportDto result = report.build();
        log.info("Copied project '{}/{}' → '{}': {} document(s), {} setting(s),"
                        + " {} secret(s), {} failure(s)",
                tenantId, sourceName, targetName, result.getDocumentsCopied(),
                result.getSettingsCopied(), result.getSecretsCopied(),
                result.getDocumentsFailed());
        return result;
    }

    // ─── Documents ─────────────────────────────────────────────────────────

    private void copyDocuments(
            String tenantId,
            String sourceName,
            String targetName,
            SecurityContext subject,
            ProjectCopyReportDto.ProjectCopyReportDtoBuilder report) {

        WriteActor actor = WriteActor.user(subject);
        // Ordering is not load-bearing — folders are virtual and every create
        // stands on its own — but a deterministic pass keeps the log and the
        // failure list readable.
        List<DocumentDocument> all = documentService.listByProject(tenantId, sourceName).stream()
                .sorted(Comparator.comparing(d -> d.getPath() == null ? "" : d.getPath()))
                .toList();

        int copied = 0;
        int excluded = 0;
        List<String> failures = new ArrayList<>();
        for (DocumentDocument doc : all) {
            if (isExcluded(doc.getPath())) {
                excluded++;
                continue;
            }
            try {
                copyOne(tenantId, targetName, doc, subject, actor);
                copied++;
            } catch (RuntimeException e) {
                log.warn("Copy of document '{}' from '{}/{}' failed: {}",
                        doc.getPath(), tenantId, sourceName, e.toString());
                failures.add(doc.getPath() + ": " + describe(e));
            }
        }
        report.documentsCopied(copied)
                .documentsExcluded(excluded)
                .documentsFailed(failures.size())
                .failures(failures);
    }

    private void copyOne(
            String tenantId,
            String targetName,
            DocumentDocument source,
            SecurityContext subject,
            WriteActor actor) {

        DocumentDocument created;
        // Streamed rather than read as text: a project holds PDFs, images and
        // workspace assets, and a round-trip through String would corrupt
        // every one of them.
        try (InputStream content = documentService.loadContent(source)) {
            created = documentService.create(
                    tenantId,
                    targetName,
                    source.getPath(),
                    source.getTitle(),
                    source.getTags() == null ? null : List.copyOf(source.getTags()),
                    source.getMimeType(),
                    content,
                    subject.subjectId(),
                    actor);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read content: " + e.getMessage(), e);
        }

        // Author decisions the create path does not reproduce: create seeds a
        // lock only from $meta.lockedForInitial, and colour is pure metadata.
        if (source.getLockedFor() != null && !source.getLockedFor().isEmpty()) {
            documentService.setLockedFor(created.getId(), source.getLockedFor(), actor);
        }
        if (source.getColor() != null) {
            documentService.setColor(created.getId(), source.getColor(), actor);
        }
    }

    /** Whether a source path is one the copy deliberately passes over. */
    private static boolean isExcluded(@Nullable String path) {
        return path == null
                || JaglanPaths.isMounted(path)
                || DocumentService.isTrash(path)
                || path.startsWith(LOGS_PREFIX);
    }

    // ─── Settings ──────────────────────────────────────────────────────────

    private void copySettings(
            String tenantId,
            String sourceName,
            String targetName,
            boolean includeSecrets,
            @Nullable String actorName,
            ProjectCopyReportDto.ProjectCopyReportDtoBuilder report) {

        int plain = 0;
        int secrets = 0;
        List<String> skipped = new ArrayList<>();
        for (SettingDocument setting : settingService.findAll(
                tenantId, SettingService.SCOPE_PROJECT, sourceName)) {
            SettingType type = setting.getType();
            if (type == null) {
                continue;
            }
            if (!type.encrypted()) {
                settingService.setAs(tenantId, SettingService.SCOPE_PROJECT, targetName,
                        setting.getKey(), setting.getValue(), type,
                        setting.getDescription(), actorName);
                plain++;
                continue;
            }
            if (!includeSecrets) {
                skipped.add(setting.getKey());
                continue;
            }
            // Decrypt and re-encrypt rather than moving the ciphertext: it
            // keeps the write on the one supported path (audit row, feed
            // entry) instead of storing a blob nothing checked.
            String plaintext = settingService.getDecryptedPassword(
                    tenantId, SettingService.SCOPE_PROJECT, sourceName, setting.getKey());
            if (plaintext == null) {
                skipped.add(setting.getKey() + " (could not be decrypted)");
                continue;
            }
            settingService.setEncryptedSecretAs(tenantId, SettingService.SCOPE_PROJECT,
                    targetName, setting.getKey(), plaintext, type, actorName);
            secrets++;
        }
        report.settingsCopied(plain).secretsCopied(secrets).secretsSkipped(skipped);
    }

    // ─── Status ────────────────────────────────────────────────────────────

    /**
     * Suspends the fresh copy, and says why in one line for the report.
     *
     * <p>Never fatal: the copy already holds the data, and a project stuck in
     * RUNNING is a click away from being suspended by hand — losing the whole
     * report over it would be the worse trade.
     */
    private @Nullable String suspendCopy(String tenantId, String targetName) {
        ProjectDocument fresh = projectService.findByTenantAndName(tenantId, targetName)
                .orElse(null);
        if (fresh == null || fresh.getStatus() != ProjectStatus.RUNNING) {
            // Placement may have parked it as PENDING — nothing runs there
            // either, so the reason the suspend exists is already satisfied.
            return null;
        }
        try {
            lifecycleService.suspend(tenantId, targetName);
            return "The copy is SUSPENDED — copied scheduler and hook documents"
                    + " would otherwise start firing the original's timers. Resume it"
                    + " when you have reviewed them.";
        } catch (RuntimeException e) {
            log.warn("Could not suspend fresh copy '{}/{}': {}",
                    tenantId, targetName, e.toString());
            return "The copy could not be suspended (" + describe(e) + ") — check its"
                    + " scheduler and hook documents before letting it run.";
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
