# Open API 调用文档

## 概述

开放 API 供外部系统调用，输入简历信息、岗位方向、级别，异步返回简历分析评分和面试题。

**认证方式**：所有请求需在 Header 中携带 `X-API-Key`。

**基础地址**：`http://localhost:8080`

**核心概念**：
- **岗位方向（skillId）**：决定面试的技术领域，如 Java 后端、前端、算法等
- **招聘需求（jdText）**：可选，用于定制化面试题，使题目更贴合具体岗位要求
- 两者是**结合关系**：skillId 确定大方向，jdText 在此基础上定制化

---

## 接口列表

### 1. 提交分析任务

```
POST /api/openapi/analyze
```

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| skillId | string | 是 | 岗位方向 ID，如 `java-backend`、`frontend`、`python-backend` |
| jdText | string | 否 | 招聘需求（JD）文本，用于定制化面试题，与 skillId 结合使用 |
| resumeText | string | 否 | 简历文本（与 resumeFile 二选一） |
| resumeFile | file | 否 | 简历文件，支持 PDF/DOC/DOCX/TXT，单个不超过 20MB（与 resumeText 二选一） |
| difficulty | string | 否 | 难度：`junior` / `mid` / `senior`，默认 `mid` |
| questionCount | int | 否 | 面试题数量，3-20，默认 10 |
| llmProvider | string | 否 | LLM 提供商，不填使用系统默认 |
| style | string | 否 | 面试风格：`standard`（标准初面）/ `deep_dive`（深挖验证）/ `scenario`（业务场景模拟）/ `quick`（快筛速面），默认 standard |
| focusTags | string | 否 | 考察重点标签，逗号分隔（技术深度/稳定性/求职动机/量化成果验证/JD 匹配盲区/沟通表达/合规与风险意识/团队协作），最多 4 个 |
| extraInstructions | string | 否 | 补充出题要求（≤500 字符） |
| previousQuestions | string | 否 | 历史已出题目（换行分隔，≤30 条），用于避免重复出题 |
| mode | string | 否 | 任务模式：`both`（评分+出题，默认）/ `questions`（仅出题）/ `analysis`（仅评分） |

#### 请求示例

**基础调用（仅岗位方向 + 简历）**：

```bash
curl -X POST http://localhost:8080/api/openapi/analyze \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "skillId": "java-backend",
    "resumeText": "张三，3年Java开发经验，熟悉Spring Boot、MyBatis...",
    "difficulty": "mid",
    "questionCount": 10
  }'
```

**带 JD 定制化（岗位方向 + JD + 简历）**：

```bash
curl -X POST http://localhost:8080/api/openapi/analyze \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "skillId": "java-backend",
    "jdText": "负责公司核心系统的后端开发，要求熟悉 Java、Spring Boot、MySQL、Redis...",
    "resumeText": "张三，3年Java开发经验，熟悉Spring Boot、MyBatis...",
    "difficulty": "mid",
    "questionCount": 10
  }'
```

**文件上传方式**：

```bash
curl -X POST http://localhost:8080/api/openapi/analyze \
  -H "X-API-Key: your-api-key" \
  -F "skillId=java-backend" \
  -F "difficulty=mid" \
  -F "questionCount=10" \
  -F "resumeFile=@/path/to/resume.pdf"
```

#### 响应示例

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "a1b2c3d4e5f67890"
  }
}
```

---

### 2. 查询任务结果

```
GET /api/openapi/tasks/{taskId}
```

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| taskId | string | 提交任务时返回的任务 ID |

#### 请求示例

```bash
curl http://localhost:8080/api/openapi/tasks/a1b2c3d4e5f67890 \
  -H "X-API-Key: your-api-key"
```

#### 响应示例

**处理中**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "a1b2c3d4e5f67890",
    "status": "PROCESSING",
    "error": null,
    "resumeAnalysis": null,
    "questions": null
  }
}
```

