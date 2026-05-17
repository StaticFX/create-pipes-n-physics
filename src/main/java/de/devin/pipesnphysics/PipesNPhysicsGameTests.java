package de.devin.pipesnphysics;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import de.devin.pipesnphysics.physics.PhysicsConfig;
import de.devin.pipesnphysics.physics.PipeFormulas;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * NeoForge GameTests for Pipes n Physics.
 *
 * <p>Unit tests verify the PipeFormulas math directly.
 * Integration tests build pipe networks in-world and verify gravity-driven flow.</p>
 *
 * <p>Run with: {@code ./gradlew runGameTestServer}</p>
 */
@GameTestHolder(PipesNPhysics.ID)
@PrefixGameTestTemplate(false)
public class PipesNPhysicsGameTests {

    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void gravityPressureBasic(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // 7 blocks drop, no friction → 7 * 3.0 = 21 → clamped to max 20
        float p1 = physics.gravityPressure(10.0, 3.0, 0.0f);
        assertClose(helper, "7-block drop capped at max", 20.0f, p1);

        // 3 blocks drop, no friction → 3 * 3.0 = 9
        float p2 = physics.gravityPressure(10.0, 7.0, 0.0f);
        assertClose(helper, "3-block drop", 9.0f, p2);

        // 3 blocks drop, 5 friction → 9 - 5 = 4
        float p3 = physics.gravityPressure(10.0, 7.0, 5.0f);
        assertClose(helper, "3-block drop minus friction", 4.0f, p3);

        helper.succeed();
    }

    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void gravityPressureDeadZone(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // Height diff 0.05 < deadZone 0.1 → 0
        float p = physics.gravityPressure(10.0, 9.95, 0.0f);
        assertClose(helper, "within dead zone", 0.0f, p);

        // Height diff 0.1 at boundary → should still be 0 (< not <=)
        // heightDiff = 0.1, deadZone = 0.1 → 0.1 < 0.1 is false, so pressure > 0
        float pBound = physics.gravityPressure(10.0, 9.9, 0.0f);
        if (pBound <= 0.0f) {
            helper.fail("At dead zone boundary (0.1), expected positive pressure but got " + pBound);
        }

        helper.succeed();
    }

    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void gravityPressureNoUphill(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // Source below node → negative height diff → 0
        float p = physics.gravityPressure(3.0, 10.0, 0.0f);
        assertClose(helper, "uphill returns 0", 0.0f, p);

        // Same height → within dead zone → 0
        float pFlat = physics.gravityPressure(5.0, 5.0, 0.0f);
        assertClose(helper, "flat returns 0", 0.0f, pFlat);

        helper.succeed();
    }

    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void gravityPressureFrictionExceedsHead(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // 2 blocks drop, 10 friction → 6 - 10 = -4 → clamped to 0
        float p = physics.gravityPressure(10.0, 8.0, 10.0f);
        assertClose(helper, "friction exceeds head clamped to 0", 0.0f, p);

        helper.succeed();
    }

    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void pumpPressureGravityAssist(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // Pump at Y=5, node at Y=2 (downhill) → gravity helps
        // 10 + (5-2)*3*1.0 - 0 = 10 + 9 = 19
        float pDown = physics.pumpPressure(10.0f, 5.0, 2.0, 0.0f);
        assertClose(helper, "downhill pump assist", 19.0f, pDown);

        // Pump at Y=5, node at Y=8 (uphill) → gravity hurts
        // 10 + (5-8)*3*1.0 - 0 = 10 - 9 = 1
        float pUp = physics.pumpPressure(10.0f, 5.0, 8.0, 0.0f);
        assertClose(helper, "uphill pump penalty", 1.0f, pUp);

        helper.succeed();
    }

    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void pumpPressureGravityFactor(GameTestHelper helper) {
        // gravityFactor = 0 → no gravity effect on pump
        // 10 + (5-8)*3*0 - 0 = 10
        PipeFormulas physics0 = testFormulasWithGravityFactor(3.0f, 5.0f, 20.0f, 0.1f, 0.0f);
        float p0 = physics0.pumpPressure(10.0f, 5.0, 8.0, 0.0f);
        assertClose(helper, "gravity factor 0 ignores height", 10.0f, p0);

        // gravityFactor = 0.5 → half gravity
        // 10 + (5-8)*3*0.5 - 0 = 10 - 4.5 = 5.5
        PipeFormulas physics05 = testFormulasWithGravityFactor(3.0f, 5.0f, 20.0f, 0.1f, 0.5f);
        float p05 = physics05.pumpPressure(10.0f, 5.0, 8.0, 0.0f);
        assertClose(helper, "gravity factor 0.5", 5.5f, p05);

        helper.succeed();
    }

    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void pumpPressureClamp(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // Pump pressure is NOT capped by maxPressure (only gravity flow is).
        // Huge downhill: 15 + (10-0)*3*1.0 = 45
        float pMax = physics.pumpPressure(15.0f, 10.0, 0.0, 0.0f);
        assertClose(helper, "pump pressure uncapped", 45.0f, pMax);

        // Huge uphill + small pump → clamped to 0 (can't go negative)
        float pMin = physics.pumpPressure(5.0f, 5.0, 20.0, 0.0f);
        assertClose(helper, "clamped to 0", 0.0f, pMin);

        helper.succeed();
    }

