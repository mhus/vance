package de.mhus.vance.runner;

import de.mhus.vance.exec.RootDirException;
import de.mhus.vance.exec.RootDirHandle;
import de.mhus.vance.exec.RootDirSpec;
import de.mhus.vance.exec.WorkspaceDescriptor;
import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code root.list}, {@code root.create}, {@code root.delete} — the project's RootDirs on the runner. */
final class RootDirOperations {

    /** Types the runner provides. {@code git}, {@code node} and {@code python} follow with their programs. */
    static final Set<String> TYPES = Set.of("temp", "ephemeral");

    private RootDirOperations() {}

    static List<RunnerOperation> all(RunnerWorkspace workspace) {
        return List.of(list(workspace), create(workspace), delete(workspace));
    }

    private static RunnerOperation list(RunnerWorkspace workspace) {
        return operation("root.list", call -> {
            var claims = call.claims();
            List<Map<String, Object>> dirs =
                    workspace.store().listRootDirs(claims.tenantId(), claims.projectId()).stream()
                            .filter(handle ->
                                    claims.rootDir() == null || claims.rootDir().equals(handle.getDirName()))
                            .map(RootDirOperations::describe)
                            .toList();
            return OperationResult.of(Map.of("rootDirs", dirs));
        });
    }

    private static RunnerOperation create(RunnerWorkspace workspace) {
        return operation("root.create", call -> {
            if (call.claims().rootDir() != null) {
                throw new OperationException(
                        "root-not-allowed", "This call is confined to one RootDir; it cannot create another");
            }
            OperationArgs args = new OperationArgs(call.args());
            String type = args.text("type");
            if (!TYPES.contains(type)) {
                throw new OperationException(
                        "unsupported-type",
                        "This runner provides RootDir types "
                                + TYPES.stream().sorted().toList() + ", not '" + type + "'");
            }
            RootDirHandle handle = workspace
                    .store()
                    .createRootDir(RootDirSpec.builder()
                            .tenantId(call.claims().tenantId())
                            .projectId(call.claims().projectId())
                            .type(type)
                            .creatorProcessId(args.text("creatorProcessId"))
                            .creatorEngine(args.optionalText("creatorEngine"))
                            .labelHint(args.optionalText("label"))
                            .deleteOnCreatorClose(false)
                            .build());
            return OperationResult.of(describe(handle));
        });
    }

    private static RunnerOperation delete(RunnerWorkspace workspace) {
        return operation("root.delete", call -> {
            String dirName = new OperationArgs(call.args()).rootDir(call.claims());
            workspace
                    .store()
                    .disposeRootDir(call.claims().tenantId(), call.claims().projectId(), dirName);
            return OperationResult.of(Map.of("deleted", dirName));
        });
    }

    static Map<String, Object> describe(RootDirHandle handle) {
        WorkspaceDescriptor descriptor = handle.getDescriptor();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", handle.getDirName());
        out.put("type", handle.getType());
        out.put("label", descriptor.getLabel());
        out.put("createdAt", descriptor.getCreatedAt());
        return out;
    }

    /** Wraps an operation body so a {@link RootDirException} reaches the brain as a normal error. */
    static RunnerOperation operation(String name, Body body) {
        return new RunnerOperation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public OperationResult execute(Call call) throws OperationException {
                try {
                    return body.run(call);
                } catch (RootDirException e) {
                    throw new OperationException("rootdir", e.getMessage(), e);
                }
            }
        };
    }

    /** One operation's work. */
    @FunctionalInterface
    interface Body {
        OperationResult run(Call call) throws OperationException;
    }
}
