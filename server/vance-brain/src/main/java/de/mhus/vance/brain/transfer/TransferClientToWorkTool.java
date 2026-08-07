package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferFileAttrs;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * LLM-facing tool: triggers the connected Foot client to upload a
 * local file into the brain workspace. The brain sends a request to
 * Foot, Foot reads {@code localPath} from its workspace root and
 * streams chunks back; the brain writes them under
 * {@code dirName/remotePath}.
 *
 * <p>Deliberately <b>not</b> in the {@code client_file_*} namespace:
 * both endpoints are fixed (foot host → brain workspace), so there is
 * nothing for {@code WorkTargetDispatcher} to resolve and no sibling
 * {@code work_*} backend to pair with. The {@code transfer_*} prefix
 * marks that third category — see {@code planning/tool-naming-sweep.md}
 * §1 and §3.
 */
@Component
public class TransferClientToWorkTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 5L * 60 * 1000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "localPath", Map.of(
                            "type", "string",
                            "description",
                                    "Path on the Foot host, relative to the foot "
                                            + "workspace root."),
                    "dirName", Map.of(
                            "type", "string",
                            "description", "Brain workspace RootDir to write into."),
                    "remotePath", Map.of(
                            "type", "string",
                            "description", "Path inside the RootDir."),
                    "mode", Map.of(
                            "type", "string",
                            "description",
                                    "Optional POSIX mode in octal (e.g. \"0644\"). "
                                            + "AND-ed against the brain mode mask.")),
            "required", List.of("localPath", "dirName", "remotePath"));

    private final BrainTransferService transfers;

    public TransferClientToWorkTool(BrainTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String name() {
        return "transfer_client_to_work";
    }

    @Override
    public String description() {
        return "Pull a file from the user's local disk (foot host) into "
                + "the brain workspace. Use it to ingest user-supplied "
                + "files for downstream processing. Returns when the "
                + "file is fully received and verified.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String localPath = stringOrThrow(params, "localPath");
        String dirName = stringOrThrow(params, "dirName");
        String remotePath = stringOrThrow(params, "remotePath");
        String mode = stringOrNull(params, "mode");
        if (ctx.sessionId() == null) {
            throw new ToolException("transfer_client_to_work requires a bound session");
        }
        if (ctx.projectId() == null) {
            throw new ToolException("transfer_client_to_work requires a project context");
        }
        TransferFileAttrs attrs = mode == null ? null
                : TransferFileAttrs.builder().mode(mode).build();

        TransferResult result;
        try {
            result = transfers.startUpload(
                    ctx.sessionId(), ctx.tenantId(), ctx.projectId(), dirName, remotePath, localPath, attrs)
                    .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ToolException("transfer timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("transfer interrupted");
        } catch (Exception e) {
            throw new ToolException("transfer failed: " + e.getMessage(), e);
        }
        if (!result.ok()) {
            throw new ToolException(result.error() != null ? result.error() : "transfer failed");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("localPath", localPath);
        out.put("dirName", dirName);
        out.put("remotePath", remotePath);
        out.put("bytesWritten", result.bytesWritten());
        out.put("hash", result.hash());
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
