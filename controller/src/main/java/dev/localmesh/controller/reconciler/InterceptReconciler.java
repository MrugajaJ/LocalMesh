package dev.localmesh.controller.reconciler;

import dev.localmesh.controller.crd.LocalMeshIntercept;
import dev.localmesh.controller.crd.LocalMeshInterceptList;
import dev.localmesh.controller.redis.EventPublisher;
import dev.localmesh.controller.repository.InterceptRepository;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * The core reconciliation loop.
 *
 * Watches LocalMeshIntercept CRDs and:
 *  - On CREATE → injects sidecar into target pod, records intercept in DB
 *  - On DELETE → removes sidecar, updates status in DB
 *  - On missed heartbeat → triggers teardown
 */
@Component
public class InterceptReconciler {

    private static final Logger log = LoggerFactory.getLogger(InterceptReconciler.class);

    private static final String SIDECAR_CONTAINER_NAME = "localmesh-proxy";
    private static final String SIDECAR_IMAGE          = "localmesh/sidecar:latest";
    private static final String INIT_CONTAINER_NAME    = "localmesh-iptables";
    private static final String INIT_CONTAINER_IMAGE   = "alpine:3.19";

    private final ApiClient               apiClient;
    private final InterceptRepository     interceptRepository;
    private final EventPublisher          eventPublisher;
    private final BlockingQueue<WorkItem> workQueue = new LinkedBlockingQueue<>();
    private final ExecutorService         worker    = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reconciler-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${localmesh.grpc.port:50051}")
    private int grpcPort;

    @Value("${server.port:8090}")
    private int serverPort;

    private SharedInformerFactory informerFactory;
    private volatile boolean      running = true;

    public InterceptReconciler(ApiClient apiClient,
                               InterceptRepository interceptRepository,
                               EventPublisher eventPublisher) {
        this.apiClient          = apiClient;
        this.interceptRepository = interceptRepository;
        this.eventPublisher      = eventPublisher;
    }

    @PostConstruct
    public void start() {
        informerFactory = new SharedInformerFactory(apiClient);

        GenericKubernetesApi<LocalMeshIntercept, LocalMeshInterceptList> crdApi =
                new GenericKubernetesApi<>(
                        LocalMeshIntercept.class,
                        LocalMeshInterceptList.class,
                        LocalMeshIntercept.GROUP,
                        LocalMeshIntercept.VERSION,
                        LocalMeshIntercept.PLURAL,
                        apiClient);

        SharedIndexInformer<LocalMeshIntercept> informer =
                informerFactory.sharedIndexInformerFor(crdApi, LocalMeshIntercept.class, 0);

        informer.addEventHandler(new ResourceEventHandler<>() {
            @Override
            public void onAdd(LocalMeshIntercept obj) {
                enqueue(new WorkItem(WorkItem.Action.CREATE, obj));
            }

            @Override
            public void onUpdate(LocalMeshIntercept oldObj, LocalMeshIntercept newObj) {
                // No-op for now; status updates come from this controller
            }

            @Override
            public void onDelete(LocalMeshIntercept obj, boolean deletedFinalStateUnknown) {
                enqueue(new WorkItem(WorkItem.Action.DELETE, obj));
            }
        });

        informerFactory.startAllRegisteredInformers();
        log.info("LocalMeshIntercept informer started");

        worker.submit(this::processWorkQueue);
    }

    @PreDestroy
    public void stop() {
        running = false;
        worker.shutdownNow();
        if (informerFactory != null) informerFactory.stopAllRegisteredInformers();
    }

    private void enqueue(WorkItem item) {
        workQueue.offer(item);
        log.debug("Enqueued {} for {}", item.action(), item.intercept().getMetadata().getName());
    }

