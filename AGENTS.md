# Repository Guidelines

## Scope and Source of Truth

This file is the canonical shared instruction source for coding agents in this repository. `CLAUDE.md` is only an entry-point bridge and must not duplicate these rules.

Separate execution authority from evidence about current behavior:

- Follow applicable system/developer instructions, the user's current request and approvals, and this file's execution and safety constraints. Skills and historical plans do not create extra authority or invalidate an approval that still covers the work.
- Use the approved task PRD/design and accepted `.trellis/spec/` contracts for intended behavior and acceptance criteria. Consult `.trellis/spec/project/context.md` for project context.
- Read current production code and tests to establish what actually happens. Existing behavior or a passing test does not waive a security rule, approval requirement, or acceptance criterion; report and resolve the discrepancy within the authorized scope.
- Treat `docs/` planning material and historical notes as supporting context, not permission to override current requirements.

Do not implement from an obsolete progress table. Read the relevant Trellis context before architecture, cross-layer, security, persistence, AI, or Compose interaction changes. Keep detailed feature contracts in Trellis rather than copying them here.

Codex must perform research, implementation, verification, and review in the main session. Do not spawn or delegate to native subagents, Trellis channel workers, or agents launched through another tool or CLI. Use the inline Trellis workflow; a delegation step is not a reason to stop authorized work.

## Architecture and Ownership

This is a single-module Android app in `app/`, using Kotlin, Jetpack Compose, MVVM, repositories, and Hilt. Preserve this ownership flow:

```text
Compose UI -> Hilt ViewModel -> Repository / feature facade
                              -> Room / DataStore / encrypted credentials / Retrofit
```

- ViewModels own screen state, asynchronous work coordination, and user-facing events.
- Compose screens collect lifecycle-aware state and emit intents; keep components stateless where practical.
- Use `Channel` or `SharedFlow` for one-shot feedback instead of durable UI state.
- Keep pure normalization, parsing, identity, and policy logic JVM-testable.
- Add behavior to an existing repository/facade unless reuse or complexity proves another layer is necessary; do not introduce a generic UseCase/provider framework speculatively.
- Make coroutine ownership explicit, preserve cancellation, and never swallow `CancellationException`.
- Guard asynchronous UI results with a request snapshot, generation, or opaque request ID when selection, article, profile, editor, or navigation state can change.

## Project Invariants

- `InteractiveText` uses `BasicText`, `AnnotatedString`, and `TextLayoutResult` hit testing; do not replace the primary interaction path with `ClickableText`.
- Preserve exact source offsets in `SentenceRange`; never reconstruct ranges from joined display text.
- Keep `ReadingScreen` paragraph rendering lazy. Do not raise `MAX_IMPORT_CHARS` above `40_000` until the paragraph sentence-result reuse task and real-device long-article benchmark are both complete.
- Normalize full-article AI explanation input and reject content above `ImportBudget.MAX_FULL_EXPLANATION_CHARS` (`8_000` UTF-16 code units) with `AiError.InputTooLong` before profile, credential, cache, operation, or network access; never silently truncate AI input.
- The import module is feature-frozen; only confirmed compatibility fixes are allowed unless scope is explicitly reopened.
- Room must not use `fallbackToDestructiveMigration*`. Schema changes require a version bump, exported schema, migration, and migration coverage.
- AI callers pass `profileId`; credential resolution produces an immutable request snapshot shared by cache identity and remote execution.
- Explanation cache identity contains only normalized output-determining inputs, never profile, credential, article, or UI-offset identifiers.
- Potentially billed requests are application-scope operations. Dismissing UI detaches observers but does not implicitly cancel paid work.
- AI cache failures may fail open where the relevant contract permits, but cancellation always propagates.

## Security and Privacy

- Store API keys only through the `AiCredentialStorage` abstraction, bound to `EncryptedAiCredentialStorage` in `di/`; never place secrets in Room, DataStore, source, Git, saved Compose state, or logs.
- Do not add `HttpLoggingInterceptor` to AI networking.
- Never log prompts, request/response bodies, article or selected text, cached explanations, credentials, full configurable endpoint URLs, raw AI exception messages, or AI throwables.
- Classify AI failures from typed exceptions and their cause chain, using request-phase evidence where required. Preserve distinct connect/read/call timeout, offline/DNS, TLS, HTTP auth, rate-limit, malformed/empty-response, and explicit-cancellation semantics; never classify by localized exception-message matching.
- Remote AI DTOs and request snapshots must keep redacted `toString()` implementations: redact content, bodies, credentials, endpoints, and model IDs, and expose only safe scalar metadata or collection counts.
- AI diagnostics may contain only safe categories, status codes, elapsed time, and generated request IDs. Do not log user-defined provider/profile identities.
- Keep `usesCleartextTraffic="false"`; local HTTP/LAN/Ollama support requires an explicit product and security decision.
- Never commit `local.properties`, API keys, keystores, logs, generated build outputs, or user content.

## Kotlin and Android Conventions

Use official Kotlin style with 4-space indentation. There is no separate ktlint or detekt configuration.

- Prefer immutable state, typed domain errors, injected coroutine dispatchers, and explicit resource cleanup.
- Put user-visible strings in `strings.xml`.
- Collect flows in Compose with `collectAsStateWithLifecycle()`.
- Keep touch targets at least 48dp and provide appropriate TalkBack semantics.
- Use established suffixes: `XXXViewModel`, `XXXRepository`, `XXXDao`, `XXXEntity`, and `XXXScreen`.
- Keep package names lowercase and under `io.github.zoot.englishreader`.

## Testing and Verification

Use JUnit 4, MockK, coroutine test utilities, Turbine, MockWebServer, and Robolectric for JVM tests, including Compose UI that only needs Android resources and a theme. Reserve instrumentation for real Room, Keystore, the Hilt graph, and high-fidelity Compose behavior such as real text-layout geometry, touch bounds, and gestures. Name tests `method_condition_expectedBehavior`.

Choose verification by change scope; do not claim a gate that was not run:

- Pure logic/repository change: focused JVM tests, then `:app:testDebugUnitTest` when practical.
- Production Kotlin or Compose change: `:app:compileDebugKotlin`, focused tests, `:app:lintDebug`, and `git diff --check`.
- Instrumentation test-source change: `:app:compileDebugAndroidTestKotlin`. Connected tests are a separate acceptance step: run them only when the user asks and a device/emulator is available, and report them separately.
- Resource, manifest, or broad integration change: `:app:assembleDebug` and applicable tests.
- Release dependencies, ProGuard/R8, encrypted-storage integration, or generated keep rules: `:app:assembleRelease`; debug success is insufficient.
- Room schema change: migration tests and updated files under `app/schemas/`.

Run Gradle from the repository root with JDK 17 and Android SDK 34, using `gradlew.bat` on Windows. Run the local verification required by the matrix for the completed change; avoid unrelated broad builds. Connected device tests still require the user's request and an available device/emulator; calling a check necessary does not waive that approval requirement.

## Change Discipline

Keep changes focused, minimal, and root-cause oriented. Do not refactor unrelated code or update unrelated documentation. Preserve existing working-tree changes that are outside the requested scope.

Follow Conventional Commit style, such as `feat(ai): ...`, `fix(6.4): ...`, `test: ...`, `docs: ...`, or `chore: ...`. Include Room schema exports with database changes and report exactly which verification commands were run.
