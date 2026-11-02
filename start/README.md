# start 启动脚本集

| 脚本 | 作用 |
|---|---|
| `start-all.bat` | 一键全启：基础设施 → 后端(:8080 新窗口) → 前端(:5173 新窗口) → 自动开浏览器 |
| `run-infra.bat` | 仅起 mysql/redis/pgvector 容器（Docker 未开会先拉起并等待就绪） |
| `run-backend.bat` | 仅起后端（jar 不存在会自动构建；窗口保留实时日志） |
| `run-frontend.bat` | 仅起前端 dev（首次自动 npm install；/api 代理到 8080） |
| `build-backend.bat` | 打包后端 jar；带参数 `tests` 则同时跑单元测试 |
| `stop-all.bat` | 停止本项目前后端进程（按命令行特征过滤，不误伤其它 java/node） |
| `stop-infra.bat` | 停止三个基础设施容器（数据 volume 保留） |

## 前置
- `backend\.env` 已配置（见根目录 README「快速开始」），否则后端会拒绝启动并提示。
- 端口约定：基础设施 3307/6380/5433（避开本机已占用的 3306/6379），后端 8080，前端 5173。

## 编码提醒
所有 .bat 以 **GBK(cp936)+CRLF** 保存——cmd 按系统 OEM 码页逐行解析源文件，
若被另存为 UTF-8，中文注释会错位成"xx 不是内部或外部命令"连环报错。请勿用 UTF-8 编辑器改写。
