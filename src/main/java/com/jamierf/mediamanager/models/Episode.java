package com.jamierf.mediamanager.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Episode implements Comparable<Episode> {

    @JsonProperty
    private final Name name;

    @JsonProperty
    private final State state;

    @JsonCreator
    public Episode(
            @JsonProperty("name") Name name,
            @JsonProperty("state") State state) {
        this.name = name;
        this.state = state;
    }

    public Episode copyWithState(State state) {
        return new Episode(name, state);
    }

    public Name getName() {
        return name;
    }

    public State getState() {
        return state;
    }

    @JsonIgnore
    public boolean isDesired() {
        return state == State.DESIRED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Episode episode = (Episode) o;

        if (name != null ? !name.equals(episode.name) : episode.name != null) return false;
        if (state != episode.state) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (state != null ? state.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return String.format("%s (%s)", name, state);
    }

    @Override
    public int compareTo(Episode o) {
        return name.compareTo(o.name);
    }
}
