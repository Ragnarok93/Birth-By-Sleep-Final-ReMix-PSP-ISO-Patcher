#!/usr/bin/env python3
"""KH Birth by Sleep Final Mix ULJM-05775 — Birth By Sleep - Final ReMix Stage 5 conservative runtime candidate.

Targets exactly the decrypted English-patched EBOOT fingerprint used during development.
This patcher contains patch data/injected code only; it does not contain game assets.

Retained Stage 5 code (advanced writes below are disabled):
- direct PPSSPP right-stick camera through the game's existing Type-B camera path
- native player-camera distance override (default 4.5) using BBS zoom ratio fields; modes 1/2 only
- normal/free + lock-on player-camera height override (default 1.0), mapped from the PSP camera parameter table
- hit-aware attack/finisher cancels using player+0x23C low 2-bit attack-target state
- character-specific attack timing: Ven 20, Aqua 22, Terra 30 (+15 grounded)
- character-specific command timing: Ven 35, Aqua 40, Terra 45; motion 0x5F => 46
- guard / Square / grounded Circle cancel rules and Steam exclusion lists
- forced dodge/form-change/command-windup/Zantetsuken invincibility windows
- 64-frame telemetry ring for runtime auditing
- Critical Mode Reload Boost + Second Chance grants through the PSP runtime ability table
- Critical Mode Munny Plus + Berserk + Auto-Remedy + Double CP via player+0x30 mask 0x04008300

Advanced combat writes are disabled pending runtime validation. Structural
verification does not certify gameplay correctness. PSP BBS already
has native L+R+Start+Select soft reset. The archive's title/minigame helper scripts are
separate utilities whose executable logic lives in separately loaded PSP modules.
"""
from __future__ import annotations
import argparse, hashlib, struct
from pathlib import Path

