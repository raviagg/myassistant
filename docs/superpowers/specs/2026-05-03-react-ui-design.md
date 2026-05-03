# React UI — Design Spec
**Date:** 2026-05-03  
**Branch:** `feature/implement-ui`  
**Status:** Approved

---

## Overview

A React chat UI for the personal assistant. Users log in with a username, which is resolved to a person UUID, then chat with the AI. Each session starts fresh. Every message exchange has an expandable debug panel showing Anthropic API calls and tool call request/responses.

---

## Repository Restructure

### Deletions
| Path | Action |
|---|---|
| `client/chatbot/` | Delete — replaced by the web UI |
| `client/common/` | Delete — absorbed into `backend/chatbot_server/core/` |

### Moves / Renames
| From | To | Notes |
|---|---|---|
| `client/chatbot_tests/` | `backend/chatbot_server/tests/` | Rewritten to test FastAPI endpoints |

### Additions
| Path | What |
|---|---|
| `backend/chatbot_server/` | New Python FastAPI server |
| `frontend/` | New Vite + React app |

### Final top-level structure
```
myassistant/
  backend/
    http_server/          Scala REST API
    mcp_server/           Python MCP tools
    chatbot_server/       FastAPI chatbot server (NEW)
  frontend/               Vite + React UI (NEW)
  docs/
  CLAUDE.md
```

---

## 1 — Scala Backend Change

### Add `userIdentifier` filter to `GET /api/v1/persons`

`http-contract.md` must be updated first. New optional query parameter:

| Parameter | Type | Description |
|---|---|---|
| `userIdentifier` | string | Exact match on `user_identifier` column |

**Behaviour:** returns the matching person row (or empty items list if not found). Used exclusively by the login flow.

---

## 2 — FastAPI Chatbot Server (`backend/chatbot_server/`)

### Directory layout
```
backend/chatbot_server/
  core/
    agentic_runner.py     AgenticRunner — add chat_turn_streaming() method
    live_executor.py      LiveExecutor (unchanged from client/common)
    system_prompt.py      build_system_prompt(), CHATBOT_PROMPT_ADDENDUM (unchanged)
    tool_definitions.py   ALL_TOOLS (unchanged)
    server_manager.py     managed_server(), auth_token() (unchanged)
  routers/
    auth.py               POST /api/login
    chat.py               POST /api/chat  (SSE stream)
    files.py              POST /api/files (proxy to Scala)
  main.py                 FastAPI app, CORS, router registration
  pyproject.toml
  kickstart.md
  tests/                  (from client/chatbot_tests — rewritten for HTTP)
```

### Endpoints

#### `POST /api/login`
Looks up a person by `userIdentifier`. Returns person data on success, 404 on unknown username.

**Request:**
```json
{ "username": "raaggarw" }
```

**Response `200`:**
```json
{
  "personId": "uuid",
  "displayName": "Ravi",
  "fullName": "Ravi Aggarwal"
}
```

**Response `404`:**
```json
{ "error": "user_not_found", "message": "No person with username 'raaggarw'" }
```

#### `POST /api/chat` — SSE stream
Starts an agentic turn. Streams tokens as they arrive, then sends a terminal event with full debug data.

**Request (JSON body):**
```json
{
  "personId": "uuid",
  "message": "What was my salary in January?",
  "filePaths": ["/data/files/2026/04/slip.pdf"]
}
```

**File path injection:** When `filePaths` is non-empty, the router appends a structured note to the user message before passing it to `AgenticRunner`:

```
<original message>

Attached files:
- /data/files/2026/04/slip.pdf
```

The agent then calls `extract_text_from_file` on those paths as part of its normal gather phase — no special handling needed in `AgenticRunner`.

