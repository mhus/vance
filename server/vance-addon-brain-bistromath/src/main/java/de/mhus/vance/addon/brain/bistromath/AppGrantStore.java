package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code _vance/config/applications-granted.yaml} — read and written by the
 * server.
 *
 * <p>The counterpart to the hand-written {@link ApplicationsConfig}. It holds
 * every app that has asked for release, with what it asked for and how it was
 * answered. One document rather than a collection: it is low-traffic (a person
 * clicked a button), an admin can read it, and it needs no schema migration.
 *
 * <p><b>Concurrency is best-effort.</b> Two people requesting different apps in
 * the same second could have one write lose the other's entry. The document API
 * offers {@code If-Match}, and this path does not use it — the exposure is one
 * lost request that its owner will notice immediately (no inbox item appeared)
 * and can repeat. Worth stating rather than implying.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppGrantStore {

    public static final String PATH = "_vance/config/applications-granted.yaml";

    private static final String HEADER = """
            # Written by the server — do not hand-edit.
            #
            # Release requests for custom applications and how they were answered.
            # The hand-written _vance/config/applications.yaml always wins where it
            # names an app; entries here only fill in where it says nothing. To
            # revoke, name the app in that file rather than deleting a line here.
            """;

    private final DocumentService documentService;

    /** Every record, keyed by {@code <project>/<appPath>}. */
    public Map<String, AppGrantRecord> all(String tenantId) {
        Optional<DocumentDocument> doc = find(tenantId);
        if (doc.isEmpty()) return Map.of();
        String text = readText(doc.get());
        if (text == null || text.isBlank()) return Map.of();
        try {
            return parse(new Yaml().load(text));
        } catch (RuntimeException e) {
            // Fail closed, like the hand-written file: a broken bookkeeping
            // document must not read as "nothing was ever granted" *and* not as
            // "everything was" — refusing keeps it from silently doing either.
            throw new ToolException(PATH + " in tenant '" + tenantId
                    + "' is malformed and cannot be trusted to say what was granted ("
                    + e.getMessage() + ")", e);
        }
    }

    public @Nullable AppGrantRecord find(String tenantId, String appKey) {
        return all(tenantId).get(appKey);
    }

    /**
     * Insert or replace one record and write the document.
     *
     * <p>The {@code actor} is who caused it — the requester when a request is
     * raised, the approver when one is answered. Passed in rather than assumed
     * to be SYSTEM: this document is under {@code _vance/}, which needs ADMIN,
     * and a write that names nobody is a write nobody can be asked about.
     */
    public void put(String tenantId, String appKey, AppGrantRecord record, WriteActor actor) {
        Map<String, AppGrantRecord> records = new LinkedHashMap<>(all(tenantId));
        records.put(appKey, record);
        write(tenantId, records, actor);
    }

    // ── document I/O ───────────────────────────────────────────────────

    private Optional<DocumentDocument> find(String tenantId) {
        return documentService.findByPath(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, PATH);
    }

    private void write(String tenantId, Map<String, AppGrantRecord> records,
                       WriteActor actor) {
        Map<String, Object> apps = new LinkedHashMap<>();
        for (Map.Entry<String, AppGrantRecord> e : records.entrySet()) {
            apps.put(e.getKey(), toMap(e.getValue()));
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        String body = HEADER + new Yaml(options).dump(Map.of("apps", apps));

        Optional<DocumentDocument> existing = find(tenantId);
        try {
            if (existing.isPresent()) {
                documentService.update(existing.get().getId(),
                        null, null, body, null, actor);
            } else {
                // Created on first request — which is why a tenant that never
                // had an applications.yaml is not stuck: asking bootstraps this
                // file, and approving fills it in.
                documentService.create(tenantId, HomeBootstrapService.TENANT_PROJECT_NAME,
                        PATH, "Application grants", null, "application/yaml",
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                        null, actor);
            }
        } catch (RuntimeException e) {
            throw new ToolException("Failed to write " + PATH + ": " + e.getMessage(), e);
        }
    }

    private @Nullable String readText(DocumentDocument doc) {
        String inline = documentService.readContent(doc);
        if (inline != null) return inline;
        try (var in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Failed to read " + PATH, e);
        }
    }

    // ── mapping ────────────────────────────────────────────────────────

    private static Map<String, Object> toMap(AppGrantRecord r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name().toLowerCase(Locale.ROOT));
        out.put("mode", r.mode().name().toLowerCase(Locale.ROOT));
        if (r.restFamilies() != null) out.put("rest", new ArrayList<>(r.restFamilies()));
        out.put("surface", r.surface());
        out.put("documents", r.documentsWritable() ? "write" : "read");
        putIfSet(out, "requestedBy", r.requestedBy());
        putIfSet(out, "requestedAt", r.requestedAt());
        putIfSet(out, "inboxItemId", r.inboxItemId());
        putIfSet(out, "decidedBy", r.decidedBy());
        putIfSet(out, "decidedAt", r.decidedAt());
        return out;
    }

    private static void putIfSet(Map<String, Object> out, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AppGrantRecord> parse(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> top)) return Map.of();
        Object apps = top.get("apps");
        if (!(apps instanceof Map<?, ?> map)) return Map.of();
        Map<String, AppGrantRecord> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey()).trim();
            if (key.isEmpty() || !(e.getValue() instanceof Map<?, ?> v)) continue;
            out.put(key, fromMap((Map<String, Object>) v, key));
        }
        return Map.copyOf(out);
    }

    private static AppGrantRecord fromMap(Map<String, Object> v, String key) {
        AppGrantRecord.Status status = enumOf(AppGrantRecord.Status.class,
                str(v.get("status")), key + ".status");
        AppMode mode = enumOf(AppMode.class, str(v.get("mode")), key + ".mode");
        List<String> rest = null;
        if (v.get("rest") instanceof List<?> list) {
            List<String> families = new ArrayList<>();
            for (Object o : list) {
                String s = str(o);
                if (s != null && !s.isBlank()) families.add(s.trim().toLowerCase(Locale.ROOT));
            }
            rest = List.copyOf(families);
        }
        return new AppGrantRecord(
                status, mode, rest,
                Boolean.TRUE.equals(v.get("surface")),
                !"read".equalsIgnoreCase(String.valueOf(v.get("documents"))),
                str(v.get("requestedBy")), str(v.get("requestedAt")),
                str(v.get("inboxItemId")), str(v.get("decidedBy")), str(v.get("decidedAt")));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, @Nullable String raw, String where) {
        if (raw == null) {
            throw new ToolException(PATH + ": `" + where + "` is missing.");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException(PATH + ": `" + where + "` is `" + raw + "`, which is not one"
                    + " of the expected values.");
        }
    }

    private static @Nullable String str(@Nullable Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
