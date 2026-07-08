package dev.localmesh.cli.cmd;

import dev.localmesh.cli.LocalMeshCli;
import dev.localmesh.cli.SessionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.Map;

/**
 * localmesh status — shows active intercepts and their metrics.
 */
@Command(
    name        = "status",
    description = "Show all active intercepts and their current metrics",
    mixinStandardHelpOptions = true
)
public class StatusCommand implements Runnable {

    @ParentCommand
    LocalMeshCli parent;

    @Override
    public void run() {
        SessionStore.Session session;
        try {
            session = SessionStore.load();
        } catch (Exception e) {
            System.err.println(LocalMeshCli.Ansi.RED +
                    "✗ No active session. Run 'localmesh connect' first." + LocalMeshCli.Ansi.RESET);
            System.exit(1);
            return;
        }

        String apiUrl = session.apiUrl != null ? session.apiUrl : parent.apiUrl;

        try {
            List<?> intercepts = SessionStore.getList(
                    apiUrl + "/api/intercepts?sessionId=" + session.sessionId);

            if (intercepts.isEmpty()) {
                System.out.println(LocalMeshCli.Ansi.DIM + "No active intercepts." + LocalMeshCli.Ansi.RESET);
                System.out.println("Run: localmesh intercept <service> --port <port>");
                return;
            }

            // Header
            System.out.println();
            System.out.printf(LocalMeshCli.Ansi.BOLD + "%-30s %-12s %-10s %-14s %-8s%n" + LocalMeshCli.Ansi.RESET,
                    "SERVICE", "STATUS", "REQUESTS", "AVG LATENCY", "ERRORS");
            System.out.println("─".repeat(80));

            for (Object item : intercepts) {
                if (item instanceof Map<?, ?> intercept) {
                    String svc    = (String) intercept.getOrDefault("serviceName", "?");
                    String status = (String) intercept.getOrDefault("status", "?");
                    Object reqs   = intercept.getOrDefault("requestCount", 0);
                    Object lat    = intercept.getOrDefault("avgLatencyMs", 0);
                    Object errs   = intercept.getOrDefault("errorCount", 0);

                    String statusColour = switch (status) {
                        case "ACTIVE"    -> LocalMeshCli.Ansi.GREEN;
                        case "TEARDOWN"  -> LocalMeshCli.Ansi.YELLOW;
                        case "FAILED"    -> LocalMeshCli.Ansi.RED;
                        default          -> LocalMeshCli.Ansi.DIM;
                    };

                    System.out.printf("%-30s %s%-12s%s %-10s %-14s %-8s%n",
                            svc,
                            statusColour, status, LocalMeshCli.Ansi.RESET,
                            reqs,
                            lat + "ms",
                            errs);
                }
            }
            System.out.println();

        } catch (Exception e) {
            System.err.println(LocalMeshCli.Ansi.RED + "✗ Failed to get status: " + e.getMessage() + LocalMeshCli.Ansi.RESET);
            System.exit(1);
        }
    }
}
