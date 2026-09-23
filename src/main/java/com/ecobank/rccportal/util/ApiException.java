package com.ecobank.rccportal.util;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Équivalent de src/errors/app-error.js côté Node : une exception métier porte
 * son code HTTP et un code d'erreur stable (utilisé par le frontend), jamais
 * une trace technique brute.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException locked(String code, String message) {
        return new ApiException(HttpStatus.LOCKED, code, message);
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", message);
    }
}
