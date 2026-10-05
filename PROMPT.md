# DevMate — Personal AI Developer Assistant

You are acting as a **Senior Java Architect, Spring Boot Architect, MCP Engineer, and Agentic AI Engineer**.

Your task is to build a production-oriented Personal AI Developer Assistant called:

# DevMate

DevMate will be an **MCP Client + AI Agent platform** designed to help with my daily software engineering work.

The project must be built incrementally through clearly defined milestones.

Do NOT build everything at once.

---

# 1. PRIMARY OBJECTIVE

DevMate should eventually allow me to ask natural-language developer questions such as:

* "Review my changes from today."
* "Why is this API slow?"
* "Find where this Kafka event is produced and consumed."
* "Explain the complete loan creation flow."
* "Find where this class is used."
* "Check why this pod is restarting."
* "Investigate this transaction using its correlation ID."
* "Check whether the latest deployment caused this problem."

DevMate should use MCP tools to gather evidence and then use an LLM/Agent to analyze that information.

Final conceptual architecture:

```text
                         ┌───────────────┐
                         │     User      │
                         └───────┬───────┘
                                 │
                                 ▼
                         ┌───────────────┐
                         │   DevMate     │
                         │   AI Agent    │
                         └───────┬───────┘
                                 │
                                 ▼
                         ┌───────────────┐
                         │   MCP Client  │
                         └───────┬───────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
              ▼                  ▼                  ▼
          Git MCP            Code MCP          File MCP
              │                  │                  │
              ▼                  ▼                  ▼
          Git Repo          Source Code          Files

                        Future integrations

       ┌────────┬────────┬──────────┬──────────┬────────────┐
       ▼        ▼        ▼          ▼          ▼
      DB      Kafka     Redis      OTel       K8s
       │        │        │          │          │
       ▼        ▼        ▼          ▼          ▼
    Database   Events   Cache      Traces     Pods
```

---

# 2. TECHNOLOGY BASELINE

Use the following versions.

## Java

Use:

```text
Java 25 LTS
```

Java 25 is the primary runtime and compilation target.

Do NOT use Java 21 as the baseline.

Use modern Java 25 features where they provide real value.

Use:

* Records
* Pattern matching
* Sealed classes where appropriate
* Modern switch expressions
* Virtual threads where appropriate
* Structured concurrency only where it is stable and appropriate
* Modern collections and APIs
* Immutable data structures where practical

Do NOT use obsolete Java APIs or unnecessarily maintain Java 8/11/17 compatibility.

If a third-party dependency requires an older Java version, explain the compatibility issue before making a change.

---

# 3. SPRING BOOT

Use:

```text
Spring Boot 4.1.x
```

Use the **latest stable 4.1.x patch release available in Maven Central at implementation time**.

IMPORTANT:

Do NOT use:

```text
Spring Boot 3.x
Spring Boot 4.0.x
Spring Boot 4.2.x-M*
Spring Boot 4.2.x-RC*
```

unless a real dependency compatibility issue requires it.

Spring Boot 4.2 milestone/release-candidate versions must NOT be used for the production baseline.

The project should use the latest stable Spring Boot 4.1.x patch release.

---

# 4. BUILD SYSTEM

Use:

```text
Apache Maven 3.9+
```

Use a Maven multi-module project.

Root:

```text
devmate/
```

The root POM must manage:

* Java version
* Spring Boot version
* Dependency versions where necessary
* Plugin versions where necessary
* Common build configuration

Avoid duplicating versions across child POMs.

---

# 5. INITIAL PROJECT STRUCTURE

Start with:

```text
devmate/
│
├── pom.xml
│
├── README.md
│
├── docs/
│   ├── architecture/
│   ├── decisions/
│   └── milestones/
│
├── devmate-client/
│
├── devmate-files-mcp/
│
└── devmate-git-mcp/
```

Do NOT create every future module immediately.

Modules should be introduced only when their milestone starts.

---

# 6. ARCHITECTURAL PRINCIPLES

Follow:

* Clean Architecture
* SOLID
* Dependency Inversion
* Separation of concerns
* Explicit boundaries
* High cohesion
* Low coupling
* Immutable models where appropriate
* Interface-driven design where useful
* Testability
* Secure-by-default design

Avoid:

* God classes
* God services
* Static global state
* Hardcoded credentials
* Hardcoded environment-specific configuration
* Unnecessary abstractions
* Premature optimization
* Premature distributed architecture
* Copy-paste code
* Hidden side effects

