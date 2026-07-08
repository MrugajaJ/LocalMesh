package dev.localmesh.cli.cmd;

import dev.localmesh.cli.LocalMeshCli;
import dev.localmesh.cli.SessionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.time.Instant;
import java.util.Map;

/**
 * localmesh connect — establishes a session with the LocalMesh controller.
 *
 * Usage:
 *   localmesh connect [--kubeconfig PATH] [--namespace NS] [--name NAME]
 */
@Command(
    name        = "connect",
    description = "Connect your laptop to the cluster via LocalMesh",
    mixinStandardHelpOptions = true
)
public class ConnectCommand implements Runnable {

    @ParentCommand
    LocalMeshCli parent;

    @Option(names = {"--kubeconfig"},
            description = "Path to kubeconfig file (default: ~/.kube/config)",
            defaultValue = "")
    String kubeconfig;

    @Option(names = {"--namespace", "-n"},
            description = "Default namespace to use",
            defaultValue = "default")
    String namespace;

    @Option(names = {"--name"},
            description = "Your developer name (default: current OS user)",
            defaultValue = "")
    String developerName;

    @Override
    public void run() {
        String name = developerName.isBlank()
                ? System.getProperty("user.name", "developer")
                : developerName;

        String apiUrl = parent.apiUrl;

        System.out.println(LocalMeshCli.Ansi.CYAN + "Connecting to LocalMesh..." + LocalMeshCli.Ansi.RESET);

        try {
            // Create session via REST API
            Map<?, ?> response = SessionStore.post(apiUrl + "/api/sessions",
                    Map.of("developerName", name));

            String sessionId  = (String) response.get("sessionId");
            String createdAt  = (String) response.getOrDefault("createdAt", Instant.now().toString());

            // Persist session locally
            SessionStore.Session session = new SessionStore.Session();
            session.sessionId     = sessionId;
            session.developerName = name;
            session.apiUrl        = apiUrl;
            session.createdAt     = createdAt;
            SessionStore.save(session);

            System.out.println(LocalMeshCli.Ansi.GREEN + "✓ Connected to cluster." + LocalMeshCli.Ansi.RESET);
            System.out.println("  Session ID   : " + LocalMeshCli.Ansi.BOLD + sessionId + LocalMeshCli.Ansi.RESET);
            System.out.println("  Developer    : " + name);
            System.out.println("  API URL      : " + apiUrl);
            System.out.println("  Namespace    : " + namespace);
            System.out.println();
            System.out.println(LocalMeshCli.Ansi.DIM +
                    "Next step: localmesh intercept <service-name> --port <local-port>" +
                    LocalMeshCli.Ansi.RESET);

        } catch (Exception e) {
            System.err.println(LocalMeshCli.Ansi.RED + "✗ Failed to connect: " + e.getMessage() + LocalMeshCli.Ansi.RESET);
            System.exit(1);
        }
    }
}
