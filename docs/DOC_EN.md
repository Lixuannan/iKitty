# iKitty Design Document

> [简体中文](DOC.md) · [Back to README](../README_EN.md)

This is iKitty's architecture map and reference manual: module contracts, data formats, key algorithms,
extension points, and testing strategy. It is aimed at anyone modifying or extending the code. Usage,
configuration steps, and privacy notes live in the [README](../README_EN.md).

- Version: 0.1.0 · Package: `com.example.aicat` · Source root: `app/src/main/java/com/example/aicat/`
- Stack: Kotlin 2.0.21, Jetpack Compose (Material3), OkHttp 4.12.0, DataStore Preferences 1.1.1
- Build: AGP 8.7.3, Gradle 9.7.1, Java 17 bytecode target, minSdk 26 / targetSdk 35

---

## 1. System overview

### 1.1 Layering

```
MainActivity (ComponentActivity + MaterialTheme)
│
└── CatChatScreen            ← top-level Composable, switches between three screens with boolean state
    ├── SettingsScreen       ← settings screen
    ├── CatMemoryScreen      ← memory screen
    └── chat screen (Header + LazyColumn + InputBar)
            │  collectAsState / event callbacks
            ▼
    CatChatViewModel (AndroidViewModel)   ← the only state holder and orchestrator
        ├── ApiClient            → model service (HTTP)
        ├── SettingsStore        → DataStore
        ├── ChatLogStore         → JSONL append
        ├── CatMemoryStore       → memory JSON
        ├── MemoryExtractor      → memory extraction request
        └── LocationSource ─ IpLocationSource → IP city geolocation
```

Dependencies always point from UI to ViewModel, and from ViewModel to storage and network.
The reverse direction is `StateFlow` only: the UI never reads or writes a file or the network directly.

### 1.2 The Android-free logic layer

The following objects reference no Android API and are therefore callable directly from plain JVM unit tests:

| Object | Responsibility |
| --- | --- |
| `CatPersona` | Persona → system prompt |
| `ModelCatalog` | Providers and model capability, parameter ranges, name heuristics |
| `ContextAssembler` / `TokenEstimator` | Context assembly and token estimation |
| `CatMemoryRules` / `CatMemoryRender` / `parseMemoryUpdate` | Memory merge, rendering, parsing |
| `CatReply` / `parseCatReply` | Model reply parsing |
| `ChatModels` | Domain data models |
| `TimeFormat` | Time formatting |
| `parseIpPlace` / `Place` | IP response parsing |
| `AmbientContext` | "Right now" background block |

The Android adapters are: `ChatLogStore`, `CatMemoryStore` (files + a `Context` constructor),
`SettingsStore` (DataStore), `IpLocationSource` (OkHttp), `CatChatViewModel` (`AndroidViewModel`),
and all Compose UI.

### 1.3 State inventory

Every state flow exposed by `CatChatViewModel`:

| StateFlow | Type | Meaning |
| --- | --- | --- |
| `messages` | `List<StoredMessage>` | Current conversation (most recent 400 messages) |
| `memory` | `CatMemory` | Fact list plus extraction cursor |
| `memoryStatus` | `MemoryStatus` | Running / last run time / last error |
| `contextPlan` | `ContextPlan?` | The context actually assembled for the last request |
| `mood` | `CatMood` | Current mood |
| `animation` | `CatAnimation` | Current one-shot animation |
| `busy` | `Boolean` | Whether a model reply is pending |
| `config` | `ApiConfig` | Model service configuration |
| `persona` | `CatPersona` | Cat persona |
| `locationEnabled` | `Boolean` | Whether IP geolocation is allowed |

---

## 2. Full lifecycle of one message

1. The user types. `onInputChanged` moves the mood to `LISTENING` while the field is non-empty and back to
   `IDLE` when cleared; it does not override the mood while `busy`.
2. `send(text)`: after `trim`, a `StoredMessage` is created with `seq = nextSeq++` and `role = user`,
   appended to `messages`, and persisted as JSONL. If the location toggle is on and the cache is stale, a
   background refresh is triggered.
