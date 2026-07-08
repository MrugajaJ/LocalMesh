package dev.localmesh.cli.tunnel;

import com.google.protobuf.ByteString;
import dev.localmesh.cli.LocalMeshCli;
import dev.localmesh.proto.TunnelFrame;
import dev.localmesh.proto.TunnelServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * The local gRPC tunnel server.
 *
 * Connects to the controller's TunnelService, receives TunnelFrame(REQUEST) messages,
 * proxies them as real HTTP requests to localhost:LOCAL_PORT, and streams
 * TunnelFrame(RESPONSE) back.
 *
 * Prints coloured traffic log to stdout.
 */
public class LocalTunnelServer {

    private final String interceptId;
    private final int    localPort;
    private final String tunnelEndpoint; // controller's gRPC address

    private ManagedChannel           channel;
    private StreamObserver<TunnelFrame> sender;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LocalTunnelServer(String interceptId, int localPort, String tunnelEndpoint) {
        this.interceptId    = interceptId;
        this.localPort      = localPort;
        this.tunnelEndpoint = tunnelEndpoint;
    }

    public void start() {
        channel = ManagedChannelBuilder.forTarget(tunnelEndpoint)
                .usePlaintext()
                .build();

        TunnelServiceGrpc.TunnelServiceStub stub = TunnelServiceGrpc.newStub(channel);

        sender = stub.connectTunnel(new StreamObserver<>() {
            @Override
            public void onNext(TunnelFrame frame) {
                // We only receive REQUEST frames here (CLI side acts as RESPONSE sender)
                if ("REQUEST".equals(frame.getDirection())) {
                    handleIncomingRequest(frame);
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println(LocalMeshCli.Ansi.RED +
                        "✗ Tunnel error: " + t.getMessage() + LocalMeshCli.Ansi.RESET);
            }

            @Override
            public void onCompleted() {
                System.out.println(LocalMeshCli.Ansi.DIM + "Tunnel closed." + LocalMeshCli.Ansi.RESET);
            }
        });

        // Send an initial RESPONSE frame to register this CLI stream with the controller
        sender.onNext(TunnelFrame.newBuilder()
                .setInterceptId(interceptId)
                .setRequestId(UUID.randomUUID().toString())
                .setDirection("RESPONSE")
                .setTimestampMs(System.currentTimeMillis())
                .build());
    }

    private void handleIncomingRequest(TunnelFrame frame) {
        String reqId  = frame.getRequestId();
        String method = frame.getMethod();
        String path   = frame.getPath();
        long   start  = System.currentTimeMillis();

        Thread.ofVirtual().start(() -> {
            try {
                // Build HTTP request to local service
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + localPort + path))
                        .timeout(Duration.ofSeconds(30));

                // Add headers from frame
                frame.getHeadersMap().forEach(reqBuilder::header);

                byte[] body = frame.getPayload().toByteArray();
                HttpRequest.BodyPublisher publisher = body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody();

                reqBuilder.method(method, publisher);

                HttpResponse<byte[]> response = httpClient.send(reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofByteArray());

                long latencyMs = System.currentTimeMillis() - start;
                int  status    = response.statusCode();

                // Print coloured log line
                printTrafficLine(method, path, status, latencyMs);

                // Send response frame back over the tunnel
                TunnelFrame responseFrame = TunnelFrame.newBuilder()
                        .setInterceptId(interceptId)
                        .setRequestId(reqId)
                        .setDirection("RESPONSE")
                        .setStatusCode(status)
                        .setLatencyMs(latencyMs)
                        .setPayload(ByteString.copyFrom(response.body()))
                        .setTimestampMs(System.currentTimeMillis())
                        .build();

                sender.onNext(responseFrame);

            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - start;
                System.err.printf("%s[ERROR] %s %s → 502 Bad Gateway (%dms) — %s%s%n",
                        LocalMeshCli.Ansi.RED, method, path, latencyMs, e.getMessage(), LocalMeshCli.Ansi.RESET);

                // Send 502 response
                sender.onNext(TunnelFrame.newBuilder()
                        .setInterceptId(interceptId)
                        .setRequestId(reqId)
                        .setDirection("RESPONSE")
                        .setStatusCode(502)
                        .setLatencyMs(latencyMs)
                        .build());
            }
        });
    }

    private static void printTrafficLine(String method, String path, int status, long latencyMs) {
        String colour;
        String icon;
        if (status >= 500) {
            colour = LocalMeshCli.Ansi.RED;
            icon   = "✗";
        } else if (status >= 400) {
            colour = LocalMeshCli.Ansi.YELLOW;
            icon   = "△";
        } else {
            colour = LocalMeshCli.Ansi.GREEN;
            icon   = "✓";
        }
        System.out.printf("%s%s [%s] %-6s %-50s → %d (%dms)%s%n",
                colour, icon, java.time.LocalTime.now().toString().substring(0, 12),
                method, path, status, latencyMs, LocalMeshCli.Ansi.RESET);
    }

    public void stop() {
        if (sender != null) sender.onCompleted();
        if (channel != null) channel.shutdownNow();
    }
}
