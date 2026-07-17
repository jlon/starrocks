---
name: sr-build-fe-k8s
description: >-
  Compile StarRocks FE in the local docker dev-env, package the shared-data (存算分离)
  K8s unified image from local output/, and optionally push to the internal registry.
  Use when the user asks to 编译 FE、打包镜像、打 K8s 镜像、推送镜像、存算分离编译打包,
  or says things like「编译 fe 并打包」「推一下镜像」「按之前流程编 fe」.
---

# StarRocks FE 编译 + 存算分离 K8s 镜像

仓库：`/home/80372263/IdeaProjects/starrocks`（`branch-3.5`，pom 版本常为 `3.4.0`）。

本机 Docker **必须** `sudo`。**禁止**把 sudo 密码写进 skill / 日志 / 提交；从用户当次消息或环境变量 `SUDO_PASS` 读取，用 `echo "$SUDO_PASS" | sudo -S ...`。

## 默认目标

改了 FE 代码后，默认做完整链路：

1. 容器内 `./build.sh --fe`
2. 本地产物打 **K8s 统一镜像**（FE + 已有 SHARED_DATA BE/CN）
3. **仅当用户明确说推送** 时再 `docker push`

不要默认重编 BE（全量 BE 很久）。只有用户明确要求「重编 BE / 存算分离 BE」时才加 `--enable-shared-data`。

## 前置检查

```bash
# 容器应在跑；挂载本机仓库到 /root/starrocks
echo "$SUDO_PASS" | sudo -S docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | head -15

# 期望：sr-build-branch-3.5  Up，镜像 starrocks/dev-env-ubuntu:3.5-latest
# 若 Exited：sudo docker start sr-build-branch-3.5

# 磁盘：根分区建议 ≥50G 可用（打镜像 context 很大）
df -h / | tail -1

# 存算分离 BE 是否已就绪（打 K8s 镜像需要）
stat -c '%y %s' output/be/lib/starrocks_be
grep -E '^USE_STAROS' be/build_Release/CMakeCache.txt | head -1
# 期望 USE_STAROS:BOOL=ON；否则先走「可选：重编 SHARED_DATA BE」
```

## Step 1 — 编译 FE

在仓库根目录执行，日志落到带时间戳的文件：

```bash
cd /home/80372263/IdeaProjects/starrocks
LOG="build-fe-$(date +%Y%m%d-%H%M).log"
echo "LOG=${LOG}"
echo "$SUDO_PASS" | sudo -S docker exec sr-build-branch-3.5 bash -lc \
  'git config --global --add safe.directory /root/starrocks 2>/dev/null; cd /root/starrocks && ./build.sh --fe -j 4' \
  2>&1 | tee "${LOG}"
```

成功标志：日志出现 `Successfully build StarRocks` 且含 Frontend；exit code 0。

校验：

```bash
stat -c '%y %s' output/fe/lib/starrocks-fe.jar fe/fe-core/target/starrocks-fe.jar
```

失败常见原因：`target` 属主是 root、checkstyle/编译错误。修代码后重跑本步；必要时：

```bash
echo "$SUDO_PASS" | sudo -S chown -R "$(id -u):$(id -g)" fe java-extensions output
```

（容器内编译仍以 root 写产物，属主问题按需再修。）

## Step 2 — 打包存算分离 K8s 镜像

复用本地 `output/fe` + `output/be`（BE 须已开 SHARED_DATA）：

```bash
cd /home/80372263/IdeaProjects/starrocks
TAG="3.4.0-ubuntu-amd64-$(date +%Y%m%d-%H%M)"
echo "TAG=${TAG}"
echo "$SUDO_PASS" | sudo -S ./deploy/build.sh \
  --target k8s \
  --artifact-source local \
  --image-name devhub.baymax.oppoer.me/starrocks/starrocks \
  --tag "${TAG}" \
  2>&1 | tee "build-k8s-${TAG}.log"
echo "BUILD_OK=${TAG}"
```

脚本会自动装 Jindo/Hadoop native、同步到 `output/fe/lib/hadoop`、删 stub `core-site.xml`。

成功标志：`Successfully tagged devhub.baymax.oppoer.me/starrocks/starrocks:${TAG}`。

抽检：

