# KnowFlow M1 实测报告

## 配置密钥后的复测（2026-11-22 14:43）

本地已检测到密钥，程序读取值与本地配置一致。两次真实模型尝试均在 embedding 阶段返回 HTTP 401，未进入问答，M1 仍未通过。
当前配置为北京区 https://dashscope.aliyuncs.com，用户正在重新配置密钥。401 也可能来自地区或套餐与接口不匹配，尚未确认具体原因。
官方说明：https://www.alibabacloud.com/help/en/model-studio/error-code

首次真实用例的 finally 清理遇到外键错误，掩盖了原始 embedding 401；已改为清理本次各库所有文档（含失败上传），保留原始异常。
SDK 在失败时会输出 embedding 原文；已禁用供应商原始日志，并新增 MODEL_AUTH_FAILED / MODEL_ACCESS_DENIED 的安全提示和两项测试。
修复后不调用真实模型的回归：40 通过、0 失败、2 跳过（真实模型及专门重启用例），不改变此前真实模型失败的结论。
真实模型复测记录见 evidence/auth-recheck.txt；修复回归输出见 evidence/auth-fix-verify.txt。
构建曾因运行中的后端锁住 Windows JAR 而失败；核实 18080 进程属于 KnowFlow 后停止，重新打包成功。
当前独立后端已停止，待用户完成重新配置后启动和复测。下面保留首次无密钥阶段的历史报告。

## 再次配置后的复测（2026-11-22 14:45）

本次完整回归真实执行：40 项通过、1 项真实模型用例报错、1 项专用重启用例跳过。不能标为全部通过。
真实模型依然在首次 embedding 返回 MODEL_AUTH_FAILED（HTTP 401），未进入问题生成。
为排除 Java SDK 影响，直接向同一北京区原生 embedding 接口发送一次最小请求，得到 HTTP 401 / InvalidApiKey / Invalid API-key provided.，没有 embedding 输出。
证据：evidence/real-model-reconfigured.txt、evidence/direct-provider-auth.json。
当前结论是这把密钥不被该接口接受；尚不能确定是地区、套餐、平台来源还是密钥本身问题。等待用户提供平台／地区／套餐信息，不要求其发送密钥，不切换服务商或发送密钥到未知接口。
项目仍为 M1 待验收，独立后端暂未启动，未进入 M2。以下各节为此前历史记录。

日期：2026-11-22。范围：M1 Java 后端；不含完整前端、不含多 Agent、不修改 SiteDori。

**结论：M1 后端候选版本已实现并完成下述验证，但 M1 尚未验收通过。**
缺少 DashScope API Key，真实 embedding → 模型问答 → 引用的完整链路未执行。
不能把数据库集成测试使用的测试向量／模拟生成器算成真实模型接通。

## 环境与版本

Windows 11；JDK 21.0.10；Maven 3.9.16；Docker Engine 29.8.1；Compose 5.5.1（Linux containers）。
Boot 3.5.16；Spring AI 1.1.2；Alibaba core BOM 1.1.2.0、DashScope/extensions 1.1.2.2；
Milvus SDK 2.5.8 + server 2.6.2；PostgreSQL 17.9；etcd 3.5.18；MinIO 官方源码 RELEASE.2025-10-15T17-29-55Z。
实际依赖树：[dependency-tree.txt](evidence/dependency-tree.txt)。版本依据与变更见 [decisions.md](decisions.md)。

## 实际测试结果

最终执行 `verify`：**38 项通过，0 失败、0 错误；另有 2 项跳过**。
重启用例在专门脚本中另行执行 seed、verify 两轮，各 1 项通过；最终普通回归中跳过该用例是预期的。

| 测试类 | 实际执行／结果 | 服务与替身边界 |
|---|---|---|
| KnowledgeApiTest | 5 通过 | MockMvc HTTP 合同；业务服务使用 Mockito |
| TxtParserTest | 9 通过 | 真实 TXT 解析／分块；含参数化文件名测试 |
| RagServiceTest | 8 通过 | 引用校验、无证据短路、删除竞态；存储／检索／生成使用 Mockito |
| InfrastructureIT | 11 通过 | **真实 PostgreSQL + Milvus**，Spring AI 实际写入／检索／删除；仅 embedding 为测试字符哈希向量，生成为 Mockito |
| NoModelHttpIT | 5 通过 | **真实 Tomcat HTTP + PostgreSQL + Milvus 健康检查**；明确清空模型 Key，不替换生产业务组件 |
| RealModelAcceptanceIT | 1 跳过 | 未启用真实模型测试，Key 缺失；不算通过 |
| RestartPersistenceIT | 普通回归 1 跳过；专门脚本两轮各 1 通过 | 两个独立 Java 进程，中间实际重启四个 Docker 服务并保留卷；真实索引，测试 embedding |

