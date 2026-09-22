import { Injectable, signal, computed } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { BehaviorSubject, firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { Conversation, ChatMessage, SessionStats, ChatProgressPhase } from '../models/types';

@Injectable({ providedIn: 'root' })
export class TokenLabService {
  // Conversation state
  conversations = signal<Conversation[]>([]);
  activeConversationId = signal<string | null>(null);
  activeConversation = computed(() =>
    this.conversations().find(c => c.id === this.activeConversationId()) ?? null
  );

  // Streaming state
  isStreaming = signal(false);
  streamPhase = signal<ChatProgressPhase | null>(null);
  streamBuffer = signal('');

  // Session stats
  sessionStats = signal<SessionStats | null>(null);

  // Settings
  activeModelId = signal('gemini-3.6-flash');
  optimizationMode = signal('AUTO');
  responseMode = signal('standard');

  // UI state
  locationProvided = signal(false);
  userLocation = signal<string | null>(null);

  // Mini pathway
  miniPathwayOpen = signal(false);

  private activeStream: { close: () => void } | null = null;

  constructor(private http: HttpClient, private auth: AuthService) {}

  private get headers(): HttpHeaders {
    const t = this.auth.token;
    return t ? new HttpHeaders({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' }) : new HttpHeaders();
  }

  async loadConversations(): Promise<void> {
    try {
      const convs = await firstValueFrom(
        this.http.get<Conversation[]>('/api/conversations', { headers: this.headers })
      );
      this.conversations.set(convs ?? []);
    } catch {}
  }

  async createConversation(modelId?: string, title?: string): Promise<Conversation> {
    const conv = await firstValueFrom(
      this.http.post<Conversation>('/api/conversations', { modelId: modelId ?? this.activeModelId(), title }, { headers: this.headers })
    );
    this.conversations.update(cs => [conv, ...cs]);
    this.activeConversationId.set(conv.id);
    return conv;
  }

  async loadDemoConversation(): Promise<Conversation> {
    const conv = await firstValueFrom(
      this.http.post<Conversation>('/api/conversations/load-demo', {}, { headers: this.headers })
    );
    this.conversations.update(cs => [conv, ...cs]);
    this.activeConversationId.set(conv.id);
    return conv;
  }

  async renameConversation(id: string, title: string): Promise<void> {
    await firstValueFrom(
      this.http.put(`/api/conversations/${id}`, { title }, { headers: this.headers })
    );
    this.conversations.update(cs => cs.map(c => c.id === id ? { ...c, title } : c));
  }

  async selectConversation(id: string): Promise<void> {
    this.activeConversationId.set(id);
    // Fetch fresh if needed
    try {
      const conv = await firstValueFrom(
        this.http.get<Conversation>(`/api/conversations/${id}`, { headers: this.headers })
      );
      this.conversations.update(cs => cs.map(c => c.id === id ? conv : c));
    } catch {}
  }

  async deleteConversation(id: string): Promise<void> {
    await firstValueFrom(
      this.http.delete(`/api/conversations/${id}`, { headers: this.headers })
    );
    this.conversations.update(cs => cs.filter(c => c.id !== id));
    if (this.activeConversationId() === id) {
      const remaining = this.conversations();
      this.activeConversationId.set(remaining[0]?.id ?? null);
    }
  }

  sendMessage(message: string, userEvent?: any): void {
    if (this.isStreaming()) return;
    const convId = this.activeConversationId();
    if (!convId) return;

    // Add user message optimistically
    const userMsg: ChatMessage = {
      id: Math.random().toString(36).slice(2),
      role: 'user',
      content: message,
      timestamp: new Date().toISOString()
    };
    this.addMessageToConv(convId, userMsg);

    // Add streaming placeholder
    const streamingMsg: ChatMessage = {
      id: 'streaming',
      role: 'assistant',
      content: '',
      streaming: true,
      streamBuffer: ''
    };
    this.addMessageToConv(convId, streamingMsg);

    this.isStreaming.set(true);
    this.streamPhase.set('routing');
    this.streamBuffer.set('');

    const token = this.auth.token;
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const ctrl = new AbortController();
    this.activeStream = { close: () => ctrl.abort() };

    fetch(`/api/conversations/${convId}/messages`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        message,
        modelId: this.activeModelId(),
        optimizationMode: this.optimizationMode(),
        responseMode: this.responseMode(),
        userEvent
      }),
      signal: ctrl.signal
    }).then(async res => {
      if (!res.ok || !res.body) {
        this.finishStream(convId, null, 'API error');
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = '';

      const processLine = (line: string) => {
        if (!line.startsWith('data:')) return;
        try {
          const data = JSON.parse(line.slice(5).trim());
          if (data.phase) {
            this.streamPhase.set(data.phase as ChatProgressPhase);
          } else if (data.chunk) {
            this.streamBuffer.update(b => b + data.chunk);
            this.updateStreamingMessage(convId, this.streamBuffer());
          } else if (data.done) {
            this.finishStream(convId, data);
          } else if (data.error) {
            this.finishStream(convId, null, data.error);
          }
        } catch {}
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split('\n');
        buf = lines.pop() ?? '';
        for (const line of lines) processLine(line);
      }

      // Process any event left in buf when stream closes without trailing newline
      for (const line of buf.split('\n')) processLine(line);
      if (this.isStreaming()) this.finishStream(convId, null);
    }).catch(e => {
      if (e.name !== 'AbortError') this.finishStream(convId, null, e.message);
    });
  }

  stopStream(): void {
    this.activeStream?.close();
    this.activeStream = null;
    const convId = this.activeConversationId();
    if (convId) {
      this.replaceStreamingMessage(convId, {
        id: Math.random().toString(36).slice(2),
        role: 'assistant',
        content: this.streamBuffer(),
        streamStopped: true
      });
    }
    this.isStreaming.set(false);
    this.streamPhase.set(null);
  }

  private addMessageToConv(convId: string, msg: ChatMessage): void {
    this.conversations.update(cs =>
      cs.map(c => c.id === convId ? { ...c, messages: [...c.messages, msg] } : c)
    );
  }

  private updateStreamingMessage(convId: string, text: string): void {
    this.conversations.update(cs =>
      cs.map(c => c.id === convId ? {
        ...c,
        messages: c.messages.map(m => m.id === 'streaming' ? { ...m, streamBuffer: text } : m)
      } : c)
    );
  }

  private replaceStreamingMessage(convId: string, msg: ChatMessage): void {
    this.conversations.update(cs =>
      cs.map(c => c.id === convId ? {
        ...c,
        messages: c.messages.map(m => m.id === 'streaming' ? msg : m)
      } : c)
    );
  }

  private finishStream(convId: string, data: any, error?: string): void {
    const finalMsg: ChatMessage = {
      id: data?.messageId ?? Math.random().toString(36).slice(2),
      role: 'assistant',
      content: this.streamBuffer(),
      parsedResponse: data?.parsedResponse,
      tokenUsage: data?.tokenUsage,
      compaction: data?.compaction,
      researchOffer: data?.researchOffer,
      validationFailed: data?.validationFailed || !!error,
      streaming: false
    };
    this.replaceStreamingMessage(convId, finalMsg);
    this.isStreaming.set(false);
    this.streamPhase.set(null);
    this.activeStream = null;
    // Refresh session stats
    this.loadSessionStats();
  }

  async loadSessionStats(): Promise<void> {
    try {
      const stats = await firstValueFrom(
        this.http.get<SessionStats>('/api/tokens/session-stats', { headers: this.headers })
      );
      this.sessionStats.set(stats);
    } catch {}
  }

  async resetSessionStats(): Promise<void> {
    await firstValueFrom(
      this.http.post('/api/tokens/session-reset', {}, { headers: this.headers })
    );
    await this.loadSessionStats();
  }

  setLocation(location: string): void {
    this.userLocation.set(location);
    this.locationProvided.set(true);
  }
}
