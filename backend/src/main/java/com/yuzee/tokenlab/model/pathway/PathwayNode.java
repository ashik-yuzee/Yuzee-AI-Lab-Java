package com.yuzee.tokenlab.model.pathway;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One card in the Pathway Whiteboard node-graph. Field names match the old React app's
 * {@code PWNode} interface (PathwayWhiteboard.tsx) so a future Angular frontend can consume this
 * shape unchanged: {@code {id, label, subtitle?, description?, prerequisites?, type}}.
 *
 * {@code type} is one of the node types the AI is prompted to emit: goal, phase, course, skill,
 * project, resume, apply, milestone. (The old app also has purely client-side status types --
 * current/complete/blocked/option/step -- toggled locally when the learner marks progress; those
 * never come from the server and so are not modelled here.)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PathwayNode {
    private String id;
    private String label;
    private String subtitle;
    private String description;
    private List<String> prerequisites;
    private String type;

    public PathwayNode() {
    }

    public PathwayNode(String id, String label, String subtitle, String description, String type) {
        this.id = id;
        this.label = label;
        this.subtitle = subtitle;
        this.description = description;
        this.type = type;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(List<String> prerequisites) { this.prerequisites = prerequisites; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
