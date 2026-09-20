# OPPO Hive UDF compatibility for StarRocks

This module packages the OPPO Hive-compatible UDF behavior as StarRocks Java UDF classes.
It is intended for StarRocks SQL execution, including sessions that use the Trino SQL dialect.

Build:

```bash
cd /root/starrocks/fe
mvn -pl oppo-hive-udf -am -DskipTests package
```

The jar is produced at:

```text
fe/oppo-hive-udf/target/oppo-hive-udf-1.0.0.jar
```

Generate registration SQL after uploading or serving the jar from an FE-accessible URL:

```bash
cd fe/oppo-hive-udf
python3 tools/generate_sql.py --jar-url http://host:port/oppo-hive-udf-1.0.0.jar
```

Then execute `generated/create-functions.sql` in a StarRocks SQL session to register the functions.
After registration, switch query sessions to the Trino dialect when needed:

```sql
SET sql_dialect = 'trino';
SELECT dc_udf.normalize_app_version('13.1.2');
```
