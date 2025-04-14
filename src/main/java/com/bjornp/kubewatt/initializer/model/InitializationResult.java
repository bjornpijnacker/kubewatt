package com.bjornp.kubewatt.initializer.model;

/// After initializing KubeWatt, this result will be stored. This same value should be provided as config when running KubeWatt in estimator mode.
/// @see com.bjornp.kubewatt.utils.config.Mode
public record InitializationResult(
        double staticPower
) {
}
