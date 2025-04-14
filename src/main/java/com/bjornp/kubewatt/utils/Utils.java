package com.bjornp.kubewatt.utils;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@UtilityClass
@Slf4j
public class Utils {
    public static ApiClient kubernetesClient() {
        try {
            return ClientBuilder.standard().build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
