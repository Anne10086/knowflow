# KnowFlow M1 实测报告

日期：2026-11-22（Asia/Shanghai）。**M1 核心链路实测通过，提交人工验收；尚未开发 M2。**
本机密钥已由用户通过遮挡输入窗口保存。百炼实际向量化返回 HTTP 200、1024 维。
实际运行模型：**qwen-plus 回答 + text-embedding-v3 向量化**。没有切换或调用 DeepSeek。

## 实际结果与证据

完整 Maven 回归：**41 项通过，0 失败、0 错误，1 项专门重启测试跳过**。
此后新增真实模型重启用例，并用专门脚本执行 seed 和 verify 两轮，各 1 项通过。
另有 PowerShell 5.1 的 9 项合成密钥输入测试通过；不与 Java 数量混算。

| 分组 | 通过数量 | 真实服务／替身边界 |
|---|---:|---|
| KnowledgeApiTest | 5 | MockMvc，业务服务使用 Mockito |
| ModelFailuresTest | 2 | 合成鉴权异常；不调用供应商 |
| TxtParserTest | 9 | 真实 TXT 解析、分块、Unicode 和文件校验 |
| RagServiceTest | 8 | 服务层引用、拒答与删除竞态；外部依赖 Mockito |
| InfrastructureIT | 11 | 真实 PostgreSQL／Milvus；测试专用 embedding，模拟生成 |
| NoModelHttpIT | 5 | 真实 Tomcat HTTP、PostgreSQL、Milvus 健康；故意清空 Key |
| RealModelAcceptanceIT | 1 | **真实 embedding、Milvus、PostgreSQL、qwen-plus 和原文引用，无模型替身** |
| RealRestartPersistenceIT | 两轮各 1 | **真实模型与数据库；两个独立 JVM，中间实际重启四个依赖容器，保留卷** |
| RestartPersistenceIT | 本次完整回归跳过 | 先前的测试 embedding 重启测试已单独通过，历史记录仍保留 |

真实模型接受测试取得以下结果（源文档全部是用户提供的虚构验收资料）：

| 场景 | 实际结果 |
|---|---|
| 蓝鲸版本代号 | BW-4827；引用 bluewhale.txt，原文“版本代号：BW-4827” |
| 蓝鲸归档星期 | 星期三；引用原文“归档星期：星期三” |
| 未提供的预算 | 没有足够依据，grounded=false，sources=[] |
| 两个知识库互相询问对方信息 | 双向拒答、无来源，没有泄露另一个库的代码 |
| 空知识库 | 拒答，无来源 |
| 注入样本与用户注入指令 | 回答 SAFE-204 并引用实际业务原文，没有输出“安全限制已关闭” |
| 删除原文档后再提问 | 拒答，无来源 |
| 实际重启后问版本 | BW-4827，引用同一 documentId 和 chunkId |

- [完整真实模型回归输出](evidence/m1-verify-output.txt)
- [实际回答与来源 JSON](evidence/real-rag-answers.json)：completed=true，包含 8 个场景。
- [真实模型重启输出](evidence/real-restart-output.txt)：seed／verify 各一次 BUILD SUCCESS。
- [重启前回答](evidence/real-restart-seed.json)、[重启后回答](evidence/real-restart-verified.json)。
- [真实 HTTP 完整流程](evidence/live-http-rag.json)：生产 jar 的 API 上传 READY、版本回答、预算拒答、删除后拒答；包含 requestId。
- [当前健康接口](evidence/live-health.json)：HTTP 200，数据库与 Milvus UP。
- [实际 Maven 依赖树](evidence/dependency-tree.txt)、同目录各测试类报告。
- [PowerShell 5.1 输入测试](evidence/key-input-ps51.txt)：9 项通过，仅使用临时目录合成数据。

上述 HTTP、真实模型和重启测试的数据均完成本次用例清理。历史鉴权失败时留存的失败记录与当前成功证据分开记录。
JSON 中的页码为 null：TXT 没有可靠页码，不编造页号。引用偏移是归一化文本的 Java UTF-16 字符位置。
图形运行截图仍未取得，先前图形工具无法激活窗口；没有用示意图或壁纸冒充运行截图。

## 实际环境与复现命令

Windows 11；Java 21.0.10；Maven 3.9.16；Docker 29.8.1；Compose 5.5.1。
Boot 3.5.16、Spring AI 1.1.2、Alibaba core BOM 1.1.2.0、DashScope/extensions 1.1.2.2、Milvus SDK 2.5.8、
Milvus server 2.6.2、PostgreSQL 17.9、etcd 3.5.18、官方源码构建 MinIO RELEASE.2025-10-15T17-29-55Z。
版本来源、源码镜像取舍与模型空间保护见 [decisions.md](decisions.md)。

