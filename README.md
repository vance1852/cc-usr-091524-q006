# 工业设备巡检与维保工单管理平台（纯后端）

工业设备台账、巡检与维保工单管理的纯后端 API 服务。

## 技术栈

- Java 17 + Spring Boot 3 + Spring Web
- Spring Data JPA + MySQL 8（字符集 utf8mb4）
- JWT 鉴权（jjwt，自定义过滤器）、PBKDF2 密码哈希（JDK 自带）

## 启动（Docker）

```bash
docker compose up --build
```

MySQL 就绪后，应用通过 JPA 自动建表（ddl-auto=update）并在启动时灌入种子数据，服务监听 `http://127.0.0.1:7654`。

## 内置账号

唯一管理员（本平台只有 admin 一个角色）：

- 用户名：`admin`
- 密码：`admin123`

## 已实现的基础功能

- 登录签发 JWT、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 巡检异常证据附件：图片/音频/文档分段上传、完成确认、绑定与范围下载（`/api/attachments`、`/api/inspection/abnormalities`）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 受控附件（巡检异常证据）

现场上报异常（如电机异响）时，可先上传录音/铭牌照片/文档，再绑定到异常；
异常转工单时自动保留附件引用（只记指针、不复制文件），工单与异常闭环记录都能引用同一份证据。

- 文件内容落在可配置本地目录（`app.attachment.dir`，默认 `./data/attachments`），
  MySQL 只保存元数据、SHA-256、大小、媒体类型、上传者与业务引用
- 上传流程：`POST /api/attachments/uploads` 发起会话（声明大小/SHA-256/媒体类型，校验白名单与大小上限）
  → `PUT /api/attachments/uploads/{id}/chunks/{index}` 分片上传（可乱序、可重复，支持断点续传，
  `GET /api/attachments/uploads/{id}` 查询已上传分片）
  → `POST /api/attachments/uploads/{id}/complete` 完成确认（核验 SHA-256 与声明大小，重复完成幂等）
- 绑定：`POST /api/inspection/abnormalities/{id}/attachments`（绑定前重算校验和/大小/类型，全部匹配才允许；
  并发绑定幂等）；也可在异常上报时随单绑定（`attachmentIds` 字段）
- 查看：`GET /api/inspection/abnormalities/{id}/attachments`、`GET /api/work-orders/{id}/attachments`
  返回每份证据的完整性状态（ok/missing/size_mismatch）与来源（上传者、引用它的异常与工单）
- 下载：`GET /api/attachments/{id}/download`，JWT 鉴权、防路径穿越、支持 `Range` 范围读取；
  数据库记录在但文件丢失时返回 410 降级响应
- 删除：`DELETE /api/attachments/{id}` 仅允许删除无引用附件（同事务内行锁+引用计数检查，
  提交后才删物理文件）；仍被异常/工单引用的证据返回 409 禁止删除
- 清理：失败/过期的临时分片由定时任务回收，也可 `POST /api/attachments/uploads/cleanup` 手动触发

相关配置（均有环境变量覆盖）：`app.attachment.dir`（`ATTACHMENT_DIR`）、
`max-size-bytes`、`chunk-size-bytes`、`session-expire-hours`、`cleanup-interval-ms`、
`allowed-media-types`（`ATTACHMENT_ALLOWED_MEDIA_TYPES`）。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
