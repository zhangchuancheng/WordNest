# 第三方内容与许可证

本文记录词巢 0.7.4 使用的第三方数据及便携版运行时。原始许可证文件随项目保留。当前橙底词巢与字母卡图标的原图保存在 `design/selected-icon.png`，应用内图标和 Windows 图标均由此生成；旧版绿色书本图标不再使用。

## 授权归属一览

| 内容 | 权利人与来源 | 适用许可及本地原文 |
| --- | --- | --- |
| `dictionary.bin.gz` 基础词库及其合并输入 | skywind3000/ECDICT；上游版权声明为 Linwei | MIT；`licenses/ECDICT-LICENSE.txt`（2025 声明） |
| `dictionary/` 中的合并词库分片和词形索引 | ECDICT 基础词库及官方 1.0.28 SQLite 历史数据 | MIT；保留上述声明及 `licenses/ECDICT-1.0.28-LICENSE.txt`（2017 声明） |
| `lexical.bin.gz` | Open English WordNet Team；含 Princeton University WordNet 底层内容 | CC BY 4.0，并保留 Princeton WordNet 条款；`licenses/OEWN-LICENSE.txt`、`licenses/OEWN-WNDB-LICENSE.txt` |
| `etymology.bin.gz` 及其数据改编 | droher、etymology-db 贡献者与 Wiktionary 贡献者 | 独立按 CC BY-SA 3.0 分发；`licenses/Etymology-CC-BY-SA-3.0.txt`、`licenses/Etymology-data-NOTICE.md` |
| `learning-curated.tsv` 中的原创例句和中文整理 | WordNest 原创学习整理；参考页面逐词列在 source 行 | 不是上游词典整段转载；本说明不为这些原创内容另行指定许可证 |
| FlatLaf 3.7.2 界面库及 Windows x64 DLL | FormDev Software GmbH 及贡献者 | Apache 2.0；`licenses/FlatLaf-NOTICE.md`、`licenses/FlatLaf-APACHE-2.0.txt` |
| 便携 Java 运行时 | Eclipse Adoptium / OpenJDK 及其组件权利人 | GPL v2、适用部分的 Classpath Exception 及各组件许可；见 `runtime/legal/` |

词源资源单独存储、单独署名和授权，**CC BY-SA 3.0 适用于该词源数据及其改编，不用于替代 WordNest 应用代码或其他独立资料的许可证**。分发数据时请保留对应署名、许可和修改说明。上游项目名称仅用于来源说明，不表示其认可或担保本应用。

## ECDICT 离线词库与词形索引

### 原有基础词库

- 项目：<https://github.com/skywind3000/ECDICT>
- 固定提交：`bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`
- 源数据：<https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/ecdict.csv>
- 上游许可：<https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE>
- 本地许可原文：`licenses/ECDICT-LICENSE.txt`
- 原 CSV：65,933,428 字节，770,611 条记录
- 原 CSV SHA-256：`1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`
- 应用资源：`src/main/resources/dictionary.bin.gz`
- 资源 SHA-256：`0eeddb7903a116161a8baef7b448b7673f377910b240003b8486f95e69e5a92a`

基础资源的处理方式：解析原始 UTF-8 CSV，将保留字段中的字面换行符恢复为换行，移除字段首尾空白，按不区分大小写的词头建立索引，保留原词头、音标、中文释义、英文释义、标签与 exchange 六个字段，再序列化为 WND1 格式并 gzip 压缩。该基础版本无空词头或重复键导致的记录删减，仍为 770,611 条。它作为 0.7.0 合并时优先保留的输入，原覆盖和校验信息继续记录于 `licenses/dictionary-source.json`。

### 0.7.0 使用的扩充数据

