# WordNest 验证记录

当前发行 0.7.3：最新历史、托盘、设置布局检查见文末；以下保留此前实际验证结果，不将历史测试冒充本次重跑。

# WordNest 0.7.0 验证记录（2026-10-23）

本版扩充离线主词库，并增加输入格式整理、短语查询与基于显式关系的词形候选。以下记录本轮实际完成的检查；0.6.0 及更早版本的数字在后文单独保留，不能视为新版已重复执行的结果。

- 数据转换以固定 SHA-256 核对原有基础资源、官方 ECDICT 1.0.28 ZIP 和 SQLite。完整读回比对合并后 **3,403,176 条**记录；原有 **770,611 条**优先，新增 **2,632,565 条**，不以扩充记录覆盖同名原词条。
- 另行执行独立只读扫描，未调用构建脚本的验证函数：逐条核对 WND2 偏移、UTF-8 字段和 UTF-16 查询键排序；从基础 WND1 读取六字段序列化字节，与合并库中的对应记录逐条 SHA-256 比较，**770,611 条全部字节一致**。所有新增记录均有 `wn-expanded` 来源标签；未写入源数据或个人文件。
- 独立扫描重算字段覆盖：音标 **353,030**、中文释义 **3,386,635**、英文释义 **177,409**，与元数据一致。完整 WND2 和 3 个分片 SHA-256 均匹配；词形索引 **161,859 对**排序且无重复，覆盖 **161,398 个形式**，**141,977 个目标词头**全部实际存在。
- `ExpandedDictionaryTest`：**2,037 项通过**，实际使用 Eclipse Temurin 17.0.20.1、`-Xmx512m`、真实新版词库与独立测试缓存。覆盖精确条目数、原词条优先、新词条来源、规范化、词形回退、无命中、前缀边界和重复查询；加载原有 355 张学习卡，未创建窗口或读取个人数据。
- 上述单次运行测得首次新缓存加载 **3.537 秒**、复用缓存加载 **0.268 秒**；词库、解析器及学习资料加载后保留的 Java 堆增量约 **108.0 MiB**，2,000 次 lookup/resolve 合计 **0.028 秒**。这些是同一机器上的一次测量，不是速度承诺，也不是进程总 RSS 或内存映射文件大小。
- `DictionaryStorageTest`：**81 项通过**，覆盖缓存存储与失败保护，含 **2,000,003 条**模拟词条在 `-Xmx96m` 下读取；`CoreTest` **1,045 项通过**。模拟大库检查与真实 3,403,176 条集成检查分别记录，不相互替代。
- 查询与候选回归：`LookupResolverTest` **34 项**、`ClipboardWatcherTest` **46 项**、`LookupCandidatesPanelTest` **19 项**、`LookupResolverBundledTest` **11 项**通过。覆盖格式整理、120 字符与 8 词限制、精确优先、显式词形回退、歧义手选、无命中与候选保留；剪贴板测试使用注入数据，不读取真实剪贴板。
- 使用真实 WND2 重新运行：`FavoritesRecoveryTest` **73**、`LookupHistoryTest` **256**、`LookupSessionTest` **54**、`ExampleSelectionTest` **31**、`WordDetailPanelTest` **8,026**、`LearningPanelTest` **12,490**、`CuratedCoverageTest` **6,810**、`OpenLearningTest` **202,239**、`LearningRepositoryTest --full` **504** 项，全部通过。数据模型测试使用临时目录；需要主词库的控件和覆盖检查通过共享辅助类加载新版资源，缓存位于隔离测试目录。
- 复习与朗读回归：`ReviewStoreTest` **119**、`ReviewPanelTest` **57**、`SpeechServiceTest` **21**、`WindowsSpeechEngineTest` **18**、`SpeechControlsTest` **101**、`SpeechButtonsTest` **76** 项通过。朗读服务使用假引擎或假进程，控件仅记录回调文字，没有播放声音。
- 后台与主题回归：`BackgroundControllerTest` **121**、`ActivationRequestsTest` **40**、`TraySupportTest` **14** 项通过；`ThemeTest` 检查依赖、主题及零 Window。主页面、例句和复习的 README 预览更新为 0.7.0 实际控件离屏图；页脚取实际词条数，导航与语音列表为演示状态，复习进度使用临时文件。
- 真实数据中 `took into account` 已有扩充词条，优先精确命中；`takes a back seat → take a back seat` 和 `making a bad start → make a bad start` 才是本轮核对的实际词形回退。`large language model`、`deepfake`、`doomscrolling` 仍未收录；条目数量增加不代表所有现代新词都得到覆盖。
- 候选 Windows app-image 构建成功。实际 `WordNest.exe --self-check` 退出码 **0**，报告 `status=PASS`、`app.version=0.7.0`、`headless=true`、`dictionary.entries=3403176`、`lookup.normalization.phrases.inflections=PASS`，`java.home` 指向包内 runtime；EXE FileVersion 与 ProductVersion 均为 **0.7.0**。此自检不创建应用窗口或打开个人数据目录。
- 另从候选包 JAR 编译测试，运行时 classpath 仅含测试类、候选包 JAR 及依赖：`ExpandedDictionaryTest` 再次 **2,037 项通过**，`LookupResolverTest` **34**、`ClipboardWatcherTest` **46**、`LookupCandidatesPanelTest` **19** 项通过。候选包单次测得首次新缓存 **3.694 秒**、复用缓存 **0.304 秒**、加载后保留的 Java 堆增量 **108.1 MiB**、2,000 次查询 **0.037 秒**；与前述开发构建测量分开记录，同样不代表跨机器性能或进程总内存。

