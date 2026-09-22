package com.yuzee.tokenlab.model.pathway;

/**
 * A directed connection between two {@link PathwayNode}s, e.g. {@code {"from":"n0","to":"n1"}}.
 * Matches the {@code edges} array the old app's generation schema already produced
 * (server.ts's {@code POST /api/pathway/generate}); the old React component itself never
 * rendered edges explicitly (it renders nodes as a single linear stream and reorders via drag and
 * drop), but the wire shape is kept so a future Angular whiteboard can draw real connections.
 */
public class PathwayEdge {
    private String from;
    private String to;

    public PathwayEdge() {
    }

    public PathwayEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
}
