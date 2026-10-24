"""Build attributed offline learning supplements from pinned public snapshots.

Import: --sources DIR --ecdict CSV. Rebuild bundled selected data: --check.
No user data, network, paid service, or machine translation is used.
"""
import argparse
import bz2
import collections
import csv
import gzip
import hashlib
import json
import re
import struct
import tarfile
from pathlib import Path
from urllib.parse import quote
from extract_learning_reference import read_binary

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'data/learning'
RES = ROOT / 'src/main/resources'
CJK = re.compile(r'[\u3400-\u9fff]')
TOKEN = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
PINNED = {
    'eng-cmn_links.tsv.bz2': '1e9110de03d59a72fb92f1b82d3d3a2218efc320020be223db7784f77a8c7063',
    'cmn_sentences_detailed.tsv.bz2': '0d1b611c0a13cc38ebeb43157942626b50c0d3ceda857bc7d750542c77977d22',
    'eng_sentences_detailed.tsv.bz2': '24f742143d9079308d8c6b9c8cb4f6efb54e908a37d27fd5e40f22ab841b3d0d',
    'zh-en-simplified.jsonl': '9a34deb4d964326d33b30a2b3bacc94e58b5a1624527f23202b8759ce0a2bfcd',
    'zh-en-traditional.jsonl': '192ebca4ba346264a24ebcd98a8df9e7f3fc8dc6d74cf6f098b2149ee24e1b7f',
    'user_languages.csv': '8770cd3ae712fd791fc9370f98f6b17abb6d05d151cc5e5b67fe0494163b9376',
    'tags.tar.bz2': '6c2b5247af7edec4289e52554bfb6b8d33b7e13df0b5e6d23e28b622efda8cbd',
}
# Only unambiguous, source-attested regular forms are indexed. Homographs such
# as "left", "saw", "found" are deliberately not guessed back to a lemma.
IRREGULAR = {'be': {'am','is','are','was','were','been'}, 'go': {'went','gone'},
    'eat': {'ate','eaten'}, 'write': {'wrote','written'}, 'speak': {'spoke','spoken'},
    'take': {'took','taken'}, 'give': {'gave','given'}, 'choose': {'chose','chosen'},
    'begin': {'began','begun'}, 'break': {'broke','broken'}, 'drive': {'drove','driven'},
    'know': {'knew','known'}, 'grow': {'grew','grown'}, 'throw': {'threw','thrown'},
    'wear': {'wore','worn'}, 'forget': {'forgot','forgotten'}, 'buy': {'bought'},
    'bring': {'brought'}, 'teach': {'taught'}, 'think': {'thought'}, 'catch': {'caught'}}
BAD_TAGS = {'archaic','obsolete','dated','vulgar','offensive','derogatory','slur','rare'}

def clean(value):
    return re.sub(r'\s+', ' ', str(value)).strip()

def rank(row):
    return min([int(row[k]) for k in ('bnc','frq') if row[k].isdigit() and int(row[k]) > 0] or [999999])

def regular_forms(word):
    result = {word+'s',word+'es',word+'ed',word+'d',word+'ing',word+'er',word+'est'}
    if word.endswith('e'): result |= {word[:-1]+'ing',word+'r',word+'st'}
    if word.endswith('y'): result |= {word[:-1]+s for s in ('ies','ied','ier','iest')}
    if len(word)>2 and word[-1] not in 'aeiouwxy' and word[-2] in 'aeiou':
        result |= {word+word[-1]+s for s in ('ed','ing','er','est')}
    return result | IRREGULAR.get(word,set())

def wiki_url(word):
    return 'https://zh.wiktionary.org/wiki/'+quote(word,safe='')+'#英語'

