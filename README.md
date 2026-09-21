# Yuzee AI Token Lab — Java/Angular port

A Java Spring Boot + Angular re-implementation of **yuzee-ai-token-lab**, a Gemini-powered career-counselling chat app ("Yuzee"/"Oala") originally built as a Node/Express + React application. This repository is the migration target: same product, same protocol contract, new stack.

**Status: partial migration, actively in progress.** The foundation (Phase 0 below) is complete and live-verified against real Gemini and Postgres. Most of the original app's feature surface — routing/skill suggestions, the objectives workspace, mini-pathway, research ("Explore more"), warehouse (course/provider data), the pathway whiteboard, and most of the frontend UI — is not yet ported. See [Migration status](#migration-status) for the honest breakdown.

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3.6 (Web, Security, Validation, Actuator, JDBC) |
| Frontend | Angular 18 (standalone components, signals), Bootstrap 5 |
| AI | Google Gemini (REST API via OkHttp — no SDK dependency) |
| Persistence | PostgreSQL when `DATABASE_URL` is set, otherwise a local JSON file store |
| Auth | Single-admin HMAC bearer token (no user accounts) |

Ionic is installed as a dependency but currently unused (no `<ion-*>` markup anywhere) — the UI is plain Bootstrap.

## Repository layout

```
backend/    Spring Boot API (com.yuzee.tokenlab)
frontend/   Angular SPA
mvn-wrapper.jar   (legacy artifact at repo root — superseded by backend/.mvn/wrapper)
.env.example      Template for required environment variables (keep secrets OUT of this file — see below)
```

### Backend package structure

Flat, by-layer packages under `com.yuzee.tokenlab`:

