package com.bjornp.kubewatt.utils.datastorage;

import com.bjornp.kubewatt.utils.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class FileDataStorageProvider implements DataStorageProvider {
    @Getter(value = AccessLevel.PROTECTED)
    private final Map<String, SingleNodeFileDataStorageProvider> storageProviders = new HashMap<>();

    private final String[] dataLabels;

    protected FileDataStorageProvider(String name, String id, String... dataLabels) throws IOException {
        this.dataLabels = dataLabels;
        for (var node : Config.get().collector().nodeNames()) {
            storageProviders.put(node, new SingleNodeFileDataStorageProvider(name, id, node, dataLabels));
        }
    }

    @Override
    public void addData(String node, double... data) throws IOException {
        var sanitizedData = new double[dataLabels.length];

        // We use System.arraycopy since reassigning varargs can lead to unsafe scenarios.
        if (data.length < dataLabels.length) {
            log.warn("Data size is smaller than number of labels. Padding with zeroes.");
            System.arraycopy(data, 0, sanitizedData, 0, data.length);
            // rest of sanitizedData will be initialized with zeroes automatically.
        } else if (data.length > dataLabels.length) {
            log.warn("Data size is larger than number of labels. Ignoring overflow.");
            System.arraycopy(data, 0, sanitizedData, 0, dataLabels.length);
        } else {
            System.arraycopy(data, 0, sanitizedData, 0, data.length);
        }

        storageProviders.get(node).addData(sanitizedData);
    }

    @Override
    public void close() throws Exception {
        for (SingleNodeFileDataStorageProvider provider : storageProviders.values()) {
            provider.close();
        }
    }
}
