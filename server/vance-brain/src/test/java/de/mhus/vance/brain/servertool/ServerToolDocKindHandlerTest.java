package de.mhus.vance.brain.servertool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Validation surface of the {@code vance-server-tool} document kind. The
 * handler runs the loader's own parse and the type lookup the admin REST
 * refuses unknown types with — the checks the Settings area relies on now
 * that it writes the document directly.
 */
class ServerToolDocKindHandlerTest {

    private ServerToolDocKindHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void buildHandler() {
        ToolFactory mcp = mock(ToolFactory.class);
        when(mcp.typeId()).thenReturn("mcp_server");
        ToolFactoryRegistry factories = new ToolFactoryRegistry(List.of(mcp));
        ObjectProvider<ToolFactoryRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(factories);
        handler = new ServerToolDocKindHandler(new ServerToolLoader(mock(DocumentService.class)), registry);
    }

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", mock(DocRefs.class));
    }

    @Test
    void getName_isVanceServerTool() {
        assertThat(handler.getName()).isEqualTo("vance-server-tool");
    }

    @Test
    void detectsPath_claimsTheToolFolder() {
        assertThat(handler.detectsPath("_vance/server-tools/jira.yaml")).isTrue();
        assertThat(handler.detectsPath("_vance/server-tools/jira.json")).isFalse();
        assertThat(handler.detectsPath("_vance/server-tools-old/jira.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/scheduler/jira.yaml")).isFalse();
    }

    @Test
    void validate_minimalTool_hasNoFindings() {
        String yaml = """
                type: mcp_server
                description: Notion workspace.
                parameters:
                  transport: http
                  url: https://mcp.notion.com/mcp
                """;
        assertThat(handler.validate(yaml, ctx("_vance/server-tools/notion.yaml")))
                .isEmpty();
    }

    @Test
    void validate_missingDescription_isWhatTheLoaderSkips() {
        List<Finding> findings = handler.validate("type: mcp_server\n", ctx("_vance/server-tools/notion.yaml"));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("server-tool-parse");
            assertThat(f.message()).contains("description");
        });
    }

    @Test
    void validate_nameNotMatchingThePath_isAnError() {
        String yaml = "name: other\ntype: mcp_server\ndescription: x\n";
        assertThat(handler.validate(yaml, ctx("_vance/server-tools/notion.yaml")))
                .extracting(Finding::code)
                .containsExactly("server-tool-parse");
    }

    @Test
    void validate_unknownType_namesTheAvailableOnes() {
        String yaml = "type: carrier_pigeon\ndescription: x\n";
        assertThat(handler.validate(yaml, ctx("_vance/server-tools/notion.yaml")))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.code()).isEqualTo("server-tool-type-unknown");
                    assertThat(f.message()).contains("carrier_pigeon").contains("mcp_server");
                });
    }

    @Test
    void validate_outsideTheToolFolder_isAWarning() {
        String yaml = "type: mcp_server\ndescription: x\n";
        assertThat(handler.validate(yaml, ctx("drafts/notion.yaml")))
                .extracting(Finding::code)
                .containsExactly("server-tool-location");
    }
}
