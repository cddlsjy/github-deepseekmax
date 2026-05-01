---
AIGC:
    ContentProducer: Minimax Agent AI
    ContentPropagator: Minimax Agent AI
    Label: AIGC
    ProduceID: "00000000000000000000000000000000"
    PropagateID: "00000000000000000000000000000000"
    ReservedCode1: 3044022033cae5e8bac99df4ffb078b651d2284caba4802ecb877e92cc3aa4829e1332aa02201b387e9743557b3b39119a5d09b8d11609c5fbd7147acf59614091c7ac1b02c7
    ReservedCode2: 3044022029bd1df25e03b2230659df55edd48760cd44136d83086d35b45f512572997dd702203ca427635156146ab4dc8be46d62836b03900a2824a239ea4d3457f0c3cee93c
---

# GitHub Explorer

一个基于 Jetpack Compose 的 Android GitHub 浏览器应用，适用于平板pad。

## 功能特性

- **仓库浏览**: 搜索和浏览 GitHub 仓库
- **文件树**: 查看仓库目录结构
- **文件预览**: 查看仓库中的文件内容
- **Actions 管理**: 查看和管理 GitHub Actions 工作流
- **工作流运行**: 触发和查看工作流运行状态
- **日志查看**: 查看 Actions 运行日志
- **构建产物下载**: 下载工作流产生的构建产物
- **字体缩放**: 支持自定义字体大小

## 技术栈

- **语言**: Kotlin
- **UI框架**: Jetpack Compose
- **架构**: MVVM
- **网络**: Retrofit + OkHttp
- **配置存储**: DataStore Preferences
- **异步**: Kotlin Coroutines + Flow

## 项目结构

```
app/src/main/java/com/example/githubexplorer/
├── GitHubExplorerApp.kt      # Application类
├── Constants.kt              # 常量定义
├── ConfigManager.kt          # 配置管理
├── MainActivity.kt          # 主活动
├── data/
│   └── github/
│       ├── GitHubApi.kt     # Retrofit API接口
│       └── GitHubRepository.kt  # 数据仓库
└── ui/
    ├── login/
    │   ├── LoginScreen.kt   # 登录界面
    │   └── LoginViewModel.kt
    ├── main/
    │   ├── MainScreen.kt   # 主界面
    │   └── MainViewModel.kt
    └── theme/
        └── Theme.kt         # 主题定义
```

## 构建

### 前置要求

- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 34

### 构建步骤

1. 克隆项目
2. 在 Android Studio 中打开项目
3. 等待 Gradle 同步完成
4. 运行项目

或使用命令行:

```bash
./gradlew assembleDebug
```

## 使用

1. 首次启动需要输入 GitHub Personal Access Token
2. 登录后可以浏览自己的仓库或搜索其他仓库
3. 选择仓库后可以查看目录树和文件内容
4. 在 Actions 标签页可以管理 GitHub Actions

## GitHub Token 权限

应用需要以下 GitHub API 权限:
- `repo` - 访问私有仓库
- `workflow` - 管理 Actions 工作流
流