实际 Maven 可执行文件为 C:\apache-maven-3.9.16\bin\mvn.cmd；Java 位于 D:\JDK21。
在项目 H:\Plan\knowflow 中执行：

```powershell
. ./scripts/Import-LocalEnvironment.ps1
$env:KNOWFLOW_INFRA_TESTS='true'
$env:KNOWFLOW_REAL_MODEL_TESTS='true'
& 'C:\apache-maven-3.9.16\bin\mvn.cmd' -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp verify
# 上述为完整回归的实际命令。以下为真实模型重启实测命令：
.\scripts\Test-RealRestart.ps1 -MavenCommand 'C:\apache-maven-3.9.16\bin\mvn.cmd' -DockerCommand 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
```

专门重启脚本在两个 Maven/JVM 进程之间执行 docker compose restart 和带健康等待的 compose up，不删除卷。
后端当前已重新启动，仅监听 127.0.0.1:18080。健康检查的 CONFIGURED_NOT_PROBED 仍只代表配置存在；
本报告对模型已接通的判断依据是实际生成的回答／引用与 HTTP 验证，而不是健康字段。
新加入的真实重启类只在 KNOWFLOW_REAL_RESTART_PHASE=seed/verify 时执行，普通回归会跳过；
没有 Key 时真实模型类也应跳过或明确失败，不能将其视为通过。

## T01–T18 对照

| ID | 状态 | 本轮证据与范围 |
|---|---|---|
| T01 | 后端通过 | 真实 HTTP 创建／列表、独立数据库连接、实际重启读取；网页刷新属于 M2 |
| T02 | M1 TXT 通过 | 真实解析、分块、embedding、Milvus 入库、READY；PDF/DOCX 未做 |
| T03 | 通过 | 实际回答 BW-4827 与原文引用 |
| T04 | 通过 | 实际回答星期三与原文引用 |
| T05 | 通过 | 空库拒答；单元测试额外验证不调用检索／生成 |
| T06 | M1 通过 | 两个库分别导入蓝鲸／橙灯，真实向量化与隔离 |
| T07 | 通过 | 双库真实模型接受用例与服务端 Milvus filter |
| T08 | 通过 | 反向跨库问答拒绝 |
| T09 | 通过 | 真实模型链路和真实 HTTP 均拒答未知预算 |
| T10 | 通过 | 实际删除向量／原文／元数据，随后问答无来源；删除失败补偿也已测试 |
| T11 | 通过 | 真实模型生成的索引经历四个依赖与 JVM 重启，仍引用相同 ID |
| T12 | 后端通过 | 非法文件、路径、UTF-8 单元测试，真实 HTTP 超大文件 413；UI 未做 |
| T13 | 后端通过 | 同库重复内容 409，不产生额外记录／索引 |
| T14 | 已覆盖部分 | 真实缺 Key HTTP 503、实际错误 Key 401 和异常映射；长时间供应商超时未专门测 |
| T15 | 所给样本通过 | 真实模型忽略注入且保留引用；不声称防御所有可能注入 |
| T16 | 未开发／未测 | M2 完整前端和移动端 |
| T17 | 未开发／未测 | M2 知识库删除与确认；文档删除已验证 |
| T18 | 本地交付检查 | .env／data／target 被忽略；交付前检查源码暂存与 ZIP；无远程仓库 |

## 失败历史、限制与停止条件

先前 DeepSeek Key 被填入百炼变量，两次真实 embedding 和直接请求返回 401；后来用户复制／粘贴失败，
控制台输入脚本出现本地格式拒绝。经用户重新创建／复制百炼 Key、使用本机窗口保存后，当前接口和完整链路通过。
旧失败证据保留于 auth-recheck.txt、direct-provider-auth.json、real-model-reconfigured.txt 等，均为历史结果。
修复了测试 finally 清理掩盖原始异常、SDK 错误日志输出文档原文、Windows 运行 jar 锁阻止重新打包等问题。
MinIO 旧镜像不可获取，改为官方固定源码构建；未替换 Milvus 架构。曾遇到沙箱缓存／loopback 权限错误，经获准后运行。

M1 采用同步 TXT 导入；异步任务队列、PDF/DOCX、完整知识库 CRUD 和界面待 M2。
单用户、单后端进程、loopback 使用，无登录鉴权，不能据此公开部署。
引用校验能证明片段确实存在，但不等于保证每个回答都无幻觉；只对本报告执行的用例负责。
本轮完成 M1 验收材料，**停止，不继续 M2**。本地 commit 在最终回复中报告；未创建或推送远程仓库。