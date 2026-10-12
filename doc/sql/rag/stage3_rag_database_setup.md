# 阶段三 RAG 数据库配置与初始化说明

> 对应设计文档：`doc/agent/RAG/rag_development_design.md`
> 本文档汇总 RAG 开发所需的**全部数据库连接配置**与**初始化步骤**，可直接照此创建与校验。

---

## 1. 脚本清单

| 脚本 | 目标库 | 作用 |
|---|---|---|
| `doc/sql/stage3_rag_schema.sql` | MySQL `db_agent` | 创建 `db_agent` 与 4 张 RAG 表（文档 / 父子分片 / 向量化任务 / 检索日志） |
| `doc/sql/stage3_rag_pgvector_schema.sql` | PostgreSQL `postgres`(或 `db_rag`) | 启用 `vector` 扩展、创建 `rag_chunk_embedding` 表与 HNSW 索引 |

配套脚本（非 RAG 专属，但同库）：
- 阶段三 Agent 基础表（`t_agent_session` / `t_agent_message` / `t_agent_run` / `t_agent_tool_call` / `t_agent_draft` / `t_agent_handoff` / `t_agent_eval_*` / `t_agent_feedback`）见 `doc/agent/stage3_agent_development_design.md` 第 8.2 节，需创建在同一个 `db_agent` 库中。
- 阶段一 / 阶段二业务库脚本见 `doc/sql/stage1_schema.sql`、`doc/sql/stage2_schema.sql`。

---

## 2. 连接配置

### 2.1 MySQL（元数据，权威源）

| 项 | 值 |
|---|---|
| 地址 | `100.121.74.115` |
| 端口 | `3306` |
| 库名 | `db_agent`（新建） |
| 用户名 | `root` |
| 密码 | `root` |
| 驱动 | `com.mysql.cj.jdbc.Driver` |
| 连接串 | `jdbc:mysql://100.121.74.115:3306/db_agent?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai` |

### 2.2 PostgreSQL / pgvector（向量库，派生数据）

| 项 | 值 |
|---|---|
| 容器 ID | `0444598a7276` |
| 镜像 | `registry.cn-hangzhou.aliyuncs.com/xfg-studio/pgvector:v0.5.0` |
| 地址 | `100.121.74.115` |
| 端口 | `5432`（`0.0.0.0:5432->5432/tcp`） |
| 库名 | `postgres`（未声明 `POSTGRES_DB` 时的默认库；如需隔离可新建 `db_rag`） |
| 用户名 | `root` |
| 密码 | `root`（生产必须修改） |
| 驱动 | `org.postgresql.Driver` |
| 连接串 | `jdbc:postgresql://100.121.74.115:5432/postgres?ApplicationName=mall-agent-rag&connectTimeout=3&socketTimeout=10` |
| 扩展 | `vector 0.5.0`（支持 HNSW） |
| 向量维度 | `1536`（`text-embedding-3-small`；与 `rag_chunk_embedding.embedding vector(1536)` 必须一致） |

> `pgvector:v0.5.0` 为基础镜像二次打包，基础 PostgreSQL 版本以容器实际为准，可用 `SELECT version();` 确认。pgvector 0.5.0 支持 PG 11–16。

---

## 3. 执行顺序

```text
1) 确认 pgvector 容器在运行
   docker ps --format '{{.ID}} {{.Image}} {{.Ports}}' | grep 0444598a7276

2) 初始化 PostgreSQL 向量库
   docker exec -i 0444598a7276 psql -U root -d postgres < doc/sql/stage3_rag_pgvector_schema.sql

3) 初始化 MySQL 元数据库
   mysql -h 100.121.74.115 -P 3306 -u root -proot < doc/sql/stage3_rag_schema.sql

4) 按需创建阶段三 Agent 基础表（stage3 设计文档 8.2 节，同一 db_agent 库）

5) 可选：建立中文全文索引（hybrid 关键词检索增强，见 stage3_rag_schema.sql 第 5 节）
   ALTER TABLE `t_knowledge_chunk`
       ADD FULLTEXT KEY `ft_chunk` (`chunk_content`) WITH PARSER ngram;

6) 启动 mall-agent-service，确认日志出现「pgvector 就绪」
```

---

## 4. 应用侧配置（`mall-agent-service/src/main/resources/application.yml`）

