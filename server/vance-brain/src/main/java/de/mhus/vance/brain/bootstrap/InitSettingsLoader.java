package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads bootstrap settings from a YAML file at startup. Used to bring
 * back LLM provider/model defaults and API keys after a database wipe
 * without re-typing them through the admin REST endpoint.
 *
 * <p>File format (per tenant; entries land in the tenant's
 * {@code _vance} system project):
 *
 * <pre>
 *   acme:
 *     ai.default.provider:
 *       type: STRING
 *       value: gemini
 *     ai.provider.gemini.apiKey:
 *       type: PASSWORD
 *       value: AIza...
 * </pre>
 *
 * <p>The file lives under {@code confidential/} (gitignored). Path is
 * configurable via {@link InitSettingsProperties#getSettingsFile()};
 * the loader also walks parent directories looking for
 * {@code confidential/init-settings.yaml} so the brain can be started
 * from anywhere within the workbench tree.
 *
 * <p>All operations are upserts — running the loader twice produces
 * the same state. PASSWORD entries are encrypted on write.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InitSettingsLoader {

    private static final String CONVENTIONAL_PATH = "confidential/init-settings.yaml";
    /**
     * Settings imported by this loader land on the tenant-wide
     * {@code _vance} system project. Caller (BootstrapBrainService) must
     * ensure {@code _vance} exists before invoking
     * {@link #loadIfPresent()}.
     */
    private static final String SETTINGS_REF_TYPE = SettingService.SCOPE_PROJECT;
    private static final String SETTINGS_REF_ID = HomeBootstrapService.VANCE_PROJECT_NAME;

    private final InitSettingsProperties properties;
    private final SettingService settingService;

    /**
     * Loads the configured file and applies every entry. Returns
     * silently when the file isn't there — empty default keeps the
     * brain runnable on a fresh checkout without forcing the user to
     * create the file first.
     */
    public void loadIfPresent() {
        Optional<Path> file = locateFile();
        if (file.isEmpty()) {
            log.info("Init settings file not found (configured: '{}'); skipping",
                    properties.getSettingsFile());
            return;
        }
        Path path = file.get();
        Map<String, Object> root = parseYaml(path);
        if (root == null || root.isEmpty()) {
            log.info("Init settings file '{}' is empty; nothing to apply", path);
            return;
        }
        int applied = 0;
        int skipped = 0;
        for (Map.Entry<String, Object> tenantEntry : root.entrySet()) {
            String tenant = tenantEntry.getKey();
            Object body = tenantEntry.getValue();
            if (!(body instanceof Map<?, ?> entries)) {
                log.warn("init-settings: tenant '{}' has no settings map; skipping", tenant);
                skipped++;
                continue;
            }
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                String key = e.getKey() == null ? null : e.getKey().toString();
                if (key == null || key.isBlank()) {
                    log.warn("init-settings: tenant '{}' has a blank key; skipping", tenant);
                    skipped++;
                    continue;
                }
                if (!(e.getValue() instanceof Map<?, ?> spec)) {
                    log.warn("init-settings: tenant='{}' key='{}' value is not a {{type, value}} map; skipping",
                            tenant, key);
                    skipped++;
                    continue;
                }
                try {
                    applyOne(tenant, key, spec);
                    applied++;
                } catch (RuntimeException ex) {
                    log.warn("init-settings: tenant='{}' key='{}' failed: {}",
                            tenant, key, ex.toString());
                    skipped++;
                }
            }
        }
        log.info("InitSettingsLoader applied {} setting(s) from '{}' ({} skipped)",
                applied, path, skipped);
    }

    private void applyOne(String tenant, String key, Map<?, ?> spec) {
        String typeStr = spec.get("type") == null ? "STRING" : spec.get("type").toString();
        SettingType type;
        try {
            type = SettingType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown setting type '" + typeStr + "'");
        }
        Object rawValue = spec.get("value");
        String value = rawValue == null ? null : rawValue.toString();
        if (value == null || value.isBlank()) {
            // Empty placeholder — leave the setting untouched. Lets users
            // keep "TODO: fill me" entries in the file without producing
            // an empty record that breaks consumers.
            log.debug("init-settings: tenant='{}' key='{}' has blank value — skipped",
                    tenant, key);
            return;
        }
        String description = spec.get("description") == null
                ? null : spec.get("description").toString();
        if (type == SettingType.PASSWORD) {
            settingService.setEncryptedPassword(
                    tenant, SETTINGS_REF_TYPE, SETTINGS_REF_ID, key, value);
        } else {
            settingService.set(
                    tenant, SETTINGS_REF_TYPE, SETTINGS_REF_ID, key, value, type, description);
        }
    }

    /**
     * Locates the settings file. Tries the configured path first; if
     * not found and the configured path matches the default convention,
     * walks up parent directories looking for
     * {@code confidential/init-settings.yaml}.
     */
    private Optional<Path> locateFile() {
        Path configured = Paths.get(properties.getSettingsFile()).toAbsolutePath().normalize();
        if (Files.isRegularFile(configured)) {
            return Optional.of(configured);
        }
        // Walk up from the working directory looking for the convention.
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        while (cwd != null) {
            Path candidate = cwd.resolve(CONVENTIONAL_PATH);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            cwd = cwd.getParent();
        }
        return Optional.empty();
    }

    private Map<String, Object> parseYaml(Path path) {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                return casted;
            }
            log.warn("init-settings file '{}' top level is not a map; got {}",
                    path, loaded == null ? "null" : loaded.getClass().getSimpleName());
            return Map.of();
        } catch (IOException e) {
            log.warn("init-settings: failed to read '{}': {}", path, e.toString());
            return Map.of();
        } catch (RuntimeException e) {
            log.warn("init-settings: failed to parse '{}': {}", path, e.toString());
            return Map.of();
        }
    }
}
