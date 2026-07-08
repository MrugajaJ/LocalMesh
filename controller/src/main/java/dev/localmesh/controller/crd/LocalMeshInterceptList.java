package dev.localmesh.controller.crd;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.generic.KubernetesApiResponse;

import java.util.List;

/**
 * Kubernetes list wrapper for LocalMeshIntercept CRDs.
 */
public class LocalMeshInterceptList implements io.kubernetes.client.common.KubernetesListObject {

    private String apiVersion;
    private String kind;
    private io.kubernetes.client.openapi.models.V1ListMeta metadata;
    private List<LocalMeshIntercept> items;

    @Override public String getApiVersion()                                   { return apiVersion; }
    @Override public String getKind()                                         { return kind; }
    @Override public io.kubernetes.client.openapi.models.V1ListMeta getMetadata() { return metadata; }
    @Override public List<LocalMeshIntercept> getItems()                      { return items; }

    public void setApiVersion(String v) { this.apiVersion = v; }
    public void setKind(String k)       { this.kind = k; }
    public void setMetadata(io.kubernetes.client.openapi.models.V1ListMeta m) { this.metadata = m; }
    public void setItems(List<LocalMeshIntercept> i) { this.items = i; }
}
