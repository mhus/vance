package de.mhus.vance.toolpack.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiSpecLoaderTest {

    private static final String PETSTORE_INLINE = """
            openapi: 3.0.0
            info:
              title: Petstore
              version: '1.0'
            servers:
              - url: https://petstore.example.com/v1
            paths:
              /pets:
                get:
                  operationId: listPets
                  summary: List all pets
                  parameters:
                    - name: limit
                      in: query
                      schema: { type: integer }
                  responses:
                    '200':
                      description: ok
                post:
                  operationId: createPet
                  summary: Create a pet
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            name: { type: string }
                            tag: { type: string }
                          required: [name]
                  responses:
                    '201':
                      description: created
              /pets/{petId}:
                get:
                  operationId: getPet
                  summary: Get a pet by id
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema: { type: string }
                  responses:
                    '200':
                      description: ok
                delete:
                  operationId: deletePet
                  summary: Delete a pet
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema: { type: string }
                  responses:
                    '204':
                      description: gone
            """;

    @Test
    void parsesAllOperationIds() {
        List<OpenApiOperation> ops = OpenApiSpecLoader.parseInline(PETSTORE_INLINE);

        assertThat(ops).extracting(OpenApiOperation::operationId)
                .containsExactlyInAnyOrder("listPets", "createPet", "getPet", "deletePet");
    }

    @Test
    void identifiesParameterLocations() {
        List<OpenApiOperation> ops = OpenApiSpecLoader.parseInline(PETSTORE_INLINE);

        OpenApiOperation listPets = byId(ops, "listPets");
        assertThat(listPets.queryParamNames()).containsExactly("limit");
        assertThat(listPets.pathParamNames()).isEmpty();
        assertThat(listPets.bodyParamName()).isNull();

        OpenApiOperation getPet = byId(ops, "getPet");
        assertThat(getPet.pathParamNames()).containsExactly("petId");

        OpenApiOperation createPet = byId(ops, "createPet");
        assertThat(createPet.bodyParamName()).isEqualTo("body");
        assertThat(createPet.bodyContentType()).contains("json");
    }

    @Test
    void preservesHttpMethodCasing() {
        List<OpenApiOperation> ops = OpenApiSpecLoader.parseInline(PETSTORE_INLINE);

        assertThat(byId(ops, "listPets").httpMethod()).isEqualTo("GET");
        assertThat(byId(ops, "createPet").httpMethod()).isEqualTo("POST");
        assertThat(byId(ops, "deletePet").httpMethod()).isEqualTo("DELETE");
    }

    @Test
    void pickBaseUrl_prefersOverrideOverSpecServer() {
        var spec = OpenApiSpecLoader.parseSpec(PETSTORE_INLINE);
        assertThat(OpenApiSpecLoader.pickBaseUrl(null, spec))
                .isEqualTo("https://petstore.example.com/v1");
        assertThat(OpenApiSpecLoader.pickBaseUrl("https://override.test", spec))
                .isEqualTo("https://override.test");
    }

    @Test
    void malformedSpec_throwsWithUsefulMessage() {
        assertThatThrownBy(() -> OpenApiSpecLoader.parseInline("this is not yaml or json {"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OpenAPI spec failed to parse");
    }

    @Test
    void mergedParamsSchema_isObjectShaped() {
        List<OpenApiOperation> ops = OpenApiSpecLoader.parseInline(PETSTORE_INLINE);

        var schema = byId(ops, "createPet").paramsSchema();
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsKey("properties");
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("body");
    }

    private static OpenApiOperation byId(List<OpenApiOperation> ops, String id) {
        return ops.stream().filter(o -> o.operationId().equals(id))
                .findFirst().orElseThrow();
    }
}
