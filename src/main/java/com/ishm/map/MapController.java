package com.ishm.map;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Controller("/api/map")
public class MapController {

    private static final Logger LOG = LoggerFactory.getLogger(MapController.class);

    @Inject
    DataSource dataSource;

    /**
     * Get all districts with their soil health data as GeoJSON
     */
    @Get("/districts")
    public HttpResponse<Map<String, Object>> getDistricts(@QueryValue Optional<String> state) {
        try {
            String query = """
                SELECT 
                    d.id as district_id,
                    d.name as district_name,
                    d.state_name,
                    ST_AsGeoJSON(d.geom) as geometry,
                    s.nitrogen_avg,
                    s.nitrogen_status,
                    s.phosphorus_avg,
                    s.phosphorus_status,
                    s.potassium_avg,
                    s.potassium_status,
                    s.ph_avg,
                    s.organic_carbon,
                    s.samples_analyzed,
                    s.measurement_year
                FROM districts d
                LEFT JOIN soil_health_data s ON d.id = s.district_id
                WHERE 1=1
                """;

            List<Object> params = new ArrayList<>();

            if (state.isPresent() && !state.get().isEmpty()) {
                query += " AND d.state_name = ?";
                params.add(state.get());
            }

            query += " ORDER BY d.state_name, d.name";

            List<Map<String, Object>> features = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> feature = new HashMap<>();
                    feature.put("type", "Feature");

                    // Parse geometry from GeoJSON string
                    String geomJson = rs.getString("geometry");
                    if (geomJson != null) {
                        feature.put("geometry", parseJson(geomJson));
                    }

                    // Properties
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("district_id", rs.getLong("district_id"));
                    properties.put("district_name", rs.getString("district_name"));
                    properties.put("state_name", rs.getString("state_name"));
                    properties.put("nitrogen_avg", rs.getDouble("nitrogen_avg"));
                    properties.put("nitrogen_status", rs.getString("nitrogen_status"));
                    properties.put("phosphorus_avg", rs.getDouble("phosphorus_avg"));
                    properties.put("phosphorus_status", rs.getString("phosphorus_status"));
                    properties.put("potassium_avg", rs.getDouble("potassium_avg"));
                    properties.put("potassium_status", rs.getString("potassium_status"));
                    properties.put("ph_avg", rs.getDouble("ph_avg"));
                    properties.put("organic_carbon", rs.getDouble("organic_carbon"));
                    properties.put("samples_analyzed", rs.getInt("samples_analyzed"));
                    properties.put("measurement_year", rs.getInt("measurement_year"));

                    feature.put("properties", properties);
                    features.add(feature);
                }
            }

            // Build GeoJSON FeatureCollection
            Map<String, Object> geoJson = new HashMap<>();
            geoJson.put("type", "FeatureCollection");
            geoJson.put("features", features);

            return HttpResponse.ok(geoJson);

        } catch (SQLException e) {
            LOG.error("Error fetching districts", e);
            return HttpResponse.serverError(Map.of("error", "Database error"));
        }
    }

    /**
     * Get soil health statistics by state
     */
    @Get("/stats/{state}")
    public HttpResponse<Map<String, Object>> getStateStats(@PathVariable String state) {
        try {
            String query = """
                SELECT 
                    COUNT(DISTINCT d.id) as district_count,
                    AVG(s.nitrogen_avg) as avg_nitrogen,
                    AVG(s.phosphorus_avg) as avg_phosphorus,
                    AVG(s.potassium_avg) as avg_potassium,
                    AVG(s.ph_avg) as avg_ph,
                    AVG(s.organic_carbon) as avg_oc,
                    SUM(s.samples_analyzed) as total_samples
                FROM districts d
                LEFT JOIN soil_health_data s ON d.id = s.district_id
                WHERE d.state_name = ?
                """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, state);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("state", state);
                    stats.put("district_count", rs.getInt("district_count"));
                    stats.put("avg_nitrogen", rs.getDouble("avg_nitrogen"));
                    stats.put("avg_phosphorus", rs.getDouble("avg_phosphorus"));
                    stats.put("avg_potassium", rs.getDouble("avg_potassium"));
                    stats.put("avg_ph", rs.getDouble("avg_ph"));
                    stats.put("avg_organic_carbon", rs.getDouble("avg_oc"));
                    stats.put("total_samples", rs.getInt("total_samples"));

                    return HttpResponse.ok(stats);
                }

                return HttpResponse.notFound();
            }

        } catch (SQLException e) {
            LOG.error("Error fetching state stats", e);
            return HttpResponse.serverError(Map.of("error", "Database error"));
        }
    }

    /**
     * Get bounding box for a specific district
     */
    @Get("/district/{districtName}/bbox")
    public HttpResponse<Map<String, Object>> getDistrictBounds(
            @PathVariable String districtName,
            @QueryValue String state) {

        try {
            String query = """
                SELECT 
                    ST_XMin(ST_Envelope(geom)) as min_lng,
                    ST_YMin(ST_Envelope(geom)) as min_lat,
                    ST_XMax(ST_Envelope(geom)) as max_lng,
                    ST_YMax(ST_Envelope(geom)) as max_lat
                FROM districts
                WHERE name = ? AND state_name = ?
                """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, districtName);
                stmt.setString(2, state);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    Map<String, Object> bbox = new HashMap<>();
                    bbox.put("min_lng", rs.getDouble("min_lng"));
                    bbox.put("min_lat", rs.getDouble("min_lat"));
                    bbox.put("max_lng", rs.getDouble("max_lng"));
                    bbox.put("max_lat", rs.getDouble("max_lat"));

                    return HttpResponse.ok(bbox);
                }

                return HttpResponse.notFound();
            }

        } catch (SQLException e) {
            LOG.error("Error fetching district bounds", e);
            return HttpResponse.serverError(Map.of("error", "Database error"));
        }
    }

    /**
     * Simple JSON parser for geometry
     */
    private Map<String, Object> parseJson(String json) {
        // This is a simplified parser - in production use Jackson or similar
        Map<String, Object> result = new HashMap<>();

        if (json.contains("\"type\":\"MultiPolygon\"")) {
            result.put("type", "MultiPolygon");
        } else if (json.contains("\"type\":\"Polygon\"")) {
            result.put("type", "Polygon");
        }

        // Extract coordinates (simplified - use proper JSON parser in production)
        int coordStart = json.indexOf("\"coordinates\":");
        if (coordStart != -1) {
            int coordEnd = json.lastIndexOf("}");
            String coordString = json.substring(coordStart + 14, coordEnd);
            // Parse coordinates array - simplified
            result.put("coordinates", parseCoordinates(coordString));
        }

        return result;
    }

    private Object parseCoordinates(String coordString) {
        // Simplified coordinate parsing
        // In production, use proper JSON parsing library
        return coordString;
    }
}