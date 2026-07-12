# 数据库连接完成说明

后端默认仍使用内存 H2，便于本地快速编译和轻量验证；需要连接已经建好的 PostgreSQL 数据库时，启用 `postgres` profile。

当前已经完成 PostgreSQL 连接配置和最小数据访问层：

- `GET /api/v1/database/status`：检查数据库连接，并统计核心表行数。
- `GET /api/v1/intersections`：读取 `intersections` 路口列表。
- `GET /api/v1/intersections/{code}`：按路口编码读取单个路口。
- `PATCH /api/v1/intersections/{code}/status`：更新路口 `status` 字段，用于验证后端可以修改数据库。

## PostgreSQL 连接配置

配置入口：

```text
src/main/resources/application.yml
```

启用 `postgres` profile 后，后端会使用以下默认连接信息：

```text
jdbc:postgresql://120.48.99.240:5432/traffic_signal
username: traffic_user
password: 123456
```

当前阶段按团队要求将云端连接信息直接写入 `application.yml` 的 `postgres` profile，不再依赖 `.env` 或 `TRAFFIC_DB_*` 环境变量。后续稳定后再改回环境变量或密钥管理。

## 启动方式

PowerShell 示例：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

## 读写验证接口

启动后端后，先检查数据库状态：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/database/status
```

成功时会返回 `connected: true`，并包含核心表的行数统计，例如 `intersections`、`lanes`、`traffic_snapshots` 等。

读取全部路口：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/intersections
```

读取单个路口：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/intersections/BJTU_WEST_GATE
```

验证修改能力时，建议先把状态改成当前原值，避免影响演示数据：

```powershell
Invoke-RestMethod `
  -Method Patch `
  -Uri http://localhost:8080/api/v1/intersections/BJTU_WEST_GATE/status `
  -ContentType "application/json" `
  -Body '{"status":"online"}'
```

`status` 目前允许：

```text
online
maintenance
offline
```

## Flyway 与表结构

项目原有 Flyway 脚本位于：

```text
src/main/resources/db/migration/V1__init_core_tables.sql
```

该脚本中的表结构与当前已经存在的 `traffic_signal` 数据库表结构不是同一套。为了不改动现有数据库，`postgres` profile 中暂时关闭了 Flyway：

```yaml
spring:
  flyway:
    enabled: false
```

同时 Hibernate 不会自动建表或改表：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
```

当前已经优先打通 `intersections` 表的读写闭环。后续如果要继续读取 `lanes`、`traffic_snapshots`、`signal_plans` 等表，可以按同样结构继续补充 Controller、Service 和 Repository。

## 当前验证结果

- 后端编译验证通过：`mvn compile`。
- 当前 `postgres` profile 已直接指向百度云 PostgreSQL：`120.48.99.240:5432/traffic_signal`。
- 启动时无需再设置 `TRAFFIC_DB_PASSWORD`；如连接失败，优先检查云服务器安全组、PostgreSQL `pg_hba.conf` 和用户授权。
