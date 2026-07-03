# OPPO StarRocks Change Log

This document tracks OPPO-owned changes on top of StarRocks 4.1.x.
Keep it updated when adding, backporting, or porting a fork-only change.

## Scope

- Workspace: `/mnt/data/starrocks`
- Current branch: `branch-4.1.1`
- Audit date: 2026-07-03
- Upstream comparison ref: `upstream/branch-4.1.1`
- Upstream ref commit: `14b7e3fa6626a9959179d1b4442d021ce1dd895f`
- Local range audited: `upstream/branch-4.1.1..6fcba194b3ef1ace8ed024dc6d94359decca4da3`
- Local range size at audit time: 117 commits

Evidence commands:

```bash
git rev-parse --verify upstream/branch-4.1.1
git rev-list --count upstream/branch-4.1.1..6fcba194b3ef1ace8ed024dc6d94359decca4da3
git log --format='%H%x09%h%x09%an <%ae>%x09%s' \
  upstream/branch-4.1.1..6fcba194b3ef1ace8ed024dc6d94359decca4da3 \
  --author='oppo.com\|adc.com'
```

## OPPO-Owned Commits On branch-4.1.1

These commits are in the audited local range and have OPPO author evidence.
Treat them as fork-owned changes when moving to another StarRocks branch.

| Commit | Author | Area | Purpose |
| --- | --- | --- | --- |
| `97a036826d420d1e19569ff0c1bdc04a6aee94a2` | `jianglong@oppo.com` | BE cache | Avoid filling data cache from metadata/stat RPCs. |
| `b498560f5d0b154e2e8904b255bea4a6f4e6d695` | `jianglong@oppo.com` | FE shared-data cleanup | Protect StarMgr metadata cleanup in shared-data mode. |
| `f7339f03647b4b60aa3580ffdc8c57993e57ed75` | `qiunan1@oppo.com` | Shield plugin | Enforce Shield permissions at query time through catalog properties. |
| `36c1baa2c92e1a3c24daf1aa5fb50971c82e0c36` | `qiunan1@oppo.com` | Deploy, FE/CN image, Jindo, Shield | Add unified FE/CN image pipeline, Jindo OSS/Hadoop native support, CN entrypoint fix, libthrift update, and Shield/Jindo staging. |
| `32a179b28b5596fb6803069b442bed7b66590ce5` | `qiunan1@oppo.com` | Shield auth | Enable `authentication_shield_shared` shared-password login. |
| `b41d445f348bffce97ecac1309ec971ef9b86b3e` | `qiunan1@oppo.com` | FE entrypoint, Shield auth | Expand `meta_dir` with `POD_NAME`, resolve Leader host from `SHOW FRONTENDS`, and rebuild Shield shared-auth config after Gson load. |
| `3439a375bd26a96691603cfdec0ee6cc3c8dda63` | `jianglong@oppo.com` | BE lake vacuum | Avoid aborting on malformed txn log filenames. |
| `bf21f562e914918721285418bd4ccb526c5fda4f` | `jianglong@oppo.com` | FE lake tablet stats | Add parameter-gated parallel lake tablet stat collection. |
| `ab4e78dbcbbefd9a99e5e2cb003fdf05e9ccbe59` | `jianglong@oppo.com` | Docs | Track OPPO fork changes. |
| `6fcba194b3ef1ace8ed024dc6d94359decca4da3` | `jianglong@oppo.com` | FE lake tablet stats | Preserve parallel collector failure semantics and add serial/parallel correctness verification. |

## OPPO Ports On branch-4.1.2

The local 4.1.2 fork exists at `/mnt/data/starrocks-4.1.2-fork`.
The following commits were observed there on `branch-4.1.2`.

| Commit | Author | Area | Purpose |
| --- | --- | --- | --- |
| `55ff32e09b592bfd86e3e501af0bb48d103dd6b7` | `jianglong@oppo.com` | BE cache | Avoid filling data cache from metadata/stat RPCs. |
| `4c3eec8c3a3ac184b6dd3a50bc3d94624ebdc6c3` | `jianglong@oppo.com` | FE shared-data cleanup | Protect StarMgr metadata cleanup in shared-data mode. |
| `1c2a823de17f0510a6294450d725bd1790bdddc6` | `qiunan1@oppo.com` | Shield plugin | Enforce Shield permissions at query time through catalog properties. |
| `164f4d7460dfab70c0abb34137a291638700e34d` | `qiunan1@oppo.com` | Deploy, FE/CN image, Jindo, Shield | Port unified image pipeline, Jindo support, CN entrypoint fix, libthrift update, and Shield/Jindo staging. |
| `65516092be294b1cb9bc03af72e6e413409afc3a` | `qiunan1@oppo.com` | Shield auth | Enable `authentication_shield_shared` shared-password login. |
| `1a9fdf8a236d06a3ccb331e98715fd5d13b492fe` | `qiunan1@oppo.com` | FE entrypoint, Shield auth | Expand `meta_dir` with `POD_NAME` and rebuild Shield shared-auth config after restart. |
| `1c887a3bef610d23575e852b15c07ac39e1db1b2` | `jianglong@oppo.com` | BE lake vacuum | Avoid aborting on malformed txn log filenames. |
| `c76da8f080cab3061d49e916ff71ffd392c3bc91` | `jianglong@oppo.com` | 4.1.2 port | Align fork-only changes with StarRocks 4.1.2 APIs and build files. |

## Document Maintenance Note

The audited range ends at `6fcba194b3ef1ace8ed024dc6d94359decca4da3`.
The commit that updates this document after that code commit is not self-listed,
because a Git commit cannot contain its own final hash. Record that document
maintenance commit in the next audit if it needs to be tracked explicitly.

## Current Unclassified Local Files

The following uncommitted local files existed before this audit. They are not
classified as OPPO-owned commits in this document until they are reviewed and
committed:

```text
fe/fe-core/src/main/java/com/starrocks/leader/LeaderImpl.java
fe/fe-core/src/main/java/com/starrocks/sql/common/MetaUtils.java
fe/fe-plugin-shield/pom.xml
.kiro/
curvine-article/
docs/curvine-empty-tablet-directory-incident-analysis.md
fe/fe-core/src/test/java/com/starrocks/task/TabletCreationOptimizationLatchTest.java
```

## Update Rules

1. Add every OPPO-owned commit with full hash, author, area, and purpose.
2. Keep upstream StarRocks backports separate from fork-owned changes.
3. Move pending work into a commit table only after the commit exists.
4. When porting to a new branch, record the new commit and source change.
