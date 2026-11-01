package app.spicetify.patches.spotify.shuffle

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.spicetify.patches.spotify.spotifyCompatibility
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

private const val LIKED_SONGS = "smart_shuffle_as_default_for_liked_songs"
private const val PLAYLIST = "smart_shuffle_as_default_for_playlist"

/**
 * The smart-shuffle remote-config class's property list: it pairs each flag's name with a call to
 * its getter, so the getters are found by name here rather than by their obfuscated class
 * (p/sl4 in 9.1.80, p/ez4 in 9.1.90).
 */
internal object SmartShufflePropertiesFingerprint : Fingerprint(
    filters = listOf(string(LIKED_SONGS), string(PLAYLIST)),
)

/**
 * Adapted from Oyasumi Patches' "Disable Smart Shuffle default" (dunecache/oyasumi-patches, GPL-3.0).
 */
@Suppress("unused")
val smartShuffleDefaultPatch = bytecodePatch(
    name = "Disable Smart Shuffle default",
    description = "Stops Smart Shuffle being the default for playlists and Liked Songs, so shuffle " +
        "plays only your own songs. Smart Shuffle can still be turned on by hand.",
    default = true,
) {
    compatibleWith(spotifyCompatibility)

    execute {
        val properties = SmartShufflePropertiesFingerprint.method
        val owner = properties.definingClass
        val instructions = properties.implementation!!.instructions.toList()

        /** The getter called right after the flag's name: on the same class, no parameters, returns a boolean. */
        fun getterFor(flag: String): String {
            val start = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == flag
            }
            if (start < 0) throw PatchException("Smart shuffle flag $flag not found.")
            for (index in start + 1 until instructions.size) {
                val instruction = instructions[index]
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
                val call = (instruction as ReferenceInstruction).reference as MethodReference
                if (call.definingClass == owner && call.parameterTypes.isEmpty() && call.returnType == "Z") return call.name
            }
            throw PatchException("Smart shuffle getter for $flag not found.")
        }

        val ownerClass = mutableClassDefBy(owner)
        for (flag in listOf(LIKED_SONGS, PLAYLIST)) {
            val name = getterFor(flag)
            val getter = ownerClass.methods.single {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
            getter.addInstructions(0, """
                const/4 v0, 0x0
                return v0
            """.trimIndent())
            Logger.getLogger("").info("Smart shuffle default off: $owner->$name ($flag)")
        }
    }
}
