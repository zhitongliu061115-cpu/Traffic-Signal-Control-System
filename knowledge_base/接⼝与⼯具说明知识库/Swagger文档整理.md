# Swagger 文档整理

## 文档信息

- 来源网页：Swagger Docs，https://swagger.io/docs/
- 相关网页：
  - What Is OpenAPI：https://swagger.io/docs/specification/v3_0/about/
  - Swagger Editor Documentation：https://swagger.io/docs/open-source-tools/swagger-editor/
  - Swagger UI Installation：https://swagger.io/docs/open-source-tools/swagger-ui/usage/installation/
  - Swagger Codegen：https://swagger.io/docs/open-source-tools/swagger-codegen/codegen-v3/about/
- 整理日期：2026-07-09
- 适合知识库分类：接口与工具说明知识库、系统操作手册知识库、开发说明知识库
- 关键词：Swagger、Swagger UI、Swagger Editor、Swagger Codegen、OpenAPI、接口文档、SDK 生成、API 调试、API 设计

## 一句话定义

Swagger 是围绕 OpenAPI Specification 构建的一组 API 工具。OpenAPI 是接口描述规范，Swagger 则提供编辑、预览、交互式文档、代码生成、API 探索等工具，帮助团队设计、构建、记录和消费 REST API。

最容易混淆的一点是：OpenAPI 是规范，Swagger 是工具生态。现在所谓“Swagger 文档”通常指使用 Swagger UI 渲染 OpenAPI 描述后得到的交互式接口文档。

## 适合回答的问题

- Swagger 和 OpenAPI 有什么区别？
- Swagger Editor、Swagger UI、Swagger Codegen 分别做什么？
- 如何把 OpenAPI 文件变成可交互接口文档？
- 如何在线或本地编辑 OpenAPI 文档？
- 如何根据 OpenAPI 文档生成客户端 SDK 或服务端桩代码？
- 为什么不要对不可信 OpenAPI 文件直接生成代码？
- 在交通信号控制系统中，Swagger 文档应该如何服务前后端联调和智能体工具说明？

## Swagger 与 OpenAPI 的关系

OpenAPI 文档描述 API 的结构，包括端点、请求方法、参数、请求体、响应、认证方式和元数据。Swagger 工具读取这些描述后，可以进行不同工作：

- Swagger Editor：编辑和校验 OpenAPI 文档。
- Swagger UI：把 OpenAPI 文档渲染成可读、可交互的网页。
- Swagger Codegen：从 OpenAPI 文档生成客户端 SDK、服务端桩代码和文档。
- Swagger Parser、Swagger Core、APIDom 等：用于解析、生成或处理 API 描述。

因此，一个典型流程是：先写 OpenAPI 文件，再用 Swagger 工具编辑、展示、测试和生成代码。

## Swagger Docs 首页包含的主要内容

Swagger Docs 首页把文档分成几类：

1. OpenAPI Specification：介绍 API 描述标准。
2. Swagger Studio：面向协作式 API 设计和治理。
3. Swagger Explore：用于快速连接、测试和探索多协议 API。
4. Swagger Editor：用于创建、编辑、可视化和校验 API 定义。
5. Swagger UI：用于展示交互式 API 文档。
6. Swagger Codegen：用于根据 API 定义生成客户端库、服务端桩代码和 API 文档。

对于本项目的知识库，最重要的是 OpenAPI Specification、Swagger Editor、Swagger UI、Swagger Codegen 四部分。

## Swagger Editor

Swagger Editor 是一个开源编辑器，用于设计、定义和记录 HTTP RESTful API。它围绕 OpenAPI 和 AsyncAPI 等规范提供编辑和可视化能力。

### 主要用途

- 编写 OpenAPI YAML 或 JSON。
- 即时查看文档渲染效果。
- 发现语法错误或结构问题。
- 作为设计优先 API 的协作入口。
- 在接口尚未实现前，先约定路径、参数、请求体和响应结构。

### 使用方式

Swagger Editor 可以通过浏览器访问，也可以本地部署。文档中提到当前 Swagger Editor 与 Swagger Editor Next 有区别：

- Swagger Editor 当前版本仍可用于常规 OpenAPI 编辑。
- Swagger Editor Next 面向新版本能力，支持 OpenAPI 3.1.0。
- SwaggerEditor@4 被视为旧版本方向，未来会逐步迁移到 SwaggerEditor@5。

### 本地运行要点

文档说明本地运行时通常需要 Git、Node.js 和 npm。当前页面给出的最低版本要求是 Node.js 20.3.0 及 npm 9.6.7。实际项目中，如果只是查看接口文档，通常不需要自己开发 Swagger Editor；如果要二次开发或本地部署编辑器，才需要关注这些依赖。

### 知识库整理建议

Swagger Editor 相关内容适合整理进“系统操作手册知识库”或“接口工具说明知识库”，用于回答：

- 如何编辑接口文档？
- 如何检查 OpenAPI 文件语法？
- 在线编辑器和本地编辑器有什么区别？
- 为什么某些 OpenAPI 3.1 功能在旧版 Editor 中不支持？

