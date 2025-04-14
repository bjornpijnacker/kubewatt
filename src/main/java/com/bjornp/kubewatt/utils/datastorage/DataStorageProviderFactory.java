package com.bjornp.kubewatt.utils.datastorage;

import com.bjornp.kubewatt.utils.config.Config;

import java.io.IOException;

public class DataStorageProviderFactory {
    public static DataStorageProvider getDataStorageProvider(String name, String id, String... dataLabels) throws IOException {
        return switch (Config.get().dataStorage().mode()) {
            case FILE -> new FileDataStorageProvider(name, id, dataLabels);
            case EMAIL -> new EmailDataStorageProvider(name, id, dataLabels);
            case null, default -> new NoopDataStorageProvider();
        };
    }
}
