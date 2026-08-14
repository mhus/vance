package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * The pools a fresh adam Trillian is drawn from: given names with their
 * gender, and traits.
 *
 * <p>Held as a document rather than as a constant, and read through the
 * ordinary cascade — project, then {@code _vance}, then the bundled
 * classpath copy. A tenant that wants German names, or a test project
 * that wants deliberately silly ones, replaces a file; nobody touches
 * Java. That is the same reasoning that makes recipes, prompts and
 * manuals documents.
 *
 * <p>No cache. This is read once per minted Trillian — rare enough that
 * a lookup costs nothing, and an edit then takes effect on the next one
 * rather than on the next restart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianCharacterCatalog {

    static final String PATH = "_vance/trillian/adam-characters.yaml";

    /** Attribute keys. Plain words: they are rendered into a prompt. */
    public static final String ATTR_NAME = "name";
    public static final String ATTR_GENDER = "gender";
    public static final String ATTR_CHARACTER = "character";

    /**
     * Used only if the catalog cannot be read at all — a broken bundled
     * resource, or a document that overrode it with something
     * unparseable. A Trillian with a dull name is better than a Trillian
     * that fails to start.
     */
    private static final String FALLBACK_NAME = "Ada";
    private static final String FALLBACK_GENDER = "female";
    private static final String FALLBACK_TRAIT =
            "Terse. States the result and stops; no preamble.";

    private final DocumentService documentService;

    /**
     * A fresh character: name, gender, one trait.
     *
     * <p>Not unique across Trillians — two Adas in one project are
     * possible and harmless, since everything technical keys on the
     * account name.
     */
    public Map<String, Object> generate(String tenantId, String projectId, Random random) {
        Catalog catalog = load(tenantId, projectId);
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (catalog.names().isEmpty()) {
            attributes.put(ATTR_NAME, FALLBACK_NAME);
            attributes.put(ATTR_GENDER, FALLBACK_GENDER);
        } else {
            Person person = catalog.names().get(random.nextInt(catalog.names().size()));
            attributes.put(ATTR_NAME, person.name());
            attributes.put(ATTR_GENDER, person.gender());
        }
        attributes.put(ATTR_CHARACTER, catalog.traits().isEmpty()
                ? FALLBACK_TRAIT
                : catalog.traits().get(random.nextInt(catalog.traits().size())));
        return attributes;
    }

    private Catalog load(String tenantId, String projectId) {
        try {
            Optional<LookupResult> hit =
                    documentService.lookupCascade(tenantId, projectId, PATH);
            if (hit.isEmpty()) {
                log.warn("Trillian adam: no character catalog at {} — using fallback", PATH);
                return Catalog.empty();
            }
            Object parsed = new Yaml().load(hit.get().content());
            if (!(parsed instanceof Map<?, ?> root)) {
                log.warn("Trillian adam: character catalog at {} is not a YAML map", PATH);
                return Catalog.empty();
            }
            return new Catalog(readNames(root.get("names")), readTraits(root.get("traits")));
        } catch (RuntimeException e) {
            // An override can be hand-edited, so a broken file is a
            // question of when. Falling back beats refusing to mint.
            log.warn("Trillian adam: could not read character catalog: {}", e.toString());
            return Catalog.empty();
        }
    }

    private static List<Person> readNames(Object raw) {
        List<Person> out = new ArrayList<>();
        if (!(raw instanceof List<?> entries)) {
            return out;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> m)) {
                continue;
            }
            Object name = m.get(ATTR_NAME);
            Object gender = m.get(ATTR_GENDER);
            if (name instanceof String n && !n.isBlank()) {
                // A missing gender is left empty rather than guessed —
                // the whole reason the column exists.
                out.add(new Person(n.strip(),
                        gender instanceof String g ? g.strip() : ""));
            }
        }
        return out;
    }

    private static List<String> readTraits(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> entries)) {
            return out;
        }
        for (Object entry : entries) {
            if (entry instanceof String trait && !trait.isBlank()) {
                out.add(trait.strip());
            }
        }
        return out;
    }

    private record Person(String name, String gender) {
    }

    private record Catalog(List<Person> names, List<String> traits) {
        static Catalog empty() {
            return new Catalog(List.of(), List.of());
        }
    }
}
