package de.devin.pipesnphysics.engine.solve;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApportionTest {
    /**
     * The starvation regression: a source clamped to 256 feeding sink A (take 256) and sink B
     * (take 80) must give B its PROPORTIONAL share, not zero. Greedy pairing gave B either 0
     * (A first) or its full 80 (B first) — both outside the (0, 80) a fair split produces.
     */
    @Test
    void clampedSourceSplitsProportionallyNotGreedily() {
        int[] shares = Apportion.largestRemainder(256, new int[]{256, 80});
        assertEquals(256, shares[0] + shares[1], "conservation: the whole clamped give is placed");
        assertEquals(195, shares[0]);
        assertEquals(61, shares[1]);
        assertTrue(shares[1] > 0 && shares[1] < 80,
                "the second sink gets a strict fraction (61), never 0 or its full 80");
    }

    /** Symmetric sinks split evenly — the property greedy violates (it fills one before the other). */
    @Test
    void symmetricSinksSplitEvenly() {
        assertArrayEquals(new int[]{128, 128}, Apportion.largestRemainder(256, new int[]{180, 180}));
    }

    /** Leftover units go to the largest remainders; exact ties break toward the lower index. */
    @Test
    void largestRemainderTieBreaksByIndex() {
        assertArrayEquals(new int[]{1, 0}, Apportion.largestRemainder(1, new int[]{1, 1}));
        assertArrayEquals(new int[]{1, 1, 0}, Apportion.largestRemainder(2, new int[]{1, 1, 1}));
    }

    /** total == sum hands each entry exactly its own weight (no shortfall to apportion). */
    @Test
    void fullTotalGivesEachItsWeight() {
        int[] weights = {256, 80, 33};
        assertArrayEquals(weights, Apportion.largestRemainder(256 + 80 + 33, weights));
    }

    @Test
    void degenerateInputs() {
        assertArrayEquals(new int[]{0, 0}, Apportion.largestRemainder(0, new int[]{5, 5}));
        assertArrayEquals(new int[]{0}, Apportion.largestRemainder(10, new int[]{0}));
        assertArrayEquals(new int[]{}, Apportion.largestRemainder(10, new int[]{}));
        assertArrayEquals(new int[]{7}, Apportion.largestRemainder(7, new int[]{100}));
    }

    /**
     * Conservation and the no-over-serve bound hold for arbitrary shortfalls: the parts sum to
     * total exactly and no part exceeds its own weight (so a clamped source never over-fills a sink).
     */
    @Test
    void conservesAndNeverExceedsWeightForRandomShortfalls() {
        Random random = new Random(7);
        for (int trial = 0; trial < 2000; trial++) {
            int n = 1 + random.nextInt(6);
            int[] weights = new int[n];
            long sum = 0;
            for (int i = 0; i < n; i++) {
                weights[i] = random.nextInt(300);
                sum += weights[i];
            }
            int total = sum == 0 ? 0 : random.nextInt((int) sum + 1); // total <= sum
            int[] alloc = Apportion.largestRemainder(total, weights);

            int placed = 0;
            for (int i = 0; i < n; i++) {
                assertTrue(alloc[i] >= 0, "no negative allocation");
                assertTrue(alloc[i] <= weights[i], "never over-serves a sink beyond its take");
                placed += alloc[i];
            }
            assertEquals(total, placed, "conservation (trial " + trial + ")");
        }
    }
}
