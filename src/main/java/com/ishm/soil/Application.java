package com.ishm.soil;

import io.micronaut.runtime.Micronaut;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;

/**
 * Main application class for Interactive Soil Health Map
 */
public class Application {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        LOG.info("Starting Interactive Soil Health Map Application...");

        ApplicationContext context = Micronaut.run(Application.class, args);

        // Initialize database if needed
        DataInitializer initializer = context.getBean(DataInitializer.class);
        initializer.initializeDatabase();

        LOG.info("Application started successfully!");
        LOG.info("Access the application at: http://localhost:8080");
        LOG.info("API Documentation available at: http://localhost:8080/api");
    }
}

/**
 * Database initializer to ensure schema and sample data exist
 */
@Singleton
class DataInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(DataInitializer.class);

    @Inject
    DataSource dataSource;

    @Value("${datasources.default.schema-generate:NONE}")
    String schemaGenerate;

    public void initializeDatabase() {
        if ("NONE".equals(schemaGenerate)) {
            LOG.info("Checking database schema...");
            try {
                ensureSchemaExists();
                loadSampleDataIfEmpty();
                LOG.info("Database initialization complete");
            } catch (SQLException e) {
                LOG.error("Error initializing database", e);
            }
        }
    }

    private void ensureSchemaExists() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Check if PostGIS extension exists
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis");
                LOG.info("PostGIS extension ensured");
            }

            // Check if main tables exist
            DatabaseMetaData metaData = conn.getMetaData();

            // Check for districts table
            ResultSet rs = metaData.getTables(null, null, "districts", null);
            if (!rs.next()) {
                LOG.info("Creating database schema...");
                executeSchemaSql(conn);
            } else {
                LOG.info("Database schema already exists");
            }
        }
    }

    private void executeSchemaSql(Connection conn) throws SQLException {
        // Execute the schema creation SQL
        // In production, this would read from a schema.sql file
        String[] sqlStatements = {
                """
            CREATE TABLE IF NOT EXISTS states (
                id BIGSERIAL PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                code VARCHAR(2) NOT NULL UNIQUE,
                geom geometry(MultiPolygon, 4326),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )""",

                "CREATE INDEX IF NOT EXISTS states_geom_gix ON states USING GIST (geom)",

                """
            CREATE TABLE IF NOT EXISTS districts (
                id BIGSERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                state_id BIGINT REFERENCES states(id) ON DELETE CASCADE,
                state_name TEXT NOT NULL,
                geom geometry(MultiPolygon, 4326) NOT NULL,
                area_km2 DOUBLE PRECISION,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(name, state_name)
            )""",

                "CREATE INDEX IF NOT EXISTS districts_geom_gix ON districts USING GIST (geom)",
                "CREATE INDEX IF NOT EXISTS districts_state_idx ON districts(state_id)",

                """
            CREATE TABLE IF NOT EXISTS soil_health_data (
                id BIGSERIAL PRIMARY KEY,
                district_id BIGINT REFERENCES districts(id) ON DELETE CASCADE,
                district_name TEXT NOT NULL,
                state_name TEXT NOT NULL,
                nitrogen_avg DOUBLE PRECISION,
                nitrogen_status VARCHAR(20),
                phosphorus_avg DOUBLE PRECISION,
                phosphorus_status VARCHAR(20),
                potassium_avg DOUBLE PRECISION,
                potassium_status VARCHAR(20),
                ph_avg DOUBLE PRECISION,
                organic_carbon DOUBLE PRECISION,
                ec_avg DOUBLE PRECISION,
                zinc_avg DOUBLE PRECISION,
                iron_avg DOUBLE PRECISION,
                copper_avg DOUBLE PRECISION,
                manganese_avg DOUBLE PRECISION,
                boron_avg DOUBLE PRECISION,
                sulphur_avg DOUBLE PRECISION,
                samples_analyzed INTEGER,
                measurement_year INTEGER,
                season VARCHAR(20),
                data_source TEXT,
                last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(district_id, measurement_year, season)
            )""",

                """
            CREATE TABLE IF NOT EXISTS crop_recommendations (
                id BIGSERIAL PRIMARY KEY,
                crop_name TEXT NOT NULL,
                crop_type VARCHAR(50),
                nitrogen_min DOUBLE PRECISION,
                nitrogen_max DOUBLE PRECISION,
                phosphorus_min DOUBLE PRECISION,
                phosphorus_max DOUBLE PRECISION,
                potassium_min DOUBLE PRECISION,
                potassium_max DOUBLE PRECISION,
                ph_min DOUBLE PRECISION,
                ph_max DOUBLE PRECISION,
                organic_carbon_min DOUBLE PRECISION,
                season VARCHAR(20),
                water_requirement VARCHAR(20),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )"""
        };

        for (String sql : sqlStatements) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }

        LOG.info("Database schema created successfully");
    }

    private void loadSampleDataIfEmpty() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Check if we have any data
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM districts")) {

                rs.next();
                if (rs.getInt(1) == 0) {
                    LOG.info("Loading sample data...");
                    insertSampleData(conn);
                } else {
                    LOG.info("Data already exists in database");
                }
            }
        }
    }

    private void insertSampleData(Connection conn) throws SQLException {
        // Insert sample states
        String insertStates = """
            INSERT INTO states (name, code) VALUES
            ('Delhi', 'DL'),
            ('Haryana', 'HR'),
            ('Punjab', 'PB'),
            ('Uttar Pradesh', 'UP'),
            ('Maharashtra', 'MH'),
            ('Karnataka', 'KA'),
            ('Tamil Nadu', 'TN'),
            ('Gujarat', 'GJ'),
            ('Rajasthan', 'RJ'),
            ('West Bengal', 'WB')
            ON CONFLICT (name) DO NOTHING
            """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(insertStates);
        }

        // Insert sample district with geometry
        String insertDistrict = """
            INSERT INTO districts (name, state_name, geom) VALUES
            ('Delhi', 'Delhi', 
             ST_Multi(ST_GeomFromText('POLYGON((77.05 28.38, 77.40 28.38, 77.40 28.88, 77.05 28.88, 77.05 28.38))', 4326))),
            ('Gurugram', 'Haryana',
             ST_Multi(ST_GeomFromText('POLYGON((77.00 28.35, 77.15 28.35, 77.15 28.55, 77.00 28.55, 77.00 28.35))', 4326))),
            ('Ludhiana', 'Punjab',
             ST_Multi(ST_GeomFromText('POLYGON((75.70 30.80, 75.95 30.80, 75.95 31.00, 75.70 31.00, 75.70 30.80))', 4326)))
            ON CONFLICT (name, state_name) DO NOTHING
            """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(insertDistrict);
        }

        // Insert sample soil health data
        String insertSoilData = """
            INSERT INTO soil_health_data 
            (district_id, district_name, state_name, nitrogen_avg, phosphorus_avg, potassium_avg, 
             nitrogen_status, phosphorus_status, potassium_status,
             ph_avg, organic_carbon, samples_analyzed, measurement_year, season)
            SELECT 
                d.id, d.name, d.state_name,
                CASE d.name 
                    WHEN 'Delhi' THEN 110
                    WHEN 'Gurugram' THEN 320
                    WHEN 'Ludhiana' THEN 580
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 18
                    WHEN 'Gurugram' THEN 28
                    WHEN 'Ludhiana' THEN 22
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 140
                    WHEN 'Gurugram' THEN 290
                    WHEN 'Ludhiana' THEN 310
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 'Low'
                    WHEN 'Gurugram' THEN 'Medium'
                    WHEN 'Ludhiana' THEN 'High'
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 'Medium'
                    WHEN 'Gurugram' THEN 'High'
                    WHEN 'Ludhiana' THEN 'Medium'
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 'Medium'
                    WHEN 'Gurugram' THEN 'High'
                    WHEN 'Ludhiana' THEN 'High'
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 7.6
                    WHEN 'Gurugram' THEN 7.8
                    WHEN 'Ludhiana' THEN 7.4
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 0.55
                    WHEN 'Gurugram' THEN 0.62
                    WHEN 'Ludhiana' THEN 0.71
                END,
                CASE d.name 
                    WHEN 'Delhi' THEN 15234
                    WHEN 'Gurugram' THEN 12876
                    WHEN 'Ludhiana' THEN 18945
                END,
                2025, 'Rabi'
            FROM districts d
            ON CONFLICT (district_id, measurement_year, season) DO NOTHING
            """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(insertSoilData);
        }

        // Insert crop recommendations
        String insertCrops = """
            INSERT INTO crop_recommendations 
            (crop_name, crop_type, nitrogen_min, nitrogen_max, phosphorus_min, phosphorus_max, 
             potassium_min, potassium_max, ph_min, ph_max, organic_carbon_min, season, water_requirement)
            VALUES
            ('Wheat', 'Cereal', 280, 560, 10, 25, 110, 280, 6.0, 7.5, 0.5, 'Rabi', 'Medium'),
            ('Rice', 'Cereal', 280, 560, 10, 25, 110, 280, 5.5, 7.0, 0.5, 'Kharif', 'High'),
            ('Maize', 'Cereal', 280, 560, 10, 25, 110, 280, 5.5, 7.8, 0.5, 'Kharif', 'Medium'),
            ('Cotton', 'Cash Crop', 280, 560, 15, 30, 150, 300, 7.0, 8.0, 0.5, 'Kharif', 'Medium'),
            ('Sugarcane', 'Cash Crop', 350, 600, 20, 35, 200, 350, 6.0, 7.5, 0.75, 'Annual', 'High'),
            ('Mustard', 'Oilseed', 200, 400, 10, 20, 100, 200, 6.0, 7.5, 0.4, 'Rabi', 'Low'),
            ('Tomato', 'Vegetable', 250, 450, 15, 30, 150, 280, 6.0, 7.0, 0.5, 'All', 'Medium'),
            ('Potato', 'Vegetable', 280, 500, 20, 35, 200, 350, 5.5, 6.5, 0.5, 'Rabi', 'Medium'),
            ('Groundnut', 'Oilseed', 200, 350, 15, 25, 150, 250, 6.0, 7.0, 0.5, 'Kharif', 'Medium'),
            ('Onion', 'Vegetable', 200, 400, 15, 30, 150, 280, 6.0, 7.5, 0.5, 'All', 'Medium')
            ON CONFLICT DO NOTHING
            """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(insertCrops);
        }

        LOG.info("Sample data loaded successfully");
    }
}