## Swagger UI

Swagger UI 是最常见的 Swagger 工具。它可以把 OpenAPI 文档渲染为交互式网页，让用户查看接口、参数、请求体、响应结构，并在允许的情况下直接发起测试请求。

### 主要用途

- 把 OpenAPI 文件转换成可读接口文档。
- 按标签展示 API 分组。
- 展示接口方法、路径、参数和响应示例。
- 支持“Try it out”一类的浏览器内测试能力。
- 帮助前端、测试、产品和接口调用方理解后端能力。

### 分发方式

Swagger UI 文档列出了多种使用方式：

- npm 包：适合前端项目或需要打包集成的项目。
- `swagger-ui-dist`：适合服务端直接托管静态资源。
- `swagger-ui-react`：适合 React 项目内嵌。
- Docker 镜像：适合快速启动独立 Swagger UI 服务。
- unpkg 或静态 HTML/CSS/JS：适合简单静态部署。

### 常见配置主题

Swagger UI 文档中还包含以下主题：

- 安装方式。
- 配置参数。
- CORS 处理。
- OAuth 2.0 授权配置。
- Deep Linking 深链接。
- 限制与已知问题。
- OpenAPI 版本识别。
- 插件 API。
- 自定义布局。
- 开发脚本和本地构建。

### 与项目的关系

在交通信号控制系统中，Swagger UI 适合作为后端接口查看入口。建议在知识库中保留：

- Swagger UI 访问地址。
- 文档对应的后端环境，例如开发、测试、演示。
- 哪些接口可以试调用，哪些接口只允许查看。
- 哪些控制类接口需要人工确认或管理员权限。
- 如何根据 Swagger UI 中的 Schema 理解请求和响应字段。

如果系统包含真实控制能力，不建议在公开 Swagger UI 中暴露可直接执行的控制接口，或者至少应加认证、权限、审计和二次确认。

## Swagger Codegen

Swagger Codegen 是一个根据 OpenAPI Description 自动生成代码和文档的工具。它可以生成：

- API 客户端 SDK。
- 服务端桩代码。
- API 文档。

### 支持内容

Swagger Codegen v3 文档中列出了多种支持目标，包括：

- 客户端：Java、JavaScript、TypeScript、Python、Go、PHP、Ruby、Swift、Kotlin、C# 等。
- 服务端：Spring、Node.js、Python Flask、Go server、Kotlin server、Micronaut 等。
- 文档生成器：HTML、动态 HTML、OpenAPI JSON、OpenAPI YAML 等。

### 版本关系

Swagger Codegen 2.x 和 3.x 是不同版本线。文档说明：

- 2.x 和 3.x 使用不同的 group id。
- OpenAPI 3.0.x 支持来自 3.x 版本线。
- 2.x 主要面向 Swagger / OpenAPI 2.0 及更早版本。

如果项目采用 OpenAPI 3.x，应优先确认使用 Swagger Codegen v3 或其他兼容工具。

### 安全提醒

不要对来源不可信的 OpenAPI 或 Swagger 文件直接执行代码生成。原因是生成器会根据文档内容生成代码、模板、文件名、注释或配置，恶意构造的描述文件可能带来代码注入或构建链路风险。

项目中建议只对以下来源生成代码：

- 本项目仓库内受版本控制的 OpenAPI 文件。
- 后端服务自动生成并由团队确认过的接口文档。
- 官方示例或可信服务提供方文档。

对外部来源文档，先审查再生成代码。

## OpenAPI Guide

Swagger Docs 还提供 OpenAPI Guide，覆盖 OpenAPI 3.0 和 Swagger 2.0 的常见写法。OpenAPI 3.0 相关主题包括：

- What Is OpenAPI
- Basic Structure
- API Server and Base Path
- Media Types
- Paths and Operations
- Describing Parameters
- Parameter Serialization
- Describing Request Body
- File Upload
- Multipart Requests
- Describing Responses
- Data Models
- Data Types
- Enums
- Dictionaries、Hashmaps、Associative Arrays
- oneOf、anyOf、allOf、not
- Inheritance and Polymorphism
- Representing XML
- Supported JSON Schema Keywords
- Adding Examples
- Authentication
- Basic Authentication
- API Keys
- Bearer Authentication
- OAuth 2.0
- OpenID Connect Discovery
- Cookie Authentication
- Links
- Callbacks
- Components Section
- Using `$ref`
- API General Info
- Grouping Operations With Tags
- OpenAPI Extensions

这些内容适合补充到接口知识库中，用来解释“某个 OpenAPI 写法是什么意思”。

## Swagger 工具链推荐流程

### 设计阶段

1. 使用 Swagger Editor 编写 OpenAPI 草案。
2. 先定义核心路径、方法、参数、请求体、响应体。
3. 为每个接口设置稳定的 `operationId`。
4. 为重要 Schema 添加示例和字段说明。
5. 与前端、测试、业务方确认接口契约。

### 开发阶段

