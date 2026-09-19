# iKitty Design Document

> [简体中文](DOC.md) · [Back to README](../README_EN.md)

This is iKitty's architecture map and reference manual: module contracts, data formats, key algorithms,
extension points, and testing strategy. It is aimed at anyone modifying or extending the code. Usage,
configuration steps, and privacy notes live in the [README](../README_EN.md).

- Version: 1.0.0 · Package: `com.codingcow.ikitty`
- Sources: Android `app/src/main/java/com/codingcow/ikitty/` · shared `shared/src/commonMain/kotlin/com/codingcow/ikitty/` · iOS `iosApp/iosApp/`
- Stack: Kotlin 2.4.20, Jetpack Compose (Material3), Kotlin Multiplatform (`:shared`, with iOS targets), OkHttp 4.12.0 / Ktor 3.6.0, okio 3.18.2, kotlinx-serialization 1.11.0
- Build: AGP 8.7.3, Gradle 9.7.0, Java 17 bytecode target, minSdk 26 / targetSdk 35, iOS 17+ (Xcode 27.0)

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
    CatChatViewModel (AndroidViewModel)   ← thin shell: only the non-portable parts
        ├── ChatEngine           → all chat orchestration (see 1.1.1)
        ├── UpdateClient         → GitHub release lookup and APK download
        ├── ApkInstaller         → package/signature check before an in-place update
        └── .ikitty backup over content:// URIs
```

Dependencies always point from UI to ViewModel, and from ViewModel to storage and network.
The reverse direction is `StateFlow` only: the UI never reads or writes a file or the network directly.

### 1.1.1 Cross-platform structure (Android and iOS share `:shared`)

Everything platform-free lives in `:shared` (Kotlin Multiplatform), used by both the Android app
and the iOS app (`iosApp/`, SwiftUI):

```
CatChatViewModel (Android)              ChatView / AppModel (iOS, SwiftUI)
        └──────────────┬─────────────────────────┘
                       ▼
                  ChatEngine                ← send, streaming, persistence, memory, mood
        ┌──────────────┼───────────────┬──────────────────────┐
        ▼              ▼               ▼                      ▼
   ApiClient     ChatLogStore    CatMemoryStore      ContextAssembler / CatPersona
        │              │               │              CatMemory / AmbientContext
        ▼              │               │              ModelCatalog / StoredMessage
  HttpTransport        │               │              CatReply / JsonSupport
   ├ OkHttp (JVM/Android)              │              PromptTime (time in the prompt)
   └ Ktor Darwin (iOS)                 │              BackupArchive / ZipCodec
                            okio FileSystem            SettingsRepository
                    (androidMain / iosMain supply the root and the dispatcher)
```

Platform differences are injected as constructor parameters rather than `expect`/`actual`:
the file system, the root path, the IO dispatcher, image normalisation and the settings store
(DataStore on Android, NSUserDefaults on iOS) all come from the platform. Key names, defaults
and fallbacks are written once, in `SettingsRepository`.

Each platform keeps one non-portable tail: in-app APK updates and `content://` backup IO on
Android; CoreGraphics image normalisation and `fileImporter` / `ShareLink` on iOS.

