package com.bjornp.kubewatt.collector.power;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/// The PowerCollectorProxy can wrap any PowerCollector for debug purposes. It logs how long obtaining power data took.
@Slf4j
public class PowerCollectorProxy implements PowerCollector {
    private final PowerCollector powerCollector;

    public PowerCollectorProxy(PowerCollector powerCollector) {
        if (powerCollector instanceof PowerCollectorProxy) {
            throw new IllegalArgumentException("PowerCollector already proxy");
        }
        this.powerCollector = powerCollector;
    }

    @Override
    public String getName() {
        return powerCollector.getName();
    }

    @Override
    public Map<String, Double> getPowerWatts() {
        if (log.isDebugEnabled()) {
            var start = System.currentTimeMillis();
            var pw = powerCollector.getPowerWatts();
            var end = System.currentTimeMillis();
            log.debug("Power collection completed in {} ms\t {}", end - start, pw);
            return pw;
        } else {
            return powerCollector.getPowerWatts();
        }
    }
}
