package com.bjornp.kubewatt.initializer;

import com.bjornp.kubewatt.initializer.model.InitializationResult;
import com.bjornp.kubewatt.collector.power.PowerCollector;
import com.bjornp.kubewatt.collector.power.PowerCollectorFactory;
import com.bjornp.kubewatt.utils.config.Config;
import com.bjornp.kubewatt.utils.datastorage.DataStorageProviderFactory;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/// Initialize KubeWatt parameters using an empty cluster. KubeWatt takes measurements of the power draw of the empty cluster for a couple of minutes. The average of this value should closely resemble the static power of the cluster.
@Slf4j
public class BaseInitializer {
    /// How long to run the first empty-cluster data gathering step for. Measurements are collected every 15 seconds.
    private final static Duration INIT_IDLE_DURATION = Duration.of(5, ChronoUnit.MINUTES);

    private final PowerCollector powerCollector;

    public BaseInitializer() {
        this.powerCollector = PowerCollectorFactory.getPowerCollector();
    }

    public Map<String, InitializationResult> init() {
        var staticPower = this.findStaticPower();
//        var powerCurve = this.findPowerCurve();

        var result = new HashMap<String, InitializationResult>();
        for (var node : Config.get().collector().nodeNames()) {
            var initResult = new InitializationResult(staticPower.get(node));
            result.put(node, initResult);
        }
        return result;
    }