The staged plan and its progress live in [`KMP_IOS_MIGRATION_PLAN.md`](KMP_IOS_MIGRATION_PLAN.md).

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
| `parseLatestRelease` / `compareVersions` | Release JSON parsing and version comparison |
| `BackupArchive` / `settingsToJson` / `settingsFromJson` / `parseCatMemory` | Backup archive read/write, settings serialization, memory parsing |
| `exifTransformFor` | EXIF orientation tag → rotation angle and mirroring |
| `clampPan` | Pan-range clamping for the zoomed viewer image |

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
| `updateStatus` | `UpdateStatus` | Update flow state (see [18. Software updates](#18-software-updates)) |
| `backupStatus` | `BackupStatus` | Export/import progress (see [19. Backup and restore](#19-backup-and-restore)) |

---

## 2. Full lifecycle of one message

1. The user types. `onInputChanged` moves the mood to `LISTENING` while the field is non-empty and back to
   `IDLE` when cleared; it does not override the mood while `busy`.
2. `send(text, attachments)`: after `trim`, a `StoredMessage` is created with `seq = nextSeq++` and
   `role = user`, with `images` holding the imported local file names. It is appended to `messages` and
   persisted as JSONL. Both text and images empty, or `busy`, returns immediately. If the location toggle is
   on and the cache is stale, a background refresh is triggered.
3. `busy = true`, mood moves to `THINKING`.
4. On an IO thread, images used by the history are encoded into data URLs
   (`ImageStore.dataUrls`). Then `ContextAssembler.assemble(...)` builds the request: system = persona +
   memory block + "right now" block, with history trimmed to the token budget and image names resolved
   through the resolver. The result is stored in `contextPlan` for the memory screen.
5. `ApiClient.chatStream(config, plan.messages, onDelta)` calls `POST {Base}/chat/completions` with
   `stream: true`; each delta appends to `_streamingReply`, which the UI shows as a streaming bubble.
6. After the stream ends, `parseCatReply(raw.text)` parses the complete reply into text, an optional mood,
   and an optional animation. Any parse failure falls back to plain text. `_streamingReply` is cleared, the
   assistant message is appended and persisted. The mood is set with `autoReset = true` and returns to
   `IDLE` after 4 seconds; the animation comes from the model, or from `CatAnimation.defaultFor(mood)`.
7. On error: any partial reply already streamed is first persisted as an assistant message, then a
   `localError = true` message is appended (shown in red, but it **never enters the request or memory**),
   the mood moves to `SAD`, and `SHAKE` plays.
8. `finally` clears `_streamingReply` and sets `busy = false`.
9. `maybeExtractMemory()`: if at least 6 non-error messages have accumulated past the extraction cursor, a
   background memory extraction starts.

Clearing chat history resets `nextSeq` to 1, clears `contextPlan`, and resets the memory's
`lastExtractedSeq` to zero; otherwise new `seq` values would look "already extracted" to the old cursor.
An empty conversation gets a greeting.

---

## 3. Domain models

### 3.1 Messages

| Type | Fields | Notes |
| --- | --- | --- |
| `StoredMessage` | `seq` / `role` / `content` / `createdAt` / `localError` / `images` | Persisted message; `seq` is both the ordering key and a stable id; `images` are local file names |
| `ChatMessage` | `role` / `content` / `images` | The form sent to the provider; `images` are encoded data URLs |

`StoredMessage.toWire()` is the single conversion point; `localError`, `seq`, `createdAt`, and image file
names never reach the request body — images are resolved to data URLs there
(see [17. Image storage](#17-image-storage-imagestorekt)). The role constants are `"user"` / `"assistant"`.
A message is valid as long as `content` and `images` are not both empty: image-only messages carry no text.

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

The defaults are DeepSeek's `deepseek-flash` (`providerId = "deepseek"`,
`baseUrl = https://api.deepseek.com/v1`): on a fresh install with nothing stored yet, an API key is all it
takes to start chatting. `SettingsStore` falls back to the same defaults when no preference is stored.

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
declaration order) → speech style → cat flavor (`flavor.prompt` plus the flavor-dependent `flavor.hint`)
→ JSON reply contract → comfort principle → extra notes (only when non-empty). The `hint` bullet carries the
cat-action rule: "like a friend" only says "do not overuse kaomoji" and pushes no actions, while
"occasional meow" and "strong cat flavor" explicitly suggest describing cat actions in the reply. The
`emotion` and `animation` values in the JSON contract are exactly the inputs to the `CatReply` lookup tables.

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

Zhipu GLM → Z.AI (GLM international) → DeepSeek → OpenAI → Anthropic → Google Gemini → xAI →
DashScope (Qwen) → Moonshot / Kimi → MiniMax → Doubao (Volcengine Ark) → Tencent Hunyuan →
Baidu ERNIE (Qianfan) → Mistral → SiliconFlow → OpenRouter → local Ollama → Custom
(`CUSTOM_PROVIDER_ID = "custom"`): 17 presets plus custom.

Apart from Anthropic and Google, which go through their own OpenAI compatibility layers, every provider is
a native OpenAI-compatible endpoint, so `chatPath` and `modelsPath` keep their defaults. Meta publishes
Llama only as open weights with no first-party hosted API, so the same weights are reached through
OpenRouter (`meta-llama/llama-4-maverick`), SiliconFlow, and Ollama (`llama4:maverick`) — three different
model IDs.

`providerIdForBaseUrl(url)` reverse-looks-up a preset from the trailing-slash-stripped Base URL; the
settings screen uses it to switch providers when the user edits the address, falling back to `custom`.

### 5.3 Capability resolution order (`resolve`)

1. **Exact built-in table** `MODEL_SPECS`, keyed by `(providerId, modelId)` and built through
   `builtIn(...)`. The GLM table is registered for both `zhipu` and `zai`; `temperatureMax = null` or
   `ReasoningSpec.AlwaysOn` means the model accepts neither `temperature` nor `top_p` (the GPT-5 series and
   always-thinking models).
2. **Name heuristics** `genericSpec`, matched in order:
   - `^(o[1-9](-|$)|gpt-5)` → `Effort(LOW/MEDIUM/HIGH)`;
   - `glm-[5-9]\.` → `Effort(LOW/MEDIUM/HIGH)` (GLM-5 and later use `reasoning_effort`);
   - `reasoner|reasoning|thinking|(^|[-_/])r1([-_/]|$)|z1` → `AlwaysOn`;
   - `glm-4\.[5-9]` → `Toggle(defaultOn = true)`;
   - otherwise `Unsupported`.
3. **Generic fallback**: `AlwaysOn` models send no `temperature` / `top_p`; the rest get `temperature`
   (max 1.0 for a `glm` prefix or the `moonshot` / `anthropic` providers, 2.0 otherwise), `top_p`, and
   `max_tokens` (default 8192, step 512), labeled "no built-in capability table for this model".

### 5.4 Context window

`defaultContextWindow(modelId)`: `Nk` in the name → ×1024, `Nm` → ×1024×1024, otherwise 32768.
Explicit values in the built-in table override it: GLM-5.3 / MiniMax M3 / Gemini 3 / Llama 4 Maverick at
1000000, GLM-5.3-Flash at 200000, GPT-5.x at 400000, Grok 4 / Qwen3.6 / Kimi K3 / Doubao at 256000,
Claude 4.x at 200000, and DeepSeek V4 / Hunyuan / ERNIE / Mistral at 128000.

Except for the verified 1M windows of GLM-5.3 and MiniMax M3, the new models use conservative values from
the previous generation of the same series. When a vendor changes a number, edit the `window` argument at
the corresponding `ModelCatalog.builtIn` call. Models without a built-in entry still fall back to 32768.

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
  Used by memory extraction and any scenario that needs complete JSON (non-streaming).
- `chatStream(config, messages, onDelta)`: the same address with `stream: true`, invoking `onDelta` once per
  incremental piece of text and returning the same value as `chat`, so callers need not distinguish.
  Cancelling the coroutine cancels the underlying HTTP call.
- `test(config)`: sends a very short request through the **exact same** `buildChatPayload` as chat
  (`"只回复两个字：在呢"`), returning `TestOutcome`: `latencyMillis`, `endpoint`, `model`, `reply`,
  `reasoningChars`, `sentParams`, `skippedParams`, `totalTokens`. A passing test means the address, key,
  model, and sampling parameters all work together.
- `listModels(config)`: `GET {Base}{modelsPath}`, returning `Available(models)` or `NotSupported`.

`buildChatPayload` writes only values that `resolvedFor(spec)` leaves non-null; an empty `model` throws
`ApiException`. The authorization header is sent only when `apiKey` is non-blank.

The `content` value comes from `chatContent`: a plain string when there are no images (byte-for-byte what it
was before image support), and an OpenAI-compatible content array when there are images — a text part
`{"type":"text"}` plus one `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}` part per
image. An image-only message writes no empty text part, so providers that reject empty text blocks still
work. No provider-specific field appears here; whether images are understood depends on the chosen model.

Timeouts: 20 s connect, 30 s write, 90 s read.

### 6.2 Response parsing and tolerance

`parseCompletion` requires a non-empty `choices` array and a `choices[0].message`. The text comes from
`content`; the reasoning content comes from `reasoning_content`, falling back to `reasoning`. If both are
empty it reports "the model returned no content"; if `content` is empty but `reasoning` is not, the
reasoning text is used as a fallback. `total_tokens` is returned only when > 0.

Streaming reads (`executeStream`): SSE is read line by line; only lines starting with `data:` are handled,
`data: [DONE]` ends the stream, and any line that does not parse as JSON is skipped so an occasional
heartbeat cannot break the reply. Incremental text comes from `choices[0].delta.content` and thinking from
`delta.reasoning_content` (falling back to `reasoning`); both accumulate and converge by the non-streaming
rules. If a provider ignores `stream` and returns an ordinary JSON body (no `data:` lines at all), the
buffered text goes through `parseCompletion`, so providers without streaming still work. Cancelling the
request makes the blocking read throw `IOException`; when the coroutine is already cancelled this is
re-thrown as `CancellationException` so a deliberate cancellation is not shown as a network error.

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
- Each image adds a fixed `IMAGE_TOKENS = 1100`. The server computes real image usage from resolution;
  this high fixed value only exists to keep assembly from overshooting the budget.
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
4. Each turn's token cost is computed, counting images by the piece.
5. Packing walks backwards from the **last turn**: the last turn is kept unconditionally (better to let the
   server report an over-long context than to send a request with only a system message), then
   `while (used + costs[index] <= budget - systemTokens)` continues backwards.
6. `dropWhile { role != user }` removes a leading assistant message (such as the greeting), so a request
   never begins with an assistant message.
7. The output is `[system] + kept`, where each kept message goes through `toWire(imageUrl)` to resolve image
   names into data URLs (unresolvable images are skipped, so deleting an image file never makes a historical
   message unsendable), reporting `keptMessages`, `droppedMessages`, `estimatedTokens`, and `inputBudget`.

`droppedMessages` is "sendable messages − messages actually sent", so a dropped greeting counts.

---

## 8. Chat log (`ChatLogStore.kt`)

- File: `filesDir/chat/chat_log.jsonl`, one message per line.
- Each line is `{"seq","role","content","at","error"?,"images"?}`; `images` is an array of local image file
  names and is omitted for text-only messages. An image-only message has an empty `content` and is still valid.
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
  sit left with a small cat avatar; `localError` uses the error color. Messages with images render thumbnails
  inside the bubble as a `FlowRow` two per row; image-only messages render no empty text. Tapping a thumbnail
  opens the full-screen viewer (`ImagePreviewDialog`, see [20. Full-screen viewer](#20-full-screen-viewer-imageviewerkt)).
- A three-dot `ThinkingBubble` is appended while awaiting a reply; once streaming starts it is replaced by a
  `StreamingBubble` showing the current `streamingReply`.
- Input bar: a leading "+" opens "choose from gallery / take photo", and a multiline field (≤ 5 lines, IME
  send action) sits in the middle. Chosen images appear as a horizontally scrollable thumbnail strip, each
  with its own delete button. The send button is disabled when there is no text and no image, or while `busy`.
- The gallery uses `PickMultipleVisualMedia` (up to 9; older devices fall back to the system file picker);
  the camera uses `TakePicture` with a target URI generated by `ImageStore.newCameraTarget()` through
  FileProvider, and `finishCamera` adopts or deletes it on return.
- Both launchers, like the other `remember` calls, must be invoked before the early `return`s; otherwise
  navigating to settings and back would shift their positions.
- Auto-scroll: `animateScrollToItem` for new messages, switching to `scrollToItem` during streaming so each
  delta does not restart the animation.
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
- "Backup and restore": exports/imports `.ikitty`, each action behind its own confirmation dialog
  (see [19. Backup and restore](#19-backup-and-restore)). File selection uses
  `rememberLauncherForActivityResult` + SAF (`CreateDocument` / `OpenDocument`), so the app requests no
  storage permission; unpacking, validation, and writing all live in the ViewModel.
- The "Software update" section at the bottom drives three phases — check, download, install
  (see [18. Software updates](#18-software-updates)). The install step lives in the UI because it needs an
  Activity `Context` to launch system screens; checking and downloading stay in the ViewModel.

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
| Change image compression or storage location | Change only `ImageStore`'s import pipeline and constants |
| Change how images look in the UI | Change only `ChatImage`, `ImageViewer`, and the bubble/attachment strip in `CatChatScreen` |
| Change how historical images are carried | Change only `ContextAssembler`'s `imageUrl` resolver and image cost |
| Change the update source | Change `RELEASE_API_URL` and `parseLatestRelease` in `UpdateModels.kt` |
| Change update download/verification/install | `UpdateClient` (download) / `ApkInstaller` (check and install) / the ViewModel's `UPDATE_DIR` |
| Change what a backup contains | The entry constants, `manifestJson`, and `settingsToJson` / `settingsFromJson` in `BackupArchive`; bump `FORMAT_VERSION` when the format breaks compatibility |
| Add a screen | Add a boolean-state branch in `CatChatScreen`, or introduce navigation |

---

## 14. Testing strategy

```bash
./gradlew testDebugUnitTest
```

101 cases, all plain JVM tests (no device or emulator):

| Test file | Cases | Contracts covered |
| --- | --- | --- |
| `ChatLogStoreTest` | 6 | Append/tail round trip, only newest returned, Chinese text uncorrupted across 8192-byte chunks, `readAfter` cursor, a corrupt line not affecting the rest, clear |
| `CatMemoryStoreTest` | 2 | Memory save/load round trip, corrupt file reading as empty memory |
| `CatMemoryTest` | 11 | Additive merge, same-key overwrite, unchanged fact keeps its timestamp, `forget` leaves pinned alone, over-cap eviction, key rename, over-long truncation, render grouping, three JSON shapes, parse failure returning null, unknown category fallback |
| `CatPersonaTest` | 8 | Default prompt carries name/traits/JSON contract, trait render order and empty set, extra notes, `HUMAN` has no "meow", only non-"like a friend" flavors suggesting cat actions, blank-name fallback, trait storage round trip, enum lookup |
| `StoredMessageTest` | 7 | Image message JSON round trip, image-only message valid, text-only omits `images`, neither text nor images rejected, blank image names dropped, `toWire` resolving and skipping missing images, plain-text wire carrying no images |
| `MultimodalPayloadTest` | 5 | Plain text stays a string content with no `stream`, images become `image_url` content parts, image-only writes no empty text part, streaming only adds `stream`, sent params still follow the capability table |
| `ContextAssemblerTest` | 13 | Starts with system and never with assistant, over-budget whole turns dropped, last turn always kept, local errors never sent, extra blocks joined only when present, stable blocks before volatile, non-negative budget, Chinese costing more than equal-length ASCII, window in the model name, ambient block counted against the budget, fixed per-image cost, images resolved into the wire, images eating history budget |
| `ImageStoreTest` | 6 | Sampling ratio within the limit, sampling by the longer edge, sampled dimensions never exceeding the limit, MIME fallback for data URLs, EXIF orientation values 1–8 mapped to the right rotation/mirroring, unknown orientations left untouched |
| `ImageViewerTest` | 3 | Pan ignored while not zoomed, pan clamped to the zoomed overflow, clamping growing with the zoom level |
| `LocationTest` | 10 | Three provider response shapes, JSON null not becoming the string "null", failures and garbage rejected, `display` fallback, ambient block carrying time/gap/city and labeling it "may be inaccurate", omitted unknowns |
| `UpdateModelsTest` | 9 | Release JSON parsing version/notes/APK URL/size/published time, preferring the version-named APK among several, no APK returning null, prerelease not treated as an update, non-JSON returning null, missing tag returning null, version comparison newer/equal/older, prerelease older, `v` prefix normalization |
| `ModelCatalogTest` | 10 | Every preset's default model hits its own built-in table, the preset list covers every vendor, new presets have a Base URL and reverse-look-up to themselves, GLM-5.3 uses `reasoning_effort` with a 1M window, the `glm-5` name heuristic, GPT-5.5 sends no sampling parameters, Meta Llama reaches aggregators and local runtimes, legacy models still resolve through the generic fallback, the default model is deepseek-flash, and deepseek-flash exposes adjustable reasoning depth |
| `BackupArchiveTest` | 11 | Export/import round trip restoring messages, memory, images, and settings; overwriting deleting old images and memory; non-zip and missing-manifest rejected; too-new format version rejected; out-of-bounds image entries ignored; an empty backup clearing local history; a corrupt chat log refusing to overwrite; full settings serialization round trip; missing fields falling back to defaults; memory parsing tolerating garbage; the default file name carrying the extension |

`testImplementation("org.json:json:20240303")` is deliberate: unit tests run on the JVM, where the
`org.json` in `android.jar` is only a throwing stub; a real implementation is needed to test pure logic such
as memory parsing.

Not covered: Compose UI, real network requests, SSE parsing, real image decoding/compression (which depends
on `BitmapFactory`), DataStore reads/writes in `SettingsStore`, the actual HTTP of `IpLocationSource`,
real GitHub requests and downloads in `UpdateClient`, and `ApkInstaller`'s signature check and system
installer hand-off. The backup tests cover the archive itself, while SAF file selection and
`ContentResolver` reads/writes likewise need on-device verification.
These need on-device integration / end-to-end verification.

---

## 15. Security and privacy boundaries

- **Permissions**: `android.permission.INTERNET` and `android.permission.REQUEST_INSTALL_PACKAGES`; the
  latter is used only to hand the official release APK to the system installer.
- **Cleartext traffic**: `network_security_config.xml`'s `base-config` permits it for every domain so local
  and LAN model services work; tighten it with per-domain `domain-config` entries.
- **Data at rest**: chat history, images, and memory are files in the app-private directory; the API key is
  plaintext in DataStore with no extra encryption.
- **Backup files**: a `.ikitty` file holds the plaintext API key and the entire conversation, so the app
  must warn before exporting. Import accepts only the format the app itself writes, and unpacking checks for
  path escapes (see [19. Backup and restore](#19-backup-and-restore)).
- **Images**: gallery images are copied into `filesDir/chat/images/`; the camera temp file is written to the
  cache directory and adopted or deleted immediately on return. The app grants only its own `FileProvider`
  URI and requests no storage or camera permission — the system camera app performs the capture.
- **Data leaving the device**: chat content goes only to the configured Base URL; with location enabled, the
  egress IP goes to third-party geolocation services; an update check only reads release metadata from
  `api.github.com` and downloads the APK, reporting no local information.
- **Error messages**: at most the first 200 characters of an error body are echoed, so a whole gateway HTML
  page does not end up in the UI.
- **Context isolation**: `localError` messages and the greeting never enter a request, and `StoredMessage`
  metadata never enters the request body.

---

## 16. Known technical debt

1. The cat canvas is not wired into the chat screen (see [README current shape](../README_EN.md#current-shape-chat-only)).
2. Only one provider configuration is stored; switching providers overwrites.
3. History loads only the most recent 400 messages, with no upward pagination.
4. Only UTC millisecond timestamps are stored; the timezone offset at write time is not recorded.
5. Token counts are estimates only.
6. Location is city-level and depends on third-party IP services.
7. The API key is stored in plaintext.
8. UI strings have no localization resources.
9. Images are downscaled to a 1280 px longest edge and re-encoded as JPEG: lossy, with transparent areas
   flattened to white, and at most 9 per message.
10. Historical images are re-encoded and re-sent every turn (`dataUrls` has only an in-memory cache), so many
    images noticeably enlarge the request body and memory pressure.
11. Images open to a full-screen in-app viewer with pinch-to-zoom, but cannot be saved to the gallery or shared.
12. The update package is checked only for length, package name, and signature, with no checksum from the
    release, and there is no background automatic update check.
13. Most context windows of the newly added models are conservative values from the previous generation
    (only GLM-5.3 and MiniMax M3 at 1M are verified), and reasoning control is declared only for GPT-5.x,
    GLM-5.3, and deepseek-flash. Both only affect parameters and budgeting, never whether a
    request can be made.
14. Backups are plaintext ZIPs with no password or encryption; import can only replace everything rather
    than restoring selected items, there is no scheduled/automatic backup, and an export carries the API key.
15. Only the most recent 400 messages are held in memory while the backup is complete: after importing a
    long backup the UI still shows only the last 400.

## 17. Image storage (`ImageStore.kt`)

Chosen or captured images are **copied into the app-private directory** before their file name is recorded,
because a gallery `content://` URI is only readable within the current process; without the copy, images in
the history would go blank after a restart.

- Directories: `filesDir/chat/images/`; camera temp files in `cacheDir/chat_camera/`.
- Import: read bounds → read the EXIF orientation → sample-decode via `sampleSizeFor` → scale to a longest
  edge of `MAX_DIMENSION = 1280` → rotate/mirror the pixels upright → flatten alpha onto white → compress to
  JPEG (quality 85) as `img_<uuid>.jpg`.
  Any step failing returns `null` so the caller skips that image instead of failing the whole send.
  Note that `decodeStream` returning `null` under `inJustDecodeBounds` is normal — the size is only written
  into the `Options`; only an unopenable stream counts as failure.
- EXIF orientation: `BitmapFactory` does **not** rotate pixels according to `TAG_ORIENTATION` — a portrait
  photo's pixels are actually landscape and the direction lives only in the tag. Re-encoding as JPEG drops
  that tag, so the pixels must be made upright during import or the thumbnail, the full-screen view, and the
  image sent to the model all lie on their side. `exifTransformFor` maps tags 1–8 to "rotate N degrees plus
  optional horizontal mirroring"; unreadable or unknown values are left untouched.
- Data URLs: `dataUrl` / `dataUrls` encode a file as `data:image/jpeg;base64,...`, cached by file name in a
  12-entry LRU (files are never rewritten, so cache entries never go stale).
- Camera: `newCameraTarget()` produces a writable URI through `FileProvider` (authority
  `${applicationId}.fileprovider`, paths configured in `res/xml/file_paths.xml`); on success `commitCamera`
  runs the same import pipeline, and on cancel the temp file is deleted.
- Thumbnails: `decodeSampledBitmap` samples to a 512 px longest edge for `ChatImage`, keeping full images out
  of memory; the full-screen view uses the same decode path at 2048
  (see [20. Full-screen viewer](#20-full-screen-viewer-imageviewerkt)).

---

## 18. Software updates

### 18.1 Source and version comparison (`UpdateModels.kt`)

- The source is fixed at `RELEASE_API_URL = https://api.github.com/repos/Lixuannan/iKitty/releases/latest`.
- `parseLatestRelease(body)` reads `tag_name` / `body` / `published_at` and `assets`:
  - a truthy `draft` / `prerelease` returns `null` immediately;
  - `normalizeVersion` strips the `v` prefix and surrounding whitespace;
  - it prefers the `.apk` whose name contains the version, then any `.apk`, and returns `null` when neither
    exists;
  - an invalid ISO-8601 `published_at` only loses the time, never the other fields.
- `compareVersions(a, b)` compares numeric segments only; when those are equal, the side with a prerelease
  suffix is older (`0.2.0-beta.1 < 0.2.0`). Unrecognized segments count as 0, so an odd version string never
  turns into an error.
- `UpdateStatus` is the UI state machine: `Idle` / `Checking` / `UpToDate` / `Available` / `Downloading` /
  `Ready` / `Failed`. A non-null `Failed.info` means "version known, download failed", which is how the UI
  offers "retry download".

### 18.2 Download (`UpdateClient.kt`)

- `fetchLatest()`: `GET releases/latest` with `Accept: application/vnd.github+json`. A `null` return means no
  downloadable APK; HTTP or network failures throw `ApiException` (403 / 429 report rate limiting, 404
  reports a missing release).
- `download(info, destination, onProgress)`: writes `<name>.part` first and then `renameTo`s it, falling back
  to a copy when the rename fails; any exception (including coroutine cancellation) deletes the `.part`, so a
  half-written APK never looks complete. Progress is reported every 64 KB, and the loop calls
  `ensureActive()` so cancellation is honored promptly. The final byte count is compared against the `size`
  the release declared, and a mismatch reports an incomplete download.
- The ViewModel owns the destination directory: `cacheDir/updates/`, cleared of other versions before a
  download starts.

### 18.3 Verification and installation (`ApkInstaller.kt`)

- `check(context, apk)` reads the downloaded package's package name and signature through
  `getPackageArchiveInfo` and compares them with the installed app, returning `COMPATIBLE` /
  `PACKAGE_MISMATCH` / `SIGNATURE_MISMATCH` / `UNREADABLE`.
- When neither side yields a signature it returns `UNREADABLE` rather than treating them as equal — that
  would silently skip the check.
- `install(context, apk)` obtains a `content://` URI through `FileProvider` (authority
  `${applicationId}.fileprovider`, paths from the `updates` entry in `res/xml/file_paths.xml`) that grants
  the installer access to that one file, then opens the system installer with `ACTION_VIEW` and
  `application/vnd.android.package-archive`.
- `canInstall` calls `canRequestPackageInstalls()`; when it is false the UI opens
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES`.

### 18.4 Why an update does not clear chat history

- An in-place update only replaces code; `filesDir`, `cacheDir`, and DataStore stay where they are, so chat
  history (`chat/chat_log.jsonl`), images, memory (`cat_memory.json`), and settings all survive.
- The app **never** uninstalls before installing and uses no install API that deletes app data.
- The download writes only to `cacheDir/updates/`, and the FileProvider grant covers that single file, so the
  update flow never touches `filesDir/chat/`.
- A signature mismatch would be rejected by the system anyway. Verifying it up front, deleting the package,
  and explaining why is what keeps users from being misled into uninstalling and reinstalling — which is what
  would actually clear chat history.

### 18.5 UI (`SettingsScreen.UpdateSection`)

`Idle` shows the current version and "check for updates"; `Available` shows the new version and the release
notes (at most 8 lines) plus "download update"; `Downloading` shows a progress bar and downloaded/total;
`Ready` shows "install update"; `Failed` offers "retry download" or "check again" depending on whether
`info` is present. The install step lives in the UI layer because it needs an Activity `Context` to launch
system screens; checking and downloading stay in the ViewModel.

---

## 19. Backup and restore

### 19.1 Container format (`BackupArchive.kt`)

The exported file has the `.ikitty` extension and is an ordinary ZIP with these entries:

| Entry | Content |
| --- | --- |
| `manifest.json` | `format = "ikitty-backup"`, `version`, `appVersion`, `exportedAt`, and the three entry counts |
| `chat/chat_log.jsonl` | JSONL identical to the on-disk format of [section 8](#8-chat-log-chatlogstorekt) |
| `chat/cat_memory.json` | Memory JSON identical to [9.5 persistence](#95-persistence-catmemorystorekt) |
| `chat/images/<name>` | The original JPEGs referenced by messages, file names unchanged |
| `settings.json` | Model configuration, persona, and the location toggle (**including the API key**) |

ZIP rather than one large JSON: the chat log is already JSONL and can be moved in and out verbatim, and
images stay as original JPEGs instead of base64 (which would add a third to the size and force everything
into memory). The JSON entries are plaintext, so any unzip tool can show the user what a backup holds.

`MIME` is `application/octet-stream`: `.ikitty` has no registered type, and declaring `application/zip`
makes some file pickers rename the file back to `.zip`.

### 19.2 Export

1. Scan the local JSONL line by line, counting parseable messages and collecting referenced image names
   (corrupt lines are skipped per [section 8](#8-chat-log-chatlogstorekt));
2. Only images that **actually exist** are written, so a name pointing at a deleted file produces no empty
   entry;
3. Settings come from the ViewModel and are the **saved** values — drafts edited in the settings screen but
   never saved are not included;
4. Returns a `BackupSummary`, which the UI turns into "exported N messages, M images, K facts".

### 19.3 Import: validate everything first, then touch local data

Import means **full replacement**, so it is deliberately two-phase:

1. `stage(input)` unpacks into `cacheDir/backup_staging/` and validates everything: the manifest exists and
   its `format` matches, `version` does not exceed the supported `FORMAT_VERSION`, `settings.json` exists,
   and a non-empty chat log must yield at least one parseable message (otherwise it counts as corrupt). Any
   failure deletes the staging directory and throws `BackupException` — **not one byte of local data is
   touched**;
2. `commit(contents)` replaces the log and memory via temp-file-plus-rename, clears
   `filesDir/chat/images/` and puts the archived images back under their original names, then deletes the
   staging directory.

Defences:

- Entry names are accepted only as bare file names under `chat/images/`; anything containing `/`, `\`, `..`,
  or a leading `.` is ignored, so zip slip cannot write outside the archive directory;
- Caps of 20000 entries, 2 GiB total uncompressed, 32 MiB per image, and 1 MiB per JSON entry make a
  malformed file fail before it fills the cache partition;
- One bad entry rejects the whole backup: there is no "import what is readable" mode, because half a backup
  is more dangerous than none.

### 19.4 Refreshing state after an import

`commit` handles files only; settings must go back into DataStore and UI state must be re-read:

- The ViewModel calls `store.save(config)` / `save(persona)` / `saveLocationEnabled` in turn;
- `reloadFromDisk()` re-reads DataStore, the memory file, and the last `LOAD_LIMIT` messages, resets
  `nextSeq`, clears `contextPlan`, and calls `images.invalidateCache()` — archived images overwrite by name
  and the cache may still hold the old encoding;
- An empty imported conversation gets a greeting, otherwise the screen would be blank.

### 19.5 UI (`SettingsScreen.BackupSection`)

- "Export backup" → confirmation (the file contains the API key) → SAF `CreateDocument` with the default
  name `iKitty-yyyyMMdd-HHmm.ikitty` (`defaultBackupFileName`);
- "Import backup" → confirmation (full replacement, irreversible) → SAF `OpenDocument` (`*/*`; `.ikitty` has
  no registered MIME type);
- `BackupStatus`'s four states (`Idle` / `Working` / `Done` / `Failed`) drive progress and result text; a
  new import cannot start while another action runs or a model reply is pending.

---

## 20. Full-screen viewer (`ImageViewer.kt`)

Tapping a thumbnail in the chat history opens `ImagePreviewDialog(name, onDismiss)` through
`CatChatScreen`'s `previewImage` state: a full-screen `Dialog` (`usePlatformDefaultWidth = false`) with a
black background and `ContentScale.Fit`, closed by the top-right button or the back key (back goes through
the `Dialog`'s `onDismissRequest`).

- Decoding: `decodeSampledBitmap(file, PREVIEW_PIXELS = 2048)`, clearer than the list's 512 px thumbnail
  while still avoiding an un-sampled 1280 px image in memory; decoding happens on the IO dispatcher with a
  spinner until it finishes.
- Three states: `Loading` / `Missing` (the file was deleted or cannot be decoded, showing "this image is
  gone") / `Ready`. `Missing` exists so the placeholder spinner cannot spin forever.
- Gestures: `rememberTransformableState` + `transformable` handle pinch-to-zoom (1–5×) and pan; panning is
  ignored while unzoomed, and once zoomed the pure function `clampPan` clamps the translation to the
  overflow so the image cannot be dragged off-screen.
- Only thumbnails inside message bubbles pass `onClick`; the pending-attachment strip does not open the viewer.
- Compatibility: the EXIF fix applies only to **newly imported** images. Portrait photos imported before the
  fix already lost their orientation tag at import time — the file itself is landscape and cannot be
  corrected during display. Backup archives carry images as-is, so already-sideways ones stay sideways.