**SSE event stream:**
```
event: token
data: {"text": "Your "}

event: token
data: {"text": "January "}

... (one event per token) ...

event: tool_call
data: {"name": "search_current_facts", "input": {...}}

event: tool_result
data: {"toolName": "search_current_facts", "result": {...}}

event: done
data: {
  "fullText": "Your January 2026 payslip showed...",
  "debugInfo": {
    "apiCalls": [
      {
        "requestMessages": [...],
        "responseBlocks": [...],
        "stats": { "durationMs": 1240, "inputTokens": 1842, "cacheReadTokens": 1104, "outputTokens": 312 }
      }
    ],
    "toolCalls": [
      { "name": "search_current_facts", "input": {...}, "result": {...} },
      { "name": "get_current_fact", "input": {...}, "result": {...} }
    ]
  }
}
```

#### `POST /api/files`
Accepts `multipart/form-data`. Forwards file to `POST /api/v1/files` on the Scala backend (base64 encoded). Returns the `filePath` for use in subsequent `/api/chat` calls.

**Response `200`:**
```json
{ "filePath": "/data/files/2026/04/...", "filename": "slip.pdf", "mimeType": "application/pdf" }
```

### Streaming implementation
`AgenticRunner` gets a new `chat_turn_streaming()` async generator method alongside the existing `chat_turn()` (which is unchanged, no regressions). It yields typed events:

```python
async def chat_turn_streaming(self, user_message):
    # yields: TokenEvent | ToolCallEvent | ToolResultEvent | DoneEvent
```

Text tokens are yielded as `TokenEvent` during model output. Tool calls pause the stream, execute, and yield `ToolCallEvent` + `ToolResultEvent` before resuming the stream with the next model call.

**Bedrock streaming:** Both auth paths need streaming support:
- `anthropic.AnthropicBedrock`: uses `client.messages.stream()` — supported natively by the SDK.
- `_BedrockBearerClient` (custom httpx client): must be extended to call the Bedrock streaming endpoint (`/invoke-with-response-stream`) and parse the `application/vnd.amazon.eventstream` chunked response. This is non-trivial; the implementation should add a `stream()` method to `_BedrockBearerClient` that adapts the event-stream format to match the Anthropic SDK's streaming interface so `chat_turn_streaming()` needs no branching.

### Configuration (environment variables)
| Variable | Default | Purpose |
|---|---|---|
| `MYASSISTANT_BASE_URL` | `http://localhost:8080` | Scala backend |
| `MYASSISTANT_AUTH_TOKEN` | `dev-token-change-me-in-production` | Bearer token |
| `BEDROCK_API_KEY` | — | Bedrock auth |
| `AWS_REGION` | `us-west-2` | Bedrock region |
| `CHATBOT_DB` | `myassistant` | DB for auto-start |
| `CORS_ORIGIN` | `http://localhost:5173` | Allowed frontend origin |

### CORS
FastAPI configured with `CORSMiddleware` allowing `CORS_ORIGIN`. In development, `http://localhost:5173` (Vite default).

---

## 3 — React Frontend (`frontend/`)

### Stack
- **Vite** + **React 18** + **TypeScript**
- **No UI component library** — minimal custom CSS (dark theme matching the mockup)
- **No global state library** — React `useState` / `useContext` is sufficient for session scope

### Directory layout
```
frontend/
  src/
    components/
      LoginScreen.tsx
      ChatScreen.tsx
      MessageList.tsx
      MessageBubble.tsx
      DebugPanel.tsx
      InputBar.tsx
      ProfileMenu.tsx
    hooks/
      useChatStream.ts    SSE consumer hook
    api.ts                login(), uploadFile() fetch wrappers
    types.ts              Message, DebugInfo, ToolCall, etc.
    App.tsx               top-level: shows LoginScreen or ChatScreen
    main.tsx
    index.css
  index.html
  vite.config.ts
  tsconfig.json
  package.json
```

### Screens & components

#### `App.tsx`
Holds `session: { personId, displayName } | null`. Renders `<LoginScreen>` when null, `<ChatScreen session={session}>` when set. Logout clears the session state — chat history is in-memory in `ChatScreen` and is discarded.

