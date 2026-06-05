package com.DinoCorp.Lost_In_Woods.exception;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Entity Validation Exception");
        error.put("message", ex.getMessage());
        error.put("status", HttpStatus.NOT_FOUND.value());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    // A missing static file or unmapped path (favicon.ico, "/", etc.) must stay a 404.
    // Without this, the catch-all below turns those into a misleading 500.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Not Found");
        error.put("message", ex.getMessage());
        error.put("status", HttpStatus.NOT_FOUND.value());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    // The client (browser) closed the connection mid-response — typically because the
    // user navigated away or reloaded while a large static asset (the logo PNG, the
    // music MP3) was still streaming. Nothing the server can do; the socket is gone
    // and the response is already committed with a non-JSON Content-Type. Log a
    // single quiet line and DO NOT try to write a body (returning void tells Spring
    // we've handled it). Without this, the catch-all below tried to serialize a JSON
    // HashMap into an image/png response and threw HttpMessageNotWritableException —
    // doubling one harmless disconnect into two scary stack traces.
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        log.debug("Client disconnected mid-response: {}", ex.getMessage());
    }

    // Broken-pipe / connection-reset IOExceptions from streaming responses get the
    // same treatment for the same reason. We narrow the message check so this only
    // swallows real network resets — anything else still falls through to the
    // catch-all so genuine bugs aren't hidden.
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (msg.contains("broken pipe") || msg.contains("connection reset")
                || msg.contains("connection abort") || msg.contains("an established connection")) {
            log.debug("Client disconnected mid-response: {}", ex.getMessage());
            return null;   // response already committed; don't try to write a body
        }
        log.error("IO error while processing request", ex);
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Internal System Server Error");
        error.put("message", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralFault(Exception ex) {
        // If a ClientAbortException slipped through wrapped in something else, treat it the same.
        Throwable c = ex;
        while (c != null) {
            if (c instanceof ClientAbortException) {
                log.debug("Client disconnected mid-response: {}", c.getMessage());
                return null;
            }
            c = c.getCause();
        }
        // Log the real cause so it actually shows up in the server console.
        log.error("Unhandled exception while processing request", ex);
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Internal System Server Error");
        // Surface the exception type/message so it can be diagnosed from the client too.
        error.put("message", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
