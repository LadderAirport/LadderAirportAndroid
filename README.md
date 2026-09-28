# LadderAirport Android

Android 上的 LadderAirport 节点。手机、平板或电视盒子主动连到 Panel，作为 uplink 节点加入机群。不需要公网 IP，也不监听入站端口。

需要 Panel **v0.15.7** 或更新版本。注册走 `POST /api/v1/agent/enroll`，只交换长期控制令牌，不申请管理面证书。

## 仓库关系（git submodule）

主仓通过 **Git submodule** 挂在本仓根目录 `LadderAirport/`（GitHub 上该目录显示为指向 `LadderAirport/LadderAirport` 的子模块，与 OpenWrt 仓库架构保持一致）：

```text
LadderAirportAndroid/
  .gitmodules              # LadderAirport → https://github.com/LadderAirport/LadderAirport.git
  LadderAirport/           # submodule（再含 agent/sing-box、agent/frp）
  core/                    # gomobile 绑定（go.mod replace 指向 ../LadderAirport）
  app/                     # Android Kotlin 应用
```

克隆本仓库：

```bash
git clone --recurse-submodules https://github.com/LadderAirport/LadderAirportAndroid.git
# 或已 clone 后同步子模块：
cd LadderAirportAndroid && make sync-submodule
# 构建只需主仓 + frp/sing-box；make sync-submodule 与 CI 不会拉 sing-box 的 android/apple 完整客户端子模块
```

`make check-src` 校验 submodule 已就绪。`core/go.mod` 中的 `replace` 直接通过相对路径 `../LadderAirport` 指向子模块，无需硬编码本机绝对路径。

## 运行方式

- 控制面是 Agent 主动建立的 WebSocket 长连接，能力与 Panel 拨号 gRPC 对齐。连接断开时回退到 HTTP 上报和拉配置。
- 数据面在进程里跑 sing-box，并通过 FRP 把入站流量从公网 FRPS 转到这台设备。
- 前台服务保持进程，支持开机自启。
- 安装包支持 `arm64-v8a` 及 `x86_64`。

在 Panel 里把节点建成 **uplink**。添加成功后点「扫码配对」，用本应用的相机扫描二维码，填入 Panel 地址、节点 ID 和控制令牌，再点「一键注册」。

## 本地构建

`app/libs/ladderagent.aar` 由本机 `make aar` 生成，不提交。打 APK 前需要 Android SDK（compileSdk 35）、JDK 17、Go 1.26+、`gomobile` 和 Android NDK r28。

```bash
# 校验子模块就绪
make check-src

# 编译 Go AAR
make aar

# 编译 Debug APK（按 ABI 分包）
make assemble
# app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
# app/build/outputs/apk/debug/app-x86_64-debug.apk

# 编译 Release APK（按 ABI 分包；配置 ANDROID_KEYSTORE_* 后会签名）
make release
# app/build/outputs/apk/release/app-arm64-v8a-release.apk
# app/build/outputs/apk/release/app-x86_64-release.apk
```

`make test` 跑 Android 单元测试。

## CI/CD（GitHub Actions）

Workflow：`.github/workflows/android-build.yml`

| 触发 | 行为 |
|------|------|
| `push` / `pull_request` → `main` | 初始化 submodule → gomobile AAR（arm64 + amd64）→ 单测 → 分别上传 `arm64-v8a` / `x86_64` debug APK |
| 推送 tag `v*`（如 `v1.0.0`） | 同上，并打 **signed release APK**，创建 GitHub Release 挂上两个架构产物 |
| `workflow_dispatch` | 手动跑一轮 CI |

发布签名（可选，仓库 Secrets）：

- `ANDROID_KEYSTORE_BASE64` — keystore 文件的 base64
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

未配置 Secrets 时，tag 发布会用一次性 CI keystore 签名（可安装，但与正式包签名不同）。

## 目录

```text
.gitmodules           Git 子模块配置
LadderAirport/        主仓库 submodule (agent / pkg / proto)
app/                  Kotlin 界面与前台服务
core/mobile/          gomobile 绑定：注册、uplink HTTP、WebSocket
```
