package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.CheckpointSpec;
import de.mhus.vance.api.vogon.CheckpointType;
import de.mhus.vance.api.vogon.GateSpec;
import de.mhus.vance.api.vogon.PhaseSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads bundled strategies from {@code strategies.yaml} on the
 * classpath at construction time. Fail-fast: missing file or
 * malformed entries refuse the brain start. Mirrors the design of
 * {@link de.mhus.vance.brain.recipe.BundledRecipeRegistry}.
 *
 * <p>v1 has no Mongo-tier resolution yet — bundled-only. The
 * {@link StrategyResolver} will add the cascade later when
 * tenant/project overrides become a real use-case.
 */
@Service
@Slf4j
public class BundledStrategyRegistry {

    private static final String RESOURCE = "strategies.yaml";

    private final Map<String, StrategySpec> byName = new LinkedHashMap<>();
    private final List<StrategySpec> ordered = new ArrayList<>();

    public BundledStrategyRegistry() {
        load();
    }

    public Optional<StrategySpec> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name.toLowerCase().trim()));
    }

    public List<StrategySpec> all() {
        return List.copyOf(ordered);
    }

    public int size() {
        return ordered.size();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.warn("Bundled strategies resource '{}' not found — Vogon "
                    + "will only have inline strategies", RESOURCE);
            return;
        }
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            Object parsed = new Yaml().load(in);
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "Bundled strategies file must have a YAML map at the top");
            }
            root = (Map<String, Object>) m;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to parse " + RESOURCE
                    + ": " + e.getMessage(), e);
        }
        Object stratsObj = root.get("strategies");
        if (!(stratsObj instanceof List<?> list)) {
            throw new IllegalStateException(
                    "strategies.yaml must contain a 'strategies:' list");
        }
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (!(entry instanceof Map<?, ?> spec)) {
                throw new IllegalStateException(
                        "strategy at index " + i + " is not a map");
            }
            StrategySpec strategy = parseStrategy((Map<String, Object>) spec, i);
            String key = strategy.getName().toLowerCase().trim();
            if (byName.containsKey(key)) {
                throw new IllegalStateException(
                        "Duplicate strategy name '" + strategy.getName() + "'");
            }
            byName.put(key, strategy);
            ordered.add(strategy);
        }
        log.info("BundledStrategyRegistry: loaded {} strategies from '{}': {}",
                ordered.size(), RESOURCE,
                ordered.stream().map(StrategySpec::getName).toList());
    }

    private static StrategySpec parseStrategy(Map<String, Object> spec, int index) {
        String name = requireString(spec, "name", "strategies[" + index + "]");
        String description = optString(spec.get("description"));
        String version = optString(spec.get("version"));
        if (version == null) version = "1";
        Object phasesRaw = spec.get("phases");
        if (!(phasesRaw instanceof List<?> phaseList) || phaseList.isEmpty()) {
            throw new IllegalStateException(
                    "strategy '" + name + "' must declare a non-empty 'phases:' list");
        }
        List<PhaseSpec> phases = new ArrayList<>(phaseList.size());
        for (int i = 0; i < phaseList.size(); i++) {
            Object p = phaseList.get(i);
            if (!(p instanceof Map<?, ?> pm)) {
                throw new IllegalStateException(
                        "strategy '" + name + "' phase[" + i + "] is not a map");
            }
            phases.add(parsePhase(toStringMap(pm), name + ".phases[" + i + "]"));
        }
        Map<String, Object> paramDefaults = toStringMap(spec.get("paramDefaults"));
        return StrategySpec.builder()
                .name(name)
                .description(description)
                .version(version)
                .phases(phases)
                .paramDefaults(paramDefaults)
                .build();
    }

    private static PhaseSpec parsePhase(Map<String, Object> spec, String trail) {
        String name = requireString(spec, "name", trail);
        return PhaseSpec.builder()
                .name(name)
                .worker(optString(spec.get("worker")))
                .workerInput(optString(spec.get("workerInput")))
                .checkpoint(parseCheckpoint(spec.get("checkpoint"), trail))
                .gate(parseGate(spec.get("gate"), trail))
                .build();
    }

    private static @Nullable CheckpointSpec parseCheckpoint(@Nullable Object raw, String trail) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(trail + ".checkpoint must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        String typeStr = optString(spec.get("type"));
        CheckpointType type = typeStr == null
                ? CheckpointType.APPROVAL
                : CheckpointType.valueOf(typeStr.trim().toUpperCase());
        Object opts = spec.get("options");
        List<String> options = new ArrayList<>();
        if (opts instanceof List<?> l) {
            for (Object o : l) if (o != null) options.add(o.toString());
        }
        Object tags = spec.get("tags");
        List<String> tagList = new ArrayList<>();
        if (tags instanceof List<?> l) {
            for (Object o : l) if (o != null) tagList.add(o.toString());
        }
        return CheckpointSpec.builder()
                .type(type)
                .message(optStringOrEmpty(spec.get("message")))
                .options(options)
                .storeAs(optString(spec.get("storeAs")))
                .criticality(optString(spec.get("criticality")))
                .defaultValue(spec.get("default"))
                .tags(tagList)
                .payload(toStringMap(spec.get("payload")))
                .build();
    }

    private static @Nullable GateSpec parseGate(@Nullable Object raw, String trail) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException(trail + ".gate must be a map");
        }
        Map<String, Object> spec = toStringMap(m);
        return GateSpec.builder()
                .requires(asStringList(spec.get("requires")))
                .requiresAny(asStringList(spec.get("requiresAny")))
                .build();
    }

    private static List<String> asStringList(@Nullable Object raw) {
        if (raw == null) return new ArrayList<>();
        if (raw instanceof String s) {
            List<String> single = new ArrayList<>();
            single.add(s);
            return single;
        }
        if (raw instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(o.toString());
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(@Nullable Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String requireString(Map<String, Object> spec, String key, String trail) {
        Object raw = spec.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(trail + " missing required string '" + key + "'");
        }
        return s;
    }

    private static @Nullable String optString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static String optStringOrEmpty(@Nullable Object raw) {
        return raw instanceof String s ? s : "";
    }
}