Prefer simple implementations first.

Introduce abstractions only when they provide real architectural value.

---

# 7. MCP ARCHITECTURE

MCP must be treated as a first-class protocol boundary.

Conceptually:

```text
LLM / Agent
     │
     ▼
MCP Client
     │
     ├── Connection
     ├── Session
     ├── Tool Discovery
     ├── Tool Registry
     ├── Tool Execution
     └── Error Handling
              │
              ▼
          MCP Server
              │
              ▼
             Tool
```

The MCP Client MUST NOT hardcode the available tools.

Tools must be discovered dynamically through MCP.

The client must support the MCP lifecycle required by the selected SDK/protocol version.

Before implementing MCP-specific classes, verify the currently supported MCP Java SDK/API and use the current stable compatible version.

Do not invent MCP protocol structures when an official SDK/API is available.

---

# 8. MILESTONE ROADMAP

Implement the project through these milestones:

```text
🔵 M0   Foundation
🔵 M1   Files MCP Server
⚪ M2   MCP Client
⚪ M3   LLM Integration
⚪ M4   Git MCP
⚪ M5   Code Intelligence
⚪ M6   RAG
⚪ M7   Database MCP
⚪ M8   Kafka MCP
⚪ M9   OpenTelemetry MCP
⚪ M10  Redis MCP
⚪ M11  Kubernetes/OpenShift MCP
⚪ M12  Full Agentic Investigation
⚪ M13  Security & Human Approval
⚪ M14  Production Hardening
```

> 🔵 = completed · ⚪ = not started

IMPORTANT:

Do NOT skip milestones.

Do NOT implement future milestones prematurely.

Each milestone must produce a usable artifact.

---

# 🔵 MILESTONE 0 — FOUNDATION

## Goal

Create the basic DevMate project.

Technology:

```text
Java 25 LTS
Spring Boot 4.1.x
Maven 3.9+
```

Requirements:

* Multi-module Maven project
* Java 25 configuration
* Spring Boot 4.1.x
* Basic application
* Configuration structure
* Logging
* Actuator
* Health endpoint
* README
* Architecture documentation

Initial module:

```text
devmate-client
```

At this stage DO NOT implement:

* MCP protocol
* LLM
* RAG
* Kafka
* Redis
* Database
* Kubernetes
* OpenTelemetry MCP

## Definition of Done

Application starts successfully.

Endpoint:

```text
GET /actuator/health
```

must return:

```text
UP
```

Create:

```text
docs/milestones/M0.md
```

---

# 🔵 MILESTONE 1 — FILES MCP SERVER

Create:

```text
devmate-files-mcp
```

Initial MCP tools:

```text
list_files(path)
read_file(path)
search_code(query)
```

Example:

```text
search_code("KafkaTemplate")
```

Expected result:

```text
PaymentProducer.java
PaymentService.java
KafkaConfig.java
```

## Security

Protect against:

* Directory traversal
* Arbitrary filesystem access
* Huge files
* Binary file abuse
* Invalid paths

Configure allowed root directories.

Example:

```yaml
devmate:
  filesystem:
    allowed-roots:
      - /workspace/projects
```

Never allow unrestricted filesystem access.

## Definition of Done

An MCP client/inspector can successfully execute:

```text
tools/list
tools/call
```

for:

```text
list_files
read_file
search_code
```

---

# MILESTONE 2 — MCP CLIENT

Implement the core MCP Client.

Components:

```text
McpConnection
McpSession
McpTransport
ToolRegistry
ToolExecutor
McpClientService
```

Responsibilities:

## Connection

Connect to MCP Server.

## Initialization

Perform MCP initialization.

## Tool Discovery

Discover tools dynamically.

Conceptually:

```text
initialize
    ↓
tools/list
    ↓
Tool Registry
```

## Tool Execution

Support:

```text
tools/call
```

## Error Handling

Handle:

* Connection failure
* Timeout
* Invalid tool
* Invalid arguments
* Server errors
* Session errors
* Transport errors

## Definition of Done

DevMate can:

```text
Connect
 ↓
Initialize
 ↓
Discover tools
 ↓
Register tools
 ↓
Execute tool
 ↓
Receive result
```

Test:

```text
search_code("Kafka")
```

without using an LLM.

This is the first major MCP milestone.

---

# MILESTONE 3 — LLM INTEGRATION

