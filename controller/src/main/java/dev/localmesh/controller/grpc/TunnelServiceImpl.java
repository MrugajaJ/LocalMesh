package dev.localmesh.controller.grpc;

import dev.localmesh.proto.TunnelFrame;
import dev.localmesh.proto.TunnelServiceGrpc;
import dev.localmesh.controller.redis.EventPublisher;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC TunnelService implementation on the controller side.
 *
 * Acts as a router: when the sidecar opens a stream for intercept X,
 * it registers it here. When the CLI opens a stream for intercept X,
 * they are paired together and frames flow bidirectionally.
 */
@Component
public class TunnelServiceImpl extends TunnelServiceGrpc.TunnelServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(TunnelServiceImpl.class);

    /** Active CLI stream observers, keyed by intercept_id */
    private final Map<String, StreamObserver<TunnelFrame>> cliStreams     = new ConcurrentHashMap<>();
    /** Active Sidecar stream observers, keyed by intercept_id */
    private final Map<String, StreamObserver<TunnelFrame>> sidecarStreams = new ConcurrentHashMap<>();

    private final EventPublisher eventPublisher;

    public TunnelServiceImpl(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public StreamObserver<TunnelFrame> connectTunnel(StreamObserver<TunnelFrame> responseObserver) {
        return new StreamObserver<>() {
            private String interceptId;
            private String role; // "SIDECAR" or "CLI"

            @Override
            public void onNext(TunnelFrame frame) {
                // First frame registers the stream
                if (interceptId == null) {
                    interceptId = frame.getInterceptId();
                    role = frame.getDirection().startsWith("REQUEST") ? "SIDECAR" : "CLI";
                    registerStream(interceptId, role, responseObserver);
                    log.info("Tunnel connected: interceptId={} role={}", interceptId, role);
                }

                // Route the frame to the peer
                routeFrame(frame, role);
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Tunnel error for intercept={} role={}: {}", interceptId, role, t.getMessage());
                deregister();
            }

            @Override
            public void onCompleted() {
                log.info("Tunnel completed: interceptId={} role={}", interceptId, role);
                deregister();
                responseObserver.onCompleted();
            }

            private void deregister() {
                if (interceptId == null) return;
                if ("SIDECAR".equals(role)) sidecarStreams.remove(interceptId);
                else                        cliStreams.remove(interceptId);
            }
        };
    }

    private void registerStream(String interceptId, String role, StreamObserver<TunnelFrame> observer) {
        if ("SIDECAR".equals(role)) sidecarStreams.put(interceptId, observer);
        else                        cliStreams.put(interceptId, observer);
    }

    private void routeFrame(TunnelFrame frame, String senderRole) {
        String interceptId = frame.getInterceptId();
        StreamObserver<TunnelFrame> target = "SIDECAR".equals(senderRole)
                ? cliStreams.get(interceptId)
                : sidecarStreams.get(interceptId);

        if (target != null) {
            target.onNext(frame);
        } else {
            log.warn("No peer stream for intercept={} from role={}", interceptId, senderRole);
        }
    }

    public int activeStreamCount() {
        return Math.max(cliStreams.size(), sidecarStreams.size());
    }
}