3. `busy = true`, mood moves to `THINKING`.
4. `ContextAssembler.assemble(...)` builds the request: system = persona + memory block + "right now" block,
   with history trimmed to the token budget. The result is stored in `contextPlan` for the memory screen.
5. `ApiClient.chat(config, plan.messages)` calls `POST {Base}/chat/completions`.
6. `parseCatReply(raw.text)` parses the reply into text, an optional mood, and an optional animation.
   Any parse failure falls back to plain text.
7. The assistant message is appended and persisted. The mood is set with `autoReset = true` and returns to
   `IDLE` after 4 seconds; the animation comes from the model, or from `CatAnimation.defaultFor(mood)`
   when unspecified.
8. On error, an assistant message with `localError = true` is appended (shown in red, but it **never enters
   the request or memory**), the mood moves to `SAD`, and `SHAKE` plays.
9. `busy = false` in `finally`.
10. `maybeExtractMemory()`: if at least 6 non-error messages have accumulated past the extraction cursor, a
    background memory extraction starts.

Clearing chat history resets `nextSeq` to 1, clears `contextPlan`, and resets the memory's
`lastExtractedSeq` to zero; otherwise new `seq` values would look "already extracted" to the old cursor.
An empty conversation gets a greeting.

---

## 3. Domain models

### 3.1 Messages

| Type | Fields | Notes |
| --- | --- | --- |
| `StoredMessage` | `seq` / `role` / `content` / `createdAt` / `localError` | Persisted message; `seq` is both the ordering key and a stable id |
| `ChatMessage` | `role` / `content` | The form sent to the provider; metadata never leaves |

`StoredMessage.toWire()` is the single conversion point; `localError`, `seq`, and `createdAt` never reach
the request body. The role constants are `"user"` / `"assistant"`.

### 3.2 Mood and animation

`CatMood`: `IDLE`, `LISTENING`, `THINKING`, `HAPPY`, `SAD`, `EXCITED`, `SLEEPY`.
`CatAnimation`: `NONE`, `BLINK(260ms)`, `LOOK_AROUND(2400ms)`, `TAIL_WAG(1600ms)`, `BOUNCE(700ms)`,
`SHAKE(800ms)`, `YAWN(1900ms)`.

The two are fully decoupled: the same mood can pair with different animations. `defaultFor(mood)` supplies
an animation when the model gives none (`HAPPY→TAIL_WAG`, `EXCITED→BOUNCE`, `SLEEPY→YAWN`, otherwise `NONE`).
The ViewModel clears `animation` back to `NONE` after the animation duration plus a 200 ms buffer, so the
same animation can be triggered again.

### 3.3 Configuration and parameters

`ApiConfig` holds `providerId`, `baseUrl`, `apiKey`, `model`, `temperature`, `topP`, `maxTokens`
(0 = unlimited), `thinking`, and `reasoningEffort`.

`ApiConfig.resolvedFor(spec)` is the single convergence point from configured values to sent values:

- `temperature` / `topP`: `null` (not sent) when the model does not support them, otherwise `snap`ped;
- `maxTokens`: `null` when ≤ 0, otherwise `snap`ped and rounded to an integer;
- `thinking`: kept only for toggle-style models, forced to `AUTO` otherwise;
- `reasoningEffort`: kept only for `Effort`-style models at a supported, non-`OFF` level, else `OFF`.

`ReasoningEffort` wire values: `OFF → null`, `LOW → "low"`, `MEDIUM → "medium"`, `HIGH → "high"`.

---

## 4. Persona (`CatPersona.kt`)

`CatPersona` is the only source of the system prompt. Fields: `name`, `traits: Set<CatTrait>`,
`speechStyle`, `flavor`, `notes`.

| Enum | Values |
| --- | --- |
| `CatTrait` (multi-select, ≤3) | gentle, playful, aloof, clingy, witty, calm, curious, lazy |
| `CatSpeechStyle` (single) | casual, concise, sweet, literary, energetic |
| `CatFlavor` (single) | like a friend / occasional meow / strong cat flavor |