    @SneakyThrows
    private Map<String, Double> findStaticPower() {
        var api = new CoreV1Api();
        var allPods = api.listPodForAllNamespaces().execute().getItems();
        var controlPlanePods = Config.get().collector().utilization().controlPlanePods();
        allPods.removeIf(pod -> controlPlanePods.stream().anyMatch(cpPodName -> pod.getMetadata().getName().matches(cpPodName)));
        if (!allPods.isEmpty()) {
            throw new RuntimeException("Found pods that are not part of the control plane; cluster is not empty: %s"
                    .formatted(allPods.stream().map(pod -> "%s/%s".formatted(pod.getMetadata().getNamespace(), pod.getMetadata().getName())).toList()));
        }

        // initialize data structure
        Map<String, List<Double>> powerLs = new HashMap<>();
        for (var node : Config.get().collector().nodeNames()) {
            powerLs.put(node, new ArrayList<>());
        }

        // gather a measurement every 15 seconds
        log.info(
                "Starting idle power collection. Do not run workloads on the cluster. This step should finish at {}",
                LocalDateTime.now().plus(INIT_IDLE_DURATION)
        );
        try (var scheduler = Executors.newSingleThreadScheduledExecutor();
            var storage = DataStorageProviderFactory.getDataStorageProvider(this.getClass().getSimpleName(), "power", "power")) {
            var futures = new ArrayList<ScheduledFuture<?>>();
            for (int i = 0; i < INIT_IDLE_DURATION.toSeconds() / 15; ++i) {
                futures.add(scheduler.schedule(() -> {
                    var power = powerCollector.getPowerWatts();
                    for (var node : Config.get().collector().nodeNames()) {
                        var nodePower = power.get(node);
                        try {
                            storage.addData(node, nodePower);
                        } catch (IOException e) {
                            log.warn("Failed adding data to DataStorageProvider", e);
                        }
                        powerLs.get(node).add(nodePower);
                    }
                }, i * 15L, TimeUnit.SECONDS));
            }
            for (var future : futures) {
                future.get();  // to rethrow any exceptions that occur
            }
            // scheduler is closed thereby awaited automatically
        }

        // take the mean for each node
        Map<String, Double> power = new HashMap<>();
        for (var node : Config.get().collector().nodeNames()) {
            var ls = powerLs.get(node);
            var mean = ls.stream().collect(Collectors.averagingDouble(Double::doubleValue));
            power.put(node, mean);
        }

        return power;
    }

// The power curve is not used since it appears to (very nicely) approximate a linear relation, which means we don't need it.
//    @SneakyThrows
//    private Map<String, InitializationResult.PolyCurve> findPowerCurve() {
//        // run a stressor pod on each Kubernetes node
//        CoreV1Api api = new CoreV1Api();
//        try {
//            api.createNamespace(new V1Namespace().metadata(new V1ObjectMeta().name("kubewatt-stress"))).execute();
//            log.info("Created namespace 'kubewatt-stress'");
//
//            // get core count per node to pass along to stressors
//            var nodes = api.listNode().execute();
//            var nodeCpu = nodes
//                    .getItems()
//                    .stream()
//                    .collect(Collectors.toMap(node -> node.getMetadata().getName(),
//                            node -> node.getStatus().getCapacity().get("cpu")
//                    ));
//
//            // create stressor pod; one per node
//            for (var node : Config.getConfig().collector().nodeNames()) {
//                var pod = api.createNamespacedPod("kubewatt-stress",
//                        new V1Pod()
//                                .metadata(new V1ObjectMeta()
//                                        .name("kubewatt-stress-%s".formatted(Config
//                                                .getConfig()
//                                                .collector()
//                                                .nodeNames()
//                                                .indexOf(node)))
//                                        .namespace("kubewatt-stress"))
//                                .spec(new V1PodSpec()
//                                        .overhead(null)
//                                        .addContainersItem(new V1Container()
//                                                .name("kubewatt-stress")
//                                                .image("registry.bjornp.com/public/stress-random:latest")  // fixme allow custom image
//                                                .addEnvItem(new V1EnvVar()
//                                                        .name("N_CORES")
//                                                        .value(String.valueOf(nodeCpu
//                                                                .get(node)
//                                                                .getNumber()
//                                                                .intValue()))))
//                                        .nodeName(node))
//                ).execute();
//                log.info("Created pod {}", pod.getMetadata().getName());
//            }
//
//            // wait for all pods to be ready
//            var start = System.currentTimeMillis();
//            while (true) {
//                var pods = api.listNamespacedPod("kubewatt-system").execute().getItems();
//                if (pods.stream().allMatch(pod -> pod.getStatus().getPhase().equals("Running"))) {
//                    log.info("All pods active; starting data collection");
//                    break;
//                } else if (pods.stream().anyMatch(pod -> pod.getStatus().getPhase().equals("Failed"))) {
//                    throw new RuntimeException("A stressor pod has failed.");
//                } else {
//                    if (System.currentTimeMillis() - start > Duration.ofMinutes(1).toMillis()) {
//                        throw new RuntimeException(
//                                "Waited more than 1 minute for pods to become ready. Pods are not ready. Phases: %s".formatted(
//                                        pods.stream().collect(Collectors.toMap(pod -> pod.getMetadata().getName(),
//                                                pod -> pod.getStatus().getPhase()
//                                        ))));
//                    }
//                    log.info("Waiting for all pods to be ready");
//                    Thread.sleep(Duration.ofSeconds(2));
//                }
//            }
//
//            // read and store utilization data
//            log.info(
//                    "Starting stress test power collection. Do not run workloads on the cluster. This step should finish at {}",
//                    LocalDateTime.now().plus(INIT_STRESS_DURATION)
//            );
//
//            Map<String, List<WeightedObservedPoint>> observations = new HashMap<>();
//            Map<String, InitializationResult.PolyCurve> curves = new HashMap<>();
//            for (var node : Config.getConfig().collector().nodeNames()) {
//                observations.put(node, new ArrayList<>());
//            }
//
//            var scheduler = this.getScheduler();
//            for (int i = 0; i < INIT_STRESS_DURATION.toSeconds() / 15; ++i) {
//                scheduler.schedule(() -> {
//                    var power = powerCollector.getPowerWatts();
//                    var cpu = nodeUtilizationCollector.getNodeCpuUtilization();
//                    log.info("P{}\tC{}", power, cpu);
//                    for (var node : Config.getConfig().collector().nodeNames()) {
//                        observations.get(node).add(new WeightedObservedPoint(1, cpu.get(node), power.get(node)));
//                    }
//                }, i * 15L, TimeUnit.SECONDS);
//            }
//
//            scheduler.shutdown();
//            var completed = scheduler.awaitTermination((INIT_STRESS_DURATION.plus(INIT_GRACE_DURATION)).toSeconds(),
//                    TimeUnit.SECONDS
//            );
//            if (!completed) {
//                throw new RuntimeException("Stress test power collection failed to complete within allotted time.");
//            }
//
//            log.info("Data collection done!");
//            for (var node : Config.getConfig().collector().nodeNames()) {
//                var fitter = PolynomialCurveFitter.create(3);
//                var d3Poly = fitter.fit(observations.get(node));
//                curves.put(node, new InitializationResult.PolyCurve(d3Poly[0], d3Poly[1], d3Poly[2], d3Poly[3]));
//            }
//
//            return curves;
//        } catch (ApiException e) {
//            log.error("Failed creating a Kubernetes resource");
//            throw new RuntimeException(e);
//        } finally {
//            // destruct stressor namespace and pods
//            api.deleteNamespace("kubewatt-stress").execute();  // @SneakyThrows wraps this exception
//            log.info("Deleted namespace 'kubewatt-stress'");
//        }
//    }
}
