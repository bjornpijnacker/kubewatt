package com.bjornp.kubewatt.initializer;

import com.bjornp.kubewatt.collector.container.KubernetesContainerUtilizationCollector;
import com.bjornp.kubewatt.initializer.model.InitializationResult;
import com.bjornp.kubewatt.collector.node.KubernetesNodeUtilizationCollector;
import com.bjornp.kubewatt.collector.power.PowerCollector;
import com.bjornp.kubewatt.collector.power.PowerCollectorFactory;
import com.bjornp.kubewatt.utils.config.Config;
import com.bjornp.kubewatt.utils.datastorage.DataStorageProviderFactory;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.UniformRealDistribution;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoint;
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/// Initialize KubeWatt parameters using a non-empty cluster. KubeWatt takes measurements of the power draw and CPU usage of the cluster and attempts a regression on this data in order to find the power draw at low CPU utilization values. The total power used by the Kubernetes control-plane is used as a baseline CPU value for the hypothetical empty cluster.
@Slf4j
public class BootstrapInitializer {
    /// How long to run data collection for. Might be repeated if the collected data is not sufficient to perform the regression with.
    private final static Duration INIT_COLLECT_DURATION = Duration.of(30, ChronoUnit.MINUTES);

    private final PowerCollector powerCollector;

    private final KubernetesNodeUtilizationCollector nodeUtilizationCollector;

    private final KubernetesContainerUtilizationCollector containerUtilizationCollector;

    public BootstrapInitializer() {
        this.powerCollector = PowerCollectorFactory.getPowerCollector();
        this.nodeUtilizationCollector = new KubernetesNodeUtilizationCollector();
        this.containerUtilizationCollector = new KubernetesContainerUtilizationCollector();
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

    private Map<String, Double> findStaticPower() {
        // gather points of data for each node
        Map<String, List<WeightedObservedPoint>> observations = new HashMap<>();
        Map<String, List<Double>> controlPlaneObservations = new HashMap<>();

        for (var node : Config.get().collector().nodeNames()) {
            observations.put(node, new ArrayList<>());
            controlPlaneObservations.put(node, new ArrayList<>());
        }

        Map<String, Quantity> nodeNumCpus;
        try {
            var api = new CoreV1Api();
            var nodes = api.listNode().execute();
            nodeNumCpus = nodes
                    .getItems()
                    .stream()
                    .collect(Collectors.toMap(node -> node.getMetadata().getName(),
                            node -> node.getStatus().getCapacity().get("cpu")
                    ));
        } catch (ApiException e) {
            throw new RuntimeException("Couldn't initialize using BootstrapInitialzer", e);
        }

        try (var containerUtilizationStorage = DataStorageProviderFactory.getDataStorageProvider(this.getClass().getSimpleName(), "containerUtilization", "cpuSeconds", "powerWatts");
             var controlPlaneUtilizationStorage = DataStorageProviderFactory.getDataStorageProvider(this.getClass().getSimpleName(), "controlPlaneUtilization", "cpuSeconds")) {
            do {
                log.info(
                        "Starting CPU load and power collection for {}. This round should finish at {}. Round will be repeated until sufficient data has been collected",
                        INIT_COLLECT_DURATION,
                        LocalDateTime.now().plus(INIT_COLLECT_DURATION)
                );
                try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
                    var futures = new ArrayList<ScheduledFuture<?>>();
                    for (int i = 0; i < INIT_COLLECT_DURATION.toSeconds() / 15; ++i) {
                        futures.add(scheduler.schedule(() -> {
                            var nodeUtilization = nodeUtilizationCollector.getNodeCpuUtilization();
                            var power = powerCollector.getPowerWatts();

                            // get container utilization total of control plane only
                            var containerUtilization = containerUtilizationCollector.getContainerUtilization();
                            var controlPlaneUtilization = containerUtilization.entrySet().stream().collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    // for each node, filter the container utilization to include only controlpane nodes; sum the usage values per node
                                    nodeEntry -> nodeEntry.getValue().entrySet().stream().filter(entry -> {
                                        // 'entry' is a specific container
                                        var podName = entry.getKey().split("/")[1];
                                        return Config.get().collector().utilization().controlPlanePods().stream().anyMatch(podName::matches);
                                    }).mapToDouble(entry -> entry.getValue().value()).sum()
                                    // sum the utilization per node
                            ));
                            for (var node : Config.get().collector().nodeNames()) {
                                controlPlaneObservations.get(node).add(controlPlaneUtilization.get(node));
                                observations.get(node).add(new WeightedObservedPoint(1, nodeUtilization.get(node), power.get(node)));

                                try {
                                    controlPlaneUtilizationStorage.addData(node, controlPlaneUtilization.get(node));
                                    containerUtilizationStorage.addData(node, nodeUtilization.get(node), power.get(node));
                                } catch (IOException e) {
                                    log.warn("Unable to write data to persistent storage. Continuing anyway.");
                                }
                            }
                        }, i * 15L, TimeUnit.SECONDS));
                    }

                    for (var future : futures) future.get();  // this ensures any inner exceptions are rethrown on the main thread
                } catch (ExecutionException | InterruptedException e) {  // todo: BaseInitializer also needs this
                    log.error("Error thrown from scheduled method, rethrowing on main thread");
                    throw new RuntimeException(e);
                }
            } while (!verifyDataValidity(observations, nodeNumCpus));
        } catch (Exception e) {
            throw new RuntimeException("Couldn't initialize debug data storage for the BootstrapInitializer", e);
        }