```yaml
server:
  port: 8085

spring:
  application:
    name: mall-agent-service
  # 主数据源：MySQL db_agent（MyBatis-Plus 使用）
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://100.121.74.115:3306/db_agent?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

# 向量库：PostgreSQL + pgvector（JdbcTemplate 使用，非 MyBatis-Plus）
rag:
  pgvector:
    datasource:
      driver-class-name: org.postgresql.Driver
      jdbc-url: jdbc:postgresql://100.121.74.115:5432/postgres?ApplicationName=mall-agent-rag&connectTimeout=3&socketTimeout=10
      username: root
      password: ${PGVECTOR_PASSWORD:root}
      maximum-pool-size: 8
      minimum-idle: 2
      connection-timeout: 3000
      validation-timeout: 2000
    embedding-dim: 1536
    index:
      hnsw-m: 16
      hnsw-ef-construction: 64
      search-ef: 100
  embedding:
    provider: MOCK                 # MOCK | OPENAI_COMPATIBLE
    model: text-embedding-3-small
    dim: 1536
    batch-size: 32
    timeout-ms: 8000
  retrieval:
    default-mode: HYBRID           # KEYWORD | VECTOR | HYBRID（异常时自动 DEGRADED）
    fallback-enabled: true
    child-top-k: 20
    parent-top-n: 5
    rrf-k: 60
    max-context-chars: 6000
```

Druid / Hikari 与 MyBatis-Plus 的注意点：显式声明第二个 `DataSource`（向量库）后，Spring Boot 数据源自动配置会退让，必须显式声明 `@Primary` 的 MySQL 数据源，配置类见设计文档第 3.4 节。

---

## 5. 校验清单

| # | 校验项 | 命令 / 语句 | 期望 |
|---|---|---|---|
| 1 | 扩展可用 | `SELECT extversion FROM pg_extension WHERE extname='vector';` | `0.5.0` |
| 2 | 距离算子 | `SELECT '[1,2,3]'::vector <=> '[1,2,4]'::vector;` | 约 `0.00853` |
| 3 | 向量表存在 | `\d rag_chunk_embedding` | 含 `embedding vector(1536)` |
| 4 | HNSW 索引 | `SELECT indexdef FROM pg_indexes WHERE indexname='idx_rag_emb_hnsw';` | 含 `USING hnsw (embedding vector_cosine_ops)` |
| 5 | MySQL 表 | `SHOW TABLES LIKE 't_knowledge%';` | 4 张表 |
| 6 | 子片有父片 | `SELECT COUNT(*) FROM t_knowledge_chunk WHERE chunk_level=2 AND parent_chunk_id IS NULL;` | `0` |
| 7 | 父子同版本 | 见 `stage3_rag_schema.sql` 第 6 节第 4 条校验语句 | `0` |
| 8 | 向量数一致 | MySQL `COUNT(child, embedding_status='READY')` = PG `COUNT(*)` | 相等 |
| 9 | 降级演练 | 停 pgvector 后调用检索接口 | 返回正常，`retrieval_mode=DEGRADED` |

---

## 6. 注意事项

1. **权限**：`CREATE EXTENSION` 需超级用户，`root` 满足；生产建议使用最小权限账号，仅保留在建库阶段使用 root。
2. **安全**：`root/root` 为弱口令，生产必须改密，并将 5432 / 3306 限制在内网网段。
3. **ngram 全文索引**：`WITH PARSER ngram` 需要 MySQL 8.0 且 `ngram_token_size=2`。若不可用则跳过，关键词检索自动退化为 `keywords_json + LIKE` 加权，无需改表。
4. **维度一致性**：`rag.embedding.dim`、`rag.pgvector.embedding-dim`、`rag_chunk_embedding.embedding` 列维度三者必须相同，否则写入直接报 `expected 1536 dimensions`。
5. **不使用分布式事务**：MySQL 为权威源，pgvector 派生；不一致由对账任务修复（设计文档 7.7 节）。
6. **向量不落 MySQL**：阶段三原设计中的 `embedding_json` 字段在本方案中不使用，避免大 JSON 拖慢主库。
7. **数据保留**：检索日志 90 天、向量化任务 30 天、文档历史版本长期保留（清理策略见设计文档 7.8 节）。
