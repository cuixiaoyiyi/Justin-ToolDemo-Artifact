# Frozen subjects

Only Commons Text **1.15.0**, Commons Lang **3.17.0**, Commons Collections **4.6.0**, JLine **3.21.0**, and jsoup **1.23.2** are tested subjects. No historical subject versions or earlier result cohorts are included.

`manifest.json` lists the five versions. `../data/frozen-sample.json` and `../data/sample72.csv` specify the exact 72 method identities and denominators. `focal-source/` contains the ten selected classes directly from the frozen source JARs. Complete binary/source JAR pairs and required runtime dependencies are in `../inputs/subjects/` after downloading and restoring `runtime-inputs.zip`.

The `dependencies/` subdirectory inside runtime inputs contains classpath dependencies, not additional tested subjects. In particular, Commons Lang 3.20.0 is the dependency used by Commons Text 1.15.0, while the tested Commons Lang artifact remains 3.17.0. Dependency versions and ordering are preserved in `../input-manifest.json`.