主词库完整 WND2 为 **363,707,041 字节（约 346.9 MiB）**，压缩分片合计 **102,087,251 字节（约 97.4 MiB）**。来源、转换规则、字段覆盖与各文件哈希见 `licenses/dictionary-expanded-source.json`。这些检查证明结构与来源保留，不证明全部历史释义在语义、专业准确性或时效上可靠。

本轮不操作实际桌面，不以复制测试字符串代替跨应用剪贴板验收；无窗口、无声检查与真实窗口、焦点、系统 DPI、快捷键、音量和听音验收属于不同范围。本轮没有重跑 `TrayMenuFontTest` 或真实 Windows 语音合成；其结果仅在下方 0.6.0 历史记录中保留。

复现时以 Java 17 目标编译 `tests/*.java`，将 `DictionaryTestResources.java` 一并纳入；只运行本节明确列出的 headless main，不能因源码已编译就运行会创建 JFrame 的 `MainWindowStateTest`。真实词库控件与覆盖检查通过 `-Dwordnest.testCache=<隔离测试路径>` 共享可重建缓存，不使用用户数据目录；`ExpandedDictionaryTest` 自身以命令行参数接收测试缓存路径。完整命令示例见 README。

---

# WordNest 0.6.0 验证记录（2026-10-23）

本版新增有限轮次、固定间隔的计划复习，以及 Windows 本机英文语音朗读。生产源码与全部测试以 `javac --release 17 -encoding UTF-8` 编译；本轮检查实际使用 Eclipse Temurin 17.0.20.1，新增功能的最终测试直接加载候选 Windows 包中的 JAR。

