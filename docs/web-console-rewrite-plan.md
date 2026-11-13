# StarRocks Web Console 重写计划（branch-4.1.4）

> 用 shadcn-admin（https://github.com/satnaing/shadcn-admin.git）作为 UI 基底，将现有 FE/BE HTML 控制台全部迁移为 SPA。本文是执行计划，不是代码。

## 0. 基线

- 工作分支：`feat/web-console`（基于 `branch-4.1.4`，commit 0e030a1130）。
- 旧 HTML 页面（`/system`、`/backend`、`/log`、`/query`、`/session`、`/variable`、`/ha`、`/proc_profile`、`/query_profile`、`/`）本期**全部重写**为 SPA，不使用 iframe 过渡；旧路由保留一个版本作回退。
- SPA 挂 `/ui/`；浏览器访问 `/` 302 到 `/ui/`，带 `Authorization` 的请求仍返回旧首页一个版本。
- 全面登录：新 UI 必须登录才能访问（用户要求）。

## 1. 范围

**做：**

- FE 10 个页面：overview、system、backends、logs、queries、sessions、variables、ha、proc profiles、query profile。
- BE 6 个页面：`/`、`/varz`、`/memz`、`/mem_tracker`、`/logs`、`/proc_profile`，统一从 FE 代理。
- 登录、登出、会话守卫、401/403、权限校验。
- 所有现有行为：leader 转发、query profile 访问检查（4.1.4 为 OPERATE）、log verbose 增删、CSV 下载、profile 链接、硬件信息开关。

**不做：**

- 不加 query kill、SQL 控制台、告警、审计等新功能。
- 不改现有 `/api/*` 的语义，只新增 `/api/web/v1/*`。
- 不引入 i18n（现有 UI 只有英文）、不做 RTL、不保留 Google Fonts。

## 2. 架构决策

| 决策 | 方案 | 理由 |
|---|---|---|
| SPA 位置 | `/ui/`，Vite `base: '/ui/'`，TanStack Router `basepath: '/ui'` | 避开旧 HTML 路由冲突；浏览器访问 `/` 302 到 `/ui/`，下个版本移除旧页面 |
| 静态服务 | 新增 `UiResourceAction`，在 `HttpServerHandler.getAction` 对 `/ui` 前缀直接分派 | PathTrie 的 `*` 只按段匹配，多级资源路径不能只靠 `/ui/*` |
| 鉴权 | 新 `WebApiAction` 基类：cookie 优先，Basic 兼容 | 复用 `HttpAuthManager` 会话，curl/测试仍可用 Basic |
| 登录 | `POST /api/web/v1/login`，JSON body，成功后种 `STARROCKS_SESSION_ID` | 不存密码到 localStorage；支持 LDAP（复用 `AuthenticationHandler.authenticateWithClearPassword`） |
| 登出 | 新增 `HttpAuthManager.removeSessionValue`，清 cookie + 清 react-query 缓存 | 现在没有服务端登出，必须补 |
| CSRF | 依赖现有 `ServerCookieEncoder.LAX`（SameSite=Lax）+ POST 必须 `application/json` + 校验 `Origin` | cookie 已经是 Lax，无需再引入 token |
| BE 统一入口 | FE 代理，显式节点白名单，解析 BE `?raw` 页面为 JSON | 避免 CORS/SSRF；BE 是 C++，本期不在 macOS 上改 BE |
| 构建 | 提交 `fe/ui/dist`；`build.sh` 拷贝到 `output/fe/webroot/ui`；不把 Node 加进发布链 | 发布镜像无 Node；与现有 `webroot/` 提交方式一致 |

## 3. 登录设计（重点）

### 3.1 流程

```
/ui/sign-in (公开)
   └─ POST /api/web/v1/login {username,password}
        ├─ 401 用户名/密码错误（统一文案，不区分用户是否存在）
        ├─ 403 认证成功但没有 NODE 权限
        └─ 200 + Set-Cookie: STARROCKS_SESSION_ID=...; HttpOnly; Path=/; SameSite=Lax; Max-Age=86400
             └─ GET /api/web/v1/session → {user, canAdmin} → 跳转 redirect 或 /ui/
```

- 登录成功后 `queryClient.clear()`，防止上一个会话的缓存串到新用户。
- 路由守卫：`_authenticated` 的 `beforeLoad` 调 `/api/web/v1/session`；401 时 `redirect({ to: '/sign-in', search: { redirect: location.href } })`。
- 任意 API 返回 401：清会话状态、跳登录页；已在 `/ui/sign-in` 时不重复跳转。
- 登出：`POST /api/web/v1/logout` → 服务端删 session、cookie `Max-Age=0`、清缓存、跳登录。

