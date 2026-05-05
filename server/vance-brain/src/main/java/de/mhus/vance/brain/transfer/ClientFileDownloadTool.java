package de.mhus.vance.brain.transfer;

import de.mhus.vance.api.transfer.TransferFileAttrs;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * LLM-facing tool: pushes a file from the brain workspace to the
 * connected Foot client's disk. The brain reads the file from
 * {@code dirName/remotePath} and streams chunks to Foot, which writes
 * to {@code localPath} relative to its workspace root.
 */
@Component
public class ClientFileDownloadTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 5L * 60 * 1000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "dirName", Map.of(
                            "type", "string",
                            "description", "Brain workspace RootDir to read from."),
                    "remotePath", Map.of(
                            "type", "string",
                            "description", "Path inside the RootDir."),
                    "localPath", Map.of(
                            "type", "string",
                            "description",
                                    "Path on the Foot host, relative to the foot "
                                            + "workspace root. Sub-directories are "
                                            + "created automatically."),
                    "mode", Map.of(
                            "type", "string",
                            "description",
                                    "Optional POSIX mode in octal (e.g. \"0755\"). "
                                            + "AND-ed against the foot mode mask.")),
            "required", List.of("dirName", "remotePath", "localPath"));

    private final BrainTransferService transfers;

    public ClientFileDownloadTool(BrainTransferService transfers) {
        this.transfers = transfers;
    }

    @Override
    public String name() {
        return "client_file_download";
    }

    @Override
    public String description() {
        return "Push a file from the brain workspace to the user's local "
                + "disk (foot host). Use it to deliver generated artefacts "
                + "to the user. Returns when the file is fully written and "
                + "verified.";
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
        String dirName = stringOrThrow(params, "dirName");
        String remotePath = stringOrThrow(params, "remotePath");
        String localPath = stringOrThrow(params, "localPath");
        String mode = stringOrNull(params, "mode");
        if (ctx.sessionId() == null) {
            throw new ToolException("client_file_download requires a bound session");
        }
        if (ctx.projectId() == null) {
            throw new ToolException("client_file_download requires a project context");
        }
        TransferFileAttrs attrs = mode == null ? null
                : TransferFileAttrs.builder().mode(mode).build();

        TransferResult result;
        try {
            result = transfers.startDownload(
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
        out.put("dirName", dirName);
        out.put("remotePath", remotePath);
        out.put("localPath", localPath);
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
