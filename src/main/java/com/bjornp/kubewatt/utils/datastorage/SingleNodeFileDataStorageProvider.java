package com.bjornp.kubewatt.utils.datastorage;

import com.bjornp.kubewatt.KubeWatt;
import com.bjornp.kubewatt.utils.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

@Slf4j
public class SingleNodeFileDataStorageProvider implements SingleNodeDataStorageProvider {
    private final CSVPrinter printer;

    @Getter(value = AccessLevel.PROTECTED)
    private final String filename;

    protected SingleNodeFileDataStorageProvider(String name, String id, String nodeName, String... dataLabels) throws IOException {
        this.filename = "%s/%d-%s-%s-%s.csv".formatted(
                Optional.ofNullable(Config.get().dataStorage().path()).orElse("."),
                KubeWatt.start.getEpochSecond(),
                nodeName,
                name,
                id
        );
        var file = new FileWriter(filename);
        printer = new CSVPrinter(file, CSVFormat.DEFAULT);
        printer.printRecord((Object[]) dataLabels);
    }

    /// @param data Data to write to file. MUST be of the same length as `dataLabels` provided previously.
    @Override
    public void addData(double... data) throws IOException {
        printer.printRecord(Arrays.stream(data).boxed().toArray());
    }

    @Override
    public void close() throws IOException {
        printer.close();
    }
}
