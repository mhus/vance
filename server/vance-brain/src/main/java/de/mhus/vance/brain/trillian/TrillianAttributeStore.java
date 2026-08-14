package de.mhus.vance.brain.trillian;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Durable storage for a Trillian's attributes, one YAML document per
 * service account.
 *
 * <p>Nature void keeps attributes in {@code engineParams} only, which means
 * they live exactly as long as the process rows do. That was survivable
 * because a whole apparatus carries them across an archive/reactivate —
 * the outgoing worker's map is parked on the closed control process for
 * the next bootstrap to pick up. It is not survivable as a general
 * answer: it is invisible to the human, it cannot be edited, and every
 * new lifetime transition needs its own carrying code.
 *
 * <p>A document instead. The path is keyed by the account name, which is
 * the one identifier that stays put across archive and reactivate:
 * {@code _vance/trillian/<account>.yaml}. It sits in the project the
 * pair works in — the same project the account holds its grant on, so
 * anyone who may read the Trillian's work may read what it was told to
 * be. Being a document, it also shows up in Cortex, where a human can
 * read and edit it directly rather than through the agent.
 *
 * <p><b>Not a second source of truth.</b> Runtime reads still come from
 * {@code engineParams}; this is written after every change and read once
 * when a worker starts with nothing carried over. Making the prompt read
 * the document per turn would put two copies in play and invite drift.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianAttributeStore {

    private static final String FOLDER = "_vance/trillian/";
    private static final String DOC_TITLE_PREFIX = "Trillian attributes — ";
    private static final List<String> TAGS = List.of("trillian", "attributes");

    /**
     * Written on every save so the file explains itself in Cortex. Not
     * parsed back — snakeyaml drops comments on load, and the header is
     * re-added on the next write.
     */
    private static final String HEADER = """
            # Trillian attributes — persistent across archive, reactivate and restart.
            #
            # Written by the Trillian itself when the human sets an attribute
            # (user_attr_set / //trillian attr set). Editing this file by hand is
            # fine; the values are read the next time this Trillian's worker loop
            # starts. Deleting the file resets the Trillian to no attributes.
            """;

    private final DocumentService documentService;

    /** Document path for an account, exposed for tests and log lines. */
    public static String pathFor(String account) {
        return FOLDER + account + ".yaml";
    }

    /**
     * Reads the stored attributes, or an empty map when nothing was ever
     * stored or the file no longer parses.
     *
     * <p>A hand-edited file that broke is treated as absent rather than
     * fatal: the Trillian starting plain beats the Trillian not starting.
     */
    public Map<String, Object> load(String tenantId, String projectId, String account) {
        try {
            Optional<DocumentDocument> doc =
                    documentService.findByPath(tenantId, projectId, pathFor(account));
            if (doc.isEmpty()) {
                return Map.of();
            }
            String text = documentService.readContent(doc.get());
            if (text == null || text.isBlank()) {
                return Map.of();
            }
            Object parsed = new Yaml().load(text);
            if (!(parsed instanceof Map<?, ?> map)) {
                log.warn("Trillian: '{}' is not a YAML map — ignoring stored attributes",
                        pathFor(account));
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String k) {
                    out.put(k, e.getValue());
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Trillian: could not read attributes of '{}': {}", account, e.toString());
            return Map.of();
        }
    }

    /**
     * Mirrors the attribute map to the account's document.
     *
     * <p>Best-effort by contract: the authoritative write already
     * happened on {@code engineParams} before this is called, so a
     * failure here costs durability, not the attribute.
     */
    public void save(String tenantId, String projectId, String account,
            Map<String, Object> attributes) {
        try {
            documentService.upsertText(
                    tenantId, projectId, pathFor(account),
                    DOC_TITLE_PREFIX + account, TAGS,
                    HEADER + "\n" + dump(attributes),
                    /*createdBy*/ account,
                    WriteActor.SYSTEM);
        } catch (RuntimeException e) {
            log.warn("Trillian: could not persist attributes of '{}': {}", account, e.toString());
        }
    }

    /**
     * Removes the account's attribute document.
     *
     * <p>Called when the Trillian itself is gone. The file is named after
     * an account that no longer exists, so keeping it leaves a growing
     * pile of documents nobody can trace back to anything — and the next
     * account gets a fresh name, so it would never be read again either.
     */
    public void discard(String tenantId, String projectId, String account) {
        try {
            documentService.findByPath(tenantId, projectId, pathFor(account))
                    .ifPresent(doc -> {
                        documentService.delete(doc.getId(), WriteActor.SYSTEM);
                        log.info("Trillian: removed attribute document {}", pathFor(account));
                    });
        } catch (RuntimeException e) {
            log.warn("Trillian: could not remove attributes of '{}': {}", account, e.toString());
        }
    }

    private static String dump(Map<String, Object> attributes) {
        if (attributes.isEmpty()) {
            // An empty map dumps as "{}", which reads like a placeholder
            // nobody filled in rather than a Trillian that was cleared.
            return "";
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // Multi-line attribute values (a persona paragraph, typically)
        // are the normal case here, and a quoted one-liner with \\n in it
        // is unreadable in an editor.
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setSplitLines(false);
        return new Yaml(options).dump(new LinkedHashMap<>(attributes));
    }
}
