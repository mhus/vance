package de.mhus.vance.addon.brain.workbook.action;

import de.mhus.vance.addon.brain.workbook.WorkbookScriptService;
import de.mhus.vance.toolpack.ToolException;
import org.springframework.stereotype.Component;

/**
 * {@code vance-button} {@code type: script} — the original button behaviour,
 * now routed through the action registry like every other type: runs the
 * project {@code .js} document named by the fence's {@code script} key,
 * synchronously in-JVM. Delegates to {@link WorkbookScriptService}.
 */
@Component
public class ScriptButtonActionHandler implements ButtonActionHandler {

    private final WorkbookScriptService scriptService;

    public ScriptButtonActionHandler(WorkbookScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @Override
    public String type() {
        return "script";
    }

    @Override
    public ButtonActionResult run(ButtonActionContext ctx) {
        Object script = ctx.buttonConfig().get("script");
        if (script == null || script.toString().isBlank()) {
            throw new ToolException("button type 'script' requires a `script` key (a .js document path).");
        }
        scriptService.run(ctx.tenantId(), ctx.projectId(), script.toString(), ctx.editorId());
        return new ButtonActionResult(null);
    }
}
