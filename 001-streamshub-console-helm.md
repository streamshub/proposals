# Helm Installation Support for StreamsHub Console

This proposal outlines adding official Helm chart support for the StreamsHub Console operator and its Custom Resources (CRs). 
This would provide a standardized installation method and lay the foundation for a broader StreamsHub Helm ecosystem.

## Current situation

There is currently no official Helm chart for the StreamsHub Console operator or its associated Custom Resources. 
Among the broader StreamsHub stack, only Strimzi provides an official Helm chart. 
Components such as Strimzi, Prometheus, and other dependencies each maintain their own Helm charts through their respective upstream projects.

## Motivation

Helm is widely adopted as the standard packaging mechanism for k8s applications.
A large portion of the k8s ecosystem — including GitOps platforms (e.g. Argo CD) and cloud provider marketplaces — relies on Helm as the primary installation mechanism.

By introducing an official Helm chart for the Console operator and CRs, StreamsHub could:

- **Lower the barrier to adoption** for teams already using Helm-based workflows and environments
- **Enable fine-tuned configuration** with value overrides, environment-specific configuration, and lifecycle management via Helm
- **Align with ecosystem standards**, making StreamsHub easier to discover and integrate
- **Unlock the possibility for a future StreamsHub Helm chart** — once the Console operator has its own chart, it becomes easier to compose a top-level `streamshub` chart 
that depends on Console, Strimzi, Kafka, Prometheus, and other components, enabling full-stack deployment through a single `helm install` command

## Proposal

The proposal is to create and maintain an official Helm chart repo for:

1. **The StreamsHub Console Operator** — managing its deployment, RBAC, service accounts, and associated configuration
2. **The Console Custom Resource (CR)** — exposing the Console instance configuration as Helm values, allowing users to customise the Console without editing raw YAML manifests

### Scope of initial chart(s)

The initial implementation should be deliberately scoped and simple. It should cover:

- Operator's `Deployment`, `ServiceAccount`, `ClusterRole`, and `ClusterRoleBinding`
- CRD installation (either bundled or as a dependency)
- A Console CR that can be optionally deployed alongside the operator by passing a Helm value (e.g. console.instance=true), allowing users who prefer to manage the CR separately to do so

- The chart should **not** attempt to manage external dependencies such as Strimzi, Kafka, or Prometheus in this initial phase. 
Those components have already well-maintained charts and could be addressed in a follow-up effort.

### Possible Future: StreamsHub Umbrella Chart

Once official charts exist for the Console operator and operands, it becomes possible to introduce a `streamshub` 
umbrella Helm chart that composes the full stack. This chart would declare Helm dependencies on:

- `streamshub-console` (this proposal)
- `strimzi-kafka-operator` (official Strimzi chart)
- `kafka` / `kraft` via Strimzi CRs
- `prometheus` or equivalent monitoring
- `apicurio` or equivalent schema registries
- Any other components managed by StreamsHub

This umbrella chart would give users an easy way to deploy the entire stack, significantly improving the onboarding experience and enabling catalogue listings on platforms like Artifact Hub.

### Delivery

- Charts to be hosted within the StreamsHub GitHub organisation, either in a dedicated `helm-charts` repository under a `${PROJECT_NAME}-charts/` directory
- Charts published to a Helm repository (like GitHub Pages or `oci` variants like Quay / DockerHub) + Artifact Hub following each release
- Chart versioning to follow the operator release cycle, with independent patch versioning where chart-only fixes are needed
- A GitHub Actions workflow can be introduced to lint chart using helm lint on every pull request/release, catching templating errors and schema violations early
- A minimal deployment test will run on each push, spinning up a light k8s cluster (kind or minikube might be the best options) and verifying that the operator and Console CR deploy and reach a healthy state
- Chart releases will be automated using github action workflows, allowing the Console operator chart to be tested and published to the StreamsHub Helm repository

## Affected/not affected projects

**Affected:**
- `streamshub/console` — primary subject of this proposal - the operator and Console CR chart will live in or alongside this repository
- StreamsHub documentation — installation guides will need updating to include Helm-based instructions

**Not affected (at this stage):**
- Strimzi, Apicurio, Prometheus, and other stack components — remain independently managed and they will only be referenced if the future umbrella chart is taken forward

## Compatibility

- CRD management will follow Helm community best practices (CRDs in `crds/` directory) to avoid conflicts with existing CRD installations
- The chart will declare a minimum supported k8s version consistent with the operator's existing requirements
- Future versions of the chart will follow semantic versioning; breaking value changes will result in a major version bump and will be documented in a compatibility/migration guide

## Rejected alternatives

**Single monolithic StreamsHub chart (immediate)**
Jumping directly to a full StreamsHub umbrella chart that includes Strimzi, Kafka, and Prometheus was considered but rejected for the initial phase. 
Managing other chart dependencies increases complexity and introduces coupling to third-party release cycles. 
Starting with a focused Console operator chart allows us to establish chart quality and release processes before taking on a broader scope.