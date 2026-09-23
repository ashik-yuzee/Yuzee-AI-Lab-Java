package com.yuzee.tokenlab.repository;

import com.yuzee.tokenlab.model.ChatMessage;
import com.yuzee.tokenlab.model.Conversation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * db.ts without a pool: `new LocalConversationStore(path.join(process.cwd(), 'data', 'conversations.json'))`.
 * saveConversation stores the whole in-memory conversation (messages included) as the original's
 * structuredClone; saveMessage and pruneExpired do nothing; loadConversations returns the file's order.
 */
public class FileConversationRepository implements ConversationRepository {

    private final LocalJsonStore store;

    public FileConversationRepository() {
        this(Paths.get("data", "conversations.json"));
    }

    public FileConversationRepository(Path file) {
        this.store = new LocalJsonStore(file);
    }

    @Override
    public boolean isDbEnabled() { return false; }

    @Override
    public List<Conversation> loadConversations() {
        List<Conversation> out = new ArrayList<>();
        for (Map<String, Object> raw : store.list()) {
            Conversation c = Conversation.fromClientJson(raw);
            c.getMessages().forEach(m -> m.setSaved(true));
            out.add(c);
        }
        return out;
    }

    @Override
    public void saveConversation(Conversation conversation) {
        store.save(conversation.toClientJson());
    }

    @Override
    public void saveMessage(ChatMessage message, String conversationId) { }

    @Override
    public void deleteConversation(String id) {
        store.delete(id);
    }

    @Override
    public void pruneExpired() { }
}
