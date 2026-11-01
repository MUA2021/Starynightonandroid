package app.spicetify.patches.spotify.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.spicetify.patches.spotify.spotifyCompatibility
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val GET_ADS_REQUEST = "Lcom/spotify/ads/esperanto/proto/GetAdsRequest;"
private const val GET_ADS_RESPONSE = "Lcom/spotify/ads/esperanto/proto/GetAdsResponse;"

/**
 * Spotify's coroutine wrapper for `spotify.ads.esperanto.proto.Ads.GetAds`: asks the server for ads,
 * then parses the reply into a GetAdsResponse. Matched by the request class and service strings,
 * which keep their names, instead of the obfuscated class (p/x22 in 9.1.90, p/bx1 in 9.1.80).
 *
 * Adapted from Oyasumi Patches' "Disable ads" (dunecache/oyasumi-patches, GPL-3.0).
 */
internal object GetAdsFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(GET_ADS_REQUEST, "L"),
    filters = listOf(
        string("spotify.ads.esperanto.proto.Ads"),
        string("GetAds"),
    ),
)

@Suppress("unused")
val audioAdsPatch = bytecodePatch(
    name = "Disable audio ads",
    description = "Answers Spotify's ad request with an empty response, so no server-provided audio ad plays.",
    default = true,
) {
    compatibleWith(spotifyCompatibility)

    execute {
        val method = GetAdsFingerprint.method
        // The response's parse-from-bytes method, found in the wrapper itself so its name can change.
        val parse = method.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .first {
                it.definingClass == GET_ADS_RESPONSE && it.returnType == GET_ADS_RESPONSE &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("[B")
            }
        method.addInstructions(0, """
            const/4 v0, 0x0
            new-array v0, v0, [B
            invoke-static { v0 }, $GET_ADS_RESPONSE->${parse.name}([B)$GET_ADS_RESPONSE
            move-result-object v0
            return-object v0
        """.trimIndent())
    }
}
