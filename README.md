# 圣旮旯 SMP 插件和模组

这些项目可分别构建和安装。MasterGAL 由 [MCQQBridge](https://github.com/tomh500/MCQQBridge) 维护。LuoGameBalance 和 NeteasePets 已有各自的公开仓库。

| 项目 | 用途 | 服务端编译依赖 |
| --- | --- | --- |
| [GalVoice](GalVoice/README.md) | Fabric 客户端和 Spigot 服务端的局内语音 | Spigot 1.12.2、SpigotMasterGAL、BedWars1058 21.11.2 |
| [LuoBackup](LuoBackup/README.md) | BungeeCord 定时备份共享文件系统中的子服 | BungeeCord、Spigot 1.12.2 |
| [BwLegacyCombat](BwLegacyCombat/) | BedWars 子服的旧版近战机制 | Spigot 1.12.2 |
| [ShengGalaMetadata](ShengGalaMetadata/) | 自定义 BungeeCord 服列表、客户端品牌和版本命令权限 | BungeeCord |
| [Minigames](Minigames/) | TNT Run、密室杀手及其房间和每日轮换 | Spigot 1.12.2、SpigotMasterGAL、Multiverse-Core 2.5.0 |

## 构建和安装服务端插件

在 PowerShell 7 中使用 JDK 25。项目根目录运行以下命令，将路径替换为实际编译依赖：

```powershell
./build-plugin.ps1 -Project Minigames -DependencyJars @(
    '/path/to/spigot-1.12.2.jar',
    '/path/to/SpigotMasterGAL.jar',
    '/path/to/Multiverse-Core-2.5.0.jar'
)
```

输出在 `build/`。把 BungeeCord 插件安装到代理的 `plugins/`，把其余服务端插件安装到对应 Spigot 子服的 `plugins/`。ShengGalaMetadata 依赖 Java 17 或更新版本，品牌文字定义在源码的 `BRAND` 常量中。安装 Minigames 后，先配置房间、主大厅名称和回主大厅的路由，再对玩家开放。
