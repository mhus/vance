package de.mhus.vance.exec;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a read-only directory projection. {@code children} is {@code null}
 * for files and for directories listed with no depth left.
 */
public record TreeNode(
        String name,
        String path,
        boolean directory,
        long size,
        @Nullable Instant lastModified,
        @Nullable List<TreeNode> children) {}