Defaults: name「猫猫」, traits `{gentle, playful}`, style `casual`, flavor `occasional meow`.

`systemPrompt()` assembles in this order: identity line → traits section (omitted when empty, in enum
declaration order) → speech style → cat flavor (including "do not overuse kaomoji") → JSON reply contract →
comfort principle → extra notes (only when non-empty). The `emotion` and `animation` values in the JSON
contract are exactly the inputs to the `CatReply` lookup tables.

`welcome()` varies with flavor: the `HUMAN` greeting contains no "meow", the others do. `displayName()`
falls back to the default name when the name is blank, and every UI location reads the name through it.

Persistence encoding: traits are written as comma-separated enum names in declaration order
(`encodeTraits`). `parseTraits` distinguishes two cases — `null` means never stored, so fall back to
defaults; an empty string means "none selected"; unrecognized names are dropped silently.

---

## 5. Model catalog (`ModelCatalog.kt`)

### 5.1 Data structures

- `NumberParam(min, max, step, default, decimals)`: a numeric parameter range. `sliderSteps` gives the
  slider detents; `snap` clamps and rounds; `format` renders for display; `jsonNumber` rounds on `Double`
  so `0.8f` is not emitted as `0.800000011920929` and rejected by a provider.
- `ReasoningSpec`: `Unsupported` (no switch), `AlwaysOn` (always thinking), `Toggle(defaultOn)` (via
  `thinking.type`), `Effort(supported)` (via `reasoning_effort`).
- `ModelSpec`: `temperature` / `topP` / `maxTokens` being `null` means the model does not accept the field —
  neither rendered nor sent. `supportedParamNames` and `unsupportedSamplingParams` are derived from those
  three for the settings screen. `contextWindow` defaults to a value parsed from the model name.
- `ProviderSpec`: `baseUrl`, `keyHint`, `models`, `authRequired`, `modelsPath` (`null` = no model-list
  endpoint), `chatPath`, `note`. `defaultModel` is the first entry of `models`.

### 5.2 Built-in providers (`providers` order is settings-screen order)

Zhipu GLM → DeepSeek → Z.AI (GLM international) → OpenAI → Moonshot / Kimi → DashScope (Qwen) →
SiliconFlow → OpenRouter → local Ollama → Custom (`CUSTOM_PROVIDER_ID = "custom"`).

`providerIdForBaseUrl(url)` reverse-looks-up a preset from the trailing-slash-stripped Base URL; the
settings screen uses it to switch providers when the user edits the address, falling back to `custom`.

### 5.3 Capability resolution order (`resolve`)

1. **Exact built-in table** `MODEL_SPECS`, keyed by `(providerId, modelId)`. The GLM table is registered
   for both `zhipu` and `zai`.
2. **Name heuristics** `genericSpec`, matched in order:
   - `^(o[1-9](-|$)|gpt-5)` → `Effort(LOW/MEDIUM/HIGH)`;
   - `reasoner|reasoning|thinking|(^|[-_/])r1([-_/]|$)|z1` → `AlwaysOn`;
   - `glm-4\.[5-9]` → `Toggle(defaultOn = true)`;
   - otherwise `Unsupported`.
3. **Generic fallback**: `AlwaysOn` models send no `temperature` / `top_p`; the rest get `temperature`
   (max 1.0 for a `glm` prefix or the `moonshot` provider, 2.0 otherwise), `top_p`, and `max_tokens`
   (default 8192, step 512), labeled "no built-in capability table for this model".

### 5.4 Context window

`defaultContextWindow(modelId)`: `Nk` in the name → ×1024, `Nm` → ×1024×1024, otherwise 32768.
Explicit values in the built-in table override it: all GLM at 128000 (`glm-4-long` at 1000000),
`deepseek-chat` / `deepseek-reasoner` at 64000, `gpt-4o*` at 128000, `gpt-4.1*` at 1000000,
`o4-mini` at 200000.

### 5.5 Reasoning fields in the request

