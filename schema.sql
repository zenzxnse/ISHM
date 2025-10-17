-- Complete database schema for Interactive Soil Health Monitoring System
-- Drop existing tables if reinstantiating
DROP TABLE IF EXISTS fertilizer_recommendations CASCADE;
DROP TABLE IF EXISTS crop_recommendations CASCADE;
DROP TABLE IF EXISTS soil_health_data CASCADE;
DROP TABLE IF EXISTS farmers CASCADE;
DROP TABLE IF EXISTS districts CASCADE;
DROP TABLE IF EXISTS states CASCADE;
DROP FUNCTION IF EXISTS classify_nutrient_level CASCADE;
DROP FUNCTION IF EXISTS update_nutrient_status CASCADE;
DROP VIEW IF EXISTS district_soil_summary CASCADE;

-- Enable PostGIS extension
CREATE EXTENSION IF NOT EXISTS postgis;

-- ========================================
-- AUTHENTICATION & USER TABLES
-- ========================================

-- Farmers/Users table for authentication
CREATE TABLE farmers (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    postal_code VARCHAR(6) NOT NULL,
    district_id BIGINT,
    district_name TEXT,
    state_name TEXT,
    full_name TEXT,
    phone VARCHAR(15),
    email VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    CONSTRAINT chk_postal_code CHECK (postal_code ~ '^\d{6}$')
);

CREATE INDEX idx_farmers_username ON farmers(username);
CREATE INDEX idx_farmers_postal_code ON farmers(postal_code);
CREATE INDEX idx_farmers_district ON farmers(district_name, state_name);

-- ========================================
-- GEOGRAPHIC TABLES
-- ========================================

