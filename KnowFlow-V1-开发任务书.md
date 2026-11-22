# KnowFlow V1.0：Java RAG 智能知识库网站完整开发任务书

> 状态：待开发；目标：个人本地可运行、可复现、可测试的首版作品。KnowFlow 为暂定名，不与 SiteDori 项目混合。
> 工作原则：真实功能、可追溯引用、分阶段验证、禁止伪造测试结果；优先完成核心链路，不做多 Agent。

## 0. 项目成功标准

开发一套独立运行的 Java Web 知识库网站。用户可以创建/管理多个知识库、上传 PDF/DOCX/TXT 文档；系统提取文本、切分、调用 embedding 模型并持久化索引；用户仅在所选知识库内问答，得到基于文档的回答、引用文件名和真实证据片段；遇到没有依据的问题明确说不知道；删除文档后不能再检索该文档；服务重启后元数据与索引仍可使用。

**不属于 V1 范围**：公开多人 SaaS、用户注册支付、扫码 OCR、图片理解、多 Agent、联网搜索、模型微调、复杂权限体系、云端规模化部署。V1 默认仅本机 `127.0.0.1` 使用；若要公网开放，必须先补认证、隔离、限流、安全与隐私审查。

## 1. 技术选型与版本原则

- Java：21 LTS；Maven；Spring Boot 3.5.x（以 Spring AI Alibaba 官方当前兼容表为依据，固定经过编译与启动验证的**具体补丁版本**）。
- AI：Spring AI Alibaba 1.1.2.0 BOM + Spring AI 1.1.2 BOM（先验证 BOM 依赖树与构建是否一致）；模型供应商可先用阿里云百炼 DashScope 的聊天模型和 embedding 模型，均从后端环境变量读取密钥。禁止把 API Key 放进前端或 Git。
- 文档解析：Spring AI 的 PDF Reader / Apache Tika Reader；V1 仅支持**可提取文本的** PDF、DOCX、TXT，不做扫描版 OCR。若某文档没有可用文本，展示“无法提取文本”而不是假装成功。
- 向量存储：Milvus Standalone Docker（选择与 Java SDK、Spring AI starter 实测兼容的一个具体版本，锁版本；默认不从 `latest` 拉镜像）。
- 关系型元数据：PostgreSQL 16+ Docker；存知识库、文件元数据、索引批次和每块向量 ID，不把原始文档仅存入 Milvus。
- 前端：React + TypeScript + Vite；自有 CSS 设计规范，必要时复用基础无头组件，但不直接套通用 SaaS 模板。
- 本地编排：Docker Compose 启动数据库依赖；后端 `mvn spring-boot:run`，前端 `npm run dev`。如果宿主机内存不足以运行 Milvus，可提出 PGvector 备选方案，但**必须清楚标记为架构变更**，不可静默替换。
- 版本全部固定到可复现版本。不得依赖不明、已废弃或编造的 Maven artifactId；先阅读官方文档和依赖树并保留验证结果。

参考资料：
- https://www.java2ai.com/docs/versions/
- https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
- https://docs.spring.io/spring-ai/reference/api/vectordbs/milvus.html
- https://milvus.io/docs/prerequisite-docker.md
- https://milvus.io/docs/install_standalone-docker-compose.md

## 2. 用户旅程和界面设计

**核心旅程**：首页 → 新建知识库 → 上传文档 → 看到可理解的解析/索引状态 → 在选定知识库提问 → 阅读答案及证据来源 → 查看源文档对应段落 → 删除/替换文件后确认索引更新。

**设计方向先做对比，不直接生成模板**：
- A「证据优先的阅读工作台」：左侧紧凑知识库树，中间对话/问题及清楚分段的答案，右侧可展开证据区；以文档阅读体验为视觉核心，不堆叠装饰卡片。
- B「紧凑的知识整理桌面」：上方文件处理状态与知识库切换，双栏文档与问答视图，重点突出查找和对比；移动端切换为单栏与底部证据抽屉。
- 为两者提交低保真布局与关键设计说明，按“信息可查找、证据可阅读、手机操作清晰”选择其一。若已有参考图/设计，先以其为依据调整。

**必须支持的交互**：拖拽/选择文件、上传校验、进度或明确的处理状态、重试失败、空知识库、无检索结果、API 失败、加载中、删除确认、禁用态、手机触控、键盘焦点。无凭据时不能显示为“已接入模型”。

**不得采用**：不加推敲的「渐变 Hero + 三列卡片」通用网站模板；虚假按钮；没有解释的发光元素；未经测试就宣称移动端完成。

## 3. 逻辑架构与数据流

