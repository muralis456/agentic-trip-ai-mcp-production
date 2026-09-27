package com.example.travel.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", ex.getMessage() == null ? "Too many requests" : ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        log.warn("Bad plan state: {}", message);

        // A plan stored only in browser history may outlive its active HITL
        // checkpoint. Do not expose the LangGraph checkpoint detail to the UI.
        if (message.contains("Missing Checkpoint") || message.contains("No graph checkpoint")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error",
                            "This plan approval session is no longer active. It may have already been completed or expired. Please create a new trip plan."));
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error",
                        "We couldn't process that travel request. Please check the details and try again."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoResourceFoundException ex) {
        log.debug("Resource not found: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    /**
     * SSE clients can disconnect while a graph node is still publishing progress.
     * At that point the servlet response is already committed/closed. Returning a
     * JSON ResponseEntity would make Spring try to serialize JSON as text/event-stream,
     * producing a second HttpMessageNotWritableException. Treat this as a transport
     * lifecycle event instead: the SSE hub already owns cleanup.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleSseTransportClosed(AsyncRequestNotUsableException ex) {
        log.debug("SSE response is no longer usable: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Object handleGeneralException(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing {}", request.getDescription(false), ex);

        String description = request.getDescription(false);
        boolean apiRequest = description.contains("uri=/api/");
        if (apiRequest) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Something went wrong while processing the request."));
        }

        // Browser/page requests must not be converted into JSON merely because
        // a view-rendering or controller exception occurred. Return a dedicated
        // HTML error view so the browser receives the correct representation.
        org.springframework.web.servlet.ModelAndView modelAndView =
                new org.springframework.web.servlet.ModelAndView("error");
        modelAndView.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        modelAndView.addObject("message", "We couldn't load this page. Please try again.");
        return modelAndView;
    }
}
