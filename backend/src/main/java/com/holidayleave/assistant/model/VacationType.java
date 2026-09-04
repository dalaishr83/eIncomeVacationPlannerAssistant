package com.holidayleave.assistant.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class VacationType {
    @JsonProperty("code")  private final String code;
    @JsonProperty("label") private final String label;
    @JsonProperty("color") private final String color;

    public VacationType(@JsonProperty("code")  String code,
                        @JsonProperty("label") String label,
                        @JsonProperty("color") String color) {
        this.code  = code;
        this.label = label;
        this.color = color;
    }

    public String code()  { return code; }
    public String label() { return label; }
    public String color() { return color; }
}
