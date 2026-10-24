# Portable rerun harness

This entrypoint keeps reruns separate from the archived experiment, so preparing or running it always uses a new Linux/WSL workspace. It uses the fixed 72 target identities and the original generation and measurement logic. The historical discovery scripts are preserved elsewhere as provenance; a rerun does not rediscover or reselect the cohort.

## Requirements

- Python 3.10 or newer; Python packages beyond the standard library are not required.
- Linux or WSL2 for generation and measurement (`fcntl`, `/proc`, process groups and colon-separated Java classpaths). Windows can read the result tables and verify input checksums.
- JDK 17 and JDK 8, each including `java` and `javac`; Maven available on `PATH`. The archived versions are Temurin 17.0.20.1+1 and Temurin 8u504-b01. JVM and Maven installations are not bundled.
- Sufficient free memory for the original resource policy: up to eight generation workers, two measurement workers and 24 GiB of reserved heap allocation. Admission also checks available memory and can wait on smaller machines.
- An OpenAI-compatible model endpoint providing the requested `deepseek-flash` model. The provider controls the underlying snapshot. API credentials are supplied through environment variables; no credential is written to the workspace configuration.

## Prepare without test generation or model requests

Run inside Linux/WSL; choose an empty, Linux-local work directory. The `--artifact` path is the artifact checkout containing `input-manifest.json` and `data/`.

```bash
python3 scripts/portable/rerun.py prepare \
  --artifact "$PWD" \
  --root "$HOME/justin72-new-run" \
  --jdk17 "$HOME/jdk/jdk-17.0.20.1+1" \
  --jdk8 "$HOME/jdk/jdk8u504-b01"
python3 scripts/portable/rerun.py doctor --root "$HOME/justin72-new-run"
```

Preparation checks the five source/binary JAR checksums and the Justin JAR. It copies inputs into the new workspace, installs the supplied plugin/core files in the selected Maven repository, and parses subjects with ChatUniTest. Maven may download missing dependencies during setup; add `--offline` when the cache is complete. `--maven-repo /path/to/cache` chooses a separate cache. Preparation also calculates the common JaCoCo denominators from class files and checks them against the reference counters. The parser uses a disabled key and an unreachable localhost endpoint, so preparation requires no model credential and does not execute subject tests.

## Run all 360 target/tool combinations

Set `JUSTIN_LLM_API_KEY` and `JUSTIN_LLM_BASE_URL` in the current shell. Use the endpoint root, a `/v1` URL, or the full `/chat/completions` URL. For example, type the key interactively so it is absent from shell command history:

```bash
read -rsp 'Model API key: ' JUSTIN_LLM_API_KEY; echo
export JUSTIN_LLM_API_KEY
export JUSTIN_LLM_BASE_URL='https://your-provider.example/v1'
python3 scripts/portable/rerun.py run --root "$HOME/justin72-new-run"
```

This command generates, compiles, and executes tests and uses the caller's model quota. It uses the recorded seed, tool budgets, model name, response cap, and request accounting. It runs Justin, EvoSuite, ChatUniTest, ChatTester, and HITS. Justin enables LLM-based annotation inference; ChatTester and HITS use the corresponding modes of ChatUniTest Maven plugin 2.1.1.

A completed unit is reused on resume. An interrupted unit with a `started.json` but no completed result stops with a recovery error, so an accidental restart cannot silently duplicate a paid model attempt. A fresh experiment requires a different empty root. The results under the artifact's `data/` directory are never overwritten.

The new root contains `runs/<subject>/<tool>/<method_id>/` with generation logs, exported source and external measurement evidence. `usage.jsonl` and `proxy-lifecycle.jsonl` retain successful and failed upstream attempts. Missing usage remains unknown; it is not counted as zero. `state.json` records batch completion. All 360 combinations must be closed before treating a rerun as complete. After the batch, the entrypoint automatically consolidates the fresh evidence into `reports/benchmark72.json`, `reports/benchmark72.html`, `reports/sample72.csv`, `reports/results360.csv`, and `reports/summary72.csv`. Coverage, generation time, observed tokens, exported cases, compilation delivery and JUnit passing rates use the same original accounting and export code. To recompute these reports without model calls or test execution:

```bash
python3 scripts/portable/rerun.py summarize --root "$HOME/justin72-new-run"
```

The summarize command refuses to label an incomplete or unresolved run as final. Its progress diagnostic remains under the new root's `reports/` directory. It never overwrites the archived paper results.

## Input manifest

`input-manifest.json` resolves relative paths against the artifact root. It contains exactly five `subjects` entries, each with `sid`, `jar`, `sources`, and a list of `runtime_dependencies`. `tools_dir` points to the EvoSuite/JUnit/JaCoCo inputs and the evaluated `justin-all.jar`; `native_maven_dir` uses Maven repository-relative paths. An optional `justin_jar` field can specify a separate location. A dependency version is not an additional benchmark subject.

## Adaptations from the archived experiment scripts

`adaptations.json` records the original and adapted script checksums. The adaptations replace machine-specific paths with workspace settings, keep new outputs separate from the supplied results, and read model credentials from environment variables. Maven goals specify plugin version 2.1.1. Preparation can download dependencies; timed generation uses Maven offline. The HITS identifier repair is included as used in the experiment. The POM template retains the recorded starter/plugin configuration and adds each project's runtime dependencies. Generation algorithms, method selection, budgets, seed, measurement, and request accounting follow the experiment scripts.

Packaging checks covered Python syntax, help commands, and input checksums. The 360-run experiment was not repeated during packaging. Model outputs, timing, and token usage can vary with the provider and machine.
