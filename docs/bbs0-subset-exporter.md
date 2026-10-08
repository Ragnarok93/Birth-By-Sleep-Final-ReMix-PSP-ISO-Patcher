# BBS0 subset exporter

If BBS0.DAT is too large to upload, use a local Python 3.9+ interpreter.
This uses only the standard library and does not modify the game archive.

\`\`\`sh
python reference/export_bbs0_ui.py /path/to/BBS0.DAT -o bbs0-ui.zip
\`\`\`

For the smallest possible initial upload:

\`\`\`sh
python reference/export_bbs0_ui.py /path/to/BBS0.DAT -o bbs0-index.zip --metadata-only
\`\`\`

Upload the resulting ZIP, **not the original BBS0.DAT**.

The exporter includes the early BBSA index, a JSON manifest of sector-aligned
ARCs and embedded L2D/CTD files, and selected small L2D/CTD resources, capped
at 12 MiB uncompressed (adjust with \`--limit-mib N\`). It also records numeric
CTD layout values from standalone sector-aligned CTD blocks when detectable.
Hashes are recorded for validation, and all game bytes remain local except the
small index and resources you explicitly choose to share. No textures, models
or game executable are exported.

Limitations: a scan can miss assets accessed through the BBSA index or linked
CTD entries, and it is not a substitute for eventual runtime PPSSPP validation.
Try the full OpenKh BBSA index-0 extractor if an important resource is missing:
[OpenKh.Command.Bbsa](https://github.com/OpenKH/OpenKh/tree/master/OpenKh.Command.Bbsa).
Do not assume a download of BBS0.DAT from another game revision is compatible.

## Linked CTD payload resolution

The integrated exporter now reads the global BBSA directory records in the
BBS0 index (OpenKh's 12-byte file-hash/info/folder-hash entries), matches
ARC-linked `.ctd` references using CRC32 of the uppercase filename stem
and exact folder hash, and resolves *BBS0-resident* physical sectors.
Only validated `@CTD` version 1 payloads with bounded message/layout
tables are exported into `bbs0/ctd/`. SHA-256, size and offset are recorded
in `standalone_ctds`, while unresolved links are explicitly listed.
`--metadata-only` excludes CTD payloads from ZIP as expected.
References into BBS1–4, invalid magic and out-of-range offsets fail closed.

For the seven CTD names in the supplied October 2026 export, the global
BBSA index maps all seven to BBS0.DAT. The old ARC-only scanner could not
retrieve them. Re-exporting with the updated app now includes those CTDs
without transferring BBS0.DAT itself.
