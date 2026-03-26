# StreamsHub MCP: Strimzi MCP Server

A Model Context Protocol (MCP) server that gives DevOps engineers read-only tools, resources, and prompt templates to get information about their Strimzi Operator and Operands deployments on Kubernetes.
LLM clients connect to this MCP server and use its capabilities to answer questions about Strimzi infrastructure in natural language.

## Current situation

Right now, DevOps engineers need to use many different tools to check their Strimzi Kafka deployments.
They have to:

- Run `kubectl` commands to check Kafka clusters, KafkaTopics, and other Strimzi resources.
- Look through many logs, from many different pods, either directly in Kubernetes or via external log aggregator tooling.
- Check states of the metrics before and after a specific incident occurs.
- Figure out how different Strimzi resources relate to each other.
- Spend precious time gathering context when something goes wrong (collecting logs, metrics, operands states, etc.).

## Motivation

Strimzi simplifies Kafka management on Kubernetes a lot, but it is still very complex.
When something goes wrong, engineers need to connect information from many places (custom resource statuses, operator logs, broker and controller pod logs, metrics, and network configuration) to figure out what happened.
Doing this manually takes a lot of time and is easy to get wrong, especially under pressure.

An MCP server helps with this by giving LLMs structured access to all of this data through Strimzi specific tools.
The MCP server itself does not diagnose issues — it is a data provider that returns clean, filtered, and structured information.
The LLM, guided by [prompt templates](#mcp-prompt-templates) that encode Strimzi expertise (what to check, in what order, and what specific conditions or log patterns indicate problems), interprets the data, correlates findings across resources and logs, and produces a diagnosis.
Instead of running dozens of `kubectl` commands and mentally connecting the results, engineers can describe the problem in natural language and let the LLM gather and connect the information.

Where we see the most value:

- **Incident response**: Instead of manually running `kubectl get kafka`, `kubectl describe kafkanodepool`, `kubectl logs` and piecing together the picture, the LLM gathers all relevant context at once.
  For example: "My Kafka cluster in namespace `production` has been NotReady for 10 minutes, what's wrong?" triggers resource status checks, operator log analysis, and pod health inspection in seconds.
- **Onboarding and knowledge sharing**: Engineers who don't know Strimzi and Kafka well yet can still diagnose problems because the prompt templates guide the LLM through the same steps an experienced engineer would follow.

### Why not just use kubectl with an LLM?

Just giving an LLM access to `kubectl` has several limitations:

- **Too much noise in context**: Raw Kubernetes YAML includes managed fields, annotations, last-applied-configuration, and other metadata that fills up the LLM context without adding diagnostic value.
  A typical `kubectl get kafka -o yaml` response is too many lines, some of it is noise.
- **Security risks**: A generic `kubectl` tool with write access can modify or delete resources.
  An LLM might run destructive commands if prompted maliciously or just by misunderstanding.
  Existing tools like the [containers/kubernetes-mcp-server](https://github.com/containers/kubernetes-mcp-server) allow CRUD operations, which is not something we want in production.
- **No guided diagnosis**: A generic tool doesn't know which Strimzi conditions mean problems or which logs are relevant.
  Without [prompt templates](#mcp-prompt-templates), the LLM has no structured debugging process and may miss important things.

## Use Cases & Example Interactions

Below are typical DevOps questions and how the MCP server helps answer them.
In these examples, the user selects an [MCP Prompt Template](#mcp-prompt-templates) that tells the LLM what tools to call and in what order.
The user can also attach [MCP Resources](#mcp-resources) to provide the LLM with additional context about the cluster.

### "Why is my Kafka cluster not ready?"

**User question**: "The Kafka cluster `my-cluster` in namespace `kafka-prod` has been NotReady for 15 minutes. What's going on?"

**MCP flow** (user selects the `diagnose-cluster-issue` prompt template, which guides the LLM to call the following tools):
1. `get_kafka_cluster` - reads Kafka CR status and conditions, finds `NotReady` condition with reason
2. `get_strimzi_operator_logs` - reads Strimzi operator logs filtered to the relevant time window and namespace, finds reconciliation errors
3. `get_kafka_cluster_pods` - checks broker pod statuses, finds one pod in CrashLoopBackOff
4. LLM correlates findings and decides pod logs are needed
5. `get_kafka_cluster_logs` - reads logs from the failing pod (including previous container logs), finds OOM kill

**Expected output summary**: broker-2 is in CrashLoopBackOff due to OOM kills (container memory limit is 2Gi but the JVM heap requires more), which is blocking the cluster reconciliation.
The suggestion would be to increase the memory limit in the KafkaNodePool resource.

### "Show me all Kafka clusters across namespaces"

**User question**: "Give me an overview of all Kafka deployments in the cluster."

**MCP flow**:
1. `list_kafka_clusters` - discovers all Kafka CRs across namespaces
2. `get_kafka_cluster` - reads status of each cluster (batched)
3. `get_strimzi_operator` - checks Strimzi operator deployment status

**Expected output summary**: A table of clusters with their namespace, version, number of brokers, readiness status, and any warning conditions.

### "What are the bootstrap addresses and TLS configuration?"

**User question**: "How do I connect to `my-cluster` from outside the Kubernetes cluster?"

**MCP flow**:
1. `get_kafka_bootstrap_servers` - reads listener configurations from Kafka CR status
2. `get_kafka_cluster_certificates` - reads TLS certificate metadata (expiry dates, issuer, SANs) from Strimzi-managed secrets and authentication type configured on listeners (requires opt-in sensitive Role)

**Expected output summary**: Each listener with its type (internal/external), bootstrap address, port, TLS status, and authentication method.
For external listeners, also the route/ingress hostname and the certificate expiry date.

### "Why did broker pods restart overnight?"

**User question**: "I noticed broker pods restarted overnight, what happened?"

**MCP flow** (the prompt template instructs the LLM to ask for a time range when the user doesn't specify one):
1. LLM asks - "Between what hours?" - user answers "2am and 4am"
2. `get_kafka_cluster_pods` - reads pod restart counts and last termination reasons
3. `get_kafka_cluster_logs` - reads previous container logs (from before the restart) for affected pods
4. `get_strimzi_operator_logs` - checks if the operator triggered a rolling update in that time window

**Expected output summary**: Whether restarts were caused by an operator-initiated rolling update (configuration change, certificate renewal) or by pod failures (OOM, liveness probe timeout).

### "Are there any problematic KafkaTopics?"

**User question**: "Check if there are any KafkaTopics with issues in namespace `kafka-prod`."

**MCP flow**:
1. `list_kafka_topics` - finds all KafkaTopic CRs in the namespace (paginated if there are many)
2. `get_kafka_topic` - reads status and conditions for each topic, filters for non-Ready topics
3. LLM reports - 150 topics found, 3 are not Ready. Shows details for each problematic topic.

**Expected output summary**: 3 KafkaTopics have issues — `orders-events` has a `NotReady` condition due to insufficient brokers for the configured replication factor, `audit-log` has a stalled reconciliation, and `temp-test` has a config mismatch between the CR spec and the actual topic config.

## Proposal

The Strimzi MCP server lives in the `streamshub-mcp` project as the first module in a mono-repo.
It is built with [Quarkus](https://quarkus.io/) and uses the [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client) with the [Strimzi API](https://github.com/strimzi/strimzi-kafka-operator/tree/main/api) for typed access to Strimzi custom resources.
See the [Implementation](#implementation) section for the full technology stack.

### Project Structure (mono-repo)

The `streamshub-mcp` project is a Maven multi-module project where each MCP server is an independent module that produces its own container image.

```
streamshub-mcp/
├── pom.xml                    # Parent POM with shared dependencies and build config
├── common/                    # Shared utilities (K8s client helpers, DTO base classes, security utilities)
├── strimzi-mcp/               # Strimzi MCP server module (this proposal)
├── kafka-mcp/                 # Future: Kafka-level MCP server (topics, consumer groups, etc.)
├── console-mcp/               # Future: Console MCP server
└── kroxylicious-mcp/          # Future: Kroxylicious MCP server
```

Each module is independently deployable as a separate container image.
This way we avoid polluting the LLM context with unrelated tool definitions, so each MCP server focuses on a single domain.
Shared logic (Kubernetes client config, DTO serialization, security utilities, log filtering) lives in the `common` module.

**KRaft only**: Strimzi MCP server is designed for Kafka clusters running in KRaft mode.
It won't support ZooKeeper-based deployments, as Strimzi already uses KRaft as the standard.

**Infrastructure only**: Strimzi MCP server works only at the Kubernetes level.
It reads Strimzi custom resources, pod status, configurations, and logs.
It can read metrics exposed by broker and controller pods via HTTP endpoints (Strimzi Metrics Reporter or JMX exporter), but it does NOT connect to Kafka via the Kafka protocol.
It does NOT access topic data, consumer groups, or perform any Kafka admin operations.
A separate `kafka-mcp` module would cover Kafka protocol-level functionality in the future.

### MCP Tools

Each tool accepts structured JSON input validated against a JSON Schema and returns a structured DTO response.

#### Resource Discovery
- **List Strimzi Resources**: Find Strimzi operands across namespaces.
  Initial scope: Kafka, KafkaNodePool, KafkaTopic, and Strimzi operator.
  Other CRs (KafkaUser, KafkaConnect, KafkaConnector, KafkaBridge, KafkaRebalance, KafkaMirrorMaker2) can be added in a follow-up proposal.
  Accepts optional namespace and label selector filters.
  For resource types that can exist in large numbers (KafkaTopic), results are paginated with a configurable page size to avoid oversized responses.
- **Check Resource Status**: Read status, conditions, and observed generation from supported Strimzi custom resources.
- **Operator Status**: Get Strimzi operator deployment status, version, and readiness.

#### Network and Connectivity
- **Find Bootstrap Servers**: Read Kafka bootstrap server addresses from the Kafka CR status.
  Returns listener type, address, port, and protocol.
- **Cluster Certificates**: Read TLS certificate metadata (expiry date, issuer, SANs) and authentication type from Strimzi-managed Kubernetes secrets (`get_kafka_cluster_certificates`).
  Requires the opt-in sensitive Role. See [Output sanitization](#security--rbac) for details on what is excluded.

#### Logs and Pod Info
- **Read Logs**: Get logs from Kafka broker and controller pods, Strimzi operator, and other Strimzi related pods.
  See [Log Handling](#log-handling) for filtering, pagination, and security details.
- **Pod Status**: Check pod status, restart counts, termination reasons, and resource allocation (CPU, memory, storage requests/limits).

#### Metrics
- **Read Metrics**: Access metrics for diagnostic purposes.
  See [Metrics Strategy](#metrics-strategy) for details on supported approaches.

#### Composite Diagnosis
- **Diagnose Cluster**: A server-driven diagnostic tool that internally gathers data from multiple sources (CR status, pod status, operator logs, pod logs), uses [Sampling](#sampling-and-elicitation) to ask the LLM to analyze intermediate results, and uses [Elicitation](#sampling-and-elicitation) to ask the user for input when the situation is ambiguous (e.g., multiple clusters found).
  Returns a consolidated diagnostic summary in a single tool call.
  This complements the fine-grained tools above — see [Sampling and Elicitation](#sampling-and-elicitation) for details on the two approaches.

#### Tool Output Format & Examples

Each tool returns data as a structured JSON DTO that removes noise from raw Kubernetes API responses.

**What DTOs remove**:
- `metadata.managedFields`: internal Kubernetes bookkeeping, typically 100+ lines of noise
- `metadata.annotations` with `kubectl.kubernetes.io/last-applied-configuration`: duplicated resource spec
- Internal status fields not relevant to diagnostics (e.g., `observedGeneration` is kept, but raw status metadata is removed)
- Empty or null fields

**What DTOs keep and surface**:
- Resource identity: name, namespace, labels
- Current status and all conditions with timestamps
- Configuration relevant to diagnostics (listeners, storage, replicas, resource limits)
- Relationships between resources (which KafkaNodePools belong to which Kafka cluster)

##### Example: `get_kafka_cluster` tool

**Input**:
```json
{
  "namespace": "kafka-prod",
  "name": "my-cluster"
}
```

**Output**:
```json
{
  "name": "my-cluster",
  "namespace": "kafka-prod",
  "kind": "Kafka",
  "kafkaVersion": "4.2.0",
  "readiness": "NotReady",
  "conditions": [
    {
      "type": "NotReady",
      "status": "True",
      "reason": "PodNotReady",
      "message": "1 out of 3 brokers not ready",
      "lastTransitionTime": "2025-05-10T14:32:00Z"
    }
  ],
  "listeners": [
    {
      "name": "plain",
      "type": "internal",
      "bootstrapAddress": "my-cluster-kafka-bootstrap.kafka-prod.svc:9092"
    },
    {
      "name": "tls",
      "type": "route",
      "bootstrapAddress": "my-cluster-kafka-tls-bootstrap-kafka-prod.apps.example.com:443"
    }
  ],
  "replicas": {
    "expected": 3,
    "ready": 2
  }
}
```

##### Example: `get_kafka_cluster_pods` tool

**Input**:
```json
{
  "namespace": "kafka-prod",
  "clusterName": "my-cluster",
  "component": "kafka"
}
```

**Output**:
```json
{
  "pods": [
    {
      "name": "my-cluster-broker-0",
      "nodePool": "broker",
      "phase": "Running",
      "ready": true,
      "restartCount": 0,
      "resources": {
        "cpuRequest": "1",
        "cpuLimit": "2",
        "memoryRequest": "4Gi",
        "memoryLimit": "4Gi"
      }
    },
    {
      "name": "my-cluster-broker-2",
      "nodePool": "broker",
      "phase": "Running",
      "ready": false,
      "restartCount": 5,
      "lastTerminationReason": "OOMKilled",
      "lastTerminationTime": "2025-05-10T14:30:00Z",
      "resources": {
        "cpuRequest": "1",
        "cpuLimit": "2",
        "memoryRequest": "2Gi",
        "memoryLimit": "2Gi"
      }
    }
  ]
}
```

#### Error Handling

When a tool encounters an error, it returns an MCP error response with `isError: true` and a message describing the problem.
Quarkus MCP Server handles this automatically — exceptions thrown by tool methods are converted into error responses.

Common error scenarios:
- **Resource not found**: The requested Kafka cluster, KafkaNodePool, or KafkaTopic does not exist. The tool returns a clear "not found" message with the resource type, name, and namespace.
- **Namespace not accessible**: The MCP server's ServiceAccount does not have RBAC permissions for the requested namespace. The tool returns a permission denied error.
- **Kubernetes API unreachable**: The cluster API server is down or the MCP server lost connectivity. The tool returns a connection error.
- **Request timeout**: A Kubernetes API call takes too long (e.g., listing thousands of KafkaTopics). The tool returns a timeout error with a suggestion to narrow the query (e.g., add a namespace filter).

The LLM receives these error responses and can decide how to proceed — retry with different parameters, ask the user for clarification, or report the issue.

### MCP Resources

MCP Resources give LLM clients structured context that they can attach to conversations without explicit tool calls.
When a user says "I'm working on cluster X in namespace Y", the client can attach the relevant resource and the LLM has immediate context.

Quarkus MCP Server supports resources and resource templates out of the box, see the [Implementing Resources](https://docs.quarkiverse.io/quarkus-mcp-server/dev/guides-implementing-resources.html) guide.

Defined resource URIs:

- `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/status` - Current status and conditions of a Kafka cluster, including readiness, version, listener addresses, and reconciliation state.
- `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/topology` - Cluster topology: node pools, broker IDs, roles, rack assignments, and replica counts.
- `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkanodepools/{name}/status` - KafkaNodePool status, ready replicas, roles, and storage configuration.
- `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkatopics/{name}/status` - KafkaTopic status, conditions, and topic configuration (partitions, replicas, config overrides).
- `strimzi://operator.strimzi.io/namespaces/{namespace}/clusteroperator/{name}/status` - Strimzi operator deployment status, version, and managed namespaces.

The URI hierarchy follows the Kubernetes API structure (`{apiGroup}/namespaces/{namespace}/{resource}/{name}`) so that the resource paths are familiar to Kubernetes users.
The operator URI uses the same pattern for consistency, even though the operator is a Kubernetes Deployment, not a Strimzi CRD.

Dedicated MCP Resource URIs for other Strimzi CRs (KafkaConnect, KafkaUser, etc.) are out of scope for the initial implementation and can be added later.

#### Resource Subscriptions

MCP clients can subscribe to resource changes.
When a Strimzi resource status changes (e.g., a Kafka cluster goes from Ready to NotReady), the MCP server notifies subscribed clients.
This lets LLM agents detect and investigate issues without polling.

For example, an agent subscribed to `strimzi://kafka.strimzi.io/namespaces/production/kafkas/my-cluster/status` gets a notification when the cluster status changes.
The subscription notifies about any status change — the client is responsible for reading the updated resource, checking the details, and deciding whether to act (e.g., start diagnosis if the cluster became NotReady).

### MCP Prompt Templates

Prompt templates encode the practical expertise of an experienced Strimzi engineer as a sequence of diagnostic steps.
For example, an engineer knows that a `NotReady` Kafka CR usually means checking KafkaNodePool replica counts first, then operator logs, then pod logs — prompt templates tell the LLM to follow that same process, even if the user doesn't know Strimzi internals.

Quarkus MCP Server supports prompt templates via the `@Prompt` annotation, see the [Implementing Prompts](https://docs.quarkiverse.io/quarkus-mcp-server/dev/guides-implementing-prompts.html) guide.

#### Incident Diagnosis Template

Template name: `diagnose-cluster-issue`

Parameters: `cluster_name`, `namespace` (optional), `symptom` (optional)

When the user selects this template, the MCP server generates a prompt message that guides the LLM through a structured diagnostic workflow.
Below is an example of what the generated prompt can look like:

```
You are diagnosing a Kafka cluster issue for cluster `my-cluster` in namespace `kafka-prod`.
The reported symptom is: NotReady for 15 minutes.

Follow these steps in order. After each step, analyze the results before proceeding to the next.

## Step 1: Check Kafka cluster status
Use `get_kafka_cluster` to retrieve the cluster status and conditions.
Look for: NotReady conditions, stalled reconciliation,
mismatched observed/expected generation, warning conditions.

## Step 2: Check KafkaNodePool statuses
Use `list_kafka_node_pools` to list all node pools for this cluster.
For any pool that looks unhealthy, use `get_kafka_node_pool` for details.
Look for: pools with fewer ready replicas than expected,
pools in non-Ready state, role mismatches.

## Step 3: Check Strimzi operator
Use `list_strimzi_operators` to find the operator managing this cluster.
Use `get_strimzi_operator_logs` with `sinceMinutes: 15` to read recent operator logs.
Look for: reconciliation errors, exceptions, warnings related to
`my-cluster`, repeated error patterns.

## Step 4: Check pod health
Use `get_kafka_cluster_pods` to check all pods for the cluster.
Look for: CrashLoopBackOff, Pending pods, high restart counts,
pods not in Running phase, containers not ready.

## Step 5: Read pod logs from unhealthy pods
For any unhealthy pods found in Step 4, use `get_kafka_cluster_logs`
with keywords `["ERROR", "Exception", "OOM"]` and `sinceMinutes: 15` to get recent error logs.
Look for: OOM kill messages, disk full errors, connection refused,
`OutOfMemoryError`, `IOException`.

## Step 6: Correlate and summarize
Correlate the findings from all steps.
Distinguish between:
- Operator-initiated changes (rolling updates, certificate renewal, configuration changes)
- Infrastructure failures (OOM, disk full, node issues)
- Configuration errors (invalid resource specs, missing secrets)

Provide a clear summary of the root cause and actionable recommendations.
```

The prompt template tells the LLM exactly which tools to call, what to look for in the results, and how to correlate findings across steps.
The tool names in the template match the actual MCP tool names registered by the server.

#### Connectivity Troubleshooting Template

Template name: `troubleshoot-connectivity`

Parameters: `namespace`, `cluster_name`, `listener_name` (optional)

Guides the LLM to check listener configuration, bootstrap addresses, TLS certificate validity, authentication settings, and related Kubernetes services/routes.

### Sampling and Elicitation

The MCP server supports two approaches to diagnostic workflows:

**Client-driven (prompt templates + fine-grained tools)**: The user selects a [prompt template](#mcp-prompt-templates), which the LLM then follows — calling fine-grained tools one by one (`get_kafka_cluster`, `get_kafka_cluster_logs`, etc.), reasoning about results between calls, and asking the user for clarification when needed.
This works with every MCP client since prompt templates are just text and tools are standard MCP tool calls.

**Server-driven (composite tools with Sampling and Elicitation)**: The composite `diagnose_cluster` tool internally orchestrates multiple steps.
During execution, it uses two MCP features to interact with the client:

- [Sampling](https://modelcontextprotocol.io/specification/2025-11-25/client/sampling): The server sends intermediate data (e.g., CR status + operator logs gathered so far) to the LLM and asks it to analyze the findings and decide whether deeper investigation (e.g., pod logs) is needed — all within a single tool call.
- [Elicitation](https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation): The server asks the user for input mid-execution, for example "Which namespace?" when the query is ambiguous, or "What time range?" before retrieving logs.

This provides a more streamlined experience with fewer round trips, but requires MCP client support for Sampling and Elicitation.
Composite tools gracefully fall back when the client doesn't support these features (e.g., skipping Sampling and returning raw data, or using default values instead of Elicitation).

Quarkus MCP Server already [supports both features](https://docs.quarkiverse.io/quarkus-mcp-server/dev/guides-client-integration.html).
Both are injected as parameters into `@Tool` methods and used via a builder API.

### Implementation

#### Technology
- **Quarkus**: Java framework with fast startup, low memory usage, and native compilation support.
- **Fabric8 Kubernetes Client**: Java library for Kubernetes API access and custom resource support.
- **Strimzi API**: Java library built on top of Fabric8 for typed access to Strimzi custom resources.
- **Quarkus MCP Server**: Provides MCP protocol support and tool definition framework. Supports both SSE (Server-Sent Events) and Streamable HTTP transports out of the box.
- **Quarkus extensions**: Logging (JSON output), Config (environment-based configuration), Health (liveness and readiness endpoints).

#### Pluggable Architecture

The proposal mentions three pluggable systems: [log providers](#log-handling), [metrics providers](#metrics-strategy), and [guardrail filters](#prompt-injection-protection).
All three use Quarkus CDI (Contexts and Dependency Injection) but follow two different patterns:

**Providers (logs, metrics)** — single active implementation, swappable via configuration:

1. Define a Java interface for each provider (e.g., `LogProvider`, `MetricsProvider`).
2. Implement the default version as a CDI bean (e.g., `KubernetesLogProvider` that reads pod logs via Fabric8, `PodScrapingMetricsProvider` that reads HTTP endpoints).
3. Select the active implementation via configuration using Quarkus `@LookupIfProperty` or `@IfBuildProfile` annotations.
4. Custom implementations can be packaged as separate Maven modules and added to the container image.

For example, a user who wants to use an external log aggregation system would add the provider module to the image and set `mcp.log.provider=custom-provider` in the configuration.
The tool layer stays the same regardless of which provider is active.

**Filters (guardrails)** — multiple implementations chained together:

1. Define a `GuardrailFilter` interface with an `apply` method.
2. Each filter is a CDI bean annotated with `@Priority` to control execution order.
3. All filter beans are injected as an ordered list using Quarkus `@Any` with `Instance<GuardrailFilter>`.
4. Every request passes through all active filters in priority order — input validation first, then output sanitization, then any custom filters.

This way filters compose rather than replace each other.
Each MCP server module can define its own filters with different priorities, and individual filters can be enabled or disabled via configuration.
Provider and filter interfaces live in the `common` module so they can be reused by other MCP servers.

#### Security & RBAC

**Dedicated Service Account and ClusterRole**:
The MCP server uses its own Service Account with a minimal ClusterRole.
It does NOT use the Strimzi Cluster Operator Service Account.

RBAC is split into two layers: a **ClusterRole** for non-sensitive resources (safe to grant cluster-wide) and an optional **Role** for sensitive resources (opt-in per namespace).
Both are modeled on the existing [strimzi-view](https://github.com/strimzi/strimzi-kafka-operator/blob/main/packaging/install/strimzi-admin/020-ClusterRole-strimzi-view.yaml) ClusterRole.

**ClusterRole** (default, non-sensitive):

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: streamshub-strimzi-mcp
  labels:
    app: streamshub-strimzi-mcp
rules:
  # Strimzi custom resources
  - apiGroups: ["kafka.strimzi.io"]
    resources:
      - kafkas
      - kafkanodepools
      - kafkatopics
      # - kafkausers # out of initial scope
      # - kafkaconnects # out of initial scope
      # - kafkaconnectors # out of initial scope
      # - kafkabridges # out of initial scope
      # - kafkarebalances # out of initial scope
      # - kafkamirrormaker2s # out of initial scope
    verbs: ["get", "list", "watch"]
  - apiGroups: ["core.strimzi.io"]
    resources:
      - strimzipodsets
    verbs: ["get", "list", "watch"]
  # Strimzi operator and pod status
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list"]
  # Pods, logs, and services
  - apiGroups: [""]
    resources: ["pods", "pods/log", "services"]
    verbs: ["get", "list"]
  # ConfigMaps (logging overrides, external configuration references)
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["get", "list"]
  # Routes (OpenShift)
  - apiGroups: ["route.openshift.io"]
    resources: ["routes"]
    verbs: ["get", "list"]
  # Ingresses
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list"]
  # Leases (operator leader election status)
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "list"]
```

**Role** (opt-in per namespace, sensitive resources):

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: streamshub-strimzi-mcp-sensitive
  namespace: kafka-prod  # deployed per namespace where access is needed
  labels:
    app: streamshub-strimzi-mcp
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get"]
  # Only required when using the default pod-scraping metrics provider.
  - apiGroups: [""]
    resources: ["pods/proxy"]
    verbs: ["get"]
```

The sensitive Role is opt-in: deployers add it only to namespaces where certificate checking or direct metrics scraping is needed.
The `secrets` permission only grants `get` (not `list`) because Strimzi secret names follow a predictable pattern derived from the Kafka CR name (e.g., `{cluster}-cluster-ca-cert`, `{cluster}-clients-ca-cert`).
Since RBAC cannot restrict access by label, the MCP server enforces an application-level check: it only requests secrets whose names match the Strimzi naming convention and verifies the `strimzi.io/cluster` label on the returned secret before processing it.

**Authorization model**:
The initial authorization model relies entirely on Kubernetes RBAC — the MCP server can only access what its Service Account is allowed to.

For a single-team setup, one MCP server instance with a ClusterRoleBinding (for the ClusterRole) and RoleBindings (for the sensitive Role in each namespace) gives access to Strimzi resources.

For multi-tenant deployments where different teams should only see their own namespaces, the approach is to deploy separate MCP server instances per team, each with:
- Its own ServiceAccount.
- A namespaced RoleBinding (instead of a ClusterRoleBinding) scoped to that team's namespaces.
- The sensitive Role only in namespaces where the team needs certificate or metrics access.
- A separate Service endpoint that only the team's LLM clients connect to.

This way, team A's MCP server can only see Strimzi resources in team A's namespaces, and team B has no access to team A's data.
Access to each MCP server endpoint can be controlled through standard Kubernetes mechanisms (NetworkPolicies, ingress rules, service mesh policies, or a reverse proxy that handles authentication).

**MCP-level authentication and authorization**:
The MCP protocol supports OAuth 2.1 for authentication and authorization ([spec](https://modelcontextprotocol.io/specification/2025-11-25), [Quarkus OIDC example](https://quarkus.io/blog/secure-mcp-oidc-client/)).
This is out of scope for the initial implementation.
MCP-level auth/authz (e.g., user identity propagation via OIDC, Kubernetes user impersonation) can be proposed separately later, as it will be reused by other MCP servers in StreamsHub-MCP.
This will be especially important when a Kafka MCP is added.
The approach should align with how StreamsHub Console handles authorization.

**Output sanitization**:
- TLS secrets: Only certificate metadata is returned (expiry date, issuer, SANs).
  Private keys and certificate bodies are never included in tool responses.
- Secrets: Only secret names and types are returned, never secret data.
- Logs: See [Log Handling](#log-handling) for filtering and sensitive data redaction details.

#### Prompt Injection Protection

All tool inputs are validated against JSON Schemas with strict type checking, enum constraints, and pattern matching.
For example, namespace and resource name inputs are validated against Kubernetes naming conventions (`[a-z0-9]([-a-z0-9]*[a-z0-9])?`).

Tool outputs are structured DTOs, not raw strings, so there is less risk of prompt injection through resource content.
Fields that could contain user-controlled content (labels, annotations, log messages) are sanitized by stripping control characters and limiting length.

Each tool has a maximum response size so it won't fill up the LLM context.

Input validation and output sanitization are implemented as a pluggable filter chain — see [Pluggable Architecture](#pluggable-architecture) for how filters are chained together using CDI `@Priority` ordering.
This way the filters can be shared across all MCP modules in the mono-repo, and more advanced guardrails can be added as additional filter beans without changing the tool implementations.

#### Log Handling

Log retrieval needs some care to avoid returning too much data or leaking sensitive information:

- **Server-side filtering**: Logs are filtered server-side before being returned — the MCP server does the filtering, not the LLM.
  Supported filters:
  - **Severity level**: Filter by log level (ERROR, WARN, INFO) to return only relevant entries. This depends on parsing the log format (log4j2 for Strimzi operator, configurable for Kafka brokers) and is best-effort.
  - **Time range**: `sinceSeconds`, `sinceMinutes`, or `sinceTime` parameters to limit logs to a specific window.
  - **Pod name**: Target specific pods instead of retrieving logs from all pods.
  - **Keyword matching**: A `keywords` parameter accepts a list of keywords to return only matching lines (e.g., `["Exception", "ERROR", "OOM"]` to get only error-related output).
    The server converts keywords to safe, pre-validated patterns internally — users never supply raw regex.
- **Aggregation**: Error messages are grouped by pod and deduplicated when the same message repeats.
  So instead of returning 500 identical lines, the tool returns the message once with a count.
- **Previous container logs**: Supported for diagnosing crashed or restarted pods (`previous: true` parameter).
- **Pagination and size limits**: Logs are returned as a JSON array of strings within the tool response.
  A `tailLines` parameter caps the number of log lines returned per request (default: 200).
  For pagination, the client can pass `sinceTime` (timestamp of the last log line received) to retrieve subsequent entries.
  The server is stateless — it does not track which lines were previously sent.
  To determine if more data is available, the server requests one more line than `tailLines` — if it gets back more, it sets `hasMore = true` in the response and returns only the requested amount.
- **Request throttling**: Log requests are rate-limited to protect the Kubernetes API server from too much load.
- **Sensitive data redaction**: Log lines are scanned for common sensitive patterns (bearer tokens, passwords, connection strings, API keys) and redacted before being returned.
  The default redaction patterns are configurable, and users can add custom patterns via configuration to cover application-specific sensitive data.

**Pluggable log provider**: Log retrieval should be implemented behind a provider interface so that different backends can be used.
The default implementation reads logs directly from Kubernetes pod logs via the Fabric8 client.
Other providers (e.g., for external log aggregation systems) can be added later and selected via configuration.
This keeps the tool layer independent of the log source.

**Limitations**: Direct pod log reading only provides current and previous container logs.
For production use cases that require querying historical logs, a provider for an external log aggregation system would be needed.
This is not part of the initial implementation and should be proposed in a follow-up proposal.

#### Metrics Strategy

**Pluggable metrics provider**: Metrics retrieval should be implemented behind a provider interface, same as [log retrieval](#log-handling).
The default implementation reads metrics directly from broker and controller pod HTTP endpoints, using either the [Strimzi Metrics Reporter](https://github.com/strimzi/metrics-reporter) or the Kafka JMX exporter depending on user configuration.
Other providers (e.g., for Prometheus or other metrics systems) can be added later and selected via configuration.

**Prometheus API integration** (querying an existing Prometheus instance for historical and aggregated metrics) **is explicitly out of scope** for this proposal and can be added as a separate metrics provider later.

**Caveats**:
- Metrics port - The server auto-detects the metrics port from the pod spec container ports (Strimzi Metrics Reporter defaults to 8080, JMX exporter typically 9404).
- Custom metric names - Strimzi and Kafka metric names can be customized by users.
  The MCP server can read the `metricsConfig` from the Kafka CR, which references a ConfigMap with the JMX exporter or Metrics Reporter configuration, to discover metric name mappings programmatically.
- RBAC - Direct pod scraping needs `get` on `pods/proxy` (included in the optional sensitive Role, not the default ClusterRole).
- Direct pod scraping only gives point-in-time metrics, no historical data.
  For historical data, a Prometheus provider would be needed.
- Scraping load - Direct pod scraping adds load to the broker/controller pods.
  During incidents this could make things worse, so metrics requests should be rate-limited (same as log requests).
  For production use, a centralized metrics system that has already collected the data is preferred.

Metrics are mostly useful during ad-hoc incident investigation, for example "Is the broker under heavy load?", "What's the replication lag?", "Are there under-replicated partitions?".
For ongoing monitoring, existing alerting infrastructure (Prometheus alerts, Grafana) is still the primary tool.

**Future extensibility**: Integrations with external logging and metrics systems are not part of this proposal and should be proposed separately.
The pluggable provider architecture for both logs and metrics makes it possible to add such integrations without rewriting the core.

### Deployment & Delivery

The Strimzi MCP server is delivered as a container image built with Quarkus (JVM or native).

**Deployment options**:
- Kubernetes Deployment manifest (provided in the project repository).
- Helm chart (future).

**Deployment resources**:
- A Deployment with the MCP server container.
- A dedicated ServiceAccount.
- A ClusterRole (or Role for namespace-scoped deployments) and corresponding binding.
- A Service for MCP client access.

**Configuration**:
Everything is configured via environment variables:
- `MCP_NAMESPACES` - Comma-separated list of namespaces to watch (empty = all namespaces).
- `MCP_LOG_TAIL_LINES` - Default `tailLines` value for log requests (default: 200).

**Scalability**:
The MCP server is stateless — it holds no session data between requests.
Multiple replicas can run behind a Kubernetes Service for horizontal scaling.
Multiple LLM clients can connect simultaneously since Quarkus handles concurrent requests out of the box.

**Health endpoints**:
- `/q/health/live` - liveness probe.
- `/q/health/ready` - readiness probe (checks Kubernetes API connectivity).

## Differentiation from Existing Tools

Several generic Kubernetes MCP servers already exist, like [containers/kubernetes-mcp-server](https://github.com/containers/kubernetes-mcp-server).
The Strimzi MCP server differs from these in several ways:

| Aspect | Generic K8s MCP | Strimzi MCP                                           |
|--------|----------------|-------------------------------------------------------|
| **Access model** | Often includes CRUD operations | Read-only by design                                   |
| **Output format** | Raw Kubernetes YAML/JSON | Strimzi specific DTOs with noise removed              |
| **Guided diagnosis** | None, LLM must interpret raw resources | Prompt templates encode Strimzi debugging expertise   |
| **Resource relationships** | Flat resource listing | Understands Kafka → KafkaNodePool → Pod relationships |
| **Security** | Full cluster access common | Minimal read-only RBAC, output sanitization           |
| **Log handling** | Raw log dumps | Filtered, aggregated, redacted, size-limited          |

The Strimzi MCP server is complementary to generic Kubernetes MCP tools and doesn't replace them.

## Compatibility

- **Strimzi versions** - Targets Strimzi 0.51.x and newer, using the `v1` Strimzi API exclusively.
  The `v1beta2` API is not supported — this avoids maintaining compatibility with deprecated CRD versions.
- **Kubernetes versions** - Kubernetes 1.30+ and OpenShift 4.16+.
- **MCP protocol** - Follows the current MCP specification.
  Can be updated when MCP evolves.
- **Kubernetes distributions** - Works with OpenShift, EKS, GKE, AKS, and other Kubernetes distributions.

## Affected projects

N/A, this is a new project under StreamsHub.

## Rejected alternatives

### Option 1: Custom CLI Tool instead of MCP

A custom CLI tool could provide Strimzi specific commands as a shortcut for kubectl, including structured output formats like JSON or YAML.
LLM agents can use CLI tools, so a CLI could work with LLMs.
However, MCP and CLI serve different use cases:
- MCP keeps the processing server-side, so the client only needs an MCP connection — no local kubectl access, kubeconfig, or Strimzi CLI installation required.
- MCP Prompt Templates provide multistep diagnostic workflows that combine multiple tools and resources into a guided sequence, while CLI help describes individual commands without orchestrating them together.
- MCP Resources allow the server to proactively push updated state to the client, while a CLI requires the LLM to repeatedly poll for changes.

A CLI could be a useful complement to MCP in the future, but for the initial use case (LLM-driven diagnosis) MCP is a better fit.

### Option 2: Use existing generic Kubernetes MCP server

Existing tools like [containers/kubernetes-mcp-server](https://github.com/containers/kubernetes-mcp-server) were considered but lack read-only access guarantees, Strimzi-specific output filtering, and guided diagnosis.
See the [Differentiation from Existing Tools](#differentiation-from-existing-tools) table for a detailed comparison.