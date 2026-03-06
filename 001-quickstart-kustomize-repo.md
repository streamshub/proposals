# StreamsHub Quick-Start Kustomize Repository

The aim of the StreamsHub organization is to support developers in creating event driven architectures. 
There a several complimentary open-source technologies that are often used together to provide the infrastructure for these architectures.
This "event stack" consists of:

- Strimzi Kafka Operator and related components (Kafka Bridge, Kafka OAuth, etc.)
- Apicurio Schema Registry
- StreamsHub Web Console
- Kroxylicious Kafka Proxy

This proposal recommends creating a centralized [Kustomize](https://kustomize.io/)-based repository within the StreamsHub GitHub organization that provides a unified quick-start experience for deploying the event-streaming stack on a local Kubernetes cluster. 
The repository would allow developers to install the full stack; including Strimzi, Kafka, Apicurio Registry and StreamsHub Console, using only `kubectl`, with no additional tooling required.
Installation follows a two-phase approach: first deploying operators and CRDs, then deploying operands, each with a single `kubectl apply -k` command pointing at the GitHub-hosted Kustomize configurations.

## Current situation

Currently, each component of the event stack has its own installation approach, documentation location and set of manual steps. 
There is no unified quick-start that brings them together.

### Strimzi

Strimzi provides [quick-start instructions](https://strimzi.io/quickstarts/) for Minikube, KIND and Docker Desktop. 
Installation uses a vanity URL, backed by a Netlify Function, that patches the namespace into the relevant resources:

```shell
kubectl create namespace kafka && kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
```

This installs the Strimzi Operator deployment and the CRDs needed for its operands (Kafka, KafkaConnect, etc.).

### Kafka

A single-node Kafka cluster for development can be deployed using the example files from the Strimzi GitHub repository:

```shell
kubectl create -f https://raw.githubusercontent.com/strimzi/strimzi-kafka-operator/refs/heads/main/examples/kafka/kafka-single-node.yaml -n kafka
```

This requires the Strimzi Operator to already be installed and the CRDs to be registered.

### Strimzi Kafka Bridge

The Kafka Bridge can be installed from the Strimzi Operator repository's examples folder. 
The bootstrap server address must match the deployed Kafka cluster name, if you have used one of the example kafka cluster deployments from the same repo it _should_ work:

```shell
kubectl create -f https://github.com/strimzi/strimzi-kafka-operator/raw/refs/heads/main/examples/bridge/kafka-bridge.yaml -n kafka
```

### Strimzi Kafka OAuth

Strimzi Kafka OAuth has many possible configurations and its own extensive example folders covering [container](https://github.com/strimzi/strimzi-kafka-oauth/tree/main/examples/docker) and [Kubernetes-based](https://github.com/strimzi/strimzi-kafka-oauth/tree/main/examples/kubernetes) deployments. 
Even the example deployment using Keycloak as the OAuth provider requires several manual steps to configure realms and other settings. 
A simple quick-start for this component is not straightforward due to the security-critical nature of the configurations involved.

### StreamsHub Console

The StreamsHub Console does not have a clear quick-start section on the website or repository README. 
Installation involves deploying the operator via [OLM](https://olm.operatorframework.io/) or directly:

```shell
export NAMESPACE=console && export VERSION=0.11.0 && kubectl create namespace $NAMESPACE && curl -sL https://github.com/streamshub/console/releases/download/${VERSION}/streamshub-console-operator.yaml | envsubst | kubectl apply -n ${NAMESPACE} -f -
```

The Console CR requires a Kafka cluster deployed with a user configured for console access. 
The default Strimzi Kafka cluster deployment (from the Strimzi examples directory) does not have this, so manual steps are required. 
Alternatively, unsecured access can be used, but there are no existing examples for this configuration. 
Accessing the console also requires port-forwarding, which varies depending on the local Kubernetes setup.

### Kroxylicious

The Kroxylicious Kafka Proxy has [quick-start documentation](https://kroxylicious.io/documentation/0.18.0/html/kroxylicious-operator/#assembly-operator-install-operator) similar in style to Strimzi's. 
However, users need to download installation artifacts and copy or clone examples from the repository to install the operator and proxy instance.

### Apicurio Registry

The Apicurio Schema Registry has standalone, [Docker-based quick starts](https://www.apicur.io/registry/getting-started/). 
The main documentation also describes an [operator-based deployment option](https://www.apicur.io/registry/docs/apicurio-registry/3.1.x/getting-started/assembly-deploying-registry-operator.html) for Kubernetes. 
There is no simple vanity URL install like Strimzi's. 
A single install file can be pulled from the GitHub repository, but it requires patching namespaces and other configurations.

### Summary

In short, a developer wanting to try the full event stack must visit several different websites, use different installation methods (vanity URLs, raw GitHub files, OLM, manual downloads) and write custom scripting or templating to get everything installed and working together. 
There is no single entry point for the stack as a whole.

## Motivation

- Fragmented experience: Developers must currently visit multiple websites and use different installation methods to deploy each component of the stack. This creates unnecessary friction for anyone wanting to evaluate or develop against our full event-streaming platform.
- Custom integration work: Getting the components to work together (e.g. configuring the Console to connect to a Kafka cluster) requires manual steps and custom scripting that is not documented in any single location.
- High barrier to entry: The combination of scattered documentation, different tooling requirements and manual configuration steps means the barrier to trying the full stack is much higher than it needs to be.
- Unified entry point: A single, unified quick-start that deploys an opinionated development stack would make the platform significantly more accessible. Developers could go from zero to a working event-streaming environment with just a couple of commands.

## Proposal

### Introduction

[Kustomize](https://kustomize.io/) is the configuration management system built into the `kubectl` client (since v1.14). 
It allows you to define base configurations and layer overlay patches on top to customize deployments for different environments. 
Kustomize is commonly used in GitOps workflows, where platform teams define blessed base configurations and users customize only the sections they need.

Critically, `kubectl` can install Kustomize layers hosted remotely on GitHub, GitLab or Bitbucket, provided the Kustomize configuration files are in a repository on one of those domains. 
Since all StreamsHub components are hosted on GitHub, we can provide Kustomize-based development configurations that are installable directly via `kubectl` with no additional tooling.

This proposal recommends creating a centralized Kustomize-based repository within the StreamsHub GitHub organization that provides a unified quick-start experience for deploying the event-streaming stack on a local Kubernetes cluster. 

### Prior art

To explore what a Kustomize-based quick-start could look like, a proof-of-concept demo repository was created: [https://github.com/tomncooper/strimzi-kustomize](https://github.com/tomncooper/strimzi-kustomize). 
This repository provides base configurations for the main components and pulls them together into a combined development stack. 
The approach and structure described in this proposal is informed by the experience of building and testing that demo.

### Repository structure

The quick-start repository should be organized into two main layers:

- Base configs (`base/`): Contains the operator deployments and CRD definitions for each component. These are the resources that must be installed first.
- Stack configs (`stack/`): Contains the operand custom resources (Kafka cluster, Registry instance, Console CR, etc.) that depend on the operators being ready.
- Overlays (`overlays/`): Optional variant configurations (e.g. OAuth-enabled Kafka, multi-node clusters) that patch the base or stack layers for different scenarios.

Each layer has its own `kustomization.yaml` that references the component resources it includes. 
The stack layer uses remote references to pull in base configurations from the same repository.

### Two-phase installation

Kubernetes operators must be installed and their CRDs registered before any custom resources (operands) can be created. 
The quick-start handles this with a two-phase install:

Phase 1 — Operators and CRDs:

```shell
kubectl apply -k 'https://github.com/streamshub/quickstart-kustomize//dev/base?ref=main'
```

Phase 2 — Operands:

```shell
kubectl apply -k 'https://github.com/streamshub/quickstart-kustomize//dev/stack?ref=main'
```

Users need to wait for the operators in Phase 1 to become ready before running Phase 2. 
The `?ref=` parameter pins the installation to a specific branch or tag.

### Install script

To simplify the two-phase process, an optional convenience script can handle the sequencing and readiness checks automatically, as well as give richer progress information:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/quickstart-kustomize/main/dev/install.sh | bash
```

This script would:

1. Apply the base layer (operators and CRDs)
2. Wait for operator deployments to become ready
3. Apply the stack layer (operands)
4. Report the status of the deployed components

The script is optional, users who cannot/will not pipe directly to bash for security reasons or who prefer to understand and control each step, can use the two `kubectl apply -k` commands directly.

### Teardown and cleanup

#### Teardown risks

Tearing down a Kustomize-based stack requires more care than installing one. 
Three risks in particular must be addressed:

- CRD cascade deletion: Deleting a CRD removes _all_ custom resources of that type cluster-wide, not just those created by the quick-start. On a shared cluster this can destroy resources belonging to other users or namespaces.
- Deletion ordering: Operands (custom resources) must be deleted before the operators and CRDs that manage them. However, `kubectl delete -k` does not enforce deletion ordering or wait for resources to be fully removed before proceeding.
- Finalizer stalling: Operators typically add finalizers to the custom resources they manage, so that cleanup logic (e.g. deleting StatefulSets, PVCs, secrets) runs before the CR is removed. If an operator is deleted before its CRs, the finalizer controller is gone and the CRs can become stuck in a `Terminating` state indefinitely.

#### Resource labeling

All resources deployed by the quick-start should carry a common label via Kustomize `commonLabels`, for example:

```yaml
commonLabels:
  app.kubernetes.io/part-of: streamshub-quickstart
```

Note that `kubectl delete -k` already targets resources precisely, as it renders the kustomization's manifests and deletes by group/version/kind, namespace and name. 
Therefore, it will only remove the specific resources defined in the overlay, not all custom resources of a given type. 
Labels are not needed for basic delete via kustomization to work correctly.

However, labels help with several key issues: 

- Shared-cluster detection: They allow us to query for CRs of the managed CRD types that _do not_ carry the quick-start label. If any unlabeled CRs exist for a given operator's CRD types, then we know that operator's CRDs are shared and should be retained. The uninstall script removes only that operator's Deployments, ServiceAccounts, and other non-CRD resources, while operators whose CRDs have no unlabeled CRs are fully removed including their CRDs.
- Orphan discovery: After a failed or partial teardown, labels make it straightforward to find leftover quick-start resources across all namespaces:
```shell
kubectl get all -A -l app.kubernetes.io/part-of=streamshub-quickstart
```
Note that `kubectl get all` does not cover every resource type (CRDs, ClusterRoles, ClusterRoleBindings, etc.). To find labeled cluster-scoped resources, also run:
```shell
kubectl get crds,clusterroles,clusterrolebindings -l app.kubernetes.io/part-of=streamshub-quickstart
```
- Auditing: Labels distinguish quick-start-owned resources from independently deployed ones, which is useful for troubleshooting and capacity planning on shared clusters.

#### Two-phase teardown

Before tearing down, users on non-ephemeral (minkube, KIND, etc) or shared clusters should check whether non-quick-start custom resources exist on the cluster for each operator's CR types.
For example, to check for Strimzi-managed Kafka resources not owned by the quick-start:

```shell
kubectl get kafkas -A --selector='!app.kubernetes.io/part-of=streamshub-quickstart'
```

Users should repeat this check for each operator group's CR types (e.g. Apicurio Registry CRs, StreamsHub Console CRs) before proceeding with Phase 2.
If any CRs are returned for an operator group, all CRDs for that operator should be retained. 
Users should not proceed with `kubectl delete -k` for the base layer for that operator's resources and should instead manually delete only the non-CRD resources (Deployments, ServiceAccounts, ClusterRoles, etc.) for that operator.

If no unlabeled CRs are found for any operator group, it is safe to proceed with full teardown including CRD deletion.
The [uninstall script](#uninstall-script) automates these per-operator-group checks and is recommended for safe teardown.

For manual teardown, the process has two phases. 
Under most scenarios it should be safe to remove the operands (`dev/stack`) overlay:

Phase 1 — Delete operands:

```shell
kubectl delete -k 'https://github.com/streamshub/quickstart-kustomize//dev/stack?ref=main'
```

If the shared-resource checks above reveal no issues, users can proceed to remove the operator (`dev/base`) overlay:

Phase 2 — Delete operators and CRDs:

Wait for all custom resources to be fully removed before proceeding. 
This allows the operators to process finalizers and complete their cleanup logic.

```shell
kubectl delete -k 'https://github.com/streamshub/quickstart-kustomize//dev/base?ref=main'
```

Running Phase 2 before Phase 1 completes risks finalizer stalling and, on shared clusters, cascade-deleting CRs that belong to other deployments.

#### Uninstall script

To complement the `install.sh` convenience script, an `uninstall.sh` script should be provided that automates the sequencing and safety checks:

```shell
curl -sL https://raw.githubusercontent.com/streamshub/quickstart-kustomize/main/dev/uninstall.sh | bash
```

This script would:

1. Delete operand custom resources (stack layer)
2. Poll until all CRs are fully removed (finalizers cleared, resources gone from the API server)
3. Delete operators and CRDs (base layer)
4. Verify clean removal and report any resources that remain

The script should implement shared-cluster safety checks using per-operator-group CRD deletion:

- CRDs are grouped by their parent operator (e.g. all Strimzi CRDs form one group, likewise Apicurio Registry and StreamsHub Console CRDs)
- Before deleting CRDs in a group, the script queries for CRs of those types that **do not** carry the quick-start label (see [Resource labeling](#resource-labeling) above)
- If any CRD in the group has unlabeled CRs, the entire group is retained. All CRDs for that operator are kept and only the operator's Deployments, ServiceAccounts, ClusterRoles, and other non-CRD resources are removed
- Groups with no unlabeled CRs have their CRDs deleted normally as part of the base layer teardown
- Report which operator groups were retained and why, so the user can decide on manual cleanup if desired

### Initial stack components

The initial quick-start should deploy the following components:

- Strimzi Kafka Operator — The operator and its CRDs
- Single-node Kafka cluster — A minimal Kafka deployment suitable for development
- Apicurio Registry Operator — The operator for managing registry instances
- In-memory Apicurio Registry instance — A lightweight registry using in-memory storage (appropriate for development, not production)
- StreamsHub Console Operator — The operator for managing console instances
- StreamsHub Console instance — Configured without authenticated listeners or Prometheus integration initially, to keep the quick-start simple

### Future extensibility

Kustomize overlays make it straightforward to add new configurations without modifying the base stack:

- OAuth overlay: An overlay that patches the Kafka cluster to enable OAuth authentication, potentially using the Keycloak operator for automated realm configuration
- Kroxylicious overlay: An overlay that adds the Kroxylicious Kafka Proxy to the stack
- Namespace customization: Overlays or patches that allow deploying into a user-specified namespace
- Kafka Bridge overlay: An overlay that adds the Strimzi Kafka Bridge for HTTP-based Kafka access
- Prometheus overlay: An overlay that deploys Prometheus and configures metrics collection from the stack components, enabling the StreamsHub Console's metrics dashboards

A _vanity URL_ (e.g. `streamshub.io/install/dev`) could be a future enhancement layered on top of the Kustomize repository. The vanity URL backend could use Kustomize internally to generate the appropriate YAML, accepting parameters for namespace customization and component selection. This would complement rather than replace the direct GitHub-based Kustomize approach.

### Considerations

- De-facto platform: By creating a combined dev-stack installation, we are creating a de-facto "platform" that will need to be tested for compatibility across component releases. Changes to any upstream component could break the stack, so integration testing will be needed.
- Dev-to-production risk: An opinionated dev-stack install runs the risk of being adopted for production use. The quick-start should include clear documentation that this is a development-only configuration. Resource limits, security configurations and storage settings are not suitable for production.
- OAuth complexity: Strimzi Kafka OAuth has many possible configurations and security-critical settings. A simple quick-start for OAuth-enabled setups may not be advisable as an initial target, but could be explored as an overlay once the base stack is stable.
- CRD deletion on shared clusters: CRD deletion is a cluster-scoped operation that cascade-deletes all custom resources of that type across every namespace. The uninstall script will group CRDs by their parent operator and check each group independently for non-quick-start custom resources. If any CRD in an operator's group has unlabeled CRs, all CRDs for that operator are retained and only non-CRD resources are removed. Operator groups with no shared CRD usage will be fully cleaned up including CRD deletion.

## Affected/not affected projects

### Affected

- quickstart-kustomize (new repository): A new repository to be created in the StreamsHub GitHub organization containing all Kustomize configurations, the install script and documentation.
- StreamsHub Console: The Console operator and CR configurations will be referenced in the quick-start. A Console CR configuration that works without authenticated Kafka listeners or Prometheus will need to be defined.

### Not affected

- Strimzi: No changes to the upstream Strimzi project are required. The quick-start will reference existing Strimzi installation resources.
- Apicurio Registry: No changes to the upstream Apicurio Registry project are required. The quick-start will use existing operator installation resources.
- Kroxylicious: No changes to the upstream Kroxylicious project are required. Kroxylicious integration is a future overlay target.

## Compatibility

- Additive overlays: Kustomize overlays allow new components and configurations to be added without breaking existing installations. Users of the base stack are not affected by the addition of new overlays.
- Version pinning: The `?ref=` parameter in `kubectl apply -k` URLs allows users to pin to a specific tag or commit. This means users can lock to a known-good version while the repository continues to evolve.
- Component version coupling: The stack will bundle specific versions of each component together. These version combinations will need compatibility testing across releases. A compatibility matrix should be maintained in the repository documentation.
- Minimum kubectl version: Kustomize support in `kubectl` requires version 1.14 or later. This is a very low bar given current Kubernetes version support policies, but should be documented.

## Rejected alternatives

### Helm

[Helm](https://helm.sh/) is the most widely adopted package manager for Kubernetes. 
However, of all the components in our stack, only Strimzi has an official Helm chart. 
We would need to create and maintain charts for Apicurio Registry, StreamsHub Console and Kroxylicious. 
Ideally these charts would be maintained by the upstream projects themselves. 
Additionally, CRD lifecycle is awkward in Helm. 
CRDs in the `crds/` directory are not upgraded on `helm upgrade`, which is a well-known limitation. 
The two-phase deployment problem still requires careful hook orchestration to ensure operators are ready before operands are applied. 
Helm also introduces an additional dependency (`helm` CLI) beyond `kubectl`.

### Vanity URL

A vanity URL (e.g. `streamshub.io/install/dev`) would provide a memorable, user-friendly install command. 
However, it requires backend infrastructure to host and maintain. 
It could be a future enhancement layered on top of the Kustomize repository, where the backend uses Kustomize internally to generate YAML and accepts parameters for customization. 
This is not rejected outright but is not appropriate as the initial approach due to the infrastructure overhead.

### OLM

[Operator Lifecycle Manager](https://olm.operatorframework.io/) is a viable approach for installing operators and we already have OLM packages for the operators in our stack. 
However, OLM is primarily designed for operator lifecycle management, and orchestrating the deployment of operands (the custom resources that operators manage) is more complex within the OLM model. 
OLM could be explored as a complementary approach in the future.

### Ansible

[Ansible](https://www.ansible.com/) handles ordered, multi-step deployments naturally through task sequencing and readiness polling, which makes it well-suited for the two-phase deployment problem. 
It can wrap raw Kubernetes manifests, Helm charts or OLM bundles. 
However, Ansible requires Python and the Ansible package as dependencies, which is a significantly higher barrier to entry than `kubectl` alone. 
Ansible is not a Kubernetes-native tool, adding a conceptual layer that developers unfamiliar with it would need to learn. 
For a quick-start aimed at minimizing friction, the additional dependency is a significant drawback.

### CLI

A dedicated CLI tool (e.g. `streamshub dev install --target=minikube --addon http-bridge`) would provide a polished, customizable installation experience. 
However, it creates another installation method to support and maintain, takes users away from the underlying Kubernetes primitives they need to understand, and carries significant scope-creep risk as feature requests accumulate. 
A CLI could be considered in the future if the quick-start grows in complexity, but is not appropriate as the initial approach.

### Operator of Operators

Building a meta-operator that installs and manages all the other operators and operands from a central set of CRs would provide the most integrated experience. 
However, this is a significantly more complex undertaking than is needed for a developer quick-start. 
The engineering effort and ongoing maintenance burden would far outweigh the benefits for the target use case of getting developers started quickly.
