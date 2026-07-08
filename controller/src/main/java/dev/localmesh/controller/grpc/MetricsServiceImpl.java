package dev.localmesh.controller.grpc;

import dev.localmesh.proto.MetricsServiceGrpc;
import dev.localmesh.proto.TrafficAck;
import dev.localmesh.proto.TrafficReport;
import dev.localmesh.controller.redis.EventPublisher;
import dev.localmesh.controller.repository.InterceptRepository;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Receives traffic telemetry from sidecars and stores it in PostgreSQL.
 */
@Component
public class MetricsServiceImpl extends MetricsServiceGrpc.MetricsServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(MetricsServiceImpl.class);

    private final InterceptRepository interceptRepository;
    private final EventPublisher      eventPublisher;

    public MetricsServiceImpl(InterceptRepository interceptRepository, EventPublisher eventPublisher) {
        this.interceptRepository = interceptRepository;
        this.eventPublisher      = eventPublisher;
    }

    @Override
    public void reportTraffic(TrafficReport report, StreamObserver<TrafficAck> responseObserver) {
        try {
            interceptRepository.recordTrafficEvent(
                    report.getInterceptId(),
                    report.getRequestId(),
                    report.getMethod(),
                    report.getPath(),
                    report.getStatusCode(),
                    report.getLatencyMs());

            // Publish live metric update to Redis for the dashboard
            eventPublisher.publishMetrics(
                    report.getInterceptId(),
                    0,  // req/s computed by API from aggregated traffic_events
                    report.getLatencyMs(),
                    report.getStatusCode() >= 500 ? 100.0 : 0.0);

            responseObserver.onNext(TrafficAck.newBuilder().setAccepted(true).build());
        } catch (Exception e) {
            log.error("Failed to record traffic report", e);
            responseObserver.onNext(TrafficAck.newBuilder().setAccepted(false).build());
        }
        responseObserver.onCompleted();
    }
}
