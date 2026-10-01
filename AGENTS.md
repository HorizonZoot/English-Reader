# Repository Guidelines

## Scope and Source of Truth

This file is the canonical shared instruction source for coding agents in this repository. `CLAUDE.md` is only an entry-point bridge and must not duplicate these rules.

Separate execution authority from evidence about current behavior:

- Follow applicable system/developer instructions, the user's current request and approvals, and this file's execution and safety constraints. Skills and historical plans do not create extra authority or invalidate an approval that still covers the work.
- Use the approved task PRD/design and accepted `.trellis/spec/` contracts for intended behavior and acceptance criteria. Consult `.trellis/spec/project/context.md` for project context.
- Read current production code and tests to establish what actually happens. Existing behavior or a passing test does not waive a security rule, approval requirement, or acceptance criterion; report and resolve the discrepancy within the authorized scope.
- Use `.trellis/backlog.md` for requirement intake and progress; `docs/` and historical notes are dated supporting context, not live plans or permission to override current requirements.

Do not implement from an obsolete progress table. Read the relevant Trellis context before architecture, cross-layer, security, persistence, AI, or Compose interaction changes. Keep detailed feature contracts in Trellis rather than copying them here.

## Requirements and Backlog Lifecycle

- `.trellis/backlog.md` is the single live entry point for requirements and their progress. Read it before planning or implementing work, reuse existing IDs, and check the linked task and relevant specs. `docs/` contains dated snapshots, not a second backlog.
- Register feature requests, bug fixes, technical improvements, and future ideas in the backlog. Keep unconfirmed risks as `待核实` and suggestions as `候选`; recording an idea does not approve its implementation. Read-only questions do not need new requirement entries.
- Each active entry records a stable ID, title/scope, source, status, suggested priority where relevant, current progress and next action, linked task(s), and last-updated date. Preserve existing IDs and never reuse archived IDs.
- Use `候选`, `待核实`, `待决策`, `待开发`, `开发中`, `受阻`, `暂缓`, `待收尾`, or `已完成` for active requirements. Update progress after clarification, task activation, a meaningful implementation or verification milestone, blocking/resumption, and before ending an unfinished work session.
- Link approved Trellis tasks from the backlog and put requirement IDs in their PRDs. Keep detailed requirements, design, execution steps, and evidence in the task artifacts; `task.json` remains the task execution status source. Synchronize the requirement summary when task status changes instead of maintaining duplicate percentages.
- Small authorized work may be tracked directly in the backlog without creating a formal task. Record its result and verification in the requirement archive. Task creation still follows the workflow and existing consent; do not create empty tasks just to record ideas.
- Once the authorized scope and applicable checks are complete, record completion evidence, archive completed linked tasks with `task.py archive`, and move the requirement row plus its scope/notes into `.trellis/backlog-archive/YYYY-MM.md` as `已归档`. Remove it from the active pool and repair task links. `task.py finish` only clears the active pointer; it is not archival.
- A requirement spanning several tasks is complete only when all in-scope deliverables are complete. Keep remaining work active. Archive cancellation or a will-not-do decision as `已取消` with the user's decision and reason, not as successful implementation.
- The user has authorized routine requirement registration, progress updates, and completed requirement/task archival as standing maintenance. Do not ask again for those actions. This does not authorize implementation outside the user's scope, Git staging/commits/pushes, deployment, or journals; honor explicit later opt-outs and preserve existing approval boundaries.
- Routine device acceptance is user-owned. Do not leave otherwise completed work open solely because an agent did not run routine device tests or create recurring device-test backlog entries. Preserve actual verification evidence and any explicit feature-specific gate; never mark unrun checks as passed.
- These are agent-maintained conventions. Existing Trellis CLI commands do not automatically synchronize the Markdown backlog; perform the updates in the same work session.

## Codex Delegation Policy

- Follow higher-priority session restrictions and `.trellis/config.yaml` (`codex.dispatch_mode`). In `inline`, perform all work in the main session. In `auto`, the main agent may delegate authorized, independent research, review, or implementation when the scope and expected output are clear and delegation saves meaningful work. Small or dependent changes stay local or run sequentially.
- Only the main agent may create native Codex subagents, with at most three concurrent children. Children must not delegate. Do not start Trellis channel workers or agents through another CLI for this workflow.
- Assign each child its task slice, relevant context, allowed files, forbidden operations, and required result. One file has one writer at a time. Preserve existing working-tree changes; never revert another worker's edits. Shared contracts, schema/build configuration, and conflict resolution stay with the main agent.
- Keep one active Trellis task while children run; children never change task pointers or status. Parallel slices share that task's approved artifacts. For work without a task, pass the request and relevant specs explicitly; do not create artifacts merely to dispatch.
- Research and review children are read-only. Implementers edit only their assigned files. Children report files changed, evidence, checks actually run, and unresolved issues; they do not stage, commit, push, merge, publish, archive, or declare the whole task complete.
- The main agent owns requirement and architecture decisions, approvals, and final acceptance. After writers finish, inspect the complete task diff and cross-layer contracts, preserve unrelated changes, and run or verify the applicable checks on the final snapshot. Serialize Gradle work in the shared workspace; child reports alone do not prove a check passed. Rerun checks invalidated by subsequent edits.
- Only the main agent performs authorized commits and task wrap-up. A child review is supplementary; it never replaces main-session review and verification.

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

## Git Branch Management

- `main` is the stable, user-accepted integration branch. Only merge changes after the applicable local checks pass and the user confirms device acceptance. Passing agent tests alone is not device acceptance. Being ready to push does not authorize a push; obtain explicit permission for remote operations.
- Keep one shared feature-development branch for all new features and their testing. Reuse the existing `feat/onboarding` branch until the user explicitly chooses another name; do not create a separate branch per feature, test pass, review, or agent.
- Fix defects discovered while developing or accepting a feature on that same feature branch. Use the shared `fix` branch only for independent repairs to already accepted functionality in `main`, not for feature-test fixes. Create it only when needed for authorized repair work, and apply the same verification and user device-acceptance gate before merging.
- Unless the user explicitly authorizes an exception, the only branch roles are `main`, the shared `fix`, and the single shared feature branch. Do not create additional branches (including automatic agent/worktree branches). Do not rename or delete legacy branches without permission.
- Branches do not require separate Android Studio projects or worktrees. Prefer switching branches in one clean working directory; do not automatically create new worktrees. Never discard, silently stash, or commit unrelated working-tree changes to make switching possible. If an existing worktree owns a branch, explain that constraint and agree on consolidation before removing or relocating it.
- Before merging, inspect both histories, the complete incoming commit/file set, and local changes. If a feature branch includes other unmerged work beyond the user's approved scope, obtain confirmation instead of silently merging it. Resolve conflicts by preserving both intended behaviors, not by blindly choosing ours/theirs; request a decision for ambiguous semantics.
- After conflict resolution, inspect the integrated diff and run the applicable checks on the final snapshot. Material changes that invalidate device acceptance require renewed user acceptance before merging. Keep commits focused, report the target branch and included commits, and never equate merge approval with permission to push.

## Change Discipline

Keep changes focused, minimal, and root-cause oriented. Do not refactor unrelated code or update unrelated documentation. Preserve existing working-tree changes that are outside the requested scope.

Follow Conventional Commit style, such as `feat(ai): ...`, `fix(6.4): ...`, `test: ...`, `docs: ...`, or `chore: ...`. Include Room schema exports with database changes and report exactly which verification commands were run.
