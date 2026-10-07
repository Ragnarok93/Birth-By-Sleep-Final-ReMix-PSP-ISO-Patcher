#!/usr/bin/env python3
"""Release gate: require recorded gameplay evidence for the exact default payload."""
import hashlib
import json
import re
import struct
from pathlib import Path
import bbsfm_psp_bbs_stage5_patcher as p

SCENARIOS = (
    'boot_gameplay',
    'free_camera',
    'lock_on_camera',
    'right_stick',
    'scene_transitions',
    'save_load',
)


def profile_sha256():
    # Fingerprint the exact resident right-stick mutation set. Legacy Stage
    # 2/4/5 overlay blobs are deliberately excluded because the supported
    # profile never writes them.
    digest = hashlib.sha256()
    digest.update(b'psp-native-right-stick-v2\\0')
    digest.update(bytes.fromhex(p.SUPPORTED_SHA256))
    for va, expected, replacement, _desc in p.RIGHT_STICK_WORD_PATCHES:
        digest.update(struct.pack('<III', va, expected & 0xffffffff, replacement & 0xffffffff))
    for va, expected, replacement, _desc in p.RIGHT_STICK_BYTE_PATCHES:
        digest.update(struct.pack('<IBB', va, expected & 0xff, replacement & 0xff))
    return digest.hexdigest()


def validate(evidence):
    if evidence.get('profile_sha256') != profile_sha256():
        raise ValueError('Runtime evidence does not match the current resident right-stick profile')
    if evidence.get('source_eboot_sha256') != p.SUPPORTED_SHA256:
        raise ValueError('Runtime evidence uses an unsupported source EBOOT')
    if not re.fullmatch('[0-9a-f]{64}', evidence.get('patched_eboot_sha256') or ''):
        raise ValueError('Record the tested patched EBOOT SHA-256')
    for field in ['ppsspp_version', 'device', 'tested_at', 'evidence_reference']:
        if not evidence.get(field):
            raise ValueError('Missing runtime evidence field: ' + field)
    cases = evidence.get('cases', {})
    for character in ['Terra', 'Ventus', 'Aqua']:
        for scenario in SCENARIOS:
            case = cases.get(character, {}).get(scenario, {})
            if case.get('result') != 'pass' or not case.get('evidence_reference'):
                raise ValueError(f'Runtime validation pending: {character}/{scenario}')


if __name__ == '__main__':
    evidence = json.loads((Path(__file__).resolve().parents[1] / 'docs/runtime-evidence.json').read_text())
    try:
        validate(evidence)
    except ValueError as error:
        raise SystemExit(str(error))
