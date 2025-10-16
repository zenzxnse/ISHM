package com.ishm.soil;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;

/**
 * Root controller for handling main navigation and page redirects
 */
@Controller("/")
public class RootController {

    private static final Logger LOG = LoggerFactory.getLogger(RootController.class);

    /**
     * Redirect root to index.html
     */
    @Get
    public HttpResponse<?> index() {
        return HttpResponse.redirect(URI.create("/index.html"));
    }

    /**
     * Health check endpoint
     */
    @Get("/health")
    public HttpResponse<Map<String, Object>> health() {
        return HttpResponse.ok(Map.of(
                "status", "UP",
                "application", "Interactive Soil Health Map",
                "version", "1.0.0",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * API info endpoint
     */
    @Get("/api")
    public HttpResponse<Map<String, Object>> apiInfo() {
        return HttpResponse.ok(Map.of(
                "name", "Soil Health Monitoring API",
                "version", "1.0.0",
                "endpoints", Map.of(
                        "map", "/api/map/districts",
                        "recommendations", "/api/recommendations/calculate",
                        "dashboard", "/api/dashboard/summary",
                        "health", "/health"
                )
        ));
    }
}