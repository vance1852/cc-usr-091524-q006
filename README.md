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
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