- ReviewStoreTest：119 项通过。使用临时文件与可控时钟验证 10/20 词轮次、到期优先/新词补充、不用未到期词凑数、重开续做、有限结束、取消收藏跳过、固定 1/3/7/14/30/60 天间隔及忘记 10 分钟后到期；损坏文件、写入失败和陈旧题目回答不会覆盖原进度或误答下一题。
- ReviewPanelTest：57 项通过（传入预览目录）。使用生产主题，覆盖揭晓后评分、20 词选择、续轮、完成统计、保存失败保留当前题、语音回调与切题停止；340/460 宽度的 16 张 2 倍离屏图含真实 relevant 词条及临时长笔记。长文只滚动题面，评分按钮固定可见；没有创建 Window。
- SpeechServiceTest：21 项；WindowsSpeechEngineTest：18 项通过。使用假语音引擎/假进程测试连续请求替换、停止、关闭、异常、过长文字、数据与脚本分离以及超时；总截止时间覆盖子进程不读取 stdin 导致的写入阻塞。
- SpeechControlsTest：101 项通过。覆盖保存语音/语速、初始化不覆盖缺失首选项、无语音与失败提示、停止状态、340/440/640 宽度，以及用于复习窗口的 speechStatus 属性广播、EDT 回调与移除监听。共 9 张离屏图；未调用真实播放。
- SpeechButtonsTest：76 项通过。实际词条与例句控件在 340/440/640 宽度下的按钮边界、完整英文回调及标点保留均通过；回调只记录文字，没有创建语音引擎或播放声音。
- WindowsSpeechProbe：从最终候选包 JAR 中读取固定脚本，实际枚举本机可用英文桌面语音，得到 Microsoft Zira Desktop / en-US。用该语音无声生成临时 WAV：215,852 字节，RIFF/WAVE 头正确，data 块 215,806 字节且含非零音频数据；随后删除临时文件。没有输出到扬声器，因此不能据此声称音质、口音自然度或实际音量已听测。
- 本轮同时重跑 CoreTest 1,045、FavoritesRecoveryTest 73、LookupHistoryTest 256、LookupSessionTest 54、ExampleSelectionTest 31、WordDetailPanelTest 8,026、LearningPanelTest 12,490、LearningRepositoryTest --full 504、CuratedCoverageTest 6,810、OpenLearningTest 202,239，全部通过。原词库与学习覆盖保持不变。
- TraySupportTest 14、BackgroundControllerTest 121、ActivationRequestsTest 40、TrayMenuFontTest 44 通过；ThemeTest 验证固定依赖、FlatLaf 3.7.2、Windows DLL 与零 Window。托盘字体检查仅构造无 peer 的 AWT 菜单，没有注册系统托盘。
- WordNestPreview 生成九张包含朗读控件的离屏主页面预览；语音列表与导航数量使用演示状态，不读取个人数据。README 的主页面、例句与复习预览已更新。
- `scripts/build.ps1 -BuildVariant release060-package` 构建 Windows app-image 成功。实际原生 EXE 执行 `--self-check` 退出码 0，报告 `status=PASS`、`app.version=0.6.0`、`headless=true`，运行时来自候选包，语音桥接资源存在；EXE FileVersion 与 ProductVersion 均为 0.6.0。此自检不枚举语音、不播放声音，也不打开个人数据目录。

MainWindowStateTest 仅编译，未执行其 JFrame 测试。没有打开应用窗口、操作真实桌面、读取剪贴板、播放声音或修改个人收藏/复习数据。界面离屏图、无声语音合成与真实桌面、焦点、系统 DPI、快捷键、音量及听音验收是不同的验证范围。

复现：构建后编译 tests 下源码，classpath 包含应用 JAR 与 `lib/flatlaf-3.7.2.jar`。纯 Java/Swing 测试使用 `-Djava.awt.headless=true -Xmx512m`；ReviewPanelTest、SpeechControlsTest、SpeechButtonsTest 和 WordNestPreview 传入临时预览目录。真实无声语音探测需额外显式传入 `-Dwordnest.speech.probe=true` 并运行 wordnest.WindowsSpeechProbe。TrayMenuFontTest 单独使用 `-Djava.awt.headless=false`，不建立原生 peer。

---

# WordNest 0.5.0 验证记录（2026-10-23）

本次增加取消收藏后的笔记保留与恢复、最近查词及会话前进后退，并整理现有例句的筛选、去重和义项分组。所有数据测试使用临时目录；没有读写实际个人生词本、历史或设置。

