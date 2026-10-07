#!/usr/bin/env python3
"""Generate/check Kotlin payload bytes and addresses from the Python oracle."""
import argparse
import re
from pathlib import Path
import bbsfm_psp_bbs_stage5_patcher as p

TARGET = Path(__file__).resolve().parents[1] / 'composeApp/src/commonMain/kotlin/com/ragnarok93/bbsremix/patch/Stage5Payloads.kt'


def synchronized(source):
    for kotlin, python in [('s2Blob', 'S2_BLOB'), ('s4Blob', 'S4_BLOB'), ('s5WrapperBlob', 'S5_WRAPPER_BLOB')]:
        pattern = r'(val ' + kotlin + r': ByteArray = hex\(\s*)"[0-9a-f]+"'
        source, count = re.subn(pattern, lambda m: m[1] + '"' + getattr(p, python).hex() + '"', source)
        if count != 1:
            raise ValueError('Missing/duplicate Kotlin payload: ' + kotlin)
    for name in re.findall(r'const val (\w+) = 0x[0-9A-Fa-f]+', source):
        python = name.replace('_OFFSET', '_OFF') if name.endswith('_OFFSET') else name
        value = getattr(p, python)
        source = re.sub(r'(const val ' + name + r' = )0x[0-9A-Fa-f]+', lambda m: m[1] + f'0x{value:08X}', source)
    return source


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = TARGET.read_text()
    generated = synchronized(source)
    if args.check:
        if generated != source:
            raise SystemExit('Kotlin payloads differ from the Python oracle; run reference/sync_payloads.py')
    else:
        TARGET.write_text(generated)
