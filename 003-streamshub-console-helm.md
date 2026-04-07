# Helm Installation Support for StreamsHub Console

This proposal outlines adding official Helm chart support for the StreamsHub Console operator. 
It provides a standardized installation method and establishes CRD lifecycle management patterns.

## Current situation

There is currently no official Helm chart for the StreamsHub Console operator or its associated Custom Resources. 
Among the broader StreamsHub stack, only Strimzi provides an official Helm chart.
Components such as Strimzi, Prometheus, and other dependencies each maintain their own Helm charts through their respective upstream projects.

## Motivation

Helm is widely adopted as the standard packaging mechanism for k8s applications.
A large portion of the k8s ecosystem - including GitOps platforms (e.g. Argo CD) and cloud provider marketplaces - relies on Helm as the primary installation mechanism.

By introducing an official Helm chart for the Console operator, StreamsHub could:

- **Lower the barrier to adoption** for teams already using Helm-based workflows and environments
- **Enable fine-tuned configuration** with value overrides, environment-specific configuration, and lifecycle management via Helm
- **Align with ecosystem standards**, making StreamsHub easier to discover and integrate

## Proposal

The proposal is to create and maintain an official Helm chart for the **StreamsHub Console Operator** - managing its deployment, RBAC, service accounts, and associated configuration.

### Scope of initial chart

The initial implementation should be deliberately scoped and simple. It should cover:

- Operator's `Deployment`, `ServiceAccount`, `ClusterRole`, and `ClusterRoleBinding`
- CRD installation - see the [CRD Management](#crd-management) section below for the chosen approach

The chart will **not** attempt to manage external dependencies such as Strimzi, Kafka, Prometheus, or other stack components in this initial phase.
Those components have well-maintained charts and can be addressed in a separate proposal.
 
> Note on Prometheus: although Prometheus is a significant dependency for the Console, it has a well-maintained upstream chart and introduces operator-level complexity (CRDs, RBAC) that is out of scope here. Including it is deferred to a possible future proposal.
 
### Chart hosting
 
The chart will live **within the `console` repository**, consistent with how Strimzi manages its Helm chart.
This keeps chart changes co-located with operator changes, making it straightforward to keep the chart in sync with new operator releases and to run system tests that validate the chart against each new version.
 
Charts will be published to a Helm repository (GitHub Pages or an OCI registry such as Quay or DockerHub) and listed on Artifact Hub following each release.

### Chart versioning
 
Chart versioning will be **independent from the operator version**.
 
Chart and operator versions will naturally diverge over time - for example, when a chart only fix is needed without an operator release.
Starting from an independent versioning scheme avoids confusion from the outset.
A clear mapping between chart versions and compatible operator versions will be maintained on the release page and in the documentation.

## CRD management

CRD lifecycle management in Helm has known limitations that need to be addressed explicitly.
Understanding these is important both for the standalone chart and for potential future use as a subchart.

### The core CRDs problem

Helm's built-in CRD support - placing CRDs in the `crds/` directory - has two significant limitations:

1. **CRDs in `crds/` are not upgraded during `helm upgrade`**. This creates version drift risk: a new operator release may depend on an updated CRD schema or a new stored version that is not present in the cluster after a routine upgrade.
2. **CRDs in subcharts are silently skipped**. If this chart is later used as a subchart in an umbrella chart, Helm only processes CRDs from the top-level chart's `crds/` directory. Any CRDs in subchart `crds/` directories are silently ignored on install, meaning the Console CRDs would not be present when the operator tries to reconcile.

### Recommended approach

The recommended approach is the **`crds/` directory pattern**, consistent with how Strimzi manages its CRDs.

CRDs are installed automatically on `helm install`. The CRD upgrade limitation must be explicitly documented: users must manually apply updated CRDs before upgrading the operator chart (e.g. `kubectl apply -f crds/`).
This is the exactly same pattern Strimzi uses and is an understood trade-off in the Helm ecosystem.

## Delivery

- Chart to be hosted within the `console` repository, under a `helm/` or `charts/` directory
- Chart published to a Helm repository (GitHub Pages or OCI variants like Quay / DockerHub) + Artifact Hub following each release
- **Chart versioning to be independent from the operator version**, with a clear version compatibility mapping maintained in documentation and release notes
- A GitHub Actions workflow will lint the chart using `helm lint` on every pull request and release, catching templating errors and schema violations early
- A minimal deployment test will run on each push, spinning up a lightweight k8s cluster (kind or minikube) and verifying that the operator deploys and reaches a healthy state
- Chart releases will be automated using GitHub Actions, allowing the chart to be tested and published to the StreamsHub Helm repository following each release

# Affected/not affected projects

**Affected:**
- `console` - primary subject of this proposal; the operator chart will live within this repository
- `documentation` - installation guides will need updating to include Helm-based instructions, including the CRD manual upgrade procedure

**Not affected:**
- Strimzi, Apicurio, Prometheus, and other stack components - remain independently managed

## Compatibility

- CRD management will follow Helm community best practices (CRDs in `crds/` directory) to avoid conflicts with existing CRD installations
- The CRD upgrade limitation is a known trade-off and will be clearly documented; users must manually apply updated CRDs before running `helm upgrade`
- The chart will declare a minimum supported k8s version consistent with the operator's existing requirements
- Chart versioning follows semantic versioning independently from the operator; breaking value changes will result in a major version bump and will be documented in a compatibility/migration guide

## Rejected alternatives
 
**CR instance management via Helm (deferred)**
 
An earlier version of this proposal included managing the Console Custom Resource (CR) instance as part of the Helm chart, exposing its configuration as Helm values.
This was removed from scope. Managing operator CRs via Helm leads to an unbounded configuration surface - every field of the CR becomes a potential Helm value, requiring huge ongoing maintenance and branching logic.
Strimzi does not manage its CRs via Helm for the same reasons.
Teams that want to manage their Console CR via GitOps workflows can do so directly against the CRD using standard tooling.

**StreamsHub umbrella chart (deferred to a separate proposal)**

Including an umbrella chart that composes Strimzi, Kafka, Prometheus, and other dependencies was considered but removed from this proposal.
The CRD lifecycle limitations - upgrade skipping, silent subchart skipping, and the complexity of keeping a multi-operator stack in sync are not trivial and deserve a dedicated proposal and design discussion.
Any future umbrella chart proposal should address the subchart CRD visibility problem directly; the dedicated `streamshub-crds` chart pattern is a possible future candidate solution, but it has too much overhead for this proposal.

**CRD hook Job approach**

A hook Job for CRD management was considered but deferred in favour of the simpler `crds/` directory pattern for the initial release.
The hook approach adds operational complexity and RBAC requirements that are not justified at this stage.

**Dedicated `helm-charts` repository**
 
Hosting the chart in a standalone `helm-charts` repository was considered but rejected in favour of keeping it within the `console` repository.
This location makes it easier to keep the chart in sync with operator releases and to run tests that validate the chart against each new version.