- 完整生产源码及全部测试源码以 `javac --release 17 -encoding UTF-8` 编译通过。运行检查使用发行包自带 Eclipse Temurin 17.0.20.1。
- CoreTest：1,045 项；FavoritesRecoveryTest：73 项。覆盖取消收藏保留完整笔记、重开后恢复、编辑保留笔记不重新收藏、恢复最新笔记、v1 只读加载、首次写入前保留原字节备份、不同旧备份不覆盖、备份/写入失败不提交状态，以及损坏数据保留。
- LookupHistoryTest：256 项。覆盖最近 100 个不同词、大小写去重、重开持久化、独立会话导航、回退后新分支、重复查询、清空、坏文件/UTF-8/长度边界、写入失败和纯内存降级。
- LookupSessionTest：54 项。测试生产使用的保存与导航协调器及实际导航 JPanel：未保存笔记阻止切换、历史保存失败仍可查词及会话导航、查无词页面先返回最近成功词、不改变历史位置、按钮状态/回调以及窄宽度布局。
- ExampleSelectionTest：31 项。覆盖实际词形匹配、合格句优先、同等匹配时学习卡/原创练习/社区顺序、短句筛选、归一去重后保留全部原文/译文/出处，以及缺少合格句的回退。排序规则不能证明语法、自然度或翻译准确性；本版未增加或改写语料。
- LearningPanelTest：12,490 项。使用实际 Swing 控件覆盖 340/440/640 像素宽度、推荐与更多语料、逐句来源、WordNet 真实义项分组、页签与折叠状态，生成 63 张离屏图。另用 100%/125%/150%/200% 的 Graphics2D 缩放验证真实字形宽度；统一 fractional 字宽测量与绘制，修复 relevant 长句原本在 2 倍绘制下被裁掉的问题。临时移除该修复时，新回归确实失败于字宽 422 大于容器 414。已查看 relevant 默认、certificate 默认及窄版全部展开预览。
- WordDetailPanelTest：8,026 项；LearningRepositoryTest --full：504 项；CuratedCoverageTest：6,810 项；OpenLearningTest：202,239 项。主词库 770,611 条，355 张原有学习卡、710 条原有原创双语例句、14,937 条社区展示记录及 341 条补充原创例句保持有效；学习覆盖数据未变。
- TraySupportTest：14 项；BackgroundControllerTest：121 项；ActivationRequestsTest：40 项通过。ThemeTest 验证固定依赖 SHA-256、FlatLaf 3.7.2、Windows DLL 加载与零 Window。
- TrayMenuFontTest：44 项，通过未建立 peer 的 AWT 菜单对象验证中文字体、字形与回调；没有显示菜单、注册托盘或创建窗口。
- WordNestPreview：coordinate / relevant / certificate 的 440/520/640 宽度共九张离屏图，包含生产导航控件；README 中两张预览已更新。主界面预览使用演示状态，未伪造原生标题栏。
- 通过 `scripts/build.ps1 -BuildVariant release050-package` 构建 Windows app-image。实际原生 EXE 执行 `--self-check`：退出码 0、`status=PASS`、`app.version=0.5.0`、`headless=true`，Java 来自候选发行目录的 runtime；EXE FileVersion 与 ProductVersion 均为 0.5.0。自检先于个人数据目录、锁和界面初始化执行。

MainWindowStateTest 中的 75 项窗口状态回归定义仅完成编译，**没有执行**，因为它会创建 JFrame。本轮没有创建应用窗口、读取真实剪贴板、操作鼠标键盘或系统托盘；离屏缩放回归不等于真实系统 DPI、焦点、快捷键及窗口交互验收。

可复现方式：先构建应用，将 tests 下源码以 Java 17 目标编译，classpath 包含应用 JAR 和 `lib/flatlaf-3.7.2.jar`。上述无窗口测试使用 `-Djava.awt.headless=true -Xmx512m`；WordDetailPanelTest、LearningPanelTest、WordNestPreview 还需传入预览输出目录。TrayMenuFontTest 单独使用 `-Djava.awt.headless=false`，但不建立原生 peer。不要在禁止桌面操作的验证环境中运行 MainWindowStateTest。

---

# WordNest 0.4.3 验证记录（2026-10-23）

本次修复用户截图中系统托盘右键菜单中文显示为方框的问题。Swing/FlatLaf 的字体设置不覆盖 AWT 原生菜单；现在由实际菜单工厂在创建子项及原生 peer 前设置 Microsoft YaHei UI 13pt，普通项与勾选项继承该字体。

- 完整源码以 `javac --release 17 -encoding UTF-8` 编译通过，使用发行包自带 Eclipse Temurin 17.0.20.1 运行本轮检查。
- TrayMenuFontTest：44 项断言通过。调用与生产一致的菜单工厂，检查四条真实中文标签、实际解析的字体族为 Microsoft YaHei UI、每个字符（含省略号）有字形、普通项和 CheckboxMenuItem 随父菜单字体变化、开关初始状态及动作/勾选回调。
- 上述测试必须使用 `-Djava.awt.headless=false` 才能构造 AWT MenuComponent，但仅创建未建立 peer 的菜单对象；没有调用 addNotify、显示菜单、注册 TrayIcon 或创建 Window。它验证字体配置与字形覆盖，不等于 Windows 原生菜单的屏幕验收。
- TraySupportTest：14 项；BackgroundControllerTest：121 项；ActivationRequestsTest：40 项通过，全部使用无窗口或临时数据验证。
- ThemeTest 通过：界面依赖 SHA-256 正确，FlatLaf 3.7.2 生效，Windows DLL 加载成功，创建 Window 数量为零。
- 通过 `scripts/build.ps1 -BuildVariant release043-package` 构建 Windows app-image，实际原生 EXE 自检退出码 0、`status=PASS`、`app.version=0.4.3`、`headless=true`；EXE FileVersion 与 ProductVersion 均为 0.4.3。

