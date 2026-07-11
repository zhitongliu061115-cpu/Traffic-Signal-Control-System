# OpenAPI 规范整理

## 文档信息

- 来源网页：OpenAPI Specification v3.2.0，https://spec.openapis.org/oas/latest.html
- 规范版本：3.2.0
- 发布日期：2025-09-19
- 整理日期：2026-07-09
- 适合知识库分类：接口与工具说明知识库、系统项目文档知识库
- 关键词：OpenAPI、OAS、HTTP API、接口描述、Schema、路径、参数、响应、认证、组件复用、API 文档生成

## 一句话定义

OpenAPI Specification，简称 OAS，是一种面向 HTTP API 的接口描述标准。它用 JSON 或 YAML 描述 API 的基本信息、服务器地址、路径、请求参数、请求体、响应体、数据模型、认证方式和可复用组件，使人和工具都能理解 API 的能力，而不需要直接阅读源代码或抓包。

在项目中，OpenAPI 文档可以作为后端接口、前端联调、智能体工具调用、测试生成、SDK 生成和接口知识库的共同基础。

## 适合回答的问题

- 某个接口的请求路径、请求方法和参数是什么？
- 某个接口会返回哪些状态码和响应结构？
- 请求体和响应体中的字段类型、是否必填、含义是什么？
- API 使用哪种认证方式，例如 API Key、Bearer Token、OAuth2？
- 哪些 Schema、参数、响应可以被多个接口复用？
- 如何用 OpenAPI 描述文件上传、表单请求、JSON 请求和流式响应？
- 如何为智能体工具调用生成稳定的接口说明？

## OpenAPI 文档的核心用途

1. 接口说明：把 API 能力用统一格式描述出来。
2. 文档生成：交给 Swagger UI、Redoc 等工具生成可读接口文档。
3. 客户端或服务端代码生成：交给 Codegen 类工具生成 SDK、服务端桩代码或类型定义。
4. 自动化测试：让测试工具根据接口契约生成请求、校验响应。
5. 智能体工具说明：将接口路径、参数、Schema 和响应整理成模型可检索、可调用的工具知识。
6. 跨团队协作：前端、后端、测试、运维和产品可以围绕同一份接口契约沟通。

## 文件格式

OpenAPI 描述可以写成 JSON 或 YAML。实际项目里更常用 YAML，因为层级结构可读性更强；如果需要机器处理、接口传输或自动生成，也可以使用 JSON。

规范中的 `description` 等字段可以使用 CommonMark 风格的 Markdown 富文本。写入知识库时，建议保留字段名、路径、方法、状态码和 Schema 名称，避免只保留自然语言说明。

## 根对象结构

OpenAPI 文档最外层是 OpenAPI Object。常见字段如下：

| 字段 | 作用 | 知识库整理建议 |
|---|---|---|
| `openapi` | 声明 OpenAPI 版本，例如 `3.2.0` | 保留，方便判断语法版本 |
| `info` | API 元数据，包括标题、版本、描述、联系人、许可证 | 作为项目接口文档总览 |
| `jsonSchemaDialect` | 指定 Schema Object 默认 JSON Schema 方言 | 高级字段，涉及 3.1+ 和 3.2 时保留 |
| `servers` | API 服务地址列表，可区分开发、测试、生产环境 | 知识库中应说明哪些地址仅用于演示或开发 |
| `paths` | 以路径为键，描述每个接口的操作 | 最核心内容，必须保留 |
| `webhooks` | 描述 API 提供方主动发起的 webhook 请求 | 有回调/事件推送时整理 |
| `components` | 存放可复用的 Schema、参数、响应、示例、安全方案等 | 建议单独整理成“公共模型与组件”章节 |
| `security` | 顶层默认安全要求 | 与权限边界知识库关联 |
| `tags` | 给接口分组 | 可映射到前端模块或业务域 |
| `externalDocs` | 外部文档入口 | 可放项目 README、部署文档、业务说明链接 |

注意：OpenAPI 根对象除了必填字段外，至少应包含 `components`、`paths`、`webhooks` 中的一个，否则无法表达具体 API 能力。

## API 元数据：Info、Contact、License

`info` 用来描述 API 本身，常见字段包括：

- `title`：API 名称。
- `summary`：简短摘要。
- `description`：详细说明，可使用 Markdown。
- `termsOfService`：服务条款地址。
- `contact`：联系人或维护团队。
- `license`：许可证信息。
- `version`：API 文档版本，不等同于 OpenAPI 规范版本。

