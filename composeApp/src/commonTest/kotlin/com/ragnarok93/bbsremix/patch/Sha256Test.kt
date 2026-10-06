package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun empty_message_matches_standard_sha256_vector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun abc_matches_standard_sha256_vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }
}
