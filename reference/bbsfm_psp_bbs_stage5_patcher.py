#!/usr/bin/env python3
"""KH Birth by Sleep Final Mix ULJM-05775 — PSP-native revalidation candidate.

Targets exactly the decrypted English-patched EBOOT fingerprint used during development.
This patcher contains patch data/injected code only; it does not contain game assets.

Supported runtime profiles:
- PSP-native right-stick camera through the game's existing Type-B camera path,
  using resident MainApp input capture modeled on TheOfficialFloW/RemasteredControls
- PSP-native camera distance/height through MainApp's resident player-camera
  parameter table; no injected runtime code or additional ELF segment

Dormant research code (not enabled by the application):
- Stage 4/5 PC-behavior-port payloads
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
# Legacy Stage 2 overlay payload addresses are retained below only for research parity.
# They are NOT used by the supported right-stick path. 0x08B6EE7C is the start
# of MainApp's dynamic .overlays arena, so code placed at 0x08B6EE80 can be
# overwritten by separately loaded PSP modules.
S2_FILE_OFF = 0x0036BE80
S2_VA = 0x08B6EE80
NEXT_ORIGINAL_LOAD_FILE_OFF = 0x0036C000

# PSP-native in-place right-stick path.
#
# PPSSPP exposes the second stick in CtrlData.analog[1], which occupies the two
# bytes historically named SceCtrlData.Rsrv[0]/Rsrv[1] at offsets 10/11.
# MainApp already reads four CtrlData records in its resident input routine.
# We capture the final sample's right-analog bytes into two otherwise-unreferenced
# padding bytes at 0x08B4199A/0x08B4199B. The pair is loaded as a halfword and
# XORed with 0x8080 so each axis is stored as a signed-centered byte. Tagged
# camera getters signed-load the centered value. X is negated to match the
# game's horizontal camera polarity; Y is left centered because the native
# vertical camera path already uses the opposite sign convention. Normal
# left-stick callers remain native.
RIGHT_STICK_MAGIC = 0x5253  # ASCII "RS"
RIGHT_STICK_X_BYTE_VA = 0x08B4199A
RIGHT_STICK_Y_BYTE_VA = 0x08B4199B
RIGHT_STICK_SELECTOR = 0x34045253  # ori a0,zero,0x5253

RIGHT_STICK_WORD_PATCHES = [
    # Raw X getter: default callers branch directly to the original left-X
    # getter at 0x088164D0; camera callers tagged with RIGHT_STICK_MAGIC read
    # the captured right-X byte instead.
    (0x088162F8, 0x27BDFFF0, 0x34035253, 'right-X selector magic'),
    (0x088162FC, 0xAFBF0000, 0x14830074, 'right-X default branch'),
    (0x08816300, 0x0E205934, 0x3C0208B4, 'right-X resident base'),
    (0x08816304, 0x00000000, 0x8042199A, 'right-X signed captured byte'),
    (0x08816308, 0x8FBF0000, 0x03E00008, 'right-X return'),
    (0x0881630C, 0x03E00008, 0x00021023, 'right-X invert in delay slot'),
    (0x08816310, 0x27BD0010, 0x00000000, 'right-X tail padding'),

    # Raw Y getter: same scheme, preserving the native left-Y getter at
    # 0x088164F8 for every untagged caller.
    (0x08816314, 0x27BDFFF0, 0x34035253, 'right-Y selector magic'),
    (0x08816318, 0xAFBF0000, 0x14830077, 'right-Y default branch'),
    (0x0881631C, 0x0E20593E, 0x3C0208B4, 'right-Y resident base'),
    (0x08816320, 0x00000000, 0x8042199B, 'right-Y signed captured byte'),
    (0x08816324, 0x8FBF0000, 0x03E00008, 'right-Y return'),
    (0x08816328, 0x03E00008, 0x00000000, 'right-Y preserve centered polarity'),
    (0x0881632C, 0x27BD0010, 0x00000000, 'right-Y tail padding'),

    # Capture analog[1][0/1] from the final CtrlData record as one aligned
    # halfword, flip bit 7 in each byte to form signed-centered axes, and store
    # both bytes together. The game's normal left analog state is preserved.
    (0x0881683C, 0x3C0408B4, 0x9545FFFA, 'capture right-stick XY halfword'),
    (0x08816840, 0x3C0508B4, 0x38A58080, 'center right-stick XY bytes'),
    (0x08816848, 0xACA71970, 0xAC871970, 'reuse resident input-state base'),
    (0x0881684C, 0x3C0408B4, 0xA485199A, 'store centered right-stick XY'),

    # RemasteredControls-equivalent camera selection.
    (0x08940FEC, 0x508000BA, 0x00000000, 'remove L modifier from camera'),
    (0x0898F68C, 0x1C80000B, 0x00000000, 'force Type-B horizontal camera'),
    (0x0898F850, 0x1C80000B, 0x00000000, 'force Type-B vertical camera'),

    # Keep the game's original JALs to 0x08816330/0x08816360; use their delay
    # slots to tag only these four camera calls as right-stick reads.
    (0x0898F6A0, 0x00000000, RIGHT_STICK_SELECTOR, 'right-stick X selector 1'),
    (0x0898F6E0, 0x00000000, RIGHT_STICK_SELECTOR, 'right-stick X selector 2'),
    (0x0898F864, 0x00000000, RIGHT_STICK_SELECTOR, 'right-stick Y selector 1'),
    (0x0898F8A4, 0x00000000, RIGHT_STICK_SELECTOR, 'right-stick Y selector 2'),
]

RIGHT_STICK_BYTE_PATCHES = []

# Dormant legacy overlay payload, retained only so historical payload-parity
# tooling can continue to reproduce prior research artifacts.
def _right_analog_helper(reserved_offset:int)->bytes:
    words = [
        0x27BDFFD0, 0xAFBF002C, 0x27A40010, 0x34050001, 0x0E2C5B4E,
        0x00000000, 0x1840000A, 0x00000000, 0x93A80000 | reserved_offset,
        0x2508FF80, 0x44880000, 0x46800020, 0x3C093C00, 0x44891000,
        0x46020002, 0x10000002, 0x00000000, 0x44800000, 0x8FBF002C,
        0x03E00008, 0x27BD0030,
    ]
    return struct.pack('<' + 'I' * len(words), *words)

S2_RIGHT_X_VA = S2_VA
S2_X_BLOB = _right_analog_helper(0x1A)
S2_RIGHT_Y_VA = S2_VA + len(S2_X_BLOB)
S2_Y_BLOB = _right_analog_helper(0x1B)
S2_BLOB = S2_X_BLOB + S2_Y_BLOB
S2_CODE_SIZE = len(S2_BLOB)
S2_NEW_SEGMENT_SIZE = (S2_FILE_OFF + len(S2_BLOB)) - 0x1018

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
# PSP player-camera resident parameter table.
# MainApp selects two 0x30-byte mode records at base+0x10 and base+0x40.
# Their first homogeneous position vector starts at record+0x10:
#   mode 1: [0.0, 1.5, -3.5, 1.0]
#   mode 2: [0.0, 1.0, -3.5, 1.0]
# Native camera setup passes these vectors directly to 0x08AE0AF0, so +0x14
# is Y/height and +0x18 is signed Z/distance.
CAMERA_TABLE_VA = 0x08B59F00
CAMERA_TABLE_SIGNATURE = 0x41435040
CAMERA_MODE1_RECORD_VA = 0x08B59F10
CAMERA_MODE2_RECORD_VA = 0x08B59F40
CAMERA_FREE_HEIGHT_VA = 0x08B59F24
CAMERA_FREE_DISTANCE_VA = 0x08B59F28
CAMERA_LOCK_HEIGHT_VA = 0x08B59F54
CAMERA_LOCK_DISTANCE_VA = 0x08B59F58
CAMERA_FREE_HEIGHT_ORIG = 1.5
CAMERA_LOCK_HEIGHT_ORIG = 1.0
CAMERA_FREE_DISTANCE_ORIG = -3.5
CAMERA_LOCK_DISTANCE_ORIG = -3.5
CAMERA_COPY_ROUTINE_VA = 0x0893DBF4
CAMERA_COPY_ROUTINE_SIZE = 0xB0
CAMERA_COPY_ORIGINAL_WORDS = (
    0x8CA60000,0x8CA70004,0xC4AC0008,0x44086000,0xAC860000,0xAC870004,0xAC880008,0xC4AD000C,
    0x44066800,0xAC86000C,0x8CA60010,0xAC860010,0xC4AE0014,0xE48E0014,0xC4AE0018,0xE48E0018,
    0x8CA6001C,0xAC86001C,0x24860020,0x24A70020,0xD8E00000,0xF8C00000,0x24860030,0x24A70030,
    0xD8E00000,0xF8C00000,0x8CA60040,0xAC860040,0xC4AC0044,0xE48C0044,0xC4AC0048,0xE48C0048,
    0x8CA6004C,0xAC86004C,0x24860050,0x24A70050,0xD8E00000,0xF8C00000,0x24860060,0x24A50060,
    0xD8A00000,0xF8C00000,0x03E00008,0x00801025,
)

FPS_TARGETS = (30,60,90,120)
FPS_SETTER_VA = 0x088074B0
FPS_FORCE_BRANCH_VA = 0x088074BC
FPS_RUNTIME_MODE_VA = 0x09F25EC8
FPS_RUNTIME_SCALAR_VA = 0x08B41870
FPS_SETTER_ORIGINAL_WORDS = (
    (0x088074B0,0x3C0609F2),
    (0x088074B4,0x8CC25EC8),
    (0x088074B8,0x34070001),
    (0x088074BC,0x14870006),
    (0x088074C0,0x3C0508B4),
    (0x088074C4,0x3C074000),
    (0x088074C8,0x44876000),
    (0x088074CC,0xE4AC1870),
    (0x088074D0,0x03E00008),
    (0x088074D4,0xACC45EC8),
    (0x088074D8,0x3C043F80),
    (0x088074DC,0x44846000),
    (0x088074E0,0x34040000),
    (0x088074E4,0xE4AC1870),
    (0x088074E8,0x03E00008),
    (0x088074EC,0xACC45EC8),
)
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

# RIGHT_STICK_WORD_PATCHES above is the supported camera patch map.

POST_INPUT_HOOK=(0x08816904,0x8FB00048)

def verify(data:bytes, camera_controls:bool, post_input:bool):
    if len(data)!=SUPPORTED_SIZE: raise ValueError(f'Unsupported size {len(data)}; expected {SUPPORTED_SIZE}')
    d=sha(data)
    if d!=SUPPORTED_SHA256: raise ValueError(f'Unsupported EBOOT SHA-256\n got: {d}\n exp: {SUPPORTED_SHA256}')
    if data[:4]!=b'\x7fELF': raise ValueError('Not an ELF')
    if u16(data,E_PHNUM_OFF)!=2: raise ValueError('Unexpected program-header count')
    if u32(data,PH0_FILESZ_OFF)!=OLD_SEGMENT_SIZE or u32(data,PH0_MEMSZ_OFF)!=OLD_SEGMENT_SIZE:
        raise ValueError('Unexpected LOAD #0 sizes')
    if any(data[THIRD_PHDR_OFF:THIRD_PHDR_OFF+0x20]): raise ValueError('Third phdr slot not empty')
    if camera_controls:
        # The supported path must remain entirely inside original resident code/data.
        # Program headers and .overlays are intentionally untouched.
        for va,exp,rep,desc in RIGHT_STICK_WORD_PATCHES:
            got=u32(data,foff(va))
            if got!=exp: raise ValueError(f'{desc} mismatch @0x{va:08X} got 0x{got:08X} exp 0x{exp:08X}')
        for va,exp,rep,desc in RIGHT_STICK_BYTE_PATCHES:
            got=data[foff(va)]
            if got!=exp: raise ValueError(f'{desc} mismatch @0x{va:08X} got 0x{got:02X} exp 0x{exp:02X}')
    if post_input:
        va,exp=POST_INPUT_HOOK; got=u32(data,foff(va))
        if got!=exp: raise ValueError(f'post-input hook mismatch @0x{va:08X} got 0x{got:08X} exp 0x{exp:08X}')
        if S4_VA+S5_SEGMENT_PAD>=ORIGINAL_LOAD1_VA: raise ValueError('Stage 5 VA overlaps original LOAD #1')

def _float_bits(value:float)->int:
    return struct.unpack('<I', struct.pack('<f', float(value)))[0]

def fps_timing_scalar(target_fps:int)->float:
    if target_fps==60: return 1.0
    if target_fps==90: return 2.0/3.0
    if target_fps==120: return 0.5
    raise ValueError(f'No patched timing scalar for {target_fps} FPS')

def verify_fps_source(data:bytes):
    for va,expected in FPS_SETTER_ORIGINAL_WORDS:
        got=u32(data,foff(va))
        if got!=expected:
            raise ValueError(f'frame-rate source mismatch @0x{va:08X}: 0x{got:08X} != 0x{expected:08X}')

def apply_fps(out:bytearray,target_fps:int):
    if target_fps not in (60,90,120):
        raise ValueError(f'Unsupported patched FPS target: {target_fps}')
    p32(out,foff(0x088074BC),0x10000006) # force native mode-0 path
    bits=_float_bits(fps_timing_scalar(target_fps))
    p32(out,foff(0x088074D8),0x3C040000 | ((bits>>16)&0xffff))
    p32(out,foff(0x088074DC),0x34840000 | (bits&0xffff))
    p32(out,foff(0x088074E0),0x44846000) # mtc1 a0,f12
    p32(out,foff(0x088074E4),0xE4AC1870) # swc1 f12,0x1870(a1)
    p32(out,foff(0x088074E8),0x03E00008) # jr ra
    p32(out,foff(0x088074EC),0xACC05EC8) # sw zero,0x5ec8(a2)

def camera_copy_routine(camera_distance_enabled:bool,camera_distance:float,camera_height_enabled:bool,camera_height:float):
    if not (camera_distance_enabled or camera_height_enabled):
        return list(CAMERA_COPY_ORIGINAL_WORDS)

    words=[
        0x00801025, # move v0,a0
        0x3408001C, # ori t0,zero,28 (0x70 bytes)
        0x8CA90000, # loop: lw t1,0(a1)
        0xAC890000, #       sw t1,0(a0)
        0x24A50004, #       addiu a1,a1,4
        0x24840004, #       addiu a0,a0,4
        0x2508FFFF, #       addiu t0,t0,-1
        0x1500FFFA, #       bnez t0,loop
        0x00000000,
    ]
    def emit_override(value,off1,off2):
        bits=_float_bits(value)
        words.extend([
            0x3C080000 | ((bits >> 16) & 0xffff),
            0x35080000 | (bits & 0xffff),
            0xAC480000 | off1,
            0xAC480000 | off2,
        ])
    if camera_height_enabled:
        emit_override(camera_height,0x24,0x54)
    if camera_distance_enabled:
        emit_override(-float(camera_distance),0x28,0x58)
    words.extend([0x03E00008,0x00000000])
    words.extend([0]*(len(CAMERA_COPY_ORIGINAL_WORDS)-len(words)))
    if len(words)!=len(CAMERA_COPY_ORIGINAL_WORDS):
        raise ValueError('camera copy replacement no longer fits in-place')
    return words

def verify_camera_geometry_source(data:bytes):
    if u32(data, foff(CAMERA_TABLE_VA)) != CAMERA_TABLE_SIGNATURE:
        raise ValueError('camera parameter table signature mismatch')
    if u32(data, foff(CAMERA_MODE1_RECORD_VA)) != 1:
        raise ValueError('camera mode 1 record mismatch')
    if u32(data, foff(CAMERA_MODE2_RECORD_VA)) != 2:
        raise ValueError('camera mode 2 record mismatch')
    expected = [
        (CAMERA_FREE_HEIGHT_VA, CAMERA_FREE_HEIGHT_ORIG, 'camera mode 1 height'),
        (CAMERA_FREE_DISTANCE_VA, CAMERA_FREE_DISTANCE_ORIG, 'camera mode 1 distance'),
        (CAMERA_LOCK_HEIGHT_VA, CAMERA_LOCK_HEIGHT_ORIG, 'camera mode 2 height'),
        (CAMERA_LOCK_DISTANCE_VA, CAMERA_LOCK_DISTANCE_ORIG, 'camera mode 2 distance'),
        (CAMERA_MODE1_RECORD_VA + 0x1C, 1.0, 'camera mode 1 vector W'),
        (CAMERA_MODE2_RECORD_VA + 0x1C, 1.0, 'camera mode 2 vector W'),
    ]
    for va,value,desc in expected:
        got=f32(data,foff(va))
        if abs(got-value)>1e-6:
            raise ValueError(f'{desc} mismatch @0x{va:08X}: {got:g}')
    for index,word in enumerate(CAMERA_COPY_ORIGINAL_WORDS):
        va=CAMERA_COPY_ROUTINE_VA+index*4
        got=u32(data,foff(va))
        if got!=word:
            raise ValueError(f'camera resource copier mismatch @0x{va:08X}: 0x{got:08X}')

def apply_camera_geometry(out:bytearray,camera_distance_enabled:bool,camera_distance:float,camera_height_enabled:bool,camera_height:float):
    if camera_distance_enabled:
        signed=-float(camera_distance)
        pf32(out,foff(CAMERA_FREE_DISTANCE_VA),signed)
        pf32(out,foff(CAMERA_LOCK_DISTANCE_VA),signed)
    if camera_height_enabled:
        pf32(out,foff(CAMERA_FREE_HEIGHT_VA),float(camera_height))
        pf32(out,foff(CAMERA_LOCK_HEIGHT_VA),float(camera_height))
    if camera_distance_enabled or camera_height_enabled:
        words=camera_copy_routine(camera_distance_enabled,camera_distance,camera_height_enabled,camera_height)
        off=foff(CAMERA_COPY_ROUTINE_VA)
        for index,word in enumerate(words):
            p32(out,off+index*4,word)

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

def patch(data:bytes,fps_target:int=30,camera_controls:bool=True,post_input:bool=False,cfg:int=0,camera_distance_enabled:bool=False,camera_distance:float=2.0,camera_height_enabled:bool=False,camera_height:float=1.0,critical_passives:bool=False)->bytearray:
    if fps_target not in FPS_TARGETS:
        raise ValueError('FPS target must be 30, 60, 90, or 120')
    if not (fps_target!=30 or camera_controls or camera_distance_enabled or camera_height_enabled):
        raise ValueError('Enable at least one PSP-native FPS/camera feature')
    if not 2.0 <= float(camera_distance) <= 6.0:
        raise ValueError('Camera distance must be between 2.0 and 6.0')
    if not 1.0 <= float(camera_height) <= 2.5:
        raise ValueError('Camera height must be between 1.0 and 2.5')
    if post_input or critical_passives or cfg:
        raise ValueError('Better Battle System runtime payloads remain disabled pending PSP-native re-derivation')
    verify(data,camera_controls,False)
    if fps_target!=30:
        verify_fps_source(data)
    if camera_distance_enabled or camera_height_enabled:
        verify_camera_geometry_source(data)
    out=bytearray(data)
    if fps_target!=30:
        apply_fps(out,fps_target)
    if camera_controls:
        for va,exp,rep,desc in RIGHT_STICK_WORD_PATCHES:
            p32(out,foff(va),rep)
        for va,exp,rep,desc in RIGHT_STICK_BYTE_PATCHES:
            out[foff(va)] = rep
    apply_camera_geometry(out,camera_distance_enabled,camera_distance,camera_height_enabled,camera_height)
    # Resident-only invariant: do not modify program-header layout or the legacy
    # overlay payload region.
    if u16(out,E_PHNUM_OFF)!=2:
        raise ValueError('PSP-native camera patch changed ELF program-header count')
    if u32(out,PH0_FILESZ_OFF)!=OLD_SEGMENT_SIZE or u32(out,PH0_MEMSZ_OFF)!=OLD_SEGMENT_SIZE:
        raise ValueError('PSP-native camera patch changed LOAD #0 size')
    if any(out[S2_FILE_OFF:S2_FILE_OFF+len(S2_BLOB)]):
        raise ValueError('PSP-native camera patch wrote into the dynamic overlay arena')
    return out

def main():
    ap=argparse.ArgumentParser(
        description='BBSFM ULJM-05775 PSP-native resident FPS/camera patcher'
    )
    ap.add_argument('input',type=Path,help='ORIGINAL decrypted English-patched EBOOT.BIN')
    ap.add_argument('output',nargs='?',type=Path)
    ap.add_argument('--fps',type=int,choices=FPS_TARGETS,default=30,help='target FPS: 30 stock, 60 native, 90/120 experimental')
    ap.add_argument('--no-right-stick',action='store_true',help='do not patch right-stick camera control')
    ap.add_argument('--camera-distance',type=float,default=None,metavar='VALUE',help='patch native camera distance (2.0-6.0)')
    ap.add_argument('--camera-height',type=float,default=None,metavar='VALUE',help='patch native camera height (1.0-2.5)')
    ap.add_argument('--verify-only',action='store_true')
    args=ap.parse_args()

    camera_controls=not args.no_right_stick
    distance_enabled=args.camera_distance is not None
    height_enabled=args.camera_height is not None
    data=args.input.read_bytes()
    verify(data,camera_controls,False)
    if args.fps!=30:
        verify_fps_source(data)
    if distance_enabled or height_enabled:
        verify_camera_geometry_source(data)
    print('Supported source fingerprint verified:',SUPPORTED_SHA256)
    print('Profile: resident PSP-native FPS/camera controls/geometry')
    print('ELF layout: unchanged (2 program headers; LOAD #0 is not extended)')
    print('Overlay arena: untouched')
    if args.verify_only:
        return 0

    out=patch(
        data,
        fps_target=args.fps,
        camera_controls=camera_controls,
        camera_distance_enabled=distance_enabled,
        camera_distance=args.camera_distance if distance_enabled else 2.0,
        camera_height_enabled=height_enabled,
        camera_height=args.camera_height if height_enabled else 1.0,
    )
    dst=args.output or args.input.with_name(args.input.stem + '.psp-native-mods.BIN')
    dst.write_bytes(out)
    print('Wrote:',dst)
    print('Patched SHA-256:',sha(out))
    print('Output size:',len(out))
    if args.fps!=30:
        print('FPS target:',args.fps,'timing scalar:',fps_timing_scalar(args.fps))
        if args.fps>60:
            print('WARNING: 90/120 FPS are experimental and may remain limited by PSP VBlank/PPSSPP presentation.')
    if camera_controls:
        print('PPSSPP: bind your physical right stick to Right Analog X/Y.')
    if distance_enabled:
        print('Camera distance:',args.camera_distance,'(native Z =',-args.camera_distance,')')
    if height_enabled:
        print('Camera height:',args.camera_height)
    return 0

if __name__=='__main__': raise SystemExit(main())

