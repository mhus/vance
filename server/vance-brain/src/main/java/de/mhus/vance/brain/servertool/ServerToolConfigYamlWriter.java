package de.mhus.vance.brain.servertool;

import de.mhus.vance.shared.servertool.ServerToolConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Serialises a {@link ServerToolConfig} to YAML for {@link
 * de.mhus.vance.shared.document.DocumentService} writes. Used when the
 * admin REST takes a structured DTO instead of a raw YAML body — the
 * struct is reflected back as a stable, block-style YAML so the round-
 * trip via {@code documents/...} survives reformatting.
 *
 * <p>Field order mirrors the schema in {@code planning/server-tools-to-documents.md}
 * §1.D5 so diffs stay readable.
 */
final class ServerToolConfigYamlWriter {

    private ServerToolConfigYamlWriter() {}

    static String write(ServerToolConfig cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", cfg.name());
        out.put("type", cfg.type());
        out.put("description", cfg.description());
        if (!cfg.parameters().isEmpty()) {
            out.put("parameters", cfg.parameters());
        }
        if (!cfg.labels().isEmpty()) {
            out.put("labels", cfg.labels());
        }
        out.put("enabled", cfg.enabled());
        out.put("primary", cfg.primary());
        if (!cfg.disabledSubTools().isEmpty()) {
            out.put("disabledSubTools", cfg.disabledSubTools().stream().toList());
        }
        out.put("defaultDeferred", cfg.defaultDeferred());
        if (cfg.promptHint() != null && !cfg.promptHint().isBlank()) {
            out.put("promptHint", cfg.promptHint());
        }

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return new Yaml(opts).dump(out);
    }
}