```text
React 浏览器界面
    ↓ REST API
Spring Boot (Controller → Service → Repository)
    ├─ PostgreSQL：knowledge_base / document / document_chunk / ingestion_job 元数据
    ├─ 本地私有文件存储：原始上传（配置存储目录，防止遍历）
    ├─ 文档解析器：PDF / DOCX / TXT → text
    ├─ 分块器：段落/页码元数据 → chunks
    ├─ EmbeddingModel → Milvus VectorStore（带 kbId、documentId、chunkId 等 metadata）
    └─ ChatModel ← 检索到的证据片段 → 最终答案
```

**导入流程**：
1. 后端校验文件后写入安全文件目录（不是 web 静态目录），生成 UUID、SHA-256、记录 `UPLOADED`。
2. 异步任务标记 `PARSING` → 提取文本 → `INDEXING` → 分块并索引。成功时标记 `READY`，记录 chunk ID / vector ID；失败时 `FAILED` 并记录可读原因，清理部分索引或提供可重复重试机制。
3. 对同知识库中的完全相同文件（SHA-256 一致），采用拒绝重复并提示已存在，避免无谓重复 embedding 费用。不同知识库可分别添加；同名不同内容不视为相同文件。
4. 同一文档不允许多个导入任务并发产生重复向量。对一次导入做到幂等或通过状态与补偿机制保证一致。

**问答流程**：
1. 验证知识库存在，检查是否有 `READY` 文档。
2. 对问题建立 embedding，在 Milvus 中用**服务端 metadata filter 限定 kbId**，topK 初始 5；不得检索其他知识库，也不能先跨库检索再靠前端过滤。
3. 设定合理最小相关性/证据门槛，零结果或低质量证据时返回“当前知识库没有足够依据回答”，不得凭大模型常识补答案。
4. 将检索片段作为**不可信的数据**传给模型，明确忽略文档内对系统的指令；要求回答依据提供的片段，不编造出处。提示词不能当作唯一安全措施。
5. 来源引用由**后端根据实际检索结果生成结构化来源列表**，不允许只相信模型生成的文件名、页码。PDF 有可靠页面信息时显示页码；其他文档可以显示文段编号，无可靠页码就不显示。
6. 返回 answer、sources、grounded/insufficientEvidence、requestId、耗时（如可取得）。不把模型的“自信程度”当事实分数。

**删除流程**：先将文档标记 `DELETING`，按保存的 vector IDs 删除 Milvus 向量，再删除元数据/原文件，确保后续查询不再引用它；任何步骤失败必须给出明确状态与可重试补偿，不能“界面删了、向量还在”。删除知识库需要级联安全清理其全部文档与索引，并明确二次确认。

## 4. 最小数据模型

- `knowledge_base(id UUID PK, name, description, created_at, updated_at)`
- `document(id UUID PK, kb_id FK, original_filename, stored_filename, mime_type, bytes, sha256, status, error_message, chunk_count, created_at, updated_at)`
- `document_chunk(id UUID PK, document_id FK, kb_id FK, vector_id, chunk_index, page_number NULL, excerpt, created_at)`
- `ingestion_job(id UUID PK, document_id FK, status, started_at, completed_at, error_message)`

记录数据库迁移脚本（Flyway 或 Liquibase，选一种），不要只依赖 JPA `ddl-auto=update` 作为正式数据迁移方案。索引 metadata 至少包含 kbId、documentId、chunkId、filename、chunkIndex、pageNumber（如有）。

## 5. API 合同（V1）

所有成功/失败响应采用统一的 JSON 风格；出错须有 code、message、requestId；API 具体状态码符合 HTTP 语义。

| Method | Path | 职责 |
|---|---|---|
| GET | `/api/health` | 应用就绪/依赖状态；不泄露密钥 |
| POST | `/api/knowledge-bases` | 创建知识库 `{name, description}` |
| GET | `/api/knowledge-bases` | 列表 |
| PATCH | `/api/knowledge-bases/{id}` | 改名/描述 |
| DELETE | `/api/knowledge-bases/{id}` | 删除知识库与关联索引 |
| POST | `/api/knowledge-bases/{id}/documents` | `multipart/form-data` 上传，返回 documentId + ingestion status，推荐 `202` |
| GET | `/api/knowledge-bases/{id}/documents` | 文件列表和状态 |
| GET | `/api/documents/{id}/status` | 处理状态、错误信息、chunkCount |
| DELETE | `/api/documents/{id}` | 删除文件/关联索引 |
| POST | `/api/knowledge-bases/{id}/ask` | `{question}`；返回 `{answer, sources:[{documentId, filename, chunkId, pageNumber?, excerpt}], grounded, requestId}` |

