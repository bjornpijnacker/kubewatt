package com.bjornp.kubewatt.collector.power;

import com.bjornp.kubewatt.collector.power.redfish.RedfishClient;
import com.bjornp.kubewatt.utils.config.Config;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class RedfishPowerCollector implements PowerCollector {
    private final Map<String, RedfishClient> redfishClients = new HashMap<>();

    protected RedfishPowerCollector() throws IOException {
        var nodes = Config.get().collector().nodeNames();
        for (String node : nodes) {
            redfishClients.put(node, new RedfishClient(node));
        }

        log.info("Successfully initialized RedfishPowerCollector");
    }

    @Override
    public String getName() {
        return "redfish";
    }

    @Override
    public Map<String, Double> getPowerWatts() {
        Map<String, Double> powerWatts = new HashMap<>();
        for (var entry : redfishClients.entrySet()) {
            try {
                powerWatts.put(entry.getKey(), (double) entry.getValue().getTotalPowerWatts());
            } catch (IOException e) {
                throw new RuntimeException("Unable to get power from Redfish client", e);
            }
        }
        return powerWatts;
    }
}
