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
jdbc:postgresql://localhost:5432/traffic_signal
username: postgres
password: 从 TRAFFIC_DB_PASSWORD 环境变量读取
```

可通过环境变量覆盖：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `TRAFFIC_DB_URL` | PostgreSQL JDBC 地址 | `jdbc:postgresql://localhost:5432/traffic_signal` |
| `TRAFFIC_DB_USERNAME` | 数据库用户名 | `postgres` |
| `TRAFFIC_DB_PASSWORD` | 数据库密码 | 空 |

## 启动方式

PowerShell 示例：

```powershell
$env:TRAFFIC_DB_PASSWORD="你的数据库密码"
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

如果本机 PostgreSQL 已配置免密访问，可以不设置 `TRAFFIC_DB_PASSWORD`。

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
- 已确认本机 `localhost:5432` PostgreSQL 端口可访问。
- 已临时启动后端并访问新增接口；由于当前启动环境没有提供 `TRAFFIC_DB_PASSWORD`，PostgreSQL 返回“需要密码认证”，所以真实读表请求返回 500。
- 设置正确的 `TRAFFIC_DB_PASSWORD` 后，再访问上述接口即可验证真实读取和修改。
