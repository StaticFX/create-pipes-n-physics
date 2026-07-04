package de.devin.pipesnphysics.engine.solve;

import java.util.Arrays;

/**
 * Integer apportioning used by transfer planning. Has no Minecraft dependencies so the
 * proportional-split math can be unit-tested directly.
 */
public final class Apportion {
    private Apportion() {}

    /**
     * Apportion {@code total} across {@code weights} by largest-remainder rounding: each entry gets
     * {@code floor(total·weight/sum)}, and the leftover units go to the entries with the largest
     * fractional parts (ties by ascending index). The parts sum EXACTLY to {@code total}
     * (conservation) and none exceeds its own weight when {@code total ≤ sum} — so a proportional
     * split of a clamped source never over-serves any sink.
     */
    public static int[] largestRemainder(int total, int[] weights) {
        int n = weights.length;
        int[] alloc = new int[n];
        long sum = 0;
        for (int w : weights) sum += w;
        if (sum <= 0 || total <= 0) return alloc;

        long[] remainder = new long[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            long numerator = (long) total * weights[i];
            alloc[i] = (int) (numerator / sum);
            remainder[i] = numerator % sum;
            assigned += alloc[i];
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> remainder[x] != remainder[y]
                ? Long.compare(remainder[y], remainder[x])
                : Integer.compare(x, y));
        for (int k = 0; k < total - assigned; k++) alloc[order[k]]++;
        return alloc;
    }
}
