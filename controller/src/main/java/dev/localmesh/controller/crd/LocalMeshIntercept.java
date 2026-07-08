package dev.localmesh.controller.crd;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ObjectMeta;

/**
 * Custom Resource: LocalMeshIntercept
 *
 * apiVersion: localmesh.dev/v1alpha1
 * kind: LocalMeshIntercept
 */
public class LocalMeshIntercept implements KubernetesObject {

    public static final String GROUP   = "localmesh.dev";
    public static final String VERSION = "v1alpha1";
    public static final String KIND    = "LocalMeshIntercept";
    public static final String PLURAL  = "localmeshintercepts";

    @SerializedName("apiVersion")
    private String apiVersion = GROUP + "/" + VERSION;

    @SerializedName("kind")
    private String kind = KIND;

    @SerializedName("metadata")
    private V1ObjectMeta metadata;

    @SerializedName("spec")
    private Spec spec;

    @SerializedName("status")
    private Status status;

    // ── Spec ─────────────────────────────────────────────────────────────────

    public static class Spec {
        @SerializedName("serviceName")
        private String serviceName;

        @SerializedName("namespace")
        private String namespace = "default";

        @SerializedName("localPort")
        private int localPort;

        @SerializedName("developerSessionId")
        private String developerSessionId;

        public String getServiceName()          { return serviceName; }
        public void setServiceName(String s)    { this.serviceName = s; }
        public String getNamespace()            { return namespace; }
        public void setNamespace(String n)      { this.namespace = n; }
        public int getLocalPort()               { return localPort; }
        public void setLocalPort(int p)         { this.localPort = p; }
        public String getDeveloperSessionId()   { return developerSessionId; }
        public void setDeveloperSessionId(String id) { this.developerSessionId = id; }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public static class Status {
        @SerializedName("state")
        private String state; // PENDING | ACTIVE | FAILED | TORN_DOWN

        @SerializedName("tunnelEndpoint")
        private String tunnelEndpoint;

        @SerializedName("message")
        private String message;

        public String getState()                { return state; }
        public void setState(String s)          { this.state = s; }
        public String getTunnelEndpoint()       { return tunnelEndpoint; }
        public void setTunnelEndpoint(String t) { this.tunnelEndpoint = t; }
        public String getMessage()              { return message; }
        public void setMessage(String m)        { this.message = m; }
    }

    // ── KubernetesObject interface ────────────────────────────────────────────

    @Override public String getApiVersion()     { return apiVersion; }
    @Override public String getKind()           { return kind; }
    @Override public V1ObjectMeta getMetadata() { return metadata; }

    public void setApiVersion(String v) { this.apiVersion = v; }
    public void setKind(String k)       { this.kind = k; }
    public void setMetadata(V1ObjectMeta m) { this.metadata = m; }
    public Spec getSpec()               { return spec; }
    public void setSpec(Spec s)         { this.spec = s; }
    public Status getStatus()           { return status; }
    public void setStatus(Status s)     { this.status = s; }
}
