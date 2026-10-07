#!/usr/bin/env python3
"""Release gate: require recorded gameplay evidence for the exact default payload."""
import hashlib
import json
import re
import struct
from pathlib import Path
import bbsfm_psp_bbs_stage5_patcher as p

SCENARIOS = (
    'boot_gameplay', 'free_camera', 'lock_on_camera', 'right_stick',
    'guard_square_circle', 'command_cancels', 'critical_mode',
    'scene_transitions', 'save_load', 'normal_combat_5_minutes',
)


def profile_sha256():
    # Include canonical injected code plus default feature byte and camera values.
    return hashlib.sha256(p.S2_BLOB + p.S4_BLOB + p.S5_WRAPPER_BLOB +
                          struct.pack('<BBff', 0xbc, 0, 4.5, 1.0)).hexdigest()


def validate(evidence):
    if evidence.get('profile_sha256') != profile_sha256():
        raise ValueError('Runtime evidence does not match the current default payload')
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
