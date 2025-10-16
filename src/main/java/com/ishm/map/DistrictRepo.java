package com.ishm.map;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface DistrictRepo extends CrudRepository<District, Long> {

    // BBOX -> GeoJSON FeatureCollection
    @Query(value = """
    SELECT jsonb_build_object(
      'type','FeatureCollection',
      'features', COALESCE(jsonb_agg(
        jsonb_build_object(
          'type','Feature',
          'geometry', ST_AsGeoJSON(geom)::jsonb,
          'properties', jsonb_build_object('name', name, 'state', state)
        )
      ), '[]'::jsonb)
    )::text
    FROM districts
    WHERE ST_Intersects(
      geom,
      ST_MakeEnvelope(:minx, :miny, :maxx, :maxy, 4326)
    );
  """, nativeQuery = true)
    String geojsonByBbox(double minx, double miny, double maxx, double maxy);

    // Nearest district centroid to (lat,lng)
    @Query(value = """
    SELECT name, state, ST_Y(ST_Centroid(geom)) AS lat, ST_X(ST_Centroid(geom)) AS lng
    FROM districts
    ORDER BY geom <-> ST_SetSRID(ST_Point(:lng, :lat), 4326)
    LIMIT 1
  """, nativeQuery = true)
    DistrictNearest nearest(double lat, double lng);

    // Optional stats per district
    @Query(value = """
    SELECT ph, oc, n, p, k, common_crops
    FROM district_stats
    WHERE name=:name AND state=:state
  """, nativeQuery = true)
    DistrictStats statsFor(String name, String state);

    interface DistrictNearest {
        String getName();
        String getState();
        double getLat();
        double getLng();
    }

    interface DistrictStats {
        Double getPh();
        Double getOc();
        Integer getN();
        Integer getP();
        Integer getK();
        String[] getCommon_crops();
    }
}