SUPPORTED_SHA256 = "8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7"
SUPPORTED_SIZE = 3589832
VA_FILE_DELTA = 0x08803000
E_PHNUM_OFF = 0x2C
PH0_FILESZ_OFF = 0x44
PH0_MEMSZ_OFF = 0x48
OLD_SEGMENT_SIZE = 0x0036AE64
THIRD_PHDR_OFF = 0x74
S2_FILE_OFF = 0x0036BE80
S2_VA = 0x08B6EE80
NEXT_ORIGINAL_LOAD_FILE_OFF = 0x0036C000
# Camera-only replacement, assembled from labels to keep helper JALs and data
# addresses consistent. The original routine uses a0=pad buffer, a1=count.
# O32 outgoing argument area is sp+0..15; saved s0/ra live at +24/+28.
def build_camera_payload():
    words = []
    labels = {}
    branches = []
    data_refs = []
    def emit(word): words.append(word)
    def label(name): labels[name] = len(words) * 4
    def branch(word, target):
        branches.append((len(words), target))
        emit(word)
    def data_ref(word, target):
        data_refs.append((len(words), target))
        emit(word)

    label('capture')
    emit(0x27BDFFE0)  # addiu sp,sp,-32
    emit(0xAFBF001C)  # sw ra,28(sp)
    emit(0xAFB00018)  # sw s0,24(sp)
    emit(0x00808021)  # addu s0,a0,zero: actual pad-buffer pointer
    emit(0x0E2C5B4E)  # jal original controller routine, 0x08B16D38
    emit(0)           # delay slot
    emit(0x3C0808B7)  # lui t0,0x08B7 (signed low address)
    emit(0x24090080)  # neutral X on failed read
    emit(0x240A0080)  # neutral Y on failed read
    branch(0x18400000, 'store')  # blez v0,store
    emit(0)
    branch(0x12000000, 'store')  # beq s0,zero,store
    emit(0)
    emit(0x9209000A)  # lbu t1,10(s0)
    emit(0x920A000B)  # lbu t2,11(s0)
    label('store')
    data_ref(0xA1090000, 'x')
    data_ref(0xA10A0000, 'y')
    emit(0x8FB00018)  # restore s0
    emit(0x8FBF001C)  # restore ra; leave v0/v1 from original call untouched
    emit(0x03E00008)  # jr ra
    emit(0x27BD0020)  # delay slot: restore sp
    label('right_x')
    emit(0x3C0808B7)
    branch(0x10000000, 'convert')
    data_ref(0x91020000, 'x')  # branch delay slot
    label('right_y')
    emit(0x3C0808B7)
    data_ref(0x91020000, 'y')
    label('convert')
    # Original Type-B conversion: (unsigned byte - 128) * (1/128), f0 result.
    words.extend([0x2442FF80, 0x44820000, 0x46800020, 0x3C083C00,
                  0x44881000, 0x03E00008, 0x46020002])
    code_size = len(words) * 4
    labels['x'], labels['y'] = code_size, code_size + 1
    for index, target in branches:
        words[index] |= ((labels[target] - (index * 4 + 4)) // 4) & 0xffff
    for index, target in data_refs:
        words[index] |= (S2_VA + labels[target]) & 0xffff
    blob = struct.pack('<' + 'I' * len(words), *words) + bytes([128, 128])
    return blob, labels, code_size

S2_BLOB, S2_LABELS, S2_CODE_SIZE = build_camera_payload()
S2_NEW_SEGMENT_SIZE = (S2_FILE_OFF + len(S2_BLOB)) - 0x1018
S2_CAPTURE_PAD_VA = S2_VA + S2_LABELS['capture']
S2_RIGHT_X_VA = S2_VA + S2_LABELS['right_x']
S2_RIGHT_Y_VA = S2_VA + S2_LABELS['right_y']

S4_FILE_OFF = 0x0036D000
S4_VA = 0x08B70000
S4_BLOB = bytes.fromhex("4800b08fb608083cb0a4088d6d02001100000000a800088d6a02001100000000b708193c340a388f0400081300000000340a28afb708193c280a20a32002098d68020a954c000b8d256000000000804403006011000000001a006c85240060c500024d2d0800a0110000000000690a00b208193c44ae39272168b9010100ad910200001000000000ff000d24b708193c200a3993800038334700001300000000b6080f3cb0a4ef8d4100e01100000000ac00ef8d3e00e01100000000b708193c300a388f04000f1300000000300a2fafb708193c2c0a20a30e0018242300b811000000000f0018242000b81100000000140018241d003811000000001c01f88d0100192404001913000000000200192427001917000000003801f88d0080183323000017000000007802e4c500308044322006461e00014500000000b708193c240a26c7833104468002e6e58402e6e5002080447c02e4e5b708193c010018242c0a38a31400001000000000b708193c2c0a389310000013000000001c01f88d010019240400191300000000020019240700191700000000803f183c003098448002e6e58402e6e5002080447c02e4e5b708193c2c0a20a3b708193c200a39937f003833fd01001300000000b4080e3c7419ce8d3c020b8d03006b31b708193c200a3993400038330c00001300000000f2090f3c015fef91030018240700f81500000000f2090f3c54acef35ffe3183cffc118375400f8ad6800f8adb708193c200a399302003833440000130000000025c0000015000f241d002f110000000087000f241a008f110000000088000f2417008f110000000061000f2414008f110000000063000f2411008f11000000008f000f240e008f110000000058000f240b008f110000000065000f2420008f150000000070420f3c00108f443e00024603000145000000001900001000000000b7080f3c280af89110000017000000003402188d01001833080000170000000001001824280af8a13402188d01001837340218ad150000100000000002001824280af8a111000010000000003402188d01001837340218ad0c00001000000000b7080f3c280af89101000f2405000f17000000003402188dfeff0f2424c00f03340218adb7080f3c280ae0a1b7080f3cf009ef252c00e0ad1800182464013811000000001b00182461013811000000001c0018245e013811000000001d0018245b013811000000000e0018245801381100000000650018245501981100000000510018245201581100000000580018244f015811000000001d0018244c015811000000009200182449015811000000009300182446015811000000009400182443015811000000003b00182440015811000000006f0018243d01581100000000b708183c200a18932000183307000013000000000e0018243501b811000000000f0018243201b811000000003501c0110000000025c00000160019240900391500000000a040193c001099443c000246fc0001450000000000501837f900001000000000b708193c200a3993010039333a00201300000000100019242b003915000000000200601500000000001018370200192425007915000000005802199504002f2f0400e01500000000fdff3927fbff00100000000001000f2407002f130000000002000f2407002f1300000000f0410f3c0500001000000000a0410f3c0200001000000000b0410f3c00108f445000198d09002013000000006001398f01020f3c24c82f03040020130000000070410f3c00208f44801004463c100046020000450000000000401837020019240a007915000000005000198d06002013000000006001398f01020f3c24c82f03020020170000000000501837b708193c200a39930400393330002013000000000c0019241000391100000000100019240d00391100000000110019240a00391100000000130019240700391100000000140019240400391100000000040019241e0039150000000000801837100019240700391500000000010019240400791500000000ffff193cff7f393724c019032700192404009911000000002e001924020099150000000001801837008019330a002013000000005000198d07002013000000006001398f01020f3c24c82f030200201300000000002018370b001924050099150000000019001924020039110000000000f01837b708193c200a3993080039332a002013000000000400b92d2700201300000000150019242400391100000000140019242100391100000000190019241e003911000000005802199504002f2f0400e01500000000fdff3927fbff00100000000001000f2407002f130000000002000f2407002f130000000034420f3c05000010000000000c420f3c020000100000000020420f3c5f001924020099150000000038420f3c00108f443c000246020001450000000000501837010019331a002013000000005802199504002f2f0400e01500000000fdff3927fbff00100000000001000f2407002f130000000002000f2407002f130000000034420f3c05000010000000000c420f3c020000100000000020420f3c00108f443c000246020001450000000000401837feff192424c01903190019240700391500000000ffff193cff7f393724c01903ffff193cffdf393724c01903001019332200201300000000750019241c005911000000005c00192419005911000000006d00192416005911000000006200192413005911000000006300192410005911000000006e0019240d005911000000005d0019240a005911000000006f0019240700591100000000700019240400591100000000790019240400591500000000ffff193cffef393724c01903820019240400591500000000ffff193cffbf393724c01903002019330c002013000000005000198d06002013000000006001398f01020f3c24c82f030400201700000000ffff193cffdf393724c0190324c8d80120002013000000005000198d0f002013000000006001398f01020f3c24c82f030a0020130000000001001924200219adb7080f3cf009ef252c00f98d010039372c00f9ad0f0000100000000004001924200219adb7080f3cf009ef252c00f98d010039372c00f9ad0600001000000000b7080f3cf009ef252c00f88d040018372c00f8adb708193c200a3993100039332e00201300000000b7080f3cf009ef250800e9ad2c0219850c00f9a52e0219850e00f9a5580219951000f9a51200eaa51400eda11500eba11600eca51800eead3802198d1c00f9ad3c02198d2000f9ad2400e0e53402198d2800f9adb7080f3c380af98d010039273f003933380af9ad40c91900b7080f3c400aef252178f9010000e9ad580219950400f9a50600eaa50800eda10900eba10a00eca50c00eead3802198d1000f9ad3c02198d1400f9ad1800e0e53402198d1c00f9ad0800e00300000000b708193c280a20a3b708193c340a20afb708193c2c0a20a3b708193c300a20af0800e0030000000000000000424253340200040000000000000000000000000000000000000000000000000000000000000000000000000000000000ff0000000000904000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000080")
S4_POST_INPUT_VA = 0x08B70000
S4_TELEMETRY_VA = 0x08B709F0
S4_CONFIG_VA = 0x08B70A20
S4_CONFIG_OFF = S4_CONFIG_VA - S4_VA
S4_CAMERA_DISTANCE_VA = 0x08B70A24
S4_CAMERA_DISTANCE_OFF = S4_CAMERA_DISTANCE_VA - S4_VA
S4_RING_VA = 0x08B70A40
S4_RING_RECORD_SIZE = 0x20
S4_RING_RECORDS = 64
ORIGINAL_LOAD1_VA = 0x08BB4780

# Stage 5 wrapper: calls the statically inspected Stage 4.1 post-input routine first, then
# applies the Steam Critical-mode passive/enchant mask to the current PSP
# player object only when the loaded difficulty mirror equals Critical (3).
S5_WRAPPER_VA = 0x08B71280
S5_WRAPPER_OFF = S5_WRAPPER_VA - S4_VA
S5_PASSIVES_CONFIG_VA = 0x08B71300
S5_PASSIVES_CONFIG_OFF = S5_PASSIVES_CONFIG_VA - S4_VA
S5_SEGMENT_PAD = 0x1320
S5_CRITICAL_PASSIVE_MASK = 0x04008300  # Munny Plus|Berserk|Auto-Remedy|Double CP
S5_WRAPPER_BLOB = bytes.fromhex(
    "00c02d0e00000000b708083c00130991010029311200201100000000b608083cb0a4088d0e00001100000000a800088d0b00001100000000f209093c015f299103000a2406002a15000000003000098d00040a3c00834a3525482a01300009ad425a200a00000000"
)
# PSP player-camera static parameter blocks. Free/normal mode uses base+0x10,
# lock-on uses base+0x40: the two blocks are exactly 0x30 bytes apart.
# +0x14 is the vertical camera target/height parameter.
CAMERA_FREE_HEIGHT_VA = 0x08B59F24
CAMERA_LOCK_HEIGHT_VA = 0x08B59F54
CAMERA_FREE_HEIGHT_ORIG = 1.5
CAMERA_LOCK_HEIGHT_ORIG = 1.0
NOP = 0

def jal(target:int)->int:
    return 0x0C000000 | ((target >> 2) & 0x03FFFFFF)
def foff(va:int)->int:
    return va - VA_FILE_DELTA
def u16(b,off): return struct.unpack_from('<H',b,off)[0]
def u32(b,off): return struct.unpack_from('<I',b,off)[0]
def p16(b,off,v): struct.pack_into('<H',b,off,v & 0xffff)
def p32(b,off,v): struct.pack_into('<I',b,off,v & 0xffffffff)
def f32(b,off): return struct.unpack_from('<f',b,off)[0]
def pf32(b,off,v): struct.pack_into('<f',b,off,float(v))
def sha(b): return hashlib.sha256(b).hexdigest()

CAMERA_PATCHES = [
 (0x08816688,0x0E2C5B4E,jal(S2_CAPTURE_PAD_VA),'capture PPSSPP second analog'),
 (0x08940FEC,0x508000BA,NOP,'remove L modifier from camera'),
 (0x0898F68C,0x1C80000B,NOP,'force Type-B horizontal camera'),
 (0x0898F850,0x1C80000B,NOP,'force Type-B vertical camera'),
 (0x0898F69C,0x0E2058CC,jal(S2_RIGHT_X_VA),'right-stick X read 1'),
 (0x0898F6DC,0x0E2058CC,jal(S2_RIGHT_X_VA),'right-stick X read 2'),
 (0x0898F860,0x0E2058D8,jal(S2_RIGHT_Y_VA),'right-stick Y read 1'),
 (0x0898F8A0,0x0E2058D8,jal(S2_RIGHT_Y_VA),'right-stick Y read 2'),
]
POST_INPUT_HOOK=(0x08816904,0x8FB00048)

def verify(data:bytes, camera_controls:bool, post_input:bool):
    if not (camera_controls or post_input): raise ValueError('Nothing selected')
    if len(data)!=SUPPORTED_SIZE: raise ValueError(f'Unsupported size {len(data)}; expected {SUPPORTED_SIZE}')
    d=sha(data)
    if d!=SUPPORTED_SHA256: raise ValueError(f'Unsupported EBOOT SHA-256\n got: {d}\n exp: {SUPPORTED_SHA256}')
    if data[:4]!=b'\x7fELF': raise ValueError('Not an ELF')
    if u16(data,E_PHNUM_OFF)!=2: raise ValueError('Unexpected program-header count')
    if u32(data,PH0_FILESZ_OFF)!=OLD_SEGMENT_SIZE or u32(data,PH0_MEMSZ_OFF)!=OLD_SEGMENT_SIZE:
        raise ValueError('Unexpected LOAD #0 sizes')
    if any(data[THIRD_PHDR_OFF:THIRD_PHDR_OFF+0x20]): raise ValueError('Third phdr slot not empty')
    if camera_controls:
        end=S2_FILE_OFF+len(S2_BLOB)
        if end>NEXT_ORIGINAL_LOAD_FILE_OFF: raise ValueError('Camera blob overlaps LOAD #1')
        if any(data[S2_FILE_OFF:end]): raise ValueError('Camera cave is not zero-filled')
        for va,exp,rep,desc in CAMERA_PATCHES:
            got=u32(data,foff(va))
            if got!=exp: raise ValueError(f'{desc} mismatch @0x{va:08X} got 0x{got:08X} exp 0x{exp:08X}')
    if post_input:
        va,exp=POST_INPUT_HOOK; got=u32(data,foff(va))
        if got!=exp: raise ValueError(f'post-input hook mismatch @0x{va:08X} got 0x{got:08X} exp 0x{exp:08X}')
        if S4_VA+S5_SEGMENT_PAD>=ORIGINAL_LOAD1_VA: raise ValueError('Stage 5 VA overlaps original LOAD #1')

def verify_camera_height(data:bytes):
    free = f32(data, foff(CAMERA_FREE_HEIGHT_VA))
    lock = f32(data, foff(CAMERA_LOCK_HEIGHT_VA))
    if abs(free - CAMERA_FREE_HEIGHT_ORIG) > 1e-6:
        raise ValueError(f'free-camera height mismatch @0x{CAMERA_FREE_HEIGHT_VA:08X}: {free:g}')
    if abs(lock - CAMERA_LOCK_HEIGHT_ORIG) > 1e-6:
        raise ValueError(f'lock-on height mismatch @0x{CAMERA_LOCK_HEIGHT_VA:08X}: {lock:g}')

def make_config(args, combat:bool, camera_distance:bool)->int:
    cfg=0x3C if combat else 0x00  # conservative cancels + exclusions + telemetry
    if combat:
        if args.strict_steam: cfg &= ~0x20
        if args.no_hit_aware: cfg &= ~0x01
        if args.no_iframes: cfg &= ~0x02
        if args.no_extended_defense: cfg &= ~0x04
        if args.no_command_cancel: cfg &= ~0x08
        if args.no_telemetry: cfg &= ~0x10
        if args.no_critical_abilities: cfg &= ~0x40
    if camera_distance: cfg |= 0x80
    return cfg

def add_stage5(out:bytearray,cfg:int,camera_distance:float,critical_passives:bool):
    if cfg & 0x43 or critical_passives:
        raise ValueError('Advanced combat writes are disabled pending runtime evidence')
    blob=bytearray(S4_BLOB)
    blob[S4_CONFIG_OFF]=cfg & 0xff
    struct.pack_into('<f',blob,S4_CAMERA_DISTANCE_OFF,float(camera_distance))
    if len(blob)>S5_WRAPPER_OFF:
        raise ValueError('Stage 4.1 payload unexpectedly overlaps Stage 5 wrapper')
    blob.extend(b'\0'*(S5_WRAPPER_OFF-len(blob)))
    blob.extend(S5_WRAPPER_BLOB)
    if len(blob)>S5_PASSIVES_CONFIG_OFF:
        raise ValueError('Stage 5 wrapper overlaps passive config byte')
    blob.extend(b'\0'*(S5_PASSIVES_CONFIG_OFF-len(blob)))
    blob.append(1 if critical_passives else 0)
    if len(blob)<S5_SEGMENT_PAD:
        blob.extend(b'\0'*(S5_SEGMENT_PAD-len(blob)))
    ph=struct.pack('<IIIIIIII',1,S4_FILE_OFF,S4_VA,S4_VA,len(blob),len(blob),7,0x1000)
    out[THIRD_PHDR_OFF:THIRD_PHDR_OFF+0x20]=ph
    p16(out,E_PHNUM_OFF,3)
    if len(out)<S4_FILE_OFF: out.extend(b'\0'*(S4_FILE_OFF-len(out)))
    out.extend(blob)

def patch(data:bytes,camera_controls:bool,post_input:bool,cfg:int,camera_distance:float,camera_height_enabled:bool,camera_height:float,critical_passives:bool)->bytearray:
    verify(data,camera_controls,post_input)
    if camera_height_enabled: verify_camera_height(data)
    out=bytearray(data)
    if camera_controls:
        p32(out,PH0_FILESZ_OFF,S2_NEW_SEGMENT_SIZE); p32(out,PH0_MEMSZ_OFF,S2_NEW_SEGMENT_SIZE)
        out[S2_FILE_OFF:S2_FILE_OFF+len(S2_BLOB)]=S2_BLOB
        for va,exp,rep,desc in CAMERA_PATCHES: p32(out,foff(va),rep)
    if post_input:
        p32(out,foff(POST_INPUT_HOOK[0]),jal(S5_WRAPPER_VA))
        add_stage5(out,cfg,camera_distance,critical_passives)
    if camera_height_enabled:
        pf32(out,foff(CAMERA_FREE_HEIGHT_VA),camera_height)
        pf32(out,foff(CAMERA_LOCK_HEIGHT_VA),camera_height)
    return out

def main():
    ap=argparse.ArgumentParser(description='BBSFM ULJM-05775 Birth By Sleep - Final ReMix Stage 5 + right-stick + camera distance/height patcher')
    ap.add_argument('input',type=Path,help='ORIGINAL decrypted EBOOT.BIN')
    ap.add_argument('output',nargs='?',type=Path)
    mode=ap.add_mutually_exclusive_group()
    mode.add_argument('--camera-only',action='store_true',help='Right-stick + camera distance/height only; no combat changes')
    mode.add_argument('--combat-only',action='store_true',help='Combat only; no right-stick or camera distance/height changes')
    ap.add_argument('--camera-distance',type=float,default=4.5,metavar='DIST',help='Absolute normal-player-camera distance; default 4.5')
    ap.add_argument('--no-camera-distance',action='store_true',help='Disable camera-distance override')
    ap.add_argument('--camera-height',type=float,default=1.0,metavar='HEIGHT',help='Normal + lock-on camera height; default 1.0 (Steam mod target)')
    ap.add_argument('--no-camera-height',action='store_true',help='Disable camera-height override')
    ap.add_argument('--strict-steam',action='store_true',help='Disable extra Finish/Event/Network/Shootlock category guard')
    ap.add_argument('--no-hit-aware',action='store_true')
    ap.add_argument('--no-iframes',action='store_true')
    ap.add_argument('--no-extended-defense',action='store_true')
    ap.add_argument('--no-command-cancel',action='store_true')
    ap.add_argument('--no-telemetry',action='store_true')
    ap.add_argument('--no-critical-abilities',action='store_true',help='Compatibility flag; advanced writes are already disabled')
    ap.add_argument('--no-critical-passives',action='store_true',help='Compatibility flag; advanced writes are already disabled')
    ap.add_argument('--verify-only',action='store_true')
    args=ap.parse_args()

    if not (1.0 <= args.camera_distance <= 12.0):
        ap.error('--camera-distance must be between 1.0 and 12.0')
    if not (0.0 <= args.camera_height <= 4.0):
        ap.error('--camera-height must be between 0.0 and 4.0')

    camera_controls=not args.combat_only
    combat=not args.camera_only
    camera_distance=(not args.no_camera_distance) and (not args.combat_only)
    camera_height_enabled=(not args.no_camera_height) and (not args.combat_only)
    post_input=combat or camera_distance
    cfg=make_config(args,combat,camera_distance)
    critical_passives = False  # disabled until PSP ownership/state semantics are demonstrated

    data=args.input.read_bytes(); verify(data,camera_controls,post_input)
    if camera_height_enabled: verify_camera_height(data)
    print('Supported source fingerprint verified:',SUPPORTED_SHA256)
    print('Profile: conservative candidate; gameplay validation pending')
    print('Right-stick camera:', 'ON' if camera_controls else 'off')
    print('Camera distance:', f'ON ({args.camera_distance:g})' if camera_distance else 'off')
    print('Camera height:', f'ON ({args.camera_height:g})' if camera_height_enabled else 'off')
    print('Stage 5 combat:', 'ON' if combat else 'off')
    print('Critical passives:', 'ON (0x04008300)' if critical_passives else 'off')
    if post_input:
        print(f'Config byte: 0x{cfg:02X} @ VA 0x{S4_CONFIG_VA:08X}')
        print(f'Stage 5 wrapper: 0x{S5_WRAPPER_VA:08X}; passive config: 0x{S5_PASSIVES_CONFIG_VA:08X}')
        if camera_distance:
            print(f'Camera distance value: {args.camera_distance:g} @ VA 0x{S4_CAMERA_DISTANCE_VA:08X}')
        if cfg & 0x10:
            print(f'Telemetry snapshot: 0x{S4_TELEMETRY_VA:08X}; ring: 0x{S4_RING_VA:08X}')
    if args.verify_only: return 0

    out=patch(data,camera_controls,post_input,cfg,args.camera_distance,camera_height_enabled,args.camera_height,critical_passives)
    if args.output:
        dst=args.output
    else:
        suffix='.bbs-stage5.BIN' if combat and camera_controls else '.stage5-combat.BIN' if combat else '.camera-stage5.BIN'
        if camera_controls and not post_input: suffix='.rightstick.BIN'
        dst=args.input.with_name(args.input.stem + suffix)
    dst.write_bytes(out)
    print('Wrote:',dst)
    print('Patched SHA-256:',sha(out))
    print('Output size:',len(out))
    if camera_controls: print('PPSSPP: bind your physical right stick to Right Analog X/Y.')
    return 0
if __name__=='__main__': raise SystemExit(main())