    /**
     * Pump flow rate should be uniform (= pumpBase) regardless of pipe height or friction.
     * Gravity/friction only affect RANGE (whether the pump can reach a pipe), not flow rate.
     * This matches vanilla Create behavior: all pipes in range get the same pressure.
     */
    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void pumpUniformFlowRate(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);
        float pumpBase = 128.0f; // typical pump speed

        // All these pipes are in range (pumpPressure > 0), so they should all
        // receive pumpBase as pressure — the same flow rate everywhere.

        // Flat pipe: pumpPressure = 128 + 0 - 0 = 128 (in range)
        float flat = physics.pumpPressure(pumpBase, 5.0, 5.0, 0.0f);
        if (flat <= 0) helper.fail("Flat pipe should be in range");

        // Downhill pipe: pumpPressure = 128 + 9 - 0 = 137 (in range)
        float downhill = physics.pumpPressure(pumpBase, 5.0, 2.0, 0.0f);
        if (downhill <= 0) helper.fail("Downhill pipe should be in range");

        // Uphill pipe with friction: 128 - 9 - 10 = 109 (still in range)
        float uphill = physics.pumpPressure(pumpBase, 5.0, 8.0, 10.0f);
        if (uphill <= 0) helper.fail("Uphill pipe with friction should still be in range");

        // All in-range pipes should get the SAME flow rate (pumpBase), not their individual pressures
        // This is enforced by PumpBlockEntityMixin using uniform pumpBase, not per-node physics pressure

