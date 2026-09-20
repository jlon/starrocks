# Hive/Hudi Connector Cache Session Overrides

## Scope

StarRocks 4.1.4 adds two session-only controls for Hive and Hudi connector metadata reads:

```sql
SET enable_metastore_cache = false;
SET enable_remote_file_cache = false;
```

Both default to `true`. They apply only to the current session and cannot be set globally.

## Semantics

`enable_metastore_cache = false` bypasses query-level and catalog-level Hive
Metastore caches for database, table, partition, and Hive statistics reads. A
bypassed read does not invalidate, read, or populate either cache layer.

`enable_remote_file_cache = false` bypasses query-level and catalog-level
remote-file metadata caches for Hive and Hudi scans and statistics estimation.
A bypassed read does not invalidate or populate either cache layer.

The existing internal `useConnectorMetadataCache=false` override has higher
priority than both session variables. This preserves cache-bypass semantics
required by internal connector write flows.

Asynchronous Hive/Hudi scan range construction binds the initiating
`ConnectContext` in its worker callback so these session settings retain their
meaning outside the request thread.

## Operational Boundary

The controls affect FE metadata lookup and FE-generated scan ranges only. They
do not alter BE data cache behavior and require no BE binary change. New
sessions see the defaults after an FE upgrade; sessions on mixed FE versions
must not set either new variable.
