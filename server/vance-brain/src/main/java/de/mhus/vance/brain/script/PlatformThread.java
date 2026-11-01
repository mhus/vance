package de.mhus.vance.brain.script;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs work that enters a GraalJS context on a platform thread.
 *
 * <p>The sandbox CPU limit ({@code sandbox.MaxCPUTime}) is enforced by sampling
 * {@code ThreadMXBean.getThreadCpuTime} of every thread that is inside the
 * shared engine. A virtual thread has no CPU time, the call answers {@code -1},
 * and Truffle stops with {@code ShouldNotReachHere}. The failure is not limited
 * to the context that carries the limit: once the shared engine has seen one, a
 * parse-only validation or a {@code close} issued from a virtual thread fails
 * the same way. The brain runs most of its work on virtual threads, so every
 * place that touches a context from a caller thread goes through here.
 *
 * <p>On a platform thread the work runs inline; on a virtual thread it is handed
 * to a small pool of daemon platform threads and the caller waits, which a
 * virtual thread does without holding a carrier. The wait is uninterruptible and
 * hands the interrupt flag back afterwards.
 */
final class PlatformThread {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "vance-script-platform-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private PlatformThread() {}

    /**
     * Runs {@code work} on a platform thread and returns its result. A failure of
     * the work is rethrown as itself, not wrapped: the cause already carries the
     * stack of the thread that ran it, which is what PMD's check cannot see.
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    static <T> T call(Callable<T> work) throws Exception {
        if (!Thread.currentThread().isVirtual()) {
            return work.call();
        }
        Future<T> future = POOL.submit(work);
        // Uninterruptible wait: the callers of this are the clean-up paths of an
        // interrupted run (cancel, timeout), whose thread arrives here with the
        // interrupt flag already set. A plain get() would throw at once and the
        // context would never be closed — the cancelled script would keep running.
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** As {@link #call(Callable)} for work without checked exceptions. */
    static <T> T get(java.util.function.Supplier<T> work) {
        try {
            return call(work::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** As {@link #call(Callable)} for work without a result and without checked exceptions. */
    static void run(Runnable work) {
        try {
            call(() -> {
                work.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
