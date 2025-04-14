package com.bjornp.kubewatt.utils.datastorage;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoopDataStorageProvider implements DataStorageProvider {
    @Override
    public void addData(String node, double... data) {
    }

    @Override
    public void close() {
    }
}
