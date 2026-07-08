package dev.localmesh.cli.cmd;

import dev.localmesh.cli.LocalMeshCli;
import dev.localmesh.cli.SessionStore;
import dev.localmesh.cli.tunnel.LocalTunnelServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.Map;

/**
 * localmesh intercept SERVICE_NAME --port LOCAL_PORT
 *
 * Creates a LocalMeshIntercept CRD via the API, then starts a local gRPC
 * tunnel server that receives TunnelFrames and proxies them to localhost:PORT.
 */
@Command(
    name        = "intercept",
    description = "Intercept a cluster service and route its traffic to your local port",
    mixinStandardHelpOptions = true
)
public class InterceptCommand implements Runnable {

    @ParentCommand
    LocalMeshCli parent;

    @Parameters(index = "0", description = "Name of the Kubernetes service to intercept")
    String serviceName;

    @Option(names = {"--port", "-p"},
            description = "Local port where your service is running",
            required = true)
    int localPort;

    @Option(names = {"--namespace", "-n"},
            description = "Kubernetes namespace (default: default)",
            defaultValue = "default")
    String namespace;

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

        System.out.printf("%sIntercepting %s%s in namespace %s → forwarding to localhost:%d%n",
                LocalMeshCli.Ansi.CYAN, LocalMeshCli.Ansi.BOLD + serviceName,
                LocalMeshCli.Ansi.RESET + LocalMeshCli.Ansi.CYAN,
                namespace, localPort);
        System.out.println(LocalMeshCli.Ansi.RESET + "Injecting sidecar into pod...");

        try {
            // Create intercept via REST API
            Map<?, ?> response = SessionStore.post(apiUrl + "/api/intercepts", Map.of(
                    "sessionId",   session.sessionId,
                    "serviceName", serviceName,
                    "namespace",   namespace,
                    "localPort",   localPort
            ));

            String interceptId     = (String) response.get("interceptId");
            String tunnelEndpoint  = (String) response.getOrDefault("tunnelEndpoint", "");
            String status          = (String) response.getOrDefault("status", "UNKNOWN");

            if (!"ACTIVE".equals(status)) {
                System.err.println(LocalMeshCli.Ansi.RED +
                        "✗ Intercept did not become ACTIVE (status=" + status + ")" + LocalMeshCli.Ansi.RESET);
                System.exit(1);
                return;
            }

            System.out.println(LocalMeshCli.Ansi.GREEN + "✓ Intercept ACTIVE" + LocalMeshCli.Ansi.RESET);
            System.out.println("  Intercept ID   : " + interceptId);
            System.out.println("  Tunnel endpoint: " + tunnelEndpoint);
            System.out.println("  Forwarding to  : localhost:" + localPort);
            System.out.println();
            System.out.println(LocalMeshCli.Ansi.DIM + "Live traffic log (Ctrl+C to stop):" + LocalMeshCli.Ansi.RESET);
            System.out.println("──────────────────────────────────────────");

            // Start local gRPC tunnel server
            LocalTunnelServer tunnelServer = new LocalTunnelServer(interceptId, localPort, tunnelEndpoint);
            tunnelServer.start();

            // Block until interrupted
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println(LocalMeshCli.Ansi.RED + "✗ Intercept failed: " + e.getMessage() + LocalMeshCli.Ansi.RESET);
            if (parent.verbose) e.printStackTrace();
            System.exit(1);
        }
    }
}