没有弹出或操作真实托盘菜单，没有读取剪贴板或个人生词本。本次未重跑与字体修复无关的全部词库及阅读布局测试；0.4.2 的完整检查记录保留如下，正文预览也沿用该版。

---

# WordNest 0.4.2 验证记录（2026-10-23）

本次针对学习分页的信息层次、相关性分组、来源折叠、窄窗换行，以及已有窗口与后台功能的发布集成；下文旧版记录保留为历史。

- 完整源码以 `javac --release 17` 编译通过，使用 Eclipse Temurin 17.0.20.1 运行测试。
- LearningPanelTest：10,475 项断言通过。覆盖 certificate 的默认双语卡、来源展开/收起、未直接使用查询拼写的 WordNet 例句隔离、原创与社区标签、词形提示、完整词边界高亮、组间和页签间独立状态、完整来源和许可地址、相关词跳转及缺项状态。
- 使用实际 Swing JPanel 检查 340 / 440 / 640 像素宽度，以及例句、派生、词根、近义词默认与展开状态的末行、末字和容器边界；abandon 词根页在来源展开后按 640 → 340 → 440 调整宽度，未依赖递归失效辅助也没有裁字。生产布局按实际内宽重新测高，避免旧 BoxLayout 缓存过期高度。
- CoreTest：1,044 项；WordDetailPanelTest：8,026 项；BackgroundControllerTest：121 项；ActivationRequestsTest：40 项；TraySupportTest：14 项通过。后台部分使用无窗口 host / 临时文件协议，只证明被测逻辑，未实际注册系统托盘。
- LearningRepositoryTest --full：504 项；CuratedCoverageTest：6,810 项；OpenLearningTest：202,239 项通过。主词库与学习资料的覆盖保持不变：355 张中文卡、710 条原有原创双语例句、14,937 条社区例句展示记录和 341 条补充原创例句。
- ThemeTest 通过：固定界面依赖 SHA-256 正确，FlatLaf 3.7.2 生效，原生 Windows DLL 加载成功，创建 Window 数量为零。
- WordNestPreview：coordinate / relevant / certificate 的 440 / 520 / 640 宽度共九张真实控件离屏图；没有模拟原生标题栏。学习局部预览保存在 `test-artifacts/reading-ui/`，不进入运行资源。
- 通过 `scripts/build.ps1 -BuildVariant release042-package` 构建独立 Windows app-image。原生 EXE 的 `--self-check` 实际退出码 0、`status=PASS`、`app.version=0.4.2`、`headless=true`，Java 来自发行目录自带的 runtime；主词库 770,611 条。EXE ProductVersion 与 FileVersion 均为 0.4.2。

本轮没有创建应用窗口、操作鼠标键盘、读取真实剪贴板或读写用户生词本。未进行真实桌面上的焦点、系统 DPI、窗口与托盘交互验收；无窗口策略测试不能代替原生托盘验收。源码、EXE 构建元数据、自检和使用说明统一为 0.4.2。

---

# WordNest 0.4.1 验证记录

本版采用 A 布局和统一绿色；中文按词性分行，形容词显示 adj.、副词显示 adv.，英文释义默认收起。原词库义项顺序、内容及个人数据格式保持不变。

## 本版实际检查

- Java 17 目标完整编译、JAR 和 Windows app-image 构建成功。
- WordDetailPanelTest：8,026 项纯 JPanel 断言通过。使用真实 coordinate、relevant、preserve、apply、get 及超长单词；检查 328/408/560 像素正文宽度、中文原文保留、字符与末行边界、默认折叠、反复展开、收藏按钮文字变长后宽度、adj./adv. 规范缩写。
- LearningPanelTest：1,830 项通过。更换 LAF 和分页样式后，四个分页文字布局、来源展开与相关词跳转回调仍通过。
- CoreTest：1,044 项通过，使用工作目录下的临时测试数据，不触及个人收藏。
- ThemeTest：固定依赖 SHA-256 正确，FlatLaf 3.7.2 生效，原生 x64 DLL 成功加载；活跃/非活跃标题栏底色均为 #F4F7F3，标题文字为 #276E59 / #73817A，按钮悬停色也为绿色系。创建 Window 数量为零。
- WordNestPreview：六张纯 JPanel 离屏内容图；440/520/640 宽度，使用真实查词和学习组件。已查看 coordinate 的窄版与默认尺寸。预览不伪造原生标题栏，也不是打开应用后截屏。
- 独立 headless 布局诊断：导航在 440/424/408 宽度与不同收藏数字下不重叠；多次宽窄切换与英文展开/收起未发现正文裁剪。
- 最终原生 EXE 实际执行 --self-check，退出码 0、status=PASS、app.version=0.4.1、headless=true、ui.nativeLibrary.loaded=true；运行时来自便携包内 runtime。报告：work/wordnest-ui041-final-native-check.properties。

