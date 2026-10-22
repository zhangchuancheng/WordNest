# WordNest 0.4.0 开放学习资料：署名、处理与许可

新增数据以 `src/main/resources/learning-open.bin.gz` 的 WNS1 六字段格式提供；字段为词头、类型、内容、中文内容、署名/关系范围、来源地址。`data/learning/open-learning-selected.json.gz` 保留筛选后的可编辑来源记录；341 条新原创例句另外存放在 `practice-examples-v04.tsv`。同一容器收集不同来源，不把所有内容统一冒充一种许可证。

## Tatoeba 英汉例句

权利人：各条英文和中文句子的 Tatoeba 贡献者。每条记录保留两位句子所有者的用户名、两个句子 ID、可访问的句子 URL 和 CC BY 2.0 FR 标识。来源：https://tatoeba.org/ ，使用条款：https://tatoeba.org/en/terms_of_use 。上游保留的匿名用户名也照原样署名。

下载快照为官方 2026-10-17 导出，下载/处理日期 2026-10-22：

- https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences_detailed.tsv.bz2
- https://downloads.tatoeba.org/exports/per_language/cmn/cmn_sentences_detailed.tsv.bz2
- https://downloads.tatoeba.org/exports/per_language/eng/eng-cmn_links.tsv.bz2
- https://downloads.tatoeba.org/exports/user_languages.csv
- https://downloads.tatoeba.org/exports/tags.tar.bz2

许可：[Creative Commons Attribution 2.0 France](https://creativecommons.org/licenses/by/2.0/fr/)。本地法律文本 `Tatoeba-CC-BY-2.0-FR.html`，原文取自 Creative Commons 官方仓库 https://github.com/creativecommons/creativecommons.org/blob/main/docroot/legalcode/by_2.0_fr.html 。本应用不使用音频。

处理：只连接英语与官话的直接翻译链接；不使用第三语言转接；仅保留有署名的句子、4–35 个英文词且具有中文译文的合适长度句对；过滤待检查、待修改、待删除、待改善、歧义、引用等标签及少量不适合一般学习的内容；优先选取带 OK/List 907 标签的英文及自报母语作者。标签和母语为原站元数据，不是本应用逐条审定的证明。保留简繁体，不进行机器翻译；仅规范空白、索引、选句、去重并压缩。界面仍注明社区译文可能有疏漏。

每词最多选三条不同的英文句子，采用精确单词匹配或来源中已有的、经规则/少量不规则白名单限制的正常词形。附注显示所用词形。不把 left/saw/found 等歧义形式自动猜回词基。五组人工抽查发现不佳的配对记录于 `example-rejections-v04.tsv` 并排除。

语料许可及免责声明继续适用；不暗示 Tatoeba 或贡献者认可本软件。相同句对可能在不同词头下展示，独立句对数与展示数分别统计。

## 中文维基词典 / Kaikki / Wiktextract

权利人：中文维基词典各条目贡献者。抽取工具和数据发布：Tatu Ylonen、Wiktextract 贡献者与 Kaikki.org。来源页面：https://kaikki.org/zhwiktionary/ ，语言分表为“英语”和“英語”。词条来源地址按记录保留，条目的历史页面可查贡献者名单。

下载文件：

- https://kaikki.org/zhwiktionary/%E8%8B%B1%E8%AF%AD/kaikki.org-dictionary-%E8%8B%B1%E8%AF%AD.jsonl
- https://kaikki.org/zhwiktionary/%E8%8B%B1%E8%AA%9E/kaikki.org-dictionary-%E8%8B%B1%E8%AA%9E.jsonl

版本：2026-10-01 zhwiktionary dump，2026-10-20 抽取；页面标记 wiktextract `c1bb342` / `6f96ddf`。本次使用两个英语语言分表的公开后处理 JSONL；不声称它们是最新原始 dump。

许可：[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)。版权说明：https://zh.wiktionary.org/wiki/Wiktionary:版权信息 。本地法律文本 `Wiktionary-CC-BY-SA-4.0.html`，取自 https://github.com/creativecommons/creativecommons.org/blob/main/docroot/legalcode/by-sa_4.0.html 。本包相关维基词典选编、格式转换与数据改编继续按 CC BY-SA 4.0 提供。

修改：筛选优先词头；保留中文词源原文与词性；规范空白，去重，排除提取错误；保留近义词条中的义项和使用标签；派生条目允许复合词与词组，界面明确显示其范围；不把“相关词”“反义词”充当近义词。相关词中文优先使用中文维基词典，缺项时使用 ECDICT，并单独标明释义来源及它可能包含其他义项。本次不转载维基词典的例句或文学引文。

词缀释义来自独立的英语词缀条目，仅展示 etymology-db 中实际出现的对应英语成分。一般词缀的多个含义不等于当前词全部采用这些含义。关联来源为既有 etymology-db 2023-12 / Wiktionary 数据（CC BY-SA 3.0，见同目录 Etymology-data-NOTICE.md）；相关跨版本数据改编按 CC BY-SA 4.0 提供，保留两层署名。没有按字符串相似度生成历史词根。

推荐研究引用：Tatu Ylonen (2022), “Wiktextract: Wiktionary as Machine-Readable Structured Data”, LREC 2022, pp. 1317–1325，https://www.lrec-conf.org/proceedings/lrec2022/pdf/2022.lrec-1.140.pdf 。

## ECDICT、原有资料与原创例句

选词频率、ielts 标签及部分关系词中文释义来自固定版 ECDICT，MIT，详见 dictionary-source.json 和 ECDICT-LICENSE.txt。Open English WordNet 与旧词源关系仍保留独立资源及许可，没有把它们重新声明为本项目原创。

341 条新原创英汉例句单独标为“拾词原创学习例句 · 非考试真题”；它们不是 Tatoeba、维基词典或 IELTS 的原句，本说明不另行指定其许可证。原有 355 张学习卡及 710 条原创双语例句也继续单独保留。

上述第三方许可分别适用于其数据及改编，不替代应用代码、图标、文档或其他独立内容的许可。分发时保留来源、署名、适用许可及修改说明。

## 校验与复现

所有输入文件 SHA-256 和资源 SHA-256 见 open-learning-source.json。源码包保留选编来源快照，因此 `python scripts/prepare_open_learning.py --check` 无需下载即可逐字节检查生成资源。完整重新导入可执行：

```powershell
python scripts/prepare_open_learning.py --sources 'C:\Path\To\Snapshots' --ecdict 'C:\Path\To\ecdict.csv'
```

下载时将上面两个维基词典文件分别命名为 `zh-en-simplified.jsonl` 和 `zh-en-traditional.jsonl`。导入会验证固定哈希；上游可变地址将来更新后不能静默冒充本版快照。原句保留的名字、语义与译文来自社区贡献，语料筛选与结构测试不等于逐句专业校订。
