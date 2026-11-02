# Java formatting and static checks (#8)

## Rules

Java follows google-java-format AOSP: four-space indentation, UTF-8, LF line
endings, a final newline and no trailing whitespace, consistent with `.editorconfig`.
Spotless 3.10.4 pins google-java-format 1.33.0. Checkstyle Maven plugin 3.6.0
uses the shared rules in `config/checkstyle/checkstyle.xml` for main and test Java.
Checkstyle rejects tabs, trailing whitespace, wildcard and unused imports,
missing braces and incorrect indentation.

## Local workflow

Run from the repository root:

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check checkstyle:check
.\mvnw.cmd -B verify
```

`spotless:apply` changes source files; inspect the diff before committing.
Both checks are bound to `verify`, which the existing CI build already runs.
No new required check names or GitHub ruleset changes are needed.

## Tests and coverage

Surefire starts Mockito as a Java agent rather than relying on dynamic
self-attachment, which stalled the Java 21 test JVM on the Windows dev machine.
The agent version comes from the Spring Boot-managed `mockito.version` property.
Late evaluation of `@{argLine}` preserves the JaCoCo agent and coverage output.

## Verification

The normal reactor build must pass, and an intentionally malformed temporary Java
source must make both `spotless:check` and `checkstyle:check` fail. Remove the
probe before running the final build or committing. Local verification does not
establish that remote CI has passed; check both PR checks before merging.

## References

- https://github.com/diffplug/spotless/tree/main/plugin-maven
- https://maven.apache.org/plugins/maven-checkstyle-plugin/usage.html
- https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#0.3
