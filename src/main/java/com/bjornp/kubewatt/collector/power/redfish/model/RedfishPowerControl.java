package com.bjornp.kubewatt.collector.power.redfish.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class RedfishPowerControl {
    @SerializedName("PowerMetrics")
    private PowerMetrics powerMetrics;

    @SerializedName("PowerConsumedWatts")
    private int powerConsumedWatts;

    @AllArgsConstructor
    @Getter
    @ToString
    public static class PowerMetrics {
        @SerializedName("AverageConsumedWatts")
        private int averageConsumedWatts;

        @SerializedName("IntervalInMin")
        private int intervalInMin;

        @SerializedName("MaxConsumedWatts")
        private int maxConsumedWatts;

        @SerializedName("MinConsumedWatts")
        private int minConsumedWatts;
    }
}