原生包检查重新核对全部 7,684 个优先词：双语例句 6,260、派生关系 5,416、词源 6,887、近义关系 6,510；四项同时有资料 4,078。与 0.4.0 一致，所有随包词库/学习/图标资源经逐文件 SHA-256 比较相同。

## 可复现方式与边界

用 scripts/build.ps1 构建；以 JDK 17+ 编译 tests 下本版测试时 classpath 包含应用 JAR 和 lib/flatlaf-3.7.2.jar。使用 Java 17、'-Djava.awt.headless=true' -Xmx512m 运行 ThemeTest、WordDetailPanelTest、LearningPanelTest、CoreTest 和 WordNestPreview；需要输出目录的测试传入工作目录路径。JAR manifest 自动解析随包 lib，相对加载不依赖工作目录。

全程没有鼠标键盘注入、真实剪贴板读取、应用 Window 创建或桌面操作。标题栏配置和原生库已验证，真实窗口的拖动、最大化、焦点与系统 DPI 交互没有进行屏幕验收；这是本版验证的明确边界。以下为保留的旧版数据验证记录，不代表本版重复运行全部旧检查。

---

# WordNest 0.4.0 验证记录

所有本版检查均通过命令行、headless Java 或纯 JPanel 离屏绘制完成；没有创建应用 Window、读取真实剪贴板、注入鼠标键盘输入或操作桌面。实际测试运行时为 Eclipse Temurin 17.0.20.1+1，编译目标为 Java 17。

| 检查 | 实际结果 |
| --- | --- |
| LearningRepositoryTest --full | 504 项通过；含真实资源、损坏数据、UTF-8/长度边界、资源限额与别名。新增学习资源加载后约 81.7 MiB 保留堆，单次测量仅供参考，不是整程序内存上限。 |
| CuratedCoverageTest | 6,810 项通过；原有 355 张完整卡、710 条原创双语例句和四个拼写别名保持有效。 |
| OpenLearningTest | 202,239 项通过；逐词计算 7,684 行覆盖表，检查 14,937 条语料展示记录、341 条新原创例句、词形整词匹配、两个作者/ID/URL、中文字段及不可变查询结果。 |
| LearningPanelTest | 1,830 项纯 JPanel 断言通过；包含新增语料、原创补缺、中文词源、词缀解释、出处展开，以及 340/440 像素宽度的末行文字边界。 |

上述 Java 检查合计 211,383 项。覆盖表由实际加载的数据核算：新增学习资料 6,695 词；双语例句 6,260 词；词族/派生 5,416 词；词源 6,887 词；近义 6,510 词；中文词源/词缀解释 2,234 词；四项同时有资料 4,078 词。

结构测试不能证明所有句子的译文和词义正确。内容抽查核对了学术、日常、环境等例句；排除五组明显不妥的译文配对，并检查 341 条原创例句。新增源数据保留原站校对/待修标记作为筛选依据，未声称逐条专业编辑审定。

已查看 abandon 的例句离屏图片；文字末行边界由测试覆盖。离屏布局通过不等于真实桌面复制、焦点切换或人工关闭重开已验收，本版未执行这些桌面操作。

## 可复现检查

构建后，以 --release 17 编译 tests/LearningRepositoryTest.java、CuratedCoverageTest.java、OpenLearningTest.java、LearningPanelTest.java；classpath 包含应用 JAR 与测试类目录，运行时使用 '-Djava.awt.headless=true' -Xmx512m。程序类名依次为 wordnest.LearningRepositoryTest --full、wordnest.CuratedCoverageTest、wordnest.OpenLearningTest、wordnest.LearningPanelTest 后接预览输出目录。

