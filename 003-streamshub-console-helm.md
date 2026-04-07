# Helm Installation Support for StreamsHub Console

This proposal outlines adding official Helm chart support for the StreamsHub Console operator and its Custom Resources (CRs). 
It provides a standardized installation method and establishes CRD lifecycle management patterns. 

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

## Proposal

The proposal is to create and maintain an official Helm chart repo for:

1. **The StreamsHub Console Operator** — managing its deployment, RBAC, service accounts, and associated configuration
2. **The Console Custom Resource (CR)** — exposing the Console instance configuration as Helm values, allowing users to customise the Console without editing raw YAML manifests

### Scope of initial chart(s)

The initial implementation should be deliberately scoped and simple. It should cover:

- Operator's `Deployment`, `ServiceAccount`, `ClusterRole`, and `ClusterRoleBinding`
- CRD installation — see the [CRD Management](#crd-management) section below for the chosen approach

The chart will **not** attempt to manage external dependencies such as Strimzi, Kafka, or Prometheus in this initial phase. 
Those components have well-maintained charts and can be addressed in a separate proposal.

## CRD Management

CRD lifecycle management in Helm has known limitations that need to be addressed explicitly. 
Understanding these is important both for the standalone chart and for potential future use as a subchart.

### The Core Problem

Helm's built-in CRD support — placing CRDs in the `crds/` directory — has two significant limitations:

1. **CRDs in `crds/` are not upgraded during `helm upgrade`**. This creates version drift risk: a new operator release may depend on an updated CRD schema or a new stored version that is not present in the cluster after a routine upgrade.
2. **CRDs in subcharts are silently skipped**. If this chart is later used as a subchart in an umbrella chart, Helm only processes CRDs from the top-level chart's `crds/` directory. Any CRDs in subchart `crds/` directories are silently ignored on install, meaning the Console CRDs would not be present when the operator tries to reconcile.

### Options Considered

**Option 1 — `crds/` directory (Strimzi pattern)**

Place CRDs in the `crds/` directory, consistent with Strimzi's approach. CRDs are installed on `helm install` but require explicit user actions (e.g. `kubectl apply`) for upgrades. 
This is an established and well-understood pattern, but it does require users to be aware of the manual upgrade step.

**Option 2 — Dedicated `streamshub-crds` chart (cert-manager pattern)**

Package CRDs as a separate `streamshub-crds` Helm chart, similar to the approach used by cert-manager. 
This chart can be installed and upgraded independently from the operator chart, giving full control over CRD lifecycle. 
It also cleanly solves the future subchart problem: an umbrella chart could declare a dependency on `streamshub-crds` directly, ensuring CRDs are always installed at the top level regardless of how the operator chart is composed.

**Option 3 — Pre-install hook Job**

A `pre-install` / `pre-upgrade` hook Job that applies CRDs via `kubectl apply`. 
This gives the most control over upgrade ordering but adds complexity and requires appropriate RBAC for the hook Job to manage resources.

### Recommended Approach

The recommended initial approach is **Option 1** — the `crds/` directory pattern, consistent with how Strimzi manages its CRDs. 

The CRD upgrade limitation must be explicitly documented: users must manually apply updated CRDs before upgrading the operator chart (e.g. `kubectl apply -f crds/`). 
Again, this is the same pattern Strimzi uses and is a known trade off in the Helm ecosystem.

> Note: Option 2 (a dedicated `streamshub-crds` chart) should be revisited if and only when an umbrella chart is proposed, as it provides the cleanest solution to the subchart CRD visibility problem and avoids duplicating CRD definitions across charts.

## Delivery

- Charts to be hosted within the StreamsHub GitHub organisation, either in a dedicated `helm-charts` repository under a `${PROJECT_NAME}-charts/` directory
- Charts published to a Helm repository (like GitHub Pages or `oci` variants like Quay / DockerHub) + Artifact Hub following each release
- **Chart versioning to be independent from the operator version**. Chart and operator versions will naturally diverge over time (e.g. chart fixes). Independent versioning avoids confusion from the start. A clear mapping between chart versions and compatible operator versions will be maintained on the release page and in the documentation.
- A GitHub Actions workflow can be introduced to lint chart using helm lint on every pull request/release, catching templating errors and schema violations early
- A minimal deployment test will run on each push, spinning up a light k8s cluster (kind or minikube might be the best options) and verifying that the operator and Console CR deploy and reach a healthy state
- Chart releases will be automated using github action workflows, allowing the Console operator chart to be tested and published to the StreamsHub Helm repository

## Affected/not affected projects

**Affected:**
- `console` — primary subject of this proposal; the operator and Console CR chart will live in this repository
- `documentation` — installation guides will need updating to include Helm-based instructions, including the CRD upgrade procedure

**Not affected:**
- Strimzi, Apicurio, Prometheus, and other stack components — remain independently managed

## Compatibility

- CRD management will follow Helm community best practices (CRDs in `crds/` directory) to avoid conflicts with existing CRD installations
- The chart will declare a minimum supported k8s version consistent with the operator's existing requirements
- Chart versioning follows semantic versioning independently from the operator; breaking value changes will result in a major version bump and will be documented in a compatibility/migration guide

## Rejected alternatives

**StreamsHub umbrella chart (deferred to a separate proposal)**

Including an umbrella chart that composes Strimzi, Kafka, Prometheus, and other dependencies was considered but removed from this proposal. 
The CRD lifecycle limitations like upgrade skipping, silent subchart skipping, and the complexity of keeping a multi-operator stack in sync. 
These are not trivial and deserve a dedicated proposal and design discussion. 
Any future umbrella chart proposal should address the CRD subchart problem directly. The dedicated `streamshub-crds` chart pattern described above is a candidate solution.


**CRD hook Job approach**

A hook Job for CRD management was considered but deferred in favour of the simpler `crds/` directory pattern for the initial release. 
The hook approach adds operational complexity and RBAC requirements that are not justified at this stage.