    /** Work queue processor — processes items sequentially to avoid races. */
    private void processWorkQueue() {
        while (running) {
            try {
                WorkItem item = workQueue.poll(1, TimeUnit.SECONDS);
                if (item == null) continue;
                switch (item.action()) {
                    case CREATE -> reconcileCreate(item.intercept());
                    case DELETE -> reconcileDelete(item.intercept());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Reconciliation error", e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE — inject sidecar
    // ─────────────────────────────────────────────────────────────────────────

    private void reconcileCreate(LocalMeshIntercept intercept) {
        String name      = intercept.getMetadata().getName();
        String namespace = intercept.getSpec().getNamespace();
        String service   = intercept.getSpec().getServiceName();
        int    localPort = intercept.getSpec().getLocalPort();
        String sessionId = intercept.getSpec().getDeveloperSessionId();

        log.info("Reconciling CREATE: intercept={} service={} namespace={}", name, service, namespace);

        try {
            CoreV1Api coreApi = new CoreV1Api(apiClient);

            // Find pods for this service (label selector: app=<service-name>)
            V1PodList podList = coreApi.listNamespacedPod(
                    namespace, null, null, null, null, "app=" + service, null, null, null, null, null);

            if (podList.getItems().isEmpty()) {
                log.warn("No pods found for service {} in namespace {}", service, namespace);
                interceptRepository.updateStatus(name, namespace, "FAILED");
                return;
            }

            V1Pod targetPod = podList.getItems().get(0);
            String podName  = targetPod.getMetadata().getName();
            String interceptId = UUID.randomUUID().toString();
            String tunnelEndpoint = "localmesh-controller." + namespace + ".svc.cluster.local:" + grpcPort;

            // Inject sidecar via patch
            injectSidecar(coreApi, targetPod, namespace, interceptId, localPort, tunnelEndpoint);

            // Persist intercept in DB
            interceptRepository.createIntercept(interceptId, sessionId, service, namespace, localPort, tunnelEndpoint);
            interceptRepository.updateStatus(name, namespace, "ACTIVE");

            // Publish event to Redis
            eventPublisher.publishInterceptActive(interceptId, service, sessionId);

            log.info("Intercept ACTIVE: interceptId={} pod={} tunnelEndpoint={}", interceptId, podName, tunnelEndpoint);

        } catch (Exception e) {
            log.error("Failed to reconcile CREATE for {}", name, e);
            try { interceptRepository.updateStatus(name, namespace, "FAILED"); } catch (Exception ignored) {}
        }
    }

    private void injectSidecar(CoreV1Api coreApi, V1Pod pod, String namespace,
                               String interceptId, int targetPort, String tunnelEndpoint) throws Exception {
        // Build sidecar container
        V1Container sidecar = new V1Container()
                .name(SIDECAR_CONTAINER_NAME)
                .image(SIDECAR_IMAGE)
                .addEnvItem(env("INTERCEPT_ID", interceptId))
                .addEnvItem(env("TARGET_PORT", String.valueOf(targetPort)))
                .addEnvItem(env("TUNNEL_ENDPOINT", tunnelEndpoint))
                .addEnvItem(env("PROXY_PORT", "8081"))
                .addPortsItem(new V1ContainerPort().containerPort(8081).name("proxy"));

        // Build init container for iptables redirect
        V1Container initContainer = new V1Container()
                .name(INIT_CONTAINER_NAME)
                .image(INIT_CONTAINER_IMAGE)
                .securityContext(new V1SecurityContext().privileged(true))
                .command(List.of("sh", "-c",
                        "iptables -t nat -A PREROUTING -p tcp --dport " + targetPort +
                        " -j REDIRECT --to-port 8081 || true"));

        // Build patch JSON
        String patch = """
            {"spec":{"initContainers":[%s],"containers":[%s]}}
            """.formatted(
                containerJson(initContainer, interceptId, targetPort, tunnelEndpoint),
                sidecarJson(interceptId, targetPort, tunnelEndpoint));

        coreApi.patchNamespacedPod(pod.getMetadata().getName(), namespace,
                new io.kubernetes.client.custom.V1Patch(patch), null, null, null, null, null);
    }

    private String sidecarJson(String interceptId, int targetPort, String tunnelEndpoint) {
        return """
            {"name":"%s","image":"%s","env":[
              {"name":"INTERCEPT_ID","value":"%s"},
              {"name":"TARGET_PORT","value":"%d"},
              {"name":"TUNNEL_ENDPOINT","value":"%s"},
              {"name":"PROXY_PORT","value":"8081"}
            ]}""".formatted(SIDECAR_CONTAINER_NAME, SIDECAR_IMAGE, interceptId, targetPort, tunnelEndpoint);
    }

    private String containerJson(V1Container c, String interceptId, int targetPort, String tunnelEndpoint) {
        return """
            {"name":"%s","image":"%s",
             "securityContext":{"privileged":true},
             "command":["sh","-c","iptables -t nat -A PREROUTING -p tcp --dport %d -j REDIRECT --to-port 8081 || true"]}
            """.formatted(INIT_CONTAINER_NAME, INIT_CONTAINER_IMAGE, targetPort);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE — remove sidecar
    // ─────────────────────────────────────────────────────────────────────────

    private void reconcileDelete(LocalMeshIntercept intercept) {
        String name      = intercept.getMetadata().getName();
        String namespace = intercept.getSpec().getNamespace();
        String service   = intercept.getSpec().getServiceName();

        log.info("Reconciling DELETE: intercept={} service={}", name, service);

        try {
            CoreV1Api coreApi = new CoreV1Api(apiClient);
            V1PodList pods = coreApi.listNamespacedPod(
                    namespace, null, null, null, null, "app=" + service, null, null, null, null, null);

            for (V1Pod pod : pods.getItems()) {
                // Remove sidecar from the pod spec via strategic merge patch
                String patch = """
                    {"spec":{"containers":[{"name":"%s","$patch":"delete"}],
                              "initContainers":[{"name":"%s","$patch":"delete"}]}}
                    """.formatted(SIDECAR_CONTAINER_NAME, INIT_CONTAINER_NAME);
                coreApi.patchNamespacedPod(pod.getMetadata().getName(), namespace,
                        new io.kubernetes.client.custom.V1Patch(patch), null, null, null, null, null);
            }

            interceptRepository.markTornDown(name, namespace);
            eventPublisher.publishInterceptTornDown(name, service);
            log.info("Intercept TORN_DOWN: name={}", name);

        } catch (Exception e) {
            log.error("Failed to reconcile DELETE for {}", name, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Heartbeat check — runs every 15 seconds
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "15000")
    public void checkExpiredSessions() {
        List<String> expired = interceptRepository.findExpiredSessions(30);
        for (String sessionId : expired) {
            log.warn("Session {} heartbeat expired — tearing down", sessionId);
            interceptRepository.tearDownActiveInterceptsForSession(sessionId);
            eventPublisher.publishSessionExpired(sessionId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private V1EnvVar env(String name, String value) {
        return new V1EnvVar().name(name).value(value);
    }

    record WorkItem(Action action, LocalMeshIntercept intercept) {
        enum Action { CREATE, DELETE }
    }
}
