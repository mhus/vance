package de.mhus.vance.brain.tools.rest;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.rest.RestApiPackBuilder;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Server-side {@link ToolFactory} for {@code type: rest_api}. Reads
 * a {@link ServerToolDocument} (Mongo-persisted) and delegates the
 * materialisation logic to the pure-Java {@link RestApiPackBuilder}
 * in vance-toolpack. Foot-side {@code FootToolPackRegistry} calls the
 * same builder with input loaded from local JSON files.
 *
 * <p>Caching of the materialised tool list is owned by
 * {@link de.mhus.vance.brain.servertool.ServerToolService} — this
 * factory is stateless and cheap to call.
 *
 * <p>See {@code planning/server-tool-providers.md} §4.2 for the
 * recipe-side YAML schema.
 */
@Component
@Slf4j
public class RestApiToolPackFactory implements ToolFactory {

    public static final String TYPE_ID = "rest_api";

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "specUrl", Map.of("type", "string",
                            "description", "URL to the OpenAPI/Swagger spec (HTTP(S))."),
                    "specInline", Map.of("type", "string",
                            "description", "Inline OpenAPI/Swagger spec body (alternative to specUrl)."),
                    "baseUrl", Map.of("type", "string",
                            "description", "Override the spec's servers[].url."),
                    "auth", Map.of("type", "object",
                            "description", "Authentication block — bearer/basic/apiKey/none."),
                    "tls", Map.of("type", "object",
                            "description", "TLS settings (skipVerification, trustedCaPemPath)."),
                    "include", Map.of("type", "array",
                            "description", "operationId-glob whitelist."),
                    "exclude", Map.of("type", "array",
                            "description", "operationId-glob blacklist (applied after include).")));

    private final PackHttpClient httpClient;
    private final SettingsSecretResolver secretResolver;

    public RestApiToolPackFactory(SettingsSecretResolver secretResolver) {
        this.httpClient = new PackHttpClient();
        this.secretResolver = secretResolver;
    }

    @Override public String typeId() { return TYPE_ID; }
    @Override public Map<String, Object> parametersSchema() { return PARAMETERS_SCHEMA; }

    @Override
    public Collection<Tool> create(ServerToolDocument document) {
        Set<String> labels = document.getLabels() == null
                ? Set.of() : new LinkedHashSet<>(document.getLabels());
        RestApiPackBuilder.PackInput input = new RestApiPackBuilder.PackInput(
                document.getName(),
                labels,
                document.isPrimary(),
                document.isDefaultDeferred(),
                document.getParameters());
        Collection<Tool> tools = RestApiPackBuilder.build(input, httpClient, secretResolver);
        log.info("RestApiToolPackFactory pack='{}' tenant='{}' project='{}' produced {} tools",
                document.getName(), document.getTenantId(), document.getProjectId(), tools.size());
        return List.copyOf(tools);
    }
}
