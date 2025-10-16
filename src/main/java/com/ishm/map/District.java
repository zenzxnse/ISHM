package com.ishm.map;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.data.annotation.*;

@MappedEntity("districts")
@Introspected
public class District {
    @Id
    @GeneratedValue
    private Long id;

    private String name;
    private String state;
    // NOTE: we don't map the geometry column; it's fine to omit.

    public District() {}
    public District(Long id, String name, String state) {
        this.id = id; this.name = name; this.state = state;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