> **Amendment (2026-10-05):** DevMate must be able to use **any model**: hosted providers (Claude, OpenAI,
> Google, Mistral, DeepSeek, Bedrock, Azure OpenAI, any OpenAI-compatible server) **and local Ollama models**
> (e.g. `llama3.2:3b`, `qwen2.5-coder:1.5b`). Provider + model are configuration only:
> `application.yml` has an Anthropic block (active) and an Ollama block (commented). Swap the comments to switch.
> See [ADR-0003](docs/decisions/ADR-0003-model-agnostic-llm.md).

Integrate an LLM abstraction.

Create an abstraction such as:

```text
LlmClient
```

Do not tightly couple core business logic to one model provider.

Architecture:

```text
User
 ↓
Agent
 ↓
LLM
 ↓
Tool Selection
 ↓
MCP Client
 ↓
MCP Server
 ↓
Tool
 ↓
Result
 ↓
LLM
 ↓
Final Answer
```

The LLM must receive dynamically discovered MCP tools.

Do NOT hardcode tools into the agent.

Flow:

```text
MCP tools/list
        ↓
Tool Registry
        ↓
LLM Tool Definitions
```

## Definition of Done

User can ask:

```text
"এই project-এ Kafka কোথায় ব্যবহার করা হয়েছে?"
```

and DevMate can select:

```text
search_code()
```

execute it through MCP and produce a useful answer.

---

# MILESTONE 4 — GIT MCP

Create:

```text
devmate-git-mcp
```

Tools:

```text
git_status()
git_diff()
git_log()
git_show_commit(commitId)
git_branch()
```

Add:

```text
review_changes()
```

Workflow:

```text
git_diff
 ↓
Changed files
 ↓
Read relevant files
 ↓
LLM analysis
 ↓
Code review
```

Review categories:

* Bugs
* Security
* Performance
* Error handling
* Concurrency
* Kafka issues
* Database issues
* Testing
* Maintainability

Example:

```text
Potential Issue:
Kafka retry may result in duplicate processing.

Missing Test:
No timeout test found.

Recommendation:
Add idempotency validation.
```

## Definition of Done

User can ask:

```text
"আজকের আমার changes review করো।"
```

and DevMate can inspect Git changes and generate a structured review.

---

# MILESTONE 5 — CODE INTELLIGENCE

Add:

```text
find_class()
find_method()
find_interface()
find_usages()
find_api()
find_configuration()
```

Goal:

Understand relationships inside the source code.

Example:

```text
"Loan creation flow explain করো।"
```

Expected:

```text
Controller
 ↓
Service
 ↓
Eligibility
 ↓
Kafka
 ↓
Consumer
 ↓
Database
```

Do NOT build a complete compiler/indexing platform unless required.

Start with practical source-code analysis.

---

# MILESTONE 6 — RAG

Add documentation intelligence.

Documents:

```text
README
Architecture docs
ADR
API docs
Business rules
Coding guidelines
Technical documentation
```

Pipeline:

```text
Document
 ↓
Text Extraction
 ↓
Chunking
 ↓
Embedding
 ↓
Vector Store
 ↓
Retrieval
 ↓
LLM
```

Preferred initial vector store:

```text
PostgreSQL + pgvector
```

Support:

* Semantic search
* Metadata filtering
* Source references
* Document versioning where useful

Answer must include source information.

Example:

```text
Answer:
Kafka retry policy is 3 attempts.

Source:
docs/kafka/retry-policy.md
Section: Retry Configuration
```

## Definition of Done

User can ask:

```text
"আমাদের Kafka retry policy কী?"
```

and DevMate answers using project documentation.

---

# MILESTONE 7 — DATABASE MCP

Initially support read-only database investigation.

Primary database:

```text
PostgreSQL
```

Design an abstraction that allows Oracle to be added later.

Tools:

```text
find_table()
describe_table()
execute_read_query()
explain_query()
find_indexes()
```

Security:

```text
SELECT → allowed
INSERT → blocked
UPDATE → blocked
DELETE → blocked
DDL → blocked
```

Use a read-only database user.

Never store credentials in source code.

## Definition of Done

User:

```text
"এই query slow কেন?"
```

DevMate can:

```text
Execute/explain query
 ↓
Inspect indexes
 ↓
Analyze
 ↓
Suggest optimization
```

---

# MILESTONE 8 — KAFKA MCP

Add Kafka integration.

Tools:

