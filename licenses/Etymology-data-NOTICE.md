# Etymology relationship data attribution and license

The separate resource `etymology.bin.gz` is an adapted subset of **etymology-db**,
created by **droher and etymology-db contributors** from **Wiktionary contributors**.
It is distributed under **Creative Commons Attribution-ShareAlike 3.0 Unported
(CC BY-SA 3.0)**. This license applies to the adapted etymology data, separately
from the application code and other independently licensed datasets.

- Upstream project: https://github.com/droher/etymology-db
- Original dataset release: 2023-12 (upstream generated date: 2023-12-05)
- Exact downloaded asset: https://github.com/droher/etymology-db/releases/download/2023-12/etymology.csv.gz
- Upstream data-license declaration: https://github.com/droher/etymology-db/blob/master/README.md
- License: https://creativecommons.org/licenses/by-sa/3.0/
- Full license text: `Etymology-CC-BY-SA-3.0.txt`
- The upstream code license (Apache 2.0) is **not** the license for this data.
- Source content remains attributable to Wiktionary contributors. Each headword
  links to its English Wiktionary entry; the page history provides contributor history.

## Changes made for WordNest

- Keep English headwords present in the bundled ECDICT dictionary, ignoring case.
- Keep explicitly extracted ancestry and morphology relations; never infer from spelling.
- Exclude cognate_of, general comparison groups, and unstructured related-term pointers.
- Preserve nested derivation/affix context as Chinese bracket annotations on original relation types.
- Group positions are source order, not independently verified chronology or modern English segmentation.
- Deduplicate exact rows and sort by lowercased headword; retain source target spelling.
- Encode as WNE1 gzip. Leave repeated sourceURL fields empty; generate a Wiktionary headword link in the client.

No LLM-generated full-dictionary roots, spelling-match cognates or invented
historical paths were added. Chinese relation/context labels are original
editorial translations of the extracted structure, not quotations from Wiktionary.
This is a 2023 snapshot. Current Wiktionary pages may have changed. The upstream
author explicitly says that the extraction results have not been individually
validated. The data and the adaptation are provided without a warranty of accuracy.

## Format and measurements

- Format: gzip containing `WNE1`, big-endian 32-bit count, then five UTF-8 fields
  per row, each preceded by its big-endian 32-bit byte length.
- Fields: word, relation, target, language, sourceURL.
- sourceURL is empty to avoid duplicated storage; derive it as
  `https://en.wiktionary.org/wiki/{percent_encoded_original_headword}#English`.
- Rows: 309,779; unique lookup headwords: 134,158.
- Compressed bytes: 2,721,192; expanded bytes: 20,111,260.
- Source SHA-256: `e36eba331b73075fd74c8281b96eb3b31bebc510dad091ddeb815453a49d594d`
- Bundled dictionary SHA-256: `0eeddb7903a116161a8baef7b448b7673f377910b240003b8486f95e69e5a92a`
- Output SHA-256: `3007b7a9264c34078243a1082b90d73ede1d435e68dcfcd6d9ea233a340ac4ce`

For detailed type counts and limitations, read `etymology.metadata.json.gz`.
To reproduce, use `scripts/prepare_etymology.py` with the exact source asset above
and the bundled `dictionary.bin.gz`. The script never downloads automatically.
