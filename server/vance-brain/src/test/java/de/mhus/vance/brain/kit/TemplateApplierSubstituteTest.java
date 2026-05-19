package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TemplateApplier#substitute} — the pure-text
 * placeholder-replacement that powers the apply path. Behavioural
 * tests for the end-to-end apply (with KitInstaller + SettingService)
 * live in a separate integration test against a fixture kit.
 */
class TemplateApplierSubstituteTest {

    @Test
    void replaces_single_var() {
        Map<String, String> vars = Map.of("host", "imap.zoho.com");
        String out = TemplateApplier.substitute(
                "host: {{var:host}}\n", vars, "documents/a.yaml");
        assertThat(out).isEqualTo("host: imap.zoho.com\n");
    }

    @Test
    void replaces_multiple_vars_in_one_document() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("host", "imap.zoho.com");
        vars.put("port", "993");
        String content = """
                host: {{var:host}}
                port: {{var:port}}
                # second mention: {{var:host}}
                """;
        String out = TemplateApplier.substitute(content, vars, "x.yaml");
        assertThat(out).contains("host: imap.zoho.com");
        assertThat(out).contains("port: 993");
        assertThat(out).contains("# second mention: imap.zoho.com");
    }

    @Test
    void tolerates_whitespace_inside_braces() {
        String out = TemplateApplier.substitute(
                "x={{ var : foo }}",
                Map.of("foo", "BAR"),
                "x.yaml");
        assertThat(out).isEqualTo("x=BAR");
    }

    @Test
    void leaves_non_var_placeholders_alone() {
        // {{secret:...}} is the runtime resolver's syntax, not ours.
        String out = TemplateApplier.substitute(
                "token: {{secret:user:foo}}\nhost: {{var:host}}\n",
                Map.of("host", "imap.zoho.com"),
                "x.yaml");
        assertThat(out).contains("{{secret:user:foo}}");
        assertThat(out).contains("imap.zoho.com");
    }

    @Test
    void unknown_var_reference_fails_loudly() {
        assertThatThrownBy(() -> TemplateApplier.substitute(
                "host: {{var:hostname}}\n",
                Map.of("host", "imap.zoho.com"),
                "documents/imap.yaml"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("documents/imap.yaml")
                .hasMessageContaining("hostname");
    }

    @Test
    void no_vars_no_change() {
        String content = "plain: text\nno placeholders here\n";
        String out = TemplateApplier.substitute(content, Map.of(), "x.yaml");
        assertThat(out).isEqualTo(content);
    }
}
