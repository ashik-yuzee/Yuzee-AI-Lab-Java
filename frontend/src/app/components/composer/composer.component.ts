import { Component, ElementRef, EventEmitter, Input, Output, SimpleChanges, ViewChild, effect, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RoutingService } from '../../services/routing.service';

// Ported from yuzee-ai-token-lab/src/components/Composer.tsx.

export interface ComposerAttachment {
  name: string;
  mimeType: string;
  data: string; // base64
  previewUrl?: string; // for images
}
export interface ComposerSendEvent {
  text: string;
  attachments: ComposerAttachment[];
  addressedOala: boolean;
}

const ACCEPTED_FILE_TYPES = 'image/*,application/pdf,text/plain,text/csv,application/json';

// oala/invocation.ts — small enough (3 tiny regex-based functions) to keep colocated here rather
// than as a separate file; this component is currently its only consumer.
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
  imports: [CommonModule],
  templateUrl: './composer.component.html',
  styleUrl: './composer.component.scss',
})
export class ComposerComponent {
  /** Conversation the draft is scoped to; also used as the sessionStorage draft key. */
  @Input() conversationId: string | null = null;
  /** True while a response is streaming — disables input, mirrors isStreaming in Composer.tsx. */
  @Input() disabled = false;
  @Output() send = new EventEmitter<ComposerSendEvent>();

  @ViewChild('textarea') private textareaRef?: ElementRef<HTMLTextAreaElement>;

  readonly text = signal(this.readDraft());
  readonly attachments = signal<ComposerAttachment[]>([]);
  readonly listening = signal(false);
  readonly mentionDismissed = signal(false);

  readonly oala = computed(() => parseOalaMention(this.text()));
  readonly showOalaSuggestion = computed(() => !this.mentionDismissed() && isOalaSuggestion(this.text()));
  readonly hasContent = computed(() => this.text().trim().length > 0 || this.attachments().length > 0);

  readonly acceptedFileTypes = ACCEPTED_FILE_TYPES;
  readonly micSupported = typeof window !== 'undefined' && !!((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition);

  private recognition: any;

  constructor(readonly routing: RoutingService) {
    effect(() => {
      const value = this.text();
      try {
        if (value) sessionStorage.setItem(this.draftKey(), value);
        else sessionStorage.removeItem(this.draftKey());
      } catch { /* storage can be disabled */ }
    });
  }

  ngOnInit(): void {
    this.routing.startWarmup();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['conversationId'] && !this.disabled) this.text.set(this.readDraft());
  }

  onInput(event: Event): void {
    const el = event.target as HTMLTextAreaElement;
    this.text.set(el.value);
    this.mentionDismissed.set(false);
    this.autoGrow(el);
  }

  onKeydown(event: KeyboardEvent): void {
    if ((event as any).isComposing) return;
    if (this.showOalaSuggestion() && (event.key === 'Enter' || event.key === 'Tab')) { event.preventDefault(); this.selectOala(); return; }
    if (this.showOalaSuggestion() && event.key === 'Escape') { event.preventDefault(); this.mentionDismissed.set(true); return; }
    if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); this.onSend(); }
  }

  selectOala(): void {
    this.text.set(addressOala(this.text()));
    this.mentionDismissed.set(true);
    this.routing.startWarmup();
    queueMicrotask(() => { this.textareaRef?.nativeElement.focus(); this.autoGrow(); });
  }

  clearOalaMention(): void {
    this.text.set(this.oala().message);
    this.textareaRef?.nativeElement.focus();
  }

  toggleMic(): void {
    if (this.listening()) { this.recognition?.stop(); return; }
    const SpeechRecognitionCtor = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognitionCtor) return;
    const rec = new SpeechRecognitionCtor();
    rec.lang = 'en-US';
    rec.interimResults = true;
    rec.continuous = false;
    rec.onstart = () => this.listening.set(true);
    rec.onresult = (e: any) => {
      const transcript = Array.from(e.results).map((r: any) => r[0].transcript).join('');
      this.text.set(transcript);
      this.autoGrow();
    };
    rec.onerror = () => this.listening.set(false);
    rec.onend = () => this.listening.set(false);
    this.recognition = rec;
    rec.start();
  }

  onFiles(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files || []);
    for (const file of files) {
      const reader = new FileReader();
      reader.onload = () => {
        const result = reader.result as string;
        const base64 = result.split(',')[1] ?? '';
        const attachment: ComposerAttachment = {
          name: file.name,
          mimeType: file.type || 'application/octet-stream',
          data: base64,
          previewUrl: file.type.startsWith('image/') ? result : undefined,
        };
        this.attachments.update(list => [...list, attachment]);
      };
      reader.readAsDataURL(file);
    }
    input.value = '';
  }

  removeAttachment(index: number): void {
    this.attachments.update(list => list.filter((_, i) => i !== index));
  }

  onSend(): void {
    if (this.disabled || (!this.text().trim() && this.attachments().length === 0)) return;
    this.send.emit({ text: this.text(), attachments: this.attachments(), addressedOala: this.oala().active });
    this.text.set('');
    this.attachments.set([]);
    this.mentionDismissed.set(false);
    queueMicrotask(() => this.autoGrow());
  }

  private autoGrow(el?: HTMLTextAreaElement): void {
    const target = el ?? this.textareaRef?.nativeElement;
    if (!target) return;
    target.style.height = 'auto';
    target.style.height = `${Math.min(target.scrollHeight, 180)}px`;
  }

  private draftKey(): string {
    return `yuzee-message-draft:${this.conversationId || 'new'}`;
  }

  private readDraft(): string {
    try { return sessionStorage.getItem(this.draftKey()) || ''; } catch { return ''; }
  }
}