### 3.2 权限

- 登录时执行与页面相同的检查：`Authorizer.checkSystemAction(context, PrivilegeType.NODE)`，失败再 `checkUserOwnsAdminRole`（照抄 `WebBaseAction.java:166-269` 的语义）。
- `canAdmin` 用于前端隐藏 log 配置的增删按钮；服务端仍要再校验，不信任前端。
- `/query-profile` 保留 `requireOperateIfHttpAuthEnabled()`（OPERATE），**不能**因为走了新 API 就放宽。
- `/system` 默认转发 leader（`LeaderOpExecutor`，`SystemAction.java:105`），不能只在 `forward=true` 时转发。

### 3.3 会话细节

- 服务端 `HttpAuthManager`：2 小时 idle、最多 100 个会话。V1 保持现状，但在文档里写明；超过 100 个并发管理员会被淘汰，需要单独评估是否调大。
- Cookie 1 天（`STARROCKS_SESSION_EXPIRED_TIME = 3600*24`，`WebBaseAction.java:70`），但服务端 2 小时 idle 后失效；前端必须处理 401，不能假设 cookie 还在就有效。
- `Secure` 仅在 `Config.enable_https` 或请求本身是 HTTPS 时设置；不要无条件加，否则 HTTP 部署登录失败。
- 顺手修掉现有泄漏：`BaseAction.getAuthorizationInfo` 的 debug 日志（`BaseAction.java:336`）和 `ActionAuthorizationInfo.toString()`（`BaseAction.java:285-291`）明文包含 password。新增 `WebAuthActionTest` 断言日志里不出现密码。
- 登录失败限流复用现有 `RejectedCredentialCache`（`authentication_failure_cache_ttl_second=10`、capacity=1024），不另造。

### 3.4 登录页

- 居中 Card + shadcn `Form`/`Input`/`Button`/`Alert`；Logo + “StarRocks Web Console”。
- 字段：username、password；`autocomplete="username"` / `"current-password"`。
- 状态：提交中禁用按钮；401/403 显示对应错误；网络错误单独提示。
- 已登录访问 `/ui/sign-in` 直接跳 `/ui/`。

## 4. API 契约

新增 `fe/fe-core/src/main/java/com/starrocks/http/action/web/`，统一 `WebApiAction` 基类（JSON 错误、会话、ConnectContext）。

| 方法 | 路径 | 权限 | 数据来源 |
|---|---|---|---|
| POST | `/api/web/v1/login` | 公开 | `AuthenticationHandler` |
| POST | `/api/web/v1/logout` | 会话 | `HttpAuthManager` |
| GET | `/api/web/v1/session` | 会话 | 当前 `SessionValue` |
| GET | `/api/web/v1/overview` | NODE | `IndexAction` 的版本/oshi 数据 |
| GET | `/api/web/v1/system?path=` | NODE | `SystemAction` 的 Proc 逻辑，补 `isDir`、默认转发 |
| GET | `/api/web/v1/backends` | NODE | `BackendAction` 的列，保持现状 |
| GET | `/api/web/v1/queries` | NODE | `ProfileManager`，SQL 按 `ui_queries_sql_statement_max_length` 截断 |
| GET | `/api/web/v1/sessions` | NODE | `ExecuteEnv.listConnection` |
| GET | `/api/web/v1/variables` | NODE | `Config.dump()` + `VariableMgr.dump(GLOBAL)` |
| GET | `/api/web/v1/ha` | NODE | `HaAction` |
| GET | `/api/web/v1/logs?type=fe.warn.log` | NODE | 1MB tail，类型白名单 |
| GET | `/api/web/v1/log-config` | NODE | `Log4jConfig` |
| POST | `/api/web/v1/log-config/verbose` | admin | `{add:[],remove:[]}`，替代现有 GET 改配置 |
| GET | `/api/web/v1/proc-profiles?node=` | NODE | FE 文件系统 / BE `/api/proc_profile/list` |
| GET | `/api/web/v1/query-profile?queryId=&type=` | OPERATE | `ProfileManager` + `sqlformatter`/`ExplainAnalyzer` |
| GET | `/api/web/v1/be?node=&page=` | NODE | 代理 BE，路径白名单 |

