package de.mhus.vance.brain.kit.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitProvisioningAuthority;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Grammar of {@code _vance/kits/provisioning.yaml}. */
class KitProvisioningLoaderTest {

    private KitProvisioningLoader loader;
    private SecretResolver secretResolver;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        // Passthrough by default; the one test that cares stubs it properly.
        when(secretResolver.resolveForConnector(any(), any()))
                .thenAnswer(i -> i.getArgument(0));
        loader = new KitProvisioningLoader(mock(DocumentService.class), secretResolver);
    }

    private List<KitProvisioningEntry> parse(String yaml) {
        return loader.parse(yaml, "acme", "sales");
    }

    @Test
    void parse_readsTypeUrlAuthorityAndParams() {
        List<KitProvisioningEntry> entries = parse("""
                provisioning:
                  - type: ode
                    url: https://host.example
                    authority: manage
                    params:
                      lang: de
                      modules: [crm, invoicing]
                """);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("ode");
            assertThat(entry.url()).isEqualTo("https://host.example");
            assertThat(entry.authority()).isEqualTo(KitProvisioningAuthority.MANAGE);
            assertThat(entry.params()).containsEntry("lang", "de");
            assertThat(entry.params().get("modules")).isEqualTo(List.of("crm", "invoicing"));
        });
    }

    @Test
    void parse_withoutAuthority_defaultsToNotify() {
        // The safe end: nothing unattended unless somebody wrote it down.
        assertThat(parse("""
                provisioning:
                  - type: ode
                    url: https://host.example
                """)).singleElement()
                .satisfies(e -> assertThat(e.authority())
                        .isEqualTo(KitProvisioningAuthority.NOTIFY));
    }

    @Test
    void parse_unknownAuthority_isRefused() {
        // Not defaulted: a typo in `manage` would silently give the opposite of
        // what was written, and the writer would find out by nothing happening.
        assertThatThrownBy(() -> parse("""
                provisioning:
                  - type: ode
                    url: https://host.example
                    authority: managee
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("unknown authority 'managee'");
    }

    @Test
    void parse_resolvesTheTokenThroughTheConnectorPath() {
        when(secretResolver.resolveForConnector(
                eq("{{secret:project:kit.token.acme}}"), any(ToolInvocationContext.class)))
                .thenReturn("s3cr3t");

        // The connector path, not the restrictive default: the loader is
        // compiled server code, so a PASSWORD target is legitimate. The
        // restrictive one would substitute empty and produce an opaque 401.
        assertThat(parse("""
                provisioning:
                  - type: ode
                    url: https://host.example
                    token: "{{secret:project:kit.token.acme}}"
                """)).singleElement()
                .satisfies(e -> assertThat(e.token()).isEqualTo("s3cr3t"));
    }

    @Test
    void parse_paramsAreNotSecretResolved() {
        parse("""
                provisioning:
                  - type: ode
                    url: https://host.example
                    params:
                      key: "{{secret:project:something}}"
                """);

        // A value here goes to a third party; the token is the field meant for
        // that party, an arbitrary vault value is not.
        org.mockito.Mockito.verify(secretResolver, org.mockito.Mockito.never())
                .resolveForConnector(eq("{{secret:project:something}}"), any());
    }

    @Test
    void parse_emptyDocument_isNoProvisioning() {
        assertThat(parse("")).isEmpty();
        assertThat(parse("provisioning:\n")).isEmpty();
    }

    @Test
    void parse_missingType_isRefused() {
        assertThatThrownBy(() -> parse("""
                provisioning:
                  - url: https://host.example
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("needs a 'type'");
    }

    @Test
    void parse_missingUrl_isRefused() {
        assertThatThrownBy(() -> parse("""
                provisioning:
                  - type: ode
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("needs a 'url'");
    }

    @Test
    void parse_paramsNotAMap_isRefused() {
        assertThatThrownBy(() -> parse("""
                provisioning:
                  - type: ode
                    url: https://host.example
                    params: [a, b]
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("'params' must be a map");
    }

    @Test
    void parse_provisioningNotAList_isRefused() {
        assertThatThrownBy(() -> parse("provisioning: nope\n"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("must be a list");
    }

    @Test
    void parse_brokenYaml_saysSoRatherThanDefaultingToNothing() {
        // Somebody wrote something they believe is in effect; defaulting to "no
        // provisioning" would apply the opposite of their intent, quietly.
        assertThatThrownBy(() -> parse("provisioning:\n  - type: ode\n   url: broken\n"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("not valid yaml");
    }

    @Test
    void parse_severalEntries_areAllKept() {
        assertThat(parse("""
                provisioning:
                  - type: ode
                    url: https://one.example
                  - type: ode
                    url: https://two.example
                    authority: update
                """)).extracting(KitProvisioningEntry::url)
                .containsExactly("https://one.example", "https://two.example");
    }
}