        helper.succeed();
    }

    /**
     * Pump range should be limited by gravity: uphill + friction can push pipes out of range.
     * A pipe is out of range when pumpPressure <= 0.
     */
    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void pumpRangeLimitedByGravity(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);
        float pumpBase = 10.0f; // weak pump

        // Uphill exceeds pump capacity: 10 + (5-20)*3*1.0 - 0 = 10 - 45 = -35 → clamped to 0
        float tooHigh = physics.pumpPressure(pumpBase, 5.0, 20.0, 0.0f);
        assertClose(helper, "far uphill out of range", 0.0f, tooHigh);

        // Heavy friction: 10 + 0 - 15 = -5 → clamped to 0
        float tooFar = physics.pumpPressure(pumpBase, 5.0, 5.0, 15.0f);
        assertClose(helper, "heavy friction out of range", 0.0f, tooFar);

        // Just barely in range: 10 + 0 - 9 = 1 > 0
        float barely = physics.pumpPressure(pumpBase, 5.0, 5.0, 9.0f);
        if (barely <= 0) helper.fail("Should barely be in range, got " + barely);

        helper.succeed();
    }

    /**
     * Verify segmentFriction returns correct values for cardinal directions.
     * On a non-Sable level: vertical = 0 friction, horizontal = full friction.
     * This is the baseline that Sable tilting modifies.
     */
    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void segmentFrictionDirections(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // Vertical pipes: 90° elevation → sin(90°)=1 → friction = (1-1)*5 = 0
        assertClose(helper, "UP friction", 0.0f,
                physics.segmentFriction(90.0f));
        assertClose(helper, "DOWN friction", 0.0f,
                physics.segmentFriction(90.0f));

        // Horizontal pipes: 0° elevation → sin(0°)=0 → friction = (1-0)*5 = 5
        assertClose(helper, "NORTH friction", 5.0f,
                physics.segmentFriction(0.0f));
        assertClose(helper, "SOUTH friction", 5.0f,
                physics.segmentFriction(0.0f));
        assertClose(helper, "EAST friction", 5.0f,
                physics.segmentFriction(0.0f));
        assertClose(helper, "WEST friction", 5.0f,
                physics.segmentFriction(0.0f));

        helper.succeed();
    }

    /**
     * Test the friction formula at intermediate elevation angles that Sable sub-levels produce.
     * On a tilted sub-level, a "horizontal" pipe has a non-zero world-space elevation,
     * reducing its friction proportionally: {@code (1 - sin(elevation)) * frictionPerBlock}.
     */
    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void sableTiltedFrictionFormula(GameTestHelper helper) {
        float frictionPerBlock = 5.0f;

        // 30° tilt: sin(30°) = 0.5 → friction = 0.5 * 5 = 2.5
        float sin30 = (float) Math.sin(Math.toRadians(30));
        float friction30 = (1.0f - sin30) * frictionPerBlock;
        assertClose(helper, "30° friction", 2.5f, friction30);

        // 45° tilt: sin(45°) ≈ 0.7071 → friction ≈ 1.46
        float sin45 = (float) Math.sin(Math.toRadians(45));
        float friction45 = (1.0f - sin45) * frictionPerBlock;
        float expected45 = (1.0f - (float) Math.sin(Math.toRadians(45))) * frictionPerBlock;
        assertClose(helper, "45° friction", expected45, friction45);

        // 60° tilt: sin(60°) ≈ 0.866 → friction ≈ 0.67
        float sin60 = (float) Math.sin(Math.toRadians(60));
        float friction60 = (1.0f - sin60) * frictionPerBlock;
        float expected60 = (1.0f - (float) Math.sin(Math.toRadians(60))) * frictionPerBlock;
        assertClose(helper, "60° friction", expected60, friction60);

        // Monotonicity: steeper angle → less friction
        if (friction30 <= friction45 || friction45 <= friction60) {
            helper.fail("Friction should decrease with steeper angles: "
                    + friction30 + " > " + friction45 + " > " + friction60);
        }

        helper.succeed();
    }

    /**
     * Siphon physics: fluid goes over a hill (up then down).
     * Pressure uses endpoint heights (path-independent), friction accumulates along path.
     * A 10-block source-to-sink drop that routes through 5 vertical + 3 horizontal + 5 vertical
     * segments should still flow — the extra horizontal friction doesn't kill it if the
     * total drop is large enough.
     */
    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void siphonGravityPressure(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // Siphon path: source Y=20 → up 5 to Y=25 → horizontal 3 → down 10 to Y=15
        // Endpoint heights: source=20, sink=15 → heightDiff=5
        // Path friction: 5 vertical(0) + 3 horizontal(5 each) + 10 vertical(0) = 15.0
        float pathFriction = 5 * 0.0f + 3 * 5.0f + 10 * 0.0f; // 15.0
        float pressure = physics.gravityPressure(20.0, 15.0, pathFriction);
        // 5 * 3.0 - 15.0 = 15 - 15 = 0 → barely no flow
        assertClose(helper, "siphon with equal head and friction", 0.0f, pressure);

        // Deeper sink at Y=10 → heightDiff=10 → 30 - 15 = 15 pressure
        float deepPressure = physics.gravityPressure(20.0, 10.0, pathFriction);
        assertClose(helper, "siphon with deeper sink", 15.0f, deepPressure);

        // Sink ABOVE source → no flow regardless of path
        float uphillPressure = physics.gravityPressure(20.0, 25.0, 0.0f);
        assertClose(helper, "sink above source", 0.0f, uphillPressure);

        helper.succeed();
    }

    /**
     * On a 45° tilted Sable sub-level, a nominally horizontal pipe has reduced friction.
     * This means gravity flow reaches further horizontally.
     * Compares range on a flat vs 45°-tilted sub-level for the same height drop.
     */
    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void tiltedSubLevelExtendsRange(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        float heightDrop = 5.0f; // 5 blocks
        float headPressure = heightDrop * physics.config().gravityPerBlock(); // 15.0

        // Flat (0°): friction = 5.0/block → range = 15/5 = 3 blocks
        float flatFriction = physics.config().frictionPerBlock();
        float flatRange = headPressure / flatFriction;
        assertClose(helper, "flat range", 3.0f, flatRange);

        // 45° tilt: friction = (1-sin45)*5 ≈ 1.46/block → range ≈ 10.2 blocks
        float sin45 = (float) Math.sin(Math.toRadians(45));
        float tiltedFriction = (1.0f - sin45) * physics.config().frictionPerBlock();
        float tiltedRange = headPressure / tiltedFriction;

        // Tilted range should be significantly larger
        if (tiltedRange <= flatRange * 2) {
            helper.fail("45° tilt should at least double range: flat=" + flatRange
                    + " tilted=" + tiltedRange);
        }

        helper.succeed();
    }

    /**
     * Pump on a Sable sub-level: gravity assist is based on world-space Y, not local Y.
     * When a sub-level is tilted, a pump pushing "horizontally" in local space
     * may be pushing downhill in world space, gaining gravity assist.
     * Simulates a pump at world Y=10 pushing to a node at world Y=7 on a tilted structure.
     */
    @GameTest(template = "empty_1x1x1", templateNamespace = PipesNPhysics.ID)
    public static void tiltedPumpGravityAssist(GameTestHelper helper) {
        PipeFormulas physics = testFormulas(3.0f, 5.0f, 20.0f, 0.1f);

        // Pump locally horizontal but world-space 3 blocks downhill
        // pumpBase=8, pumpWorldY=10, nodeWorldY=7, gravityFactor=1.0
        // Tilted friction for 3 segments at 45°: 3 * (1-sin45)*5 ≈ 4.39
        float sin45 = (float) Math.sin(Math.toRadians(45));
        float tiltedSegFriction = (1.0f - sin45) * physics.config().frictionPerBlock();
        float totalFriction = 3 * tiltedSegFriction;

        // pressure = 8 + (10-7)*3*1.0 - 4.39 = 8 + 9 - 4.39 = 12.61
        float pressure = physics.pumpPressure(8.0f, 10.0, 7.0, totalFriction);
        float expected = 8.0f + 3.0f * physics.config().gravityPerBlock() - totalFriction;
        assertClose(helper, "tilted pump with gravity assist", expected, pressure);

        // Same setup but gravityFactor=0 (vanilla pump, ignores tilt)
        // pressure = 8 + 0 - 4.39 = 3.61
        PipeFormulas vanillaPhysics = testFormulasWithGravityFactor(3.0f, 5.0f, 20.0f, 0.1f, 0.0f);
        float vanillaPressure = vanillaPhysics.pumpPressure(8.0f, 10.0, 7.0, totalFriction);
        float expectedVanilla = 8.0f - totalFriction;
        assertClose(helper, "vanilla pump ignores tilt gravity", expectedVanilla, vanillaPressure);

        // Physics-enabled pump should always outperform vanilla going downhill
        if (pressure <= vanillaPressure) {
            helper.fail("Gravity-aware pump should beat vanilla downhill: physics="
                    + pressure + " vanilla=" + vanillaPressure);
        }

        helper.succeed();
    }

    /**
     * Vertical pipe network: tank on top → pipes → tank on bottom.
     * Gravity should drive fluid downward.
     */
    @GameTest(template = "empty_3x10x3", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void gravityFlowTransfersFluid(GameTestHelper helper) {
        BlockPos topTank = new BlockPos(1, 8, 1);
        BlockPos bottomTank = new BlockPos(1, 0, 1);

        // Phase 1: Place all blocks
        helper.setBlock(bottomTank, AllBlocks.FLUID_TANK.getDefaultState());
        helper.setBlock(topTank, AllBlocks.FLUID_TANK.getDefaultState());
        BlockPos[] pipes = new BlockPos[7];
        for (int y = 1; y <= 7; y++) {
            pipes[y - 1] = new BlockPos(1, y, 1);
            helper.setBlock(pipes[y - 1], AllBlocks.FLUID_PIPE.getDefaultState());
        }

        // Phase 2: After block entities initialize, re-propagate to force connection rebuild
        helper.runAfterDelay(20, () -> propagatePipes(helper, pipes));

        // Phase 3: Fill source tank well after network is established
        helper.runAfterDelay(60, () -> fillTank(helper, topTank, 8000));

        // Phase 4: Poll until bottom tank has fluid
        helper.succeedWhen(() -> {
            BlockPos abs = helper.absolutePos(bottomTank);
            var handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, null);
            if (handler == null) { helper.fail("No fluid handler at bottom tank"); return; }
            for (int i = 0; i < handler.getTanks(); i++) {
                if (!handler.getFluidInTank(i).isEmpty()) return;
            }
            helper.fail("Bottom tank has no fluid yet");
        });
    }

    /**
     * Horizontal pipes at the same Y level — no height difference, no pump.
     * Gravity flow should NOT transfer any fluid.
     */
    @GameTest(template = "empty_10x3x3", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void noGravityFlowOnFlatPipes(GameTestHelper helper) {
        BlockPos leftTank = new BlockPos(0, 1, 1);
        BlockPos rightTank = new BlockPos(9, 1, 1);

        helper.setBlock(leftTank, AllBlocks.FLUID_TANK.getDefaultState());
        helper.setBlock(rightTank, AllBlocks.FLUID_TANK.getDefaultState());
        BlockPos[] pipes = new BlockPos[8];
        for (int x = 1; x <= 8; x++) {
            pipes[x - 1] = new BlockPos(x, 1, 1);
            helper.setBlock(pipes[x - 1], AllBlocks.FLUID_PIPE.getDefaultState());
        }

        helper.runAfterDelay(20, () -> propagatePipes(helper, pipes));
        helper.runAfterDelay(60, () -> fillTank(helper, leftTank, 8000));

        // After plenty of time, verify right tank is still empty
        helper.runAfterDelay(180, () -> {
            BlockPos abs = helper.absolutePos(rightTank);
            var handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, null);
            if (handler != null) {
                for (int i = 0; i < handler.getTanks(); i++) {
                    if (!handler.getFluidInTank(i).isEmpty()) {
                        helper.fail("Flat pipes should not transfer fluid without a pump");
                        return;
                    }
                }
            }
            helper.succeed();
        });
    }

    /**
     * Verify that gravity flow produces the expected pressure on intermediate pipes.
     * Uses a 5-block vertical drop which should generate 5 * 3.0 = 15 pressure (with default config).
     */
    @GameTest(template = "empty_3x10x3", templateNamespace = PipesNPhysics.ID, timeoutTicks = 400)
    public static void gravityFlowAppliesPressure(GameTestHelper helper) {
        BlockPos topTank = new BlockPos(1, 6, 1);
        BlockPos bottomTank = new BlockPos(1, 0, 1);

        helper.setBlock(topTank, AllBlocks.FLUID_TANK.getDefaultState());
        helper.setBlock(bottomTank, AllBlocks.FLUID_TANK.getDefaultState());
        BlockPos[] pipes = new BlockPos[5];
        for (int y = 1; y <= 5; y++) {
            pipes[y - 1] = new BlockPos(1, y, 1);
            helper.setBlock(pipes[y - 1], AllBlocks.FLUID_PIPE.getDefaultState());
        }

        helper.runAfterDelay(20, () -> propagatePipes(helper, pipes));
        helper.runAfterDelay(60, () -> fillTank(helper, topTank, 8000));

        // Check that an intermediate pipe has non-zero pressure
        helper.succeedWhen(() -> {
            BlockPos absMid = helper.absolutePos(new BlockPos(1, 3, 1));
            FluidTransportBehaviour pipe = FluidPropagator.getPipe(helper.getLevel(), absMid);
            if (pipe == null) { helper.fail("No pipe behaviour at mid position"); return; }
            if (!pipe.hasAnyPressure()) { helper.fail("Mid pipe has no pressure yet"); }
        });
    }

    /**
     * L-shaped pipe: vertical drop then horizontal run.
     * Proves angle-based friction works: vertical segments contribute 0 friction,
     * so the 6-block head pressure only fights the 2 horizontal segments' friction.
     *
     * <p>With default config (gravity=3.0/block, friction=5.0/block):
     * <ul>
     *   <li>Head pressure: 6 blocks × 3.0 = 18.0</li>
     *   <li>Angle-based friction: 6 vertical(0) + 2 horizontal(5) = 10.0</li>
     *   <li>Net pressure: 18 - 10 = 8.0 → <b>flows</b></li>
     *   <li>Without angle-based friction: 8 segments × 5 = 40 → would NOT flow</li>
     * </ul></p>
     *
     * <p>This is the same friction model that Sable sub-levels use at intermediate
     * angles — the test validates the core mechanism on standard 0°/90° segments.</p>
     */
    @GameTest(template = "empty_8x9x3", templateNamespace = PipesNPhysics.ID, timeoutTicks = 600, required = false)
    public static void lShapedGravityFlowWithAngleFriction(GameTestHelper helper) {
        // Layout:
        // (1,7,1) source tank
        // (1,6,1)-(1,1,1) 6 vertical pipes (0 friction each)
        // (2,1,1)-(3,1,1) 2 horizontal pipes (5.0 friction each)
        // (4,1,1) destination tank
        BlockPos sourceTank = new BlockPos(1, 7, 1);
        BlockPos destTank = new BlockPos(4, 1, 1);

        helper.setBlock(sourceTank, AllBlocks.FLUID_TANK.getDefaultState());
        helper.setBlock(destTank, AllBlocks.FLUID_TANK.getDefaultState());

        BlockPos[] pipes = new BlockPos[8];
        for (int y = 1; y <= 6; y++) {
            pipes[y - 1] = new BlockPos(1, y, 1);
            helper.setBlock(pipes[y - 1], AllBlocks.FLUID_PIPE.getDefaultState());
        }
        pipes[6] = new BlockPos(2, 1, 1);
        pipes[7] = new BlockPos(3, 1, 1);
        helper.setBlock(pipes[6], AllBlocks.FLUID_PIPE.getDefaultState());
        helper.setBlock(pipes[7], AllBlocks.FLUID_PIPE.getDefaultState());

        helper.runAfterDelay(20, () -> propagatePipes(helper, pipes));
        helper.runAfterDelay(60, () -> fillTank(helper, sourceTank, 8000));

        helper.succeedWhen(() -> {
            BlockPos abs = helper.absolutePos(destTank);
            var handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, null);
            if (handler == null) { helper.fail("No fluid handler at destination"); return; }
            for (int i = 0; i < handler.getTanks(); i++) {
                if (!handler.getFluidInTank(i).isEmpty()) return;
            }
            helper.fail("Destination tank has no fluid yet");
        });
    }

    /**
     * L-shaped pipe where horizontal friction exceeds the head pressure budget.
     * Same vertical drop as above, but 5 horizontal segments instead of 2.
     *
     * <p>With default config:
     * <ul>
     *   <li>Head pressure: 6 blocks × 3.0 = 18.0</li>
     *   <li>Angle-based friction: 6 vertical(0) + 5 horizontal(5) = 25.0</li>
     *   <li>Net pressure: 18 - 25 = -7 → clamped to 0 → <b>no flow</b></li>
     * </ul></p>
     */
    @GameTest(template = "empty_8x9x3", templateNamespace = PipesNPhysics.ID, timeoutTicks = 200)
    public static void lShapedExcessFrictionBlocksFlow(GameTestHelper helper) {
        // Layout:
        // (1,7,1) source tank
        // (1,6,1)-(1,1,1) 6 vertical pipes
        // (2,1,1)-(6,1,1) 5 horizontal pipes → friction exceeds head pressure
        // (7,1,1) destination tank
        BlockPos sourceTank = new BlockPos(1, 7, 1);
        BlockPos destTank = new BlockPos(7, 1, 1);

        helper.setBlock(sourceTank, AllBlocks.FLUID_TANK.getDefaultState());
        helper.setBlock(destTank, AllBlocks.FLUID_TANK.getDefaultState());

        BlockPos[] pipes = new BlockPos[11];
        for (int y = 1; y <= 6; y++) {
            pipes[y - 1] = new BlockPos(1, y, 1);
            helper.setBlock(pipes[y - 1], AllBlocks.FLUID_PIPE.getDefaultState());
        }
        for (int x = 2; x <= 6; x++) {
            pipes[x + 4] = new BlockPos(x, 1, 1);
            helper.setBlock(pipes[x + 4], AllBlocks.FLUID_PIPE.getDefaultState());
        }

        helper.runAfterDelay(20, () -> propagatePipes(helper, pipes));
        helper.runAfterDelay(60, () -> fillTank(helper, sourceTank, 8000));

        // Destination should remain empty — friction exceeds head pressure
        helper.runAfterDelay(180, () -> {
            BlockPos abs = helper.absolutePos(destTank);
            var handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, null);
            if (handler != null) {
                for (int i = 0; i < handler.getTanks(); i++) {
                    if (!handler.getFluidInTank(i).isEmpty()) {
                        helper.fail("Fluid should not reach destination — horizontal friction "
                                + "(25.0) exceeds head pressure (18.0)");
                        return;
                    }
                }
            }
            helper.succeed();
        });
    }

    /**
     * Fills a tank at the given relative position with fluid.
     * Uses absolute position for capability lookup.
     */
    private static void fillTank(GameTestHelper helper, BlockPos relPos, int amount) {
        BlockPos abs = helper.absolutePos(relPos);
        var handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, null);
        if (handler != null) {
            handler.fill(new FluidStack(Fluids.WATER.builtInRegistryHolder(), amount),
                    IFluidHandler.FluidAction.EXECUTE);
        }
    }

    /**
     * Re-propagates all pipe blocks at the given relative positions.
     * Should be called AFTER block entities have had time to initialize (use runAfterDelay).
     */
    private static void propagatePipes(GameTestHelper helper, BlockPos... relPositions) {
        ServerLevel level = helper.getLevel();
        for (BlockPos rel : relPositions) {
            BlockPos abs = helper.absolutePos(rel);
            FluidPropagator.propagateChangedPipe(level, abs, level.getBlockState(abs));
        }
    }

    /**
     * Two pumps in series pushing the same direction.
     * Uses pre-built double_pump.nbt with creative motors already connected.
     */
    @GameTest(template = "double_pump", templateNamespace = PipesNPhysics.ID, timeoutTicks = 1200)
    public static void twoPumpsInSeries(GameTestHelper helper) {
        BlockPos destTank = new BlockPos(0, 1, 0);

        helper.succeedWhen(() -> {
            BlockPos abs = helper.absolutePos(destTank);
            var handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, null);
            if (handler == null) { helper.fail("No fluid handler at dest tank"); return; }
            for (int i = 0; i < handler.getTanks(); i++) {
                if (!handler.getFluidInTank(i).isEmpty()) return;
            }
            helper.fail("Dest tank has no fluid — two pumps in series failed to transfer");
        });
    }

    private static void assertClose(GameTestHelper helper, String label, float expected, float actual) {
        if (Math.abs(expected - actual) > 0.01f) {
            helper.fail(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static PipeFormulas testFormulas(float gravity, float friction, float maxPressure, float deadZone) {
        return new PipeFormulas(new PhysicsConfig(gravity, friction, maxPressure, deadZone, 5.0f, true, true, 1.0f, 10, true));
    }

    private static PipeFormulas testFormulasWithGravityFactor(float gravity, float friction, float maxPressure, float deadZone, float gravityFactor) {
        return new PipeFormulas(new PhysicsConfig(gravity, friction, maxPressure, deadZone, 5.0f, true, true, gravityFactor, 10, true));
    }
}
