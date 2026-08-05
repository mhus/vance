package de.mhus.vance.shared.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Behaviour tests for {@link MemoryService}. Mongo is stubbed — the tests
 * verify the cleanup delegates to the right repository query, not Mongo
 * behaviour.
 */
class MemoryServiceTest {

    private MemoryRepository repository;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryRepository.class);
        service = new MemoryService(repository, mock(MongoTemplate.class));
    }

    @Test
    void deleteBySession_dropsEverySessionScopedMemory_andReturnsCount() {
        when(repository.deleteByTenantIdAndSessionId("acme", "sess-1")).thenReturn(4L);

        long n = service.deleteBySession("acme", "sess-1");

        assertThat(n).isEqualTo(4L);
        verify(repository).deleteByTenantIdAndSessionId("acme", "sess-1");
    }
}