- `controller/` — REST + SSE endpoints. `ChatController` owns the `/api/conversations/:id/messages` streaming turn; `ConversationController` owns CRUD; `SystemController` is a grab-bag of config/stats/status endpoints; `AuthController` handles login; `SpaController` serves the Angular build's `index.html` as a fallback for client-side routes.
- `service/` — business logic (see [How a chat turn works](#how-a-chat-turn-works) below).
- `protocol/` — the "Yuzee Response Protocol" envelope types and validator.
- `model/` — plain POJOs (`Conversation`, `ChatMessage`, `ChatRequest`, `ModelInfo`, `CompactionMetrics`).
- `repository/` — `ConversationRepository` with two implementations (Postgres/file), chosen automatically at boot.
- `config/` — Spring config (`SecurityConfig`, `CorsConfig`, `PersistenceConfig`).
- `auth/` — the HMAC bearer-token filter.

### Frontend structure

Angular 18 standalone components (no NgModules), Angular **signals** for state (see `TokenLabService`), Bootstrap utility classes for styling. `frontend/src/app/components/` holds the UI; `frontend/src/app/services/` holds `TokenLabService` (central chat/conversation state), `AuthService`, and `ApiService` (a reusable HTTP/SSE wrapper that isn't fully adopted everywhere yet).

## How a chat turn works

A `POST /api/conversations/:id/messages` request runs through this pipeline (`ChatController.runTurn`):

1. **`RequestAssemblerService.assembleRequest`** — first runs a deterministic bypass classifier (greeting/farewell/idle/rubbish chit-chat never touches Gemini at all); for real turns, it calls into `ConversationMemoryService` to select which prior turns fit the token budget (3 strategies: keep-everything, keyword-relevance, or newest-first eviction), builds the multi-turn Gemini `contents` array via `MultiTurnRequestBuilder`, resolves a per-message "thinking level" from a keyword heuristic, and attaches the sanitized structured-output schema.
2. **`SystemPromptCacheManager`** — looks up (or kicks off in the background) an explicit Gemini context cache for the ~200KB system prompt, so it isn't resent on every turn. Falls back to sending it inline if caching isn't available yet.
3. **`GeminiService.streamGenerateRich`** — streams the model response over SSE, wrapped by **`ProviderRecoveryService`** (a single retry, but only if no output has reached the client yet — never retries a stream that's already partially delivered).
4. **`ProtocolValidator`** — validates the complete response against the JSON Schema (`response-schema-v1.3.json`) plus hand-ported semantic invariants (first block must be plain text, confidence score/band consistency, trusted-action registry checks, etc.). Also normalizes a known Gemini quirk where nullable enum fields come back as JSON `null` instead of `""`.
5. **`TeachingAnswerReviewService` + `ReviewRetryService`** — for substantive answers (≥120 words or ≥3 titled blocks), runs a bounded second Gemini pass that fact-checks the response against the conversation ("don't let a self-reported task become an inflated skill claim") and merges the corrected blocks back in. Falls back to the original valid answer if the review itself fails.
6. **`SecurityStateService`** — overwrites the model's own claimed security-penalty state with the server-authoritative value (never trust the model to self-report a breach count).
7. Persisted via `ConversationService`/`ConversationLogService`, priced via `GeminiModelRegistry` (real per-model pricing, not a flat rate), and streamed to the client as a sequence of SSE events (`phase`, `chunk`, final `done` payload with the parsed response + token usage + compaction metrics).

The actual "Yuzee Response Protocol" is a large, strict JSON contract (content blocks, an active interaction/question, service-trigger state, RMO readiness, confidence tracking) — see `backend/src/main/resources/prompts/response-schema-v1.3.json` for the schema and `system-prompt.md` for the ~3,500-line prompt that drives it.

## Running it locally

### Prerequisites
- Java 21+
- Node 18+ / npm
- A Gemini API key
- (Optional) A Postgres connection string — without one, conversations persist to a local JSON file instead

### Environment variables

Copy `.env.example` and fill in real values (see the [security note](#security-notes) below — do not put real secrets in a file that gets committed):

| Variable | Required | Purpose |
|---|---|---|
| `GEMINI_API_KEY` | Yes | Gemini API access |
| `DATABASE_URL` | No | `postgres://user:pass@host:port/db` — enables Postgres persistence; omit for the local file store |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | No | Defaults to `yuzeeadmin` / `yuzeeadmin@2026` |

Spring Boot does **not** read `.env` files automatically — these need to be real environment variables in whatever shell starts the backend.

### Backend

```bash
cd backend
export GEMINI_API_KEY=...        # plus DATABASE_URL / ADMIN_* if needed
./mvnw spring-boot:run
```

Runs on `http://localhost:8080`.

### Frontend

```bash
cd frontend
npm install
npm start                        # ng serve --proxy-config proxy.conf.json
```

Runs on `http://localhost:4200`, proxying `/api/*` to the backend on `:8080`. Login with the admin credentials above.

### Production build

`npm run build:spring` (in `frontend/`) builds the Angular app and copies it into `backend/src/main/resources/static/`, so a single Spring Boot jar can serve both the API and the SPA.

### Tests

```bash
cd backend && ./mvnw test     # JUnit
cd frontend && npm test       # Karma/Jasmine
```

## Migration status

Ported from the original `yuzee-ai-token-lab` (Node/Express + React) app in phases. Each phase is only marked done once it's been compiled, tested, and live-verified against a real Gemini key.

- [x] **Phase 0 — Foundation**: real system prompt/schema, Postgres-or-file conversation persistence with 30-day TTL, full request-assembly pipeline (memory budgeting, thinking-level resolution, schema sanitization), protocol validation, Gemini context caching, provider retry, the teaching-review pass, real per-model pricing.
- [ ] **Phase 1** — Oala (`@mention`) skill routing, the client-side BGE embedding-based router, Cloudflare Llama fallback routing.
- [ ] **Phase 2** — Full protocol rendering (all content-block types, interactive question/answer widgets) and the conversation sidebar.
- [ ] **Phase 3** — Mini-pathway (AI-generated career route reports).
- [ ] **Phase 4** — Objectives workspace (a 316-item structured-activity catalogue).
- [ ] **Phase 5** — Research / "Explore more" (Google Search-grounded evidence lookup).
- [ ] **Phase 6** — Career context, contradiction detection, clarification questions, user profile.
- [ ] **Phase 7** — Token Lab power-user tooling (token inspector, analytics, benchmark, advanced settings).
- [ ] **Phase 8** — Pathway Whiteboard (drag/drop visual career-route editor).
- [ ] **Phase 9** — Warehouse (course/provider/local-labour-market data) — **blocked**: needs an external SQLite source database (`training_gov.db`) that isn't available anywhere yet; the Java pipeline will be built to honestly report "not configured" until that data is supplied.
- [ ] **Phase 10** — Rate limiting, hardening, end-to-end parity pass.

Endpoints for un-ported features currently exist as placeholders (e.g. `/api/warehouse/status` always reports unavailable, `/api/benchmark` returns a "not available" message) so the frontend doesn't 404 — they don't yet do anything real.

## Security notes

- `.env.example` is **gitignored** in this repo because it has been used to hold real credentials during development. Never remove it from `.gitignore` without first replacing its contents with placeholder values — a committed API key or database password lives in git history forever, even if the file is edited afterward.
- The admin auth scheme (`HmacTokenFilter`) is a single hardcoded identity with a non-expiring token — adequate for local development, not for a real deployment. Replacing it is out of scope for this migration and tracked as a known gap from the original app too (its own handover docs call this out as "local POC" auth).

## Where things live

- Original app (source of truth for anything not yet ported): `yuzee-ai-token-lab` (`server.ts` + its ~30 imported modules under `src/`).
- Protocol schema: `backend/src/main/resources/prompts/response-schema-v1.3.json` / `v1.4.json`.
- System prompt: `backend/src/main/resources/prompts/system-prompt.md`.
- Gemini model registry & pricing: `backend/src/main/java/com/yuzee/tokenlab/service/GeminiModelRegistry.java`.
