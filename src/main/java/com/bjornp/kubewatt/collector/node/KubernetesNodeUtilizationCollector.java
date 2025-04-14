package com.bjornp.kubewatt.collector.node;

import com.bjornp.kubewatt.utils.config.Config;
import io.kubernetes.client.Metrics;
import io.kubernetes.client.openapi.ApiException;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class KubernetesNodeUtilizationCollector {
    public Map<String, Double> getNodeCpuUtilization() {
        try {
            var metrics = new Metrics().getNodeMetrics().getItems();

            var metricsNodes = metrics.stream().map(metric -> metric.getMetadata().getName()).toList();
            if (!new HashSet<>(metricsNodes).equals(new HashSet<>(Config.get().collector().nodeNames()))) {
                throw new RuntimeException(
                        "Kubernetes cluster does not have the same nodes as configured in KubeWatt. Cluster: %s, KubeWatt: %s".formatted(
                                metricsNodes,
                                Config.get().collector().nodeNames()
                        ));
            }

            // we go from K8s Quantity -> BigDecimal -> Double. Since the number is about CPU utilization, and we don't
            // export more CPUs than a double can hold, this is fine.
            var result = metrics.stream().collect(Collectors.toMap(metric -> metric.getMetadata().getName(),
                    metric -> metric.getUsage().get("cpu").getNumber().doubleValue()
            ));
            log.debug("Node utilization collection completed: {}", result);
            return result;
        } catch (ApiException e) {
            throw new RuntimeException("Could not obtain Node utilization metrics from Kubernetes", e);
        }
    }
}