def imported(sources, ecdict):
    for name, expected in PINNED.items():
        if hashlib.sha256((sources/name).read_bytes()).hexdigest() != expected:
            raise ValueError('Snapshot hash mismatch: '+name)
    dictionary = {}
    with ecdict.open(encoding='utf-8-sig',newline='') as stream:
        for row in csv.DictReader(stream):
            dictionary.setdefault(row['word'], row)
    priority = {w for w,r in dictionary.items() if re.fullmatch('[a-z]+',w)
                and CJK.search(r['translation']) and (rank(r)<=5000 or 'ielts' in r['tag'].split())}
    curated = set((RES/'curated-words.txt').read_text(encoding='utf-8').splitlines())
    selected = priority | curated
    rows = []
    def add(word, kind, first, second, third, url):
        row = [word,kind,clean(first),clean(second),clean(third),url]
        if not all(row): raise ValueError('Empty source field: '+repr(row))
        rows.append(row)

    wiki = collections.defaultdict(list)
    affixes = collections.defaultdict(list)
    for filename in ('zh-en-simplified.jsonl','zh-en-traditional.jsonl'):
        for line in (sources/filename).open(encoding='utf-8'):
            entry=json.loads(line)
            if entry.get('lang_code') != 'en': continue
            wiki[entry['word']].append(entry)
            if entry.get('pos') in ('prefix','suffix','infix'):
                for sense in entry.get('senses',[]):
                    if BAD_TAGS.intersection(sense.get('tags',[])): continue
                    for gloss in sense.get('glosses',[]):
                        if CJK.search(gloss) and clean(gloss) not in affixes[entry['word']]:
                            affixes[entry['word']].append(clean(gloss))
    def gloss(term):
        result=[]
        for e in wiki.get(term,[]):
            for sense in e.get('senses',[]):
                if sense.get('form_of') or BAD_TAGS.intersection(sense.get('tags',[])):continue
                for g in sense.get('glosses',[]):
                    if CJK.search(g) and clean(g) not in result:result.append(clean(g))
        # Store the origin of every Chinese gloss separately from the relation.
        if result:return '；'.join(result[:2]), '中文释义：中文维基词典', wiki_url(term)
        text=clean(dictionary.get(term,{}).get('translation','')).replace('\\n','；')
        return (text, '中文释义：ECDICT（可能包含其他义项）', 'https://github.com/skywind3000/ECDICT') if CJK.search(text) else ('','','')

    for word in sorted(selected):
        seen=set(); family=[]; synonyms=[]
        for entry in wiki.get(word,[]):
            if BAD_TAGS.intersection(entry.get('tags',[])):continue
            pos=entry.get('pos_title') or entry.get('pos','')
            for text in entry.get('etymology_texts',[]):
                text=clean(text)
                if not CJK.search(text) or text in seen or len(text)>5000:continue
                if any(s in text for s in ('Lua錯誤','Lua错误','脚本错误','模板:')):continue
                seen.add(text)
                add(word,'etymology','中文词源 · '+pos,text,
                    '中文维基词典贡献者 / Kaikki · 2026-10-01 快照 · CC BY-SA 4.0',wiki_url(word))
            for kind, destination in (('derived',family),('synonyms',synonyms)):
                values=list(entry.get(kind,[]))
                for sense in entry.get('senses',[]):
                    if BAD_TAGS.intersection(sense.get('tags',[])):continue
                    for value in sense.get(kind,[]):
                        values.append({**value,'sense':value.get('sense') or '；'.join(sense.get('glosses',[]))})
                for value in values:
                    term=clean(value.get('word',''))
                    if term == word or not re.fullmatch(r"[a-z]+(?:[- '][a-z]+){0,2}",term):continue
                    if BAD_TAGS.intersection(value.get('tags',[])):continue
                    meaning,credit,gloss_url=gloss(term)
                    if not meaning:continue
                    scope=clean(value.get('sense',''))
                    tags='、'.join(value.get('raw_tags',[])+value.get('tags',[]))
                    detail=pos+(' · 本组词义：'+scope if scope else ' · 原条目未标注具体义项')
                    if tags:detail+=' · '+tags
                    record=(term,meaning,detail+'；'+credit,gloss_url)
                    if record not in destination:destination.append(record)
        for kind,items in (('derived',family),('synonym',synonyms)):
            # Common terms first; keep sense-specific duplicate targets.
            items.sort(key=lambda t:(rank(dictionary.get(t[0],{'bnc':'','frq':''})),t))
            for term,meaning,detail,gloss_url in items[:24]:
                add(word,kind,term,meaning,detail+'；关系：中文维基词典 · CC BY-SA 4.0',wiki_url(word)+'\n'+gloss_url)

    # Explain only affixes actually present in a sourced etymology relation.
    # A component's general senses are not asserted to be this word's history.
    components=set()
    for w,relation,target,language,url in read_binary(RES/'etymology.bin.gz',5):
        if w not in selected or language not in ('en','eng','English'):continue
        kind=relation.split(' ')[0]
        if kind=='has_suffix':target='-'+target.lstrip('-')
        if kind=='has_prefix':target=target.rstrip('-')+'-'
        if target not in affixes or (w,target) in components:continue
        components.add((w,target))
        add(w,'component',target,'；'.join(affixes[target][:6]),
            '词缀条目的一般含义；请结合本词词源判断。释义：中文维基词典 · CC BY-SA 4.0；关联：etymology-db 2023 · CC BY-SA 3.0',wiki_url(target))

    forms=collections.defaultdict(set)
    for word in selected:
        forms[word].add(word)
        allowed=regular_forms(word)
        for part in dictionary.get(word,{}).get('exchange','').split('/'):
            if ':' not in part:continue
            tag,value=part.split(':',1)
            if tag not in {'p','d','i','3','s','r','t'}:continue
            for f in value.split(','):
                if f in allowed:forms[f].add(word)
        for e in wiki.get(word,[]):
            for f in e.get('forms',[]):
                value=f.get('form','')
                if value in allowed:forms[value].add(word)

    links=[line.strip().split('\t') for line in bz2.open(sources/'eng-cmn_links.tsv.bz2','rt',encoding='utf-8')]
    owners={}
    for line in (sources/'user_languages.csv').read_text(encoding='utf-8').splitlines():
        r=line.split('\t')
        if len(r)>=3 and r[0] in ('eng','cmn'):
            owners[(r[2],r[0])]=r[1]
    wanted_ids={i for pair in links for i in pair};tags=collections.defaultdict(set)
    with tarfile.open(sources/'tags.tar.bz2') as archive:
        with archive.extractfile('tags.csv') as stream:
            for line in stream:
                sid,tag=line.decode('utf-8').rstrip('\n').split('\t',1)
                if sid in wanted_ids:tags[sid].add(tag.casefold())
    def doubtful(sid):
        return any(t.startswith(('@check','@change','@delete','@needs','@wrong')) or t in
                   {'quote','ungrammatical','unnatural','not natural','wrong grammar','wrong translation','ambiguous','possibly unnatural'} for t in tags[sid])
    sentences={}
    for index,lang in ((0,'eng'),(1,'cmn')):
        wanted={r[index] for r in links}; entries={}
        for line in bz2.open(sources/f'{lang}_sentences_detailed.tsv.bz2','rt',encoding='utf-8'):
            r=line.rstrip('\n').split('\t')
            if len(r)>=4 and r[0] in wanted and r[3] not in ('','\\N'):
                entries[r[0]]=r
        sentences[lang]=entries
    candidates=collections.defaultdict(list)
    rejected=collections.Counter()
    pair_rejections=set()
    for line in (DATA/'example-rejections-v04.tsv').read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#'):
            eid,zid,_=line.split('\t',2);pair_rejections.add((eid,zid))
    # Conservative learning-corpus filter, not a claim of perfect review.
    unsuitable=re.compile(r'\b(?:fuck\w*|shit\w*|nigger\w*|porn\w*|rape[ds]?|suicid\w*)\b',re.I)
    for eid,zid in links:
        if (eid,zid) in pair_rejections:rejected['local_sample_review']+=1;continue
        e=sentences['eng'].get(eid);z=sentences['cmn'].get(zid)
        if not e or not z:rejected['missing_or_unowned']+=1;continue
        if doubtful(eid) or doubtful(zid):rejected['review_flags_or_quotation']+=1;continue
        english,chinese=e[2],z[2]
        tokens=TOKEN.findall(english)
        if not 4<=len(tokens)<=35 or len(english)>280 or not 4<=len(chinese)<=200:
            rejected['length']+=1;continue
        if not CJK.search(chinese) or CJK.search(english) or unsuitable.search(english) or any(x in english for x in ('http','@','[',']','{','}')):
            rejected['format_or_content']+=1;continue
        matches={}
        for token in tokens:
            if len(token)>1 and token.isupper():continue
            f=token.lower()
            for word in forms.get(f,[]):
                if word not in matches or f==word:matches[word]=f
        for word,form in matches.items():
            reviewed=('ok' in tags[eid] or 'list 907' in tags[eid])
            natives=int(owners.get((e[3],'eng'))=='5')+int(owners.get((z[3],'cmn'))=='5')
            score=(0 if word==form else 1,0 if reviewed else 1,2-natives,abs(len(tokens)-12),len(english),int(eid),int(zid))
            candidates[word].append((score,english,chinese,eid,zid,e[3],z[3],form))
    unique_pairs=set()
    for word,values in sorted(candidates.items()):
        seen_english=set(); taken=0
        for _,english,chinese,eid,zid,eauthor,zauthor,form in sorted(values):
            if english.casefold() in seen_english:continue
            seen_english.add(english.casefold());taken+=1;unique_pairs.add((eid,zid))
            add(word,'example',english,chinese,
                f'Tatoeba 社区译文 · 英文 #{eid} ({eauthor}) / 中文 #{zid} ({zauthor}) · CC BY 2.0 FR'+(f' · 例句词形：{form}' if form!=word else ''),
                f'https://tatoeba.org/en/sentences/show/{eid}\nhttps://tatoeba.org/en/sentences/show/{zid}')
            if taken==3:break
    rows=sorted({tuple(r) for r in rows})
    evidence={w:{'ielts':'ielts' in dictionary[w]['tag'].split(),'bnc':dictionary[w]['bnc'],'frq':dictionary[w]['frq']} for w in sorted(selected)}
    return {'selection':evidence,'rows':rows,'pinned_sha256':PINNED,
            'corpus_pairs_selected':len(unique_pairs),'filtered_pairs':dict(rejected),
            'transforms':['Whitespace normalization; no translation or script conversion.',
                          'Direct Tatoeba translation links only; 3 different English sentences per word.',
                          'Exclude pending check/change/delete/needs/wrong flags, ambiguous text and quotations; prefer OK/List 907 and self-declared native owners.',
                          'Exact token or source-attested regular/allowlisted inflection; no stemming.',
                          'Wiktionary derived terms may include compounds and phrases; label separately.',
                          'No Wiktionary quotations/examples copied; bilingual examples use Tatoeba.']}

