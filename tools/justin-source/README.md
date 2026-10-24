# Justin

Automatic unit test generation for Java programs. Design and progress log: [PLAN.md](PLAN.md).

Justin reads bytecode (plus sources when available), generates JUnit 5 tests with several
complementary strategies, executes every candidate input in a sandbox to build oracles, then
compiles, runs and measures the tests it keeps. Current version: v0.8.3 (P0–P4 of the plan done, plus generic value construction, fill sequences and the regex channel for string parameters).

## What it does

- **Strategies** — random values; special values (type boundaries, configurable tables); state-building
  sequences (receivers, containers and arrays at several fill levels, elements typed by the generic
  bindings; functional-interface arguments synthesized as lambdas); annotation
  contracts (Bean Validation, Hibernate Validator, JSR-305/JetBrains/Spring nullability and Justin's own
  annotation basis: `@Min @Max @Pattern @ArrayPara @Field @Element @SubClass @InRange @IndexOf @Examples`);
  static seeds mined from the method body (the `static` strategy, see [Generation strategies](#generation-strategies)); flow-insensitive program analysis (parameter relations such
  as array/index, length bounds, map keys; explicit throws; purity); symbolic execution over Jimple with Z3.
- **Combination** — IPOG n-way covering arrays over the candidate values, with relation-derived
  parameters (an index derived from the array it indexes, a length bound to another parameter, …)
  and illegal values kept apart from legal ones.
- **Cascade** — the cheap strategies run first; the lines and branches they leave uncovered become the
  targets of symbolic execution (stage C). Symbolic execution can also run alone or on every method
  (`--strategies symbolic`, `--symbolic direct`), with per-class budgets, two-round scheduling,
  explosion cuts and same-class call inlining.
- **Regex channel for strings** — inside stage C and before the solver, targets whose conditions depend
  on `String`, `StringBuilder` or `StringBuffer` values are handled by regex-guided generation: paths to
  the target are sliced into predicates, an API-to-regex mapping (equals, startsWith, contains, length,
  charAt, indexOf, substring, `split(d)[j]`, trim, case conversion, builder appends, ...) is composed
  backwards into a regex for the parameter, and the strings drawn from it are checked against the sliced
  predicates on the real JDK APIs. Exception-triggering variants shorten the caller of substring, charAt
  or a split element. Delimiter-induced structure is handled by structural relaxation: all predicates
  on the pieces of one `split` (segment contents, number of segments, `limit`, regex delimiters, a split
  of a split, two segments related to each other) are answered by one string that materializes only the
  segments the path refers to, and `indexOf` / `lastIndexOf` with `substring` (the key=value and
  file-extension idioms) use the same mechanism. What the channel reaches is no longer a target of
  symbolic execution (`--no-regex` turns it off).
- **Oracles** — regression assertions from observed results (equality, arrays, null/not-null,
  exceptions), stability checks (repetition, fresh-sandbox re-execution for classes with static state),
  and semantic contracts from annotations on return values and fields (`assertTrue(result >= 0 && …,
  "contract @Min(0) @Max(200)")`). A returned value that violates its contract marks the test as a
  suspect defect; exception tests are classified EXPECTED / SUSPECT / UNKNOWN with evidence.
- **LLM-inferred annotations** (optional) — a model proposes contracts for unannotated methods in the
  same annotation basis; answers are validated, cached and attributed to the `llm` strategy.
- **Reports** — a self-contained `report.html` (KPIs, coverage meters, per-strategy contribution,
  suspects with evidence, failures with stack traces, uncovered targets, symbolic-execution and LLM
  accounting) plus `report.json`, JUnit XML and JaCoCo output.

## Generation strategies

A strategy proposes candidate values for the receiver and for each parameter of the method under test.
The candidates of all enabled strategies are pooled per parameter and combined into input tuples (IPOG
covering arrays); every tuple is executed in the sandbox, and the ones that are stable and add coverage
become tests. The names below are what `--strategies` takes and what the report shows: a test labelled
`annotation+special` took its values from those two strategies.

| Name | When | What it contributes |
|---|---|---|
| `random` | stages A/B | Type-directed random values under the fixed seed: mostly small integers (within ±10 and ±1000, occasionally up to ±1,000,000), short strings and dictionary words, random objects built through constructors and factories. |
| `special` | stages A/B | Boundary and notorious values per type from a configurable table: `0, 1, -1, MAX_VALUE, MIN_VALUE` and their neighbours, `NaN`, infinities and `-0.0`, the empty string, blanks, `"null"`, numerals as strings, `null` for references. |
| `annotation` | stages A/B | Values derived from contracts on parameters, fields and return values: Bean Validation, nullability annotations and the annotation basis of Justin (`@Min @Max @Pattern @ArrayPara @Field @Element @SubClass ...`). For each constraint it produces values on the bounds, inside and outside (the outside ones are kept apart as illegal inputs), strings sampled from `@Pattern`, arrays and objects built to specification. Return-value contracts become assertions. |
| `llm` | stages A/B, only with `--llm-config` | The same machinery as `annotation`, fed with contracts a language model infers for methods that have none. Reported separately so its contribution can be measured. |
| `static` | stages A/B | **Static seeds**: constants taken from the body of the method under test by static analysis, used as inputs. See below. |
| `sequence` | stages A/B | State-building call sequences: the receiver and every container-like parameter (arrays, JDK collections and maps, project classes with `add/put/push/offer/append/insert` or setters) at three fill levels, empty, one element, three elements, plus an array with a `null` element. Elements are typed by the generic bindings; functional-interface parameters get synthesized lambdas. |
| `regex` | stage C, before the solver; off with `--no-regex` | For uncovered lines whose branch conditions depend on strings: paths to the line are sliced into predicates, an API-to-regex mapping is composed backwards into a regex for the parameter, candidates are checked against the predicates on the real JDK APIs. Handles `split` and the `indexOf`/`substring` idioms by structural relaxation; also emits inputs that make `substring`, `charAt` or a split element throw. |
| `symbolic` | stage C (`--symbolic cascade`, default), or every method (`--symbolic direct`, or listing `symbolic` in `--strategies`) | Symbolic execution over Jimple with Z3: one input per satisfiable path, steered towards the lines the other strategies left uncovered, within per-method and per-class time budgets. Calls into project classes are followed, `java.util` collections have a model, the class of an object is part of the path condition. A path that reads fields of the receiver is solved against the concrete receivers at hand; when none of them fits, calls of public methods that write those fields are explored in front of the method, their arguments solved together with its own, and the test builds the receiver by that sequence. |

The default is `random,special,annotation,static,sequence` with `regex` and `symbolic` on top in cascade
mode. Stages A/B are cheap and run for every method; stage C runs only where coverage is still missing.

### What `static` means

`static` stands for *static analysis of the method body*, not for the Java keyword `static`. Before any
input is generated, the body is read once (SootUp/Jimple, flow-insensitive) and the constants it compares
or computes with are collected: `if (x == 42)`, `s.equals("yes")`, `c == ';'`, `case 1000:`. The strategy
turns them into candidate inputs for parameters of a matching type:

- integer constants `c` as `c - 1`, `c`, `c + 1` (0, 1 and -1 are left to `special`), also offered to `long`, `double` and `float` parameters;
- string constants `s` as `s`, `s + "x"` and `s` with its case flipped, for `String`, `CharSequence` and `Object` parameters;
- integer constants in the printable ASCII range also as one-character strings, because a `char` compared with `charAt(i)` appears as an integer in bytecode.

This is what makes a branch guarded by a magic number or a keyword reachable without search: random
values almost never hit `42` or `"yes"`. It is deliberately blind to which parameter a constant is
compared with; the combination step and the coverage filter sort that out.

The same body analysis also yields facts that are used whether or not the `static` strategy is enabled,
and that carry other labels in the report: relations between parameters (an index into an array
parameter, a length bound, a map key; tuples derived from them are labelled `relation:<name>`, deliberate
violations `illegal:<name>`), the exceptions a method throws explicitly (used to judge an observed
exception as expected or suspect), and purity and never-null facts (used when building assertions).

## Build

Requires JDK 17 and Maven 3.9.

```
mvn -q -B install
```

The CLI is `justin-cli/target/justin.jar` with its dependencies in `justin-cli/target/lib/`; the same build also
produces the single-file distribution `justin-cli/target/justin-all.jar` (everything shaded in, `java -jar justin-all.jar ...`),
which extracts the JUnit launcher and JaCoCo agent it embeds to the temp directory on first use.

## Use

Generate tests for one class of a jar, then compile, run and measure coverage:

```
java -jar justin-cli/target/justin.jar generate \
    --classes path/to/subject.jar --cp dep1.jar;dep2.jar \
    --class org.example.Foo --java-version 8 \
    --out workspace/out/foo --run
```

A subject can also be a Maven artifact; its jar, sources jar and dependencies are downloaded to
`~/.justin/repository` (POM parents, properties, dependencyManagement with imported BOMs, exclusions,
nearest-wins):

```
java -jar justin-cli/target/justin-all.jar generate --maven org.apache.commons:commons-text:1.12.0 --out out --run
java -jar justin-cli/target/justin-all.jar generate --maven https://mvnrepository.com/artifact/org.jsoup/jsoup/1.17.2 --out out --run
```

`--sources` takes source directories or sources jars, and the Java level of a jar is read off its class
files when `--java-version` is absent. The distribution scripts in `dist/` wrap the two common inputs for
Windows and Linux: `justin-maven` (coordinates or a link) and `justin-local` (a jar and its sources jar);
`dist/README.md` is the usage guide shipped with the single-file jar.

Scope: `--class`, `--method "pkg.Cls#name(int,java.lang.String)"`, `--package pkg [--no-recursive]`,
or nothing for everything in `--classes`; refine with `--include` / `--exclude` globs. A Maven or Gradle
project (single- or multi-module) can be given with `--project dir`, optionally narrowed with `--module`. Path lists use the platform separator
(`;` on Windows, `:` elsewhere).

Frequently used options:

| Option | Meaning |
|---|---|
| `--strategies random,special,annotation,static,sequence[,symbolic]` | which strategies produce values (default: the first five, with symbolic execution in cascade mode on top) |
| `--seed 42`, `--tuples 20`, `--strength 1` | random seed, input tuples per method, n-way strength |
| `--run`, `--no-minimize`, `--allow-io` | execute the tests; keep coverage-redundant tests; do not block file/network/process/exit calls |
| `--threads 8` | generate classes in parallel (results do not depend on the thread count); give the JVM about 1 GB of heap per thread, e.g. `java -Xmx8g -jar ...` |
| `--project dir [--module name] [--mvn path] [--gradle path]` | Maven (single- or multi-module) or Gradle build; one module, or all |
| `--symbolic cascade\|direct\|off`, `--no-symbolic` | how symbolic execution is coupled to the other strategies |
| `--sym-budget 5000`, `--sym-start 1000`, `--sym-class-budget 60000` | per-method maximum, round-one budget and per-class budget in ms |
| `--sym-paths 50`, `--sym-inline 1`, `--sym-no-gain 50` | satisfiable paths per method, inlining depth of calls within the class (0 switches inlining off; a call into another class gets one level more), explosion cut |
| `--sym-objects all` | how symbolic execution models objects, a list of `receiver` (paths are solved against the fields of concrete receivers, read in the sandbox), `sequence` (receivers built by calls of public methods of the class), `collections` (model of the `java.util` collections), `inline` (calls into other project classes and constructors are followed), `types` (`instanceof`, casts, virtual calls); `all` (default) or `none`. See `0929ObjectModel/REPORT.md` |
| `--maven LINK`, `--maven-repo URL,...`, `--no-maven-deps` | the subject as a Maven artifact (coordinates, a repository link or an artifact page); repositories to try first; skip dependency resolution |
| `--no-regex` | skip the regex channel for string parameters that runs before the solver in stage C |
| `--no-bridge-lift` | send path conditions to the solver as they are. By default the conversions between `int` and string lengths or indices are taken off the string terms first (same meaning, Java overflow kept), which is what makes Z3 slow on string constraints; see `0929BridgeLift/REPORT.md` |
| `-Djustin.smtdump=DIR` (JVM option, with `-Djustin.smtdump.ms=200`) | diagnostics: every solver call of symbolic execution as a line of `DIR/checks.csv`, and each call slower than the threshold or timed out as a self-contained SMT-LIB 2 file under `DIR/slow/` |
| `--llm-config file.properties` | enable model-inferred annotations (`baseUrl`, `model`, `apiKey`, `maxCallsPerRun`, `maxTokensPerCall`, `thinking=disabled`, …); keep the file out of version control |

Each run gets its own directory under `--out` (`<timestamp>_<strategies>_seed<seed>` or `--label`):

- `generated-tests/src/` JUnit 5 sources (`<Class>JustinTest`, same package as the target); suspect
  tests carry `@Tag("justin-suspect")`
- `generated-tests/classes/` compiled tests
- `reports/report.html` visual report; `justin report --run <dir>` re-renders it from the JSON
- `reports/report.json` structured result: tests per class with strategy, source, outcome, verdict and
  execution status; rejected tuples; compile diagnostics; execution summary; coverage per class and
  method; per-method symbolic-execution accounting; timings and LLM usage
- `reports/junit/` JUnit XML, `reports/jacoco.exec`, `reports/coverage-html/`

Other commands:

```
java -jar justin-cli/target/justin.jar report --run workspace/out/foo/<run>      # re-render the HTML report
java -jar justin-cli/target/justin.jar daemon [--port 7331]                      # JSON-RPC 2.0 front-end for IDE plugins (stdin/stdout or loopback TCP)
java -jar justin-cli/target/justin.jar measure --classes subject.jar --package org.example --repeat 2   # SootUp loading cost
```

Debug logging: `-Djustin.log=DEBUG` (engine) and `-Djustin.symlog=TRACE` (symbolic executor).

## Repository layout

Maven multi-module build; modules depend only downwards in this list. Front-ends (CLI, later IDE
plugins and a daemon) depend on `justin-api` alone.

| Directory | Role |
|---|---|
| `pom.xml` | parent: module list, dependency and plugin versions |
| `justin-annotations/` | the annotation basis subjects may use: `Min`, `Max`, `Pattern`, `Nullable`, `NonNull`, `InRange`, `IndexOf`, `Examples`, `ArrayPara`, `Field`, `Element`, `SubClass` (no other dependency, safe to add to any project) |
| `justin-core/` | shared model: targets (`ClassTarget`, `MethodTarget`, `Parameter`, `FieldInfo`, `AnnotationInfo`), generic types (`TypeRef`), values and construction recipes (`Value`, `Candidate`, `ValuePool`, `InputTuple`), test model (`Statement`, `Assertion`, `Outcome`, `Verdict`, `Relation`, `BodyFacts`), scope, configuration, and `contract.Constraints` (unification of annotations into specs) |
| `justin-generics/` | signature parser and type instantiation for generic classes and methods |
| `justin-scan/` | ASM class-path scanner: classes, methods, fields, annotations, without loading classes |
| `justin-analysis/` | SootUp on-demand loading (`SootUpLoader`) and the flow-insensitive fact extractor (relations, explicit throws, constants, purity) |
| `justin-strategies/` | value strategies: `RandomStrategy`, `SpecialValueStrategy`, `AnnotationStrategy` (with `SpecGenerator`, `RegexStrings`), `StaticSeedStrategy`, `SequenceStrategy` (fill levels), plus `ValueGenerator` for binding-consistent object construction, fill sequences and synthesized functions |
| `justin-combine/` | IPOG n-way combiner and `RelationExpander` (relation-derived and illegal values) |
| `justin-exec/` | generation sandbox: isolated class loader, JaCoCo probes per invocation, IO interception, invoker with timeouts and stability checks, result observer, uncovered-target computation |
| `justin-oracle/` | assertion synthesis from outcomes (`OracleBuilder`), annotation-derived return contracts (`ReturnContracts`), exception classification (`ExceptionClassifier`) |
| `justin-emit/` | JUnit 5 source generation with JavaPoet (`TestClassEmitter`, `ValueCode`) |
| `justin-runner/` | forked javac with test pruning, forked JUnit console launcher with the JaCoCo agent, JUnit XML parsing, coverage analysis |
| `justin-symexec/` | symbolic execution over Jimple with Z3 (`SymbolicExecutor`, `JdkSummaries`, `SymConfig`, `SymbolicStrategy` with resumable sessions, `TargetAttribution`, `BridgeLift`), the object model (`ReceiverStates`, `FieldUse`, `CollectionSummaries`, `TypeModel`, `TypeOracle` / `Hierarchy`, `ValueSource`); package `strings` is the regex channel (`PathSlicer`, `Term`, `ApiRegexMapping`, `RegexWrapper`, `Rx`, `Profile`, `SegmentProfile`, `RegexChannel`) |
| `justin-llm/` | model-inferred annotations: Anthropic-compatible client, prompt and JSON schema (`AnnotationInferrer`), cache, source snippets, `AnnotationAugmenter` |
| `justin-api/` | the engine (`Engine`: scan → generate → cascade → minimize → emit → compile → run → report, classes optionally in parallel), request/result records, `ProjectModel` implementations (class path, multi-module Maven, Gradle), HTML report writer |
| `justin-cli/` | picocli front-end: `generate`, `report`, `measure`, `daemon` (JSON-RPC server, `DaemonServer`) |
| `justin-samples/` | small annotated subject classes used by the tests and for smoke runs |
| `workspace/` | not committed: subject jars and sources (`subjects/`), run outputs (`out/`), caches, and the local LLM configuration (`llm.properties`) |
| `PLAN.md` | design, decisions and the measured status of every phase (sections 8a–8f) |

## Results so far (Commons Lang 3.17.0, 254 classes, default settings, seed 42)

| Version | Tests | Line / branch / method coverage | Notes |
|---|---|---|---|
| v0.3.0 (P2) | 4414, all green | 69.8% / 61.5% / 72.1% | strategies A/B only |
| v0.4.0 (P3) | 5321 | 76.1% / 67.9% / 82.2% | + symbolic execution as cascade stage C |
| v0.5.0 (P3+) | 5316, all green | 76.3% / 68.2% / 82.5% | + budgets, explosion cut, same-class inlining |
| v0.6.0 (P4), `--threads 8` | 5315, all green | 76.2% / 67.9% / 82.5% | 252 s instead of 807 s; multi-module Maven, Gradle, daemon |
| v0.7.0, `--threads 8` | 5214, all green | 76.7% / 67.9% / 83.4% | + generic value construction, fill sequences, synthesized lambdas |
| v0.7.1, `--threads 8` | 5247, all green | 77.5% / 68.2% / 84.6% | + intersection / self-referential / forward bounds, nested wildcards, second type-argument set, overload casts |
| v0.8.0, `--threads 8` | 5380, all green | 78.2% / 70.4% / 84.5% | + regex channel for string parameters before the solver (302 target lines in 12 s), target attribution |
| v0.8.1, `--threads 8` | 5382, all green | 78.3% / 70.4% / 84.6% | + structural relaxation for split and indexOf idioms in the regex channel (no measurable change on this library: 10 of 987 string methods have such structure) |
| v0.8.5, `--threads 8` | 5776, all green | 79.6% / 72.0% / 86.1% | + bridge lift: int/string conversions taken off the string terms before solving (solver timeouts 53 to 29; on jline 100 to 7) |
| v0.9.0, `--threads 8`, `--sym-class-budget 20000` | 5910, all green | 80.8% / 74.0% / 86.4% | + object model in symbolic execution: paths solved against concrete receivers, call sequences of public methods that build receiver state, `java.util` collections, calls into other classes, classes of objects. Three runs: 80.8 / 74.0, 80.8 / 74.0, 80.8 / 73.9; the same build with `--sym-objects none`: 79.5 / 71.8, 79.6 / 72.0, 79.6 / 72.0. See `0929ObjectModel/REPORT.md` |
| v0.8.4, `--threads 8` | 5760, all green | 79.5% / 71.5% / 86.0% | + values for JDK interface types, instance methods of abstract classes through a concrete subclass, constructibility check of candidates (see the comparison below) |

Other subjects (line / branch coverage, same settings with `--threads 8`):

| Subject | Version | Tests | Line / branch | Notes |
|---|---|---|---|---|
| Commons Text 1.15.0 | v0.5.0 | 1352, 3 failures | 79.2% / 70.4% | |
| Commons Text 1.15.0 | v0.8.0 | 1369, all green | 80.7% / 72.6% | the three failures fixed (interned strings, surrogate escapes, literal casts) |
| Commons Text 1.15.0 | v0.8.1 | 1369, all green | 80.8% / 72.6% | |
| Commons Collections4 4.6.0 | v0.6.0 | 2804, 4 errors | 45.2% / 34.0% | |
| Commons Collections4 4.6.0 | v0.7.0 | 2969, all green | 55.8% / 47.7% | generic value construction, fill sequences |
| Commons Collections4 4.6.0 | v0.7.1 | 3175, all green | 56.7% / 48.8% | |
| Commons Collections4 4.6.0 | v0.8.0 | 3174, all green | 56.6% / 48.8% | no string targets there |
| jline 3.21.0 | v0.8.1 | 1409, 1 failure | 22.1% / 12.9% | most classes need a terminal; the failure depends on the terminal palette |
| Commons Text 1.15.0 | v0.8.4 | 1490, all green | 83.7% / 77.2% | with the dependency its POM declares (commons-lang3 3.20.0) |
| Commons Collections4 4.6.0 | v0.8.4 | 5127, all green | 72.7% / 63.7% | the six omissions found by comparing with EvoSuite, PLAN.md 8o |
| jline 3.21.0 | v0.8.4 | 2205, all green | 40.6% / 24.8% | in-memory readers, writers and streams as arguments; outcomes that depend on a blocked call are dropped |

v0.9.0 against the same build with the object model switched off (`--sym-objects none`, which is what v0.8.5 does), three runs
each on the same machine and day, 8 threads, 20 s of symbolic execution per class, all generated tests passing:

| Subject | Object model off, line / branch | v0.9.0, line / branch | Tests |
|---|---|---|---|
| Commons Text 1.15.0 | 83.7 / 77.0, 84.4 / 77.9, 84.2 / 77.7 | 84.9 / 79.1, 84.9 / 79.2, 85.1 / 79.3 | 1523, 1526, 1522 |
| Commons Lang 3.17.0 | 79.5 / 71.8, 79.6 / 72.0, 79.6 / 72.0 | 80.8 / 74.0, 80.8 / 74.0, 80.8 / 73.9 | 5910, 5909, 5910 |
| Commons Collections4 4.6.0 | 72.7 / 63.7 in all three | 74.9 / 68.3, 75.0 / 68.4, 75.0 / 68.4 | 5391, 5398, 5400 |
| jline 3.21.0 | 37.2 / 24.6, 37.3 / 24.6, 37.3 / 24.7 | 37.6 / 25.1, 37.3 / 24.8, 37.9 / 25.5 | 2185, 2172, 2178 |

The step-by-step ablation, what is still out of reach and why are in `0929ObjectModel/REPORT.md`.

### Compared with EvoSuite 1.2.0 and Randoop 4.3.3

Same machine, same dependency classpath, one measurement procedure for all tools (JaCoCo over the whole subject jar, so
the percentages are lower than Justin's own report above, which counts the classes in scope). Line / branch coverage and
generation time; details, raw data layout and the tool defects met on the way are in `0919Compare/REPORT.md` and
`0919Compare/ISSUES.md`.

| Subject | Justin 0.8.4 | EvoSuite (60 s per class, 8 classes in parallel) | Randoop (8x Justin's wall time) |
|---|---|---|---|
| Commons Text 1.15.0 | 75.2% / 68.2%, 1.3 min | 67.2% / 61.0%, 18.8 min | 78.4% / 68.2%, 17.1 min |
| Commons Lang 3.17.0 | 77.1% / 69.1%, 3.4 min | 74.9% / 69.9%, 33.4 min | 64.4% / 49.8%, 44.9 min |
| Commons Collections4 4.6.0 | 62.0% / 52.9%, 2.4 min | 62.0% / 54.0%, 61.3 min | run did not finish |
| jline 3.21.0 | 36.9% / 21.9%, 9.7 min | 25.4% / 18.6%, 38.9 min | 32.9% / 15.0%, 43.6 min |

Randoop's time limits were derived from Justin 0.8.3's generation times; its numbers were not re-run for 0.8.4.
All Justin tests pass when re-executed; EvoSuite has 12 to 100 failing tests per library, Randoop 0 to 53.

Details and per-package experiments are in PLAN.md.