```text
list_topics()
get_topic_metadata()
get_partition_info()
get_consumer_lag()
get_consumer_group()
search_message()
find_by_correlation_id()
```

Primary use case:

```text
"এই transaction-এর Kafka flow কোথায় fail করেছে?"
```

Investigation:

```text
Correlation ID
 ↓
Producer
 ↓
Topic
 ↓
Partition
 ↓
Offset
 ↓
Consumer
 ↓
Retry
 ↓
DLQ
```

Kafka security configuration must be externalized.

Never hardcode credentials.

---

# MILESTONE 9 — OPENTELEMETRY MCP

Add observability integration.

Tools:

```text
find_trace()
get_trace()
get_span()
find_slowest_span()
get_service_dependencies()
```

Goal:

Identify:

* Slow service
* Slow method
* Slow database query
* Kafka latency
* Redis latency
* Error spans

Example:

```text
POST /loan

Total: 842 ms

Gateway          12 ms
Controller       18 ms
CustomerService  95 ms
Oracle SELECT    72 ms
Eligibility     420 ms
Kafka Publish    24 ms
```

Agent should identify the primary bottleneck.

---

# MILESTONE 10 — REDIS MCP

Tools:

```text
get_key()
scan_keys()
get_ttl()
get_memory_usage()
get_cluster_info()
```

Initial scope:

READ ONLY.

Use it for:

* Cache miss
* TTL issue
* Memory issue
* Cluster state
* Key investigation

---

# MILESTONE 11 — KUBERNETES / OPENSHIFT MCP

Tools:

```text
get_pods()
get_pod_logs()
describe_pod()
get_deployment()
get_events()
get_resource_usage()
```

Initially use read-only access.

Example:

```text
"Payment service-এর pod কেন restart হচ্ছে?"
```

Agent:

```text
Pod
 ↓
Logs
 ↓
Events
 ↓
Deployment
 ↓
Resource usage
 ↓
Previous logs
 ↓
Root cause
```

---

# MILESTONE 12 — FULL AGENTIC INVESTIGATION

Combine all available tools.

Example:

```text
"Loan API কেন slow এবং recent deployment-এর পরে সমস্যা হয়েছে কিনা analyse করো।"
```

Agent workflow:

```text
1. Find API
2. Find trace
3. Find slowest span
4. Inspect source
5. Inspect Git changes
6. Inspect logs
7. Inspect DB
8. Inspect Kafka
9. Inspect Redis
10. Inspect deployment
11. Correlate evidence
12. Identify root cause
13. Recommend fix
```

The agent must:

* Avoid unnecessary tool calls
* Prefer evidence over assumptions
* Explain why a tool is being used
* Correlate results
* Distinguish facts from hypotheses
* Provide confidence where appropriate

---

# MILESTONE 13 — SECURITY AND HUMAN APPROVAL

Tool permission model:

```text
READ
 ↓
Automatic

WRITE
 ↓
Confirmation

DANGEROUS
 ↓
Explicit Approval
```

Examples:

```text
read_file()        → Auto
git_diff()         → Auto
SELECT             → Auto

git_commit()       → Confirmation
git_push()         → Confirmation

UPDATE             → Confirmation
DELETE             → Strong approval

restart_pod()      → Confirmation
scale_deployment() → Confirmation
```

Implement:

* RBAC
* Tool permission policies
* Audit logging
* Rate limiting
* Secret management
* User confirmation
* Tool execution history

Every sensitive action must be auditable.

---

# MILESTONE 14 — PRODUCTION HARDENING

Before calling DevMate production-ready, implement:

* Retry policies
* Timeout policies
* Circuit breakers where appropriate
* Rate limiting
* Secure secret handling
* Structured audit logs
* Health checks
* Readiness checks
* Graceful shutdown
* Resource limits
* Observability
* Error classification
* Configuration validation
* Security testing
* Integration tests
* Container image
* Docker Compose local environment
* Production deployment documentation

---

# OBSERVABILITY

DevMate itself must be observable.

Use:

```text
OpenTelemetry
```

Capture:

* Request trace
* Agent execution
* LLM call
* MCP tool call
* Tool latency
* Errors
* Token usage where available
* External system latency

Example:

```text
User Request
   ↓
Agent
   ↓
LLM
   ↓
MCP search_code
   ↓
MCP read_file
   ↓
LLM
   ↓
Final Response
```

---

# LOGGING

Use structured logging.

Recommended:

```text
SLF4J
Logback
```

Include where appropriate:

