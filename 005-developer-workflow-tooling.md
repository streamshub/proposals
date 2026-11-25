# StreamsHub Console: Local Developer Workflow Tooling (`devup`)

This proposal introduces a developer-workflow tool, referred to here as `devup`, that gives Console contributors a single, low-config way to run the Console's inner development loop against a real backing stack.
The proposal is scoped to the Console.
The same pattern could later extend to other StreamsHub projects, but that is noted only as a possibility and is not designed here.
A working prototype already exists in [streamshub/console#2972](https://github.com/streamshub/console/pull/2972).

## Current situation

StreamsHub already has tooling for *standing up and trying* the platform.
[Proposal 001](./001-quickstart-kustomize-repo.md) created the [developer-quickstart](https://github.com/streamshub/developer-quickstart) repository: a Kustomize-based, `kubectl`-native way to deploy an opinionated stack (Strimzi, Kafka, Apicurio Registry, the Console) so a developer can go from zero to a running event-streaming environment with a couple of commands.
Its stated scope is the lowest-resistance way to deploy a minimum viable stack for developers to *use and assess*, and it deploys the Console itself in-cluster, reached by port-forwarding over HTTP.

What is not covered anywhere is the *inner development loop*: iterating on a StreamsHub component's own source code, fast, against a live backing stack.
That is a different activity from running the platform, and it has different needs.

Using the Console as the concrete example, developing it means working in one of three modes, each with its own requirements:

- **Frontend** and **backend**: the Console runs on the developer's host via `quarkus:dev` so edits hot-reload in seconds, and it connects to an in-cluster Kafka from outside the cluster.
- **Operator**: the operator runs on the host and reconciles a `Console` custom resource into the cluster.

Each mode needs a backing cluster (Strimzi plus Kafka, and depending on the feature under test, some of Apicurio, Keycloak, Prometheus, Cruise Control, or Kafka Connect), a `console-config.yaml` that matches that cluster, and the correct Maven invocation to launch the right dev loop.
None of this is hard once learned, but it is undocumented for day-to-day work, it drifts as components change, and every contributor maintains their own version of it.

Other StreamsHub components may well face a similar inner-loop setup cost, but this proposal does not attempt to speak for them and focuses on the Console.

## Motivation

The friction compounds along two axes.
Which component you work on changes the runtime setup and the launch command.
Which feature you work on changes the backing infrastructure and the component configuration: auth needs Keycloak and OIDC, rebalancing needs multiple brokers and Cruise Control, metrics needs Prometheus.

In practice a contributor ends up keeping several cluster setups and several config files, and has to leave the project directory to bring infrastructure up before starting.
That effort is real, it needs maintenance as upstream components move, and the cost is multiplied across every Console contributor.
When a setup breaks, everyone fixes their own copy.

The value of the tool is a consistent, low-config inner-loop workflow for the Console, a lower barrier for new contributors, and tooling that one person can fix for everyone rather than each developer maintaining a private setup.
It complements developer-quickstart rather than competing with it: the quickstart remains the way to deploy and try the stack, and the workflow tool builds the inner-loop experience on top of it.

A good inner-loop experience, stated plainly, looks like this:

- As close to zero config as possible for the common case.
- No need to leave the project directory to start or stop the environment.
- No memorising build or launch commands for different components.
- Extra features are a flag, not a research task.
- Help, URLs, and demo credentials are easy to find.

## Proposal

### Introduction

We propose a developer-workflow tool for the Console, invoked as `./dev` from the Console repository, that does three things:

1. Provisions the backing stack by consuming developer-quickstart.
2. Generates the Console's development configuration from the live cluster.
3. Launches the Console's inner development loop in the chosen mode.

The important scope boundary is that this tool is not an installer.
Installing the stack is developer-quickstart's job, and it does it well with a `kubectl`-native primitive.
This tool is an inner-loop orchestrator that sits above the quickstart and adds the things a Kustomize install cannot do by design: launching host-side processes, generating configuration from a running cluster, and switching between development modes.

### Developer experience

For the Console, basic use is three commands:

```bash
./dev up                               # provision the backing stack locally
./dev frontend | backend | operator    # launch the chosen dev loop
./dev down                             # tear the stack down
```

Optional ecosystem components are selected with profiles, so a cluster only runs what the current work needs:

```bash
./dev up --profile metrics,registry,keycloak,connect
./dev up --full    # heavier topology, e.g. a 3-broker Kafka with Cruise Control for rebalance work
```

This command surface is the part worth reacting to.
The prototype demonstrates the experience; its internals are not fixed and can change based on what the community prefers.

### How it fits together

The tool lives in the Console repository and consumes developer-quickstart for the backing infrastructure.
It is responsible only for the Console's inner loop: selecting the infrastructure to bring up, generating the Console's configuration from the running cluster, and launching the chosen development mode on the host.
Keeping it in the Console repository keeps it next to the code it drives and avoids committing to a shared cross-project structure before the experience has proven itself.

### Development modes and runtime

The Console's development modes map to the runtime differently, by design.

- **Frontend and backend** run the Console on the host via `quarkus:dev`.
  Quinoa serves the React app with hot-module reload, Java live-reloads on the next request, and a debugger can attach.
  The host-run Console reaches the in-cluster Kafka over a TLS and SCRAM listener exposed on ingress.
  This is the same external-listener pattern the Console already uses against remote clusters, reproduced locally, so the generated configuration and the Console's own code path are unchanged.
  This mode is what makes iteration fast, and it is why the Console is not deployed into the cluster here.
- **Operator** runs the operator on the host via `quarkus:dev` and reconciles a `Console` custom resource into the cluster, so the Console runs in-cluster using Kafka's internal listener.

### Configuration generation

The part that removes most of the manual effort is configuration generation.
The tool reads the live cluster for the values the host-run Console needs, such as credentials and the cluster CA, and writes a ready-to-use configuration file.
It regenerates on every run, so the configuration never goes stale against the cluster it targets.

### Relationship to developer-quickstart

developer-quickstart and this tool do different jobs.
The quickstart stands up the whole stack, Console included, in-cluster, to run and try it.
This tool develops the Console itself, which is why it deliberately does not run the Console in-cluster for host-mode work.

The part that overlaps is the backing infrastructure.
We recommend converging on it: `devup` should consume developer-quickstart as its infrastructure base rather than ship a parallel set of manifests.
This builds the infrastructure once and keeps a single opinionated definition of the stack.

Convergence is not free, and this proposal does not claim the feasibility work is complete.
The prerequisites as we see them today, several of which align with developer-quickstart's own future-extensibility plans:

1. **A host-reachable Kafka listener.**
   The quickstart's Kafka is consumed in-cluster and today exposes only internal listeners with no SCRAM authentication.
   Host-mode development needs a TLS and SCRAM listener exposed on ingress so the host-run Console can connect.
   This would be a variant of the quickstart's Kafka configuration, and it would also let anyone point their own Kafka client at a quickstart cluster from outside.
2. **A Kafka Connect overlay.**
   The quickstart has overlays for metrics and others, but not Connect.
   This fits the quickstart's existing overlay model and would be useful there in its own right.
3. **Consuming the stack without the in-cluster Console.**
   Host-mode development needs the backing infrastructure but not the quickstart's in-cluster Console deployment, so there needs to be a way to consume the overlays while omitting that component.
4. **Auth (Keycloak/OIDC) placement.**
   Development of the Console's authentication needs a Keycloak instance and realm.
   Proposal 001 deliberately deferred OAuth/OIDC from the quickstart as too complex and production-sensitive for a simple quick-start, so this needs care.
   Two options:
   - Contribute a clearly dev-only, non-production OIDC overlay to developer-quickstart.
   - Keep auth in the `devup` layer, leaving the quickstart auth-free.

   We recommend keeping auth in the `devup` layer initially.
   It respects the decision in proposal 001, keeps the changes asked of the shared base smaller, and fits the fact that auth wiring is tightly coupled to the Console's generated configuration.
   A dev-only quickstart overlay remains a reasonable future option if there is appetite for it.

### Possible extension to other projects

The inner-loop problem this solves is not unique to the Console, and the same approach could be applied to other StreamsHub projects that develop a component against a live backing stack.
That is noted here only as a possibility.
Generalising the tool, and deciding whether shared machinery across repositories is worthwhile, is out of scope for this proposal.
It would be taken up separately if there is interest once the Console experience has settled.

### Open questions

- The precise set of changes developer-quickstart needs to serve as the infrastructure base, and how that work is sequenced against the existing prototype.
- Whether auth belongs in a dev-only quickstart overlay or in `devup`, as discussed above.

## Affected/not affected projects

### Affected

- **StreamsHub Console**: the tool lives in the Console repository.
  A working prototype exists; converging it onto developer-quickstart is follow-up work.
- **developer-quickstart**: to serve as the infrastructure base it would need a host-reachable TLS and SCRAM Kafka listener variant, a Kafka Connect overlay, and a way to consume the stack without the in-cluster Console.

### Not affected

- **Strimzi, Apicurio Registry, Kroxylicious**: referenced through developer-quickstart, not changed.
- **Other StreamsHub projects**: not affected; any later adoption would be proposed separately.

## Compatibility

- This is development-only tooling with no production surface.
  It should carry the same clear development-only framing as developer-quickstart.
- Consuming developer-quickstart couples the tool to specific stack component versions.
  As with the quickstart, a compatibility matrix should be maintained, and the `?ref=` parameter on Kustomize URLs allows pinning to a known-good version.
- Transition: the current prototype ships its own backing-stack manifests.
  Converging onto developer-quickstart replaces them, and during the transition both may exist briefly.

## Rejected alternatives

### Status quo: per-developer ad-hoc setups

Leaving each contributor to maintain their own cluster setup, configuration, and launch commands.
This is the current cost the proposal exists to remove, and it scales badly across contributors.

### Fold the inner loop into developer-quickstart

Building the dev-loop capability directly into developer-quickstart.
This blurs the deliberate scope of proposal 001, which is a `kubectl`-native install primitive, not a workflow tool.
A Kustomize install cannot launch host-side processes, generate configuration from a live cluster, or switch development modes, so the inner-loop concern belongs in a separate layer that consumes the quickstart.

### A polished standalone CLI or binary

Proposal 001 rejected a dedicated CLI as an alternative installation method, on the grounds that it is another method to maintain, takes users away from the Kubernetes primitives, and invites scope creep.
We agree for installation, and this proposal deliberately does not reintroduce that.
The workflow tool does not install the stack; it orchestrates the inner loop on top of the quickstart.
It is kept as a shell-based tool in the Console repository, scoped to the Console's development workflow rather than packaged as a general-purpose, separately-distributed CLI, specifically to avoid the scope creep that concern describes.

### Designing a general cross-project tool now

Defining a shared, cross-project tool with a reusable core and per-project plug-ins up front.
The inner-loop experience has only been proven for the Console, and committing to a generalised structure before it has settled would be premature.
The tool is kept in the Console repository for now, leaving any generalisation to a later, separate proposal if there is interest.

### Run every mode in-cluster

Always deploying the Console into the cluster for development.
This removes the need for host-reachable infrastructure but loses the fast host-side hot-reload loop, which is the main reason the host modes exist.

## References

- [Proposal 001: StreamsHub Quick-Start Kustomize Repository](./001-quickstart-kustomize-repo.md)
- [Console developer-workflow prototype (streamshub/console#2972)](https://github.com/streamshub/console/pull/2972), which includes a full `devtools/README.md` describing prerequisites, per-mode quick starts, and resource guidance.
- [developer-quickstart repository](https://github.com/streamshub/developer-quickstart)
