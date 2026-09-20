#!/bin/bash
# 神盾插件本地联调：Mock API + Hive Catalog + 权限校验
set -euo pipefail

CONTAINER="${CONTAINER:-starrocks-allin1}"
MYSQL=(docker exec "$CONTAINER" mysql -h127.0.0.1 -P9030 -uroot --batch --skip-column-names)
MOCK_PORT=18999
CATALOG=hive_shield

log() { echo "[shield-test] $*"; }

log "1) 启动 Mock 神盾 API (容器内 ${MOCK_PORT})"
docker exec "$CONTAINER" pkill -f shield-mock-api.py 2>/dev/null || true
docker exec -d "$CONTAINER" python3 /data/deploy/shield-mock-api.py
sleep 1
docker exec "$CONTAINER" bash -lc "curl -sf -X POST http://127.0.0.1:${MOCK_PORT}/oauthority/api/getUserAppGroup \
  -H 'Content-Type: application/json' \
  -d '{\"user\":\"80372263\",\"operator\":\"80372263\",\"sysID\":\"starrocks\",\"reqID\":1,\"signature\":\"x\"}' >/dev/null" \
  || { echo 'Mock API 未就绪'; exit 1; }

log "2) 创建带神盾插件的 Hive Catalog"
"${MYSQL[@]}" -e "DROP CATALOG IF EXISTS ${CATALOG};" 2>/dev/null || true
"${MYSQL[@]}" -e "
CREATE EXTERNAL CATALOG ${CATALOG}
PROPERTIES (
    \"type\" = \"hive\",
    \"hive.metastore.type\" = \"hive\",
    \"hive.metastore.uris\" = \"thrift://127.0.0.1:9083\",
    \"access.controller.class\" = \"com.oppo.starrocks.shield.ShieldHiveAccessController\",
    \"shield.api.domain\" = \"http://127.0.0.1:${MOCK_PORT}\",
    \"shield.api.app_key\" = \"test-app-key\",
    \"shield.api.operator\" = \"80372263\",
    \"shield.api.sys_id\" = \"starrocks\",
    \"shield.api.area_code\" = \"china1\",
    \"shield.permission.cache.ttl.seconds\" = \"10\"
);
"

log "3) 检查 FE 是否加载 ShieldHiveAccessController"
docker exec "$CONTAINER" grep -F "ShieldHiveAccessController initialized" /data/deploy/starrocks/fe/log/fe.log | tail -1 \
  || { echo '未找到插件初始化日志'; exit 1; }

log "4) 创建测试用户并授权 catalog 访问"
"${MYSQL[@]}" -e "
CREATE USER IF NOT EXISTS '80372263_37422' IDENTIFIED BY '';
GRANT USAGE ON CATALOG ${CATALOG} TO USER '80372263_37422';
CREATE USER IF NOT EXISTS 'deny_user_99999' IDENTIFIED BY '';
GRANT USAGE ON CATALOG ${CATALOG} TO USER 'deny_user_99999';
"

log "5) 有权限用户访问 ad_model 库（预期：触发 Shield 放行，HMS 不可达则报 metastore 错而非权限错）"
set +e
ALLOW_OUT=$(docker exec "$CONTAINER" mysql -h127.0.0.1 -P9030 -u80372263_37422 --batch -e "SET CATALOG ${CATALOG}; SHOW DATABASES LIKE 'ad_model';" 2>&1)
ALLOW_CODE=$?
set -e
echo "$ALLOW_OUT"
if echo "$ALLOW_OUT" | grep -qi "Access denied"; then
  echo "FAIL: 有权限用户被 Shield 拒绝"
  exit 1
fi
if echo "$ALLOW_OUT" | grep -qiE "metastore|9083|Failed to connect|Getting analyzing error"; then
  log "PASS: Shield 已放行，后续因无 Hive Metastore 失败（符合预期）"
else
  log "WARN: 未出现预期 metastore 错误，请人工确认输出"
fi

log "6) 无权限用户访问 ad_model 库（预期：Access denied）"
set +e
DENY_OUT=$(docker exec "$CONTAINER" mysql -h127.0.0.1 -P9030 -udeny_user_99999 --batch -e "SET CATALOG ${CATALOG}; SHOW DATABASES LIKE 'ad_model';" 2>&1)
set -e
echo "$DENY_OUT"
if echo "$DENY_OUT" | grep -qi "Access denied"; then
  log "PASS: 无权限用户被 Shield 拒绝"
else
  echo "FAIL: 无权限用户未被拒绝"
  exit 1
fi

log "7) 检查 FE 神盾 API 调用日志"
docker exec "$CONTAINER" grep -E "Shield API done|Shield denied|Shield loadDatabaseTables" /data/deploy/starrocks/fe/log/fe.log | tail -8

log "联调完成"
