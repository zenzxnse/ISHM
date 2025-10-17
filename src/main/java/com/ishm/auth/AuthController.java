package com.ishm.auth;

import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import io.micronaut.security.token.generator.TokenGenerator;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

@Controller("/api/auth")
public class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    @Inject
    DataSource dataSource;

    @Inject
    TokenGenerator tokenGenerator;

    /**
     * Register new farmer with postal code verification
     */
    @Post("/register")
    public HttpResponse<Map<String, Object>> register(@Body RegistrationRequest request) {
        try {
            // Validate inputs
            if (request.getUsername() == null || request.getUsername().length() < 3) {
                return HttpResponse.badRequest(Map.of("error", "Username must be at least 3 characters"));
            }
            if (request.getPassword() == null || request.getPassword().length() < 6) {
                return HttpResponse.badRequest(Map.of("error", "Password must be at least 6 characters"));
            }
            if (request.getPostalCode() == null || request.getPostalCode().length() != 6) {
                return HttpResponse.badRequest(Map.of("error", "Valid 6-digit postal code required"));
            }

            // Check if username exists
            try (Connection conn = dataSource.getConnection()) {
                String checkQuery = "SELECT id FROM farmers WHERE username = ?";
                try (PreparedStatement stmt = conn.prepareStatement(checkQuery)) {
                    stmt.setString(1, request.getUsername());
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return HttpResponse.badRequest(Map.of("error", "Username already exists"));
                    }
                }

                // Verify postal code maps to a valid district
                String districtQuery = """
                    SELECT d.id, d.name, d.state_name 
                    FROM districts d
                    WHERE d.name ILIKE ? OR d.state_name ILIKE ?
                    LIMIT 1
                """;

                Long districtId = null;
                String districtName = null;
                String stateName = null;

                // For demo: map postal codes to districts (in production, use proper postal code DB)
                Map<String, String[]> postalCodeMap = Map.of(
                        "110001", new String[]{"Delhi", "Delhi"},
                        "122001", new String[]{"Gurugram", "Haryana"},
                        "141001", new String[]{"Ludhiana", "Punjab"}
                );

                String[] location = postalCodeMap.getOrDefault(request.getPostalCode(), new String[]{"Delhi", "Delhi"});

                try (PreparedStatement stmt = conn.prepareStatement(districtQuery)) {
                    stmt.setString(1, "%" + location[0] + "%");
                    stmt.setString(2, "%" + location[1] + "%");
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        districtId = rs.getLong("id");
                        districtName = rs.getString("name");
                        stateName = rs.getString("state_name");
                    }
                }

                // Hash password
                String hashedPassword = hashPassword(request.getPassword());

                // Insert farmer
                String insertQuery = """
                    INSERT INTO farmers (username, password_hash, postal_code, district_id, 
                                       district_name, state_name, full_name, phone)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, username, postal_code, district_name, state_name
                """;

                try (PreparedStatement stmt = conn.prepareStatement(insertQuery)) {
                    stmt.setString(1, request.getUsername());
                    stmt.setString(2, hashedPassword);
                    stmt.setString(3, request.getPostalCode());
                    stmt.setObject(4, districtId);
                    stmt.setString(5, districtName);
                    stmt.setString(6, stateName);
                    stmt.setString(7, request.getFullName());
                    stmt.setString(8, request.getPhone());

                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("message", "Registration successful");
                        response.put("farmer", Map.of(
                                "id", rs.getLong("id"),
                                "username", rs.getString("username"),
                                "postalCode", rs.getString("postal_code"),
                                "district", rs.getString("district_name"),
                                "state", rs.getString("state_name")
                        ));
                        return HttpResponse.ok(response);
                    }
                }
            }

            return HttpResponse.serverError(Map.of("error", "Registration failed"));

        } catch (SQLException e) {
            LOG.error("Registration error", e);
            return HttpResponse.serverError(Map.of("error", "Database error during registration"));
        }
    }

    /**
     * Login with username and password
     */
    @Post("/login")
    public HttpResponse<Map<String, Object>> login(@Body LoginRequest request) {
        try {
            String query = """
                SELECT id, username, password_hash, postal_code, district_id, 
                       district_name, state_name, full_name
                FROM farmers 
                WHERE username = ?
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, request.getUsername());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");

                    // Verify password
                    if (verifyPassword(request.getPassword(), storedHash)) {
                        Long farmerId = rs.getLong("id");
                        String username = rs.getString("username");

                        // Generate JWT token
                        Map<String, Object> claims = new HashMap<>();
                        claims.put("sub", username);
                        claims.put("farmerId", farmerId);
                        claims.put("district", rs.getString("district_name"));
                        claims.put("state", rs.getString("state_name"));

                        Optional<String> token = tokenGenerator.generateToken(claims);

                        if (token.isPresent()) {
                            // Update last login
                            updateLastLogin(conn, farmerId);

                            Map<String, Object> response = new HashMap<>();
                            response.put("success", true);
                            response.put("token", token.get());
                            response.put("farmer", Map.of(
                                    "id", farmerId,
                                    "username", username,
                                    "postalCode", rs.getString("postal_code"),
                                    "district", rs.getString("district_name"),
                                    "state", rs.getString("state_name"),
                                    "fullName", rs.getString("full_name") != null ? rs.getString("full_name") : username
                            ));

                            return HttpResponse.ok(response);
                        }
                    }
                }
            }

            return HttpResponse.unauthorized().body(Map.of("error", "Invalid username or password"));

        } catch (SQLException e) {
            LOG.error("Login error", e);
            return HttpResponse.serverError(Map.of("error", "Login failed"));
        }
    }

    /**
     * Verify token and get farmer details
     */
    @Get("/verify")
    public HttpResponse<Map<String, Object>> verifyToken(@Header("Authorization") String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return HttpResponse.unauthorized().body(Map.of("error", "No token provided"));
            }

            // Token is validated by Micronaut Security automatically
            return HttpResponse.ok(Map.of("valid", true));

        } catch (Exception e) {
            LOG.error("Token verification error", e);
            return HttpResponse.unauthorized().body(Map.of("error", "Invalid token"));
        }
    }

    /**
     * Hash password using SHA-256
     * NOTE: For production, use BCrypt instead
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    /**
     * Verify password against hash
     */
    private boolean verifyPassword(String password, String hashedPassword) {
        String inputHash = hashPassword(password);
        return inputHash.equals(hashedPassword);
    }

    private void updateLastLogin(Connection conn, Long farmerId) throws SQLException {
        String updateQuery = "UPDATE farmers SET last_login = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(updateQuery)) {
            stmt.setLong(1, farmerId);
            stmt.executeUpdate();
        }
    }

    // Request classes
    public static class RegistrationRequest {
        private String username;
        private String password;
        private String postalCode;
        private String fullName;
        private String phone;

        // Getters and setters
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getPostalCode() { return postalCode; }
        public void setPostalCode(String postalCode) { this.postalCode = postalCode; }

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
    }

    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}