可额外提供下载原文档的受控接口，但不得让文件路径直接暴露到 Web 根目录。V1 可以不做聊天历史持久化，避免提前加入不必要的会话数据库。

## 6. 项目目录约定

```text
knowflow/
  README.md
  .gitignore
  .env.example                 # 只有变量名和无敏感示例
  docs/
    architecture.md
    decisions.md               # 技术取舍、版本、架构调整依据
    ui-directions.md           # 至少两种实质不同的设计方向及选择理由
    test-report.md
  infra/
    compose.yaml               # PostgreSQL + Milvus 及其必要依赖
  backend/
    pom.xml
    src/main/java/.../
      api/
      service/
      ingestion/
      rag/
      persistence/
      config/
      exception/
    src/main/resources/
      application.yml
      db/migration/
    src/test/
  frontend/
    package.json
    src/
      pages/
      components/
      api/
      styles/
  tests/
    fixtures/
    manual-acceptance.md
```

源码目录不强制逐字一致，但职责清晰、可维护；不得为了填充目录创建空的“企业级”模块。

## 7. 版本与密钥处理

- `.env.example` 写清变量用途，例如 `DASHSCOPE_API_KEY=`, `DB_USER=`, `DB_PASSWORD=`, `MILVUS_HOST=127.0.0.1`, `MILVUS_PORT=19530`, `UPLOAD_DIR=./data/uploads`，**不得包含真实密钥**。
- 后端启动时根据开发环境缺少模型密钥的情况给出清晰诊断；不得在 API 返回或日志输出密钥。
- 记录具体 chat/embedding 模型名称与 embedding 向量维度。创建 Milvus collection 时必须与真实 embedding 维度一致；更换 embedding 模型时必须经过重新建索引策略，不得直接混合维度或污染结果。
- 对上传文件实施尺寸限制（建议 10 MiB）、扩展名/MIME/内容基本一致性检查，阻止路径遍历；拒绝 HTML/JS/可执行文件；不主动执行上传内容中的代码、宏或命令。
- 数据发往第三方模型前，前端需说明上传文档可能经模型服务处理；用公开/自编演示材料，不要默认上传私人、敏感、机密文件。
- 生产部署前另需登录鉴权、访问权限隔离、速率/成本限制、HTTPS、日志审查、备份恢复和合规评估，不能仅因本地功能可用就声称生产级安全。

## 8. 开发三大里程碑与停止条件

### M1：后端真实 RAG 链路

- 从零创建 Spring Boot 项目，锁依赖版本；Docker Compose 启动 PostgreSQL、Milvus；后端成功连接。
- 用一份 `.txt` 测试文件实现：解析 → 分块 → embedding → Milvus 入库 → 按 `kbId` 检索 → 模型问答 → 来源返回。
- 至少编写一个集成测试证明两个知识库互不泄露检索结果，以及一个无证据时不调用生成或返回拒答的测试。
- 提交日志/测试输出；**未通过 M1，不启动完整 UI 的大规模开发**。

### M2：知识库管理 + 文件管理 + 前端

- 补齐知识库 API、PDF/DOCX/TXT 上传、导入任务状态、删除/重试、元数据持久化和完整错误提示。
- 设计两个有实际差异的 UI 方向，选择一个实现；PC 和移动端真实可交互。
- UI 完成文件上传、状态更新、问答、证据引用、知识库切换、删除确认。
- 文件存储和索引在服务重启后仍可用；无 API key、模型超时、数据库不可用有可理解的失败反馈。

### M3：工程验收与文档

- 后端单元/集成测试、前端基本交互测试；对 `tests/fixtures` 的测试资料跑完验收矩阵。
- 记录构建版本、运行步骤、已测场景、未测场景、已知缺陷；提交可验证截图和一段完整操作录屏（可选）。
- 至少进行一次后端重启与依赖重启测试，不做破坏性数据清理。
- 加入 README 的技术架构、运行说明、环境变量、限制、截图；不能称 `100% 无幻觉` 或 `生产就绪`。

## 9. 验收测试矩阵（由验收者重新抽查）

