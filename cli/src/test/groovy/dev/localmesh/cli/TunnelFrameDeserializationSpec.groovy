package dev.localmesh.cli

import com.google.protobuf.ByteString
import dev.localmesh.cli.tunnel.LocalTunnelServer
import dev.localmesh.proto.TunnelFrame
import io.grpc.stub.StreamObserver
import spock.lang.Specification
import spock.lang.Timeout

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TunnelFrameDeserializationSpec extends Specification {

    def "LocalTunnelServer translates TunnelFrame to HttpRequest and responses back to TunnelFrame"() {
        given: "A LocalTunnelServer with mocked HttpClient and gRPC sender"
        def server = new LocalTunnelServer("intercept-123", 8080, "localhost:50051")
        
        def mockHttpClient = Mock(HttpClient)
        def mockSender = Mock(StreamObserver)
        def mockResponse = Mock(HttpResponse)

        // Inject mocks using Java reflection to bypass Groovy final field write restriction
        def httpClientField = LocalTunnelServer.class.getDeclaredField("httpClient")
        httpClientField.setAccessible(true)
        httpClientField.set(server, mockHttpClient)

        def senderField = LocalTunnelServer.class.getDeclaredField("sender")
        senderField.setAccessible(true)
        senderField.set(server, mockSender)

        def requestFrame = TunnelFrame.newBuilder()
                .setInterceptId("intercept-123")
                .setRequestId("req-x")
                .setDirection("REQUEST")
                .setMethod("POST")
                .setPath("/pay")
                .putHeaders("Content-Type", "application/json")
                .setPayload(ByteString.copyFrom('{"amount":100}'.getBytes()))
                .setTimestampMs(System.currentTimeMillis())
                .build()

        mockResponse.statusCode() >> 201
        mockResponse.body() >> '{"ok":true}'.getBytes()

        def latch = new CountDownLatch(1)
        TunnelFrame sentResponse = null

        // Configure mocks in the given block for thread-safe asynchronous execution
        mockHttpClient.send(_, _) >> { args ->
            HttpRequest req = args[0]
            assert req.method() == "POST"
            assert req.uri().toString() == "http://localhost:8080/pay"
            assert req.headers().firstValue("Content-Type").get() == "application/json"
            return mockResponse
        }

        mockSender.onNext(_) >> { args ->
            sentResponse = args[0] as TunnelFrame
            latch.countDown()
        }

        when: "The server handles the incoming REQUEST frame"
        server.handleIncomingRequest(requestFrame)

        then: "Wait for the async processing to complete"
        latch.await(2, TimeUnit.SECONDS)
        
        and: "The response frame contains correct status code and payload"
        sentResponse != null
        sentResponse.direction == "RESPONSE"
        sentResponse.statusCode == 201
        new String(sentResponse.payload.toByteArray()) == '{"ok":true}'
        sentResponse.requestId == "req-x"
        sentResponse.interceptId == "intercept-123"
    }
}