整理知识库时，建议把 `info.version` 与系统版本、后端版本、发布批次区分开。例如：接口文档版本是 `v1.0`，系统发布版本可能是 `2026.07`。

## Server 对象

`servers` 描述 API 的基础访问地址。一个文档可以包含多个服务器，例如开发环境、测试环境、生产环境。Server URL 可以包含变量，例如：

```yaml
servers:
  - url: https://api.example.com/{basePath}
    variables:
      basePath:
        default: v1
```

Server Variable 常见字段：

- `default`：必填，变量默认值。
- `enum`：可选，限制变量值范围。
- `description`：说明变量含义。

在知识库中，`servers` 适合回答“接口部署在哪里”“演示环境和真实环境有什么区别”“当前接口是否能直接调用”。如果涉及敏感域名、内网地址或密钥，不要直接放入公共知识库。

## Paths 与 Path Item

`paths` 是 OpenAPI 文档的核心。它以 URL 路径为键，例如 `/api/intersections/{intersectionId}/traffic-status`，每个路径下面放不同 HTTP 方法的操作。

路径模板使用 `{name}` 表示路径参数。需要注意：

- 具体路径应优先于模板路径，例如 `/pets/mine` 比 `/pets/{petId}` 更具体。
- 只改变参数名但路径结构相同的模板会产生冲突，例如 `/pets/{petId}` 和 `/pets/{name}` 不能同时表达两个不同路径。
- 过度泛化的路径会导致路由和文档歧义，例如 `/{entity}/me` 和 `/books/{id}` 同时存在时需要谨慎。

Path Item 可定义的 HTTP 操作包括：

- `get`
- `put`
- `post`
- `delete`
- `options`
- `head`
- `patch`
- `trace`
- `query`
- `additionalOperations`

其中 `query` 是较新的 HTTP QUERY 方法相关描述，项目普通 REST 接口通常不需要使用。常规业务接口主要使用 `get`、`post`、`put`、`patch`、`delete`。

## Operation 对象

Operation Object 描述一次具体接口调用，例如 `GET /api/intersections/{id}`。

常见字段：

| 字段 | 说明 |
|---|---|
| `tags` | 接口分组 |
| `summary` | 简短说明 |
| `description` | 详细说明 |
| `operationId` | 操作唯一标识，建议稳定且唯一 |
| `parameters` | 路径、查询、请求头、Cookie 等参数 |
| `requestBody` | 请求体 |
| `responses` | 响应定义 |
| `callbacks` | 异步回调 |
| `deprecated` | 是否废弃 |
| `security` | 覆盖或补充安全要求 |
| `servers` | 覆盖根级或路径级服务器配置 |

知识库整理建议：

- `operationId` 非常重要，建议与后端方法、前端调用、智能体工具名保持稳定映射。
- `summary` 用一句话说明“做什么”。
- `description` 补充业务规则、权限要求、前置条件和异常情况。
- 每个操作必须有清晰的 `responses`，不能只写成功情况。

## Parameter 对象

Parameter Object 用来描述请求参数。OpenAPI 3.2 中 `in` 字段有五类位置：

| 位置 | 含义 | 示例 |
|---|---|---|
| `path` | 路径参数 | `/items/{itemId}` 中的 `itemId` |
| `query` | URL 查询参数 | `/items?page=1` 中的 `page` |
| `querystring` | 将整个查询字符串作为一个整体描述 | 复杂表单或特殊查询串 |
| `header` | 请求头参数 | `X-Request-Id` |
| `cookie` | Cookie 参数 | `sessionId` |

Parameter 的关键规则：

- 参数唯一性由 `name` 和 `in` 共同决定。
- 路径参数通常必须 `required: true`。
- 参数应使用 `schema` 或 `content` 描述，但不能同时使用二者。
- 简单参数可以使用 `schema`；复杂编码或媒体类型相关参数可使用 `content`。
- `style` 和 `explode` 控制数组、对象等复杂值的序列化方式。

对交通信号系统而言，建议明确整理这些参数：路口 ID、道路 ID、时间窗口、策略名称、事件类型、任务 ID、分页参数、排序参数、过滤条件。

## Request Body 对象

`requestBody` 描述请求体，通常用于 `post`、`put`、`patch` 等操作。常见字段：

- `description`：请求体说明。
- `content`：按媒体类型描述请求体，例如 `application/json`、`multipart/form-data`、`application/x-www-form-urlencoded`。
- `required`：请求体是否必填。

`content` 的值是 Media Type Object。每个媒体类型下可以包含：

