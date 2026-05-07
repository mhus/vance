package de.mhus.vance.brain.cluster;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Picks a fresh node-name for a brain pod by joining two entries from
 * the {@code cluster-node-names.txt} dictionary with a hyphen
 * ({@code "maya-prosser"}). Loaded once at boot; subsequent calls are
 * pure in-memory.
 *
 * <p>With ~350 names the namespace is ~123k two-word combinations, so
 * collisions on the {@code (clusterId, nodeName)} unique index are
 * extremely rare. The cluster service still retries on collision so
 * the absolute worst case (paranoid: tiny cluster of pods, hash
 * pigeon-hole etc.) doesn't wedge a boot.
 */
@Component
@Slf4j
public class ClusterNodeNameGenerator {

    private static final String DICT_RESOURCE = "cluster-node-names.txt";

    private List<String> dictionary = List.of();

    @PostConstruct
    void load() {
        ClassPathResource res = new ClassPathResource(DICT_RESOURCE);
        if (!res.exists()) {
            throw new IllegalStateException(
                    "Cluster node-name dictionary missing on classpath: " + DICT_RESOURCE);
        }
        List<String> names = new ArrayList<>();
        try (InputStream in = res.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.indexOf('-') >= 0) {
                    // The joiner uses '-', so an entry containing '-' would make
                    // the resulting two-word name ambiguous. Reject at load time
                    // so a typo in the dictionary fails fast rather than later.
                    throw new IllegalStateException(
                            "Cluster node-name dictionary entry contains a hyphen: '" + trimmed + "'");
                }
                names.add(trimmed);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + DICT_RESOURCE, e);
        }
        if (names.size() < 32) {
            throw new IllegalStateException(
                    "Cluster node-name dictionary has too few entries (" + names.size()
                            + ") to give meaningful collision resistance");
        }
        this.dictionary = Collections.unmodifiableList(names);
        log.info("ClusterNodeNameGenerator: loaded {} dictionary entries", names.size());
    }

    /** Picks two random entries and joins them with a hyphen. */
    public String generate() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String first = dictionary.get(rng.nextInt(dictionary.size()));
        String second = dictionary.get(rng.nextInt(dictionary.size()));
        return first + "-" + second;
    }

    /** Visible to tests. */
    int dictionarySize() {
        return dictionary.size();
    }
}
