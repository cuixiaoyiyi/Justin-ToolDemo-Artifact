# Justin: Size-Aware Test Generation for Java APIs

This artifact provides Justin, five tested project versions, experiment scripts, and results for **72 methods × 5 tools = 360 runs**. You can inspect the generated tests, check the measurements, or repeat the experiment.

Repository: <https://github.com/cuixiaoyiyi/Justin-ToolDemo-Artifact> · Release: [v1.0.0](https://github.com/cuixiaoyiyi/Justin-ToolDemo-Artifact/releases/tag/v1.0.0)

## Video overview

[Watch or download the introduction video](https://github.com/cuixiaoyiyi/Justin-ToolDemo-Artifact/releases/download/v1.0.0/Justin_SizeAware_Introduction_4m30s_1080p_v2.mp4) (**4 minutes 30 seconds, 1920 × 1080, English narration**). The video follows a running example through input construction, tool use, and the five-tool evaluation. It includes selectable English captions and chapter markers.

[English subtitles](https://github.com/cuixiaoyiyi/Justin-ToolDemo-Artifact/releases/download/v1.0.0/Justin_SizeAware_Introduction_4m30s_1080p_v2.en.srt) · [Narration transcript](https://github.com/cuixiaoyiyi/Justin-ToolDemo-Artifact/releases/download/v1.0.0/Justin_SizeAware_Introduction_4m30s_1080p_v2.narration.txt)

## 1. Start with the recorded results

The recorded results can be checked without Java, Maven, a model account, or network access. Python **3.10 or later**, using only its standard library, is sufficient. Run these commands from the artifact root on Linux, macOS, or Windows (replace `python3` with `python` on Windows):

```bash
python3 scripts/verify_results.py data --output verification-local.json
```

Expected output includes `status: passed`, **72 methods**, **360 cells**, **5 projects**, **10 classes**, and common coverage denominators of **777 executable lines / 722 branches**. The script checks the JSON and CSV exports against each other and recomputes the summary from all method/tool records. It does not run generated tests or call a model.

Open [data/benchmark72.html](data/benchmark72.html) in a browser for the interactive report. Its recorded absolute paths identify the original execution environment; use the selective restore command below to inspect their local copies. The CSV files use UTF-8 with a BOM and can be opened in Excel.

| File or directory | Contents |
|---|---|
| `tools/justin-source/` | Buildable Maven source snapshot of Justin |
| `input-manifest.json` | Input locations, versions, runtime dependencies, and binary checksums |
| `subjects/manifest.json`, `subjects/focal-source/` | Five-version subject manifest and the ten focal classes' original source files |
| `data/frozen-sample.json`, `data/sample72.csv` | The 72 target methods, JVM descriptors, and common coverage denominators |
| `data/results360.csv`, `data/benchmark72.json` | Complete per-method/tool measurements, including failures and zero coverage |
| `data/summary72.csv` | Five tool summaries from those same 360 records |
| `data/audit72.json`, `data/native-exact-target-audit.json` | Independent raw-evidence audit and exact-overload/selector audit |
| `data/analysis72*.json` | Per-method mechanism analysis and limitations |
| `scripts/verify_results.py`, `scripts/evidence.py` | Offline result verification and selective raw-evidence restoration |
| `scripts/portable/` | Configurable Linux/WSL replay harness and adaptation record |
| `scripts/original/` | Experiment and analysis scripts |
| `LICENSES/` | Third-party attribution and preserved embedded license/notice texts |
| `release-assets/` | Download location for the two large release archives |

The local delivered artifact also contains the restored `inputs/` directory and both release archives. A Git clone obtains the source, scripts, manifests, and readable data; the binary inputs and raw logs are downloaded separately to keep large binary files out of Git history.

## 2. Download binaries and experiment evidence

The [v1.0.0 release](https://github.com/cuixiaoyiyi/Justin-ToolDemo-Artifact/releases/tag/v1.0.0) provides:

- **`runtime-inputs.zip`**: the Justin JAR used in the experiment, EvoSuite and measurement tools, Maven artifacts, five subject binary/source JAR pairs, and their runtime dependencies.
- **`raw-evidence.zip`**: generated tests, native outputs, process logs, command vectors, external compilation diagnostics, JUnit XML, JaCoCo data, token usage/lifecycle records, and static parser context from the completed 360-run batch.

To download and checksum-verify both archives, then materialize the runtime inputs:

```bash
python3 scripts/fetch_assets.py --extract-inputs
python3 scripts/verify_integrity.py --inputs --assets
python3 scripts/evidence.py verify
```

For a smaller download, use `python3 scripts/fetch_assets.py --only runtime-inputs.zip --extract-inputs`. `--gh` selects the installed GitHub CLI as the downloader. Download URLs and SHA-256 checksums are listed in [assets.json](assets.json).

Because the historical harness copied much of the same parser context into each run, full raw-evidence restoration creates over **900,000 files** (about **5.6 GB of file bytes**, plus filesystem overhead). The archive stores identical content once and provides a streamed path map. Restore one target first:

```bash
python3 scripts/evidence.py inspect
python3 scripts/evidence.py restore --unit s0001 --tool justin-llm --destination restored
```

The selected files appear under `restored/runtime-root/runs/commons-text-1.15.0/justin-llm/s0001/`. `list --unit s0001 --tool justin-llm` lists their logical paths. For the complete usage ledger, use `restore --prefix runtime-root/usage.jsonl --destination restored`. Full restoration requires the explicit `restore --all --destination restored` option and is best performed on a Linux filesystem. Existing files with different bytes are never overwritten.

Each retained raw file keeps its original bytes and content hash. Repacking removed historical delivery documents and redundant external-input paths; no experiment outcomes were changed. The original full-archive hash is recorded in [data/raw-evidence-manifest.json](data/raw-evidence-manifest.json), and [packaging-decisions.json](packaging-decisions.json) records the packaging choices. Absolute paths inside logs and historical metadata remain provenance, not required installation paths.

## 3. Tested projects and method selection

| Tested project | Version | Focal methods |
|---|---|---:|
| Apache Commons Text | 1.15.0 | 21 |
| Apache Commons Lang | 3.17.0 | 42 |
| Apache Commons Collections | 4.6.0 | 3 |
| JLine | 3.21.0 | 1 |
| jsoup | 1.23.2 | 5 |
| **Total** | **5 versions / 10 classes** | **72** |

String, array, and indexed collection APIs operate on finite sequences: access requires an existing position, a fragment count depends on the extent remaining after its start, and insertion depends on the receiver's current size. The selected methods cover text editing/padding, binary/hex conversion, array comparison, byte ranges, indexed collections, and document-tree updates. Every tool uses the same 72 method signatures, including their JVM descriptors to distinguish overloads.

The 72 methods were selected retrospectively to study size constraints. Each tool configuration ran once, so the results describe this targeted sample.

Only the versions in the table are **tested subjects**. For example, `inputs/subjects/dependencies/commons-lang3-3.20.0.jar` is the runtime dependency used by Commons Text 1.15.0; it is not an additional tested Commons Lang version. Preserving it avoids silently changing the recorded classpath. Other dependency JARs are likewise separated and listed in `input-manifest.json`.

## 4. Use Justin on one example

Justin builds related input values and receiver states, supplements uncovered paths, and exports executable JUnit tests with source labels. For a non-null, nonempty array of length `L`, a start **inside** the array satisfies `0 <= i < L`, and a fragment count satisfies `0 <= n <= L - i`; `n = 0` is allowed and the count's upper bound is inclusive. Endpoint and insertion rules depend on the operation: the example append API also accepts `i = L, n = 0`, while ordinary element access requires `i < L`. An indexed insertion can allow `i = size`.

The recorded target `s0001` is `org.apache.commons.text.TextStringBuilder#append(char[],int,int)`, with JVM descriptor `([CII)Lorg/apache/commons/text/TextStringBuilder;`. Its generated `append_62` test passes an eight-element array with start `0` and count `8`, and records the source `symbolic:path~`. The raw run also contains expected-exception cases and independent exact-method coverage evidence.

The binary needs **JDK 17**. After restoring inputs, this command starts a new local example and writes results to a separate directory:

```bash
java -Xmx3g -jar inputs/tools/justin-all.jar generate \
  --classes inputs/subjects/commons-text-1.15.0.jar \
  --sources inputs/subjects/commons-text-1.15.0-sources.jar \
  --cp inputs/subjects/dependencies/commons-lang3-3.20.0.jar \
  --method 'org.apache.commons.text.TextStringBuilder#append(char[],int,int)' \
  --out new-demo --seed 42 --run
```

To enable LLM-based annotation inference, add `--llm-config /path/outside/repository/justin-local.properties`. Copy [config/justin.example.properties](config/justin.example.properties) to that external path and supply your own **Anthropic Messages-compatible** endpoint and API key. The standalone Justin client uses that protocol; the benchmark below provides a local bridge to an OpenAI-compatible upstream. Keep the private properties file outside version control. `java -jar inputs/tools/justin-all.jar generate --help` describes the full CLI.

Generation creates Java test sources and HTML/JSON reports in the output run directory. Source comments identify input-generation channels. A tool's class-level report can cover helper calls and other methods; the experiment's primary metric independently measures the exact focal JVM descriptor. Test assertions record observed behavior and require review when used as specification checks.

To build the supplied source with JDK 17 and Maven:

```bash
cd tools/justin-source
mvn -DskipTests package
java -jar justin-cli/target/justin-all.jar --help
```

The source commit and experiment JAR checksum are recorded in `tools/source-provenance.json`. A rebuild may produce different JAR bytes. Use the released experiment binary to compare with the recorded results.

## 5. Prepare and rerun the complete experiment

Generation and external measurement require **Linux or WSL2**, Python 3.10+, Maven 3.8+, **JDK 17 and JDK 8**, and access to an OpenAI-compatible model service. EvoSuite uses JDK 8; the other generators use JDK 17. Use a new workspace on a Linux filesystem. The historical scripts use POSIX process control and `/proc` resource data, so they are not native Windows runners. Result inspection and checksum validation remain cross-platform.

The recorded schedule allowed up to eight generation workers and two external-measurement workers, with 24 GiB weighted memory reservations and at least 4 GiB available before admission. A machine with **32 GiB RAM** is a practical starting point. Scheduler limits and the measurement heap are documented in `data/protocol.json`; concurrency is resource-gated.

Prepare the inputs and parser context before test generation:

```bash
python3 scripts/portable/rerun.py prepare \
  --artifact "$PWD" \
  --root "$HOME/justin72-new-run" \
  --jdk17 /path/to/jdk17 \
  --jdk8 /path/to/jdk8 \
  --maven-repo "$HOME/.m2/repository"
python3 scripts/portable/rerun.py doctor --root "$HOME/justin72-new-run"
```

Preparation can download missing Maven dependencies. The release includes the plugin/core files, but Maven itself and a complete dependency cache are required separately. Use `prepare --offline` with a populated cache. Preparation requires an empty workspace so previous results are preserved.

The following **explicit `run` command sends paid model requests** and generates/executes tests for all 360 cells. Provide the endpoint and credential only through process environment variables:

```bash
export JUSTIN_LLM_BASE_URL='https://your-provider.example/v1'
read -rsp 'LLM API key: ' JUSTIN_LLM_API_KEY; echo
export JUSTIN_LLM_API_KEY
python3 scripts/portable/rerun.py run --root "$HOME/justin72-new-run"
unset JUSTIN_LLM_API_KEY
```

The model field remains `deepseek-flash`, the response cap is 4,096 tokens, thinking is disabled, and native tool temperatures are retained. A provider must support the recorded model name and request format; its current backend may differ from the unpinned 2026-10-23 snapshot. New wall times and generated tests need not match a single historical run exactly. Keep new results separate from `data/`. On completion, the runner automatically writes fresh JSON, HTML, sample/result/summary CSV files under the new workspace's `reports/` directory; raw generation, compilation, JUnit, JaCoCo, and token records remain alongside them. To recompute those reports without model calls or test execution, run `python3 scripts/portable/rerun.py summarize --root "$HOME/justin72-new-run"`.

`scripts/portable/README.md` describes the setup and commands. `scripts/original/` contains the experiment and analysis scripts with their recorded paths. Use the configurable entry point above to rerun the experiment.

## 6. Tool configurations and measurement

The five tools are **Justin**, **EvoSuite 1.2.0**, **ChatUniTest**, **ChatTester**, and **HITS**. Justin used LLM-based annotation inference in this experiment, with the same requested model as the LLM baselines. The latter three tools use their corresponding modes in ChatUniTest Maven plugin/core **2.1.1**. Implementation details and method-selection checks are recorded in `data/implementation.json` and `data/native-exact-target-audit.json`.

Every cell has a 900-second generation wall ceiling. EvoSuite's search budget is 60 seconds and Justin's symbolic class budget is 20 seconds; the common ceiling does not equate native search/repair budgets. External compilation and testing use the same subject bytecode/runtime classpath, a 6 GiB heap, and a 300-second test wall ceiling. Exact selectors are verified from the complete JVM descriptor before generation.

| Tool configuration | Line coverage | Branch coverage | Generation time (min.) | Recorded tokens, approx. | Exported cases |
|---|---:|---:|---:|---:|---:|
| Justin | 96.65% | 96.54% | 26.8 | 163,419 | 733 |
| EvoSuite | 94.85% | 95.57% | 29.9 | 0 | 349 |
| ChatUniTest | 86.49% | 90.17% | 42.7 | 186,644 | 863 |
| ChatTester | 97.17% | 94.88% | 62.6 | 373,306 | 965 |
| HITS | 54.05% | 56.51% | 126.4 | 1,238,654 | 2,640 |

Coverage sums covered focal lines/branches over the **same 72-target denominator**, including failed or zero-coverage cells. Generation time sums each cell's generator wall time and excludes external measurement; parallel batch elapsed time was about 48.21 minutes. Exported cases count ordinary source `@Test` methods, not all XML-discovered executions, and more cases do not imply better tests.

| Tool | Fully compiled targets / 72 | All-export compile rate | Executed tests passing |
|---|---:|---:|---:|
| Justin | 72/72 | 72/72 | 731/733 (99.73%) |
| EvoSuite | 72/72 | 72/72 | 348/348 (100%) |
| ChatUniTest | 72/72 | 72/72 | 771/863 (89.34%) |
| ChatTester | 70/72 | 70/70 | 858/965 (88.91%) |
| HITS | 38/72 | 38/38 | 2,364/2,640 (89.55%) |

The conditional compile rate uses nonempty exported suites as its denominator. Missing exports count as failures to deliver a compilable suite in the 72-target sample. EvoSuite exported 349 source cases and executed 348, with one skipped case. The data also identify the 37 targets delivered by every tool; results for that subset are reported separately from the full sample.

Token figures sum recorded provider usage over actual attempts, including retries. **21 failed attempts have missing usage** (Justin 2, ChatUniTest 2, ChatTester 2, HITS 15). Recorded totals are therefore approximate observed amounts, not complete actual totals, and do not establish the true total-cost ranking. Cache-input usage is already part of input tokens. No cost is assigned to a missing usage value as if it were zero.

The audit distinguishes wrong overload binding, missing focal coverage probes, repair failure, and missing slice files. In this run, 34 HITS-mode targets have no saved export after a missing `slice.json`, and two ChatTester-mode targets have no saved export after repair failure. These observations describe the tested plugin modes and configuration. Interpretation also depends on the recorded model, budgets, project versions, sample selection, and single run per configuration.

## 7. Integrity, attribution, and publication

`SHA256SUMS` lists checksums for repository files; `input-manifest.json` and `assets.json` record binary and release checksums. Run `python3 scripts/verify_integrity.py --inputs --assets` after download. Credentials and machine settings are excluded.

Justin's source snapshot does not declare a permissive license; no new license has been assigned during packaging. Consult [LICENSES/README.md](LICENSES/README.md) for rights and third-party attribution. Third-party components retain their own licenses, notices, and upstream source links. Authors: Baoquan Cui, Rong Qu, and Jian Zhang, Institute of Software, Chinese Academy of Sciences.
