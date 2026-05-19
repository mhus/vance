package de.mhus.vance.brain.kit;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Parsed view of a {@code template.yaml} carried by an apply-style kit
 * (sibling of {@code kit.yaml}). Templates extend the kit model with
 * an input-schema and {@code {{var:fieldName}}}-substitution at apply
 * time — see {@code planning/tool-templates.md}.
 *
 * <p>YAML schema (mirrors the planning doc):
 * <pre>
 *   name: jira
 *   title: "Atlassian Jira"
 *   description: "Connect Jira via REST API + OAuth 2.0 (3LO)."
 *   icon: jira                                  # optional
 *   inputs:
 *     - name: clientId
 *       type: string
 *       label: "OAuth Client ID"
 *       required: true
 *     - name: clientSecret
 *       type: password
 *       required: true
 *       target:
 *         kind: setting
 *         scope: project
 *         project: _tenant
 *         key: oauth.atlassian.client_secret
 *   postInstall:
 *     kind: oauth-connect
 *     provider: atlassian
 *     message: "Click 'Connect Atlassian' …"
 * </pre>
 *
 * <p>{@code inputs} drives both the Web-UI form rendering and the
 * chat-agent's "ask for the missing inputs" recipe. {@code target}
 * decides whether the substituted value lands inline in a kit
 * document or in {@link de.mhus.vance.shared.settings.SettingService}.
 */
public record TemplateDescriptor(
        String name,
        String title,
        @Nullable String description,
        @Nullable String icon,
        List<TemplateInput> inputs,
        @Nullable TemplatePostInstall postInstall) {

    public TemplateDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("template: 'name' is required");
        }
        if (title == null || title.isBlank()) title = name;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
