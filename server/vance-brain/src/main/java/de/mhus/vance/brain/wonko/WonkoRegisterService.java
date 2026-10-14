package de.mhus.vance.brain.wonko;

import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Persistence of the review register in project memory (plan §5.1): one chain
 * of {@link MemoryKind#REVIEW} entries per subject, keyed by
 * {@code metadata.subjectKey}. Every save writes a new entry and supersedes
 * the previous one — the supersede chain is the round history, like
 * {@code PLAN}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WonkoRegisterService {

    static final String META_SUBJECT_KEY = "subjectKey";

    private static final String DOCUMENTS_PREFIX = "documents:";

    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    /** Stable key of a document subject: kind + sorted refs. */
    public static String subjectKey(List<String> refs) {
        List<String> sorted = new ArrayList<>(refs);
        sorted.sort(String::compareTo);
        return DOCUMENTS_PREFIX + String.join(",", sorted);
    }

    /** The current register of the subject, or a fresh one. */
    public WonkoRegister loadOrCreate(String tenantId, String projectId, String subjectKey, List<String> refs) {
        Optional<MemoryDocument> current = memoryService.activeProjectEntryByMetadata(
                tenantId, projectId, MemoryKind.REVIEW, META_SUBJECT_KEY, subjectKey);
        if (current.isPresent()) {
            WonkoRegister register = objectMapper.readValue(current.get().getContent(), WonkoRegister.class);
            register.setSubjectRefs(new ArrayList<>(refs));
            return register;
        }
        WonkoRegister register = new WonkoRegister();
        register.setSubjectKey(subjectKey);
        register.setSubjectRefs(new ArrayList<>(refs));
        return register;
    }

    /** Writes the register as the new head of its chain. */
    public void save(String tenantId, String projectId, WonkoRegister register) {
        Optional<MemoryDocument> previous = memoryService.activeProjectEntryByMetadata(
                tenantId, projectId, MemoryKind.REVIEW, META_SUBJECT_KEY, register.getSubjectKey());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(META_SUBJECT_KEY, register.getSubjectKey());
        MemoryDocument saved = memoryService.save(MemoryDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .kind(MemoryKind.REVIEW)
                .title("Review register: " + String.join(", ", register.getSubjectRefs()))
                .content(objectMapper.writeValueAsString(register))
                .metadata(metadata)
                .build());
        previous.ifPresent(p -> memoryService.supersede(p.getId(), saved.getId()));
        log.debug(
                "Wonko register saved project='{}' subject='{}' topics={} id='{}'",
                projectId,
                register.getSubjectKey(),
                register.getTopics().size(),
                saved.getId());
    }
}
