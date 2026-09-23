package com.yuzee.tokenlab.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Port of TokenBudgetMemoryManager.ts DialogueTurn: one atomic user (+ optional assistant) pair. */
public class DialogueTurn {
    public String id;
    public Message userMessage;
    /** Undefined in the original when the turn has no accepted assistant reply, so omitted from JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Message assistantMessage;
    public int estimatedTokens;

    public DialogueTurn() {
    }

    public DialogueTurn(String id, Message userMessage, Message assistantMessage, int estimatedTokens) {
        this.id = id;
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
        this.estimatedTokens = estimatedTokens;
    }

    public static class Message {
        public String id;
        public String content;
        public long createdAt;

        public Message() {
        }

        public Message(String id, String content, long createdAt) {
            this.id = id;
            this.content = content;
            this.createdAt = createdAt;
        }
    }
}