#### `LoginScreen`
- Single username text input + "Continue →" button
- Calls `POST /api/login`; on 404 shows inline error "Username not found"
- On success sets session in `App`

#### `ChatScreen`
- Holds `messages: Message[]` in local state (fresh per login)
- Renders `<MessageList>`, `<InputBar>`, and top bar with `<ProfileMenu>`

#### `MessageBubble`
- User messages: right-aligned, dark blue bubble
- Assistant messages: left-aligned, darker bubble
- File attachment tag shown above user bubble when files were included
- `<DebugPanel>` rendered below each assistant bubble

#### `DebugPanel`
- Collapsed by default; toggle arrow shows token stats summary even when collapsed (e.g. `4 tool calls · 2.3 s · in=1,842 hit=1,104 out=312`)
- Expanded view: lists each API call with request message summary and response blocks, then all tool call inputs + results as formatted JSON
- Streaming turns: toggle shows `n tool calls · streaming…` until `done` event arrives, then finalises

#### `InputBar`
- Textarea (auto-grows up to ~4 lines), paperclip button, send button
- Paperclip opens file picker (accepts any file); selected files shown as removable chips above the textarea
- On send: upload each file via `POST /api/files` first, collect `filePaths`, then open SSE stream to `POST /api/chat`
- Send button and file picker disabled while a stream is in progress

#### `useChatStream` hook
Manages the SSE lifecycle:
1. Sends `POST /api/chat` with `fetch` + `ReadableStream`
2. Parses `event:` / `data:` lines from the stream
3. On `token`: appends to the in-progress assistant message text
4. On `tool_call` / `tool_result`: accumulates into `debugInfo`
5. On `done`: finalises message, attaches full `debugInfo`, closes stream
6. Exposes `{ sendMessage, isStreaming }`

#### `ProfileMenu`
- Avatar circle showing user initials (top right)
- Click toggles a small dropdown with "Logout" option
- Logout calls `App`'s clear-session callback

### Routing
No client-side router needed — the app has two states (logged out / logged in) managed by `App` state.

### API base URL
Vite proxy in `vite.config.ts` forwards `/api/*` to `http://localhost:8000` (FastAPI) during development, so no hardcoded URLs in the frontend code.

---

## 4 — Tests (`backend/chatbot_server/tests/`)

The existing `client/chatbot_tests/tool_harness/` tests will be rewritten to test the FastAPI HTTP layer. Each test scenario becomes an HTTP-level integration test:

- `POST /api/login` — valid username resolves, unknown returns 404
- `POST /api/files` — file upload proxied correctly to Scala
- `POST /api/chat` (SSE) — stream emits `token`, `tool_call`, `tool_result`, `done` events in correct order; `done.debugInfo` contains expected tool names

Tests use `httpx.AsyncClient` against the FastAPI `TestClient` (or a live server). The Scala backend is mocked with `respx` (same pattern as existing `mcp_server/tests/`).

---

## 5 — Key Design Decisions

| Decision | Rationale |
|---|---|
| SSE over WebSocket | Simpler server-side (no ws state), sufficient for unidirectional token stream |
| Debug data in `done` event | Tool call details only make sense once a turn is complete; no need to stream them incrementally |
| No auth token in browser | FastAPI server holds the Scala Bearer token; browser only knows `personId` |
| Fresh session per login | No persistence requirement stated; keeps implementation simple |
| No React router | Two-state app (login / chat) needs no URL routing |
| Vite proxy for `/api/*` | Avoids CORS in dev without changing FastAPI CORS config |
| `userIdentifier` exact match | Login is an identity assertion, not a search — exact match prevents false positives |

---

## Out of Scope

- Password / authentication (username-only login as specified)
- Persisting or restoring previous chat sessions
- Multiple concurrent chat sessions per user
- Mobile-responsive layout
- Dark/light theme toggle
