package de.mhus.vance.brain.trillian;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Durable appointments of one Trillian, one YAML per schedule in its home:
 * {@code _vance/trillian/schedules/<name>.yaml}.
 *
 * <p><b>One concept.</b> A schedule is a wakeup with a self-updating
 * {@code due}: the scanner only compares {@code due <= now}, and after a
 * fire the service rewrites {@code due} from {@code next} <em>computed from
 * now</em> — so missed occurrences never materialise and nothing piles up
 * (D10). Without {@code next} the entry is a single wakeup and goes to
 * {@code enabled: false} after firing.
 *
 * <p><b>Not the Ursa scheduler.</b> Deliberately a different document
 * family and path ({@code _vance/trillian/…}, not {@code _vance/scheduler/…})
 * — those documents are <em>runs</em> Ursa spawns in system sessions; these
 * are events handed to the loop's own turns through its self-check. Same
 * idea, different consumer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrillianScheduleStore {

    private static final String FOLDER = "_vance/trillian/schedules/";
    private static final String DOC_TITLE_PREFIX = "Trillian schedule — ";
    private static final List<String> TAGS = List.of("trillian", "schedule");

    /** Floor for recurring entries: at most every five minutes. */
    public static final long MIN_EVERY_SECONDS = 300;

    private static final String HEADER = """
            # Trillian schedule — an appointment the loop keeps for itself.
            #
            # due:    next time this comes up (ISO-8601). The scanner only compares.
            # next:   optional recurrence (e.g. 30m, 2h, 1d). After a fire the next
            #         due is computed FROM NOW — missed runs never accumulate.
            #         Without next this is a one-shot and disables itself after firing.
            # label:  one line for the self-check frame.
            # payload: what to do when it comes up.
            # enabled: false parks the entry without deleting it.
            """;

    private final DocumentService documentService;

    /** One appointment, as stored. */
    public record Schedule(
            String name,
            @Nullable String label,
            Instant due,
            @Nullable String next,
            @Nullable String payload,
            boolean enabled,
            @Nullable Instant lastRun) {}

    /** Document path for a schedule name, exposed for tests and log lines. */
    public static String pathFor(String name) {
        return FOLDER + name + ".yaml";
    }

    /** All schedules filed under this project, broken ones skipped. */
    public List<Schedule> list(String tenantId, String projectId) {
        List<Schedule> out = new ArrayList<>();
        for (DocumentDocument doc : documentService.listUnderFolder(tenantId, projectId, FOLDER)) {
            Schedule s = parse(doc);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** One schedule by name, or empty when never stored / broken. */
    public Optional<Schedule> find(String tenantId, String projectId, String name) {
        try {
            Optional<DocumentDocument> doc = documentService.findByPath(tenantId, projectId, pathFor(name));
            return doc.map(this::parse).filter(s -> s != null);
        } catch (RuntimeException e) {
            log.warn("Trillian: could not read schedule '{}': {}", name, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Writes the schedule document. The recurrence floor is enforced here:
     * an {@code every} under five minutes is rejected, not silently rounded.
     */
    public void save(String tenantId, String projectId, Schedule schedule) {
        if (schedule.next() != null && parseEverySeconds(schedule.next()) < MIN_EVERY_SECONDS) {
            throw new IllegalArgumentException("Schedule '" + schedule.name() + "': recurrence '" + schedule.next()
                    + "' is under the 5-minute floor");
        }
        try {
            documentService.upsertText(
                    tenantId,
                    projectId,
                    pathFor(schedule.name()),
                    DOC_TITLE_PREFIX + schedule.name(),
                    TAGS,
                    HEADER + "\n" + dump(schedule),
                    /*createdBy*/ null,
                    WriteActor.SYSTEM);
        } catch (RuntimeException e) {
            log.warn("Trillian: could not persist schedule '{}': {}", schedule.name(), e.toString());
        }
    }

    public void delete(String tenantId, String projectId, String name) {
        try {
            documentService.findByPath(tenantId, projectId, pathFor(name)).ifPresent(doc -> {
                documentService.delete(doc.getId(), WriteActor.SYSTEM);
                log.info("Trillian: removed schedule {}", pathFor(name));
            });
        } catch (RuntimeException e) {
            log.warn("Trillian: could not remove schedule '{}': {}", name, e.toString());
        }
    }

    /**
     * The next fire after {@code now} for a recurrence like {@code 30m},
     * {@code 2h}, {@code 1d}. Computed from now on purpose (D10) — a missed
     * run must not shift the whole series.
     */
    public static Instant nextDue(String every, Instant now) {
        return now.plus(Duration.ofSeconds(parseEverySeconds(every)));
    }

    /** {@code <n>m|h|d} in seconds; throws on anything else. */
    public static long parseEverySeconds(@Nullable String every) {
        if (every == null || every.isBlank()) {
            throw new IllegalArgumentException("Recurrence is empty");
        }
        String e = every.trim().toLowerCase(Locale.ROOT);
        long factor;
        if (e.endsWith("m")) {
            factor = 60;
        } else if (e.endsWith("h")) {
            factor = 3600;
        } else if (e.endsWith("d")) {
            factor = 86400;
        } else {
            throw new IllegalArgumentException("Recurrence '" + every + "' must be <n>m, <n>h or <n>d");
        }
        try {
            long n = Long.parseLong(e.substring(0, e.length() - 1));
            if (n <= 0) {
                throw new IllegalArgumentException("Recurrence '" + every + "' must be positive");
            }
            return n * factor;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Recurrence '" + every + "' is not a number", ex);
        }
    }

    private @Nullable Schedule parse(DocumentDocument doc) {
        try {
            String text = documentService.readContent(doc);
            if (text == null || text.isBlank()) {
                return null;
            }
            Object parsed = new Yaml().load(text);
            if (!(parsed instanceof Map<?, ?> map)) {
                return null;
            }
            String name = nameOf(doc.getPath());
            Instant due = instantOf(map.get("due"));
            if (due == null) {
                log.warn("Trillian: schedule '{}' has no due — ignoring", name);
                return null;
            }
            return new Schedule(
                    name,
                    str(map.get("label")),
                    due,
                    str(map.get("next")),
                    str(map.get("payload")),
                    !Boolean.FALSE.equals(map.get("enabled")),
                    instantOf(map.get("lastRun")));
        } catch (RuntimeException e) {
            log.warn("Trillian: could not parse schedule '{}': {}", doc.getPath(), e.toString());
            return null;
        }
    }

    private static String nameOf(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".yaml") ? file.substring(0, file.length() - 5) : file;
    }

    private static @Nullable String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static @Nullable Instant instantOf(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v.toString().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String dump(Schedule s) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (s.label() != null) {
            map.put("label", s.label());
        }
        map.put("due", s.due().toString());
        if (s.next() != null) {
            map.put("next", s.next());
        }
        if (s.payload() != null) {
            map.put("payload", s.payload());
        }
        map.put("enabled", s.enabled());
        if (s.lastRun() != null) {
            map.put("lastRun", s.lastRun().toString());
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setSplitLines(false);
        return new Yaml(options).dump(map);
    }
}