//        log.info("All observations {}", observations);

        // get avg of control plane utilization per node
        var controlPlaneMean = new HashMap<String, Double>();
        for (var node : Config.get().collector().nodeNames()) {
            var ls = controlPlaneObservations.get(node);
            var mean = ls.stream().collect(Collectors.averagingDouble(Double::doubleValue));
            controlPlaneMean.put(node, mean);
        }
        log.info(String.valueOf(controlPlaneMean));

        // perform regression on power/CPU observations to calculate curve
        return Config.get().collector().nodeNames().stream()
                .collect(Collectors.toMap(node -> node, node -> {
            var fitter = PolynomialCurveFitter.create(1);
            if (Config.get().bootstrapInitializer().nodeHasSmt().get(node)) {
                log.info("Node '{}' has SMT enabled; discarding top 50% of CPU usage values", node);
                int nCpu = nodeNumCpus.get(node).getNumber().intValue() / 2;
                observations.put(node, observations.get(node).stream().filter(observation -> observation.getX() <= nCpu).toList());
            }
            var d1Poly = fitter.fit(observations.get(node));
            var cpUtil = controlPlaneMean.get(node);
            return d1Poly[0] + cpUtil * d1Poly[1];
        }));
    }

    /// Verify that the collected data has sufficient variability and distribution to perform the upcoming regression with.
    /// The data is checked for each node, and if any of the nodes is insufficient then false will be returned.
    /// @return <table><tbody>
     ///    <tr><td>`true`</td><td>if the data is sufficient</td></tr>
     ///    <tr><td>`false`</td><td>if the data is not sufficient and more data collection should be performed</td></tr>
     /// </tbody></table>
    private boolean verifyDataValidity(
            Map<String, List<WeightedObservedPoint>> observations,
            Map<String, Quantity> numCpus
    ) {
        return observations
                .entrySet()
                .stream()
                .allMatch(entry -> verifyDataValidity(entry.getKey(),
                        entry.getValue(),
                        numCpus.get(entry.getKey()).getNumber().intValue()
                ));
    }

    /// To verify that data is valid we perform a (very!) simple check of the spread of the data.
    /// We want to know:
    ///   - Is there data between 20% and 80% CPU usage;
    ///   - Is there data in every 10%-bucket between 20% and 80% and is there approx. an equal amount of data in each bucket?
    ///
    /// The prior checks whether the range of the data is sufficient, the latter checks whether the data is distributed somewhat uniformly.
    private boolean verifyDataValidity(String node, List<WeightedObservedPoint> observations, int numCpu) {
        if (observations.isEmpty()) {
            return false;
        }
        var cpu = observations.stream().map(WeightedObservedPoint::getX).toList();

//        var minCpu = cpu.stream().min(Double::compareTo).orElseThrow();
//        var maxCpu = cpu.stream().max(Double::compareTo).orElseThrow();
//
//        // check data range
//        if (minCpu / numCpu > 0.2d) {
//            log.warn("MinCPU value > 20%. Collected data is not sufficient for node {}", node);
//            return false;
//        }
//
//        if (maxCpu / numCpu < 0.8d) {
//            log.warn("MaxCPU value < 80%. Collected data is not sufficient for node {}", node);
//            return false;
//        }

        // check data is (somewhat) uniformly distributed !! TEMP
        var test = new KolmogorovSmirnovTest();
        var stat = test.kolmogorovSmirnovTest(new UniformRealDistribution(), cpu.stream().mapToDouble(Double::doubleValue).toArray());
        log.info("K-S D_n: {}", stat);

        // verify that the minimum amount of data in each bucket is no less than half than in the largest bucket
        // this should yield a (very) rough approximation of uniformly distributed data
        // we required and thus check only data between 20% and 80% utilization
        int bucketPercentSize = Config.get().bootstrapInitializer().bucketSize();
        int bucketPercentStart = Config.get().bootstrapInitializer().bucketPercentStart();
        int bucketPercentEnd = Config.get().bootstrapInitializer().bucketPercentEnd();

        double[][] buckets = new double[(bucketPercentEnd - bucketPercentStart) / bucketPercentSize][];
        int maxBucket_i = 0;
        for (int bucket = bucketPercentStart; bucket < bucketPercentEnd; bucket += bucketPercentSize) {
            int i = (bucket - bucketPercentStart) / bucketPercentSize;
            double bucketStart = bucket / 100d;
            double bucketEnd = (bucket + bucketPercentSize) / 100d;

            var bucketCpu = cpu.stream()
                    .map(cpuSeconds -> cpuSeconds / numCpu)
                    .filter(cpuPercent -> cpuPercent >= bucketStart && cpuPercent < bucketEnd)
                    .mapToDouble(Double::doubleValue)
                    .toArray();
            buckets[i] = bucketCpu;

            if (buckets[maxBucket_i].length < buckets[i].length) {
                maxBucket_i = i;
            }
        }
        log.debug("Largest bucket holds {} samples", buckets[maxBucket_i].length);

        long minSamplesRequired = (long) (buckets[maxBucket_i].length * Config.get().bootstrapInitializer().minMult());
        boolean failed = false;
        for (int i = 0; i < buckets.length; i++) {
            var bucket = buckets[i];
            if (bucket.length < minSamplesRequired) {
                failed = true;
                log.warn("Too few samples for node {} in bucket {}%-{}%: {}/{}",
                        node,
                        (i * bucketPercentSize) + bucketPercentStart,
                        ((i + 1) * bucketPercentSize) + bucketPercentStart,
                        bucket.length,
                        minSamplesRequired
                );
            }
        }
        if (failed) return false;

        return true;
    }
}
