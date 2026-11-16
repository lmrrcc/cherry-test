# Native IDE reporting fixture

Copy this directory to a disposable workspace. Run it through the Java Cherry Test CLI with `CHERRY_TEST_IDE_EVENTS` set to a new JSONL path. Maven must be on PATH (or add the repository's Maven wrapper to the copy). The CLI requires JDK 25; fixture source targets Java 21.

The two actual JUnit tests verify integer discount calculation. JaCoCo reports partially covered branches in `Price.discounted` and an uncovered `deliveryFee` method. No external service or database is used.

For the failing control, change the copied POM's `fixture.fail` value to `true`. One real assertion then fails. `testFailureIgnore` intentionally lets Maven reach JaCoCo reporting, while Cherry Test still returns exit code 1 from the actual failed JUnit result. Restore false for two passing tests.

Surefire XML can omit file and line locations; native events preserve that absence. These results do not establish per-test production-line attribution or source-to-executed-bytecode identity.

The complete protocol and reproduction commands are in `docs/native-ide-events.md` at the repository root. Keep run artifacts outside this source fixture.
