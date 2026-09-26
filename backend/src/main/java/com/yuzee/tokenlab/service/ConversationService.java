package com.yuzee.tokenlab.service;

import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.repository.ConversationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * server.ts `conversations` Map: the in-memory copy every route reads and mutates, loaded once at startup
 * (loadConversations), with the db.ts writes fired after each change exactly where the original fires them.
 * A persistence failure is logged and never fails the request (`.catch(() => {})`).
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final int MAX_IN_MEMORY = 500;

    private final ConversationRepository repository;
    // ponytail: one lock around the Map, like the original's single thread.
    private final Map<String, Conversation> conversations = Collections.synchronizedMap(new LinkedHashMap<>());

    public ConversationService(ConversationRepository repository) {
        this.repository = repository;
    }

    /** startServer(): "Always load persisted conversations" (file order, or updated_at DESC from PostgreSQL). */
    @PostConstruct
    void loadOnStartup() {
        for (Conversation c : repository.loadConversations()) conversations.putIfAbsent(c.getId(), c);
        if (!conversations.isEmpty()) log.info("[startup] Loaded {} conversations", conversations.size());
    }

    private List<Conversation> inMemory() {
        synchronized (conversations) {
            return new ArrayList<>(conversations.values());
        }
    }

    private static final Comparator<Conversation> NEWEST_FIRST =
        Comparator.comparing((Conversation c) -> c.getUpdatedAt() != null ? c.getUpdatedAt().toEpochMilli() : 0L).reversed();

    /** GET /api/conversations: from the DB (merged with in-flight conversations) when enabled, else the Map. */
    public List<Conversation> listAll() {
        if (repository.isDbEnabled()) {
            List<Conversation> fromDb = repository.loadConversations();
            Set<String> dbIds = new HashSet<>();
            for (Conversation c : fromDb) dbIds.add(c.getId());
            List<Conversation> merged = new ArrayList<>(fromDb);
            for (Conversation c : inMemory()) if (!dbIds.contains(c.getId())) merged.add(c);
            merged.sort(NEWEST_FIRST); // stable, like Array.prototype.sort
            // The original's "sync Map to DB" loop never removes anything (every missing id is in-flight).
            return merged;
        }
        List<Conversation> list = inMemory();
        list.sort(NEWEST_FIRST);
        return list;
    }

    public Optional<Conversation> findById(String id) {
        return Optional.ofNullable(conversations.get(id));
    }

    /** Create / restore: evict the least recently updated conversation at 500 ("free Render instance" cap), set, save. */
    public Conversation create(Conversation conv) {
        synchronized (conversations) {
            if (conversations.size() >= MAX_IN_MEMORY) {
                conversations.values().stream().min(Comparator.comparing((Conversation c) -> c.getUpdatedAt() != null ? c.getUpdatedAt().toEpochMilli() : 0L))
                    .ifPresent(oldest -> conversations.remove(oldest.getId()));
            }
            conversations.put(conv.getId(), conv);
        }
        persist(conv);
        return conv;
    }

    /** conversations.set(id, conv) without the cap (load-demo and the chat route's implicit create), then save. */
    public Conversation put(Conversation conv) {
        conversations.put(conv.getId(), conv);
        persist(conv);
        return conv;
    }

    /** `conv.updatedAt = Date.now(); saveConversation(conv)` (+ saveMessage for each message not yet saved). */
    public Conversation save(Conversation conv) {
        conv.setUpdatedAt(Instant.now());
        return persist(conv);
    }

    /**
     * saveConversation(conv), then saveMessage(m) for every message the original has not saved yet (a turn's two new
     * messages; every message of a demo or restore). Later edits to a saved message (feedback) are not re-saved,
     * as in the original. Leaves updatedAt alone.
     */
    public Conversation persist(Conversation conv) {
        try {
            repository.saveConversation(conv);
        } catch (Exception e) {
            log.error("[db] saveConversation failed:", e);
        }
        for (ChatMessage m : new ArrayList<>(conv.getMessages())) {
            if (m.isSaved()) continue;
            m.setSaved(true);
            try {
                repository.saveMessage(m, conv.getId());
            } catch (Exception e) {
                log.error("[db] saveMessage failed:", e);
            }
        }
        return conv;
    }

    /** DELETE: false when not in the Map; otherwise removed and deleteConversation(id). */
    public boolean delete(String id) {
        if (conversations.remove(id) == null) return false;
        try {
            repository.deleteConversation(id);
        } catch (Exception e) {
            log.error("[db] deleteConversation failed:", e);
        }
        return true;
    }

    /** db.ts pruneExpired() on server.ts's 6-hour interval (a no-op without PostgreSQL). */
    @Scheduled(initialDelay = 6 * 60 * 60 * 1000L, fixedRate = 6 * 60 * 60 * 1000L)
    public void pruneExpiredConversations() {
        repository.pruneExpired();
    }

    /** server.ts hourly keepAlive() ping (a no-op without PostgreSQL). */
    @Scheduled(initialDelay = 60 * 60 * 1000L, fixedRate = 60 * 60 * 1000L)
    public void keepDatabaseAlive() {
        repository.keepAlive();
    }
}