| `ReasoningSpec` | What appears in the request |
| --- | --- |
| `Toggle` + `ThinkingMode.ON` | `"thinking": {"type": "enabled"}` |
| `Toggle` + `ThinkingMode.OFF` | `"thinking": {"type": "disabled"}` |
| `Toggle` + `ThinkingMode.AUTO` | Nothing |
| `Effort` + a non-OFF level | `"reasoning_effort": "low" / "medium" / "high"` |
| `AlwaysOn` / `Unsupported` | No reasoning field at all |

---

## 6. API client (`ApiClient.kt`)

### 6.1 Requests

- `chat(config, messages)`: `POST {Base}{chatPath}`, returning `ChatCompletion(text, reasoning, totalTokens)`.
- `test(config)`: sends a very short request through the **exact same** `buildPayload` as chat
  (`"只回复两个字：在呢"`), returning `TestOutcome`: `latencyMillis`, `endpoint`, `model`, `reply`,
  `reasoningChars`, `sentParams`, `skippedParams`, `totalTokens`. A passing test means the address, key,
  model, and sampling parameters all work together.
- `listModels(config)`: `GET {Base}{modelsPath}`, returning `Available(models)` or `NotSupported`.

`buildPayload` writes only values that `resolvedFor(spec)` leaves non-null; an empty `model` throws
`ApiException`. The authorization header is sent only when `apiKey` is non-blank.

Timeouts: 20 s connect, 30 s write, 90 s read.

### 6.2 Response parsing and tolerance

`parseCompletion` requires a non-empty `choices` array and a `choices[0].message`. The text comes from
`content`; the reasoning content comes from `reasoning_content`, falling back to `reasoning`. If both are
empty it reports "the model returned no content"; if `content` is empty but `reasoning` is not, the
reasoning text is used as a fallback. `total_tokens` is returned only when > 0.

`listModels` treats 404 / 405 as "provider does not support this" (not an error), reports
"no data array in the response" when the body is not JSON, and sorts model ids after dropping blanks.

### 6.3 Error mapping

`httpFailure` maps status codes to Chinese hints:

| Status | Hint |
| --- | --- |
| 400 / 422 | Request parameters rejected; try restoring defaults or lowering max_tokens |
| 401 / 403 | Invalid API key, or no permission for this model |
| 404 | Endpoint address or model name does not exist |
| 429 | Rate limited or out of quota |
| 5xx | Service temporarily unavailable, try later |

Error bodies are handled in order: `{"error":{"message"|"code"}}` → `{"message"}` → the first 200
characters of the raw body. Network-layer `IOException`s become "network request failed…". Every
user-facing error is an `ApiException` whose `message` is directly displayable.

---

## 7. Context assembly (`ContextAssembler.kt`)

### 7.1 Token estimation

`TokenEstimator` deliberately avoids a tokenizer and only guarantees "does not exceed budget", so it
overestimates:

- Per character: CJK, kana, Hangul, full-width punctuation, and similar wide characters cost 1 token;
  everything else costs 1 token per 4 characters (rounded up).
- Each message adds `MESSAGE_OVERHEAD = 4`.
- Real usage is calibrated by the server's `usage` (shown by **Test connection**).

### 7.2 Input budget

```
requested = maxTokens > 0 ? snap(maxTokens) : 0
reserve   = min(max(requested, MIN_REPLY_RESERVE=1024), contextWindow / 2)
budget    = max(contextWindow - reserve - SAFETY_TOKENS=512, MIN_INPUT_BUDGET=1024)
```

The reply reserve is capped at half the window so that a `max_tokens` larger than the window cannot
produce a negative budget.

### 7.3 Assembly algorithm

1. system = `systemPrompt.trimEnd()` plus the non-blank memory block plus the non-blank background block,
   joined by blank lines. **The order is fixed stable → volatile**, so provider prompt caching can reuse
   as much of the prefix as possible.
2. Messages with `localError` are filtered out.
3. `buildUnits` groups by turn: a `user` message or an empty list starts a new turn; other messages join
   the current turn.
4. Each turn's token cost is computed.
5. Packing walks backwards from the **last turn**: the last turn is kept unconditionally (better to let the
   server report an over-long context than to send a request with only a system message), then
   `while (used + costs[index] <= budget - systemTokens)` continues backwards.
