package com.ishm.map;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Controller("/api/map")
public class MapController {

    private static final Logger LOG = LoggerFactory.getLogger(MapController.class);

    @Inject
    DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get all districts with soil health data as GeoJSON
     */
    @Get("/districts")
    public HttpResponse<Map<String, Object>> getDistricts(
            @QueryValue Optional<String> state) {

        try {
            // Get data from database
            Map<String, Object> geoJson = getDistrictsFromDatabase(state);
            return HttpResponse.ok(geoJson);

        } catch (Exception e) {
            LOG.error("Error fetching districts", e);
            return HttpResponse.serverError(Map.of("error", "Failed to fetch district data"));
        }
    }

    /**
     * Get districts from local database
     */
    private Map<String, Object> getDistrictsFromDatabase(Optional<String> state) throws SQLException {
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
            WHERE s.measurement_year = (
                SELECT MAX(measurement_year) 
                FROM soil_health_data s2 
                WHERE s2.district_id = d.id
            )
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

                // Parse geometry
                String geomJson = rs.getString("geometry");
                if (geomJson != null) {
                    try {
                        JsonNode geomNode = objectMapper.readTree(geomJson);
                        Map<String, Object> geomMap = objectMapper.convertValue(geomNode, Map.class);
                        feature.put("geometry", geomMap);
                    } catch (Exception e) {
                        LOG.warn("Failed to parse geometry for district", e);
                    }
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

        Map<String, Object> geoJson = new HashMap<>();
        geoJson.put("type", "FeatureCollection");
        geoJson.put("features", features);

        return geoJson;
    }

    /**
     * Get state-level statistics
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
                    SUM(s.samples_analyzed) as total_samples,
                    COUNT(CASE WHEN s.nitrogen_status = 'Low' THEN 1 END) as n_low_count,
                    COUNT(CASE WHEN s.nitrogen_status = 'Medium' THEN 1 END) as n_medium_count,
                    COUNT(CASE WHEN s.nitrogen_status = 'High' THEN 1 END) as n_high_count,
                    COUNT(CASE WHEN s.phosphorus_status = 'Low' THEN 1 END) as p_low_count,
                    COUNT(CASE WHEN s.phosphorus_status = 'Medium' THEN 1 END) as p_medium_count,
                    COUNT(CASE WHEN s.phosphorus_status = 'High' THEN 1 END) as p_high_count,
                    COUNT(CASE WHEN s.potassium_status = 'Low' THEN 1 END) as k_low_count,
                    COUNT(CASE WHEN s.potassium_status = 'Medium' THEN 1 END) as k_medium_count,
                    COUNT(CASE WHEN s.potassium_status = 'High' THEN 1 END) as k_high_count
                FROM districts d
                LEFT JOIN soil_health_data s ON d.id = s.district_id
                WHERE d.state_name = ?
                AND s.measurement_year = (
                    SELECT MAX(measurement_year)
                    FROM soil_health_data s2
                    WHERE s2.district_id = d.id
                )
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, state);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("state", state);
                    stats.put("district_count", rs.getInt("district_count"));
                    stats.put("avg_nitrogen", Math.round(rs.getDouble("avg_nitrogen") * 10) / 10.0);
                    stats.put("avg_phosphorus", Math.round(rs.getDouble("avg_phosphorus") * 10) / 10.0);
                    stats.put("avg_potassium", Math.round(rs.getDouble("avg_potassium") * 10) / 10.0);
                    stats.put("avg_ph", Math.round(rs.getDouble("avg_ph") * 10) / 10.0);
                    stats.put("avg_organic_carbon", Math.round(rs.getDouble("avg_oc") * 100) / 100.0);
                    stats.put("total_samples", rs.getLong("total_samples"));

                    // NPK distribution
                    Map<String, Object> npkDistribution = new HashMap<>();
                    npkDistribution.put("nitrogen", Map.of(
                            "low", rs.getInt("n_low_count"),
                            "medium", rs.getInt("n_medium_count"),
                            "high", rs.getInt("n_high_count")
                    ));
                    npkDistribution.put("phosphorus", Map.of(
                            "low", rs.getInt("p_low_count"),
                            "medium", rs.getInt("p_medium_count"),
                            "high", rs.getInt("p_high_count")
                    ));
                    npkDistribution.put("potassium", Map.of(
                            "low", rs.getInt("k_low_count"),
                            "medium", rs.getInt("k_medium_count"),
                            "high", rs.getInt("k_high_count")
                    ));
                    stats.put("npk_distribution", npkDistribution);

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
     * Get district bounds for map centering
     */
    @Get("/district/{districtName}/bounds")
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
                    Map<String, Object> bounds = new HashMap<>();
                    bounds.put("min_lng", rs.getDouble("min_lng"));
                    bounds.put("min_lat", rs.getDouble("min_lat"));
                    bounds.put("max_lng", rs.getDouble("max_lng"));
                    bounds.put("max_lat", rs.getDouble("max_lat"));

                    // Calculate center
                    bounds.put("center_lng", (rs.getDouble("min_lng") + rs.getDouble("max_lng")) / 2);
                    bounds.put("center_lat", (rs.getDouble("min_lat") + rs.getDouble("max_lat")) / 2);

                    return HttpResponse.ok(bounds);
                }

                return HttpResponse.notFound();
            }

        } catch (SQLException e) {
            LOG.error("Error fetching district bounds", e);
            return HttpResponse.serverError(Map.of("error", "Database error"));
        }
    }

    /**
     * Get all states list
     */
    @Get("/states")
    public HttpResponse<List<Map<String, Object>>> getStates() {
        try {
            String query = """
                SELECT DISTINCT state_name, COUNT(d.id) as district_count
                FROM districts d
                GROUP BY state_name
                ORDER BY state_name
            """;

            List<Map<String, Object>> states = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {

                while (rs.next()) {
                    Map<String, Object> state = new HashMap<>();
                    state.put("name", rs.getString("state_name"));
                    state.put("district_count", rs.getInt("district_count"));
                    states.add(state);
                }
            }

            return HttpResponse.ok(states);

        } catch (SQLException e) {
            LOG.error("Error fetching states", e);
            return HttpResponse.serverError(Collections.emptyList());
        }
    }
}