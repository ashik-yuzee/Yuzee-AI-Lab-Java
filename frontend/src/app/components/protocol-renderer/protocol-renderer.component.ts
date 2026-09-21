import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { YuzeeResponseV13, YuzeeContentBlock, YuzeeItem } from '../../models/types';

@Component({
  selector: 'app-protocol-renderer',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './protocol-renderer.component.html',
  styleUrl: './protocol-renderer.component.scss'
})
export class ProtocolRendererComponent {
  @Input({ required: true }) response!: YuzeeResponseV13;

  blockTitle(block: YuzeeContentBlock): string {
    return (block['title'] as string) ?? '';
  }

  blockItems(block: YuzeeContentBlock): YuzeeItem[] {
    return (block['items'] as YuzeeItem[]) ?? [];
  }

  itemLabel(item: YuzeeItem): string { return (item['label'] as string) ?? ''; }
  itemText(item: YuzeeItem): string  { return (item['text']  as string) ?? ''; }

  renderText(text: string): string {
    return text
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\n\n/g, '</p><p>')
      .replace(/^/, '<p>')
      .replace(/$/, '</p>');
  }

  onOption(value: string): void {
    console.log('Option selected:', value);
  }
}
