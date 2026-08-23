# QX设备直连巡检工具

## 项目简介

独立工具直连QX设备进行光功率巡检，使用独立用户避免与老网管冲突。

## 技术栈

- **Java**: JDK 21
- **Spring Boot**: 3.2.0
- **数据库**: MySQL（老库只读） + SQLite（本地缓存）
- **构建工具**: Maven

## 项目结构

```
qx-inspection-tool/
├── src/main/java/com/optel/qxinspection/
│   ├── QxInspectionToolApplication.java    # 启动类
│   ├── config/                             # 配置类
│   │   ├── MySQLDataSourceConfig.java      # MySQL数据源配置
│   │   └── SQLiteDataSourceConfig.java     # SQLite数据源配置
│   ├── entity/                             # 实体类
│   │   ├── mysql/                          # MySQL实体（老库只读）
│   │   │   ├── DmNe.java                   # 网元设备表
│   │   │   └── EmNeComm.java               # 网元通信配置表
│   │   └── sqlite/                         # SQLite实体（本地缓存）
│   │       ├── DeviceAccessConfig.java     # 设备接入配置
│   │       └── OpticalPowerInspection.java # 光功率巡检记录
│   ├── repository/                         # 数据访问层
│   │   ├── mysql/                          # MySQL Repository
│   │   └── sqlite/                         # SQLite Repository
│   ├── service/                            # 业务服务层
│   │   └── DeviceAccessService.java        # 设备接入管理服务
│   └── controller/                         # 控制器层
│       └── DatabaseTestController.java     # 数据库测试控制器
├── src/main/resources/
│   └── application.yml                     # 应用配置文件
└── pom.xml                                 # Maven项目配置
```

## 数据库配置

### MySQL（老库只读）

```yaml
spring:
  datasource:
    mysql:
      jdbc-url: jdbc:mysql://127.0.0.1:3306/Uniview
      username: sa
      password: 11111111
```

### SQLite（本地缓存）

```yaml
spring:
  datasource:
    sqlite:
      jdbc-url: jdbc:sqlite:./data/qx_inspection.db
```

## 快速开始

### 1. 编译项目

```bash
mvn clean install
```

### 2. 运行项目

```bash
mvn spring-boot:run
```

或者运行打包后的jar文件：

```bash
java --enable-preview -jar target/qx-inspection-tool-1.0.0-SNAPSHOT.jar
```

### 3. 测试数据库连接

访问以下接口测试数据库连接：

- 测试MySQL连接: `GET http://localhost:8080/qx-inspection/api/database/test-mysql`
- 测试SQLite连接: `GET http://localhost:8080/qx-inspection/api/database/test-sqlite`
- 测试所有数据库: `GET http://localhost:8080/qx-inspection/api/database/test-all`

### 4. 同步设备信息

从MySQL老库同步设备信息到SQLite本地库：

```bash
POST http://localhost:8080/qx-inspection/api/database/sync-devices
```

### 5. 查询设备列表

```bash
GET http://localhost:8080/qx-inspection/api/database/devices
GET http://localhost:8080/qx-inspection/api/database/devices/enabled
```

## 核心功能

### 1. 设备接入管理

- 从MySQL老库读取设备清单（DmNe、EmNeComm表）
- 同步设备信息到SQLite本地库
- 管理设备连接配置（IP、端口、用户名、密码）

### 2. 设备类型统计

- 按设备类型统计数量
- 按厂商统计数量
- 查询在线设备列表

### 3. 光功率巡检任务

- 定时巡检光功率数据
- 记录巡检结果到SQLite
- 门限判定和越限告警

## 注意事项

1. **MySQL老库只读**: 应用对MySQL数据库只有读取权限，不会修改老库数据
2. **SQLite本地缓存**: 所有巡检结果和配置变更都存储在本地SQLite数据库
3. **独立用户**: 使用独立用户连接设备，避免与老网管冲突
4. **同机部署**: 不启用UDP 9910端口，避免端口冲突

## 参考资源

- **tmaster2000（Qx协议核心库）**: `D:\ai-workspace\1240615\tmaster2000`
- **mtp（本项目工作空间）**: `D:\ai-workspace\mtp`

## 作者

Rwj

## 版本

1.0.0-SNAPSHOT
