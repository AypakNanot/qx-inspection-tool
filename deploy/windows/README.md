# QX 设备直连巡检工具 — Windows 部署说明

## 1. 环境要求

| 项 | 要求 |
|---|---|
| 操作系统 | Windows（Server 2016+ / Win10+） |
| JDK | 17 及以上，`java -version` 可验证（无需 `--enable-preview`） |
| 端口（入站） | 38543 —— Web 访问，冲突时改 `config\application.yml` 的 `server.port` |
| 网络（出站） | TCP 9900 —— 连接 QX 设备；防火墙需放行 |
| MySQL 老网管库 | **可选**。应用可正常启动，未接通时仅设备发现/数据同步不可用 |

## 2. 部署步骤

1. 将整个交付目录拷贝到目标机，例如 `C:\opt\mstp-inspect\`
2. 编辑 `config\application.yml`：
   - `app.mysql.password`（或用环境变量 `APP_MYSQL_PASSWORD` 注入，优先级更高）
   - `server.port`（如与本机已占用端口冲突）
3. 双击 `start.bat` 启动（独立窗口运行，可在窗口里看实时日志）
4. 浏览器访问 `http://<目标机IP>:38543/`
5. 停止：双击 `stop.bat`（按 jar 名匹配 java 进程，不会误杀其它 Java 程序）

## 3. 目录结构

```
mstp-inspect/
├── mstp-inspect-*.jar   # 应用本体（含前端页面）
├── start.bat                  # 启动
├── stop.bat / stop.ps1        # 停止
├── config/application.yml     # 目标机外置配置（覆盖 jar 内默认值）
├── data/qx_inspection.db      # SQLite 巡检库，首次启动自动创建
└── logs/                      # 日志（应用控制台日志由启动窗口输出）
```

## 4. 关键说明

- **不要移动 jar 后单独运行**：SQLite 路径是相对路径 `./data/`，必须在目录内启动
  （`start.bat` 已固定工作目录，直接用它即可）。
- **外置配置生效机制**：Spring Boot 自动读取 `./config/application.yml` 并覆盖 jar 内同名配置，
  改配置后重启生效，不需要重新打包。
- **老库连不上不影响启动**：MySQL 是按需连接（同步/设备发现时才建连），
  可用界面「数据库连通测试」验证。
- **数据是单实例的**：同一时刻只跑一个实例（SQLite 单写约束）。

## 5. 注册为 Windows 服务（生产推荐）

用 [NSSM](https://nssm.cc/) 后台常驻 + 开机自启 + 崩溃自动拉起：

```bat
nssm install QxInspection "C:\Program Files\Java\jdk-17\bin\java.exe" ^
    -jar C:\opt\mstp-inspect\mstp-inspect-1.0.0.jar
nssm set QxInspection AppDirectory C:\opt\mstp-inspect
nssm set QxInspection AppStdout   C:\opt\mstp-inspect\logs\stdout.log
nssm set QxInspection AppStderr   C:\opt\mstp-inspect\logs\stderr.log
nssm set QxInspection Start SERVICE_AUTO_START
nssm start QxInspection
```

注意：`AppDirectory` 必须是交付目录（等价于 `start.bat` 里的 `cd`），
否则会另建一个空的 `data\qx_inspection.db`。

服务方式下请**不要**再双击 `start.bat`，避免双实例（可用 `stop.bat` 先停服务进程）。

## 6. 常见问题

| 现象 | 处理 |
|---|---|
| 启动后设备清单为空/「清单加载失败」 | 老库未通：核对 `config\application.yml` 的 MySQL 账号，界面点「数据库连通测试」 |
| 页面打不开 | `netstat -ano | findstr :38543` 看端口；context-path 是 `/`（根路径，直接访问端口即可） |
| 发现多出一个空库、历史巡检记录不见了 | 工作目录不对（从别处直接 `java -jar`）——改用 `start.bat` 或设好服务的 `AppDirectory` |
| 改了配置不生效 | 配置放在 `config\application.yml`，改完需重启 |
| 端口 9900 连不上设备 | 出站防火墙/网段策略，工具只做出站 TCP 9900，不监听 UDP 9910 |
