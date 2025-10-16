package com.ishm.dash;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Controller("/api/dashboard")
public class DashboardController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardController.class);

    @Inject
    DataSource dataSource;

    /**
     * Get dashboard summary with key metrics
     */
    @Get("/summary")
    public HttpResponse<Map<String, Object>> getDashboardSummary(
            @QueryValue Optional<String> state,
            @QueryValue Optional<Integer> year) {

        try {
            Map<String, Object> summary = new HashMap<>();

            // Get current year if not specified
            int targetYear = year.orElse(LocalDate.now().getYear());

            // Key metrics
            summary.put("metrics", getKeyMetrics(state, targetYear));

            // NPK trends
            summary.put("npkTrends", getNPKTrends(state, targetYear));

            // State-wise distribution
            summary.put("stateDistribution", getStateDistribution(targetYear));

            // Recent activities
            summary.put("recentActivities", getRecentActivities());

            // District summary table
            summary.put("districtSummary", getDistrictSummary(state, targetYear));

            return HttpResponse.ok(summary);

        } catch (SQLException e) {
            LOG.error("Error fetching dashboard summary", e);
            return HttpResponse.serverError(Map.of("error", "Database error"));
        }
    }

    /**
     * Get key performance metrics
     */
    private Map<String, Object> getKeyMetrics(Optional<String> state, int year) throws SQLException {
        Map<String, Object> metrics = new HashMap<>();

        String baseQuery = """
            SELECT 
                COUNT(DISTINCT d.id) as districts_covered,
                SUM(s.samples_analyzed) as total_samples,
                AVG((CASE 
                    WHEN s.nitrogen_status = 'Medium' THEN 5
                    WHEN s.nitrogen_status = 'High' THEN 8
                    ELSE 3 END +
                    CASE 
                    WHEN s.phosphorus_status = 'Medium' THEN 5
                    WHEN s.phosphorus_status = 'High' THEN 8
                    ELSE 3 END +
                    CASE 
                    WHEN s.potassium_status = 'Medium' THEN 5
                    WHEN s.potassium_status = 'High' THEN 8
                    ELSE 3 END) / 3.0) as avg_soil_health
            FROM districts d
            LEFT JOIN soil_health_data s ON d.id = s.district_id
            WHERE s.measurement_year = ?
            """;

        List<Object> params = new ArrayList<>();
        params.add(year);

        if (state.isPresent()) {
            baseQuery += " AND d.state_name = ?";
            params.add(state.get());
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(baseQuery)) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                metrics.put("districtsCovered", rs.getInt("districts_covered"));
                metrics.put("totalSamples", rs.getLong("total_samples"));
                metrics.put("avgSoilHealth", Math.round(rs.getDouble("avg_soil_health") * 10) / 10.0);
            }
        }

        // Calculate YoY growth
        metrics.put("districtsGrowth", calculateGrowth("districts", year - 1, year, state));
        metrics.put("samplesGrowth", calculateGrowth("samples", year - 1, year, state));

        // Estimate farmers benefited (rough calculation: 1 sample = 2-3 farmers)
        Long totalSamples = (Long) metrics.get("totalSamples");
        metrics.put("farmersBenefited", totalSamples != null ? totalSamples * 2.5 : 0);

        return metrics;
    }

    /**
     * Get NPK trends over months
     */
    private Map<String, Object> getNPKTrends(Optional<String> state, int year) throws SQLException {
        Map<String, Object> trends = new HashMap<>();

        // For demo purposes, generate sample trend data
        // In production, this would fetch real monthly data
        List<String> months = Arrays.asList("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");

        // Generate sample data with some variation
        List<Double> nitrogenData = new ArrayList<>();
        List<Double> phosphorusData = new ArrayList<>();
        List<Double> potassiumData = new ArrayList<>();

        double nBase = 280, pBase = 18, kBase = 180;
        Random rand = new Random();

        for (int i = 0; i < 12; i++) {
            nitrogenData.add(nBase + (rand.nextDouble() * 40 - 20));
            phosphorusData.add(pBase + (rand.nextDouble() * 4 - 2));
            potassiumData.add(kBase + (rand.nextDouble() * 30 - 15));
        }

        trends.put("labels", months);
        trends.put("nitrogen", nitrogenData);
        trends.put("phosphorus", phosphorusData);
        trends.put("potassium", potassiumData);

        return trends;
    }

    /**
     * Get state-wise sample distribution
     */
    private List<Map<String, Object>> getStateDistribution(int year) throws SQLException {
        List<Map<String, Object>> distribution = new ArrayList<>();

        String query = """
            SELECT 
                d.state_name,
                COUNT(DISTINCT d.id) as district_count,
                SUM(s.samples_analyzed) as total_samples,
                AVG(s.nitrogen_avg) as avg_nitrogen,
                AVG(s.phosphorus_avg) as avg_phosphorus,
                AVG(s.potassium_avg) as avg_potassium
            FROM districts d
            LEFT JOIN soil_health_data s ON d.id = s.district_id
            WHERE s.measurement_year = ?
            GROUP BY d.state_name
            ORDER BY total_samples DESC
            LIMIT 10
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setInt(1, year);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                Map<String, Object> stateData = new HashMap<>();
                stateData.put("state", rs.getString("state_name"));
                stateData.put("districts", rs.getInt("district_count"));
                stateData.put("samples", rs.getLong("total_samples"));
                stateData.put("avgNitrogen", rs.getDouble("avg_nitrogen"));
                stateData.put("avgPhosphorus", rs.getDouble("avg_phosphorus"));
                stateData.put("avgPotassium", rs.getDouble("avg_potassium"));
                distribution.add(stateData);
            }
        }

        // Add demo data if no real data
        if (distribution.isEmpty()) {
            distribution.add(Map.of("state", "Delhi", "districts", 11, "samples", 15234));
            distribution.add(Map.of("state", "Haryana", "districts", 22, "samples", 45678));
            distribution.add(Map.of("state", "Punjab", "districts", 23, "samples", 67890));
            distribution.add(Map.of("state", "UP", "districts", 75, "samples", 123456));
            distribution.add(Map.of("state", "Maharashtra", "districts", 36, "samples", 89012));
        }

        return distribution;
    }

    /**
     * Get district-wise summary for table
     */
    private List<Map<String, Object>> getDistrictSummary(Optional<String> state, int year) throws SQLException {
        List<Map<String, Object>> districts = new ArrayList<>();

        String query = """
            SELECT 
                d.name as district_name,
                d.state_name,
                s.samples_analyzed,
                s.nitrogen_status,
                s.phosphorus_status,
                s.potassium_status,
                s.ph_avg,
                s.organic_carbon,
                s.last_updated
            FROM districts d
            LEFT JOIN soil_health_data s ON d.id = s.district_id
            WHERE s.measurement_year = ?
            """;

        List<Object> params = new ArrayList<>();
        params.add(year);

        if (state.isPresent()) {
            query += " AND d.state_name = ?";
            params.add(state.get());
        }

        query += " ORDER BY s.samples_analyzed DESC LIMIT 20";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> district = new HashMap<>();
                district.put("districtName", rs.getString("district_name"));
                district.put("stateName", rs.getString("state_name"));
                district.put("samples", rs.getInt("samples_analyzed"));
                district.put("nitrogenStatus", rs.getString("nitrogen_status"));
                district.put("phosphorusStatus", rs.getString("phosphorus_status"));
                district.put("potassiumStatus", rs.getString("potassium_status"));
                district.put("ph", rs.getDouble("ph_avg"));
                district.put("organicCarbon", rs.getDouble("organic_carbon"));
                district.put("lastUpdated", rs.getTimestamp("last_updated"));
                districts.add(district);
            }
        }

        // Add demo data if empty
        if (districts.isEmpty()) {
            districts.add(createDemoDistrict("Delhi", "Delhi", 15234, "Low", "Medium", "Medium", 7.6, 0.55));
            districts.add(createDemoDistrict("Gurugram", "Haryana", 12876, "Medium", "High", "High", 7.8, 0.62));
            districts.add(createDemoDistrict("Ludhiana", "Punjab", 18945, "High", "Medium", "High", 7.4, 0.71));
        }

        return districts;
    }

    /**
     * Get recent activities
     */
    private List<Map<String, Object>> getRecentActivities() {
        List<Map<String, Object>> activities = new ArrayList<>();

        // Demo activities - in production, fetch from activity log table
        activities.add(Map.of(
                "type", "report",
                "title", "New Report Generated",
                "description", "Haryana State Soil Health Report 2025",
                "timestamp", "2 hours ago",
                "icon", "📊"
        ));

        activities.add(Map.of(
                "type", "map_update",
                "title", "Map Updated",
                "description", "Added 15 new districts in Maharashtra",
                "timestamp", "5 hours ago",
                "icon", "🗺️"
        ));

        activities.add(Map.of(
                "type", "milestone",
                "title", "Milestone Reached",
                "description", "4 Million samples analyzed this year",
                "timestamp", "1 day ago",
                "icon", "📈"
        ));

        activities.add(Map.of(
                "type", "advisory",
                "title", "Crop Advisory",
                "description", "Rabi season recommendations published",
                "timestamp", "2 days ago",
                "icon", "🌾"
        ));

        return activities;
    }

    /**
     * Export district data as CSV
     */
    @Get("/export/csv")
    @Produces("text/csv")
    public HttpResponse<String> exportCSV(
            @QueryValue Optional<String> state,
            @QueryValue Optional<Integer> year) {

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("District,State,Samples,N Status,P Status,K Status,pH,OC%,Last Updated\n");

            List<Map<String, Object>> districts = getDistrictSummary(state, year.orElse(LocalDate.now().getYear()));

            for (Map<String, Object> district : districts) {
                csv.append(String.format("%s,%s,%d,%s,%s,%s,%.1f,%.2f,%s\n",
                        district.get("districtName"),
                        district.get("stateName"),
                        district.get("samples"),
                        district.get("nitrogenStatus"),
                        district.get("phosphorusStatus"),
                        district.get("potassiumStatus"),
                        district.get("ph"),
                        district.get("organicCarbon"),
                        district.get("lastUpdated")
                ));
            }

            return HttpResponse.ok(csv.toString())
                    .header("Content-Disposition", "attachment; filename=soil_health_data.csv");

        } catch (SQLException e) {
            LOG.error("Error exporting CSV", e);
            return HttpResponse.serverError("Error generating CSV");
        }
    }

    // Helper methods

    private double calculateGrowth(String metric, int previousYear, int currentYear, Optional<String> state) {
        // Simplified growth calculation - in production, query actual data
        Random rand = new Random();
        return Math.round((rand.nextDouble() * 30 - 5) * 10) / 10.0; // -5% to +25% growth
    }

    private Map<String, Object> createDemoDistrict(String name, String state, int samples,
                                                   String nStatus, String pStatus, String kStatus, double ph, double oc) {
        Map<String, Object> district = new HashMap<>();
        district.put("districtName", name);
        district.put("stateName", state);
        district.put("samples", samples);
        district.put("nitrogenStatus", nStatus);
        district.put("phosphorusStatus", pStatus);
        district.put("potassiumStatus", kStatus);
        district.put("ph", ph);
        district.put("organicCarbon", oc);
        district.put("lastUpdated", new Timestamp(System.currentTimeMillis()));
        return district;
    }
}