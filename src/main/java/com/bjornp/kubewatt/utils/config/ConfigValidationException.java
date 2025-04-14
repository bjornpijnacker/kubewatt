package com.bjornp.kubewatt.utils.config;

public class ConfigValidationException extends RuntimeException {
    public ConfigValidationException(String message) {
        super(message);
    }
}
