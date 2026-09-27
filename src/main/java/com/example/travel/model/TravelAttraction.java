package com.example.travel.model;

import java.io.Serial;
import java.io.Serializable;

public class TravelAttraction implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
    private String description;
    private String area;

    public TravelAttraction() {
    }

    public TravelAttraction(String name, String description, String area) {
        this.name = name;
        this.description = description;
        this.area = area;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        if (name != null) {
            sb.append(name);
        }
        if (area != null && !area.isBlank()) {
            sb.append(" (").append(area).append(')');
        }
        if (description != null && !description.isBlank()) {
            sb.append(": ").append(description);
        }
        return sb.toString();
    }
}
