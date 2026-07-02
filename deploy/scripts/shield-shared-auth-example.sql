-- Shield shared-password Security Integration (requires fe-plugin-shield.jar in FE lib).
--
-- 推荐：CREATE 时设置 shield.shared.password，FE 会转为 MySQL 哈希写入元数据，
-- 重启 / 多 FE 自动同步，无需本地密码文件。
-- 可选：shield.shared.password_file 仍支持从文件读密（K8s Secret 挂载场景）。

CREATE SECURITY INTEGRATION shield_shared
PROPERTIES (
    "type" = "authentication_shield_shared",
    "shield.shared.password" = "YourSharedPassword",
    "shield.shared.username_pattern" = "^\\d+_\\d+$",
    "shield.shared.verify_shield_on_login" = "false",
    "comment" = "Shared password login for Shield users without CREATE USER"
);

ADMIN SET FRONTEND CONFIG ("authentication_chain" = "native,shield_shared");

GRANT USAGE ON CATALOG hive TO ROLE public;

-- 修改共享密码：
-- ALTER SECURITY INTEGRATION shield_shared SET (
--     "shield.shared.password" = "NewSharedPassword"
-- );

-- Login example:
-- mysql -h<fe> -P9030 -u80372263_37422 -pYourSharedPassword

-- 重启后校验（password_hash 应存在且 SHOW CREATE 显示为 ******）：
-- SHOW CREATE SECURITY INTEGRATION shield_shared;
-- 若共享认证报 "Lost connection ... authorization packet"，DROP + CREATE 可临时恢复；
-- 需使用含 Gson 反序列化修复的 fe-plugin-shield 镜像后重启才彻底修复。
