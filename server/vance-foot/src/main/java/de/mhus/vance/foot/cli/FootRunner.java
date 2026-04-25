package de.mhus.vance.foot.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Hands control to Picocli once the Spring context is fully built. Picocli's
 * {@code IFactory} is the Spring-aware factory contributed by
 * {@code picocli-spring-boot-starter}, so all {@code @Command} subcommands
 * resolve as Spring beans with constructor injection.
 */
@Component
public class FootRunner implements CommandLineRunner, ExitCodeGenerator {

    private final VanceFootCommand root;
    private final IFactory factory;
    private int exitCode;

    public FootRunner(VanceFootCommand root, IFactory factory) {
        this.root = root;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        // No subcommand → default to `chat`. The CLI without arguments lands
        // in the REPL, mirroring the npm/python/psql convention. Users who
        // want usage text pass `--help`; the explicit `vance-foot chat ...`
        // form keeps working unchanged.
        String[] effectiveArgs = args.length == 0 ? new String[] {"chat"} : args;
        exitCode = new CommandLine(root, factory).execute(effectiveArgs);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