6. `dropWhile { role != user }` removes a leading assistant message (such as the greeting), so a request
   never begins with an assistant message.
7. The output is `[system] + kept`, reporting `keptMessages`, `droppedMessages`, `estimatedTokens`, and
   `inputBudget`.

`droppedMessages` is "sendable messages − messages actually sent", so a dropped greeting counts.

---

## 8. Chat log (`ChatLogStore.kt`)

- File: `filesDir/chat/chat_log.jsonl`, one message per line.
- Not DataStore / Room because: append cost is independent of history length, a crash damages at most the
  last line, and it avoids pulling in Room/KSP.
- The cost is weak querying, so only two reads exist:
  - `tail(limit)`: the last N entries (UI);
  - `readAfter(seq, limit)`: everything after `seq` (memory extraction).
- `append` / `clear` run on `Dispatchers.IO`.
- `tail` reads backwards from the end of the file in 8192-byte chunks, stopping once it has `limit + 1`
  newlines, so the cost depends only on the size of those N entries. Bytes must be concatenated before
  UTF-8 decoding — one Chinese character is 3 bytes and can straddle two chunks, and decoding per chunk
  would produce replacement characters at the boundary. If the read did not reach the start of the file,
  the first line is a truncated fragment and is dropped.
- `readAfter` scans forward, skipping corrupt lines.
- Each line is parsed by `StoredMessage.fromJson`; an empty `role` or `content`, or invalid JSON, returns
  `null`.

---

## 9. Structured memory

### 9.1 Models and categories (`CatMemory.kt`)

The five `MemoryCategory` values are fixed: owner / preference / relationship / experience / situation.
`fromName` accepts both the enum name (case-insensitively) and the Chinese label, falling back to
`SITUATION` rather than dropping the fact.

`MemoryFact`: `category`, `key`, `value`, `updatedAt`, `sourceSeq`, `pinned`. `key` is the stable identity
of one fact and the deduplication key.

`CatMemory`: `facts` plus `lastExtractedSeq` (the extraction cursor) and `lastExtractedAt`.

### 9.2 Merge rules (`CatMemoryRules`)

Caps: `MAX_FACTS = 60`, `MAX_KEY_CHARS = 12`, `MAX_VALUE_CHARS = 60`.

`merge(existing, incoming, forget, now)`:

1. Keep every `existing` entry not in `forget`; **pinned entries are immune to `forget`**.
2. For each `incoming` entry, `sanitized` cleans it (key truncated to 12 chars, value with newlines turned
   into spaces and truncated to 60 chars, dropped entirely when empty), then look it up by `key`:
   - absent → append;
   - present with identical `category` and `value` → keep the old entry **as-is**, so a repeated
     extraction does not refresh `updatedAt` and let an old fact stay newest forever, escaping eviction;
   - present but changed → take the new value, inherit the old `pinned`, and set `updatedAt = now`
     (never trusting the timestamp the model supplied).
3. `evict`: over the cap, entries are removed in ascending `updatedAt` order among unpinned facts. If every
   entry is pinned, the cap is exceeded rather than silently discarding what the user explicitly kept.

`upsert` (manual add/edit in the memory screen), `remove`, and `togglePin` are the direct user operations;
they also pass through `sanitized` and `evict`.

### 9.3 Rendering (`CatMemoryRender.block`)

Empty memory returns an empty string. Otherwise it emits "【你记得的事】", groups by `MemoryCategory`
declaration order, writes `- key：value` per line, and closes with "use them naturally, do not recite them
one by one, and do not say 'according to my memory'". This text is appended to the system prompt **after
the persona and before the background block**.

### 9.4 Parsing (`parseMemoryUpdate`)