- `schema`：请求体数据结构。
- `example` 或 `examples`：示例。
- `encoding`：表单或 multipart 字段的编码方式。

知识库整理建议：请求体 Schema 应单独保留字段含义，尤其是必填字段、枚举值、时间格式、单位、范围限制和业务约束。

## Responses 与 Response 对象

`responses` 描述接口可能返回的所有响应。键通常是 HTTP 状态码，例如 `200`、`201`、`400`、`401`、`403`、`404`、`409`、`500`，也可以使用 `default` 表示默认响应。

Response Object 常见字段：

- `description`：响应说明，必填。
- `headers`：响应头。
- `content`：响应体内容，按媒体类型组织。
- `links`：描述响应与后续操作的关系。

知识库整理建议：

- 不只保存成功响应，也要保存错误响应。
- 错误码、错误消息、业务失败原因应单独整理。
- 对智能体工具调用来说，响应字段含义比示例更重要。

## Media Type 与 Encoding

Media Type Object 描述某种内容类型下的数据结构和示例。常见媒体类型：

- `application/json`：最常见的 JSON 请求或响应。
- `application/xml`：XML 兼容场景。
- `multipart/form-data`：文件上传或复杂表单。
- `application/x-www-form-urlencoded`：传统表单提交。
- `text/event-stream`：Server-Sent Events 流式事件。
- `application/jsonl` 或 `application/x-ndjson`：按行输出的 JSON 流。

`encoding` 主要用于表单和 multipart 场景，用来描述某个字段如何编码、使用什么内容类型、是否有额外头信息。

如果知识库用于智能体理解接口，建议重点整理媒体类型、字段结构、文件字段名、数组字段编码规则，不必保留所有底层编码细节。

## Components 对象

`components` 存放可复用对象。本身不会直接影响 API，只有被其他地方引用才生效。

常见组件包括：

| 组件 | 用途 |
|---|---|
| `schemas` | 可复用数据模型 |
| `responses` | 可复用响应 |
| `parameters` | 可复用参数 |
| `examples` | 可复用示例 |
| `requestBodies` | 可复用请求体 |
| `headers` | 可复用响应头或请求头描述 |
| `securitySchemes` | 可复用认证方案 |
| `links` | 可复用链接关系 |
| `callbacks` | 可复用回调 |
| `pathItems` | 可复用路径项 |

建议把 `components.schemas` 单独整理成“数据字典”或“接口字段说明”文档。这样知识库更容易回答字段含义、枚举值、单位和业务约束。

## Reference Object 与 `$ref`

`$ref` 用来引用内部或外部定义，避免重复写同一套 Schema、参数或响应。

常见形式：

```yaml
$ref: '#/components/schemas/TrafficStatus'
```

整理规则：

- 保留 `$ref` 的目标路径。
- 对常用引用对象展开整理一次，避免知识库只看到引用却不知道字段含义。
- 外部引用要保留来源文件或 URI。
- Reference Object 只应关注 `$ref`、`summary`、`description`，不要把它当普通 Schema 随意加字段。

## Schema Object

Schema Object 描述输入和输出数据类型。OpenAPI 3.1+ 与 JSON Schema 的关系更紧密，OpenAPI 3.2 也延续了这种方向。

常见 Schema 能力：

- 基础类型：`string`、`number`、`integer`、`boolean`、`array`、`object`。
- 枚举值：`enum`。
- 必填字段：`required`。
- 对象属性：`properties`。
- 数组元素：`items`。
- 字符串格式：`format`，例如 `date-time`、`uuid`、`email`。
- 组合模型：`allOf`、`oneOf`、`anyOf`、`not`。
- 多态识别：`discriminator`。
- 读写属性：`readOnly`、`writeOnly`。
- 允许空值：OpenAPI 3.1+ 通常使用 JSON Schema 风格，例如 `type: ["string", "null"]`。

知识库整理建议：

- 每个 Schema 单独作为一个小节。
- 字段表至少包含：字段名、类型、是否必填、含义、单位、示例、取值范围。
- 枚举值必须解释业务含义。
- 对交通系统中的时间、速度、排队长度、相位、策略名、任务状态等字段，应说明单位和来源。

## Security Scheme 与 Security Requirement

OpenAPI 可以描述 API 的认证和授权方式。常见安全方案包括：

- HTTP Basic
- Bearer Token / JWT
- API Key
- OAuth2
- OpenID Connect
- Mutual TLS

`components.securitySchemes` 定义认证方案，`security` 定义接口需要满足的认证要求。

理解规则：

