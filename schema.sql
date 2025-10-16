-- Enhanced database schema for soil health monitoring
CREATE EXTENSION IF NOT EXISTS postgis;

-- States table
CREATE TABLE IF NOT EXISTS states (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    code VARCHAR(2) NOT NULL UNIQUE,
    geom geometry(MultiPolygon, 4326),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS states_geom_gix ON states USING GIST (geom);

-- Districts table (enhanced)
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
);
CREATE INDEX IF NOT EXISTS districts_geom_gix ON districts USING GIST (geom);
CREATE INDEX IF NOT EXISTS districts_state_idx ON districts(state_id);

-- Soil health data (district-level aggregates)
CREATE TABLE IF NOT EXISTS soil_health_data (
    id BIGSERIAL PRIMARY KEY,
    district_id BIGINT REFERENCES districts(id) ON DELETE CASCADE,
    district_name TEXT NOT NULL,
    state_name TEXT NOT NULL,

    -- NPK levels (kg/ha)
    nitrogen_avg DOUBLE PRECISION,
    nitrogen_status VARCHAR(20), -- 'Low', 'Medium', 'High'
    phosphorus_avg DOUBLE PRECISION,
    phosphorus_status VARCHAR(20),
    potassium_avg DOUBLE PRECISION,
    potassium_status VARCHAR(20),

    -- Other soil parameters
    ph_avg DOUBLE PRECISION,
    organic_carbon DOUBLE PRECISION,
    ec_avg DOUBLE PRECISION, -- Electrical conductivity

    -- Micronutrients (ppm)
    zinc_avg DOUBLE PRECISION,
    iron_avg DOUBLE PRECISION,
    copper_avg DOUBLE PRECISION,
    manganese_avg DOUBLE PRECISION,
    boron_avg DOUBLE PRECISION,
    sulphur_avg DOUBLE PRECISION,

    -- Metadata
    samples_analyzed INTEGER,
    measurement_year INTEGER,
    season VARCHAR(20),
    data_source TEXT,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(district_id, measurement_year, season)
);
CREATE INDEX IF NOT EXISTS soil_health_district_idx ON soil_health_data(district_id);
CREATE INDEX IF NOT EXISTS soil_health_npk_idx ON soil_health_data(nitrogen_status, phosphorus_status, potassium_status);

-- Crop recommendations based on soil conditions
CREATE TABLE IF NOT EXISTS crop_recommendations (
    id BIGSERIAL PRIMARY KEY,
    crop_name TEXT NOT NULL,
    crop_type VARCHAR(50), -- 'Cereal', 'Pulse', 'Oilseed', 'Vegetable', 'Fruit'

    -- Optimal NPK ranges
    nitrogen_min DOUBLE PRECISION,
    nitrogen_max DOUBLE PRECISION,
    phosphorus_min DOUBLE PRECISION,
    phosphorus_max DOUBLE PRECISION,
    potassium_min DOUBLE PRECISION,
    potassium_max DOUBLE PRECISION,

    -- Other requirements
    ph_min DOUBLE PRECISION,
    ph_max DOUBLE PRECISION,
    organic_carbon_min DOUBLE PRECISION,

    season VARCHAR(20), -- 'Kharif', 'Rabi', 'Zaid'
    water_requirement VARCHAR(20), -- 'Low', 'Medium', 'High'

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Fertilizer recommendations
CREATE TABLE IF NOT EXISTS fertilizer_recommendations (
    id BIGSERIAL PRIMARY KEY,
    soil_health_id BIGINT REFERENCES soil_health_data(id) ON DELETE CASCADE,
    crop_id BIGINT REFERENCES crop_recommendations(id),

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

-- Function to classify NPK levels
CREATE OR REPLACE FUNCTION classify_nutrient_level(value DOUBLE PRECISION, nutrient_type VARCHAR)
RETURNS VARCHAR AS $$
BEGIN
    IF nutrient_type = 'N' THEN
        IF value < 280 THEN RETURN 'Low';
        ELSIF value <= 560 THEN RETURN 'Medium';
        ELSE RETURN 'High';
        END IF;
    ELSIF nutrient_type = 'P' THEN
        IF value < 10 THEN RETURN 'Low';
        ELSIF value <= 25 THEN RETURN 'Medium';
        ELSE RETURN 'High';
        END IF;
    ELSIF nutrient_type = 'K' THEN
        IF value < 110 THEN RETURN 'Low';
        ELSIF value <= 280 THEN RETURN 'Medium';
        ELSE RETURN 'High';
        END IF;
    ELSE
        RETURN 'Unknown';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Trigger to update nutrient status
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

CREATE TRIGGER update_soil_health_status
BEFORE INSERT OR UPDATE ON soil_health_data
FOR EACH ROW EXECUTE FUNCTION update_nutrient_status();

-- Sample data insertion
INSERT INTO states (name, code) VALUES
('Delhi', 'DL'),
('Haryana', 'HR'),
('Punjab', 'PB'),
('Uttar Pradesh', 'UP'),
('Maharashtra', 'MH')
ON CONFLICT (name) DO NOTHING;

-- Sample crop recommendations
INSERT INTO crop_recommendations (crop_name, crop_type, nitrogen_min, nitrogen_max, phosphorus_min, phosphorus_max, potassium_min, potassium_max, ph_min, ph_max, organic_carbon_min, season, water_requirement) VALUES
('Wheat', 'Cereal', 280, 560, 10, 25, 110, 280, 6.0, 7.5, 0.5, 'Rabi', 'Medium'),
('Rice', 'Cereal', 280, 560, 10, 25, 110, 280, 5.5, 7.0, 0.5, 'Kharif', 'High'),
('Maize', 'Cereal', 280, 560, 10, 25, 110, 280, 5.5, 7.8, 0.5, 'Kharif', 'Medium'),
('Cotton', 'Cash Crop', 280, 560, 15, 30, 150, 300, 7.0, 8.0, 0.5, 'Kharif', 'Medium'),
('Sugarcane', 'Cash Crop', 350, 600, 20, 35, 200, 350, 6.0, 7.5, 0.75, 'Annual', 'High'),
('Mustard', 'Oilseed', 200, 400, 10, 20, 100, 200, 6.0, 7.5, 0.4, 'Rabi', 'Low'),
('Tomato', 'Vegetable', 250, 450, 15, 30, 150, 280, 6.0, 7.0, 0.5, 'All', 'Medium'),
('Potato', 'Vegetable', 280, 500, 20, 35, 200, 350, 5.5, 6.5, 0.5, 'Rabi', 'Medium')
ON CONFLICT DO NOTHING;

-- View for map visualization
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
LEFT JOIN soil_health_data s ON d.id = s.district_id
WHERE s.measurement_year = (
    SELECT MAX(measurement_year)
    FROM soil_health_data s2
    WHERE s2.district_id = d.id
) OR s.measurement_year IS NULL;