-- States table
CREATE TABLE states (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    code VARCHAR(2) NOT NULL UNIQUE,
    geom geometry(MultiPolygon, 4326),
    population BIGINT,
    area_km2 DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_states_geom ON states USING GIST (geom);
CREATE INDEX idx_states_code ON states(code);

-- Districts table
CREATE TABLE districts (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    state_id BIGINT REFERENCES states(id) ON DELETE CASCADE,
    state_name TEXT NOT NULL,
    geom geometry(MultiPolygon, 4326) NOT NULL,
    area_km2 DOUBLE PRECISION,
    population INTEGER,
    agricultural_area_km2 DOUBLE PRECISION,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_district_state UNIQUE(name, state_name)
);

CREATE INDEX idx_districts_geom ON districts USING GIST (geom);
CREATE INDEX idx_districts_state ON districts(state_id);
CREATE INDEX idx_districts_name ON districts(name);
CREATE INDEX idx_districts_state_name ON districts(state_name);

-- ========================================
-- SOIL HEALTH DATA
-- ========================================

-- Soil health data (district-level aggregates)
CREATE TABLE soil_health_data (
    id BIGSERIAL PRIMARY KEY,
    district_id BIGINT REFERENCES districts(id) ON DELETE CASCADE,
    district_name TEXT NOT NULL,
    state_name TEXT NOT NULL,

    -- NPK levels (kg/ha)
    nitrogen_avg DOUBLE PRECISION,
    nitrogen_status VARCHAR(20) CHECK (nitrogen_status IN ('Low', 'Medium', 'High')),
    phosphorus_avg DOUBLE PRECISION,
    phosphorus_status VARCHAR(20) CHECK (phosphorus_status IN ('Low', 'Medium', 'High')),
    potassium_avg DOUBLE PRECISION,
    potassium_status VARCHAR(20) CHECK (potassium_status IN ('Low', 'Medium', 'High')),

    -- Other soil parameters
    ph_avg DOUBLE PRECISION CHECK (ph_avg >= 0 AND ph_avg <= 14),
    organic_carbon DOUBLE PRECISION CHECK (organic_carbon >= 0),
    ec_avg DOUBLE PRECISION, -- Electrical conductivity (dS/m)

    -- Micronutrients (ppm)
    zinc_avg DOUBLE PRECISION,
    iron_avg DOUBLE PRECISION,
    copper_avg DOUBLE PRECISION,
    manganese_avg DOUBLE PRECISION,
    boron_avg DOUBLE PRECISION,
    sulphur_avg DOUBLE PRECISION,

    -- Metadata
    samples_analyzed INTEGER CHECK (samples_analyzed >= 0),
    measurement_year INTEGER CHECK (measurement_year >= 2000 AND measurement_year <= 2100),
    season VARCHAR(20) CHECK (season IN ('Kharif', 'Rabi', 'Zaid', 'Annual', 'All')),
    data_source TEXT,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_district_year_season UNIQUE(district_id, measurement_year, season)
);

CREATE INDEX idx_soil_health_district ON soil_health_data(district_id);
CREATE INDEX idx_soil_health_year ON soil_health_data(measurement_year);
CREATE INDEX idx_soil_health_npk ON soil_health_data(nitrogen_status, phosphorus_status, potassium_status);
CREATE INDEX idx_soil_health_district_name ON soil_health_data(district_name, state_name);

-- ========================================
-- CROP & RECOMMENDATIONS
-- ========================================

-- Crop recommendations based on soil conditions
CREATE TABLE crop_recommendations (
    id BIGSERIAL PRIMARY KEY,
    crop_name TEXT NOT NULL,
    crop_type VARCHAR(50) CHECK (crop_type IN ('Cereal', 'Pulse', 'Oilseed', 'Vegetable', 'Fruit', 'Cash Crop')),

    -- Optimal NPK ranges (kg/ha)
    nitrogen_min DOUBLE PRECISION CHECK (nitrogen_min >= 0),
    nitrogen_max DOUBLE PRECISION CHECK (nitrogen_max >= nitrogen_min),
    phosphorus_min DOUBLE PRECISION CHECK (phosphorus_min >= 0),
    phosphorus_max DOUBLE PRECISION CHECK (phosphorus_max >= phosphorus_min),
    potassium_min DOUBLE PRECISION CHECK (potassium_min >= 0),
    potassium_max DOUBLE PRECISION CHECK (potassium_max >= potassium_min),

    -- Other requirements
    ph_min DOUBLE PRECISION CHECK (ph_min >= 0 AND ph_min <= 14),
    ph_max DOUBLE PRECISION CHECK (ph_max >= ph_min AND ph_max <= 14),
    organic_carbon_min DOUBLE PRECISION CHECK (organic_carbon_min >= 0),

    season VARCHAR(20) CHECK (season IN ('Kharif', 'Rabi', 'Zaid', 'Annual', 'All')),
    water_requirement VARCHAR(20) CHECK (water_requirement IN ('Low', 'Medium', 'High')),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_crop_recommendations_type ON crop_recommendations(crop_type);
CREATE INDEX idx_crop_recommendations_season ON crop_recommendations(season);

-- Fertilizer recommendations (saved recommendations)
CREATE TABLE fertilizer_recommendations (
    id BIGSERIAL PRIMARY KEY,
    farmer_id BIGINT REFERENCES farmers(id) ON DELETE CASCADE,
    soil_health_id BIGINT REFERENCES soil_health_data(id) ON DELETE SET NULL,
    crop_id BIGINT REFERENCES crop_recommendations(id) ON DELETE SET NULL,

    -- Input parameters
    district_name TEXT NOT NULL,
    state_name TEXT NOT NULL,
    crop_name TEXT NOT NULL,
    season VARCHAR(20),

    -- Soil test values
    nitrogen_value DOUBLE PRECISION,
    phosphorus_value DOUBLE PRECISION,
    potassium_value DOUBLE PRECISION,
    ph_value DOUBLE PRECISION,

    -- Recommended fertilizer doses (kg/ha)
    urea_dose DOUBLE PRECISION,
    dap_dose DOUBLE PRECISION,
    mop_dose DOUBLE PRECISION,
    ssp_dose DOUBLE PRECISION,

    -- Micronutrient recommendations
    zinc_sulphate DOUBLE PRECISION,
    ferrous_sulphate DOUBLE PRECISION,
    borax DOUBLE PRECISION,

    -- Application schedule
    basal_dose TEXT,
    first_topdress TEXT,
    second_topdress TEXT,

    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_fertilizer_recommendations_farmer ON fertilizer_recommendations(farmer_id);
CREATE INDEX idx_fertilizer_recommendations_date ON fertilizer_recommendations(created_at);

-- ========================================
-- FUNCTIONS & TRIGGERS
-- ========================================

-- Function to classify NPK levels
CREATE OR REPLACE FUNCTION classify_nutrient_level(value DOUBLE PRECISION, nutrient_type VARCHAR)
RETURNS VARCHAR AS $$
BEGIN
    IF value IS NULL THEN
        RETURN 'Unknown';
    END IF;

    CASE nutrient_type
        WHEN 'N' THEN
            IF value < 280 THEN RETURN 'Low';
            ELSIF value <= 560 THEN RETURN 'Medium';
            ELSE RETURN 'High';
            END IF;
        WHEN 'P' THEN
            IF value < 10 THEN RETURN 'Low';
            ELSIF value <= 25 THEN RETURN 'Medium';
            ELSE RETURN 'High';
            END IF;
        WHEN 'K' THEN
            IF value < 110 THEN RETURN 'Low';
            ELSIF value <= 280 THEN RETURN 'Medium';
            ELSE RETURN 'High';
            END IF;
        ELSE
            RETURN 'Unknown';
    END CASE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Trigger to automatically update nutrient status
CREATE OR REPLACE FUNCTION update_nutrient_status()
RETURNS TRIGGER AS $$
BEGIN
    NEW.nitrogen_status = classify_nutrient_level(NEW.nitrogen_avg, 'N');
    NEW.phosphorus_status = classify_nutrient_level(NEW.phosphorus_avg, 'P');
    NEW.potassium_status = classify_nutrient_level(NEW.potassium_avg, 'K');
    NEW.last_updated = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_update_soil_health_status
BEFORE INSERT OR UPDATE ON soil_health_data
FOR EACH ROW EXECUTE FUNCTION update_nutrient_status();

-- ========================================
-- VIEWS
-- ========================================

-- View for latest soil health data per district
CREATE OR REPLACE VIEW district_soil_summary AS
SELECT
    d.id as district_id,
    d.name as district_name,
    d.state_name,
    d.geom,
    s.nitrogen_avg,
    s.nitrogen_status,
    s.phosphorus_avg,
    s.phosphorus_status,
    s.potassium_avg,
    s.potassium_status,
    s.ph_avg,
    s.organic_carbon,
    s.samples_analyzed,
    s.measurement_year,
    s.last_updated
FROM districts d
LEFT JOIN LATERAL (
    SELECT *
    FROM soil_health_data
    WHERE district_id = d.id
    ORDER BY measurement_year DESC, last_updated DESC
    LIMIT 1
) s ON true;

-- ========================================
-- SAMPLE DATA
-- ========================================

-- Insert sample states
INSERT INTO states (name, code, population, area_km2) VALUES
('Delhi', 'DL', 16787941, 1484),
('Haryana', 'HR', 25351462, 44212),
('Punjab', 'PB', 27743338, 50362),
('Uttar Pradesh', 'UP', 199812341, 240928),
('Maharashtra', 'MH', 112374333, 307713),
('Karnataka', 'KA', 61095297, 191791),
('Tamil Nadu', 'TN', 72147030, 130060),
('Gujarat', 'GJ', 60439692, 196244),
('Rajasthan', 'RJ', 68548437, 342239),
('West Bengal', 'WB', 91276115, 88752)
ON CONFLICT (name) DO NOTHING;

-- Insert sample districts with geometry
INSERT INTO districts (name, state_name, state_id, geom, area_km2, population, agricultural_area_km2)
SELECT 'Delhi', 'Delhi', s.id,
    ST_Multi(ST_GeomFromText('POLYGON((77.05 28.40, 77.35 28.40, 77.35 28.88, 77.05 28.88, 77.05 28.40))', 4326)),
    1484, 16787941, 200
FROM states s WHERE s.name = 'Delhi'
ON CONFLICT (name, state_name) DO NOTHING;

INSERT INTO districts (name, state_name, state_id, geom, area_km2, population, agricultural_area_km2)
SELECT 'Gurugram', 'Haryana', s.id,
    ST_Multi(ST_GeomFromText('POLYGON((76.85 28.30, 77.15 28.30, 77.15 28.55, 76.85 28.55, 76.85 28.30))', 4326)),
    1258, 1514432, 450
FROM states s WHERE s.name = 'Haryana'
ON CONFLICT (name, state_name) DO NOTHING;

INSERT INTO districts (name, state_name, state_id, geom, area_km2, population, agricultural_area_km2)
SELECT 'Ludhiana', 'Punjab', s.id,
    ST_Multi(ST_GeomFromText('POLYGON((75.70 30.75, 75.95 30.75, 75.95 31.05, 75.70 31.05, 75.70 30.75))', 4326)),
    3767, 3498739, 2800
FROM states s WHERE s.name = 'Punjab'
ON CONFLICT (name, state_name) DO NOTHING;

INSERT INTO districts (name, state_name, state_id, geom, area_km2, population, agricultural_area_km2)
SELECT 'Lucknow', 'Uttar Pradesh', s.id,
    ST_Multi(ST_GeomFromText('POLYGON((80.75 26.55, 81.15 26.55, 81.15 27.00, 80.75 27.00, 80.75 26.55))', 4326)),
    2528, 4589838, 1500
FROM states s WHERE s.name = 'Uttar Pradesh'
ON CONFLICT (name, state_name) DO NOTHING;

INSERT INTO districts (name, state_name, state_id, geom, area_km2, population, agricultural_area_km2)
SELECT 'Pune', 'Maharashtra', s.id,
    ST_Multi(ST_GeomFromText('POLYGON((73.55 18.25, 74.05 18.25, 74.05 18.75, 73.55 18.75, 73.55 18.25))', 4326)),
    15642, 9429408, 10000
FROM states s WHERE s.name = 'Maharashtra'
ON CONFLICT (name, state_name) DO NOTHING;

INSERT INTO districts (name, state_name, state_id, geom, area_km2, population, agricultural_area_km2)
SELECT 'Bangalore Urban', 'Karnataka', s.id,
    ST_Multi(ST_GeomFromText('POLYGON((77.45 12.85, 77.75 12.85, 77.75 13.15, 77.45 13.15, 77.45 12.85))', 4326)),
    2190, 9621551, 800
FROM states s WHERE s.name = 'Karnataka'
ON CONFLICT (name, state_name) DO NOTHING;

INSERT INTO districts (name, state_name, state_id, geom, area_km2, population, agricultural_area_km2)
SELECT 'Chennai', 'Tamil Nadu', s.id,
    ST_Multi(ST_GeomFromText('POLYGON((80.15 12.95, 80.30 12.95, 80.30 13.20, 80.15 13.20, 80.15 12.95))', 4326)),
    426, 7088000, 150
FROM states s WHERE s.name = 'Tamil Nadu'
ON CONFLICT (name, state_name) DO NOTHING;

-- Insert soil health data for all districts
INSERT INTO soil_health_data
(district_id, district_name, state_name, nitrogen_avg, phosphorus_avg, potassium_avg,
 ph_avg, organic_carbon, ec_avg, samples_analyzed, measurement_year, season, data_source)
SELECT
    d.id, d.name, d.state_name,
    CASE d.name
        WHEN 'Delhi' THEN 110.0
        WHEN 'Gurugram' THEN 320.0
        WHEN 'Ludhiana' THEN 580.0
        WHEN 'Lucknow' THEN 295.0
        WHEN 'Pune' THEN 420.0
        WHEN 'Bangalore Urban' THEN 380.0
        WHEN 'Chennai' THEN 240.0
        ELSE 300.0
    END,
    CASE d.name
        WHEN 'Delhi' THEN 18.0
        WHEN 'Gurugram' THEN 28.0
        WHEN 'Ludhiana' THEN 22.0
        WHEN 'Lucknow' THEN 15.0
        WHEN 'Pune' THEN 24.0
        WHEN 'Bangalore Urban' THEN 20.0
        WHEN 'Chennai' THEN 16.0
        ELSE 20.0
    END,
    CASE d.name
        WHEN 'Delhi' THEN 140.0
        WHEN 'Gurugram' THEN 290.0
        WHEN 'Ludhiana' THEN 310.0
        WHEN 'Lucknow' THEN 180.0
        WHEN 'Pune' THEN 260.0
        WHEN 'Bangalore Urban' THEN 220.0
        WHEN 'Chennai' THEN 195.0
        ELSE 200.0
    END,
    CASE d.name
        WHEN 'Delhi' THEN 7.6
        WHEN 'Gurugram' THEN 7.8
        WHEN 'Ludhiana' THEN 7.4
        WHEN 'Lucknow' THEN 7.5
        WHEN 'Pune' THEN 7.2
        WHEN 'Bangalore Urban' THEN 6.8
        WHEN 'Chennai' THEN 7.1
        ELSE 7.0
    END,
    CASE d.name
        WHEN 'Delhi' THEN 0.55
        WHEN 'Gurugram' THEN 0.62
        WHEN 'Ludhiana' THEN 0.71
        WHEN 'Lucknow' THEN 0.58
        WHEN 'Pune' THEN 0.65
        WHEN 'Bangalore Urban' THEN 0.60
        WHEN 'Chennai' THEN 0.52
        ELSE 0.50
    END,
    CASE d.name
        WHEN 'Delhi' THEN 0.85
        WHEN 'Gurugram' THEN 0.92
        WHEN 'Ludhiana' THEN 0.78
        WHEN 'Lucknow' THEN 0.88
        WHEN 'Pune' THEN 0.75
        WHEN 'Bangalore Urban' THEN 0.82
        WHEN 'Chennai' THEN 0.95
        ELSE 0.80
    END,
    CASE d.name
        WHEN 'Delhi' THEN 15234
        WHEN 'Gurugram' THEN 12876
        WHEN 'Ludhiana' THEN 18945
        WHEN 'Lucknow' THEN 24567
        WHEN 'Pune' THEN 34890
        WHEN 'Bangalore Urban' THEN 28734
        WHEN 'Chennai' THEN 19823
        ELSE 10000
    END,
    2025, 'Rabi', 'Sample Data'
FROM districts d
ON CONFLICT (district_id, measurement_year, season) DO NOTHING;

-- Insert crop recommendations
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
ON CONFLICT DO NOTHING;

-- Insert demo farmer
INSERT INTO farmers (username, password_hash, postal_code, district_name, state_name, full_name, phone)
VALUES
('demofarmer',
 '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', -- SHA-256 of 'password123'
 '110001',
 'Delhi',
 'Delhi',
 'Demo Farmer',
 '9876543210')
ON CONFLICT (username) DO NOTHING;

-- ========================================
-- GRANTS (Adjust as needed)
-- ========================================

-- Grant permissions to application user
-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO ishm;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO ishm;
-- GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO ishm;

-- ========================================
-- ANALYTICS & REPORTING
-- ========================================

-- Create materialized view for performance (optional)
-- CREATE MATERIALIZED VIEW mv_district_statistics AS
-- SELECT
--     state_name,
--     COUNT(DISTINCT district_id) as total_districts,
--     AVG(nitrogen_avg) as avg_nitrogen,
--     AVG(phosphorus_avg) as avg_phosphorus,
--     AVG(potassium_avg) as avg_potassium,
--     SUM(samples_analyzed) as total_samples
-- FROM soil_health_data
-- WHERE measurement_year = EXTRACT(YEAR FROM CURRENT_DATE)
-- GROUP BY state_name;

-- CREATE INDEX ON mv_district_statistics(state_name);

COMMENT ON TABLE farmers IS 'User authentication and farmer profiles';
COMMENT ON TABLE states IS 'Indian states with geographic boundaries';
COMMENT ON TABLE districts IS 'Districts with PostGIS geometry data';
COMMENT ON TABLE soil_health_data IS 'NPK and soil health measurements by district';
COMMENT ON TABLE crop_recommendations IS 'Crop-specific nutrient requirements';
COMMENT ON TABLE fertilizer_recommendations IS 'Saved fertilizer recommendations for farmers';
COMMENT ON FUNCTION classify_nutrient_level IS 'Classifies NPK values into Low/Medium/High categories';
COMMENT ON VIEW district_soil_summary IS 'Latest soil health data per district for mapping';

-- Vacuum and analyze for optimal performance
VACUUM ANALYZE;

-- Display summary
SELECT 'Database schema created successfully!' as status;
SELECT COUNT(*) as total_states FROM states;
SELECT COUNT(*) as total_districts FROM districts;
SELECT COUNT(*) as total_soil_records FROM soil_health_data;
SELECT COUNT(*) as total_crops FROM crop_recommendations;
SELECT COUNT(*) as total_farmers FROM farmers;