```bash
echo "$SUDO_PASS" | sudo -S docker images | grep "3.4.0-ubuntu-amd64" | head -3
echo "$SUDO_PASS" | sudo -S docker run --rm --entrypoint sh \
  "devhub.baymax.oppoer.me/starrocks/starrocks:${TAG}" -c \
  'ls -la /opt/starrocks/fe/lib/starrocks-fe.jar /opt/starrocks/be/lib/starrocks_be; strings /opt/starrocks/be/lib/starrocks_be | grep -c SHARED_DATA'
```

## Step 3 — 推送（仅用户明确要求时）

```bash
TAG="<上一步 TAG>"
echo "$SUDO_PASS" | sudo -S docker push \
  "devhub.baymax.oppoer.me/starrocks/starrocks:${TAG}" \
  2>&1 | tee "push-$(date +%Y%m%d-%H%M).log"
```

完成后回报完整镜像名与 `digest: sha256:...`。

## 可选：重编 SHARED_DATA BE

仅当 CN 报 `not compiled with SHARED_DATA`，或 `USE_STAROS` 不是 `ON`：

```bash
LOG="build-be-shared-$(date +%Y%m%d-%H%M).log"
echo "$SUDO_PASS" | sudo -S docker exec sr-build-branch-3.5 bash -lc \
  'cd /root/starrocks && ./build.sh --be --enable-shared-data -j 4' \
  2>&1 | tee "${LOG}"
```

BE 全量常需 1～2 小时；跑完再执行 Step 2。

## 可选：只打 FE 专用镜像

用户只要 FE 镜像（非 K8s 一体）时才用：

```bash
TAG="3.4.0-trino-compat-$(date +%Y%m%d-%H%M)"
echo "$SUDO_PASS" | sudo -S DOCKER_BUILDKIT=0 docker build \
  --build-arg ARTIFACT_SOURCE=local \
  --build-arg LOCAL_REPO_PATH=. \
  -f docker/dockerfiles/fe/fe-ubuntu.Dockerfile \
  -t "devhub.baymax.oppoer.me/starrocks/starrocks-fe:${TAG}" .
```

线上存算分离集群默认用 **K8s 统一镜像**（Step 2），不要和 FE 专用镜像搞混。

## 用户话术 → 动作

| 用户说 | 执行 |
|--------|------|
| 编译 FE / 编一下 fe | Step 1 |
| 打包 / 打镜像 / 存算分离镜像 | Step 1（若 jar 已是本次改动可跳过）+ Step 2 |
| 推送 / push | Step 3（TAG 用刚打的或用户指定） |
| 编译并打包并推送 | Step 1 → 2 → 3 |
| 重编 BE / SHARED_DATA BE | 可选 BE 步骤，再 Step 2 |

## 监控上报（Consul /metrics）

K8s 镜像已对齐参考目录 `starrocks-*-centos`：启动时向 Consul 注册 `/metrics`，preStop 注销。

实现：`deploy/monitor/`（`register_to_consul.py` / wrappers），由 `Dockerfile.starrocks-k8s` 打进镜像。

部署 StatefulSet 需配环境变量（与线上 centos 镜像一致）：

| 变量 | 说明 | 建议 |
|------|------|------|
| `CONSUL_ADDRESS` | Consul agent 地址 | 如 `consul-ums-xxx.wanyol.com`（默认测试地址） |
| `METRICS_PORT` | metrics 端口 | FE=`8030`，CN/BE=`8040` |
| `CONSUL_REGISTER_META` | 可选 meta 覆盖 | `zonecode:BJHT,dataset:starrocks` |

验证：Pod 日志有 `register metrics to consul` / `Registration successful`；Consul 出现 `*-starrocks-self-monitor`。

## 完成后回复模板

用中文简短回报：

- FE 编译：成功/失败 + 日志文件名 + jar 时间
- 镜像：`devhub.baymax.oppoer.me/starrocks/starrocks:<TAG>` + 大小
- BE：是否 SHARED_DATA（沿用旧 BE 或新编）
- 监控：镜像是否含 consul register（从本仓库打的 k8s 镜像默认含）
- 若未推送：问是否需要 push

## 关键路径

| 用途 | 路径 |
|------|------|
| 编译入口 | `./build.sh --fe` / `--be --enable-shared-data` |
| 打镜像 | `./deploy/build.sh --target k8s --artifact-source local` |
| Dockerfile | `deploy/Dockerfile.starrocks-k8s` |
| 监控上报 | `deploy/monitor/` |
| 产物 | `output/fe`、`output/be` |
| 构建容器 | `sr-build-branch-3.5` |
| 镜像仓库 | `devhub.baymax.oppoer.me/starrocks/starrocks` |
