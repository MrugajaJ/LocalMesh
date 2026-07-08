package dev.localmesh.cli;

import dev.localmesh.cli.cmd.ConnectCommand;
import dev.localmesh.cli.cmd.DisconnectCommand;
import dev.localmesh.cli.cmd.InterceptCommand;
import dev.localmesh.cli.cmd.StatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * LocalMesh CLI entry point.
 *
 * Usage:
 *   localmesh connect [--kubeconfig PATH] [--namespace NS]
 *   localmesh intercept SERVICE_NAME --port LOCAL_PORT [--namespace NS]
 *   localmesh status
 *   localmesh disconnect
 */
@Command(
    name        = "localmesh",
    description = "Connect your laptop to a Kubernetes cluster. Your laptop is a pod.",
    mixinStandardHelpOptions = true,
    version     = "LocalMesh 0.1.0",
    subcommands = {
        ConnectCommand.class,
        InterceptCommand.class,
        StatusCommand.class,
        DisconnectCommand.class,
        CommandLine.HelpCommand.class
    },
    footer = {
        "",
        "Examples:",
        "  localmesh connect",
        "  localmesh intercept payment-service --port 8080",
        "  localmesh status",
        "  localmesh disconnect",
        ""
    }
)
public class LocalMeshCli implements Runnable {

    @Option(names = {"--api-url"},
            description = "LocalMesh API base URL (default: http://localhost:8080)",
            defaultValue = "http://localhost:8080",
            scope = CommandLine.ScopeType.INHERIT)
    String apiUrl;

    @Option(names = {"--verbose", "-v"},
            description = "Enable verbose/debug logging",
            scope = CommandLine.ScopeType.INHERIT)
    boolean verbose;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new LocalMeshCli())
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    System.err.println(Ansi.RED + "✗ Error: " + ex.getMessage() + Ansi.RESET);
                    return 1;
                })
                .execute(args);
        System.exit(exit);
    }

    /** ANSI colour helpers */
    public static class Ansi {
        public static final String RESET  = "\u001B[0m";
        public static final String GREEN  = "\u001B[32m";
        public static final String YELLOW = "\u001B[33m";
        public static final String RED    = "\u001B[31m";
        public static final String CYAN   = "\u001B[36m";
        public static final String BOLD   = "\u001B[1m";
        public static final String DIM    = "\u001B[2m";
    }
}
