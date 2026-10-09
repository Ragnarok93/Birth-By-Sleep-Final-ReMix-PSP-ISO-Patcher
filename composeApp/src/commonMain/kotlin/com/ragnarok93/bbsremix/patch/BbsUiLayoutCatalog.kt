package com.ragnarok93.bbsremix.patch

import com.ragnarok93.bbsremix.iso.Iso9660Reader
import com.ragnarok93.bbsremix.iso.IsoBytePatch
import com.ragnarok93.bbsremix.iso.IsoFormatException
import com.ragnarok93.bbsremix.iso.IsoImageInfo
import okio.Path

/**
 * Source-fingerprinted catalog from the supplied plaintext BBS0/BBS1/BBS3 archives.
 *
 * Offsets are in each DAT, not the ISO. An entry proves only that a bounded
 * asset is known and byte-authenticated; complete renderer/UI coverage and
 * SQ2 animated key geometry still require visual PPSSPP validation.
 */
internal object BbsUiLayoutCatalog {
    data class Candidate(
        val element: UiScaleElement,
        val archive: String,
        val archiveSize: Long,
        val layout: String,
        val offsetInArchive: Long,
        val layoutSize: Int,
        val originalSha256: String,
        val digestArchive: String = archive,
        val digestOffset: Long = offsetInArchive,
    )

