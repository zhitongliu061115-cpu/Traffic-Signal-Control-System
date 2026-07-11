# 本机后端连接百度云 PostgreSQL

本文说明本机运行的后端如何连接百度云服务器 `120.48.99.240` 上的 PostgreSQL。

## 连接方式

当前方式是：

- 后端运行位置：本机 Windows
- PostgreSQL 运行位置：百度云服务器
- 云服务器公网 IP：`120.48.99.240`
- 数据库端口：`5432`
- 数据库名：`traffic_signal`
- 数据库用户：`traffic_user`

本地连接配置已写入：

```text
backend/.env
```

`.env` 已被 `.gitignore` 忽略，里面包含数据库密码和云服务器 SSH 备忘信息，不应提交到 Git。

## 云服务器侧必须完成的配置

### 1. 百度云安全组放行 5432

入站规则：

```text
协议：TCP
端口：5432
来源：本机公网 IP/32
策略：允许
```

不要长期使用 `0.0.0.0/0` 暴露数据库端口。

### 2. PostgreSQL 监听远程连接

在云服务器上编辑：

```bash
nano /etc/postgresql/*/main/postgresql.conf
```

确认：

```text
listen_addresses = '*'
```

### 3. PostgreSQL 允许本机 IP 登录

在云服务器上编辑：

```bash
nano /etc/postgresql/*/main/pg_hba.conf
```

文件末尾添加：

```text
host    traffic_signal    traffic_user    本机公网IP/32    scram-sha-256
```

然后重启：

```bash
systemctl restart postgresql
```

## 本机验证端口

在 PowerShell 执行：

```powershell
Test-NetConnection 120.48.99.240 -Port 5432
```

如果显示：

```text
TcpTestSucceeded : True
```

说明本机可以访问云端 PostgreSQL 端口。

## 启动后端

在 PowerShell 执行：

```powershell
cd <项目所在目录>\backend
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

示例：如果项目放在 `D:\Projects\Traffic-Signal-Control-System`，则执行：

```powershell
cd D:\Projects\Traffic-Signal-Control-System\backend
```

## 验证后端数据库连接

后端启动后访问：

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/database/status
```

返回中如果包含：

```json
"connected": true
```

表示本机后端已经连接到云服务器 PostgreSQL。

## 注意事项

- `backend/.env` 中的 `BAIDU_CLOUD_SSH_*` 只是本地备忘，不会被后端读取。
- 后端实际使用的是 `TRAFFIC_DB_*` 配置。
- 数据库密码建议后续改成强密码，并同步更新 `backend/.env`。
