package com.yuzee.tokenlab.service;

import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;
import com.yuzee.tokenlab.repository.ConversationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ConversationService {

    private final ConversationRepository repository;

    public ConversationService(ConversationRepository repository) {
        this.repository = repository;
    }

    public List<Conversation> listAll() {
        return repository.listAll();
    }

    public Conversation create(String modelId, String title) {
        Conversation conv = new Conversation();
        if (modelId != null) conv.setModelId(modelId);
        if (title != null) conv.setTitle(title);
        return repository.save(conv);
    }

    public Optional<Conversation> findById(String id) {
        return repository.findById(id);
    }

    public Conversation save(Conversation conv) {
        conv.setUpdatedAt(Instant.now());
        return repository.save(conv);
    }

    public boolean delete(String id) {
        return repository.delete(id);
    }

    public ChatMessage addMessage(String convId, String role, Object content) {
        return findById(convId).map(conv -> {
            ChatMessage msg = new ChatMessage();
            msg.setRole(role);
            msg.setContent(content);
            conv.getMessages().add(msg);
            conv.setUpdatedAt(Instant.now());
            repository.save(conv);
            return msg;
        }).orElseThrow(() -> new NoSuchElementException("Conversation not found: " + convId));
    }

    /** 30-day TTL cleanup, once a day (old app ran the equivalent on an interval too). */
    @Scheduled(initialDelay = 60_000L, fixedRate = 24 * 60 * 60 * 1000L)
    public void pruneExpiredConversations() {
        repository.pruneExpired();
    }
}
