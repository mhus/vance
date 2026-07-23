package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.api.ursahooks.UrsaHookSource;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads hook YAML files from the document layer along the cascade
 * (Project → {@code _vance}). Returns {@link UrsaHookDef}s ready to be
 * registered.
 *
 * <p>The path layout is {@code _vance/hooks/<event>/<name>.yaml}.
 * {@link DocumentService#listByPrefixCascade} lists only one level
 * under a prefix, so the loader walks the event catalog and calls the
 * service once per event — that matches what we want anyway (the
 * registry indexes hooks by event).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UrsaHookLoader {

    public static final String HOOK_PATH_ROOT = "_vance/hooks/";
    public static final String HOOK_PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;
    private final UrsaHookYamlParser parser;

    /**
     * Every hook visible to the project, across all events. Parse
     * failures are logged and skipped; the caller still gets the
     * surviving entries.
     */
    public List<UrsaHookDef> listAll(String tenantId, String projectId) {
        List<UrsaHookDef> out = new ArrayList<>();
        for (UrsaHookEventName event : UrsaHookEventName.values()) {
            out.addAll(listForEvent(tenantId, projectId, event));
        }
        return out;
    }

    /** All hooks for one event — Project layer overrides {@code _vance}. */
    public List<UrsaHookDef> listForEvent(
            String tenantId, String projectId, UrsaHookEventName event) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, projectId, HOOK_PATH_ROOT + event.wireName());
        if (hits.isEmpty()) return List.of();
        List<UrsaHookDef> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            if (!path.endsWith(HOOK_PATH_SUFFIX)) continue;
            String hookName = deriveHookName(path);
            UrsaHookSource source = mapSource(e.getValue().source());
            @Nullable String createdBy = e.getValue().document() == null
                    ? null : e.getValue().document().getCreatedBy();
            boolean privileged = e.getValue().document() != null
                    && e.getValue().document().isPrivileged();
            try {
                out.add(parser.parse(
                        e.getValue().content(), event, source, hookName, createdBy)
                        .withPrivileged(privileged));
            } catch (UrsaHookParseException ex) {
                log.warn("Hook '{}/{}/{}/{}' failed to parse: {}",
                        tenantId, projectId, event.wireName(), hookName, ex.getMessage());
            }
        }
        return out;
    }

    /** Look up a single hook by event + name, parsed. */
    public Optional<UrsaHookDef> load(
            String tenantId, String projectId, UrsaHookEventName event, String hookName) {
        String path = HOOK_PATH_ROOT + event.wireName() + "/" + hookName + HOOK_PATH_SUFFIX;
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, projectId, path);
        if (hit.isEmpty()) return Optional.empty();
        UrsaHookSource source = mapSource(hit.get().source());
        @Nullable String createdBy = hit.get().document() == null
                ? null : hit.get().document().getCreatedBy();
        boolean privileged = hit.get().document() != null
                && hit.get().document().isPrivileged();
        return Optional.of(parser.parse(
                hit.get().content(), event, source, hookName, createdBy)
                .withPrivileged(privileged));
    }

    /**
     * Group {@link #listAll} by event for the registry-rebuild path.
     */
    public Map<UrsaHookEventName, List<UrsaHookDef>> groupByEvent(
            String tenantId, String projectId) {
        Map<UrsaHookEventName, List<UrsaHookDef>> out = new LinkedHashMap<>();
        for (UrsaHookEventName event : UrsaHookEventName.values()) {
            List<UrsaHookDef> defs = listForEvent(tenantId, projectId, event);
            if (!defs.isEmpty()) {
                out.put(event, defs);
            }
        }
        return out;
    }

    private static String deriveHookName(String path) {
        int slash = path.lastIndexOf('/');
        String last = slash < 0 ? path : path.substring(slash + 1);
        if (last.endsWith(HOOK_PATH_SUFFIX)) {
            last = last.substring(0, last.length() - HOOK_PATH_SUFFIX.length());
        }
        return last;
    }

    /**
     * Parsed location of a hook YAML document. Cf. {@link #parsePath(String)}.
     *
     * @param event    wire-name of the event (e.g. {@code "project.start"})
     * @param hookName local name of the hook (stem of the YAML filename)
     */
    public record ParsedPath(String event, String hookName) {}

    /**
     * Inverse of {@code HOOK_PATH_ROOT + event + "/" + name + ".yaml"}.
     * Returns the {@code (event, hookName)} pair encoded in the document
     * path, or {@code null} if the path doesn't match the expected shape
     * (missing prefix/suffix, missing event segment, empty name).
     *
     * <p>The wire-name is returned as a string rather than the
     * {@link UrsaHookEventName} enum so listeners can react to events the
     * running brain doesn't yet know about (the parsed value flows
     * straight into {@link UrsaHookService#refreshOne}, which is the
     * one that decides whether the wire-name is valid).
     */
    public static @Nullable ParsedPath parsePath(String path) {
        if (!path.startsWith(HOOK_PATH_ROOT)) return null;
        if (!path.endsWith(HOOK_PATH_SUFFIX)) return null;
        String tail = path.substring(
                HOOK_PATH_ROOT.length(),
                path.length() - HOOK_PATH_SUFFIX.length());
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash == tail.length() - 1) return null;
        String event = tail.substring(0, slash);
        String hookName = tail.substring(slash + 1);
        if (event.isBlank() || hookName.isBlank()) return null;
        // Defence against deeper paths — we only support a single
        // {event}/{name} segment, never sub-folders below the event.
        if (hookName.indexOf('/') >= 0) return null;
        return new ParsedPath(event, hookName);
    }

    private static UrsaHookSource mapSource(LookupResult.Source s) {
        // Resource layer would be UrsaHookSource — but the spec says no bundled
        // hooks, so any RESOURCE hit is treated as VANCE for diagnostics.
        return switch (s) {
            case PROJECT -> UrsaHookSource.PROJECT;
            case VANCE, RESOURCE -> UrsaHookSource.VANCE;
        };
    }
}
