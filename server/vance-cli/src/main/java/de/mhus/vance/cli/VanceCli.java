package de.mhus.vance.cli;

import de.mhus.vance.cli.chat.ChatCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root of the Vance CLI. Registers subcommands and forwards exit codes.
 */
@Command(
        name = "vance",
        mixinStandardHelpOptions = true,
        version = "vance-cli 0.1.0",
        description = "Command-line client for the Vance Brain.",
        subcommands = {ConnectCommand.class, ChatCommand.class})
public class VanceCli implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new VanceCli()).execute(args);
        System.exit(exitCode);
    }
}