python scripts/assemble_curated.py --check 检查原有卡片；python scripts/prepare_open_learning.py --check 从随源码保留的选编快照重建新增资料并逐字节比较。全量原始来源重新导入要求固定 SHA-256，不会静默接受已变化的网络文件。

## 原生包与归档

最终 0.4.0 EXE 已实际执行 --self-check，退出码 0、status=PASS、headless=true；java.home 指向便携目录内的 runtime，运行时为 17.0.20.1。全部 7,684 个优先词的覆盖清单由实际加载数据逐项核对，结果与上表一致。

另有一项内容验收条件：ECDICT 带 ielts 标签且 BNC 或 FRQ 正排名不超过 5,000 的 2,583 个词全部有英汉例句；缺少其中任何一个，原生包检查都会失败。这是第三方词库标签与排名的交集，不是“雅思全部词汇”声明。

两个数据生成器已通过 --check，逐字节重现随包资源。原生验证报告为 work/wordnest-v04-final-native-check.properties。此无窗口自检在个人数据目录、单实例锁和界面初始化之前执行，不读取收藏、设置或剪贴板。


## 0.7.1 历史容量（2026-10-24）

Java 17 无窗口验证：LookupHistoryTest 267 项、LookupSessionTest 54 项通过；涵盖默认上限、50/100 切换、即时清理、重启持久化、去重、写入失败回滚、内存降级与空历史入口。编译通过。未操作真实窗口。


## 0.7.2 托盘中文菜单（2026-10-24）

旧版仅检查 AWT 字体继承和 canDisplayUpTo，不能证明原生 Windows 菜单绘制正常；用户截图再次出现方块。改用 Swing 控件和 Java2D，与主窗口一致，不再向 TrayIcon 挂接 AWT PopupMenu。TrayMenuFontTest 30 项、TraySupportTest 14 项、BackgroundControllerTest 121 项、ActivationRequestsTest 40 项通过。实际菜单控件已离屏绘制并检查中文可读性。未打开真实托盘菜单；系统前台焦点、失焦关闭及多显示器现场行为仍待实际使用确认。


## 0.7.3 设置布局（2026-10-24）

PreferencesPanelTest 在 FlatLaf 100%、150%、200% 缩放配置下各通过 26 项检查，覆盖 340×220、500×400、640×520 内容区域，核对底部按钮边界和高度、小窗口滚动及正文宽度跟随。实际面板已离屏绘制检查；未操作真实桌面窗口。


## 0.7.3 发布前复查（2026-10-24）

本次以最终程序 JAR 重跑 LookupHistoryTest 267、LookupSessionTest 54、TrayMenuFontTest 30、TraySupportTest 14、BackgroundControllerTest 121、ActivationRequestsTest 40、PreferencesPanelTest 26，共 552 项通过。最终程序未再改代码；使用已通过自检的 0.7.3 app-image，仅更新随包文档。


## 0.7.4 词巢品牌更新（2026-10-24）

- 使用用户选定的 C2 词巢字母卡图案。`design/selected-icon.png` 保存原图；随包 `app.png` 为 256px，`app.ico` 含 16、24、32、48、64、128、256px。窗口采用 16/32/64px 资源，托盘使用 32px 资源。
- Java 17 目标编译及 Windows app-image 构建成功，程序文件 `词巢.exe` 的 ProductName 为“词巢”、FileVersion/ProductVersion 均为 0.7.4；从 EXE 提取的小图标可辨认绿色词巢和 A 字母卡。
- `词巢.exe --self-check` 退出码 0，报告 `status=PASS`、`app.version=0.7.4`、`headless=true`、`dictionary.entries=3403176`。TrayMenuFontTest 30、TraySupportTest 14、BackgroundControllerTest 121、ActivationRequestsTest 40 项及 ThemeTest 通过；未操作真实桌面窗口或托盘。
- 最终源码重编后，LearningPanelTest **12,491 项**离屏断言通过，包含旧资源署名在可见出处中显示为“词巢”的检查；`prepare_open_learning.py --check` 与固定资源逐字节一致。
- 原 `%LOCALAPPDATA%\WordNest` 个人数据目录和单实例协议保持不变，未迁移或删除收藏、笔记、历史、复习数据。GitHub Release 0.7.3 仍为旧名称与旧图标；本段只记录本地 0.7.4 的实际验证。
