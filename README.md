图像编辑（ImgEditor）

一个专为字库制作场景设计的 Android 图像编辑 App。用于处理从古籍或字帖中切分出来的单字图，支持擦除干扰笔画、补画被切掉的边缘，并逐张保存为无损 PNG。

功能特性

· 批量处理：选择输入文件夹后自动列出所有图片，按文件名排序逐张处理。
· 擦除 / 补画：默认擦除为白色画笔、补画为黑色画笔，可手动切换颜色与笔刷粗细。
· 无损编辑：加载与保存全程保持原分辨率、ARGB_8888 像素格式，PNG 输出保留透明通道。
· 触摸放大镜：手指触摸时在左上方显示 3 倍放大镜，半透明圆点标记跟随笔刷粗细，精确对准笔画边缘。
· 自动保存：修改后点击「上一张 / 下一张」自动保存到输出文件夹；点击「跳过」或未修改时不保存。
· 重置当前图：一键丢弃当前图所有未保存修改，恢复到原始状态。
· 自适应图标：蓝色背景 + 白色画笔图标，替换默认机器人图标。

界面说明

顶部工具栏

按钮 功能
输入 选择包含待处理图片的文件夹
输出 选择保存处理后图片的文件夹
擦除 切换到擦除模式，默认画笔为白色
补画 切换到补画模式，默认画笔为黑色
重置 丢弃当前图的所有未保存修改，重新加载原图

下方色板可手动选择画笔颜色，滑杆调整笔刷粗细（2–80 dp）。

画布

· 图像居中显示，四周留白约 9%（可通过 IMAGE_FILL_FACTOR 调整）。
· 手指拖动即可绘制，触摸位置显示半透明圆点和左上方放大镜。
· 放大镜内显示 3 倍放大图像（可通过 MAGNIFY_FACTOR 调整），像素不插值，方便对齐。

底部按钮

按钮 行为
上一张 若有修改则先保存当前图，再切换到上一张
跳过 不保存当前图，直接切换到下一张
下一张 若有修改则先保存当前图，再切换到下一张

状态栏

显示当前图片序号、文件名、尺寸，以及是否已修改。

构建与安装

通过 GitHub Actions 自动构建

项目已配置 .github/workflows/build.yml：

1. 将项目推送到 GitHub 的 main 或 master 分支。
2. 进入仓库的 Actions 页面，等待 Build APK 工作流执行完成。
3. 在 workflow 运行页面的 Artifacts 区域下载 ImgEditor-debug-apk。
4. 解压得到 app-debug.apk，在手机上安装即可。

本地构建（需 Android SDK）

```bash
./gradlew assembleDebug
```

生成的 APK 位于 app/build/outputs/apk/debug/app-debug.apk。

项目结构

```
.
├── .github/workflows/build.yml          # GitHub Actions 构建配置
├── settings.gradle.kts                  # Gradle 项目设置
├── build.gradle.kts                     # 顶层构建脚本
├── gradle.properties                    # Gradle 配置
├── .gitignore
└── app
    ├── build.gradle.kts                 # 模块构建脚本
    ├── proguard-rules.pro
    └── src/main
        ├── AndroidManifest.xml
        ├── java/com/example/imgeditor
        │   └── MainActivity.kt          # 主界面与全部编辑逻辑
        └── res
            ├── values/strings.xml
            ├── values/themes.xml
            ├── drawable/ic_launcher_background.xml
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml
            ├── mipmap-anydpi-v26/ic_launcher_round.xml
            ├── mipmap/ic_launcher.xml
            └── mipmap/ic_launcher_round.xml
```

技术要点

无损加载与保存

· 加载图片时关闭降采样（inSampleSize = 1、inScaled = false），保持原图分辨率。
· 强制 ARGB_8888 配置，保留 alpha 通道。
· 保存时使用 Bitmap.compress(PNG, 100, ...)，PNG 是无损格式，像素零丢失。
· 输出文件名为原图名去掉扩展名后加 .png，同名文件会被覆盖。

保存规则

· 修改后翻页（上一张 / 下一张）→ 保存当前图。
· 点击跳过 → 不保存，直接丢弃修改。
· 未修改时翻页 → 不保存。
· 重置 → 不保存，仅恢复原图。

坐标映射

· 图像按 IMAGE_FILL_FACTOR 缩放后居中显示。
· 触摸坐标通过 displayScale 和图像原点映射到原图坐标。
· 笔刷宽度同步除以 displayScale，保证屏幕上笔迹粗细与滑杆一致。

可调参数

在 MainActivity.kt 顶部可调整以下常量：

常量 说明 默认值
IMAGE_FILL_FACTOR 图像占画布短边的比例，越大图像越大、留白越小 0.82f
MAGNIFIER_SIZE 放大镜直径 140.dp
MAGNIFY_FACTOR 放大镜放大倍数 3f
MAGNIFIER_GAP 放大镜与触摸点的间距 70.dp

使用流程建议

1. 将待处理的单字图放入手机上的一个文件夹（如 Pictures/字库源图）。
2. 打开 App，点「输入」选择该文件夹。
3. 点「输出」选择保存处理后图片的文件夹。
4. 逐张处理：擦除多余干扰、补画被切掉的边缘。
5. 修改后点「下一张」或「上一张」自动保存；不需要处理的点「跳过」。
6. 处理出错时点「重置」恢复当前图原始状态。

许可证

本项目未附带特定许可证，可自由使用与修改。

```

**建议：** 如果你的字库源图不是白底黑字，擦除用白色可能会留下白斑，此时建议把擦除色改为与背景一致，或改用透明擦除。需要的话可以再补一版。
