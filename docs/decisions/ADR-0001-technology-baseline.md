# ADR-0001 — Technology baseline

- Status: Accepted
- Date: 2026-10-05

## Context

DevMate is a long-lived personal platform. It needs a current LTS JDK and a stable Spring line that the MCP Java SDK
and Spring AI support. Versions were checked against Maven Central on 2026-10-05:

| Artifact                                     | Available                                  | Chosen   |
|----------------------------------------------|--------------------------------------------|----------|
| JDK                                          | 25.0.4.1 LTS (Microsoft build)             | 25       |
| `spring-boot-starter-parent`                 | 4.0.8 · 4.1.0 · **4.1.1** · 4.2.0-M2       | 4.1.1    |
| Maven                                        | 3.9.11                                     | 3.9.11   |
| `io.modelcontextprotocol.sdk:mcp-bom`        | 1.1.4 · 2.0.0 · **2.0.1**                  | (M1/M2)  |
| `org.springframework.ai:spring-ai-bom`       | 2.0.0 · **2.0.1** · 2.1.0-M1               | (M1/M2)  |

Compatibility checks:
- Spring AI 2.0.1 modules are compiled against `spring-boot-autoconfigure` **4.1.1**, the same as our baseline.
- MCP SDK 2.0.1 ships `mcp-json-jackson3`, which matches Boot 4's Jackson 3. Its `mcp-core` needs only
  reactor-core, slf4j and the servlet API, all of which run on JDK 25.

## Decision

- Compile for Java 25 (`java.version=25`, so `maven.compiler.release=25`). The enforcer rejects JDKs older than 25
  and Maven older than 3.9.
- Inherit `spring-boot-starter-parent:4.1.1` in the root POM. Child POMs declare no versions.
- Ship the Maven wrapper pinned to 3.9.11.
- Use Web MVC on virtual threads, not WebFlux.
- Choose between the MCP SDK and Spring AI MCP when M1/M2 start, in a separate ADR. Neither is a dependency yet.

## Alternatives

- **Java 21 LTS.** Supported, but older than the requirement and misses JDK 22–25 features (e.g. stable
  scoped values, flexible constructor bodies, compact source files).
- **Spring Boot 4.2.0-M\*.** Pre-release, so rejected for the baseline.
- **Spring Boot 4.0.x.** Older line. Spring AI 2.0.1 is built against 4.1.x.
- **WebFlux.** Reactive code everywhere, for a workload (a handful of slow LLM/tool calls) that virtual threads
  handle with plain blocking code.
- **Gradle.** Works fine, but Maven multi-module was required.

## Consequences

- The build needs JDK 25 on `JAVA_HOME`. On this machine the system default is 17, so set `JAVA_HOME` explicitly.
- Mockito's inline mock maker is loaded as a declared `-javaagent` (root `pluginManagement`), because JDK 21+
  warns about self-attaching agents and will block them later.
- Maven 3.9.11's bundled Guice still prints an `sun.misc.Unsafe` deprecation warning on JDK 25. It comes from the
  build tool, not DevMate code, and goes away with a future Maven release.
- Moving to a newer Boot patch (4.1.x) means changing one line, the root parent version.
