package de.mhus.vance.brain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.ws.WsHandler;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * Static-analysis style smoke test: every {@link RestController} and every
 * {@link WsHandler} in the brain must inject {@link RequestAuthority}.
 *
 * <p>This is the cheap "did you remember to wire the permission check?"
 * regression net. The test scans the compiled classpath, so adding a new
 * controller without a {@code RequestAuthority} field fails the build —
 * even before anyone runs an integration test.
 *
 * <p>Known exempt classes (e.g. {@code AccessController} which mints JWTs
 * before any user is authenticated) live in {@link #EXEMPT}.
 */
class InboundPermissionWiringTest {

    /**
     * Classes that legitimately do not enforce — login/refresh happen
     * pre-auth, help is bundled docs, ping is a heartbeat.
     */
    private static final List<String> EXEMPT = List.of(
            "de.mhus.vance.brain.access.AccessController",
            "de.mhus.vance.brain.help.HelpController",
            "de.mhus.vance.brain.ws.handlers.PingHandler",
            // Internal pod-to-pod controller protected by a different filter.
            "de.mhus.vance.brain.workspace.access.WorkspaceInternalController",
            // Transfer is a different trust model (shared-secret between pods).
            "de.mhus.vance.brain.transfer.TransferInitWsHandler",
            "de.mhus.vance.brain.transfer.TransferInitResponseWsHandler",
            "de.mhus.vance.brain.transfer.TransferChunkWsHandler",
            "de.mhus.vance.brain.transfer.TransferCompleteWsHandler",
            "de.mhus.vance.brain.transfer.TransferFinishWsHandler",
            // Client-tool registry is a control-plane registration, gated by
            // session bind already (no resource semantics).
            "de.mhus.vance.brain.tools.client.ClientToolRegisterHandler",
            "de.mhus.vance.brain.tools.client.ClientToolResultHandler",
            // The dispatcher itself — it OWNS the permission check; it's not
            // a controller/handler and would create a cycle if treated like one.
            "de.mhus.vance.brain.permission.PermissionExceptionAdvice"
    );

    @Test
    void everyRestController_injectsRequestAuthority() throws Exception {
        List<Class<?>> controllers = scan(c -> c.isAnnotationPresent(RestController.class));
        assertControllersWired(controllers, "RestController");
    }

    @Test
    void everyWsHandler_injectsRequestAuthority() throws Exception {
        List<Class<?>> handlers = scan(c ->
                WsHandler.class.isAssignableFrom(c)
                        && !c.isInterface()
                        && !java.lang.reflect.Modifier.isAbstract(c.getModifiers()));
        assertControllersWired(handlers, "WsHandler");
    }

    private static void assertControllersWired(List<Class<?>> classes, String label) {
        List<String> missing = new ArrayList<>();
        for (Class<?> c : classes) {
            if (EXEMPT.contains(c.getName())) continue;
            boolean injects = false;
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == RequestAuthority.class) {
                    injects = true;
                    break;
                }
            }
            if (!injects) {
                missing.add(c.getName());
            }
        }
        assertThat(missing)
                .as("Every %s must inject RequestAuthority (or be in EXEMPT). "
                        + "Missing: %s", label, missing)
                .isEmpty();
    }

    /** Scan the brain's classes directory for classes matching the filter. */
    private static List<Class<?>> scan(java.util.function.Predicate<Class<?>> filter)
            throws Exception {
        String basePackage = "de.mhus.vance.brain";
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL root = loader.getResource(basePackage.replace('.', '/'));
        assertThat(root).as("Brain package on classpath").isNotNull();

        List<Class<?>> out = new ArrayList<>();
        if ("file".equals(root.getProtocol())) {
            scanDir(new File(root.toURI()), basePackage, filter, out, loader);
        } else if ("jar".equals(root.getProtocol())) {
            // Running from a packaged jar — walk entries.
            String jarPath = root.getPath().substring(5, root.getPath().indexOf("!"));
            try (JarFile jar = new JarFile(jarPath)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    String cn = e.getName().replace('/', '.');
                    cn = cn.substring(0, cn.length() - ".class".length());
                    if (!cn.startsWith(basePackage)) continue;
                    tryAdd(cn, filter, out, loader);
                }
            }
        }
        return out;
    }

    private static void scanDir(File dir, String pkg,
                                java.util.function.Predicate<Class<?>> filter,
                                List<Class<?>> out, ClassLoader loader) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                scanDir(f, pkg + "." + f.getName(), filter, out, loader);
            } else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
                String cn = pkg + "."
                        + f.getName().substring(0, f.getName().length() - ".class".length());
                tryAdd(cn, filter, out, loader);
            }
        }
    }

    private static void tryAdd(String className,
                               java.util.function.Predicate<Class<?>> filter,
                               List<Class<?>> out, ClassLoader loader) {
        try {
            Class<?> c = Class.forName(className, false, loader);
            if (filter.test(c)) out.add(c);
        } catch (Throwable ignored) {
            // Some classes can't be loaded outside Spring context — skip them.
        }
    }
}
