package de.mhus.vance.brain.servicecatalog;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads the service catalog through the usual cascade ({@code classpath → _tenant → project}, innermost wins by
 * name). An entry that does not parse is skipped with a warning: a broken description must not take the list of
 * tools down with it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceCatalogLoader {

    /** Folder of the entries in every cascade tier. */
    public static final String PATH_PREFIX = "_vance/service-catalog/";

    private static final String SUFFIX = ".yaml";

    private final DocumentService documentService;

    /** Every entry visible to {@code projectId}, by name. */
    public List<ServiceCatalogEntry> list(String tenantId, String projectId) {
        List<ServiceCatalogEntry> out = new ArrayList<>();
        Map<String, LookupResult> documents = documentService.listByPrefixCascade(tenantId, projectId, PATH_PREFIX);
        for (Map.Entry<String, LookupResult> doc : documents.entrySet()) {
            String path = doc.getKey();
            if (!path.endsWith(SUFFIX)) continue;
            String name = path.substring(path.lastIndexOf('/') + 1, path.length() - SUFFIX.length());
            try {
                out.add(parse(name, doc.getValue().content()));
            } catch (RuntimeException e) {
                log.warn("Service catalog entry '{}' skipped: {}", path, e.getMessage());
            }
        }
        out.sort(Comparator.comparing(ServiceCatalogEntry::name));
        return List.copyOf(out);
    }

    /** The entry that describes the pack {@code service}, if any. */
    public Optional<ServiceCatalogEntry> forService(List<ServiceCatalogEntry> entries, String service) {
        return entries.stream().filter(e -> e.describes(service)).findFirst();
    }

    @SuppressWarnings("unchecked")
    static ServiceCatalogEntry parse(String name, String yaml) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalStateException("not a map");
        }
        Map<String, Object> m = (Map<String, Object>) map;
        List<String> match = strings(m.get("match"));
        if (match.isEmpty()) {
            throw new IllegalStateException("'match' must name at least one pack");
        }
        String provider = null;
        String manual = null;
        if (m.get("login") instanceof Map<?, ?> login) {
            provider = text(login.get("provider"));
            manual = text(login.get("manual"));
        }
        List<ServiceCatalogEntry.Highlight> highlights = new ArrayList<>();
        if (m.get("highlights") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> h) || text(h.get("tool")) == null) {
                    throw new IllegalStateException("each highlight needs a 'tool'");
                }
                Map<String, Object> params =
                        h.get("params") instanceof Map<?, ?> p ? new LinkedHashMap<>((Map<String, Object>) p) : null;
                highlights.add(
                        new ServiceCatalogEntry.Highlight(text(h.get("tool")), texts(h.get("description")), params));
            }
        }
        return new ServiceCatalogEntry(
                name,
                texts(m.get("title")),
                texts(m.get("description")),
                text(m.get("icon")),
                match,
                provider,
                manual,
                List.copyOf(highlights));
    }

    private static @Nullable String text(@Nullable Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> strings(@Nullable Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                String s = text(item);
                if (s != null) out.add(s);
            }
        }
        return out;
    }

    /** A text per language, or a plain string taken as English. */
    private static Map<String, String> texts(@Nullable Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                String s = text(v);
                if (s != null) out.put(String.valueOf(k), s);
            });
        } else if (text(o) != null) {
            out.put("en", text(o));
        }
        return Map.copyOf(out);
    }
}
