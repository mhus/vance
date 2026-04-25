package de.mhus.vance.foot.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Picocli root for {@code vance-foot}. Subcommands are registered as
 * Spring-managed Picocli beans — see {@link ChatRunCommand} and
 * {@link DaemonCommand}. With no subcommand and no args the root prints its
 * usage so users can see what is on offer (the IDE "Run main()" path lands
 * here).
 */
@Component
@Command(
        name = "vance-foot",
        mixinStandardHelpOptions = true,
        version = "vance-foot 0.1.0",
        description = {
                "Spring-based CLI client for the Vance Brain.",
                "",
                "Spring config override (intercepted before Picocli):",
                "  --config <path>  / -c <path>   merge YAML on top of defaults",
                "  may be passed multiple times; later wins on key collisions",
        },
        subcommands = {ChatRunCommand.class, DaemonCommand.class})
public class VanceFootCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
