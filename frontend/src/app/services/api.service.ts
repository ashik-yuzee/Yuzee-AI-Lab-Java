import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, from } from 'rxjs';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = '/api';

  constructor(private http: HttpClient, private auth: AuthService) {}

  private get headers(): HttpHeaders {
    const token = this.auth.token;
    return token
      ? new HttpHeaders({ Authorization: `Bearer ${token}` })
      : new HttpHeaders();
  }

  get<T>(path: string): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}${path}`, { headers: this.headers });
  }

  post<T>(path: string, body: any = {}): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body, { headers: this.headers });
  }

  put<T>(path: string, body: any = {}): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body, { headers: this.headers });
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${path}`, { headers: this.headers });
  }

  /**
   * Open an SSE stream. Returns an EventSource.
   * Caller is responsible for closing it.
   */
  openStream(path: string, body: any, onMessage: (data: any) => void, onError: (e: Event) => void): EventSource {
    // SSE with POST requires a workaround — we use fetch + ReadableStream
    const ctrl = new AbortController();
    const token = this.auth.token;
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    fetch(`${this.baseUrl}${path}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: ctrl.signal
    }).then(async res => {
      if (!res.ok || !res.body) {
        onError(new Event('error'));
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const payload = line.slice(5).startsWith(' ') ? line.slice(6) : line.slice(5);
            try {
              onMessage(JSON.parse(payload));
            } catch {}
          }
        }
      }
    }).catch(e => {
      if (e.name !== 'AbortError') onError(e);
    });

    // Return a fake EventSource-like object with close()
    return { close: () => ctrl.abort() } as any;
  }
}
