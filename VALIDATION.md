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
