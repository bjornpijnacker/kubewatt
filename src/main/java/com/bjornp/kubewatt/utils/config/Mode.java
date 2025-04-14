package com.bjornp.kubewatt.utils.config;

/**
 * KubeWatt can run in two modes. The first mode, INIT, initializes KubeWatt by taking measurements of a cluster. This
 * produces a set of parameters that the KubeWatt ESTIMATOR-mode needs to run. Initialization can be done in two ways:
 * INIT_BASE requires a completely empty cluster with no workloads running except base Kubernetes. It will take
 * zero-level measurements to find what the static power load of the system is, and it will run benchmarks to find the
 * power usage against CPU load curve. If a completely empty cluster is not available then KubeWatt can also run in
 * INIT_BOOTSTRAP mode, where it attempts to find these parameters by analyzing existing workloads. Note: this will only
 * work if the existing workloads have sufficient variability in CPU usage and power draw.
 * <p>
 * ESTIMATOR mode will take the parameters produced by the initialization and use these to estimate the power usage per
 * container/pod.
 */
public enum Mode {
    INIT_BASE,
    INIT_BOOTSTRAP,
    ESTIMATOR
}
