# Justin and comparison configurations

`justin-source/` is the tracked Maven source snapshot, including all modules needed to build the CLI. Use JDK 17 and run `mvn -DskipTests package` there. The packaged CLI appears at `justin-cli/target/justin-all.jar`.

The Justin binary used in the experiment is `../inputs/tools/justin-all.jar`, SHA-256 `acc1227b2e0fbcb620107af99b77b64224d23703900a78f08887a3e9cbc38aed`. Download it as part of `runtime-inputs.zip` from the release. Rebuilt JAR bytes may differ from this binary. LLM-based annotation inference was enabled for the reported Justin runs.

The same input archive supplies EvoSuite 1.2.0, its standalone runtime, JUnit/JaCoCo measurement tools, and ChatUniTest core/plugin 2.1.1 JAR/POMs. The plugin implements the CHATTESTER and HITS modes used in this experiment. Implementation and binary details are in `../data/implementation.json` and `../data/maven-binary-provenance-readonly.json`.