实现上不要复制页面拼装逻辑：把 `SystemAction`、`BackendAction`、`HaAction`、`QueryAction` 里的数据获取抽成包内 helper，HTML action 和 JSON action 共用。这样旧页面测试不会因为分叉而失真。

## 5. 前端工程

### 5.1 脚手架

- 新建 `fe/ui/`：Vite + React + TS + Tailwind + shadcn/ui + TanStack Router/Table/Query。
- 从 shadcn-admin 只复制约 5.1k 行：`components/ui`、`components/layout`、`components/data-table`、`context/theme-provider`、`hooks/use-table-url-state.ts`、主题 CSS。
- 删除：`features/{dashboard,apps,chats,tasks,users,settings,auth}`、`routes/clerk`、`stores/auth-store`、Clerk、faker、react-day-picker、input-otp、recharts（现有 UI 没有图表）、Google Fonts、RTL/font 切换、demo 图片。
- 字体改为 system font stack 或自托管 Inter，禁止 CDN。
- 路由：`/sign-in` 公开；其余在 `_authenticated` 下。

### 5.2 页面

| 页面 | 组件 |
|---|---|
| overview | `Card` + 描述列表；保留硬件信息开关 |
| system | 面包屑 + Table；仅 `isDir` 的首列可点；URL 单元格校验后渲染 |
| backends | DataTable，默认 50 行，客户端排序/过滤 |
| logs | `<pre>` tail + 刷新/下载 + 管理员 verbose 编辑器 |
| queries | DataTable + profile 链接 + Download CSV |
| sessions | DataTable |
| variables | Tabs（FE config / global variables）+ 搜索 |
| ha | Cards/描述列表 |
| proc profiles | Tabs（FE/BE）+ Table + View 链接 |
| query profile | 折叠树 + Profile/Formatted SQL/Analyze + Expand/Collapse/Copy/Download |

Profile 树重写：按行首缩进算 depth，渲染扁平列表；折叠时隐藏 depth 更大的后续行直到同/更低 depth。不要用 `innerHTML`，避免现有 XSS 面。CSV 下载用 `Blob` + `URL.createObjectURL`，列数从表头取，不再硬编码 9。

### 5.3 数据层

- `src/lib/api.ts`：`fetch` + `credentials: 'same-origin'`，统一 `ApiError {status, code, message}`；401 交给全局处理。不引入 axios（模板里本来也没用）。
- TanStack Query：手动刷新按钮 + 可选自动刷新（默认关，10s，`refetchIntervalInBackground: false`）。现有 UI 是手动刷新，不要默认开轮询给 FE 加压。
- 表格状态放 URL（复用 `use-table-url-state.ts`），便于分享链接。

## 6. 静态文件与构建

### 6.1 `UiResourceAction`

- 公开（`needPassword()=false`），只服务 `/ui` 和 `/ui/**`。
- 路径规范化：拒绝 `..`、绝对路径、null byte；`canonicalPath.startsWith(root)` 再读。
- `/ui`、`/ui/`、无扩展名的深链接 → `index.html`；未知的带扩展名资源 → 404。
- MIME 显式映射 `.js/.css/.svg/.woff2/.png/.ico`；加 `X-Content-Type-Options: nosniff`。
- 缓存：带 hash 的 `assets/*` 用 `public, max-age=31536000, immutable`；`index.html` 用 `no-cache`。
- 可选：构建时生成 `.gz`，按 `Accept-Encoding` 返回；v1 先不做，实测 WAN 体验再定。

### 6.2 构建/发布

- `fe/ui/package.json` 固定 Node `22.12.x`（Vite 8 要求 20.19+/22.12+），提交 `pnpm-lock.yaml`。
- 提交 `fe/ui/dist`（和现有 `webroot/` 提交静态资源一致），`build.sh` 增加：
  ```bash
  install -d ${STARROCKS_OUTPUT}/fe/webroot/ui
  cp -r -p ${STARROCKS_HOME}/fe/ui/dist/* ${STARROCKS_OUTPUT}/fe/webroot/ui/
  ```
- 不把 Node 写进 `dev-env` 镜像；本地/CI 用固定 `node:22.12` 容器构建。
- 本地修改 `fe/ui/src` 后必须执行 `pnpm exec vite build`（刷新 `routeTree.gen.ts`）再 `pnpm build`，并提交 `fe/ui/dist`。
- License：`pnpm licenses list --prod` 作为门禁，禁止 GPL/AGPL；保留 shadcn-admin 的 MIT 版权声明；补 NOTICE。
- 打包体积门禁：初始 JS gzip < 400KB；路由级自动拆包。

