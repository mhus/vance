package de.mhus.vance.shared.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;

/** {@link DocumentService#lookupCascadeBytes}: bytes stay bytes, innermost layer wins, a bound is a bound. */
class DocumentServiceCascadeBytesTest {

    private static final String PATH = "_vance/webui/icons/x.png";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF, (byte) 0xFE};

    private DocumentRepository repository;
    private StorageService storageService;
    private ResourcePatternResolver resolver;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentRepository.class);
        storageService = mock(StorageService.class);
        resolver = mock(ResourcePatternResolver.class);
        service = new DocumentService(
                repository,
                storageService,
                mock(MongoTemplate.class),
                resolver,
                mock(DocumentHeaderParser.class),
                mock(DocumentArchiveService.class),
                mock(SettingService.class),
                DocTestSupport.permissionProvider());
    }

    private DocumentDocument stored(String project, byte[] content) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId("acme")
                .projectId(project)
                .path(PATH)
                .mimeType("image/png")
                .storageId("sid-" + project)
                .status(DocumentStatus.ACTIVE)
                .build();
        doc.setSize(content.length);
        when(repository.findByTenantIdAndProjectIdAndPath("acme", project, PATH))
                .thenReturn(Optional.of(doc));
        when(storageService.load("sid-" + project)).thenReturn(new ByteArrayInputStream(content));
        return doc;
    }

    @Test
    void theBytesOfAProjectDocumentComeBackUntouched() {
        stored("proj", PNG);

        Optional<BinaryLookupResult> hit = service.lookupCascadeBytes("acme", "proj", PATH, 1024);

        assertThat(hit).isPresent();
        assertThat(hit.get().content()).containsExactly(PNG);
        assertThat(hit.get().source()).isEqualTo(LookupResult.Source.PROJECT);
    }

    @Test
    void theProjectLayerBeatsTheTenantLayer() {
        stored("proj", PNG);
        stored("_tenant", new byte[] {1, 2, 3});

        assertThat(service.lookupCascadeBytes("acme", "proj", PATH, 1024)
                        .orElseThrow()
                        .source())
                .isEqualTo(LookupResult.Source.PROJECT);
    }

    @Test
    void theTenantLayerAnswersWhenTheProjectHasNone() {
        stored("_tenant", PNG);

        Optional<BinaryLookupResult> hit = service.lookupCascadeBytes("acme", "proj", PATH, 1024);

        assertThat(hit.orElseThrow().source()).isEqualTo(LookupResult.Source.VANCE);
    }

    @Test
    void theClasspathIsTheLastResort() throws Exception {
        Resource resource = new ByteArrayResource(PNG);
        when(resolver.getResource("classpath:" + DocumentService.RESOURCE_PREFIX + PATH))
                .thenReturn(resource);

        Optional<BinaryLookupResult> hit = service.lookupCascadeBytes("acme", "proj", PATH, 1024);

        assertThat(hit.orElseThrow().content()).containsExactly(PNG);
        assertThat(hit.get().source()).isEqualTo(LookupResult.Source.RESOURCE);
    }

    @Test
    void aDocumentLargerThanTheBoundCountsAsNotFound() {
        stored("proj", PNG);

        assertThat(service.lookupCascadeBytes("acme", "proj", PATH, PNG.length - 1))
                .isEmpty();
    }

    @Test
    void aResourceLargerThanTheBoundCountsAsNotFound() {
        when(resolver.getResource(anyString())).thenReturn(new ByteArrayResource(PNG));

        assertThat(service.lookupCascadeBytes("acme", "proj", PATH, PNG.length - 1))
                .isEmpty();
    }

    @Test
    void nothingAnywhereIsNotFound() {
        when(resolver.getResource(anyString())).thenReturn(new ByteArrayResource(new byte[0]) {
            @Override
            public boolean exists() {
                return false;
            }
        });

        assertThat(service.lookupCascadeBytes("acme", "proj", PATH, 1024)).isEmpty();
    }
}