**已完成**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "a1b2c3d4e5f67890",
    "status": "COMPLETED",
    "error": null,
    "resumeAnalysis": {
      "overallScore": 78,
      "scoreDetail": {
        "contentScore": 12,
        "structureScore": 13,
        "skillMatchScore": 16,
        "expressionScore": 8,
        "projectScore": 29,
        "jdAlignmentScore": 0
      },
      "summary": "3年Java开发经验，技术栈匹配度较高，项目描述需加强量化指标...",
      "strengths": [
        "技术栈与岗位要求高度匹配",
        "有实际项目经验"
      ],
      "suggestions": [
        {
          "category": "项目经验",
          "priority": "高",
          "issue": "项目描述缺乏量化指标",
          "recommendation": "建议补充性能优化数据，如QPS提升、响应时间降低等"
        }
      ],
      "originalText": null
    },
    "questions": [
      {
        "questionIndex": 0,
        "question": "请介绍一下你项目中使用的Redis缓存策略，遇到过缓存穿透问题吗？",
        "type": "REDIS",
        "category": "Redis",
        "topicSummary": "Redis缓存策略",
        "userAnswer": null,
        "score": null,
        "feedback": null,
        "isFollowUp": false,
        "parentQuestionIndex": null
      },
      {
        "questionIndex": 1,
        "question": "你提到的缓存穿透，除了布隆过滤器还有其他解决方案吗？",
        "type": "REDIS",
        "category": "Redis（追问1）",
        "topicSummary": "缓存穿透解决方案",
        "userAnswer": null,
        "score": null,
        "feedback": null,
        "isFollowUp": true,
        "parentQuestionIndex": 0
      }
    ]
  }
}
```

**失败**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": "a1b2c3d4e5f67890",
    "status": "FAILED",
    "error": "AI服务响应超时",
    "resumeAnalysis": null,
    "questions": null
  }
}
```

---

## 状态说明

| status | 说明 |
|--------|------|
| PENDING | 任务已创建，等待处理 |
| PROCESSING | 正在处理（简历分析 + 面试题生成） |
| COMPLETED | 处理完成，可获取结果 |
| FAILED | 处理失败，查看 error 字段 |

**轮询建议**：提交后每 3-5 秒轮询一次，任务通常在 10-30 秒内完成。

---

## 岗位 ID（skillId）

| skillId | 岗位 |
|---------|------|
| java-backend | Java 后端开发 |
| frontend | 前端开发 |
| python-backend | Python 后端开发 |
| algorithm | 算法工程师 |
| system-design | 系统设计 |
| test-development | 测试开发 |
| ai-agent-dev | AI Agent 开发 |
| ali-backend | 阿里系后端 |
| bytedance-backend | 字节系后端 |
| java-backend-tencent | 腾讯系后端 |

---

## 评分口径

`resumeAnalysis.overallScore` 满分 **100**，等于五个维度之和：项目经验与技术深度 40 / 技能匹配 20 / 内容完整 15 / 结构清晰 15 / 表达专业 10。
`scoreDetail.jdAlignmentScore`（0-20）为 JD 对齐参考分，**不计入总分**，未提供 jdText 时为 0。

## 错误码

| code | 说明 |
|------|------|
| 200 | 成功 |
| 401 | API Key 无效或未提供 |
| 12001 | 任务不存在或已过期 |
| 12002 | 请求参数无效 |
| 12003 | 文件解析失败 |
| 12004 | 必须提供简历文本或简历文件 |
| 12005 | API Key 无效或未提供（HTTP 401） |
| 8001 | 请求过于频繁 |

---

## 限流策略

| 接口 | 全局 | IP | API Key |
|------|------|-----|---------|
| POST /analyze | 2 次/秒 | 5 次/分钟 | 30 次/分钟 |
| GET /tasks/{taskId} | 20 次/秒 | - | - |

---

## 配置说明

环境变量配置：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| OPENAPI_KEY | API Key（**必填**，未配置时所有开放接口拒绝访问） | 无，必须显式配置 |
