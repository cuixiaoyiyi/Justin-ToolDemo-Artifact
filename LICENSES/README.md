# Rights and third-party attribution

The Justin source snapshot and experiment material are supplied by their authors for this artifact. The source checkout contains no explicit open-source license declaration. Packaging does not assign a new MIT, Apache, or other permissive license to that code. Rights remain with the respective copyright holders; contact the authors for reuse permissions where needed.

Third-party components retain their own licenses. The exact binary/source input hashes and original artifact locations are in `../input-manifest.json`. `embedded/` preserves license, notice, and copying files found in the redistributed JARs; `embedded-notices.json` records the originating JAR/member and SHA-256. A shaded JAR may contain several dependency licenses; a root LICENSE alone does not describe every embedded component.

| Component | Upstream source and licensing information |
|---|---|
| Apache Commons Text, Lang, Collections | [Commons](https://commons.apache.org/), [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); source JARs retain their original headers/notices |
| JLine | [jline3](https://github.com/jline/jline3), BSD 3-Clause license; upstream license copy supplied in `upstream/` |
| jsoup | [jsoup](https://github.com/jhy/jsoup), MIT license; retained source headers/embedded license |
| EvoSuite 1.2.0 | [EvoSuite](https://github.com/EvoSuite/evosuite), LGPL 3.0; preserve COPYING/LESSER and the additional bundled dependency notices |
| ChatUniTest Maven plugin/core 2.1.1 | [plugin](https://github.com/ZJU-ACES-ISE/chatunitest-maven-plugin), [core](https://github.com/ZJU-ACES-ISE/chatunitest-core), MIT as declared in the archived POMs; upstream license copies supplied in `upstream/` |
| JUnit, JaCoCo, Mockito, ASM, SootUp, Z3 and other runtime libraries | Respective upstream projects and the license/notice files preserved with their JARs; inspect the input manifest and embedded notices for exact components |

The ChatTester/HITS configurations are modes in the pinned ChatUniTest Maven plugin. References to the corresponding research tools identify experimental configurations and do not transfer their authorship or licenses to this artifact.
