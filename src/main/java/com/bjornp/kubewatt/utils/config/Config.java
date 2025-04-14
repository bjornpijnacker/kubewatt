package com.bjornp.kubewatt.utils.config;

import com.bjornp.kubewatt.utils.datastorage.DataStorageMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.URL;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/// Stores the global configuration for KubeWatt. By default, it is loaded from the `config.json` resource; however, the environment variable `KW_CONFIG_PATH` can be set to another path to load the config from. The config will be deserialized from JSON in Kebab case.
/// @param collector Configuration of the collector components, such as the PowerCollector.
 /// @param mode The mode to run KubeWatt in.
 /// @param dataStorage Configuration of data storage.
 /// @see Mode
@Slf4j
public record Config(
        @Valid @NotNull CollectorConfig collector,
        @NotNull Mode mode,
        @Valid @NotNull DataStorageConfig dataStorage,
        @Valid @NotNull BootstrapInitializerConfig bootstrapInitializer
) {
    public static Config get() {
        return ConfigHolder.INSTANCE;
    }

    private static class ConfigHolder {
        private static final Config INSTANCE;

        static {
            // load config from file
            var configPath = System.getenv("KW_CONFIG_PATH");
            try (var stream = configPath == null
                              ? Config.class.getResourceAsStream("/config.json")
                              : new FileInputStream(configPath);
                 var validationFactory = Validation.buildDefaultValidatorFactory()) {
                if (stream == null) {
                    throw new RuntimeException("Unable to find config.json");
                }

                var objectMapper = new ObjectMapper();
                objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
                INSTANCE = objectMapper.readValue(new InputStreamReader(stream), Config.class);

                var validator = validationFactory.getValidator();
                var violations = validator.validate(INSTANCE);
                if (violations.isEmpty()) {
                    log.info("Successfully loaded config.");
                } else {
                    for (var violation : violations) {
                        log.error(
                                "Config validation violation: '{}' {}",
                                violation.getPropertyPath(),
                                violation.getMessage()
                        );
                    }
                    throw new RuntimeException("Config failed validation, see logs for details");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public record CollectorConfig(
            @NotEmpty List<@NotBlank String> nodeNames,
            @Valid @NotNull PowerConfig power,
            @Valid @NotNull UtilizationConfig utilization,
            Map<String, Double> nodeStaticPower
    ) {
        @AssertTrue(message = "collector.node-static-power cannot be null when mode is ESTIMATOR")
        private boolean isStaticPowerPresent() {
            return switch (ConfigHolder.INSTANCE.mode) {
                case INIT_BASE, INIT_BOOTSTRAP -> true;
                case ESTIMATOR -> nodeStaticPower != null;
            };
        }

        @AssertTrue(message = "collector.node-static-power must contain the same keys as collector.node-names")
        private boolean isStaticPowerOk() {
            return nodeStaticPower == null || nodeStaticPower.keySet().equals(new HashSet<>(nodeNames));
        }

        public record PowerConfig(
                @NotNull PowerSource source,
                Map<@NotBlank String, @Valid RedfishConfig> redfish
        ) {
            @AssertTrue(message = "collector.power.redfish cannot be null when source = REDFISH")
            private boolean isOk() {
                return switch (source) {
                    case REDFISH -> redfish != null;
                };
            }

            @AssertTrue(message = "collector.power.redfish must contain the same keys as collector.node-names")
            private boolean isRedfishOk() {
                if (source != PowerSource.REDFISH) {
                    return true;
                }
                return redfish.keySet().equals(new HashSet<>(ConfigHolder.INSTANCE.collector.nodeNames));
            }

            public enum PowerSource {
                REDFISH
            }

            public record RedfishConfig(
                    @NotBlank @URL String host,
                    @NotEmpty List<@NotBlank String> systems,
                    @NotBlank String username,
                    @NotBlank String password
            ) {
            }
        }

        public record UtilizationConfig(
                /* A list of control plane pod names (regex) can be provided. KubeWatt will interpret these as pods whose
                 * utilization is part of the empty cluster setup when running in any of the INIT modes.
                 */
                @NotEmpty List<@NotBlank String> controlPlanePods
        ) {

        }
    }

    public record DataStorageConfig(
            @Nullable DataStorageMode mode,
            @Nullable String path,
            @Valid @Nullable EmailConfig email
    ) {
        public record EmailConfig(
                @NotBlank String hostname,
                @Min(1) @Max(65535) int port,
                boolean useSsl,
                @NotBlank String username,
                @NotBlank String password,
                @NotBlank String from,
                @NotBlank String recipient
        ) {
        }
    }

    /// Customize the values of the Bootstrap Initializer validation checks. When validating, CPU loads are collected into buckets based on percentage. The size of the largest bucket is multiplied by `minMult`, which yields the number of elements that the smallest bucket must at least contain. If not, data is not uniform enough to continue.
    public record BootstrapInitializerConfig(
            @Min(0) @Max(100) int bucketPercentStart,
            @Min(0) @Max(100) int bucketPercentEnd,
            @Min(0) @Max(100) int bucketSize,
            @Min(0) @Max(1) double minMult,
            @NotEmpty Map<@NotBlank String, @NotNull Boolean> nodeHasSmt
    ) {
        @AssertTrue(message = "collector.node-has-smt must contain the same keys as collector.node-names")
        private boolean isSMPTOkay() {
            return nodeHasSmt.keySet().equals(new HashSet<>(ConfigHolder.INSTANCE.collector.nodeNames));
        }
    }
}
