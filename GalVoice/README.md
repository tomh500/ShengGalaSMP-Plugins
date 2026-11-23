# GalVoice 局内语音

GalVoice 通过 SpigotMasterGAL 和 FabricMasterGAL 0.2.0 转发按键语音。语音随 Minecraft 连接经过现有 BungeeMasterGAL 路由，使用 GAL 事件 `galvoice:upload_v1` 和 `galvoice:audio_v1`。语音帧是 16 kHz 单声道 IMA ADPCM，每 40 ms 发送一帧。服务端按玩家的实时位置、竞技场和队伍选择接收者，姓名和频道由服务端填写。

## 客户端

将匹配 Minecraft **26.2** 或 **26.3** 的 GalVoice、FabricMasterGAL 和 Fabric API 一起放进 Fabric 客户端的 `mods`。使用 Java 25 和 Fabric Loader 0.19.5 或更新版本。

- 按住鼠标下侧键发言，默认绑定为 **鼠标键 4**，即 X1／常见的后退侧键。可在 Minecraft 控制设置里重绑。
- 按 **V** 或输入 `/galvoice` 打开语音设置，可选择麦克风和播放设备，并分别调节麦克风增益和播放音量。选择「完成」保存到客户端 `config/galvoice.properties`。
- 生存服可在同一菜单切换「附近」或「同世界」收听，默认「附近」。该选择只影响自己的收听范围，同世界模式不跨维度。
- 聊天栏下方显示发言者的头像、名字和频道。多人同时发言时按开始顺序并排显示，最多三张卡，`+N` 表示还有其他发言者。切服会清空已有的播放队列和发言提示。

## 服务端

将 GalVoice JAR 和已有的 SpigotMasterGAL 0.2.0 放到目标后端的 `plugins`，从代理加入时使用已有 BungeeMasterGAL 0.2.0。配置由各后端单独读取。改动后完整重启对应的后端，让 GAL 在连接时重新交换能力。

| 场景 | 默认语音范围 |
| --- | --- |
| 普通世界 | 同世界 48 格内可听见，8 格起线性衰减 |
| BedWars1058 等待或倒计时 | 同竞技场附近语音 |
| BedWars1058 对局中 | 同竞技场、同队，全距离可听见。等待复活的玩家暂时静音 |
| BedWars1058 永久淘汰和观战 | 同竞技场的观战者互通，与参赛语音隔离 |
| 每日小游戏 | 参赛玩家使用本局附近语音。淘汰和主动观战的玩家进入本局观战语音。等候区独立于对局 |

小游戏后端需使用带 `voiceRound(Player)` 的 Minigames 构建。普通世界可把世界名加入 `scoreboard-team-worlds`，让主计分板队伍使用独立队内语音。

在 `plugins/GalVoice/config.yml` 可调整 `proximity.enabled`、`range-blocks`、`fade-start-blocks`、`spectators.enabled`、`bedwars.waiting-proximity` 以及 `disabled-worlds`。把衰减起点设置成最大距离可以使用固定音量。权限 `galvoice.speak` 和 `galvoice.listen` 分别控制发言和收听，默认开放。

生存服配置 `listen-mode.allow-world: true` 后，接收者可选择同世界收听。队伍、对局及观战隔离继续由服务端执行。

## 构建和验证

按[仓库根目录文档](../README.md)的依赖表执行 `build-plugin.ps1 -Project GalVoice`。添加 `-Test` 可运行语音编解码、衰减及队伍和观战隔离测试。

Fabric 客户端使用 JDK 25 和 Gradle 9 构建，同时传入匹配目标版本的 FabricMasterGAL JAR：

```powershell
gradle -p GalVoice/fabric -PminecraftTarget=26.3 -PmasterGalJar=/path/to/FabricMasterGAL-0.3.0+26.3.jar build
```

构建 26.2 时，将目标和 MasterGAL JAR 都换为 26.2。客户端产物位于 `GalVoice/fabric/build/<版本>/libs/`。
