package dev.localmesh.cli.cmd;

import dev.localmesh.cli.LocalMeshCli;
import dev.localmesh.cli.SessionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.Map;

/**
 * localmesh disconnect — tears down all active intercepts and removes the session.
 */
@Command(
    name        = "disconnect",
    description = "Tear down all active intercepts and disconnect from the cluster",
    mixinStandardHelpOptions = true
)
public class DisconnectCommand implements Runnable {

    @ParentCommand
    LocalMeshCli parent;

    @Override
    public void run() {
        if (!SessionStore.exists()) {
            System.out.println(LocalMeshCli.Ansi.DIM + "No active session." + LocalMeshCli.Ansi.RESET);
            return;
        }

        SessionStore.Session session;
        try {
            session = SessionStore.load();
        } catch (Exception e) {
            System.err.println(LocalMeshCli.Ansi.RED + "✗ Failed to load session: " + e.getMessage() + LocalMeshCli.Ansi.RESET);
            System.exit(1);
            return;
        }

        String apiUrl = session.apiUrl != null ? session.apiUrl : parent.apiUrl;

        System.out.println(LocalMeshCli.Ansi.CYAN + "Disconnecting from LocalMesh..." + LocalMeshCli.Ansi.RESET);

        try {
            // Fetch all active intercepts
            List<?> intercepts = SessionStore.getList(
                    apiUrl + "/api/intercepts?sessionId=" + session.sessionId);

            int tornDown = 0;
            for (Object item : intercepts) {
                if (item instanceof Map<?, ?> intercept) {
                    String interceptId = (String) intercept.get("interceptId");
                    String status      = (String) intercept.get("status");
                    if (interceptId != null && !"TORN_DOWN".equals(status)) {
                        SessionStore.delete(apiUrl + "/api/intercepts/" + interceptId);
                        System.out.println("  ↓ Torn down: " + intercept.get("serviceName"));
                        tornDown++;
                    }
                }
            }

            // Delete session
            SessionStore.delete(apiUrl + "/api/sessions/" + session.sessionId);

            // Remove local session file
            SessionStore.delete();

            System.out.println();
            System.out.println(LocalMeshCli.Ansi.GREEN + "✓ Disconnected." + LocalMeshCli.Ansi.RESET +
                    " All intercepts torn down (" + tornDown + ").");
            System.out.println("  Traffic is now flowing through the real cluster pods.");

        } catch (Exception e) {
            System.err.println(LocalMeshCli.Ansi.RED + "✗ Disconnect error: " + e.getMessage() + LocalMeshCli.Ansi.RESET);
            // Still try to delete local session file
            try { SessionStore.delete(); } catch (Exception ignored) {}
            System.exit(1);
        }
    }
}
