package dev.localmesh.controller.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Starts the gRPC server that binds TunnelService, HeartbeatService, and MetricsService.
 */
@Component
public class GrpcServer {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    @Value("${localmesh.grpc.port:50051}")
    private int grpcPort;

    private final TunnelServiceImpl    tunnelService;
    private final HeartbeatServiceImpl heartbeatService;
    private final MetricsServiceImpl   metricsService;

    private Server server;

    public GrpcServer(TunnelServiceImpl tunnelService,
                      HeartbeatServiceImpl heartbeatService,
                      MetricsServiceImpl metricsService) {
        this.tunnelService    = tunnelService;
        this.heartbeatService = heartbeatService;
        this.metricsService   = metricsService;
    }

    @PostConstruct
    public void start() throws IOException {
        server = ServerBuilder.forPort(grpcPort)
                .addService(tunnelService)
                .addService(heartbeatService)
                .addService(metricsService)
                .build()
                .start();
        log.info("gRPC server started on port {}", grpcPort);
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
            log.info("gRPC server stopped");
        }
    }
}
