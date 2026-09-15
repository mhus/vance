package de.mhus.vance.anus.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads the agent-mode setup config ({@code --config <path|->}) into a plain
 * string-keyed map — the shared front half of every headless setup entry
 * point ({@code SetupWizard} tenant bootstrap and the compose scaffolder).
 *
 * <p>{@code "-"} reads stdin, so the config — which carries the user's
 * password and the AI API key — never has to land on the filesystem.
 * {@link SafeConstructor} keeps the parse to plain maps/scalars: agent- or
 * pipeline-supplied input must not be able to instantiate arbitrary Java
 * types.
 *
 * <p>Only the YAML-to-map step lives here; every wizard validates the
 * mapping's <i>contents</i> against its own rules (fail-closed, all problems
 * collected — see {@code SetupConfigParser} and {@code ComposeSetupConfig}).
 */
public final class AgentConfigReader {

    private AgentConfigReader() {}

    /**
     * Reads the config from a file path, or stdin when {@code source} is
     * {@code "-"}.
     *
     * @throws IOException on an unreadable source, invalid YAML, or a root
     *                     that is not a mapping
     */
    public static Map<String, Object> read(String source) throws IOException {
        String yamlText = "-".equals(source)
                ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(source), StandardCharsets.UTF_8);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root;
        try {
            root = yaml.load(yamlText);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            // Includes SafeConstructor refusing !!java tags — agent input stays data.
            throw new IOException("config is not valid YAML: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new IOException("config is empty");
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IOException(
                    "config must be a YAML mapping, got " + root.getClass().getSimpleName());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
