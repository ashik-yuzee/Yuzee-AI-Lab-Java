import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private _token: string | null = localStorage.getItem('yuzee_auth');
  private _authenticated = new BehaviorSubject<boolean>(!!this._token);
  authenticated$ = this._authenticated.asObservable();

  constructor(private http: HttpClient) {}

  get token(): string | null { return this._token; }
  get isAuthenticated(): boolean { return !!this._token; }

  login(username: string, password: string): Observable<any> {
    return this.http.post<{ token: string; username: string }>('/api/auth/login', { username, password }).pipe(
      tap(res => {
        this._token = res.token;
        localStorage.setItem('yuzee_auth', res.token);
        this._authenticated.next(true);
      })
    );
  }

  logout(): void {
    this._token = null;
    localStorage.removeItem('yuzee_auth');
    this._authenticated.next(false);
  }

  checkAuth(): Observable<any> {
    return this.http.get('/api/auth/check', {
      headers: { Authorization: `Bearer ${this._token ?? ''}` }
    });
  }
}
