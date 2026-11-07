package de.mhus.vance.brain.servicecatalog;

import de.mhus.vance.brain.magrathea.ToolFailure;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * What a polling trigger of the service catalog does on one tick: ask the service, keep the entries not seen
 * before, and say what to remember once they have been taken care of.
 *
 * <p>The two halves are separate on purpose. {@link #poll} only reads; {@link #commit} writes what was seen. A
 * workflow runs poll, starts a run per entry, and commits last, so a tick that dies in between is repeated and
 * an entry is delivered at least once rather than possibly never. What is remembered is a state document
 * ({@code _vance/trigger-state/<name>.yaml}): the ids seen (the most recent {@value #MAX_SEEN}) and the cursor.
 */
@Service
@RequiredArgsConstructor
public class CatalogPollService {

    /** Folder of the state documents. */
    public static final String STATE_PREFIX = "_vance/trigger-state/";

    /** How many ids are remembered; older ones fall out, so an entry that old would be delivered again. */
    static final int MAX_SEEN = 1000;

    private final ServiceCatalogLoader catalog;
    private final DocumentService documents;
    private final ToolDispatcher tools;
    private final CatalogToolResolver resolver;

    /**
     * What a tick found.
     *
     * @param items   entries not seen before, in the order the service gave them
     * @param pending what {@link #commit} has to remember
     * @param failure the service's own answer when it was an error ({@code ok: false}); the entries and
     *                {@code pending} are then empty. Handed on as it is so the workflow classifies it (429 →
     *                retry, 401 → sign in again)
     */
    public record Polled(
            List<Object> items, Pending pending, @Nullable Map<String, Object> failure) {}

    /** The state to write once the entries are taken care of. */
    public record Pending(List<String> ids, @Nullable String cursor) {}

    public Polled poll(
            String tenantId,
            String projectId,
            @Nullable String userId,
            String name,
            String from,
            Map<String, String> inputs) {
        CatalogPollService.Located located = locate(tenantId, projectId, from);
        ServiceCatalogEntry.Poll poll = located.poll();
        State state = readState(tenantId, projectId, name);

        Map<String, String> values = new LinkedHashMap<>();
        inputs.forEach((k, v) -> values.put("input." + k, v));
        values.put("cursor", state.cursor() == null ? "" : state.cursor());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = poll.params() == null
                ? Map.of()
                : (Map<String, Object>) CatalogTriggerService.substitute(poll.params(), values);
        ToolInvocationContext context =
                new ToolInvocationContext(tenantId, projectId, /* sessionId */ null, /* processId */ null, userId);
        // The operation may name its pack by an input: a mailbox is called what its operator called it.
        String operation = String.valueOf(CatalogTriggerService.substitute(poll.tool(), values));
        Map<String, Object> result =
                tools.invoke(resolver.resolve(located.entry(), operation, context), params, context);
        if (ToolFailure.classifyResult(result).isPresent()) {
            return new Polled(List.of(), new Pending(state.seen(), state.cursor()), result);
        }

        Object found = CatalogTriggerService.valueAt(result, poll.itemsFrom());
        if (!(found instanceof List<?> entries)) {
            throw new ToolException("'" + poll.tool() + "' returned no list at '" + poll.itemsFrom() + "'");
        }
        Set<String> seen = new LinkedHashSet<>(state.seen());
        List<Object> fresh = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new ToolException("An entry of '" + poll.tool() + "' is not an object");
            }
            @SuppressWarnings("unchecked")
            Object id = CatalogTriggerService.valueAt((Map<String, Object>) map, poll.idFrom());
            if (id == null) {
                throw new ToolException("An entry of '" + poll.tool() + "' has no id at '" + poll.idFrom() + "'");
            }
            if (seen.add(String.valueOf(id))) {
                fresh.add(entry);
            }
        }
        // The first look at a service is a baseline, not news: its whole history would otherwise start a run
        // per entry.
        if (!state.exists() && "skip".equals(poll.initial())) {
            fresh = List.of();
        }
        Object cursorValue =
                poll.cursorFrom() == null ? null : CatalogTriggerService.valueAt(result, poll.cursorFrom());
        String cursor = cursorValue == null ? state.cursor() : String.valueOf(cursorValue);
        List<String> remembered = new ArrayList<>(seen);
        if (remembered.size() > MAX_SEEN) {
            remembered = new ArrayList<>(remembered.subList(remembered.size() - MAX_SEEN, remembered.size()));
        }
        return new Polled(fresh, new Pending(remembered, cursor), null);
    }

    /** Remembers what {@link #poll} said; run once the entries have been taken care of. */
    public void commit(String tenantId, String projectId, String name, Pending pending) {
        Map<String, Object> doc = new LinkedHashMap<>();
        if (pending.cursor() != null) doc.put("cursor", pending.cursor());
        doc.put("seen", pending.ids());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        documents.upsertText(
                tenantId,
                projectId,
                STATE_PREFIX + name + ".yaml",
                name,
                List.of("trigger-state"),
                new Yaml(options).dump(doc),
                "system",
                WriteActor.SYSTEM);
    }

    /** Removes the state of a trigger that is taken away; nothing to do when it never polled. */
    public void forget(String tenantId, String projectId, String name) {
        documents
                .findByPath(tenantId, projectId, STATE_PREFIX + name + ".yaml")
                .ifPresent(d -> documents.delete(d.getId(), WriteActor.SYSTEM));
    }

    private record Located(ServiceCatalogEntry entry, ServiceCatalogEntry.Poll poll) {}

    private Located locate(String tenantId, String projectId, String from) {
        if (from.indexOf('/') < 0) throw new ToolException("'from' is <service>/<trigger>, not '" + from + "'");
        return catalog.locate(tenantId, projectId, from)
                .filter(l -> l.trigger().poll() != null)
                .map(l -> new Located(l.entry(), l.trigger().poll()))
                .orElseThrow(() -> new ToolException("'" + from + "' is not a polling trigger of the catalog"));
    }

    private record State(
            boolean exists, List<String> seen, @Nullable String cursor) {}

    @SuppressWarnings("unchecked")
    private State readState(String tenantId, String projectId, String name) {
        Optional<DocumentDocument> doc = documents.findByPath(tenantId, projectId, STATE_PREFIX + name + ".yaml");
        if (doc.isEmpty()) return new State(false, List.of(), null);
        Object loaded = new Yaml().load(documents.readContent(doc.get()));
        if (!(loaded instanceof Map<?, ?> map)) return new State(true, List.of(), null);
        List<String> seen = new ArrayList<>();
        if (map.get("seen") instanceof List<?> list) {
            for (Object id : list) seen.add(String.valueOf(id));
        }
        Object cursor = ((Map<String, Object>) map).get("cursor");
        return new State(true, List.copyOf(seen), cursor == null ? null : String.valueOf(cursor));
    }
}
