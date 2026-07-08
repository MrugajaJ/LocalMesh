package dev.localmesh.controller.grpc;

import dev.localmesh.proto.HeartbeatServiceGrpc;
import dev.localmesh.proto.PingRequest;
import dev.localmesh.proto.PongResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC HeartbeatService — receives Ping from sidecar/CLI and responds with Pong.
 */
@Component
public class HeartbeatServiceImpl extends HeartbeatServiceGrpc.HeartbeatServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatServiceImpl.class);

    @Override
    public void ping(PingRequest request, StreamObserver<PongResponse> responseObserver) {
        log.debug("Ping received: interceptId={}", request.getInterceptId());
        responseObserver.onNext(PongResponse.newBuilder()
                .setInterceptId(request.getInterceptId())
                .setTimestampMs(System.currentTimeMillis())
                .setStatus("OK")
                .build());
        responseObserver.onCompleted();
    }
}
