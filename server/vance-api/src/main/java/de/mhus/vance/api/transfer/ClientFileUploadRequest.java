package de.mhus.vance.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Brain → Foot trigger to start an upload. Foot reads
 * {@link #localPath} from its workspace sandbox, computes size + hash,
 * and replies with {@link TransferInit} (Foot is now the sender). On
 * local failure (file missing, sandbox violation) Foot answers
 * directly with {@link TransferFinish} carrying the error.
 *
 * <p>{@link #attrs} is optional. If absent, Foot derives mode + mtime
 * from the local file.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("transfer")
public class ClientFileUploadRequest {

    private String transferId;

    /** Foot-side path, relative to {@code vance.foot.workspace.root}. */
    private String localPath;

    /** Brain workspace RootDir to write into. */
    private String dirName;

    /** Path within the RootDir. */
    private String remotePath;

    private @Nullable TransferFileAttrs attrs;
}
