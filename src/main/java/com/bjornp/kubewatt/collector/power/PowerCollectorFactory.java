package com.bjornp.kubewatt.collector.power;

import com.bjornp.kubewatt.utils.config.Config;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PowerCollectorFactory {
    public static PowerCollector getPowerCollector() {
        log.info("Using {} to obtain system power", Config.get().collector().power().source());
        try {
            return new PowerCollectorProxy(switch (Config.get().collector().power().source()) {
                case REDFISH -> new RedfishPowerCollector();
                default -> throw new IllegalStateException("Unexpected PowerCollector source: " + Config.get().collector().power().source());
            });
        } catch (Exception e) {
            log.error("Unable to create PowerCollector", e);
            throw new RuntimeException("Unable to create PowerCollector", e);
        }
    }
}
