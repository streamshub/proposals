# StreamsHub Console: Migrate the Console CRD API Group to `console.streamshub.io`

This proposal applies to the StreamsHub Console project (the `streamshub/console` repository).
It recommends migrating the Console operator's Kubernetes CRD API group from `console.streamshub.github.com` to `console.streamshub.io`, the domain now owned by the StreamsHub organization.

The API group today derives from the project's GitHub location (`github.com/streamshub`) rather than from a domain the organization controls.
The CRD API group change is a public API change affecting existing Console operator installations, so the bulk of this proposal is a migration plan for users who already have the Console CRD deployed.

A companion, purely mechanical change — renaming the Maven `groupId` and Java base package from `com.github.streamshub` to `io.streamshub` — is being handled separately and immediately, because it has no external consumers and need not wait on the CRD migration.
It is tracked as its own [upstream issue](https://github.com/streamshub/console/issues/3117) (motivated by the related [upstream request](https://github.com/streamshub/console/issues/2900) to publish the Console operator's CRD Java classes as reusable artifacts).
Both changes adopt `streamshub.io` as the canonical namespace, but only the CRD group change carries migration risk, which is why it has its own proposal.

## Current situation

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

This identifier has real-world exposure.
The operator has a continuous release history and is distributed through an OLM bundle and catalog (`console-operator-bundle` and `console-operator-catalog`) with an upgrade channel graph chaining releases together.
It is also installable directly from manifests with `kubectl apply`.
We know that there are clusters with `Console` custom resources already deployed under `console.streamshub.github.com/v1alpha1`, some via OLM Subscriptions that may upgrade automatically.

Kubernetes has no in-place mechanism to rename a CRD's API group.
A CRD's identity is `<plural>.<group>`, so changing the group is equivalent to introducing a brand-new resource type.
So this makes the change a genuine public API change requiring a migration plan, rather than just a documentation update.

## Motivation

`streamshub.io` is now owned by the StreamsHub organization.
`console.streamshub.github.com` was derived from the project's GitHub location, not from a domain the organization actually controls, and is not a legitimate reverse-domain identifier for an organization-owned project.

- Consistency and correctness.
  Moving the Kubernetes API group onto `streamshub.io` gives the Console project one coherent, verifiable namespace instead of a GitHub-derived approximation, aligned with the companion Maven `groupId` rename.
- Timing.
  This is the cheapest the migration will ever be.
  Every additional operator release and every additional deployed `Console` custom resource increases the number of installations that must eventually migrate, so the cost of the rename grows the longer it is deferred.

## Proposal

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

### Phase 1: Dual Console CRD serving Operator

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
- Guard against duplicate management: because both controllers run at once, the operator detects when a Console of the same name exists under both groups and refuses to reconcile the one that is not already managing the running resources, reporting the conflict on its status.
  This must cover both the namespaced children (`Deployment`, `Service`, `Secret` and so on) and the cluster-scoped RBAC (`ConsoleClusterRole`, `ConsoleClusterRoleBinding` and any monitoring bindings), both of which share names across the two groups.
  This guard is the safety mechanism for the Phase 2 migration, and it gives the user a clear signal to finish the migration by removing the old resource.
  It is incompatible with the operator-assisted adoption approach described under Rejected alternatives, which deliberately relies on both resources existing at once. 
  
Implementation note:

The operator framework binds each controller to a single custom resource type, so serving a second API group means running a second controller rather than teaching one controller two types.
Standing up that controller is inexpensive; the weight of the change lies in what the two controllers share.

The two controllers do almost exactly the same job, because the schema is the same in both groups.
The work of building and reconciling the child resources depends only on the Console's contents, which are identical whichever group it belongs to, so very little of the logic actually cares which type it is acting on.
This leaves a straightforward choice: write one shared implementation that serves both groups, or keep two copies, one per group.
Because this migration is temporary and the old group is removed in Phase 3, we favour keeping two copies that we will later delete over building a shared abstraction that we would then have to take apart.
In practice the new group becomes the main, maintained implementation now, and the old group is added as a second copy that is discarded once the migration is complete.

The real design work, however, is not sharing this logic but the duplicate-management guard described above: with both groups served, a same-named old- and new-group Console produce identically-named resources, so preventing the two controllers from fighting over them is the part of this migration that carries genuine risk and deserves the most careful design.

### Phase 2: User initiated CRD migration 

Because the schema is identical, migration for a user is simply changing the `apiVersion` from `console.streamshub.github.com/v1alpha1` to `console.streamshub.io/v1alpha1` in their source manifests and re-applying.

However, there is one subtlety to handle.
The old and new custom resources are independent Kubernetes objects, and the operator names child resources deterministically from the CR name (`<cr-name>-<resource>`).
So an old-group `my-console` and a new-group `my-console` would both try to manage the same child `Deployment`, `Service` and so on.

To handle this, a user would need to perform a brief planned restart per Console. 
This approach has the advantage of requiring no extra operator code.
We will document a per-Console delete-old then apply-new sequence: export the existing CR, change its `apiVersion`, delete the old CR, wait for it to be fully removed, then apply the new CR (which the operator recreates children for).

Deleting the old CR tears down its children in two different ways, and both must complete before the new CR is applied.
The namespaced children (`Deployment`, `Service`, `Secret` and so on) are garbage-collected via their owner references.
The cluster-scoped RBAC (`ConsoleClusterRole`, `ConsoleClusterRoleBinding` and any monitoring bindings) cannot carry an owner reference to a namespaced CR, so it is removed instead by the operator's reconciler cleanup (`ConsoleReconciler implements Cleaner`) behind a finalizer.
This cleanup only runs while the old-group controller is still present, which it is throughout Phase 2.
The user should therefore wait for the old CR to disappear completely (finalizer cleared) before applying the new one, so the new CR does not contend with the cluster-scoped RBAC the old CR has not yet released.
If the order is not respected and both briefly coexist, the Phase 1 guard catches it: it refuses to reconcile the new CR while the old one still owns the running resources and reports the conflict on status, rather than letting the two fight.
`kubectl delete` blocks until the object is gone by default, but this ordering matters for GitOps flows that might apply the new resource and prune the old one in a single commit, so it is worth stating explicitly.

This incurs a single Console UI restart per instance.
It is justified because the Console is an administrative and observability UI with high downtime tolerance, and because the CRD is alpha.
It requires no additional operator code, is fully auditable and is GitOps-friendly.
If both resources briefly coexist (for example when a GitOps sync creates the new resource before pruning the old one), the Phase 1 guard prevents them from fighting over children and reports what to do.

The operator should not create new-group CRs on the user's behalf.
Doing so produces objects that the user's GitOps controller does not own, which causes drift and adoption conflicts.
Migration is user-driven and declarative.

### Phase 3: Removal of old group CRDs

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

Removing the old-group controller creates a hazard for any user who has not yet migrated, and this must be guarded against.
Once the old controller is gone, a leftover old-group Console still carries its finalizer, so deleting it (or the old CRD) hangs because nothing is left to run the cleanup.
Worse, its cluster-scoped RBAC is left behind under exactly the `<namespace>-<name>-<resource>` names the new-group CR needs, so even creating the new CR then conflicts.
With OLM automatic approval, a Subscription can carry a user into the Phase 3 release without them ever having migrated, landing them directly in this state.

We address this with two complementary measures:

- Prevention (recommended).
  The last release that serves both groups reports `Upgradeable=False` on its OLM `OperatorCondition` whenever any old-group `Console` still exists.
  OLM reads the *currently installed* operator's `OperatorCondition` before applying a replacement, so when a newer (Phase 3) CSV tries to install, OLM holds it in `Pending` until the condition clears, rather than automatically upgrading a cluster into the Phase 3 release before its Consoles have been migrated.
  This is bridge-only functionality: the operator must manage its own `OperatorCondition` (fetching the CR named by the injected `OPERATOR_CONDITION_NAME`, and re-evaluating the old-group `Console` count on each reconcile to keep the condition and its `observedGeneration` current), and all of it is removed once the old group is gone.
  It is recommended rather than mandatory because its failure modes are benign (a cluster admin can override it via the condition's `spec.overrides`, a wrongly-set `False` only stalls an upgrade, and a wrongly-set `True` simply falls through to the recovery below) and because it only affects OLM-managed installs; plain-manifest upgrades are a deliberate administrator action and are covered by the recovery step instead.
- Break-glass recovery (mandatory).
  The gate cannot help anyone already stuck such as a plain-manifest user who applied the Phase 3 version by hand or an OLM admin who overrode the condition, so we also document a manual recovery: remove the finalizer on the leftover old-group CR, delete its orphaned cluster RBAC (`ConsoleClusterRole`, `ConsoleClusterRoleBinding` and any monitoring bindings) by their `<namespace>-<name>-<resource>` names, then apply the new-group CR.

### Deprecation Window

We propose that the deprecation window, the period over which we carry both controllers, cover 2 minor releases (allowing a release to patch any issues found).

The current proposal is to do this work in 0.17, carry it in 0.18 and remove the old CRDs in 0.19 or 0.20 (depending on user feedback). 

### Open questions

These are the points the community should settle when reviewing this proposal:

- Downtime tolerance.
  The recommended migration accepts a brief restart per Console.
  Whether zero-downtime is a hard requirement is worth confirming, since requiring it would mean revisiting the operator-assisted adoption approach in Rejected alternatives.
- OLM channel.
  Whether Phase 1 ships on the existing `alpha` channel or a new channel.
- Duplicate-management guard.
  Whether the reconcile-time guard described in Phase 1 is the chosen safety model, or whether the community prefers a validating admission webhook that rejects a same-named duplicate at creation time.

## Affected/not affected projects

The `streamshub/console` repository carries the bulk of the change, but it is not the only StreamsHub project that references the old group, so the migration needs coordinating across a few repositories.

Primary repository:

- `streamshub/console` — the CRD API group change touches the `operator` module, its RBAC and OLM bundle manifests, the CI workflows that reference the generated CRD file by name, the system tests (which currently hard-code the CRD file name) and the documentation and example manifests.

Other repositories that reference `console.streamshub.github.com` and need code or script changes synchronised with the migration phases:

- `streamshub/developer-quickstart` — functional breakage, not just stale docs: `install.sh` and `uninstall.sh` reference the resource by group name (for example `kubectl wait … console.console.streamshub.github.com/…`), so both scripts break in Phase 3 if not updated. Also `components/core/stack/streamshub-console/console.yaml`, the metrics patch (`components/metrics/stack/kustomization.yaml` and `components/metrics/stack/console-metrics-patch.yaml`) and the kroxylicious overlay (`overlays/kroxylicious/stack/kustomization.yaml`).

Documentation-only references, which should be updated but carry no runtime risk:

- `streamshub/site` — the live Console and Developer Quick-Start documentation (`content/console/docs/main/…`, `content/docs/StreamsHub Console/main/…` and the quick-start docs) should be updated. The frozen versioned snapshots (for example `0.12.6` and `0.13.0`) can stay as they are, since they document released versions.
- `streamshub/developer-quickstart` — `docs/troubleshooting.md` and `docs/uninstallation.md`.

## Compatibility

The CRD API group change is a breaking change to a public API, which is precisely why the phased plan above exists.
During Phase 1 both groups are served, so nothing breaks for existing users until they choose to migrate.
Kubernetes' native version-conversion mechanisms (conversion webhooks and `kube-storage-version-migrator`) do not apply to a cross-group rename.
All in-repo references to the old group must be updated in lock-step during Phase 1: the operator `ClusterRole`, the OLM bundle CSVs and channel definitions, the generated CRD manifest file names, the system-test that hard-codes the CRD file name and the documentation and example manifests.

## Rejected alternatives

- A hard cutover in the style of cert-manager: back up all resources, uninstall the old CRDs, reinstall, then restore under the new group.
  cert-manager did this for its `certmanager.k8s.io` to `cert-manager.io` rename and it was painful, but that migration also changed schemas, renamed annotation prefixes and removed fields in the same release, which forced the heavyweight process.
  Our change is a pure group swap with an identical schema and no annotations, so a declarative `apiVersion` re-apply during a dual-serve window is sufficient.
  The heavyweight process is not warranted here.
- Conversion webhooks or `kube-storage-version-migrator`.
  Rejected as technically inapplicable: both operate strictly within a single API group.
- Bundling the group rename with the anticipated `v1beta1` field cleanup.
  Several fields in the current schema are already marked deprecated for removal in the next version of the API, and bundling that cleanup here would mean users go through one disruptive migration instead of two.
  Rejected (consensus reached in review): a cross-group rename and a within-group version bump are different mechanisms (the latter can use a conversion webhook), so bundling them raises complexity and risk, and the field removals are not significant enough to justify a new CRD version at this time.
  The field cleanup should be done separately, saved for a more substantial version bump.
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
