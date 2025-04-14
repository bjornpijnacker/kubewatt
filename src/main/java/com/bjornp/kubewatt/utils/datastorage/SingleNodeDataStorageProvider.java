package com.bjornp.kubewatt.utils.datastorage;

import java.io.IOException;

public interface SingleNodeDataStorageProvider extends AutoCloseable {
    void addData(double... data) throws IOException;
}
