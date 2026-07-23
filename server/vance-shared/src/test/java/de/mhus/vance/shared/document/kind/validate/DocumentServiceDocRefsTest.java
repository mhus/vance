package de.mhus.vance.shared.document.kind.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the SafeConstructor fix on the kind doc-ref validation read
 * (code-review Phase 2): referenced-document YAML is untrusted content, so
 * an explicit {@code !!<java-type>} tag must NOT instantiate an arbitrary
 * classpath type.
 */
class DocumentServiceDocRefsTest {

    private DocumentService documentService;
    private DocumentServiceDocRefs docRefs;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        docRefs = new DocumentServiceDocRefs(documentService, "acme", "proj");
    }

    private void stubContent(String path, String yaml) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId("acme").projectId("proj").path(path).build();
        when(documentService.findByPath("acme", "proj", path)).thenReturn(Optional.of(doc));
        when(documentService.loadContent(any())).thenReturn(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readYaml_plainMap_isParsed() {
        stubContent("ref.yaml", "a: 1\nb: two\n");

        Map<String, Object> out = docRefs.readYaml("ref.yaml");

        assertThat(out).containsEntry("a", 1).containsEntry("b", "two");
    }

    @Test
    void readYaml_explicitJavaTypeTag_isRejectedNotInstantiated() {
        // Under the old bare `new Yaml()` this global tag would instantiate
        // a HashMap (and any other classpath type via the same mechanism).
        // With SafeConstructor the load throws → caught → null, so no
        // arbitrary type is ever constructed.
        stubContent("evil.yaml", "!!java.util.HashMap {a: 1}");

        assertThat(docRefs.readYaml("evil.yaml")).isNull();
    }
}
