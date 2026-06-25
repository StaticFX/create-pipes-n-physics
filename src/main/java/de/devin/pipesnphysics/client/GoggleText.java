package de.devin.pipesnphysics.client;

import de.devin.pipesnphysics.PipesNPhysics;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;

/**
 * Shared {@link LangBuilder} shorthands for the goggle tooltip mixins, including
 * the Create-boiler-style colored bar run (green up to {@code filled} with a
 * bright tip, dark red for the rest) appended inline after a label and value so
 * the number and the bar read as one line instead of two.
 */
public final class GoggleText {
    private GoggleText() {}

    public static LangBuilder lang(String key) {
        return new LangBuilder(PipesNPhysics.ID).translate(key);
    }

    public static LangBuilder text(String text) {
        return new LangBuilder(PipesNPhysics.ID).text(text);
    }

    public static LangBuilder bars(int count, ChatFormatting format) {
        return new LangBuilder(PipesNPhysics.ID)
                .text("|".repeat(Math.max(0, count)))
                .style(format);
    }

    /**
     * Appends a boiler-style bar run to {@code line}: dark-green bars with a
     * bright-green tip up to {@code filled}, dark-red for the consumed remainder.
     */
    public static void appendBars(LangBuilder line, int filled, int segments) {
        line.add(bars(Math.max(0, filled - 1), ChatFormatting.DARK_GREEN))
                .add(bars(filled > 0 ? 1 : 0, ChatFormatting.GREEN))
                .add(bars(Math.max(0, segments - filled), ChatFormatting.DARK_RED));
    }
}
