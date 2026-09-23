import { Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild, computed, effect, signal } from '@angular/core';
import { IconComponent } from '../shared/icon/icon.component';
import { MicroRouterStatusComponent } from './micro-router-status.component';

// Port of yuzee-ai-token-lab/src/components/Composer.tsx.

export interface ComposerAttachment {
  name: string;
  mimeType: string;
  data: string; // base64
  previewUrl?: string; // for images
}

/** Resolves true when the message was accepted (the draft is then cleared), like sendMessage(). */
export type ComposerSendFn = (text: string, attachments: { mimeType: string; data: string }[]) => Promise<boolean> | boolean;

const ACCEPTED = 'image/*,application/pdf,text/plain,text/csv,application/json';

// oala/invocation.ts
function parseOalaMention(value: string): { active: boolean; message: string } {
  const match = value.match(/^\s*@\s*oala(?=$|\s|[:,!?])[:,!?]?\s*/i);
  return { active: !!match, message: match ? value.slice(match[0].length).trim() : value };
}
function isOalaSuggestion(text: string): boolean {
  return /^\s*@(?:o(?:a(?:l(?:a)?)?)?)?$/i.test(text);
}
function addressOala(text: string): string {
  if (parseOalaMention(text).active) return text;
  return '@Oala ' + (isOalaSuggestion(text) ? '' : text);
}

@Component({
  selector: 'app-composer',
  standalone: true,
  imports: [IconComponent, MicroRouterStatusComponent],
  templateUrl: './composer.component.html',
  styleUrl: './composer.component.scss',
})
export class ComposerComponent implements OnChanges {
  /** Conversation the draft is scoped to (sessionStorage key). */
  @Input() conversationId: string | null | undefined = null;
  /** isStreaming: disables input and swaps send for stop. */
  @Input() streaming = false;
  @Input({ required: true }) sendMessage!: ComposerSendFn;
  @Output() stop = new EventEmitter<void>();

  @ViewChild('textarea') private textareaRef?: ElementRef<HTMLTextAreaElement>;

  readonly text = signal('');
  readonly attachments = signal<ComposerAttachment[]>([]);
  readonly listening = signal(false);
  readonly mentionDismissed = signal(false);
  private readonly draftKey = signal('yuzee-message-draft:new');

  readonly oala = computed(() => parseOalaMention(this.text()));
  readonly showOalaSuggestion = computed(() => !this.mentionDismissed() && isOalaSuggestion(this.text()));
  readonly hasContent = computed(() => this.text().trim().length > 0 || this.attachments().length > 0);
  readonly acceptedFileTypes = ACCEPTED;

  private recognition: any;

  constructor() {
    this.text.set(this.readDraft());
    effect(() => {
      const text = this.text(), key = this.draftKey();
      try { if (text) sessionStorage.setItem(key, text); else sessionStorage.removeItem(key); } catch { /* storage unavailable */ }
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    const change = changes['conversationId'];
    const key = `yuzee-message-draft:${this.conversationId || 'new'}`;
    if (!change || (!change.firstChange && key === this.draftKey())) return;
    this.draftKey.set(key);
    // useState(readDraft) on mount; afterwards useEffect([draftKey]) reloads only when not streaming.
    if (change.firstChange || !this.streaming) this.text.set(this.readDraft());
  }

  private readDraft(): string {
    try { return sessionStorage.getItem(this.draftKey()) || ''; } catch { return ''; }
  }

  selectOala(): void {
    this.text.set(addressOala(this.text()));
    this.mentionDismissed.set(true);
    this.textareaRef?.nativeElement.focus();
  }

  clearOalaMention(): void {
    this.text.set(this.oala().message);
    this.textareaRef?.nativeElement.focus();
  }

  toggleMic(): void {
    if (this.listening()) { this.recognition?.stop(); return; }
    const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SR) { alert('Voice input requires Chrome or Edge.'); return; }
    const rec = new SR();
    rec.lang = 'en-US'; rec.interimResults = true; rec.continuous = false;
    rec.onstart = () => this.listening.set(true);
    rec.onresult = (e: any) => {
      const t = Array.from(e.results).map((r: any) => r[0].transcript).join('');
      this.text.set(t);
      const el = this.textareaRef?.nativeElement;
      if (el) {
        el.style.height = 'auto';
        el.style.height = `${Math.min(el.scrollHeight, 180)}px`;
      }
    };
    rec.onerror = () => this.listening.set(false);
    rec.onend = () => this.listening.set(false);
    this.recognition = rec; rec.start();
  }

  onKeydown(e: KeyboardEvent): void {
    if (e.isComposing) return;
    if (this.showOalaSuggestion() && (e.key === 'Enter' || e.key === 'Tab')) {
      e.preventDefault(); this.selectOala(); return;
    }
    if (this.showOalaSuggestion() && e.key === 'Escape') {
      e.preventDefault(); this.mentionDismissed.set(true); return;
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void this.handleSend();
    }
  }

  async handleSend(): Promise<void> {
    if ((!this.text().trim() && this.attachments().length === 0) || this.streaming) return;
    const sent = await this.sendMessage(this.text(), this.attachments().map(({ mimeType, data }) => ({ mimeType, data })));
    if (!sent) return;
    this.text.set('');
    this.attachments.set([]);
    if (this.textareaRef) this.textareaRef.nativeElement.style.height = 'auto';
  }

  onInput(e: Event): void {
    const target = e.target as HTMLTextAreaElement;
    this.text.set(target.value);
    this.mentionDismissed.set(false);
    target.style.height = 'auto';
    target.style.height = `${Math.min(target.scrollHeight, 180)}px`;
  }

  onFiles(e: Event): void {
    const input = e.target as HTMLInputElement;
    const files = Array.from(input.files || []);
    if (!files.length) return;
    files.forEach(file => {
      const reader = new FileReader();
      reader.onload = () => {
        const result = reader.result as string;
        const base64 = result.split(',')[1];
        const att: ComposerAttachment = { name: file.name, mimeType: file.type || 'application/octet-stream', data: base64 };
        if (file.type.startsWith('image/')) att.previewUrl = result;
        this.attachments.update(prev => [...prev, att]);
      };
      reader.readAsDataURL(file);
    });
    input.value = '';
  }

  removeAttachment(index: number): void {
    this.attachments.update(prev => prev.filter((_, i) => i !== index));
  }
}
