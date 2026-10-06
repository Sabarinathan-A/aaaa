package com.frauddetector.http;

/**
 * Runtime exception that carries an HTTP status code. The {@link Router} maps
 * these to JSON error bodies with the given status; any other exception becomes
 * a 500.
 */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ApiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
