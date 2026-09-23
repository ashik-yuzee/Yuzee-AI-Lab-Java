package com.yuzee.tokenlab.repository;

import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;

import java.util.List;

/**
 * db.ts conversation persistence, one method per db.ts export. ConversationService keeps the in-memory Map the
 * original serves from; these calls are the original's fire-and-forget writes and its startup/list reads.
 * <ul>
 *   <li>{@link JdbcConversationRepository}: PostgreSQL (DATABASE_URL set).</li>
 *   <li>{@link FileConversationRepository}: LocalConversationStore on data/conversations.json.</li>
 * </ul>
 */
public interface ConversationRepository {

    /** isDbEnabled(). */
    boolean isDbEnabled();

    /** loadConversations(): db.ts returns [] on a query failure; the file store throws on unreadable history, as the original. */
    List<Conversation> loadConversations();

    /** saveConversation(conv). */
    void saveConversation(Conversation conversation);

    /** saveMessage(msg, conversationId): a no-op without PostgreSQL. */
    void saveMessage(ChatMessage message, String conversationId);

    /** deleteConversation(id). */
    void deleteConversation(String id);

    /** pruneExpired(): a no-op without PostgreSQL. */
    void pruneExpired();
}
