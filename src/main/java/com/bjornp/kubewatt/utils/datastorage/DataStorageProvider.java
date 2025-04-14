package com.bjornp.kubewatt.utils.datastorage;

import java.io.IOException;

/// The DataStorageProvider classes provide a unified interface to store collected data as CSV. Viewing the raw collected data is useful for debugging purposes or to gain insight into system behavior. Data is stored per node as CSV.
public interface DataStorageProvider extends AutoCloseable {
    void addData(String node, double... data) throws IOException;
}
