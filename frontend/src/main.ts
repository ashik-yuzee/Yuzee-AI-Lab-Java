import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

// Inject auth token into all /api/* requests automatically (as the original main.tsx; HttpClient's
// fetch backend goes through window.fetch too)
const _origFetch = window.fetch.bind(window);
window.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : (input as Request).url;
  if (url.startsWith('/api') && !url.startsWith('/api/auth/')) {
    const token = localStorage.getItem('yuzee_auth') || '';
    if (token) {
      init = { ...init, headers: { Authorization: `Bearer ${token}`, ...(init?.headers as Record<string, string> | undefined) } };
    }
  }
  return _origFetch(input, init);
};

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
