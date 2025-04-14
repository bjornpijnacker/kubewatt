package com.bjornp.kubewatt.estimator;

import com.bjornp.kubewatt.collector.container.KubernetesContainerUtilizationCollector;
import com.bjornp.kubewatt.collector.power.PowerCollector;
import com.bjornp.kubewatt.collector.power.PowerCollectorFactory;
import com.bjornp.kubewatt.model.ContainerValue;
import com.bjornp.kubewatt.utils.config.Config;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ContainerPowerEstimator {
    private final PowerCollector powerCollector = PowerCollectorFactory.getPowerCollector();

    private final KubernetesContainerUtilizationCollector utilizationCollector = new KubernetesContainerUtilizationCollector();

    /// Get the power usage in Watts for each container, grouped by node name.
    public Map<String, List<ContainerValue>> getContainerPowerUsage() {
        var nodeStaticPower = Config.get().collector().nodeStaticPower();
        var controlPlanePods = Config.get().collector().utilization().controlPlanePods();

        var nodePower = powerCollector.getPowerWatts();
        var nodeUtilization = utilizationCollector.getContainerUtilization();

        var result = new HashMap<String, List<ContainerValue>>();

        for (var node : Config.get().collector().nodeNames()) {
            var nodeResult = new ArrayList<ContainerValue>();

            var power = nodePower.get(node);
            var staticPower = Math.min(nodeStaticPower.get(node), power);
            var dynamicPower = power - staticPower;
            // TODO: perform sanity check, dynamicPower should be zero if utilization map is empty

            var utilization = nodeUtilization.get(node);
            // remove any control plane pods; there are counted in static power usage value
            utilization = utilization.entrySet().stream()
                    .filter(entry -> controlPlanePods.stream().noneMatch(controlPlanePattern -> entry.getValue().podName().matches(controlPlanePattern)))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            var cpuTotal = utilization.values().stream().mapToDouble(ContainerValue::value).sum();
            for (var container : utilization.values()) {
                nodeResult.add(new ContainerValue(
                        container.containerName(),
                        container.podName(),
                        container.namespace(),
                        cpuTotal == 0
                            ? 0
                            : (container.value() / cpuTotal) * dynamicPower
                ));
            }

            result.put(node, nodeResult);
        }

        log.debug("Container power estimation finished: {}", result);
        return result;
    }
}
