package de.mhus.vance.brain.servertool;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads the bundled server-tool catalog from the classpath
 * {@code server-tools.yaml} at construction time. Fail-fast on a
 * malformed file — server tools are a hard dependency of the brain.
 *
 * <p>The registry is in-memory and immutable after boot. Tenant
 * overrides live in MongoDB inside the {@code _vance} project (or
 * shadowing in user projects); this registry is only consulted by
 * {@link ServerToolBootstrapService} during first-login provisioning.
 */
@Service
@Slf4j
public class BundledServerToolRegistry {

    private static final String RESOURCE = "server-tools.yaml";

    private final List<BundledServerTool> tools;

    public BundledServerToolRegistry() {
        this.tools = load();
        log.info("BundledServerToolRegistry: loaded {} tools from '{}': {}",
                tools.size(), RESOURCE,
                tools.stream().map(BundledServerTool::name).toList());
    }

    public List<BundledServerTool> all() {
        return tools;
    }

    @SuppressWarnings("unchecked")
    private static List<BundledServerTool> load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Bundled server tools resource '" + RESOURCE
                            + "' not found on classpath — brain cannot start");
        }
        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            Object parsed = yaml.load(in);
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "Bundled server tools file '" + RESOURCE
                                + "' must have a YAML map at the top level");
            }
            root = (Map<String, Object>) m;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read bundled server tools '" + RESOURCE + "'", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to parse bundled server tools '" + RESOURCE
                            + "': " + e.getMessage(), e);
        }

        Object listRaw = root.get("tools");
        if (!(listRaw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Bundled server tools file '" + RESOURCE
                            + "' must contain a top-level 'tools:' list");
        }
        List<BundledServerTool> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> spec)) {
                throw new IllegalStateException(
                        "Bundled server tool at index " + i
                                + " is not a map (file: '" + RESOURCE + "')");
            }
            out.add(parseOne((Map<String, Object>) spec, i));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static BundledServerTool parseOne(Map<String, Object> spec, int index) {
        String name = requireNonBlankString(spec, "name", index);
        String type = requireNonBlankString(spec, "type", index);
        String description = requireNonBlankString(spec, "description", index);

        Map<String, Object> parameters = new LinkedHashMap<>();
        Object rawParams = spec.get("parameters");
        if (rawParams != null) {
            if (!(rawParams instanceof Map<?, ?> pm)) {
                throw new IllegalStateException(
                        "Server tool '" + name + "': 'parameters' must be a map");
            }
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                parameters.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        List<String> labels = stringList(spec.get("labels"), name, "labels");
        boolean enabled = asBoolean(spec.get("enabled"), name, "enabled", true);
        boolean primary = asBoolean(spec.get("primary"), name, "primary", false);

        return new BundledServerTool(
                name, type, description, parameters, labels, enabled, primary);
    }

    private static String requireNonBlankString(Map<String, Object> spec, String key, int index) {
        Object raw = spec.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "Server tool at index " + index
                            + " is missing required string '" + key + "'");
        }
        return s.trim();
    }

    private static List<String> stringList(Object raw, String name, String fieldName) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Server tool '" + name + "': '" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "Server tool '" + name + "': '" + fieldName
                                + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static boolean asBoolean(Object raw, String name, String field, boolean def) {
        if (raw == null) return def;
        if (raw instanceof Boolean b) return b;
        throw new IllegalStateException(
                "Server tool '" + name + "': '" + field + "' must be true or false");
    }
}