| ID | 操作 | 预期结果 |
|---|---|---|
| T01 | 新建“蓝鲸资料库” | 列表可见，刷新仍存在 |
| T02 | 上传 `bluewhale.txt` | 有处理状态，最后变 `READY`，可见分块数 |
| T03 | 问“蓝鲸测试计划的版本代号是什么？” | 答 `BW-4827` 并引用 bluewhale.txt 的相关原文 |
| T04 | 问“蓝鲸测试计划的归档星期是？” | 答 `星期三` 且有正确引用 |
| T05 | 空知识库问任何资料问题 | 明确显示没有可用资料，不编造引用 |
| T06 | 新建“橙灯资料库”，上传 `orangelamp.txt` | 成功，知识库 B 与 A 的数据分隔 |
| T07 | 在橙灯资料库询问蓝鲸计划版本 | 不得泄露 `BW-4827` |
| T08 | 在蓝鲸资料库询问橙灯代码 | 不得泄露 `OL-9015` |
| T09 | 问不在资料中的“项目预算是多少？” | 表示没有足够依据，不能编造预算 |
| T10 | 删除 bluewhale.txt，重复 T03 | 不再检索到该文件原文与答案 |
| T11 | 重启后端/数据库/向量库（保留卷） | 知识库、状态、可查询索引都仍可用 |
| T12 | 上传不支持文件/超大文件 | 后端拒绝并返回清晰错误码与前端提示 |
| T13 | 同知识库重复上传同一文件 | 清楚提示重复，不生成重复向量索引 |
| T14 | 模拟模型不可用/Key 缺失 | 提示错误；不得将假结果作为真实回答 |
| T15 | 上传包含“忽略系统要求”的文件 | 文档内容仅当数据处理，不执行注入指令 |
| T16 | 360–430px 手机宽度及桌面宽度操作核心流程 | 无横向溢出，主要操作可触达，引用可读 |
| T17 | 删除知识库 | 需确认并正确清理文档、向量和元数据 |
| T18 | 查看 Git 历史和 `.env.example` | 无真实密钥/个人文件/凭证 |

**最终验收底线：T01–T18 全部通过或者明确标记阻塞、不得伪称完成。** 先验收真实性与正确性，再讨论高级设计和性能优化。

## 10. 交付给验收者的材料

1. GitHub 仓库链接或打包源码（请勿提供真实 API Key）。
2. `README.md`、实际依赖版本、Docker 配置和 `.env.example`。
3. 三大里程碑各自的提交（commit hash）与测试结果。
4. 一份 `docs/test-report.md`：逐条列出 T01–T18 的实际结果、截图或日志证据、未测原因。
5. 截图：知识库首页、上传状态、问答结果、来源片段、移动端、错误提示。
6. 必要时提供关键 API 请求/响应和数据库表结构；不能提供凭证或个人资料。
7. 说明是否修改过技术栈、为什么修改、与原设计有何不同。

## 11. 发给 Codex 的首轮执行指令

你将开发本仓库 `KnowFlow V1.0`，请以本任务书为唯一产品范围依据，并执行以下要求：

1. **先阅读项目环境和本文档，输出不超过 1 页的实施清单与重大风险**，并核实 Spring Boot/Spring AI Alibaba/Spring AI/Milvus 的依赖版本兼容性；锁具体版本，不引用过时示例，不造 Maven 坐标。若无法联网验证，则报告版本验证缺口。
2. **现在只实施 M1**：初始化后端、Docker Compose、数据库迁移、最小端到端 RAG 问答、按知识库检索的强隔离、结构化引用、无证据拒答、自动化测试。不要提前开发整套 UI 或引入 Agent。
3. 建立 `docs/decisions.md`，解释使用的 embedding 模型、维度、Milvus collection metadata 与删除方案。分块必须保留源文档 ID 与页码（如果实际有）。
4. 开始编码前列出 M1 将创建/修改的文件；代码完成后实际运行构建与测试；若 Docker/网络/密钥不足，明确标注阻塞，不可编造“测试通过”。
5. 不把 API Key 写进代码、日志和提交。不得上传用户个人学习文件作为自动化测试样本，仅使用 `tests/fixtures` 中人工编写的测试材料。
6. 保留 README 的详细 Windows/WSL2 安装与运行指令，并输出你需要我做的最少量人工操作（如安装 Docker 或在本地设置密钥），不要在未经授权下修改 SiteDori 仓库。
7. 每完成一个里程碑，提交可复现的 Git commit，并报告：实际已完成功能、测试证据、已知问题、后续步骤。用户验收 M1 后才继续 M2。

## 12. 建议验收回复模板

```text
KnowFlow 第一阶段 / M1 验收申请
GitHub/源码：
提交版本：
系统环境：Windows / WSL2 / JDK / Docker：
Spring Boot / Spring AI / Spring AI Alibaba / Milvus 版本：
AI 模型供应商与模型名称（不含 API Key）：
完成内容：
运行命令：
自动化测试结果（命令 + 数量 + 失败）：
手动测试（T01~T18 中已覆盖的 ID）：
问题或错误日志（先脱敏）：
截图：
需要重点核验：
```
