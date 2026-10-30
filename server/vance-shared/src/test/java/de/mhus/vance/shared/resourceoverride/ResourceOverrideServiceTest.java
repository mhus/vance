package de.mhus.vance.shared.resourceoverride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class ResourceOverrideServiceTest {

    private ResourceOverrideRepository repository;
    private MongoTemplate mongo;
    private ResourceOverrideService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResourceOverrideRepository.class);
        mongo = mock(MongoTemplate.class);
        service = new ResourceOverrideService(repository, mongo);
        when(repository.insert(any(ResourceOverrideDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ResourceOverrideDocument version(long number) {
        return ResourceOverrideDocument.builder()
                .type("model")
                .version(number)
                .enabled(true)
                .build();
    }

    @Test
    void publish_firstVersion_startsAtOneAndIsEnabled() {
        ResourceOverrideDocument doc = service.publish(
                "model",
                List.of(new ResourceOverrideFile("gw/_provider.yaml", "wireType: openai")),
                true,
                "ops",
                "first");

        assertThat(doc.getVersion()).isEqualTo(1);
        assertThat(doc.isEnabled()).isTrue();
        assertThat(doc.isOverwrite()).isTrue();
        assertThat(doc.getFileCount()).isEqualTo(1);
        assertThat(doc.getDigest()).hasSize(64);
        assertThat(doc.getCreatedBy()).isEqualTo("ops");
    }

    @Test
    void publish_followsTheHighestVersion() {
        when(mongo.findOne(any(Query.class), eq(ResourceOverrideDocument.class)))
                .thenReturn(version(7));

        assertThat(service.publish("model", List.of(), true, null, null).getVersion())
                .isEqualTo(8);
    }

    @Test
    void publish_overwriteFalse_dropsTheFiles() {
        ResourceOverrideDocument doc =
                service.publish("model", List.of(new ResourceOverrideFile("a/b.yaml", "x")), false, null, "off");

        assertThat(doc.isOverwrite()).isFalse();
        assertThat(doc.getFiles()).isEmpty();
        assertThat(doc.getFileCount()).isZero();
    }

    @Test
    void publish_versionTakenByAnotherWriter_retriesWithTheNextNumber() {
        when(repository.insert(any(ResourceOverrideDocument.class)))
                .thenThrow(new DuplicateKeyException("taken"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.publish("model", List.of(), true, null, null);

        verify(repository, times(2)).insert(any(ResourceOverrideDocument.class));
    }

    @Test
    void publish_alwaysClashing_givesUp() {
        when(repository.insert(any(ResourceOverrideDocument.class))).thenThrow(new DuplicateKeyException("taken"));

        assertThatThrownBy(() -> service.publish("model", List.of(), true, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publish_unregisteredType_isRefusedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.publish("nonsense", List.of(), true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");
        verify(repository, never()).insert(any(ResourceOverrideDocument.class));
    }

    @Test
    void effective_noEnabledVersion_isEmpty() {
        when(mongo.findOne(any(Query.class), eq(ResourceOverrideDocument.class)))
                .thenReturn(null);

        assertThat(service.effective("model")).isEmpty();
    }

    @Test
    void effective_returnsTheValidVersionWithoutFiles() {
        ResourceOverrideDocument doc = version(4);
        doc.setOverwrite(true);
        doc.setDigest("abc");
        when(mongo.findOne(any(Query.class), eq(ResourceOverrideDocument.class)))
                .thenReturn(doc);

        ResourceOverrideRef ref = service.effective("model").orElseThrow();

        assertThat(ref.version()).isEqualTo(4);
        assertThat(ref.overwrite()).isTrue();
        assertThat(ref.key()).isEqualTo("4:true");
    }

    @Test
    void prune_keepsTheNewestAndNeverTheValidVersion() {
        // v5 is newest but disabled, v3 is the valid one and falls outside "keep 2".
        ResourceOverrideDocument valid = version(3);
        when(mongo.findOne(any(Query.class), eq(ResourceOverrideDocument.class)))
                .thenReturn(valid);
        when(mongo.find(any(Query.class), eq(ResourceOverrideDocument.class)))
                .thenReturn(List.of(version(5), version(4), version(3), version(2), version(1)));

        int deleted = service.prune("model", 2);

        assertThat(deleted).isEqualTo(2);
        ArgumentCaptor<Long> versions = ArgumentCaptor.forClass(Long.class);
        verify(repository, times(2)).deleteByTypeAndVersion(eq("model"), versions.capture());
        assertThat(versions.getAllValues()).containsExactly(2L, 1L);
    }
}
