package com.bjornp.kubewatt.model;

import jakarta.validation.constraints.NotNull;

public record ContainerValue(
        String containerName,
        String podName,
        String namespace,
        double value
) {
    public boolean hasSameMeta(@NotNull  ContainerValue v) {
        return this.containerName.equals(v.containerName)
                && this.podName.equals(v.podName)
                && this.namespace.equals(v.namespace);
    }
}
