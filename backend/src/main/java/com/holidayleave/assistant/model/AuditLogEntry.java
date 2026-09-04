package com.holidayleave.assistant.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class AuditLogEntry {
    private final String timestamp;
    private final String eventType;
    private final String user;
    private final String employee;
    private final String details;
    private final String status;
    private final String source;

    // @JsonProperty on constructor parameters controls deserialisation (JSON → POJO).
    // @JsonAlias on event_type accepts the legacy camelCase key written during a
    // brief intermediate state (one log line with "eventType" instead of "event_type").
    // @JsonProperty on getter methods controls serialisation (POJO → JSON).
    // Fields are intentionally unannotated — field + getter @JsonProperty on the same
    // name causes InvalidDefinitionException in Jackson's deserialiser.
    public AuditLogEntry(@JsonProperty("event_type") @JsonAlias("eventType") String eventType,
                         @JsonProperty("timestamp")  String timestamp,
                         @JsonProperty("user")       String user,
                         @JsonProperty("employee")   String employee,
                         @JsonProperty("details")    String details,
                         @JsonProperty("status")     String status,
                         @JsonProperty("source")     String source) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.user      = user;
        this.employee  = employee;
        this.details   = details;
        this.status    = status;
        this.source    = source;
    }

    @JsonProperty("timestamp")  public String getTimestamp() { return timestamp; }
    @JsonProperty("event_type") public String getEventType() { return eventType; }
    @JsonProperty("user")       public String getUser()      { return user; }
    @JsonProperty("employee")   public String getEmployee()  { return employee; }
    @JsonProperty("details")    public String getDetails()   { return details; }
    @JsonProperty("status")     public String getStatus()    { return status; }
    @JsonProperty("source")     public String getSource()    { return source; }
}