## 7. 测试

**Java（`fe/fe-core/src/test/java/com/starrocks/http/web/`）：**

- 登录成功/密码错/无 NODE/会话读取/登出失效。
- cookie 属性：HttpOnly、Path、SameSite=Lax、HTTPS 时 Secure。
- 日志中不出现密码（Log4j appender 捕获）。
- system JSON：`isDir`、path 规范化、follower 转发。
- query 列表：root 与普通用户、OPERATE 可见性。
- query profile：owner/OPERATE/403。
- log：1MB 截断、类型白名单、verbose 增删仅 admin。
- `/ui`：深链接 fallback、hash 资源缓存头、路径穿越 400/404。
- BE 代理：未知节点拒绝、60s 超时。
- 旧测试 `SystemActionTest`、`HttpUtilsTest`、`IndexActionTest`、`LogActionTest`、`HttpAuthManagerTest` 必须保持绿。

**前端：**

- vitest：profile 树解析、API 401 处理、登录表单错误分支。
- Playwright：登录 → overview → backends → queries → logout，一条 smoke；CI 跑不了就保留手动清单。

## 8. 里程碑

| 阶段 | 内容 | 产出 | 估算 |
|---|---|---|---|
| M0 | 切分支、`fe/ui` 脚手架、删 demo、CI 构建 | 可 `pnpm dev` 的空壳 | 0.5–1 天 |
| M1 | 登录/登出/会话、`/ui` 静态服务、路由守卫、用户菜单 | 能登录进空壳 | 3–4 天 |
| M2 | overview/backends/queries/sessions + API + 测试 | MVP 可日常使用 | 4–5 天 |
| M3 | system/variables/HA/logs + API + 测试 | 覆盖 8 个导航页 | 4–5 天 |
| M4 | query profile、proc profiles（FE/BE） | 10 个 FE 页面齐 | 3–4 天 |
| M5 | BE 4 页 JSON + FE 代理 | 统一控制台 | 2–3 天 |
| M6 | 旧链接、文档、构建/发版、E2E、安全 review | 可发布 | 2–3 天 |

合计 **18–25 人天**，加 20% buffer ≈ **5–6 周**（1 人）。M2 结束即可内部试用。

## 9. 风险与待确认

1. `HttpAuthManager` 最多 100 个会话、2 小时 idle：大团队可能不够。v1 先保持，是否调大单独决定。
2. BE 页面没有现成 JSON，M5 的 C++ 改动是唯一跨语言成本；如果要缩范围，可先让 BE 页面继续独立存在，FE 只放链接。
3. 旧 `/system?path=` 有 3 处官方文档引用；本期保留旧页面，下期再加 302 和文档更新。
4. 登录后 cookie 1 天、服务端 2 小时 idle，会出现“cookie 还在但 API 401”；前端必须统一处理，不要各自判断。
5. 如果 FE 部署在 TLS 终止代理后，`Secure` cookie 的判定要按实际请求协议，不要只看 `enable_https`。

**需要确认的 3 个默认值：**

- 新 UI 直接挂 `/`（替换原 `/` 的 IndexAction）；旧 HTML 页面本期全部重写为 SPA，不使用 iframe 过渡。
- BE 6 页纳入本期 M5，还是先只做 FE 10 页（默认：纳入，放最后）。
- 会话上限保持 100（默认：保持，不在本期改）。

确认后从 M0 开始，先切 `feat/web-console` 并搭空壳。


## 10. 实施状态（2026-11-13）

- M0 脚手架、M1 登录/会话/`/ui` 静态服务、M2 集群/查询/会话、M3 系统/变量/HA/日志、M4 proc profile、M5 BE 诊断、M6 构建/文档均已完成。
- 关键提交：`4e6cdbd082`（脚手架）、`a65a4ffee2`（登录）、`67ae60af4c`（M2）、`5024776ca4`（M3）、`fe2176302b`（M4）、`4fec2bb14e`（M5）。
- 测试：`fe/fe-core/src/test/java/com/starrocks/http/action/web/` 下 34 个用例全绿；M1–M5 的 `checkstyleMain/checkstyleTest` 通过。
- 已知限制：BE 页面通过 FE 解析其 `?raw` HTML 提供；BE 自身的 Logs handler 仍输出 “Couldn't open INFO log file”，因此 BE Console 的 Logs 标签只显示该提示。BE 端 JSON 接口留待后续版本。
