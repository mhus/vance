package de.mhus.vance.toolpack.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a REST tool tells about its result: the operation's success response schema from the
 * OpenAPI spec, put into the envelope every REST call returns.
 */
class OpenApiOutputSchemaTest {

    private static final String SPEC = """
            openapi: 3.0.0
            info: { title: Issues, version: '1.0' }
            servers: [ { url: https://issues.example.com } ]
            paths:
              /issues:
                get:
                  operationId: listIssues
                  responses:
                    '404':
                      description: nothing
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              total: { type: integer }
                              items:
                                type: array
                                items:
                                  type: object
                                  properties:
                                    id: { type: string }
                                    title: { type: string }
                post:
                  operationId: createIssue
                  responses:
                    '201':
                      description: created
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id: { type: string }
              /ping:
                get:
                  operationId: ping
                  responses:
                    '204':
                      description: no body
              /export:
                get:
                  operationId: exportCsv
                  responses:
                    '200':
                      description: a file
                      content:
                        text/csv:
                          schema: { type: string }
            """;

    @BeforeEach
    void resetCache() {
        OpenApiSpecLoader.clearCache();
    }

    @Test
    void the_success_response_schema_is_read_from_the_spec() {
        OpenApiOperation list = operation("listIssues");

        assertThat(list.responseSchema()).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) list.responseSchema().get("properties");
        assertThat(properties).containsKeys("total", "items");
    }

    @Test
    void a_201_response_counts_as_success_too() {
        assertThat(operation("createIssue").responseSchema()).isNotNull();
    }

    @Test
    void an_operation_without_a_json_success_body_has_no_response_schema() {
        assertThat(operation("ping").responseSchema()).isNull();
        // A CSV body is not JSON and is not described as if it were.
        assertThat(operation("exportCsv").responseSchema()).isNull();
    }

    @Test
    void the_tool_puts_the_response_schema_into_the_envelope_under_json() {
        RestEndpointTool tool = tool("listIssues");

        Map<String, Object> out = tool.outputSchema();

        assertThat(out).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) out.get("properties");
        assertThat(properties).containsKeys("status", "ok", "headers", "body", "json");
        assertThat(tool.service()).isEqualTo("issues");
    }

    @Test
    void without_a_response_schema_the_envelope_is_still_known() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) tool("ping").outputSchema().get("properties");

        assertThat(properties).containsKeys("status", "ok", "headers", "body").doesNotContainKey("json");
    }

    @Test
    void the_wire_spec_carries_output_schema_and_service() {
        var spec = tool("listIssues").toSpec("pack");

        assertThat(spec.getOutputSchema()).isNotNull();
        assertThat(spec.getService()).isEqualTo("issues");
    }

    private static OpenApiOperation operation(String id) {
        List<OpenApiOperation> ops = OpenApiSpecLoader.parseInline(SPEC);
        return ops.stream().filter(o -> id.equals(o.operationId())).findFirst().orElseThrow();
    }

    private static RestEndpointTool tool(String id) {
        return new RestEndpointTool(
                "issues__" + id, "d", Set.of(), false, false, "h", operation(id), /*invoker*/ null, "issues");
    }
}
