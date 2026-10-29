package de.mhus.vance.brain.tools.runner;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolCapabilities;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * {@code runner_root_create}: a new RootDir on the project's runner — scratch
 * ({@code temp}, {@code ephemeral}), a repository checkout ({@code git}), a Python
 * environment ({@code python}) or a Node project ({@code node}).
 *
 * <p>The tool builds the RootDir's metadata from named parameters only; nothing else the
 * model passes reaches the runner. The credentials stored for the repository's host are resolved
 * here into a token that travels with this one call and is never stored.
 */
final class RunnerRootCreateTool implements Tool {

    private static final Set<String> TYPES = Set.of("temp", "ephemeral", "git", "python", "node");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", properties(),
            "required", List.of("type"));

    private final RunnerWork runner;
    private final GitCredentialResolver credentials;
    private final RunnerRepoUrl repoUrls;

    RunnerRootCreateTool(RunnerWork runner, GitCredentialResolver credentials, RunnerRepoUrl repoUrls) {
        this.repoUrls = repoUrls;
        this.runner = runner;
        this.credentials = credentials;
    }

    private static Map<String, Object> properties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "type",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("temp", "ephemeral", "git", "python", "node"),
                        "description",
                        "temp / ephemeral = scratch folder; git = a clone (needs repoUrl); python = a venv"
                                + " (optionally cloned from repoUrl); node = a package.json project."));
        p.put("label", Map.of("type", "string", "description", "Optional name hint for the RootDir."));
        p.put(
                "repoUrl",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "git (required) or python/node (optional): repository to clone."));
        p.put("branch", Map.of("type", "string", "description", "Branch to check out. Default: main."));
        p.put(
                "commit",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Commit to detach to after cloning; wins over the branch tip."));
        p.put("depth", Map.of("type", "integer", "description", "Shallow clone depth. Default: full history."));
        p.put(
                "public",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Go to the repository anonymously. Without it the server uses the stored credentials"
                                + " of the repository's host, if there are any; the runner only gets the token"
                                + " for this clone."));
        p.put(
                "pythonPath",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "python only: interpreter to build the venv with. Default: python3."));
        p.put(
                "packages",
                Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description",
                                "python: pip specs; node: npm registry specs — installed once when the RootDir is created."));
        return p;
    }

    @Override
    public String name() {
        return "runner_root_create";
    }

    @Override
    public String description() {
        return "Create a RootDir on the project's runner: a scratch folder, a git clone, a Python venv or a Node"
                + " project. Returns its dirName; use it as dirName in the runner_file_* / runner_exec_* tools or as"
                + " targetName of work_target_set(kind=\"RUNNER\"). Python/node packages install with pip/npm"
                + " through runner_exec_run afterwards, too.";
    }

    /** Listed for the projects that have a runner, and only for them. */
    @Override
    public Set<String> requiresCapabilities() {
        return Set.of(ToolCapabilities.RUNNER);
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Create a git checkout, python venv, node project or scratch folder on the runner.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, "write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String type = string(params, "type");
        if (type == null || !TYPES.contains(type)) {
            throw new ToolException(
                    "'type' must be one of " + TYPES.stream().sorted().toList());
        }
        String repoUrl = string(params, "repoUrl");
        if ("git".equals(type) && repoUrl == null) {
            throw new ToolException("'repoUrl' is required for a git RootDir");
        }
        if (repoUrl != null) {
            repoUrls.check(repoUrl);
        }
        if (repoUrl == null
                && (params.get("branch") != null || params.get("commit") != null || params.get("depth") != null)) {
            throw new ToolException("'branch', 'commit' and 'depth' need a 'repoUrl' to clone");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (repoUrl != null) {
            metadata.put("repoUrl", repoUrl);
            putIfPresent(metadata, "branch", string(params, "branch"));
            putIfPresent(metadata, "checkoutCommit", string(params, "commit"));
            if (params.get("depth") instanceof Number depth) {
                metadata.put("depth", depth.intValue());
            }
        }
        if ("python".equals(type)) {
            putIfPresent(metadata, "pythonPath", string(params, "pythonPath"));
        }
        if (("python".equals(type) || "node".equals(type)) && params.get("packages") instanceof List<?> packages) {
            metadata.put("packages", packages.stream().map(String::valueOf).toList());
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", type);
        args.put(
                "creatorProcessId",
                StringUtils.defaultIfBlank(ctx.processId(), StringUtils.defaultIfBlank(ctx.sessionId(), "_agent")));
        putIfPresent(args, "label", string(params, "label"));
        if (!metadata.isEmpty()) {
            args.put("metadata", metadata);
        }
        if (repoUrl != null) {
            Map<String, Object> credential = credentials.resolve(
                    ctx.tenantId(), ctx.projectId(), repoUrl, Boolean.TRUE.equals(params.get("public")));
            if (credential != null) {
                args.put("gitCredential", credential);
            }
        } else if (params.get("public") != null) {
            throw new ToolException("'public' needs a 'repoUrl'");
        }
        return runner.call(ctx, "root.create", args);
    }

    private static @Nullable String string(Map<String, Object> params, String key) {
        return params.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, @Nullable Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