It accepts plain JSON, a ``` fence, or JSON surrounded by prose. Any failure returns `null` and the caller
keeps the old memory — a half-parsed result never overwrites. An unrecognized category in `facts` becomes
situation; entries with an empty key or value are dropped; `forget` becomes a string set.

### 9.5 Persistence (`CatMemoryStore.kt`)

File `filesDir/chat/cat_memory.json`, structured as:

```json
{
  "version": 1,
  "lastExtractedSeq": 42,
  "lastExtractedAt": 1700000000000,
  "facts": [
    {"category": "OWNER", "key": "名字", "value": "小明", "at": 1700000000000, "seq": 41, "pinned": true}
  ]
}
```

Writes go to a `*.tmp` file first and then `renameTo`: a rename is atomic, so a process killed mid-write
leaves no half-written JSON. On file systems where the rename fails, it falls back to a direct write so
data is at least not lost. A corrupt file on read becomes empty memory rather than breaking chat.

### 9.6 Extractor (`MemoryExtractor.kt`)

It reuses the configured model and parameters, changing only the prompt. The system prompt requires:
record only durable facts; do not record one-off pleasantries, momentary feelings, or inferences; keys are
2–6 Chinese characters and must reuse an existing key for the same fact; values are at most 40 characters
and state only the conclusion; only confirmed-invalid facts go into `forget`; output JSON only. The user
prompt supplies the current time, existing memory, and the recent conversation in that order.

Triggers: `maybeExtractMemory` runs when "at least `MEMORY_BATCH = 6` non-error messages past the cursor"
have accumulated; the memory screen's "extract now" triggers it manually. Each run reads at most
`MEMORY_WINDOW = 40` messages. On success the cursor advances to `recent.last().seq`; on failure the cursor
**does not advance**, the error is recorded in `memoryStatus.lastError`, chat is unaffected, and the same
batch is retried automatically next time.

---

## 10. Time, location, and the background block

### 10.1 Time formatting (`TimeFormat.kt`)

- `formatMessageTime`: `HH:mm` today, `昨天 HH:mm` yesterday, `MM-dd HH:mm` older. Determining "which day"
  in the local timezone uses `floorDiv(epochMillis + zoneOffset, 86400000)` rather than a raw duration
  division, which would be wrong at timezone boundaries.
- `formatMoment`: `yyyy-MM-dd HH:mm EEEE`, Chinese locale, for the model.
- `formatElapsed`: `刚刚` / `N 分钟` / `N 小时` / `N 天`, with negatives clamped to 0.

### 10.2 Location contract (`Location.kt`)

`Place(city, region, country, fetchedAt)`, where `display` falls back city → region → country and `isEmpty`
means all three are blank.

The `LocationSource` interface has three methods: `cached()` (no network request; the send path only reads
this), `isFresh(now)`, and `refresh(now)` (keeps the old result on failure, never throws). Adding system
location means writing one more implementation.

### 10.3 IP geolocation (`IpLocationSource.kt`)

Tried in order, first to return a city wins:

1. `http://ip-api.com/json/?lang=zh-CN&...` — the only one returning Chinese place names, but its free tier
   is http only;
2. `https://ipwho.is/` — https, generous free quota;
3. `https://ipapi.co/json/` — https, often rate-limited on shared egress IPs, so it comes last.

TTL is 30 minutes; timeouts are 5 s connect, 5 s read, 8 s overall. If all fail, the previous result is
kept (a stale city name beats "unknown"). `parseIpPlace` tolerates both providers' field names
(`regionName`/`region`, `country`/`country_name`) and rejects `status == "fail"`, `error == true`, and
non-JSON responses.

### 10.4 Background block (`AmbientContext.block`)

```
【此刻】
- 现在：<formatMoment>
- 距离上一条消息：<formatElapsed>        // only when known
- 主人大致在：<place.display>（按网络 IP 推测，只到城市，可能不准）
```

It ends with "this is only background; do not recite these lines and do not pretend to know a specific
address". Time and gap are always included; the city appears only when the location toggle is on and the
cache holds a result.

---

## 11. Settings persistence (`SettingsStore.kt`)

DataStore Preferences under the name `cat_settings`. Keys:

| Key | Type | Default / fallback |
| --- | --- | --- |
| `base_url` / `api_key` / `model` | String | `ApiConfig` defaults |
| `temperature` / `top_p` | Float | `ApiConfig` defaults |
| `max_tokens` | Int | `ApiConfig` default |
| `thinking` | String | Enum name; `AUTO` when absent |
| `reasoning_effort` | String | Enum name; `OFF` when absent |
| `provider_id` | String | Reverse-looked-up from Base URL when absent, then `custom` |
| `cat_name` / `cat_traits` / `cat_speech_style` / `cat_flavor` / `cat_notes` | String | `CatPersona` defaults |
| `location_enabled` | Boolean | `true` |

Saves `trim` values and strip a trailing slash from Base URLs. Reads use `Flow`, and the ViewModel collects
each into its state flow in `init`.

---

## 12. UI layer

### 12.1 Screen switching

`CatChatScreen` switches screens with two `remember`ed booleans (`showSettings` / `showMemory`), returning
early on a match. The project pulls in no navigation library; a new screen follows the same pattern or
comes with a navigation migration.

### 12.2 Chat screen (`CatChatScreen.kt`)

- Header: name, current mood label, `provider · model`, the memory button (heart, with a count), and the
  settings button.
- Message list: a `LazyColumn` keyed by `msg.seq`; time is shown only on the first message, when the
  speaker changes, or when the gap is ≥ 5 minutes. User bubbles sit right in the primary color; cat bubbles
  sit left with a small cat avatar; `localError` uses the error color.
- A three-dot `ThinkingBubble` is appended while awaiting a reply.
- Input bar: multiline (≤ 5 lines) with an IME send action; the send button is disabled when the input is
  empty or `busy`.
- The cat canvas is currently not shown; a comment marks where to restore it.

### 12.3 Settings screen (`SettingsScreen.kt`)

Top to bottom: cat persona → model service → connection → generation parameters → location → save.
All inputs are local `remember`ed drafts; only pressing **Save** writes back to the ViewModel.

- Switching provider applies that provider's default Base URL and model and restores default sampling
  parameters.
- Switching model only **converges**: unsupported parameters reset and out-of-range values clamp.
- Model list: after the address and key are filled in, it auto-fetches once after a 700 ms delay, and can
  be refreshed manually. Providers without `/models` or returning 404/405 fall back to built-in presets.
- Capability card: shows the current model's tunable parameters, context window, and note.
- Test connection: sends a real request with the current draft parameters, and on success shows latency,
  endpoint, reply, reasoning character count, sent/skipped parameters, and tokens.
- Generation-parameter sliders take their range, detents, and display precision entirely from `ModelSpec`.
- "Restore current model defaults" resets the draft to that model's defaults.

### 12.4 Memory screen (`CatMemoryScreen.kt`)

A status card (running / last run / error / "extract now" / "clear memory"), memory cards grouped by
category (pin / edit / delete), a conversation card (message count plus "clear chat history (keep memory)"),
and a "context of the last request" card (messages kept, omitted, and estimated tokens / budget).
Adding and editing share one dialog.

### 12.5 Cat rendering (`CatView.kt`)

`CatView(mood, animation)` is a self-contained component: the shape (`drawBody` / `drawHead` / `drawTail` /
facial features), the expression (`expressionFor(mood)` mapping to a `CatExpression`), the animation state
machine, and idle behavior all live inside. The caller passes only the current state. Animation values are
read inside the `Canvas` draw lambda, so they trigger redraws, not recompositions.

Idle behavior: only when `mood == IDLE` and `animation == NONE`, under `repeatOnLifecycle(RESUMED)`, a
random blink / look-around / tail-wag / yawn plays every 3–8 seconds; scheduling stops when the app goes to
the background.

`CatAvatar` reuses the same head drawing for the small message-list avatar. Replacing this with
Rive / Lottie means replacing this file wholesale; the external interface does not change.

---

## 13. Extension points