1. 后端按 OpenAPI 契约实现接口。
2. 前端根据 Swagger UI 查看接口和字段。
3. 测试根据请求示例和响应 Schema 编写用例。
4. 如有需要，使用 Codegen 生成客户端类型或 SDK。

### 联调阶段

1. 使用 Swagger UI 进行只读接口试调。
2. 对写入类、控制类接口进行权限限制。
3. 对危险操作设置人工确认。
4. 把实际错误响应补回 OpenAPI 文档。

### 上线后

1. 保持 OpenAPI 文档与后端实现同步。
2. 将接口变更记录写入开发计划或版本说明。
3. 废弃接口使用 `deprecated` 标记。
4. 知识库定期更新接口说明、字段含义和权限边界。

## 在交通信号控制系统中的使用建议

### 适合作为 Swagger 文档的接口

- 查询路口交通状态。
- 查询仿真帧数据。
- 查询策略日志。
- 创建应急任务草案。
- 生成绿波建议。
- 查询任务执行状态。
- 导出复盘报告。
- 查询告警记录。
- 查询审计日志。

### 需要谨慎暴露的接口

- 直接改变信号灯相位的接口。
- 下发真实控制策略的接口。
- 取消、恢复或覆盖绿波任务的接口。
- 修改权限、角色或审计配置的接口。
- 访问未脱敏历史车辆轨迹或人员信息的接口。

这类接口可以出现在内部知识库，但应明确写出权限、审批、人工确认和审计要求。

## 与智能体知识库的关系

Swagger 文档可以给智能体提供“系统能调用什么工具”的来源，但不能直接等同于智能体可执行权限。

建议整理为三层：

1. 接口层：来自 OpenAPI / Swagger，说明路径、参数、响应。
2. 业务层：来自权限矩阵和制度文档，说明谁能做什么。
3. 智能体层：来自工具 schema 和提示词，说明模型什么时候可以调用、是否需要确认、如何解释结果。

例如：

```markdown
### 工具：query_traffic_status

- 来源接口：GET /api/intersections/{intersectionId}/traffic-status
- Swagger 分组：traffic-status
- 操作类型：只读查询
- 可调用角色：观察员、调度员、应急管理员、系统管理员
- 是否需要人工确认：否
- 输入：intersectionId、timeRange
- 输出：queueLength、avgSpeed、congestionLevel、currentPhase
- 注意：该工具只返回状态，不执行信号控制。
```

对于执行控制类接口，应增加：

- 是否为真实控制。
- 是否演示态。
- 是否需要人工确认。
- 是否写审计日志。
- 失败后是否触发 fallback。

## 知识库整理建议

建议把 Swagger 相关资料拆成以下文档：

1. Swagger 工具说明：解释 Editor、UI、Codegen 的区别。
2. API 查看与联调手册：面向前端和测试说明如何看 Swagger UI。
3. OpenAPI 编写规范：约定本项目的标签、operationId、错误响应、Schema 命名。
4. 智能体工具映射表：把 OpenAPI operationId 映射为智能体工具。
5. 接口权限说明：把 Swagger 接口与角色权限、审批流程绑定。

当前这份文档适合作为“Swagger 工具说明”的基础材料。

## 常见问答材料

### Swagger 和 OpenAPI 是一个东西吗？

不是。OpenAPI 是接口描述规范；Swagger 是围绕 OpenAPI 的工具生态。历史上 Swagger Specification 后来演进为 OpenAPI Specification，因此很多人仍习惯把 OpenAPI 文档称为 Swagger 文档。

### Swagger UI 可以直接调用接口吗？

可以，但取决于配置、浏览器跨域、认证方式、接口权限和后端是否允许。生产环境中应谨慎开放写入类或控制类接口的试调用能力。

### Swagger Editor 是运行接口的吗？

不是。Swagger Editor 主要用于编辑、校验和预览 API 定义。真正调用接口通常由 Swagger UI、测试工具或业务系统完成。

### Swagger Codegen 生成的代码可以直接上线吗？

通常不能直接上线。Codegen 生成的是客户端库、服务端桩代码或文档基础，需要经过项目适配、业务实现、安全审查和测试。

### 为什么智能体不能只看 Swagger 就直接执行控制接口？

Swagger 只说明接口怎么调用，不说明调用是否合规、是否越权、是否需要审批、是否会影响真实信号控制。因此智能体还需要结合权限矩阵、人工确认制度、审计规则和 fallback 规则。

## 与本项目后续文档的衔接

建议后续将以下本地材料与 Swagger 文档一起放入知识库：

- 后端 Controller 接口说明。
- OpenAPI / Swagger 导出文件。
- WebSocket 消息协议。
- `sim.frame` 字段说明。
- `greenwave.*` 消息协议。
- `agent.*` 消息协议。
- 数据库表字段说明。
- 角色权限表和审批流程。
- 智能体工具 schema。

这样智能体回答接口问题时，既能知道“怎么调用”，也能知道“能不能调用、谁能调用、调用后如何解释结果”。