```text
traceId
spanId
requestId
sessionId
toolName
duration
status
error
```

Never log:

* Passwords
* API keys
* Access tokens
* Database credentials
* Sensitive source code unnecessarily

---

# CONFIGURATION

Use:

```text
application.yml
application-{profile}.yml
environment variables
```

Example:

```yaml
devmate:
  mcp:
    servers:
      files:
        transport: ...
        endpoint: ...

      git:
        transport: ...
        endpoint: ...

  security:
    confirmation-required:
      - git_push
      - git_commit
```

Never hardcode environment-specific configuration.

---

# TESTING STRATEGY

Every milestone must include tests.

Use:

```text
JUnit 5
Mockito
Spring Boot Test
Testcontainers where appropriate
```

Testing layers:

```text
Unit Tests
Integration Tests
Contract Tests
End-to-End Tests
```

Important MCP tests:

```text
Connection
Initialization
Tool discovery
Tool execution
Invalid arguments
Timeout
Server unavailable
Malformed response
```

---

# DOCUMENTATION

Maintain:

```text
README.md

docs/
├── architecture/
├── decisions/
└── milestones/
```

Architecture Decision Records must use:

```text
Context
Decision
Alternatives
Consequences
```

Every milestone must update documentation.

---

# GIT WORKFLOW

Use small, meaningful commits.

Examples:

```text
feat: initialize devmate client
feat: add files mcp server
feat: implement mcp tool discovery
feat: implement mcp tool execution
feat: integrate llm tool calling
feat: add git mcp server
```

Do not create giant commits.

---

# CODE QUALITY

Before completing every milestone, run:

```text
mvn clean verify
```

Fix:

* Compilation errors
* Test failures
* Static analysis issues
* Security issues
* Important warnings

Do not claim a milestone is complete if the project does not build successfully.

---

# MILESTONE COMPLETION FORMAT

At the end of every milestone provide exactly:

```text
========================================
MILESTONE STATUS
========================================

Milestone:
Status: COMPLETED / BLOCKED

Implemented:
- ...
- ...

Files Added:
- ...
- ...

Tests:
- ...

Architecture:
- ...

Known Limitations:
- ...

Next Milestone:
- ...
```

Do NOT automatically continue to the next milestone.

Wait for my instruction.

---

# DEVELOPMENT RULE

Do NOT generate the entire project in one giant response.

For every milestone:

1. Explain the goal.
2. Explain the architecture.
3. Identify files to create/change.
4. Implement the milestone.
5. Write tests.
6. Run tests.
7. Fix issues.
8. Update documentation.
9. Provide milestone summary.
10. Stop.

Wait for my instruction before starting the next milestone.

---

# VERSION POLICY

At the beginning of development, verify the currently available stable versions from official sources.

The baseline is:

```text
Java: Spring Boot: Maven:
25 LTS
4.1.x latest stable patch
3.9+
```

Do NOT automatically switch to a pre-release version just because a newer milestone, RC, or early-access release exists.

For example:

```text
Java 27
```

may be newer than Java 25, but Java 25 must remain the production baseline because it is the current LTS.

Likewise:

```text
Spring Boot 4.2.x-M*
```

must not be used as the production baseline while it remains a milestone/pre-release.

If a stable patch release becomes available during development, prefer the latest stable patch release within the selected major/minor line.

---

# FIRST TASK

Start ONLY with:

# MILESTONE 0 — FOUNDATION

Before writing code:

1. Verify Java 25 is available.
2. Verify the latest stable Spring Boot 4.1.x version.
3. Verify Maven 3.9+.
4. Verify MCP Java SDK compatibility requirements.
5. Explain the proposed architecture.
6. Create the initial repository structure.
7. Create the root pom.xml.
8. Configure Java 25.
9. Configure Spring Boot 4.1.x.
10. Create devmate-client.
11. Add Actuator.
12. Add health endpoint.
13. Create README.
14. Create M0 architecture documentation.
15. Add tests.
16. Run:

```text
mvn clean verify
```

Do NOT implement:

* MCP Server
* MCP Client protocol
* LLM
* RAG
* Kafka
* Database
* Redis
* OpenTelemetry MCP
* Kubernetes MCP

until Milestone 0 is successfully completed.

The priority is:

**Correctness → Simplicity → Security → Testability → Extensibility → Performance**

Build DevMate as a real long-term Personal AI Developer Platform, not as a tutorial or throwaway demo.