def build(snapshot, check=False):
    rows=[list(row) for row in snapshot['rows']]
    practice_sentences=set()
    for line in (DATA/'practice-examples-v04.tsv').read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):continue
        w,en,zh=line.split('\t')
        if w not in snapshot['selection'] or not re.search(r'\b'+re.escape(w)+r'\b',en,re.I):
            raise ValueError('Invalid original example: '+w)
        if en in practice_sentences:raise ValueError('Duplicate original sentence')
        practice_sentences.add(en)
        # Keep the pinned resource byte-for-byte reproducible; LearningPanel updates this legacy display label.
        rows.append([w,'practice',en,zh,'拾词原创学习例句 · 非考试真题','拾词原创学习例句'])
    rows.sort()
    counts=collections.Counter();byword=collections.defaultdict(list)
    for row in rows:
        if len(row)!=6 or not all(isinstance(v,str) and v for v in row):raise ValueError('Invalid record')
        if row[1] not in {'example','practice','derived','synonym','etymology','component'}:raise ValueError('Invalid kind')
        if not CJK.search(row[3]) or (row[1]!='practice' and not row[5].startswith('https://')):raise ValueError('Missing Chinese/source')
        counts[row[1]]+=1;byword[row[0]].append(row)
    data=bytearray(b'WNS1'+struct.pack('>I',len(rows)))
    for row in rows:
        for field in row:
            b=field.encode('utf-8');data+=struct.pack('>I',len(b))+b
    binary=gzip.compress(data,mtime=0)
    coverage={w:{'example':0,'derived':False,'roots':False,'synonym':False,'chinese_root':False,'new':bool(byword[w])} for w in snapshot['selection']}
    for w,items in byword.items():
        for row in items:
            kind=row[1]
            if kind in ('example','practice'):coverage[w]['example']+=1
            elif kind in ('etymology','component'):coverage[w]['roots']=coverage[w]['chinese_root']=True
            else:coverage[w][kind]=True
    editorial=collections.defaultdict(list)
    for line in (RES/'learning-curated.tsv').read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#') or line.startswith('kind\t'):continue
        kind,w,*_=line.split('\t')
        editorial[w].append(kind)
    for alias,canonical in {'organize':'organise','recognize':'recognise','behavior':'behaviour','labor':'labour'}.items():
        if not editorial[alias]:editorial[alias]=editorial[canonical]
    for w,kinds in editorial.items():
        if w not in coverage:continue
        for kind in kinds:
            if kind=='example':coverage[w]['example']+=1
            elif kind in ('morphology','etymology'):coverage[w]['roots']=coverage[w]['chinese_root']=True
            elif kind in ('derived','synonym'):coverage[w][kind]=True
    for row in read_binary(RES/'lexical.bin.gz',7):
        word=row[0].strip().lower()
        if word not in coverage:continue
        if row[6]:coverage[word]['derived']=True
        if row[5]:coverage[word]['synonym']=True
    for row in read_binary(RES/'etymology.bin.gz',5):
        word=row[0].strip().lower()
        if word in coverage:coverage[word]['roots']=True
    totals={k:sum(bool(v[k]) for v in coverage.values()) for k in ('example','derived','roots','synonym','chinese_root','new')}
    totals['four_sections']=sum(all(v[k] for k in ('example','derived','roots','synonym')) for v in coverage.values())
    metadata={'version':'0.4.0','selected_words':len(coverage),'ielts_words':sum(v['ielts'] for v in snapshot['selection'].values()),
              'new_supplement_words':sum(bool(v) for v in byword.values()),'new_records':dict(counts),'combined_coverage':totals,
              'unique_tatoeba_pairs':snapshot['corpus_pairs_selected'],'resource_sha256':hashlib.sha256(binary).hexdigest(),
              'uncompressed_bytes':len(data),'sources_sha256':snapshot['pinned_sha256'],'transforms':snapshot['transforms']}
    metadata['ielts_coverage']={k:sum(bool(v[k]) and snapshot['selection'][w]['ielts'] for w,v in coverage.items())
                                for k in ('example','derived','roots','synonym','chinese_root','new')}
    header='word\tielts\tbnc\tfrq\tbilingual_examples\tfamily\troot_records\tsynonyms\tchinese_roots\tnew_material\n'
    lines=[]
    for w,v in sorted(coverage.items()):
        e=snapshot['selection'][w]
        lines.append('\t'.join(map(str,[w,int(e['ielts']),e['bnc'],e['frq'],v['example'],int(v['derived']),int(v['roots']),int(v['synonym']),int(v['chinese_root']),int(v['new'])])))
    artifacts={RES/'learning-open.bin.gz':binary,
               RES/'expanded-coverage.tsv':(header+'\n'.join(lines)+'\n').encode(),
               ROOT/'licenses/open-learning-source.json':(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n').encode()}
    report=f'''# WordNest 0.4.0 学习资料覆盖

本版按 ECDICT 的 BNC/FRQ 前 5000 或 ielts 标签选择 7,678 个词，加上保留卡片中未命中筛选的 6 个词，共 **{len(coverage):,} 个选词**。其中 **4,948 个**带 ielts 标签。这是第三方词库标签，不是雅思官方完整词表，也不保证考试覆盖率。

**实际新增资料覆盖 {metadata['new_supplement_words']:,} 个词头。** 选词数量与已补齐数量分开统计。下表只统计这个优先词表，不能相加，也不等于主词库 770,611 条都具有学习资料。

| 项目 | 合并原有资料后的词头数 | 其中带 ielts 标签 |
| --- | ---: | ---: |
| 至少一条英汉双语例句 | {totals['example']:,} | {metadata['ielts_coverage']['example']:,} |
| 派生、词基或词典派生词组关系 | {totals['derived']:,} | {metadata['ielts_coverage']['derived']:,} |
| 词源或构词记录 | {totals['roots']:,} | {metadata['ielts_coverage']['roots']:,} |
| 按义项组织的近义关系或已有中文辨析 | {totals['synonym']:,} | {metadata['ielts_coverage']['synonym']:,} |
| 词源中有中文说明或词缀释义 | {totals['chinese_root']:,} | {metadata['ielts_coverage']['chinese_root']:,} |

四项同时有资料的词头为 **{totals['four_sections']:,}**。这是“每栏至少有一项”的计数，不表示全部义项、全部同义词或历史词源均经过专业人工审定。词源栏目只有外文来源记录的词不计入中文词源说明；不同来源的记录可能有异说。

## 本次新增内容

- Tatoeba 双语例句展示记录 **{counts['example']:,}** 条，来自 **{snapshot['corpus_pairs_selected']:,}** 组不同的直接英汉翻译配对。同一句可帮助学习其中不同的词，所以展示记录数不是独立例句数。
- 原创双语学习例句 **{counts['practice']}** 条，针对常用雅思词的例句缺口；均标为原创，非真题。
- 中文维基词典词源说明 **{counts['etymology']:,}** 条、与实际词源关联的中文词缀释义 **{counts['component']:,}** 条。
- 中文词典派生/复合词/词组记录 **{counts['derived']:,}** 条、带词性和可用义项范围的近义关系 **{counts['synonym']:,}** 条。
- 原有 **355 张中文学习卡、710 条原创双语例句**全部保留；完整原卡清单见 CURATED_COVERAGE.md。四个原有英美拼写别名继续共用原卡，不增加原卡计数。

## 缺项与质量边界

优先词表中仍有 **{len(coverage)-totals['example']:,}** 个词没有中文例句；其余栏也非全部齐全。部分词没有实用派生词或可直接替换的近义词，不能仅凭缺项断定它们不存在。不用通用句式、拼写相似词或臆造词根填数。

社区语料保留原有简繁体。导入只用直接翻译链接，剔除待检查/修改/删除、歧义和引用标记，优先选取带 OK/List 907 标记的英文和自报母语的作者；这不是逐句专家审定，也不保证译文无误。人工抽查发现的 5 组不佳配对已排除。显示使用的词形，避免把 accumulated 当成另一个新词。

词缀栏目列出独立词缀词条的一般释义，不认定这些释义都适用于当前词。中文维基词典的 derived 项包含复合词和词组，界面与计数明确保留这一范围。普通 WordNet 关系仍按英文义项分组，不能当成每词都有详细中文近义辨析。

## 逐词核对与复现

便携目录中的 learning-coverage.tsv（源码中为 src/main/resources/expanded-coverage.tsv）列出所有选词、IELTS 标签、词频、例句数量和各栏覆盖标志；0 表示无对应资料。原生 EXE --self-check 会从实际加载的数据重新计算并逐行核对，不能只靠文档里的数量通过检查。

运行 `python scripts/prepare_open_learning.py --check` 可离线重建比较新增资源；运行 `python scripts/assemble_curated.py --check` 检查原有 355 张卡。来源快照哈希与处理说明见 licenses/open-learning-source.json 和 licenses/Open-learning-NOTICE.md。应用使用资料时无需联网。
'''
    artifacts[ROOT/'LEARNING_COVERAGE.md']=report.encode('utf-8')
    for path,content in artifacts.items():
        if check:
            if path.read_bytes()!=content:raise ValueError('Generated data changed: '+str(path))
        else:path.write_bytes(content)
    print(json.dumps(metadata,ensure_ascii=False,indent=2))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--sources',type=Path);parser.add_argument('--ecdict',type=Path);parser.add_argument('--check',action='store_true')
    args=parser.parse_args();path=DATA/'open-learning-selected.json.gz'
    if args.sources:
        if args.check or not args.ecdict:parser.error('Import needs --ecdict and must not use --check')
        snapshot=imported(args.sources,args.ecdict)
        path.write_bytes(gzip.compress(json.dumps(snapshot,ensure_ascii=False,separators=(',',':')).encode(),mtime=0))
    else:snapshot=json.loads(gzip.decompress(path.read_bytes()))
    build(snapshot,args.check)

if __name__=='__main__':main()
