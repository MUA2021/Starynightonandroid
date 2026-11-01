package app.spicetify.patches.spotify.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

private const val LYRICS_COLORS_CLASS = "Lapp/spicetify/extension/spotify/theme/LyricsColors;"
private const val LYRICS_STYLE_CLASS = "Lapp/spicetify/extension/spotify/theme/LyricsStyle;"
private const val FONT_WEIGHT = "Landroidx/compose/ui/text/font/FontWeight;"
private const val SHADOW = "Landroidx/compose/ui/graphics/Shadow;"

/** A call to the text style's copy: static, returns the style's class, takes a FontWeight and a Shadow. */
private fun MethodReference.isTextStyleCopy(): Boolean {
    val types = parameterTypes.map { it.toString() }
    return returnType == definingClass && types.firstOrNull() == definingClass && FONT_WEIGHT in types && SHADOW in types
}

/** The lyrics line's text: tagged #LyricsLineTag, it copies its text style to scale the font. */
internal object LyricsLineTextFingerprint : Fingerprint(
    filters = listOf(string("#LyricsLineTag")),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.isTextStyleCopy() == true
        } == true
    },
)
private const val COLOR_LYRICS_RESPONSE = "Lcom/spotify/lyrics/serviceretrofit/proto/ColorLyricsResponse;"

/** The lyrics colors object's constructor: background, background, highlight, text, highlight, flag. */
private val COLORS_PARAMETERS = listOf("I", "I", "I", "I", "I", "Z")

/**
 * The converter from the lyrics service's response to Spotify's lyrics model. Its only parameter is the
 * response, whose class keeps its name, and it builds the lyrics colors object from the response's colors.
 */
internal object ColorLyricsConverterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(COLOR_LYRICS_RESPONSE),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            call != null && call.name == "<init>" && call.parameterTypes.map { it.toString() } == COLORS_PARAMETERS
        } == true
    },
)

/** The lyrics line, which animates its color and its scale between the sung line and the others. */
internal object LyricsLineFingerprint : Fingerprint(
    filters = listOf(string("lyrics line scale")),
)

/** 0.83f, the scale Spotify gives the lines not being sung. */
private val INACTIVE_SCALE_BITS = java.lang.Float.floatToRawIntBits(0.83f)

/**
 * Themes the lyrics card and full-screen lyrics: the lyrics colors object's constructor passes each color
 * through LyricsColors first, so lyrics from the network, the cache and offline downloads all take them.
 */
internal val lyricsColorsPatch = bytecodePatch {
    execute {
        val converter = ColorLyricsConverterFingerprint.matchAll(1..1).single().method
        val colorsClass = converter.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull { it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == COLORS_PARAMETERS }
            ?.definingClass
            ?: throw PatchException("Lyrics colors constructor not found.")
        val constructor = mutableClassDefBy(colorsClass).methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == COLORS_PARAMETERS
        } ?: throw PatchException("Expected one lyrics colors constructor in $colorsClass.")
        // Static calls on parameters only, so they may run before the superclass constructor.
        constructor.addInstructions(0, """
            invoke-static { p1 }, $LYRICS_COLORS_CLASS->background(I)I
            move-result p1
            invoke-static { p2 }, $LYRICS_COLORS_CLASS->background(I)I
            move-result p2
            invoke-static { p3 }, $LYRICS_COLORS_CLASS->highlight(I)I
            move-result p3
            invoke-static { p4 }, $LYRICS_COLORS_CLASS->text(I)I
            move-result p4
            invoke-static { p5 }, $LYRICS_COLORS_CLASS->highlight(I)I
            move-result p5
        """.trimIndent())
        Logger.getLogger("").info("Lyrics colors hook: $colorsClass-><init>")

        // The lines not being sung: smaller, so the sung line stands out the way it does in Spicy Lyrics.
        val line = LyricsLineFingerprint.matchAll(1..1).single().method
        val scale = line.implementation!!.instructions.withIndex().singleOrNull { (_, instruction) ->
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == INACTIVE_SCALE_BITS
        } ?: throw PatchException("Lyrics line inactive scale not found.")
        val register = (scale.value as OneRegisterInstruction).registerA
        line.replaceInstruction(scale.index, "invoke-static { }, $LYRICS_COLORS_CLASS->inactiveScale()F")
        line.addInstruction(scale.index + 1, "move-result v$register")
        Logger.getLogger("").info("Lyrics line scale hook: ${line.definingClass}->${line.name}")

        // Bold lines and a glow on the sung one: the text's style passes through LyricsStyle.
        val text = LyricsLineTextFingerprint.matchAll(1..1).single().method
        val textInstructions = text.implementation!!.instructions
        val copyIndex = textInstructions.indices.singleOrNull { index ->
            ((textInstructions[index] as? ReferenceInstruction)?.reference as? MethodReference)?.isTextStyleCopy() == true
        } ?: throw PatchException("Expected one text style copy in the lyrics line text.")
        val styleType = ((textInstructions[copyIndex] as ReferenceInstruction).reference as MethodReference).returnType
        val result = textInstructions[copyIndex + 1] as? OneRegisterInstruction
            ?: throw PatchException("The lyrics line's text style copy has no result.")
        val styleRegister = result.registerA
        text.addInstructions(copyIndex + 2, """
            invoke-static/range { p0 .. p0 }, $LYRICS_STYLE_CLASS->setLine(Ljava/lang/Object;)V
            invoke-static/range { v$styleRegister .. v$styleRegister }, $LYRICS_STYLE_CLASS->restyle(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$styleRegister
            check-cast v$styleRegister, $styleType
        """.trimIndent())

        // The text's lambda gets the line's scale animation as its constructor's 4th parameter; its
        // field's name goes to the extension, which reads the scale to fade the glow in and out.
        val lambdaClass = text.definingClass
        val lambdaInit = line.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull { it.definingClass == lambdaClass && it.name == "<init>" }
            ?: throw PatchException("The lyrics line doesn't build its text lambda.")
        val constructor = mutableClassDefBy(lambdaClass).methods.single {
            it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == lambdaInit.parameterTypes.map { p -> p.toString() }
        }
        val implementation = constructor.implementation!!
        val parameterRegisters = 1 + constructor.parameterTypes.sumOf { p -> if (p.toString() == "J" || p.toString() == "D") 2 as Int else 1 }
        val scaleParameter = implementation.registerCount - parameterRegisters + 1 +
            constructor.parameterTypes.take(3).sumOf { p -> if (p.toString() == "J" || p.toString() == "D") 2 as Int else 1 }
        val scaleField = implementation.instructions.firstNotNullOfOrNull { instruction ->
            if (instruction.opcode != com.android.tools.smali.dexlib2.Opcode.IPUT_OBJECT) return@firstNotNullOfOrNull null
            if ((instruction as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA != scaleParameter) return@firstNotNullOfOrNull null
            ((instruction as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference).name
        } ?: throw PatchException("The lyrics line's scale field not found.")
        mutableClassDefBy(LYRICS_STYLE_CLASS).methods.single { it.name == "scaleField" }
            .replaceInstruction(0, "const-string v0, \"$scaleField\"")
        Logger.getLogger("").info("Lyrics style hook: $lambdaClass->${text.name}, scale field $scaleField")
    }
}