    val suppliedCandidates: List<Candidate> = listOf(
        Candidate(UiScaleElement.COMMAND_DECK, "BBS1.DAT", BBS1_SIZE, "comm_00.l2d",
            0xDDE1D0, 35856, "ddd889601e296fce30af8ae5ac8c11ab5fb6d5cb0e65769cb4a2bdcaae9cd2f8"),
        Candidate(UiScaleElement.COMMAND_DECK, "BBS1.DAT", BBS1_SIZE, "comm_01.l2d",
            0xE018E0, 356608, "ad1f61e62a08ff8cceec61ac8bd5259c3581f5410254e2d4fca3275e7c60032b"),
        Candidate(UiScaleElement.SHOTLOCK, "BBS1.DAT", BBS1_SIZE, "shot_00.l2d",
            0x15854C0, 73536, "d4a5a6cfb751c7841350b5cb038858cf2f1d7b419c372a78aaf16c50c3df2941"),
        Candidate(UiScaleElement.SHOTLOCK, "BBS1.DAT", BBS1_SIZE, "shot_02.l2d",
            0x15B0250, 26640, "787aaebe9a5b085cd3e72338c3f9e40879a5e78e48466bc2837a998c9de52bd3"),
        Candidate(UiScaleElement.GAUGES, "BBS1.DAT", BBS1_SIZE, "gauge_01.l2d",
            0x159F060, 70128, "b72c694db963838a4b7b29b32e802bf7fdce26507b021ae6cfbfc424749d15b8"),
        Candidate(UiScaleElement.MENUS, "BBS1.DAT", BBS1_SIZE, "pause.l2d",
            0xC392830, 16272, "0e4d0eae7b98f54a0de5c2ee952dcbfc0d83f042721e970eb3da8b1074f808de"),
        Candidate(UiScaleElement.MENUS, "BBS1.DAT", BBS1_SIZE, "camp.l2d",
            0xC2F6A50, 518320, "20db310957d3296bbceb326d938e1feb750b38f8dcf1f498900a15f118411983"),
        // Verified additional geometry assets from the provided BBS archives.
        // Exact offsets and SHA-256 preimages prevent cross-region corruption.
        Candidate(UiScaleElement.COMBAT_HUD, "BBS0.DAT", 754655232L, "wind_00.l2d",
            153870384L, 98368, "2add00f3703682efa724d6e2a6e6291344cc44fd911f068f2cb88d71f3dbcefc"),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "wind_00.l2d",
            117104L, 41088, "52622079cf63f7d2b66557c5bac49a4085022b5d1d869b9f7d32ccee661da759"),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "info_up_00.l2d",
            6127184L, 21696, "ad8fdbefc2b347ddf72cc16d23794c977ed9996db31af9ae767c1a8ea18810cd"),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "s_cursor.l2d",
            23001328L, 21488, "1dd134efcb8f71c1944e9526045e3e9372ebb3756d1928308d5f2d2c2b541573"),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "iw_00.l2d",
            14608080L, 39344, "3f8488eea7bfa9f89f6e20e5b6855a33ecf57302118c2068089507bd1b0e13f0"),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "num_01.l2d",
            22639920L, 31536, "acc03155377d8745fdd395d5a91b334ff48e4e6864ca848dce9b5ecbe318cbd2"),
        Candidate(UiScaleElement.COMMAND_DECK, "BBS1.DAT", BBS1_SIZE, "comm_02.l2d",
            45380304L, 125360, "1b1c40de5ad7b5a5d29026eb9801c97d6c2aa34f5577ad6fb2c280f1530e0f8c"),
        Candidate(UiScaleElement.COMMAND_DECK, "BBS1.DAT", BBS1_SIZE, "comm_03.l2d",
            52150320L, 99840, "e1ce553449b3e0b4c6c4d79ffad91c45c0d82f253377236defada1dfe76a51cf"),
        Candidate(UiScaleElement.COMMAND_DECK, "BBS1.DAT", BBS1_SIZE, "comm_04.l2d",
            11834640L, 74016, "fe312b1ceefcb3557ecdc58abe6210cd42d14b59d11d9e44f5be80e63762c647"),
        Candidate(UiScaleElement.GAUGES, "BBS1.DAT", BBS1_SIZE, "s_gage00.l2d",
            11817008L, 11456, "f4dcf1df1fb31d6ac56843d12322205a95c517be8032203bdd08af9b84bbd0a1"),
        Candidate(UiScaleElement.GAUGES, "BBS1.DAT", BBS1_SIZE, "m_gage_02.l2d",
            92522544L, 10720, "3925d431344bc1dae23b74f23bf78842ba3dcc373641bddd6f80762d06ec0482"),
        Candidate(UiScaleElement.GAUGES, "BBS1.DAT", BBS1_SIZE, "m_gage_00.l2d",
            180066352L, 11968, "6e4542853643ffaead8aca1e009efb2907f4d556eaf529900c2d576c8af2a844"),
        Candidate(UiScaleElement.GAUGES, "BBS1.DAT", BBS1_SIZE, "voltage_00.l2d",
            9134128L, 20608, "4fe98bc0a997dacd6dc4eebcbeae83f92ff5ce411c536966089b925d7b2bf651"),
        Candidate(UiScaleElement.PORTRAITS, "BBS1.DAT", BBS1_SIZE, "cface_10.l2d",
            14676032L, 10400, "b64c91f1bacd33746dc2ad8be6f9ed979c4565114b51683a6daebdacc51158c3"),
        Candidate(UiScaleElement.PORTRAITS, "BBS1.DAT", BBS1_SIZE, "cface_11.l2d",
            14657824L, 10400, "6a9e0d40ae43d49098ac644340f84d2c324b5dc6a39c0b5225b4d4bf02f823cb"),
        Candidate(UiScaleElement.PORTRAITS, "BBS1.DAT", BBS1_SIZE, "cface_12.l2d",
            14647424L, 10400, "e58369a8d910e28addd3ca35d1a7e296803c6ef812999cbb4bfc35d320c31bf1"),
        Candidate(UiScaleElement.MENUS, "BBS0.DAT", 754655232L, "pause.l2d",
            156605248L, 16272, "0e4d0eae7b98f54a0de5c2ee952dcbfc0d83f042721e970eb3da8b1074f808de"),
        Candidate(UiScaleElement.MENUS, "BBS0.DAT", 754655232L, "info_00.l2d",
            156621520L, 8688, "91f7ac81a03c86992487f7f453c43b2d8f7b24ae4ec75e4912295bac95c0dd88"),
        Candidate(UiScaleElement.MENUS, "BBS0.DAT", 754655232L, "t_menu.l2d",
            164623168L, 31296, "01393d2f8b3e8597c843454c6cb2b972e0da265ba9464346bd9c21ad62bfc381"),
        Candidate(UiScaleElement.MENUS, "BBS1.DAT", BBS1_SIZE, "c_help.l2d",
            205017168L, 17312, "a03e840c74c7b8cf91375c6d0a228be8dfa0139782dcba2edbfba18c69f150a0"),
        Candidate(UiScaleElement.MENUS, "BBS1.DAT", BBS1_SIZE, "c_icon_00.l2d",
            204970528L, 34944, "b52113745660e74ca7926261f2dea7bf341228625727fe95ab5cab29b4a6c6e4"),
        Candidate(UiScaleElement.MENUS, "BBS3.DAT", 206391296L, "t_menu.l2d",
            128612640L, 31296, "4c644369254782fa1baa510f76b88b5f9e843d7783505232627f48021ca0dac8"),
        // Independent archive entries may load the same *identical* layout
        // bytes in a different world/scenario. All aliases inherit their
        // known source's scale profile but have their own physical ISO offset.
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "wind_00.l2d",
            1403120L, 41088, "52622079cf63f7d2b66557c5bac49a4085022b5d1d869b9f7d32ccee661da759",
            digestOffset = 117104L),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "wind_00.l2d",
            2744560L, 41088, "52622079cf63f7d2b66557c5bac49a4085022b5d1d869b9f7d32ccee661da759",
            digestOffset = 117104L),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "wind_00.l2d",
            4053264L, 41088, "52622079cf63f7d2b66557c5bac49a4085022b5d1d869b9f7d32ccee661da759",
            digestOffset = 117104L),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "wind_00.l2d",
            4964656L, 41088, "52622079cf63f7d2b66557c5bac49a4085022b5d1d869b9f7d32ccee661da759",
            digestOffset = 117104L),
        Candidate(UiScaleElement.COMBAT_HUD, "BBS1.DAT", BBS1_SIZE, "wind_00.l2d",
            5943408L, 41088, "52622079cf63f7d2b66557c5bac49a4085022b5d1d869b9f7d32ccee661da759",
            digestOffset = 117104L),
        Candidate(UiScaleElement.PORTRAITS, "BBS1.DAT", BBS1_SIZE, "cface_12.l2d",
            204423600L, 10400, "e58369a8d910e28addd3ca35d1a7e296803c6ef812999cbb4bfc35d320c31bf1",
            digestOffset = 14647424L),
        Candidate(UiScaleElement.PORTRAITS, "BBS1.DAT", BBS1_SIZE, "cface_11.l2d",
            204952320L, 10400, "6a9e0d40ae43d49098ac644340f84d2c324b5dc6a39c0b5225b4d4bf02f823cb",
            digestOffset = 14657824L),
        Candidate(UiScaleElement.PORTRAITS, "BBS1.DAT", BBS1_SIZE, "cface_10.l2d",
            205005472L, 10400, "b64c91f1bacd33746dc2ad8be6f9ed979c4565114b51683a6daebdacc51158c3",
            digestOffset = 14676032L),
        Candidate(UiScaleElement.MENUS, "BBS1.DAT", BBS1_SIZE, "c_icon_00.l2d",
            205035568L, 34944, "b52113745660e74ca7926261f2dea7bf341228625727fe95ab5cab29b4a6c6e4",
            digestOffset = 204970528L),
        Candidate(UiScaleElement.PORTRAITS, "BBS3.DAT", 206391296L, "cface_10.l2d",
            126664752L, 10400, "b64c91f1bacd33746dc2ad8be6f9ed979c4565114b51683a6daebdacc51158c3",
            digestArchive = "BBS1.DAT", digestOffset = 14676032L),
        Candidate(UiScaleElement.PORTRAITS, "BBS3.DAT", 206391296L, "cface_11.l2d",
            133998640L, 10400, "6a9e0d40ae43d49098ac644340f84d2c324b5dc6a39c0b5225d4bf02f823cb",
            digestArchive = "BBS1.DAT", digestOffset = 14657824L),
        Candidate(UiScaleElement.PORTRAITS, "BBS3.DAT", 206391296L, "cface_12.l2d",
            146206768L, 10400, "e58369a8d910e28addd3ca35d1a7e296803c6ef812999cbb4bfc35d320c31bf1",
            digestArchive = "BBS1.DAT", digestOffset = 14647424L),
    )

    /**
     * Construct one exact-source-verified, size-preserving experimental patch.
     * Used by the experimental UI ISO patch path. Never modify an
     * encrypted, mismatched, or unknown resource.
     */
    fun planCandidate(
        iso: Path,
        image: IsoImageInfo,
        reader: Iso9660Reader,
        candidate: Candidate,
        percent: Int,
    ): IsoBytePatch? {
        require(UiScaleSettings.isSelectable(percent))
        if (percent == 100) return null
        require(candidate in suppliedCandidates) { "UI layout is not in the inspected source manifest." }
        val path = "PSP_GAME/USRDIR/" + candidate.archive
        val entry = reader.findOptionalEntry(iso, image, path)
            ?: throw IsoFormatException(path + " is missing from the selected ISO.")
        if (entry.size != candidate.archiveSize) {
            throw IsoFormatException(path + " has an unexpected size; refusing unverified layout changes.")
        }
        if (candidate.offsetInArchive < 0L ||
            candidate.offsetInArchive > entry.size ||
            candidate.layoutSize.toLong() > entry.size - candidate.offsetInArchive
        ) {
            throw IsoFormatException(candidate.layout + " lies outside the selected DAT.")
        }
        val position = entry.dataOffset + candidate.offsetInArchive
        val old = reader.readAt(iso, position, candidate.layoutSize)
        if (sha256Hex(old) != candidate.originalSha256) {
            throw IsoFormatException(candidate.layout + " SHA-256 mismatch; the game layout was not changed.")
        }
        val scaled = BbsL2dGeometry.scale(old, percent)
        check(scaled.bytes.size == old.size)
        if (scaled.totalFieldsChanged == 0) {
            throw IsoFormatException(candidate.layout + " has no adjustable static geometry.")
        }
        return IsoBytePatch(position, old, scaled.bytes, candidate.archive + "/" + candidate.layout)
    }

    private const val BBS1_SIZE = 206092288L
}
