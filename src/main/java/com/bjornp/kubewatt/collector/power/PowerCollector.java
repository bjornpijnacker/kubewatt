package com.bjornp.kubewatt.collector.power;

import java.util.Map;

public interface PowerCollector {
    String getName();
    Map<String, Double> getPowerWatts();
}
