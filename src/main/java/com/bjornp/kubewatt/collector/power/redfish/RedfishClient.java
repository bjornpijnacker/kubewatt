package com.bjornp.kubewatt.collector.power.redfish;

import com.bjornp.kubewatt.collector.power.redfish.model.RedfishComputerSystem;
import com.bjornp.kubewatt.collector.power.redfish.model.RedfishPowerControl;
import com.bjornp.kubewatt.utils.config.Config;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
public class RedfishClient {
    private final String host;

    private final String username;

    private final String password;

    private final List<String> systems;

    private final OkHttpClient client;

    public RedfishClient(String node) throws IOException {
        this.host = Config.get().collector().power().redfish().get(node).host();
        this.username = Config.get().collector().power().redfish().get(node).username();
        this.password = Config.get().collector().power().redfish().get(node).password();
        this.systems = Config.get().collector().power().redfish().get(node).systems();

        // Set up OkHttp to accept self-signed certificates. Redfish interfaces do not often sign certs.
        X509TrustManager TRUST_ALL_CERTS = new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[] {};
            }
        };

        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, new TrustManager[]{TRUST_ALL_CERTS}, new java.security.SecureRandom());

            client = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), TRUST_ALL_CERTS)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Unable to initialize self-signed cert SSL for OkHTTP", e);
        }

        // initialize the Redfish integration by checking ComputerSystems contains system in config
        init();
    }

    /**
     * The Redfish client will connect to Redfish and obtain metadata needed for power queries.
     * It connects to /redfish/v1/Systems to obtain the list of system names. It is expected that this result never
     * changes without KubeWatt needing to restart.
     */
    private void init() throws IOException {
        var request = new Request.Builder()
                .url("%s/redfish/v1/Systems".formatted(host))
                .addHeader("Authorization", Credentials.basic(this.username, this.password))
                .addHeader("Accept", "application/json")
                .build();
        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response from Redfish API " + response);
            }
            var cs = new Gson().fromJson(response.body().string(), RedfishComputerSystem.class);
            var foundSystems = new ArrayList<>(cs.getMembers().stream().map(member -> Arrays.stream(member.getId().split("/")).toList().getLast()).toList());

            var allSystemsFound = new HashSet<>(systems).containsAll(foundSystems);
            if (!allSystemsFound) {
                throw new RuntimeException("Not all systems in config were found in Redfish. Config: %s, Redfish: %s".formatted(systems, foundSystems));
            }
        }
    }

    public int getTotalPowerWatts() throws IOException {
        int total = 0;
        for (var system : systems) {
            var request = new Request.Builder()
                    .url("%s/redfish/v1/Chassis/%s/Power/PowerControl".formatted(host, system))
                    .addHeader("Authorization", Credentials.basic(this.username, this.password))
                    .addHeader("Accept", "application/json")
                    .build();
            try (var response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected response from Redfish API " + response);
                }
                var pc = new Gson().fromJson(response.body().string(), RedfishPowerControl.class);
                total += pc.getPowerConsumedWatts();
            }
        }
        return total;
    }
}
