# Terminal Benchmarks Agent Guide

`ketraterm-benchmarks` owns the JMH runner and benchmarks of public terminal APIs.
Benchmarks of internal Swing helpers live in `ketraterm-ui-swing/src/jmh` and
are compiled there; this module generates and packages their JMH harness too.

## Boundary

Benchmarks may depend on production modules to measure public behavior, but they
must not introduce production-only APIs or change terminal semantics for easier
measurement.

Keep benchmark setup realistic and explicit. Prefer stable, repeatable terminal
content over random data unless the benchmark is specifically measuring a random
workload.

## Testing

Benchmark code should compile with:

```text
./gradlew :ketraterm-benchmarks:jmhJar
```

Run JMH when performance numbers are needed:

```text
./gradlew :ketraterm-benchmarks:jmh
```

Keep allocation measurements in JMH with its GC profiler. Unit tests should
assert rendering, cache reuse, invalidation, and lifecycle semantics without
depending on JVM allocation counters or warmup timing. Keep internal Swing
benchmarks in the Swing module's associated `jmh` compilation so Gradle and IDE
visibility agree; do not widen production visibility for measurement.
