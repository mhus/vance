package de.mhus.vance.addon.brain.journal.tool;

import de.mhus.vance.addon.brain.journal.JournalConfig;
import de.mhus.vance.addon.brain.journal.JournalFolderReader;
import de.mhus.vance.addon.brain.journal.JournalService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Write or amend a journal entry for a given day. {@code date} defaults to
 * today; re-running the same date updates that day's entry (the codec
 * merges the body server-side) rather than creating a duplicate.
 */
@Component
@Slf4j
public class JournalEntryCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The journal root folder (contains _app.yaml)."));
                put("date", Map.of("type", "string",
                        "description", "ISO date yyyy-MM-dd. Defaults to today."));
                put("body", Map.of("type", "string",
                        "description", "Markdown prose for the day."));
                put("title", Map.of("type", "string"));
                put("mood", Map.of("type", "string",
                        "description", "great | good | neutral | low | bad (free-form allowed)."));
                put("tags", Map.of("type", "array", "items", Map.of("type", "string")));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final JournalFolderReader folderReader;
    private final JournalService journalService;

    public JournalEntryCreateTool(EddieContext eddieContext,
                                  JournalFolderReader folderReader,
                                  JournalService journalService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.journalService = journalService;
    }

    @Override public String name() { return "journal_entry_create"; }

    @Override
    public String description() {
        return "Write or amend a journal (diary) entry for one day. date defaults to "
                + "today; re-running the same date updates that day rather than duplicating. "
                + "Pass mood (great/good/neutral/low/bad) and tags to make it searchable. "
                + "Run app_rebuild('folder') afterwards to refresh the index + stats.";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "write", "document", "journal");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String date = paramString(params, "date");
        if (date == null) date = LocalDate.now().toString();

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        JournalConfig config = folderReader.scan(
                ctx.tenantId(), project.getName(), folder).config();

        DocumentDocument stored = journalService.upsertEntry(
                ctx.tenantId(), project.getName(), folder, config, date,
                paramString(params, "body"),
                paramString(params, "title"),
                paramString(params, "mood"),
                paramStringList(params, "tags"),
                ctx.userId());

        log.info("JournalEntryCreateTool folder='{}' date='{}' path='{}'",
                folder, date, stored.getPath());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("id", stored.getId());
        result.put("date", date);
        result.put("nextStep", "Run `app_rebuild('" + folder + "')` to refresh the index + stats.");
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static @Nullable List<String> paramStringList(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String part : s.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
            return out;
        }
        return null;
    }
}
