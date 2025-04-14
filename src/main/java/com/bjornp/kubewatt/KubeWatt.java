package com.bjornp.kubewatt;

import ch.qos.logback.classic.Level;
import com.bjornp.kubewatt.estimator.ContainerPowerEstimator;
import com.bjornp.kubewatt.initializer.BaseInitializer;
import com.bjornp.kubewatt.initializer.BootstrapInitializer;
import com.bjornp.kubewatt.model.ContainerValue;
import com.bjornp.kubewatt.utils.config.Config;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.ClientBuilder;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import ch.qos.logback.classic.Logger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class KubeWatt {
    public static final Instant start = Instant.now();

    @SneakyThrows
    public static void main(String[] args) {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.toLevel(Optional.ofNullable(System.getenv("KW_LOG_LEVEL")).orElse("DEBUG")));

        log.info("Starting KubeWatt in {} mode", Config.get().mode());

        // initialize Kubernetes client
        ApiClient kubernetesClient = ClientBuilder.standard().build();
        Configuration.setDefaultApiClient(kubernetesClient);
        log.info("Successfully initialized Kubernetes client");

        switch (Config.get().mode()) {
            case INIT_BASE -> initBase();
            case INIT_BOOTSTRAP -> initBootstrap();
            case ESTIMATOR -> estimator();
        }
    }

    private static void initBase() {
        var initializer = new BaseInitializer();
        var result = initializer.init();
        log.info(String.valueOf(result));
    }

    private static void initBootstrap() {
        var initializer = new BootstrapInitializer();
        var result = initializer.init();
        log.info(String.valueOf(result));
    }

    private static void estimator() throws IOException, ExecutionException, InterruptedException {
        try (var server = HTTPServer.builder().port(9400).buildAndStart();
        var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            log.info("Initialized Prometheus server on port 9400");

            var estimator = new ContainerPowerEstimator();
            var powerGauge = Gauge
                    .builder()
                    .name("kubewatt_container_power_watts")
                    .help("Power in Watts per Kubernetes workload container")
                    .labelNames("node", "namespace", "pod", "container", "type")
                    .register();

            for (var staticPower : Config.get().collector().nodeStaticPower().entrySet()) {
                powerGauge
                        .labelValues(staticPower.getKey(), "", "", "", "static")
                        .set(staticPower.getValue());
            }

            AtomicReference<Map<String, List<ContainerValue>>> powerUsageCache = new AtomicReference<>();
            var future = scheduler.scheduleAtFixedRate(() -> {
                var containerPowerUsage = estimator.getContainerPowerUsage();
                // 1 - Remove containers from Prometheus that don't have new data; these are likely no longer running
                if (powerUsageCache.get() != null) {
                    var oldContainerPowerUsage = powerUsageCache.get();
                    oldContainerPowerUsage.forEach((node, containerLs) -> {
                        containerLs.forEach(oldContainer -> {
                            // Check for each container whether a similar entry still exists
                            // This could be more efficient by using a multimap for the metadata, but it shouldn't matter for a low no. containers
                            if (containerPowerUsage.get(node).stream().noneMatch(container -> container.hasSameMeta(oldContainer))) {
                                // If not, then remove it from Prometheus
                                log.debug("Removing {} {} {} {} {}", node, oldContainer.namespace(), oldContainer.podName(), oldContainer.containerName(), "dynamic");
                                powerGauge.remove(node, oldContainer.namespace(), oldContainer.podName(), oldContainer.containerName(), "dynamic");
                            }
                        });
                    });
                }

                powerUsageCache.set(containerPowerUsage);
                // 2 - Add new data to Prometheus
                containerPowerUsage.forEach((node, containerLs) -> {
                    containerLs.forEach(container -> {
                        log.debug("Adding {} {} {} {} {} = {}", node, container.namespace(), container.podName(), container.containerName(), "dynamic", container.value());
                        powerGauge
                                .labelValues(node,
                                        container.namespace(),
                                        container.podName(),
                                        container.containerName(),
                                        "dynamic"
                                )
                                .set(container.value());
                    });
                });
            }, 0, 15, TimeUnit.SECONDS);
            future.get();
        }
    }
}