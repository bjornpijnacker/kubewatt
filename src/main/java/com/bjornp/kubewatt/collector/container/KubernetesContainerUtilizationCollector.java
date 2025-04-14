package com.bjornp.kubewatt.collector.container;

import com.bjornp.kubewatt.model.ContainerValue;
import com.bjornp.kubewatt.utils.config.Config;
import io.kubernetes.client.Metrics;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class KubernetesContainerUtilizationCollector {
    /// Returns the CPU seconds used by each container recently according to Kubernetes metrics API
    /// @return A Map of unique container identifiers and their utilization in CPU seconds. The inner map key is in the form 'namespace/pod/container' as we do not get container IDs from the metrics endpoint. This identifier is unique. The outer map specifies the node for each container.
    public Map<String, Map<String, ContainerValue>> getContainerUtilization() {
        var api = new CoreV1Api();

        var result = new HashMap<String, Map<String, ContainerValue>>();
        for (var node : Config.get().collector().nodeNames()) {
            result.put(node, new HashMap<>());
        }

        try {
            var namespaces = api.listNamespace().execute().getItems();
            var metricsApi = new Metrics();
            for (var namespace : namespaces) {
                var metrics = metricsApi.getPodMetrics(namespace.getMetadata().getName()).getItems();
                for (var pod : metrics) {
                    var podInfo = api.readNamespacedPod(pod.getMetadata().getName(), pod.getMetadata().getNamespace()).execute();
                    if (podInfo.getSpec() == null || podInfo.getSpec().getNodeName() == null) {
                        log.error("Null value in spec or nodeName for pod {}. It will be ignored", podInfo);
                        continue;
                    }
                    for (var container : pod.getContainers()) {
                        // NB: We don't get container ID from metrics endpoint but 'namespace/pod/container' MUST be globally unique per container.
                        // A '/' is not valid in either of these three meaning we can split this out later if need be.
                        result.get(podInfo.getSpec().getNodeName()).put(
                                "%s/%s/%s".formatted(namespace.getMetadata().getName(), pod.getMetadata().getName(), container.getName()),
                                new ContainerValue(
                                        container.getName(),
                                        pod.getMetadata().getName(),
                                        namespace.getMetadata().getName(),
                                        container.getUsage().get("cpu").getNumber().doubleValue()
                                )
                        );
                    }
                }
            }
        } catch (ApiException e) {
            throw new RuntimeException(e);
        }

        log.debug("Container utilization collection completed: {}", result);
        return result;
    }
}
