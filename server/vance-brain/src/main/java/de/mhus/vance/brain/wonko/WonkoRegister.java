package de.mhus.vance.brain.wonko;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The review register of one subject — persisted in project memory
 * ({@code MemoryKind.REVIEW}) so it outlives the run (plan §5).
 */
@Data
@NoArgsConstructor
public class WonkoRegister {

    private String subjectKey = "";

    private List<String> subjectRefs = new ArrayList<>();

    /** Rounds asked over all runs on this subject. */
    private int rounds;

    private WonkoMandate mandate = new WonkoMandate();

    private List<WonkoMandateChange> mandateLog = new ArrayList<>();

    private List<WonkoTopic> topics = new ArrayList<>();

    public Optional<WonkoTopic> topic(String key) {
        return topics.stream().filter(t -> t.getKey().equals(key)).findFirst();
    }

    /**
     * The settled topic closing {@code key}: the topic itself or an ancestor in
     * the key hierarchy ("good as it is" closes the whole branch, §4.4).
     */
    public Optional<WonkoTopic> settledAncestor(String key) {
        return topics.stream()
                .filter(t -> t.getStatus() == WonkoTopicStatus.SETTLED)
                .filter(t -> key.equals(t.getKey()) || key.startsWith(t.getKey() + "/"))
                .findFirst();
    }
}