| Goal | Where to change |
| --- | --- |
| Add a provider | Add a `ProviderSpec` to `ModelCatalog.providers` |
| Add or fix a model capability | Add a `ModelSpec` to the built-in table, or adjust a heuristic in `genericSpec` |
| Add a persona setting | Add a field and enum to `CatPersona`; update `systemPrompt()` and `SettingsStore` |
| Add a memory category | Add an entry to `MemoryCategory` (prompt, grouping, and rendering follow automatically) |
| Change memory caps or cleaning | The constants and `sanitized` in `CatMemoryRules` |
| Replace cat rendering | Replace `CatView.kt` wholesale, keeping the `CatView(mood, animation)` / `CatAvatar(modifier)` signatures |
| Integrate system location | Implement `LocationSource` and swap it for `IpLocationSource` in the ViewModel |
| Replace chat-log storage | Replace `ChatLogStore` (keep `append` / `tail` / `readAfter` / `clear`) |
| Add a screen | Add a boolean-state branch in `CatChatScreen`, or introduce navigation |

---

## 14. Testing strategy

```bash
./gradlew testDebugUnitTest
```

46 cases, all plain JVM tests (no device or emulator):

| Test file | Cases | Contracts covered |
| --- | --- | --- |
| `ChatLogStoreTest` | 6 | Append/tail round trip, only newest returned, Chinese text uncorrupted across 8192-byte chunks, `readAfter` cursor, a corrupt line not affecting the rest, clear |
| `CatMemoryStoreTest` | 2 | Memory save/load round trip, corrupt file reading as empty memory |
| `CatMemoryTest` | 11 | Additive merge, same-key overwrite, unchanged fact keeps its timestamp, `forget` leaves pinned alone, over-cap eviction, key rename, over-long truncation, render grouping, three JSON shapes, parse failure returning null, unknown category fallback |
| `CatPersonaTest` | 7 | Default prompt carries name/traits/JSON contract, trait render order and empty set, extra notes, `HUMAN` has no "meow", blank-name fallback, trait storage round trip, enum lookup |
| `ContextAssemblerTest` | 10 | Starts with system and never with assistant, over-budget whole turns dropped, last turn always kept, local errors never sent, extra blocks joined only when present, stable blocks before volatile, non-negative budget, Chinese costing more than equal-length ASCII, window in the model name, ambient block counted against the budget |
| `LocationTest` | 10 | Three provider response shapes, JSON null not becoming the string "null", failures and garbage rejected, `display` fallback, ambient block carrying time/gap/city and labeling it "may be inaccurate", omitted unknowns |

`testImplementation("org.json:json:20240303")` is deliberate: unit tests run on the JVM, where the
`org.json` in `android.jar` is only a throwing stub; a real implementation is needed to test pure logic such
as memory parsing.

Not covered: Compose UI, real network requests, DataStore reads/writes in `SettingsStore`, and the actual
HTTP of `IpLocationSource`. These need on-device integration / end-to-end verification.

---

## 15. Security and privacy boundaries

- **Permissions**: only `android.permission.INTERNET`.
- **Cleartext traffic**: `network_security_config.xml`'s `base-config` permits it for every domain so local
  and LAN model services work; tighten it with per-domain `domain-config` entries.
- **Data at rest**: chat history and memory are plaintext files in the app-private directory; the API key is
  plaintext in DataStore with no extra encryption.
- **Data leaving the device**: chat content goes only to the configured Base URL; with location enabled, the
  egress IP goes to third-party geolocation services.
- **Error messages**: at most the first 200 characters of an error body are echoed, so a whole gateway HTML
  page does not end up in the UI.
- **Context isolation**: `localError` messages and the greeting never enter a request, and `StoredMessage`
  metadata never enters the request body.

---

## 16. Known technical debt

1. The cat canvas is not wired into the chat screen (see [README current shape](../README_EN.md#current-shape-chat-only)).
2. Non-streaming requests; long replies must complete first.
3. Only one provider configuration is stored; switching providers overwrites.
4. History loads only the most recent 400 messages, with no upward pagination.
5. Only UTC millisecond timestamps are stored; the timezone offset at write time is not recorded.
6. Token counts are estimates only.
7. Location is city-level and depends on third-party IP services.
8. The API key is stored in plaintext.
9. UI strings have no localization resources.
