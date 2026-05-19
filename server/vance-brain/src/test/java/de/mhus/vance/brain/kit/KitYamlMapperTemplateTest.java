package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KitYamlMapperTemplateTest {

    @Test
    void rejects_missing_name() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("title: x\n"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("name");
    }

    @Test
    void parses_minimal_template() {
        TemplateDescriptor t = KitYamlMapper.parseTemplate("""
                name: jira
                """);
        assertThat(t.name()).isEqualTo("jira");
        assertThat(t.title()).isEqualTo("jira");          // title defaults to name
        assertThat(t.inputs()).isEmpty();
        assertThat(t.postInstall()).isNull();
    }

    @Test
    void parses_all_input_types() {
        TemplateDescriptor t = KitYamlMapper.parseTemplate("""
                name: imap
                title: IMAP Mailbox
                inputs:
                  - { name: host,     type: string,  default: "imap.example.com" }
                  - { name: port,     type: integer, default: "993", required: false }
                  - { name: tls,      type: boolean, default: "true", required: false }
                  - { name: env,      type: select,  choices: [dev, prod] }
                  - name: password
                    type: password
                    target:
                      kind: setting
                      scope: project
                      key: imap.password
                """);
        assertThat(t.inputs()).hasSize(5);
        TemplateInput host = t.inputs().get(0);
        assertThat(host.type()).isEqualTo(TemplateInputType.STRING);
        assertThat(host.defaultValue()).isEqualTo("imap.example.com");
        assertThat(host.required()).isTrue();
        assertThat(t.inputs().get(3).choices()).containsExactly("dev", "prod");
        TemplateInput pw = t.inputs().get(4);
        assertThat(pw.target().kind()).isEqualTo(TemplateInputTarget.Kind.SETTING);
        assertThat(pw.target().scope()).isEqualTo(TemplateInputTarget.Scope.PROJECT);
        assertThat(pw.target().key()).isEqualTo("imap.password");
    }

    @Test
    void rejects_password_without_setting_target() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - { name: pw, type: password }
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("target.kind=setting");
    }

    @Test
    void rejects_select_without_choices() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - { name: x, type: select }
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("choices");
    }

    @Test
    void rejects_duplicate_input_names() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: bad
                inputs:
                  - { name: x, type: string }
                  - { name: x, type: string }
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void parses_post_install_oauth_connect() {
        TemplateDescriptor t = KitYamlMapper.parseTemplate("""
                name: jira
                postInstall:
                  kind: oauth-connect
                  provider: atlassian
                  message: "Connect now."
                """);
        assertThat(t.postInstall()).isNotNull();
        assertThat(t.postInstall().kind()).isEqualTo(TemplatePostInstall.Kind.OAUTH_CONNECT);
        assertThat(t.postInstall().provider()).isEqualTo("atlassian");
        assertThat(t.postInstall().message()).isEqualTo("Connect now.");
    }

    @Test
    void rejects_oauth_post_install_without_provider() {
        assertThatThrownBy(() -> KitYamlMapper.parseTemplate("""
                name: x
                postInstall:
                  kind: oauth-connect
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("provider");
    }
}
