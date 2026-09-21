package com.yuzee.tokenlab.repository;

import com.yuzee.tokenlab.model.Conversation;

import java.util.List;
import java.util.Optional;

/**
 * Persists Conversation aggregates (a conversation and all of its messages).
 * <p>
 * Two implementations, chosen in {@code config.PersistenceConfig} based on whether a
 * DataSource is available (i.e. whether DATABASE_URL / spring.datasource.url is set):
 * <ul>
 *   <li>{@link JdbcConversationRepository} — Postgres-backed.</li>
 *   <li>{@link FileConversationRepository} — a local JSON file, used when there is no DB.</li>
 * </ul>
 */
public interface ConversationRepository {

    /** All non-expired conversations, most recently updated first. */
    List<Conversation> listAll();

    Optional<Conversation> findById(String id);

    /** Upsert: inserts a new conversation or replaces the existing one, including its messages. */
    Conversation save(Conversation conversation);

    /** @return true if a conversation with that id existed and was removed. */
    boolean delete(String id);

    /** Deletes conversations past their 30-day TTL. @return the number removed. */
    int pruneExpired();
}
