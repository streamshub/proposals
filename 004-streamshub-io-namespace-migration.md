# StreamsHub Console: Adopt `streamshub.io` as the Canonical Namespace

This proposal applies to the StreamsHub Console project (the `streamshub/console` repository).
It recommends adopting `streamshub.io`, the domain now owned by the StreamsHub organization, as the canonical namespace for the Console project's public identifiers:

1. The Maven `groupId`, changing from `com.github.streamshub` to `io.streamshub`.
2. The Console operator's Kubernetes CRD API group, changing from `console.streamshub.github.com` to `console.streamshub.io`.

Both identifiers today derive from the project's GitHub location (`github.com/streamshub`) rather than from a domain the organization controls.
The two changes are the same underlying decision so they are proposed together.
The `groupId` change is a mechanical, low-risk rename with no external consumers.
The CRD API group change is a public API change affecting existing Console operator installations, so the bulk of this proposal is a migration plan for users who already have the Console CRD deployed.

## Current situation

### Maven `groupId`

The Console repository is a Maven multi-module build whose `groupId` is `com.github.streamshub` across all modules (`root`, `api`, `common`, `coverage`, `operator`, `systemtests` and `ui`).
The Java packages for the first-party source follow the same base package (`com.github.streamshub.console.*` and `com.github.streamshub.systemtests.*`).

Nothing has ever been published under this `groupId`.
There is no `distributionManagement`, no Sonatype/OSSRH configuration, no artifact-signing plugin and no publish step in CI (the release build runs with `-Dmaven.deploy.skip=true`).
In other words, there are no external consumers of these Maven coordinates today.

### Console CRD API group

The Console operator registers its custom resource under the API group `console.streamshub.github.com`, currently at version `v1alpha1`:

```java
@Version("v1alpha1")
@Group("console.streamshub.github.com")
public class Console extends CustomResource<ConsoleSpec, ConsoleStatus> implements Namespaced
```

Users interact with this via manifests such as:

```yaml
apiVersion: console.streamshub.github.com/v1alpha1
kind: Console
metadata:
  name: my-console
```

Unlike the Maven `groupId`, this identifier does have real-world exposure.
The operator has a continuous release history and is distributed through an OLM bundle and catalog (`console-operator-bundle` and `console-operator-catalog`) with an upgrade channel graph chaining releases together.
It is also installable directly from manifests with `kubectl apply`.
We know that there are clusters with `Console` custom resources already deployed under `console.streamshub.github.com/v1alpha1`, some via OLM Subscriptions that may upgrade automatically.

Kubernetes has no in-place mechanism to rename a CRD's API group.
A CRD's identity is `<plural>.<group>`, so changing the group is equivalent to introducing a brand-new resource type.
So this makes the change a genuine public API change requiring a migration plan, rather than just a documentation update.

## Motivation

`streamshub.io` is now owned by the StreamsHub organization.
Both `com.github.streamshub` and `console.streamshub.github.com` were derived from the project's GitHub location, not from a domain the organization actually controls.
Neither is a legitimate reverse-domain identifier for an organization-owned project.

