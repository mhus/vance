package de.mhus.vance.brain.tools.ursaevent;

import de.mhus.vance.api.ursaevents.EventSource;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import de.mhus.vance.shared.ursaevents.UrsaEventParseException;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shared helpers for the {@code event_*} agent tools. Mirrors
 * {@code UrsaSchedulerToolSupport}: path conventions, name validation,
 * read-shape used by both the tool layer and (eventually) any new REST
 * controllers.
 *
 * <p>Events have no lockMode field today — see {@code specification/events.md}.
 * The bearer-token / Setting-Cascade is the protection surface; tool
 * mutations are not gated separately.
 */
@Component
@RequiredArgsConstructor
class UrsaEventToolSupport {

    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final DocumentService documentService;
    private final UrsaEventLoader loader;

    static String normalizeName(String name) {
        if (name == null) {
            throw new ToolException("'name' is required");
        }
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) {
            throw new ToolException("'name' must be a non-empty string");
        }
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw new ToolException(
                    "'name' must match " + NAME_PATTERN.pattern() + " — got '" + name + "'");
        }
        return trimmed;
    }

    static String pathFor(String name) {
        return UrsaEventLoader.EVENT_PATH_PREFIX + name
                + UrsaEventLoader.EVENT_PATH_SUFFIX;
    }

    /**
     * Validate a YAML body without persisting — runs the same parser that
     * the public webhook trigger uses on read, so any error the writer
     * sees here is exactly what would later surface at trigger time.
     */
    ResolvedUrsaEvent parseOrThrow(String name, String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new ToolException("'yaml' must be a non-empty string");
        }
        try {
            return loader.validateYaml(name, yaml);
        } catch (UrsaEventParseException ex) {
            throw new ToolException(ex.getMessage());
        }
    }

    /**
     * Persist the YAML body. If the doc exists at the same path it is
     * updated inline (document-layer auto-archives the previous version);
     * otherwise a new doc is created.
     *
     * <p>Returns {@code true} when an existing doc was replaced.
     */
    boolean upsert(
            String tenantId, String projectId, String name, String yaml,
            @Nullable String createdBy) {
        String path = pathFor(name);
        Optional<DocumentDocument> existing = documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(),
                    /*newTitle*/ null,
                    /*newTags*/ null,
                    /*newInlineText*/ yaml,
                    /*newPath*/ null,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
            return true;
        }
        documentService.createText(
                tenantId, projectId, path,
                /*title*/ "Event: " + name,
                /*tags*/ null,
                yaml,
                createdBy,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        return false;
    }

    /**
     * Hard-delete the local project entry if present. The cascade-resolved
     * tenant copy (if any) is untouched — same semantics as
     * {@code scheduler_delete}.
     */
    boolean deleteByName(String tenantId, String projectId, String name) {
        String path = pathFor(name);
        Optional<DocumentDocument> existing = documentService.findByPath(tenantId, projectId, path);
        if (existing.isEmpty()) {
            return false;
        }
        documentService.delete(existing.get().getId(), de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        return true;
    }

    /** Tool-friendly projection of a resolved event. */
    Map<String, Object> shape(ResolvedUrsaEvent r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", r.name());
        if (r.description() != null) out.put("description", r.description());
        if (r.recipe() != null) out.put("recipe", r.recipe());
        if (r.workflow() != null) out.put("workflow", r.workflow());
        if (r.script() != null) {
            Map<String, Object> script = new LinkedHashMap<>();
            script.put("source", r.script().source().name().toLowerCase(Locale.ROOT));
            if (r.script().dirName() != null) script.put("dirName", r.script().dirName());
            script.put("path", r.script().path());
            if (r.script().timeoutSeconds() != null) {
                script.put("timeoutSeconds", r.script().timeoutSeconds());
            }
            out.put("script", script);
        }
        out.put("enabled", r.enabled());
        out.put("source", r.source().name().toLowerCase(Locale.ROOT));
        if (!r.methods().isEmpty()) {
            out.put("methods", List.copyOf(r.methods()));
        }
        out.put("authConfigured", r.requiresAuth());
        if (r.requiresAuth()) {
            out.put("authType", "bearer");
            // Never leak the token literal. Surface the Setting key (if any)
            // so the LLM can tell the operator how the secret is wired.
            if (r.tokenSettingKey() != null) out.put("authTokenSetting", r.tokenSettingKey());
        }
        if (!r.params().isEmpty()) out.put("params", r.params());
        if (r.runAs() != null) out.put("runAs", r.runAs());
        if (r.effectiveRunAs() != null) out.put("effectiveRunAs", r.effectiveRunAs());
        if (!r.tags().isEmpty()) out.put("tags", r.tags());
        return out;
    }

    Map<String, Object> shapeFull(ResolvedUrsaEvent r) {
        Map<String, Object> out = shape(r);
        out.put("yaml", r.yaml());
        return out;
    }

    /** {@code true} when the resolved event lives in this project (not just cascaded from _tenant). */
    boolean isProjectLocal(ResolvedUrsaEvent r) {
        return r.source() == EventSource.PROJECT;
    }
}
