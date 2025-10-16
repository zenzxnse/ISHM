package com.ishm.recommendations;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Controller("/api/recommendations")
public class RecommendationController {

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationController.class);

    @Inject
    DataSource dataSource;

    /**
     * Calculate fertilizer recommendations based on soil test values
     */
    @Post("/calculate")
    public HttpResponse<Map<String, Object>> calculateRecommendations(@Body RecommendationRequest request) {
        try {
            // Validate input
            if (request.getCrop() == null || request.getCrop().isEmpty()) {
                return HttpResponse.badRequest(Map.of("error", "Crop selection is required"));
            }

            // Get or estimate NPK values
            double nitrogen = request.getNitrogen() != null ? request.getNitrogen() : estimateNutrient(request, "nitrogen");
            double phosphorus = request.getPhosphorus() != null ? request.getPhosphorus() : estimateNutrient(request, "phosphorus");
            double potassium = request.getPotassium() != null ? request.getPotassium() : estimateNutrient(request, "potassium");

            // Calculate nutrient status
            String nStatus = classifyNutrientLevel(nitrogen, "N");
            String pStatus = classifyNutrientLevel(phosphorus, "P");
            String kStatus = classifyNutrientLevel(potassium, "K");

            // Get crop nutrient requirements
            CropRequirements cropReq = getCropRequirements(request.getCrop());

            // Calculate fertilizer doses
            Map<String, Object> recommendations = new HashMap<>();
            recommendations.put("nitrogenStatus", nStatus);
            recommendations.put("phosphorusStatus", pStatus);
            recommendations.put("potassiumStatus", kStatus);

            // Calculate fertilizer doses based on deficiency
            recommendations.put("ureaDose", calculateUreaDose(nitrogen, cropReq.nitrogenReq, nStatus));
            recommendations.put("dapDose", calculateDAPDose(phosphorus, cropReq.phosphorusReq, pStatus));
            recommendations.put("mopDose", calculateMOPDose(potassium, cropReq.potassiumReq, kStatus));
            recommendations.put("sspDose", calculateSSPDose(phosphorus, cropReq.phosphorusReq, pStatus));

            // Add micronutrient recommendations if needed
            if (request.getPh() != null && request.getPh() < 6.0) {
                recommendations.put("limeRequired", true);
                recommendations.put("limeDose", calculateLimeDose(request.getPh()));
            }

            // Application schedule
            Map<String, String> schedule = new HashMap<>();
            schedule.put("basal", generateBasalDose(recommendations));
            schedule.put("firstTopdress", generateFirstTopdress(request.getCrop()));
            schedule.put("secondTopdress", generateSecondTopdress(request.getCrop()));
            recommendations.put("schedule", schedule);

            // Additional tips
            recommendations.put("tips", generateTips(nStatus, pStatus, kStatus, request.getCrop()));

            return HttpResponse.ok(recommendations);

        } catch (Exception e) {
            LOG.error("Error calculating recommendations", e);
            return HttpResponse.serverError(Map.of("error", "Failed to calculate recommendations"));
        }
    }

    /**
     * Get crop list with requirements
     */
    @Get("/crops")
    public HttpResponse<List<Map<String, Object>>> getCropList() {
        try {
            String query = """
                SELECT 
                    crop_name, crop_type, season,
                    nitrogen_min, nitrogen_max,
                    phosphorus_min, phosphorus_max,
                    potassium_min, potassium_max,
                    ph_min, ph_max,
                    water_requirement
                FROM crop_recommendations
                ORDER BY crop_type, crop_name
                """;

            List<Map<String, Object>> crops = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {

                while (rs.next()) {
                    Map<String, Object> crop = new HashMap<>();
                    crop.put("name", rs.getString("crop_name"));
                    crop.put("type", rs.getString("crop_type"));
                    crop.put("season", rs.getString("season"));
                    crop.put("nRange", rs.getDouble("nitrogen_min") + "-" + rs.getDouble("nitrogen_max"));
                    crop.put("pRange", rs.getDouble("phosphorus_min") + "-" + rs.getDouble("phosphorus_max"));
                    crop.put("kRange", rs.getDouble("potassium_min") + "-" + rs.getDouble("potassium_max"));
                    crop.put("phRange", rs.getDouble("ph_min") + "-" + rs.getDouble("ph_max"));
                    crop.put("waterRequirement", rs.getString("water_requirement"));
                    crops.add(crop);
                }
            }

            return HttpResponse.ok(crops);

        } catch (SQLException e) {
            LOG.error("Error fetching crop list", e);
            return HttpResponse.serverError(Collections.emptyList());
        }
    }

    /**
     * Save recommendation for user
     */
    @Post("/save")
    public HttpResponse<Map<String, Object>> saveRecommendation(@Body Map<String, Object> recommendation) {
        // TODO: Implement saving recommendation to database
        // This would require user authentication
        return HttpResponse.ok(Map.of("message", "Recommendation saved successfully"));
    }

    // Helper methods

    private String classifyNutrientLevel(double value, String nutrient) {
        switch (nutrient) {
            case "N":
                if (value < 280) return "Low";
                else if (value <= 560) return "Medium";
                else return "High";
            case "P":
                if (value < 10) return "Low";
                else if (value <= 25) return "Medium";
                else return "High";
            case "K":
                if (value < 110) return "Low";
                else if (value <= 280) return "Medium";
                else return "High";
            default:
                return "Unknown";
        }
    }

    private double estimateNutrient(RecommendationRequest request, String nutrient) {
        // If district and state are provided, fetch from database
        if (request.getDistrict() != null && request.getState() != null) {
            try {
                String query = """
                    SELECT %s_avg FROM soil_health_data s
                    JOIN districts d ON s.district_id = d.id
                    WHERE d.name = ? AND d.state_name = ?
                    ORDER BY s.measurement_year DESC
                    LIMIT 1
                    """.formatted(nutrient);

                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(query)) {

                    stmt.setString(1, request.getDistrict());
                    stmt.setString(2, request.getState());
                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            } catch (SQLException e) {
                LOG.warn("Could not fetch district data", e);
            }
        }

        // Return default values if no data available
        switch (nutrient) {
            case "nitrogen": return 250;
            case "phosphorus": return 15;
            case "potassium": return 150;
            default: return 0;
        }
    }

    private CropRequirements getCropRequirements(String crop) {
        // Simplified crop requirements - should fetch from database
        Map<String, CropRequirements> requirements = Map.of(
                "wheat", new CropRequirements(120, 60, 60),
                "rice", new CropRequirements(150, 60, 60),
                "maize", new CropRequirements(120, 60, 40),
                "cotton", new CropRequirements(160, 80, 60),
                "sugarcane", new CropRequirements(300, 100, 150),
                "mustard", new CropRequirements(80, 40, 40),
                "tomato", new CropRequirements(150, 80, 100),
                "potato", new CropRequirements(180, 80, 120)
        );

        return requirements.getOrDefault(crop, new CropRequirements(100, 50, 50));
    }

    private double calculateUreaDose(double currentN, double requiredN, String status) {
        // Urea contains 46% N
        double deficit = Math.max(0, requiredN - (currentN * 0.5)); // Assume 50% availability
        return Math.round((deficit / 0.46) * 10) / 10.0;
    }

    private double calculateDAPDose(double currentP, double requiredP, String status) {
        // DAP contains 18% N and 46% P2O5
        double deficit = Math.max(0, requiredP - (currentP * 0.5));
        return Math.round((deficit / 0.46) * 10) / 10.0;
    }

    private double calculateMOPDose(double currentK, double requiredK, String status) {
        // MOP contains 60% K2O
        double deficit = Math.max(0, requiredK - (currentK * 0.5));
        return Math.round((deficit / 0.60) * 10) / 10.0;
    }

    private double calculateSSPDose(double currentP, double requiredP, String status) {
        // SSP contains 16% P2O5
        double deficit = Math.max(0, requiredP - (currentP * 0.5));
        return Math.round((deficit / 0.16) * 10) / 10.0;
    }

    private double calculateLimeDose(double ph) {
        // Simple lime calculation
        if (ph < 5.5) return 2000;
        else if (ph < 6.0) return 1000;
        else return 500;
    }

    private String generateBasalDose(Map<String, Object> recommendations) {
        return String.format("Apply 50%% of N (%s kg Urea), full P (%s kg DAP) and K (%s kg MOP) at sowing",
                recommendations.get("ureaDose"),
                recommendations.get("dapDose"),
                recommendations.get("mopDose"));
    }

    private String generateFirstTopdress(String crop) {
        Map<String, String> schedules = Map.of(
                "wheat", "30-35 days after sowing",
                "rice", "20-25 days after transplanting",
                "maize", "25-30 days after sowing",
                "cotton", "40-45 days after sowing",
                "sugarcane", "45-50 days after planting"
        );
        return schedules.getOrDefault(crop, "30 days after sowing/planting");
    }

    private String generateSecondTopdress(String crop) {
        Map<String, String> schedules = Map.of(
                "wheat", "60-65 days after sowing",
                "rice", "45-50 days after transplanting",
                "maize", "50-55 days after sowing",
                "cotton", "70-75 days after sowing",
                "sugarcane", "90-100 days after planting"
        );
        return schedules.getOrDefault(crop, "60 days after sowing/planting");
    }

    private List<String> generateTips(String nStatus, String pStatus, String kStatus, String crop) {
        List<String> tips = new ArrayList<>();

        tips.add("Apply fertilizers when soil has adequate moisture");
        tips.add("Avoid fertilizer application during heavy rain");

        if ("Low".equals(nStatus)) {
            tips.add("Consider adding organic manure to improve nitrogen content");
        }

        if ("Low".equals(pStatus)) {
            tips.add("Phosphorus deficiency may delay maturity - monitor crop closely");
        }

        if ("Low".equals(kStatus)) {
            tips.add("Potassium deficiency may affect disease resistance");
        }

        tips.add("Conduct soil testing every 2-3 years for best results");

        return tips;
    }

    // Inner classes

    static class RecommendationRequest {
        private String state;
        private String district;
        private String crop;
        private String season;
        private Double nitrogen;
        private Double phosphorus;
        private Double potassium;
        private Double ph;
        private Double organicCarbon;
        private Double ec;

        // Getters and setters
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }

        public String getCrop() { return crop; }
        public void setCrop(String crop) { this.crop = crop; }

        public String getSeason() { return season; }
        public void setSeason(String season) { this.season = season; }

        public Double getNitrogen() { return nitrogen; }
        public void setNitrogen(Double nitrogen) { this.nitrogen = nitrogen; }

        public Double getPhosphorus() { return phosphorus; }
        public void setPhosphorus(Double phosphorus) { this.phosphorus = phosphorus; }

        public Double getPotassium() { return potassium; }
        public void setPotassium(Double potassium) { this.potassium = potassium; }

        public Double getPh() { return ph; }
        public void setPh(Double ph) { this.ph = ph; }

        public Double getOrganicCarbon() { return organicCarbon; }
        public void setOrganicCarbon(Double organicCarbon) { this.organicCarbon = organicCarbon; }

        public Double getEc() { return ec; }
        public void setEc(Double ec) { this.ec = ec; }
    }

    static class CropRequirements {
        double nitrogenReq;
        double phosphorusReq;
        double potassiumReq;

        CropRequirements(double n, double p, double k) {
            this.nitrogenReq = n;
            this.phosphorusReq = p;
            this.potassiumReq = k;
        }
    }
}