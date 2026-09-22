package com.yuzee.tokenlab.model;

import java.util.List;

/**
 * Java port of routing.ts's ObjectiveMatch: one candidate objective with a retrieval score,
 * as handed to {@code objectiveShortlist}/{@code fuseObjectiveMatches}. The vector-ranking half
 * (rankObjectivesFromVectors, mergeObjectiveRanks) that produces these scores is client-side
 * embedding work owned elsewhere; this type is just the wire shape those scores arrive in.
 */
public class ObjectiveMatch {
    private String id;
    private double score;
    private List<String> sources;

    public ObjectiveMatch() {}

    public ObjectiveMatch(String id, double score) {
        this.id = id;
        this.score = score;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }
}