- 官方发行：[ECDICT 1.0.28](https://github.com/skywind3000/ECDICT/releases/tag/1.0.28)，发布页日期为 2017-09-20；该日期不等于 SQLite 文件内容的精确生成日期。
- 官方附件：[ecdict-sqlite-28.zip](https://github.com/skywind3000/ECDICT/releases/download/1.0.28/ecdict-sqlite-28.zip)，2018-05 上传，216,765,132 字节。
- ZIP SHA-256：`ea01f76a3b3351021ce47077e89234465cc9441c8793054495320d06c0c3f3f6`
- ZIP 内的 `stardict.db`：851,288,064 字节，读取到 3,402,564 条原始记录。
- SQLite SHA-256：`2b5b40c2bdba04da0a51c8672e090f166987d5d895f32eb3fbfc5a516455fc75`
- 对应 tag 的 [MIT 许可证](https://github.com/skywind3000/ECDICT/blob/1.0.28/LICENSE) 声明 `Copyright (c) 2017 Linwei`，原文保留于 `licenses/ECDICT-1.0.28-LICENSE.txt`；基础 CSV 的 2025 版权声明继续保留，不以一个替代另一个。

合并规则：先完整保留基础库 770,611 条，再按 SQLite 原记录 ID 顺序，只加入基础库没有的不区分大小写词头。补入 **2,632,565 条**，合并去重后为 **3,403,176 条**；同名词头不从扩充数据覆盖原释义。保留原始拼写和六个数据字段，仅恢复字面换行、去除字段首尾空白，并给新加入的记录追加 `wn-expanded` 来源标签。界面据此显示“来源：ECDICT 扩充词库”。

应用使用 WND2 格式：大端记录数与偏移索引，记录包含小写查询键及六个 UTF-8 原始字段；查询键按 Java UTF-16 顺序排序。完整 gzip 流按最大 40 MiB 拆成随包分片，首次使用时校验并还原为本机缓存供内存映射读取。分片、完整资源、覆盖数与校验结果见 `licenses/dictionary-expanded-source.json` 和 `src/main/resources/dictionary/manifest.properties`。转换脚本为 `scripts/prepare_expanded_dictionary.py`，只读取指定本地输入，不联网下载。

合并库有中文释义 **3,386,635 条**、音标 **353,030 条**、英文释义 **177,409 条**。未压缩 WND2 为 **363,707,041 字节**，SHA-256 为 `139883192ee4a9ffced05f82aba091205e03585f09ceb0e772f262c0da8edd56`；gzip 共 **102,087,251 字节**，分为 3 片，各片哈希保存在上述元数据与 manifest 中。

词形索引 `dictionary/inflections.tsv.gz` **仅来自合并词条的 exchange 显式关系**（过去式、过去分词、现在分词、第三人称单数、比较级、最高级、复数和原形）。它保留符合格式且原形目标已收录的对应关系，不用截词尾算法猜原形，不生成词义、译文、音标、例句或词源。

索引含 **161,859 对关系、161,398 个变化形式、141,977 个目标词头**，压缩 834,224 字节，SHA-256 为 `47a3b8fb3cc093c1276c6a02a48a5acb6af604694144c3c3347688e63f10ed0b`。一个形式可能有多个候选；来源关系不代表已判断它在具体句子中的词性或意思。

本次使用的是历史社区词典数据，包含词组、变形词、专名、专业表达及网络释义；原释义可能混杂或过时，未逐条专业审定，也不覆盖所有新词。条目总量不等于互不重复的基础词汇数，不代表全部词条都有音标、英文释义或学习分页。缺失字段保持为空，原有独立学习资料的覆盖数没有随主词库扩充而增加。

以下保留基础词库所用固定提交的 MIT 许可证全文；扩充数据的 2017 原文另见上述独立许可文件：

```text
MIT License

Copyright (c) 2025 Linwei

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Open English WordNet 2025 学习资料

- 项目：[globalwordnet/english-wordnet](https://github.com/globalwordnet/english-wordnet)
- 固定发行：[2025 edition](https://github.com/globalwordnet/english-wordnet/releases/tag/2025-edition)
- 对应提交：`dc343f2683279ecbb13fab4e2fd778d7b162d287`
- 原数据：[english-wordnet-2025.xml.gz](https://en-word.net/static/english-wordnet-2025.xml.gz)
- 原数据 SHA-256：`9ca6d1dcb75f822fdd66617f7d9da48142ace38dd544d6ad5e2feca1674ad3fe`
- 应用资源：`src/main/resources/lexical.bin.gz`，9,473,834 字节
- 资源 SHA-256：`11249fb35bacdd07b32c745b9104c0f70f52382b833c4da83b28795a6f106713`
- [上游 LICENSE.md](https://github.com/globalwordnet/english-wordnet/blob/dc343f2683279ecbb13fab4e2fd778d7b162d287/LICENSE.md)；[Princeton WordNet 随附条款](https://github.com/globalwordnet/english-wordnet/blob/dc343f2683279ecbb13fab4e2fd778d7b162d287/WNDB_License.txt)
- [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)；许可全文保留于 `licenses/OEWN-LICENSE.txt`，WordNet 声明保留于 `licenses/OEWN-WNDB-LICENSE.txt`

署名：本资源改编自 **Open English WordNet 2025，由 Open English WordNet Team 制作，包含 Princeton University WordNet 的底层内容**。WordNest 对其作了格式转换与字段选择；这些来源项目并未为本应用背书。上游声明包含 `Copyright (c) 2019-present, The Open English WordNet Team` 和 `WordNet 3.1 Copyright 2011 by Princeton University`，随附原文均予保留。

处理方式：从 XML 生成 WNL1 gzip 二进制，每条记录对应原词汇义项，保留词头、词性、英文释义、例句、同义词和明确标为 derivation 的关系。例句合并义项与同义词集内容、规范内部空白并去重；同义词取同一义项组的其他成员；派生只使用上游明确关系，不按近似拼写猜测。未生成中文译文、例句、词根或其他新关系。

覆盖为 **185,129 条义项记录、127,311 个不区分大小写的词头**，其中 35,702 个词头有例句、91,058 个有同义词、27,254 个有派生关系。数量不代表全部 ECDICT 词条均得到增强，也不代表每个义项都包含例句。关系可能指向词基或派生词，不能把方向当作已经判断过的派生顺序。

原例句已有的作者或来源署名保留在每条例句中，并单独汇总于 `licenses/lexical-example-sources.json`；没有单条例句署名的记录标明 Open English WordNet / Princeton WordNet。例句属于共享义项，可能使用同组的另一个词。原数据的英文例句没有配套中文翻译。

完整溯源、转换规则、覆盖计数、校验值和官方推荐引用见 `licenses/lexical-source.json`。推荐论文为 McCrae 等（2019），[English WordNet 2019 — An Open-Source WordNet for English](https://aclanthology.org/2019.gwc-1.31/)。

## Wiktionary / etymology-db 词源关系

- 项目：[droher/etymology-db](https://github.com/droher/etymology-db)
- 原数据：**2023-12 发行快照**，上游生成日期 2023-12-05
- 固定资产：[etymology.csv.gz](https://github.com/droher/etymology-db/releases/download/2023-12/etymology.csv.gz)
- 原数据 SHA-256：`e36eba331b73075fd74c8281b96eb3b31bebc510dad091ddeb815453a49d594d`
- 应用资源：`src/main/resources/etymology.bin.gz`
- 资源 SHA-256：`3007b7a9264c34078243a1082b90d73ede1d435e68dcfcd6d9ea233a340ac4ce`
- 数据许可：[CC BY-SA 3.0 Unported](https://creativecommons.org/licenses/by-sa/3.0/)
- 完整署名与修改说明：`licenses/Etymology-data-NOTICE.md`
- 许可全文：`licenses/Etymology-CC-BY-SA-3.0.txt`

署名：词源数据由 **droher 与 etymology-db 贡献者**从 **Wiktionary 贡献者**的内容提取。WordNest 提供经过筛选和格式转换的独立词源子集，并将该子集及相应数据改编继续按 **CC BY-SA 3.0** 分发。上游项目代码使用的 Apache 2.0 **不是其数据许可证**。

本项目的修改包括：只保留主词库中存在的英语词头；选取明确的祖源与构词关系；排除一般比较组、cognate_of 和无结构的相关词指针；保留嵌套派生与词缀组上下文，用中文标签解释关系；去重、按小写词头排序并编码为 WNE1 gzip。不添加按相似拼写推测的同源关系或生成式历史路径。

成品覆盖 **134,158 个词头、309,779 条关系**，压缩 **2,721,192 字节**，展开 **20,111,260 字节**。重复的 sourceURL 字段为空以减少存储，界面按词头生成 `https://en.wiktionary.org/wiki/{词头}#English`，页面历史可追溯贡献者。完整转换计数保存在 `src/main/resources/etymology.metadata.json.gz`。

这是自动提取的历史快照，上游明确说明结果未逐条核验。现代页面可能已经修正；同形异源词可能被汇集，来源链成分不必是当前词的直接词根，组内位置也不证明历史先后。保留这些边界是使用数据的一部分。修改或再分发此词源子集时，应继续提供署名、许可链接、修改说明并遵守相同方式共享条件。

## 原创中文学习卡及参考来源

`src/main/resources/learning-curated.tsv` 为 355 个学习词头提供 **至少 710 条原创英语例句及原创中文译文**，另有搭配、派生/同词族提示、近义区别和词源说明。文件使用固定五列；每词的 `source` 行保留核对来源名称、URL 和用途，界面可以展开地址。

中文学习说明与原创例句是 WordNest 的学习整理，不宣称来自 Cambridge、Wiktionary 或 Etymonline 的原文。原有 12 词的词义、构词和词源事实参考这些页面核对，未整段复制其说明；来源网页的文字、商标及其他内容仍归相应权利人。参考链接本身不构成这些网站的整体再利用授权。

本文件与上述三份大词库资源分别存储。现代构词、历史词源和助记拆分明确区分；例如 relevant 的 re- / lev / -ant 是助记分析，核对过的 relieve、elevate、alleviate 作为历史同源联想，不冒充现代派生或近义词。

## Eclipse Temurin Java 运行时

Windows x64 便携版内置 **Eclipse Temurin OpenJDK JRE 17.0.20.1+1 / HotSpot**。源码版本身不包含该运行时目录。

- 项目：<https://adoptium.net/>
- 官方二进制发行及对应源码下载：<https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20.1%2B1>
- 官方资产下载：<https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip>
- 上游文件：`OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip`
- SHA-256：`bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352`
- 本地来源说明：`licenses/TEMURIN-RUNTIME.md`

OpenJDK 主要采用 GNU General Public License version 2，并在注明的部分附带 Classpath Exception；模块和第三方组件可能有各自许可。便携版保留运行时原始 `runtime/legal/` 目录、`runtime/NOTICE` 及其他随附文件，请以这些原文为准。重新分发时应继续保留其版权声明和许可证。

打包采用的原始 ZIP 已与官方 Adoptium API 返回的 SHA-256 核对。运行时 `release` 文件标明 `IMPLEMENTOR="Eclipse Adoptium"`、`IMAGE_TYPE="JRE"`、`OS_ARCH="x86_64"` 和 `JAVA_RUNTIME_VERSION="17.0.20.1+1"`。原运行时包含 `bin/`、`conf/`、`legal/`、`lib/`、`NOTICE` 与 `release`；其中 `legal/java.base/LICENSE`、`ADDITIONAL_LICENSE_INFO`、`ASSEMBLY_EXCEPTION` 保留上游原文。

开发机上的 Oracle JDK 仅用于本地编译和调用 `jpackage`，其运行时不捆绑进便携版。Eclipse Temurin、Java 和 OpenJDK 名称属于各自权利人。

## 授权范围

上面的许可分别适用于所列第三方内容。它们不自动决定 WordNest 自有源码、文档、图标或原创学习整理的许可范围。项目根目录若另行提供 LICENSE，其范围也不替代本说明列明的独立第三方许可；请同时保留各数据集和运行时的原始声明。

### 0.3.0 新增中文整理的来源记录

新增 343 词的原始选词清单、ECDICT 考试标签与词频记录保留在源码 data/learning；分片数据可通过 scripts/assemble_curated.py 重新生成，精确计数与资源 SHA-256 见 licenses/curated-source.json。

新增卡片以固定版 ECDICT、Open English WordNet 2025 和 etymology-db 2023-12 的离线记录作为词义、词族和构词参考；原始词源关系的权利人与 CC BY-SA 3.0 许可保持不变，依照这些记录改编的关系说明也遵守对应许可。source 行区分离线快照依据与实际另行核对的网页；网页地址不表示所有页面都在本轮逐页访问。

中英例句、搭配提示与近义辨析为新编学习材料，不声称是第三方词典的原句或官方中文译文。原 WordNet 英文例句仍保留原来源，未被冒充为新增双语材料。词源关系的相同方式共享许可不替代其他独立原创内容或应用代码的许可。
## 0.4.0 新增开放学习资源

Tatoeba 英汉句对按 CC BY 2.0 FR 保留逐句作者、ID 与来源；中文维基词典 / Kaikki 的中文词源、词缀和近义/派生记录及其改编按 CC BY-SA 4.0 提供。相关词释义另外标明来自维基词典或 ECDICT。341 条新原创例句与第三方语料明确区分。完整下载地址、各层归属、处理规则、许可文本位置和可复现方式见 [Open-learning-NOTICE.md](licenses/Open-learning-NOTICE.md)，精确计数与哈希见 licenses/open-learning-source.json。
