package de.mhus.vance.foot.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Files the user staged with {@code /attach}, waiting for the next chat
 * message to carry them.
 *
 * <p>Two-step by design, mirroring the web composer: attaching and
 * sending are separate acts, so several files can be collected and the
 * message written afterwards. The queue is cleared by the send — an
 * attachment rides on the turn that submitted it and no other, which is
 * the contract {@code ProcessSteerRequest.attachments} documents.
 *
 * <p>Paths only; bytes are read at send time. Staging a file and then
 * editing it before hitting return sends the edited version, which is
 * the behaviour a terminal user expects.
 *
 * <p>Single-threaded in practice (REPL input thread), but {@code /attach}
 * runs on the command dispatcher while the send runs on the input loop,
 * so the list is synchronized.
 */
@Service
public class PendingAttachmentService {

    private final List<Path> staged = new ArrayList<>();

    /**
     * Stages {@code path}. Returns the resolved absolute path, or throws
     * when the file is missing or unreadable — failing here rather than
     * at send time keeps the error next to the command that caused it.
     */
    public synchronized Path stage(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("no such file: " + resolved);
        }
        if (!Files.isReadable(resolved)) {
            throw new IllegalArgumentException("not readable: " + resolved);
        }
        staged.add(resolved);
        return resolved;
    }

    /** Staged files in the order they were added. */
    public synchronized List<Path> staged() {
        return List.copyOf(staged);
    }

    public synchronized boolean isEmpty() {
        return staged.isEmpty();
    }

    public synchronized int count() {
        return staged.size();
    }

    /**
     * Takes everything staged and empties the queue. Called by the send
     * path; the queue must be empty afterwards even when the upload
     * fails, otherwise a broken file would ride along on every
     * subsequent message.
     */
    public synchronized List<Path> drain() {
        List<Path> out = List.copyOf(staged);
        staged.clear();
        return out;
    }

    /** Drops everything without sending. Returns how many were discarded. */
    public synchronized int clear() {
        int n = staged.size();
        staged.clear();
        return n;
    }
}