- 顶层 `security` 是默认安全要求。
- Operation 级 `security` 可以覆盖顶层要求。
- 同一个 Security Requirement Object 内多个安全方案通常表示都要满足。
- 多个 Security Requirement Object 放在数组里通常表示满足其中一种即可。
- 空对象 `{}` 可以表示允许匿名访问。
- 空数组可以移除顶层默认安全要求。

知识库中应把安全方案与“角色权限表”“审批流程”“审计日志制度”关联起来，而不是只保存技术认证字段。

## Tags 与接口分组

`tags` 用于组织接口。一个接口可以属于一个或多个标签。标签可包含名称、摘要、描述、外部文档等。

在交通信号系统中，建议标签按业务域组织，例如：

- `traffic-status`：交通状态查询。
- `signal-control`：信号控制。
- `greenwave`：应急绿波任务。
- `strategy`：策略建议与日志。
- `simulation`：仿真数据。
- `agent-tools`：智能体可调用工具。
- `audit`：审计与操作记录。

## Specification Extensions

OpenAPI 支持扩展字段，通常以 `x-` 开头。扩展字段适合记录项目特有信息，例如：

- `x-permission`：接口权限要求。
- `x-audit-action`：审计动作名称。
- `x-tool-name`：智能体工具名。
- `x-risk-level`：操作风险级别。
- `x-confirmation-required`：是否需要人工确认。

建议扩展字段保持稳定命名，并在接口知识库中解释含义。

## 面向交通信号控制系统的整理模板

每个接口建议整理为以下结构：

```markdown
### 接口：查询路口交通状态

- operationId：queryTrafficStatus
- 方法：GET
- 路径：/api/intersections/{intersectionId}/traffic-status
- 权限：调度员、应急管理员、系统管理员
- 用途：查询指定路口当前或指定时间窗口内的交通状态。
- 路径参数：intersectionId，路口唯一 ID。
- 查询参数：timeRange、includePrediction。
- 成功响应：200，返回 TrafficStatus。
- 失败响应：400 参数错误，404 路口不存在，403 无权限。
- 业务注意：该接口只查询状态，不直接改变信号控制策略。
```

## 简化示例

下面是一个贴近交通信号系统的简化 OpenAPI 片段，可作为知识库示例，不代表真实接口已经实现：

```yaml
openapi: 3.2.0
info:
  title: Traffic Signal Control API
  version: 1.0.0
  description: 交通信号控制系统接口说明。
servers:
  - url: https://api.example.com/v1
paths:
  /intersections/{intersectionId}/traffic-status:
    get:
      tags:
        - traffic-status
      summary: 查询路口交通状态
      operationId: queryTrafficStatus
      parameters:
        - name: intersectionId
          in: path
          required: true
          schema:
            type: string
          description: 路口唯一 ID。
      responses:
        '200':
          description: 查询成功。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TrafficStatus'
        '404':
          description: 路口不存在。
components:
  schemas:
    TrafficStatus:
      type: object
      required:
        - intersectionId
        - queueLength
        - congestionLevel
      properties:
        intersectionId:
          type: string
          description: 路口唯一 ID。
        queueLength:
          type: integer
          description: 当前排队长度，单位为车辆数。
        congestionLevel:
          type: string
          enum: [LOW, MEDIUM, HIGH]
          description: 拥堵等级。
```

## 建议放入知识库的内容边界

建议放入：

- 接口路径、方法、参数、请求体、响应体。
- Schema 字段说明、枚举值、单位、示例。
- 认证方式和权限提示。
- 操作风险说明，例如是否会影响真实信号控制。
- 接口与前端页面、智能体工具、业务流程的映射关系。

不建议直接放入：

- 生产环境密钥、Token、Cookie。
- 内网敏感地址。
- 真实个人信息、车辆轨迹等未脱敏数据。
- 可能被误认为可直接执行的危险控制指令。

## 与智能体工具调用的关系

如果后续要让智能体调用后端工具，OpenAPI 可以作为工具 schema 的重要来源。建议为每个可调用接口补充：

- 工具名：对应 `operationId`。
- 可调用角色：来自权限矩阵。
- 是否只读：查询类接口通常可直接调用。
- 是否需要人工确认：控制类接口应要求确认。
- 输入参数约束：来自 Parameter 和 Request Body Schema。
- 输出解释：来自 Response Schema。
- 失败处理：来自错误响应。

对于交通信号控制系统，尤其要区分“生成建议”和“执行控制”。智能体可以根据接口生成分析和建议，但真实控制操作应保留人工确认、审批和审计链路。