- Maven Central publishing.
  Publishing to Maven Central requires proving ownership of the `groupId` namespace, and that verification is domain-based (a DNS `TXT` record proving control of `streamshub.io`).
  A `com.github.streamshub` namespace would instead be verified against a GitHub account, which is the mechanism intended for personal namespaces rather than organization projects.
  There is an active [upstream request](https://github.com/streamshub/console/issues/2900) to publish the Console operator's CRD Java classes as reusable artifacts so that downstream tooling can depend on the Console POJOs.
  The `groupId` should be settled before that publishing work begins, so that artifacts are never published under a name we intend to abandon.
- Consistency and correctness.
  Aligning both the Maven and Kubernetes identifiers on `streamshub.io` gives the Console project one coherent, verifiable namespace instead of two GitHub-derived approximations.
- Timing.
  This is the cheapest possible moment to make the change.
  Neither identifier has external consumers that a rename cannot reach today, but that window closes as soon as artifacts are published to Maven Central and as more operator releases accumulate.
  Doing both renames together, and now, avoids paying an integration and communication cost twice.

## Proposal

The two changes differ enormously in risk and effort, so they are described separately.

### Part 1: Maven `groupId` (`com.github.streamshub` to `io.streamshub`)

This is a purely mechanical change with no migration story, because there are no published artifacts and no external consumers.

- Update the `<groupId>` (and the corresponding `<parent>` and inter-module `<dependency>` coordinates) in all module POMs.
- Move the Java sources from `com/github/streamshub/...` to `io/streamshub/...`, updating package declarations and imports (an IDE-assisted refactor rather than hand edits).
- Update the hard-coded package-path patterns in the build configuration: the Sonar exclusion paths that embed `com/github/streamshub/...` in the root POM, and the JaCoCo exclude patterns that embed the same paths in the `api` and `coverage` POMs.

This can land in a single release with no user-facing impact.

### Part 2: CRD API group (`console.streamshub.github.com` to `console.streamshub.io`)

Two properties of this specific change make it less onerous than other API migrations:

- The schema is identical.
  Only the group string changes, with no field renames, no annotation-prefix changes and no removed fields.
  A user's migration is therefore a pure `apiVersion` swap.
- The CRD is `v1alpha1`.
  Under Kubernetes API conventions an alpha API carries no long-term compatibility guarantee, which gives the community latitude to choose a pragmatic path rather than a maximally conservative one.

The recommended approach is a phased dual-CRD, dual-controller migration with native deprecation and a time-boxed removal.
It works identically for OLM-managed and plain-manifest (`kubectl apply`) installations.

It should be noted upfront that Kubernetes' built-in version-conversion machinery cannot help here.
Conversion webhooks convert only between versions within a single group, not across groups, and `kube-storage-version-migrator` likewise operates within a single group.
Neither applies to a cross-group rename.

#### Phase 1: Dual Console CRD serving Operator

- Ship a new CRD `consoles.console.streamshub.io` at `v1alpha1` with a identical schema, alongside the existing `consoles.console.streamshub.github.com`.
- Run two controllers in the same operator binary, one watching each group, reconciling both identically.
  Existing old-group custom resources keep working with zero user action and zero downtime.
- Mark the old CRD version deprecated using the native Kubernetes deprecation fields on the version:

  ```yaml
  - name: v1alpha1
    served: true
    storage: true
    deprecated: true
    deprecationWarning: "console.streamshub.github.com/v1alpha1 is deprecated; use console.streamshub.io/v1alpha1 instead"
  ```

  This surfaces a warning in `kubectl` output and in the API-server audit log automatically, which is an established standard Kubernetes operating signal, in addition to any operator-log or Kubernetes Event warnings the operator emits for remaining old-group resources.
- Update all user-facing references to the new group: documentation, example manifests and the `README`.
- OLM: the ClusterServiceVersion declares both CRDs as `owned`, with RBAC covering both groups, so OLM continues to manage the old CRD through the transition and does not drop it mid-upgrade.
- Plain manifests: ship both CRD manifests and grant both groups in the operator's `ClusterRole` for the duration of the migration window.
- Guard against duplicate management: because both controllers run at once, the operator detects when a Console of the same name exists under both groups and refuses to reconcile the one that is not already managing the running child resources, reporting the conflict on its status.
  This ensures the two controllers never fight over the same child `Deployment`, `Service` and so on, and it gives the user a clear signal to finish the migration by removing the old resource.
  This guard is the safety mechanism for the migration approach in Phase 2.
  It is incompatible with the operator-assisted adoption approach described under Rejected alternatives, which deliberately relies on both resources existing at once. 
  
Implementation note.
The operator framework binds each controller to a single custom resource type, so serving a second API group means running a second controller rather than teaching one controller two types.
Standing up that controller is inexpensive; the weight of the change lies in what the two controllers share.

The two controllers do almost exactly the same job, because the schema is the same in both groups.
The work of building and reconciling the child resources depends only on the Console's contents, which are identical whichever group it belongs to, so very little of the logic actually cares which type it is acting on.
This leaves a straightforward choice: write one shared implementation that serves both groups, or keep two copies, one per group.
Because this migration is temporary and the old group is removed in Phase 3, we favour keeping two copies that we will later delete over building a shared abstraction that we would then have to take apart.
In practice the new group becomes the main, maintained implementation now, and the old group is added as a second copy that is discarded once the migration is complete.

The real design work is not sharing logic but preventing the two controllers from fighting over the same resources.
When the operator creates the child resources for a Console, such as its Deployment and Service, it names them after the Console and manages them the same way regardless of which API group the Console belongs to.
During the migration window both groups are served at once, so an old-group Console and a new-group Console with the same name produce child resources with the same names.
Each controller then sees those children and assumes they are its own to manage, and without protection the two controllers would repeatedly overwrite each other's work.
The Phase 1 guard prevents this: it works out which Console actually owns the running children and stops the other controller from touching them until the user completes the migration.
This guard, rather than the duplicated reconciliation logic, is the part of Part 2 that carries genuine risk and deserves the most careful design.

#### Phase 2: User initiated CRD migration 

Because the schema is identical, migration for a user is simply changing the `apiVersion` from `console.streamshub.github.com/v1alpha1` to `console.streamshub.io/v1alpha1` in their source manifests and re-applying.

However, there is one subtlety to handle.
The old and new custom resources are independent Kubernetes objects, and the operator names child resources deterministically from the CR name (`<cr-name>-<resource>`).
So an old-group `my-console` and a new-group `my-console` would both try to manage the same child `Deployment`, `Service` and so on.

To handle this, a user would need to perform a brief planned restart per Console. 
This approach has the advantage of requiring no extra operator code.
We will document a per-Console delete-old then apply-new sequence: export the existing CR, change its `apiVersion`, delete the old CR (which garbage-collects its children via owner references) then apply the new CR (which the operator recreates children for).
This incurs a single Console UI restart per instance.
It is justified because the Console is an administrative and observability UI with high downtime tolerance, and because the CRD is alpha.
It requires no additional operator code, is fully auditable and is GitOps-friendly.
If both resources briefly coexist (for example when a GitOps sync creates the new resource before pruning the old one), the Phase 1 guard prevents them from fighting over children and reports what to do.

The operator should not create new-group CRs on the user's behalf.
Doing so produces objects that the user's GitOps controller does not own, which causes drift and adoption conflicts.
Migration is user-driven and declarative.

#### Phase 3: Removal of old group CRDs

- Remove the old-group controller and remove the old CRD from the operator deliverables.
- OLM: the CSV drops the old CRD from `owned`.
  Plain manifests: the old CRD manifest and its `ClusterRole` entry are removed.
- Neither OLM nor `kubectl` deletes an existing CRD automatically.
  OLM deliberately never removes owned CRDs, to prevent data loss.
  So we will need to document a manual administrator cleanup once migration is confirmed as complete: verify none remain, then delete the old CRD.

  ```console
  $ kubectl get consoles.console.streamshub.github.com -A     # expect: no resources
  $ kubectl delete crd consoles.console.streamshub.github.com
  ```

### Open questions

These are the points the community should settle when reviewing this proposal:

- Downtime tolerance.
  The recommended migration accepts a brief restart per Console.
  Whether zero-downtime is a hard requirement is worth confirming, since requiring it would mean revisiting the operator-assisted adoption approach in Rejected alternatives.
- Deprecation window length.
  How many releases the old group remains served before removal (for example two to three minor releases).
- Bundling with the anticipated `v1beta1` field cleanup.
  Several fields in the current schema are already marked deprecated for removal in the next version of the API.
  Bundling that cleanup with this rename would mean users go through one disruptive migration instead of two.
  However, a cross-group rename and a within-group version bump are different mechanisms (the latter can use a conversion webhook), so bundling them raises complexity and risk.
  This is worth an explicit decision either way.
- OLM channel.
  Whether Phase 1 ships on the existing `alpha` channel or a new channel.
- Duplicate-management guard.
  Whether the reconcile-time guard described in Phase 1 is the chosen safety model, or whether the community prefers a validating admission webhook that rejects a same-named duplicate at creation time.

## Affected/not affected projects

Only the `streamshub/console` repository is affected.

- The Maven `groupId` change touches every module in that repository (`root`, `api`, `common`, `coverage`, `operator`, `systemtests` and `ui`).
  Note that `ui` is not part of the Maven reactor and currently points at a stale parent version, so it must be updated by hand rather than swept up by a reactor-wide build.
- The CRD API group change touches the `operator` module, its RBAC and OLM bundle manifests, the CI workflows that reference the generated CRD file by name, the system tests (which currently hard-code the CRD file name) and the documentation and example manifests.

No other project in the StreamsHub organization references either identifier, so no other project is affected.
Third-party operator and CRD dependencies used by the Console operator (Strimzi and Fabric8) are unrelated and unaffected.

## Compatibility

- Maven `groupId`: source-compatible with everything, because there are no existing consumers to break.
  This is why it can be a straightforward one-release change.
- CRD API group: this is a breaking change to a public API, which is precisely why the phased plan above exists.
  During Phase 1 both groups are served, so nothing breaks for existing users until they choose to migrate.
  Kubernetes' native version-conversion mechanisms (conversion webhooks and `kube-storage-version-migrator`) do not apply to a cross-group rename.
  All in-repo references to the old group must be updated in lock-step during Phase 1: the operator `ClusterRole`, the OLM bundle CSVs and channel definitions, the generated CRD manifest file names, the system-test that hard-codes the CRD file name and the documentation and example manifests.

## Rejected alternatives

- Splitting this into two separate proposals (Maven `groupId` alone and CRD group alone).
  Rejected: they are the same underlying decision, adopting `streamshub.io` as the canonical namespace, and reviewers benefit from weighing them and the prior art together.
- A hard cutover in the style of cert-manager: back up all resources, uninstall the old CRDs, reinstall, then restore under the new group.
  cert-manager did this for its `certmanager.k8s.io` to `cert-manager.io` rename and it was painful, but that migration also changed schemas, renamed annotation prefixes and removed fields in the same release, which forced the heavyweight process.
  Our change is a pure group swap with an identical schema and no annotations, so a declarative `apiVersion` re-apply during a dual-serve window is sufficient.
  The heavyweight process is not warranted here.
- Conversion webhooks or `kube-storage-version-migrator`.
  Rejected as technically inapplicable: both operate strictly within a single API group.
- Having the operator auto-create new-group CRs on users' behalf.
  Rejected: it creates objects outside the user's GitOps ownership, causing drift and adoption conflicts.
  Migration should be user-driven and declarative.
- Operator-assisted adoption for zero-downtime migration.
  When a new-group CR appears matching an existing old-group CR (same namespace and name), the operator adopts the existing child resources by re-parenting their owner references to the new CR instead of recreating them, then marks the old CR as superseded.
  Rejected: it requires meaningfully more reconciler code, introduces more edge cases and it is incompatible with the Phase 1 duplicate-management guard, which relies on the two resources never being reconciled at the same time.
  The recommended brief-restart migration is sufficient given the Console's high downtime tolerance and the alpha status of the CRD.
  It should be pursued only if the community decides zero-downtime is a hard requirement.
- Supporting both CRD API groups indefinitely.
  Rejected: it carries an ongoing maintenance burden (two controllers and either duplicated or generified dependents) and confuses users.
  A time-boxed deprecation, signalled with the native `deprecated` and `deprecationWarning` fields, is preferred.
