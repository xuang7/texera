# AutoFix: LLM-Assisted Demo Repair

When a Playwright video-generation scenario fails (form fill error, backend timeout, missing column, etc.), AutoFix asks Claude to propose a JSON patch to `operator-field-values.json`, applies it, and retries. The goal is to keep the pipeline running across operator schema changes without hand-fixing every config.

## Quick start

```bash
# Requires ANTHROPIC_API_KEY in env. The pipeline aborts cleanly if missing.
ANTHROPIC_API_KEY='sk-ant-...' sbt 'Docs/runMain org.apache.texera.docs.VideoGeneratorMain \
  --group=Sklearn --auto-fix --max-attempts=3'
```

`--auto-fix` enables the loop. Without it, the runner records each scenario once and reports failures unchanged.

## What gets patched

AutoFix only writes to `operator-field-values.json`. Two kinds of changes:

1. **Config values** — e.g. swap a column that doesn't exist anymore (`"attribute": "name"` → `"attribute": "title"`).
2. **`_controllerHints`** — UI binding overrides for the Playwright controller. One defined path:
   - `formFieldLabels/<configKey>` — exact UI label when "Field not found" is logged (e.g. catches a double-space typo in a schema title).

Anything outside this JSON file is out of scope. Template structure, controller code, and operator schemas are NOT touched.

## How a single scenario flows

```
snapshot config
   │
   ▼
attempt 1..N (default N=3)
   │
   ├─ runScenario() → Success? → keep patched config, record outcome
   │
   └─ Failure → FailureCollector enriches with schema + dataset
                LLMPatchProposer.proposePatch (with up to 2 transient retries)
                Validator checks patched config; on reject, give LLM one self-correction try
                PatchApplier.apply → write JSON, next attempt
   │
attempts exhausted → restore snapshot, append to docs/generated/unfixed.json
```

Key behaviors:

- **Snapshot/restore** is per-scenario. A successful patch stays in the JSON. A scenario that exhausts all attempts has its config rolled back so the next run starts clean.
- **Transient LLM errors** (network blips, 5xx) retry inline up to `llmRetries=2`. A missing API key fails fast — no retry.
- **Validator rejection** (the schema validator rejects a patch as still-invalid) gives the LLM one self-correction attempt with the rejection message included in the prompt.
- **Browser videos** for failed attempts are deleted. Only successful runs keep their `.webm`. Failure screenshots (`_failure.png`) are preserved for triage.

## What the LLM sees

For each failure, the prompt includes:

1. Operator type, failing step name, exception class + message.
2. Browser console errors + Texera workflow error text (if extractable from the DOM).
3. The current JSON config object for that operator.
4. The operator's JSON schema (properties, type rules, required fields).
5. The dataset's column schema (name + type).
6. On a self-correction round, the previous validator rejection message.

Output format (strict — no prose):

```json
{
  "operatorType": "<type>",
  "reasoning": "<one sentence>",
  "confidence": "high" | "medium" | "low",
  "diff": [ { "op": "add"|"replace"|"remove", "path": "/key", "value": ... } ]
}
```

Paths are RFC 6902 pointers relative to the operator's config object — `/attribute`, not `/operators/Foo/attribute`.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | (required) | Claude API key. Empty → autofix exits before any LLM call. |
| `ANTHROPIC_MODEL` | `claude-sonnet-4-6` | Override the model. |
| `ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | For proxy / mock endpoints. |
| `ANTHROPIC_MAX_TOKENS` | `1500` | Per-call output cap. |

CLI flags on `VideoGeneratorMain`:

| Flag | Default | Purpose |
|---|---|---|
| `--auto-fix` | off | Enable the loop. Without it, no LLM calls. |
| `--max-attempts=N` | 3 | Per-scenario attempts ceiling. Counts the original run + each retry. |

## Outputs

- **`docs/generated/unfixed.json`** — appended on every run with one entry per unresolved scenario. Each entry has the operator name, timestamp, and the full attempt history (exception, proposed patch, validator issues, whether applied). Useful for triage and prompt tuning.
- **Run report** — printed at end of run. Includes:
  - Total + succeeded + failed counts.
  - Successes broken into "attempt 1" vs "after LLM patch" — the second bucket tells you whether the agent did useful work.
  - Failure buckets grouped by exception class.
  - LLM call count, transient failures, token usage (with cache hit/creation), wall time.

## Skip list

Some operators are statically excluded from autofix because their failures are structural, not config issues:

- DB / external-API operators (`MySQL Source`, `Twitter Search API`, etc.) — need real credentials.
- ML prediction operators (`Sklearn Prediction`, `Sklearn Testing`, `Machine Learning Scorer`) — need a `model` / `prediction` column from an upstream training operator that the current `sample_ML.json` template doesn't produce.
- `Dummy` — placeholder operator with no execution semantics.

Skipped operators get counted in the run summary as "skipped runtime-incompatible" so they don't dilute the failure rate.

## When autofix won't help

- **Template-level bugs** — e.g. the `sample_join.json` `Split` partitioning makes `Intersect` output empty regardless of config. AutoFix patches config; it cannot restructure the upstream pipeline.
- **Backend issues** — `TargetClosedError` / `504 Gateway Timeout` during execution usually means the Texera backend is overloaded or the operator's data path is too slow on the sample dataset. Patches won't change the data shape.
- **Controller bugs** — e.g. "Add button not found for array field". AutoFix can sometimes work around by emptying the array (`replace ... = []`), but the root cause is in the controller code.

These cases land in `unfixed.json` for human follow-up.

## Files

```
docs/src/main/scala/org/apache/texera/docs/autofix/
├── AutoFixOrchestrator.scala   # per-scenario retry loop, snapshot/restore, stats
├── AutoFixTypes.scala          # case classes: FailureContext, PatchEnvelope, AttemptRecord, AutoFixOutcome, ...
├── FailureCollector.scala      # enriches a RunFailure with operator schema + dataset
├── LLMPatchProposer.scala      # builds the prompt, calls Claude, parses the JSON envelope
└── PatchApplier.scala          # applies an RFC 6902 patch to operator-field-values.json (with dry-run + snapshot/restore)
```