实测覆盖：知识库服务端过滤、跨库外键约束、实际向量删除、删除失败后 DELETING 排除／重试、
部分写入失败后 FAILED 排除／同 ID 重建、重复文件拒绝、索引中断恢复、独立数据库连接读取、
模型／维度变更拒绝、错误引用阻止、空库不调用生成、缺 Key 的真实 HTTP 503、超大上传真实 HTTP 413。

重启测试先保存 READY 文档、原文和检索命中；seed JVM 退出后执行 `docker compose restart`，
四个服务恢复健康后由另一个 JVM 验证相同文档／分块 ID 和 Milvus 命中，最后清理本次测试数据。
这证明了所测索引和元数据跨服务／JVM 重启的持久化，不证明外部模型质量。

## 实际执行命令与证据

本机 Maven 不在 PATH，实际使用 `C:\apache-maven-3.9.16\bin\mvn.cmd`；JDK 在 `D:\JDK21`。
日志保存了实际输出（归档时统一换行并去除行末空白，未改动测试内容）；下面以等价的 Maven 命令缩写展示，均在项目根目录执行。

```powershell
java -version
mvn -version
docker version
docker compose version
docker compose up -d --build --wait --wait-timeout 240
mvn -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp verify
. ./scripts/Import-LocalEnvironment.ps1
$env:KNOWFLOW_INFRA_TESTS='true'
mvn -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp verify
pwsh -NoProfile -File scripts/Test-Restart.ps1 `
  -MavenCommand 'C:\apache-maven-3.9.16\bin\mvn.cmd' `
  -DockerCommand 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
mvn -s .mvn/settings.xml -gs .mvn/settings.xml -B -ntp dependency:tree '-DoutputFile=docs/evidence/dependency-tree.txt'
pwsh -File scripts/Start-Backend.ps1
docker compose ps
```

- [最终构建／测试输出](evidence/m1-verify-output.txt)、[分类统计](evidence/test-summary.txt)，同目录含每个测试类报告。
- [实际重启两轮输出](evidence/restart-output.txt)，包含两个 `RESTART_PROBE` 结果与两次 BUILD SUCCESS。
- [容器启动结果](evidence/compose-startup.txt)、[独立 jar 启动日志](evidence/backend-startup.txt)。
- [正在运行的 18080 接口响应](evidence/live-health.json)：HTTP 503，database UP、milvus UP、model NOT_CONFIGURED。
- [缺 Key 上传响应](evidence/upload-without-key.json)：MODEL_NOT_CONFIGURED，没有伪造成功文档。

后端目前监听 `127.0.0.1:18080`。没有真实模型问题的成功响应可以提交。
图形截图没有取得：in-app browser 不可用，Docker Desktop 窗口激活失败；截图仅捕获壁纸，已弃用。
因此本次提供原始日志／JSON，不提供伪造的前端或容器截图。

## 遇到的失败与解决情况

1. Docker 沙箱访问命名管道被拒绝：经获准在沙箱外检查／运行，服务测试通过。
2. 原 MinIO 镜像不可用：本轮 Quay 上两个固定旧版本 manifest 检查也失败；改用官方固定源码构建，四个容器均实际健康。
3. Maven 首次测试依赖下载无法写入沙箱外 `.m2` 缓存：获准后重跑成功。最早基线没有测试，不计作任何测试通过。
4. 依赖树导出未加引号的 `-DoutputFile=...txt` 被 PowerShell 错误拆分，报 `Unknown lifecycle phase ".txt"`：给完整参数加引号后成功。
5. 后端普通沙箱启动失败：`Unable to establish loopback connection`／`Permission denied: connect`；获准后沙箱外启动成功。
   启动脚本同时改为优先使用 JAVA_HOME，避开本机 Oracle PATH 转发程序。
6. 模型未配置：真实模型测试明确跳过；未尝试编造 Key 或绕开供应商调用。
7. 图形捕获不可用：未将空白／壁纸截图作为运行证据。

## T01–T18 验收矩阵

“后端通过”不代表网页验收通过；真实模型或 M2 操作仍需验收者复测。

