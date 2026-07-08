package dev.localmesh.controller.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;

@Configuration
public class InfrastructureConfig {

    @Value("${localmesh.redis.uri:redis://localhost:6379}")
    private String redisUri;

    /**
     * Kubernetes API client — auto-detects in-cluster vs. kubeconfig.
     */
    @Bean
    public ApiClient kubernetesApiClient() throws IOException {
        ApiClient client;
        try {
            // Running inside the cluster
            client = Config.fromCluster();
        } catch (Exception e) {
            // Fallback to local kubeconfig for development
            client = Config.defaultClient();
        }
        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
        return client;
    }

    /**
     * Redis client via Lettuce.
     */
    @Bean(destroyMethod = "shutdown")
    public io.lettuce.core.RedisClient redisClient() {
        return io.lettuce.core.RedisClient.create(redisUri);
    }

    @Bean(destroyMethod = "close")
    public io.lettuce.core.api.StatefulRedisConnection<String, String> redisConnection(
            io.lettuce.core.RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    public io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands(
            io.lettuce.core.api.StatefulRedisConnection<String, String> connection) {
        return connection.sync();
    }
}
