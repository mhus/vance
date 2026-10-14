package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OAuthDoorsTest {

    static OAuthServerSettings settings(String base) {
        return new OAuthServerSettings(base, true, 3600, 30, 365, true, true);
    }

    private final OAuthDoors doors = new OAuthDoors(settings("https://vance.example.com/"));

    @Test
    void resourceUrl_isIssuerPlusDoorPath() {
        assertThat(doors.resourceUrl("acme", OAuthDoor.mcp())).isEqualTo("https://vance.example.com/brain/acme/mcp");
        assertThat(doors.resourceUrl("acme", OAuthDoor.mcpProject("docs")))
                .isEqualTo("https://vance.example.com/brain/acme/mcp/docs");
        assertThat(doors.metadataUrl("acme", OAuthDoor.agents()))
                .isEqualTo("https://vance.example.com/brain/acme/oauth/resource/agents");
    }

    @Test
    void fromResource_acceptsTheCanonicalForm_caseInsensitiveHost_andTrailingSlash() {
        assertThat(doors.fromResource("acme", "https://vance.example.com/brain/acme/mcp"))
                .isEqualTo(OAuthDoor.mcp());
        assertThat(doors.fromResource("acme", "HTTPS://Vance.Example.com/brain/acme/mcp/docs/"))
                .isEqualTo(OAuthDoor.mcpProject("docs"));
        assertThat(doors.fromResource("acme", "https://vance.example.com:443/brain/acme/agents"))
                .isEqualTo(OAuthDoor.agents());
    }

    @Test
    void fromResource_rejectsOtherTenantsHostsAndShapes() {
        assertThat(doors.fromResource("acme", "https://vance.example.com/brain/other/mcp"))
                .isNull();
        assertThat(doors.fromResource("acme", "https://evil.example/brain/acme/mcp"))
                .isNull();
        assertThat(doors.fromResource("acme", "http://vance.example.com/brain/acme/mcp"))
                .isNull();
        assertThat(doors.fromResource("acme", "https://vance.example.com/brain/acme/mcp/docs/deeper"))
                .isNull();
        assertThat(doors.fromResource("acme", "https://vance.example.com/brain/acme/agents/x"))
                .isNull();
        assertThat(doors.fromResource("acme", "https://vance.example.com/brain/acme/documents"))
                .isNull();
        assertThat(doors.fromResource("acme", "https://vance.example.com/brain/acme/mcp?x=1"))
                .isNull();
        assertThat(doors.fromResource("acme", "https://vance.example.com/brain/acme/mcp#f"))
                .isNull();
    }

    @Test
    void fromRequestPath_findsTheDoorAndTenant() {
        assertThat(OAuthDoors.fromRequestPath("/brain/acme/mcp"))
                .isEqualTo(new OAuthDoors.TenantDoor("acme", OAuthDoor.mcp()));
        assertThat(OAuthDoors.fromRequestPath("/brain/acme/mcp/docs"))
                .isEqualTo(new OAuthDoors.TenantDoor("acme", OAuthDoor.mcpProject("docs")));
        assertThat(OAuthDoors.fromRequestPath("/brain/acme/agents/"))
                .isEqualTo(new OAuthDoors.TenantDoor("acme", OAuthDoor.agents()));
        assertThat(OAuthDoors.fromRequestPath("/brain/acme/documents")).isNull();
        assertThat(OAuthDoors.fromRequestPath("/brain/acme/agents/x")).isNull();
    }

    @Test
    void settings_disableWithoutAUsableBaseUrl() {
        assertThat(settings("").enabled()).isFalse();
        assertThat(settings("not a url").enabled()).isFalse();
        assertThat(settings("https://x.example/?q=1").enabled()).isFalse();
        assertThat(settings("http://localhost:9900").enabled()).isTrue();
        assertThat(settings("https://x.example/sub/").issuer("t")).isEqualTo("https://x.example/sub/brain/t");
    }
}
