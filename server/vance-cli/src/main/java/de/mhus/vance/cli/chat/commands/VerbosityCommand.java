package de.mhus.vance.cli.chat.commands;

/**
 * Runtime verbosity control. Higher levels reveal more meta/trace lines in
 * the history panel without touching actual chat content, which is always
 * shown.
 *
 * <ul>
 *   <li>{@code 0} — chat messages + errors only</li>
 *   <li>{@code 1} — also INFO/SYSTEM status lines (default)</li>
 *   <li>{@code 2+} — also SENT/RECEIVED wire trace</li>
 * </ul>
 */
public class VerbosityCommand implements Command {

    @Override
    public String name() {
        return "verbosity";
    }

    @Override
    public String description() {
        return "Show or set how much non-chat noise is rendered (0=chat only).";
    }

    @Override
    public String usage() {
        return "/verbosity [level]";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        if (args.length == 0) {
            ctx.info("verbosity=" + ctx.verbosity());
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            ctx.error("Usage: " + usage() + " — level must be an integer");
            return;
        }
        if (level < 0) {
            ctx.error("Usage: " + usage() + " — level must be >= 0");
            return;
        }
        ctx.setVerbosity(level);
        ctx.info("verbosity=" + ctx.verbosity());
    }
}