| ID | 本轮状态 | 已取得证据／缺口 |
|---|---|---|
| T01 | 后端通过 | 真实 HTTP 创建与列表、SQL 持久化及重启探针；网页刷新待 M2 |
| T02 | 部分 | 真实 TXT 提取、分块、状态、Milvus 入库通过；embedding 是测试向量，真实模型待 Key |
| T03 | 阻塞 | BW-4827 真实模型问答未执行 |
| T04 | 阻塞 | 星期三真实模型问答未执行 |
| T05 | 后端通过 | 真实 HTTP 空库拒答、无来源；单元测试确认不调用检索／生成 |
| T06 | 部分 | 两个真实库与测试向量索引通过；真实模型未执行 |
| T07 | 检索层通过，问答待测 | Milvus 服务端过滤真实执行；模型跨库提问待 Key |
| T08 | 检索层通过，问答待测 | 反向检索同样隔离；模型跨库提问待 Key |
| T09 | 阻塞 | 已测试 sufficient=false 的程序路径，真实预算拒答未执行 |
| T10 | 后端通过（测试向量） | Milvus 实际删除与 PostgreSQL READY 排除；删除失败补偿也通过 |
| T11 | 存储／索引重启通过 | JVM 与四个服务均实际重启，原文／元数据／真实 Milvus 检索仍存在；模型替身 |
| T12 | 后端通过 | 非法类型／路径／UTF-8／尺寸单元测试、真实 HTTP 超大上传 413；UI 未做 |
| T13 | 后端通过 | 同库相同内容 409，文档／向量无额外重复 |
| T14 | 部分 | 缺 Key 真实 HTTP 503；生成异常 HTTP 合同通过，真实供应商中断／超时待测 |
| T15 | 待真实模型 | 不可信数据提示与引用结构校验已实现；真实注入样本未执行 |
| T16 | 未开发／未测 | M2 前端与移动端范围 |
| T17 | 未开发／未测 | 知识库删除与确认属于 M2；文档删除已单独验证 |
| T18 | 源码防泄漏检查；无远程 | `.env`／target／data 被忽略；交付前检查暂存文件与源码包，不发布真实凭据 |

## 交付边界与最少人工操作

工程实现候选版本，未达到“验收完成”。未创建远程仓库或发布网站；交付本地源码与不含密钥的数据包。
本地提交由最终回复报告 commit hash，可用 `git log -1 --oneline` 核对。

在本机 PowerShell 7 设置 Key（隐藏输入），然后重启正在运行的后端：

```powershell
cd H:\Plan\knowflow
pwsh -File scripts/Set-ModelKey.ps1
# 停止原后端进程后再启动；原进程不会自动加载新 Key。
pwsh -File scripts/Start-Backend.ps1
# 另开终端执行真实模型验收；会调用外部付费 API。
pwsh -File scripts/Test-M1.ps1 -Infrastructure -RealModel -MavenCommand 'C:\apache-maven-3.9.16\bin\mvn.cmd'
```

目前无须安装 Docker／Java。密钥不可发到聊天。若模型权限、地区、额度或网络失败，应保留实际错误并修复，
在真实用例通过前不得认定 M1 完成，也不继续 M2。

## 密钥输入脚本的 PowerShell 5.1 修复

用户在隐藏输入后遇到 Unexpected key format，脚本没有修改配置，也没有请求供应商；不能由此认定 API Key 无效。
旧校验限制为 16-256 位字母、数字、下划线或连字符；现在支持更长的 ASCII Bearer token（上限 8192），清除首尾空白和一层配对引号。
明确拒绝遮挡星号、变量赋值前缀、内部空白／控制字符；保存时保持其余配置，缺少设置时追加，重复设置合并。
输入合法只表示可以安全保存，不表示已通过供应商鉴权。真实 .env 未被本次修复或测试读取／改写。
Windows PowerShell 5.1.26100.9818 下 9 项脚本测试实际通过；仅临时目录中的合成值。测试首次因模拟 Read-Host 的脚本变量作用域出错而失败，修正测试替身后全部通过。
证据：evidence/key-input-ps51.txt。等待用户再次隐藏输入完整密钥，M1 模型验收状态不变。

## 本机密钥输入窗口

因用户的旧 PowerShell 控制台无法粘贴，为 Set-ModelKey.ps1 增加 -Window 模式：Windows Forms 遮挡输入框，显式粘贴、保存和取消按钮。
窗口不预填现有密钥，不在启动时访问剪贴板；只有用户主动点击粘贴时才交由系统文本框粘贴。用户亲自输入，保存失败显示固定错误，不输出密钥。
窗口与命令行共用保存校验，PowerShell 5.1 的 9 项合成输入回归实际通过；修正了中文引号的解析问题。
已启动独立 STA 进程供用户操作；实际可见布局、剪贴板粘贴和真实保存待用户确认，尚不能算图形交互验收通过。