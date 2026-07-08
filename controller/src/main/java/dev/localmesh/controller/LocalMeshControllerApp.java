package dev.localmesh.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LocalMesh Controller — the brain of LocalMesh.
 *
 * Runs inside the Kubernetes cluster and manages intercept lifecycle:
 *  1. Watches for LocalMeshIntercept CRDs via SharedInformer
 *  2. Injects the sidecar proxy into the target pod
 *  3. Bridges the gRPC tunnel from sidecar → CLI
 *  4. Persists session/intercept state in PostgreSQL
 *  5. Publishes live events to Redis pub/sub for the dashboard
 */
@SpringBootApplication(exclude = HibernateJpaAutoConfiguration.class)
@EnableScheduling
public class LocalMeshControllerApp {

    public static void main(String[] args) {
        SpringApplication.run(LocalMeshControllerApp.class, args);
    }
}
