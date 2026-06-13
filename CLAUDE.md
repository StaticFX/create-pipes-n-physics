# Fluid Network System — v2 Engine Spec (as implemented)

> Operating manual for the implemented v2 hydraulic engine (`physics-rewrite`,
> mod 2.0.0-alpha, NeoForge 1.21.1, Create 6.0.10, Sable 1.2.2). The v1 design
> (charging fronts, per-tile friction head budget) was scrapped after repeated
> oscillation failures; what shipped instead models pipe networks as electrical
> circuits and advances them one IMPLICIT Euler step per tick. Implicit Euler
> cannot overshoot — do not replace it with explicit stepping. Sections marked
> *Deferred* at the end describe v1 ideas that remain on the roadmap.

---

## 1. The model in one paragraph

Tanks are **capacitors** (head = fluid surface elevation, capacitance = mB per
block of height). Pipe runs are **resistors** (conductance ∝ 1/length, scaled
by fluid viscosity). Pumps are **EMF sources** with check valves on both flanks
and a pump curve (an internal conductance that caps free-flow throughput at
≈ RPM mB/t). Junctions are Kirchhoff nodes. Each server tick, one linear solve
per network yields end-of-tick heads and branch flows; endpoint-to-endpoint
transfers execute through `IFluidHandler`. **Pipes store no fluid** — they are
stateless conduits; only endpoints (tanks, basins, open ends) hold volume.

Direction is never stored: flow per branch is `q = conductance · (h_a − h_b + emf)`,
recomputed every tick. Gravity, towers, equalization, and siphons all emerge
from heads being *surface elevations*.

## 2. Vocabulary & data model (`engine/`)

- **Graph** (`GraphBuilder`) — BFS from a seed position discovers every pipe
  (`FluidPropagator.getPipe != null`); cells with exactly 2 connections contract
  into **Edge** runs; everything else becomes a **Node**:
  - `HANDLER` — an adjacent `IFluidHandler` (tank, basin, machine),
  - `PUMP` — `PumpBlock` with its blockstate `FACING` as push side (Create
    re-orients FACING to match rotation; a mixin watches for flips),
  - `JUNCTION` — pipe cell with ≠ 2 connections (includes dead ends),
  - `OPEN_END` — the space block an open pipe mouth faces.
  `Graph.coverage` holds every touched BlockPos for per-tick network dedupe.
  Parallel runs between the same nodes stay distinct edges.
- **BoundaryColumn** — a tank/open-end as a vertical column: `baseY` (worldY −
  0.5), `heightBlocks`, `capacityMb`, `fillFraction`; capacitance =
  capacity/height; surface = `baseY + fillFraction·height`. Multiblock tanks
  resolve through their controller, so many connections = one column (one
  identity). Open ends get 4,000,000 mB capacity (effectively atmospheric):
  a fluid-source space is a brimming column, air is a bottomless drain. Their
  Create `OpenEndedPipe` instances are cached (`OpenEndPipes`) so spill
  buffers survive ticks.
- **Solution** — one tick's decision: `edgeFlows`, `transfers`, the display
  fields (`nodeHeads`, `nodeCeilings`, `nodeAnchors`), `edgeFluids`, the status
  sets (`blockedEdges`, `stalledEdges`, `noHeadEdges`) with per-edge
  `edgeReasons` (`VALVE | PUMP_OFF | CREST | SINK_FULL | SOURCE_DRY`), and
  `active`.

## 3. The solve (`engine/solve/NetworkSolver`, pure Java, JUnit-tested)

Implicit Euler over `(C/dt + L) h' = (C/dt) h + EMF terms`, where `C` is the
diagonal capacitance matrix and `L` the conductance-weighted graph Laplacian.
Gaussian elimination ≤ 128 nodes, conjugate gradient above. Then an
**active-set loop**: deactivate branches that violate one-way constraints
(`allowedSign`: check valves, receive-only empty columns, lip gates) or whose
pump backflow exceeds the EMF (`backflowBlocked` → the NO_HEAD display), and
re-solve until consistent. Zero-capacitance islands are pruned (no phantom
circulation).

**Crest gating (siphon/cavitation) is ONE-SHOT**: evaluated once against the
pre-gate solution, then frozen — iterating it feeds back and kills working
lines. A branch's highest cell (`crestHeight`, `crestPos`) tapers flow from 1
to 0 as the head interpolated at the crest falls below it by up to
`SUCTION_LIMIT` (taper band `max(0.5, 0.25·limit)` — taper, never cliff).

## 4. Heads, fluids, and reach

- **Liquid column head** = surface elevation (`baseY + fillHeight`). Using the
  *surface* (not bottom pressure) is what makes water flow downhill and
  communicating vessels settle at equal surface lines, not equal volumes.
- **Gas approximation** (`density < 0`): head = `fillHeight − relDensity·baseY`
  — gases seek upward and fuller vessels push outward. Gas heads are NOT world
  elevations; display code must not mix them with block Y (the goggle budget
  bar and suction margin are suppressed for gases for exactly this reason).
- **Viscosity** scales conductance: `PIPE_CONDUCTANCE · (1000/viscosity) /
  (length+1)`. Honey is slow, water is fast. There is no travel time (no
  fronts) — viscosity affects steady rate only.
- **Reach is bounded by ELEVATION, not distance** (deliberate change from v1):
  a pump can push up to `supplyHead + |RPM|·headPerRpm` and pull from no lower
  than `supplyHead − SUCTION_LIMIT`. Horizontal runs only lower the *rate*
  (conductance), never stop flow. The v1 per-tile friction budget was dropped:
  static friction head conflicts with full equalization in a stateless engine.
  The puzzle lives in elevation: downhill is free, lifting costs RPM.

## 5. Per-tick pipeline

1. **`EngineTickHandler`** — pipes mark themselves dirty every tick via the
   transport-cancel mixin; `markChanged` (pump flip/speed, topology, valve)
   also wakes sleeping networks. Seeds dedupe through `Graph.coverage` so each
   network solves exactly once per tick; networks with no active flow and no
   transfers sleep 20 ticks (`IDLE_RECHECK_TICKS`).
2. **`FlowSolver.solve`** — collect columns, group present fluids by total
   volume (largest first), and run **one pass per fluid** on the shared
   topology. Endpoints holding a different fluid are walls for that pass;
   empty endpoints are receive-only and are claimed by the first pass that
   fills them. Fluids never mix within a tick.
3. **Branch assembly** (per edge, per pass): conductance from length/viscosity
   (capped by pump internal conductance), EMF from pump flanks, `allowedSign`
   from check valves + empty columns + the **lip rule**, crest data from the
   run's highest cell. Per-cell gates are honored: `canPullFluidFrom` rejection
   by closed valves / smart-pipe filters blocks the run (reason VALVE).
4. **Transfer planning** — per column, net inflow from the solve, clamped by
   `MAX_FLOW_PER_ENDPOINT`, the **lip drain cap**, and SIMULATE-probed
   drain/fill amounts; sources pair to sinks greedily. A pass that solved
   pressurized flow but planned nothing is **stalled** (reason SINK_FULL or
   SOURCE_DRY); a later pass that moves fluid over the same edge overrides the
   stall for display.
5. **`FluidEngine.apply`** — re-resolve handlers (world may have changed),
   simulate drain+fill, execute both or neither. The engine is read-only until
   this step.

**Lip rule**: fluid leaves a column only through an opening its surface
reaches (`surface > openingY − 0.5`), checked with Sable-aware geometry
(`canFluidReachPipe`); open ends are exempt (their mouth is submerged by
construction). The **lip drain cap** bounds outflow to half the volume above
the lowest flowing opening per tick (last `LIP_DREGS_MB = 4` mB may leave at
once), which settles tanks exactly at the opening instead of flapping.

## 6. Display fields (three head fields per solve — keep them distinct)

1. **Display heads** (`nodeHeads`) — the real fluid state. Anchored at
   reservoir surfaces, spread only along directions fluid could move, never
   backward through check valves or out of empty reservoirs; zero-flow
   branches continue the parent head unchanged. This deliberately drops the
   EMF jump of a dead-headed pump — no phantom vacuum/pressure on dry or
   gated lines. Gauge pressure at a cell = interpolated head − cellY.
2. **Ceilings** (`nodeCeilings`) — the *planning* field: friction-free
   potential = supply anchors + pump boosts, traversing ALL assembled branches
   (including ones the valves shut this tick — a pump line stopped by excess
   lift is precisely where the readout is needed). `boostAhead` reverse-relaxes
   pump boosts to suction sides; suction runs no reservoir can feed (empty
   source, lip-gated draw) are **self-anchored** at their own potential +
   boostAhead, gated on `boostAhead > 0` so dry pump-less lines stay blank.
   "Head left" = ceiling − elevation.
3. **Anchors** (`nodeAnchors`) — the supply surface each ceiling was seeded
   from, paired with the winning ceiling across fluid passes. Budget total =
   `ceiling − min(anchor, cellY)`; consumed = lift above the anchor. This
   feeds the goggle budget bar and is the intended trigger quantity for the
   future overpressure feature (`ceiling − cellY` vs a per-tier rating).

## 7. Player-facing UX (legibility is required, not polish)

- **Pipe goggle tooltip** (`PipeGoggleInfoMixin`, request/answer packets
  `PipeStatusRequest`/`PipeStatusPayload`, server-throttled 4 ticks, 64-block
  range): status (flowing/no-flow/blocked/stalled/no-head), fluid, flow rate +
  direction, "Head left: X / Y blocks", and a Create-boiler-style budget bar
  (`|` chars, green = head remaining with bright tip, dark red = consumed,
  dark gray padding to multiple of 5, cap 18; 1 bar per block when it fits).
  **Sneak adds**: gauge pressure, suction margin (= `SUCTION_LIMIT` + run-WORST
  pressure — the crest is the binding point, not the probed cell), the
  blocked/stalled culprit line (from `edgeReasons`, category-matched to the
  shown status), and fluid density/viscosity. Bar helpers live in
  `client/GoggleText`.
- **Pump goggle tooltip** (`PumpBlockEntityMixin` override + super for stress):
  status callout, "Pumping: rate / cap mB/t" (cap = |RPM|·flowPerRpm,
  client-side from synced server config), a 10-segment load bar (throughput
  utilization — NOT effort: a dead-headed pump reads empty with a status line
  explaining why), and "Head supplied" (|RPM|·headPerRpm). **Sneak** adds the
  load breakdown — the two factors that multiply to the bar: "Back-pressure
  passes: NN% (Δh / supplied blk)" (or "Gravity assist: +NN%" when Δh<0) and
  "Pipe friction passes: NN%". Derived from `Solution.PumpLoad` (headAgainst =
  emf − |q|/G, *unclamped* so assist shows; frictionFactor = G/internalG). The
  factors are presented multiplicatively, never as a summing waterfall, so they
  reconstruct the bar exactly even under gravity assist.
- **Pump range arrows** (`PumpRangeProbe` + `PumpRangeRenderer`): walk from the
  pump flagging cells reachable/starved against `pushCeiling = supplyHead +
  pumpHead` and `pullFloor = supplyHead − suction` (fallback `supplyHead =
  pump.worldY()` when no solve data); green push / blue pull / red starved
  animated arrows, preserved ~5 s after looking away (config).
- **/pipegraph** (`PipeGraphCommand` + `GraphOverlay`): renders the contracted
  graph at the crosshair — node boxes by kind, edge tubes with per-point
  gauge-pressure gradient (−8..+16 blocks), edge letters, 30 s lifetime.

## 8. Create / NeoForge integration (surgical override)

Replace the *engine*, not the blocks (`pipesnphysics.mixins.json`):

- `GravityFlowMixin` — cancels `FluidTransportBehaviour.tick()` on all pipes
  when `ENABLE_ENGINE` (skips virtual/ponder blocks) and marks the network
  dirty. This is the master suppression of Create's transport.
- `PumpBlockEntityMixin` — cancels `distributePressureTo`, watches FACING
  flips (`markChanged`), adds the pump goggle section.
- `PipeGoggleInfoMixin` — goggle info on Create's three pipe BE classes.
- `OpenEndedPipeMixin` — projects open-end output to world coordinates on
  Sable sub-levels.
- Accessors: `FluidTankAccessor` (window/width/height/inventory),
  `PipeConnectionAccessor` (flow field).
- **Kept intact**: Create's blocks, connection logic, kinetics/stress, and all
  `IFluidHandler` interop — machines keep receiving fluid exactly as before.
  `ENABLE_ENGINE=false` reverts to stock Create behavior cleanly.

Packets (`EnginePackets`, versioned "2"): GraphOverlay, PipeStatus (+Request),
PumpRange (+Request). Sim-affecting config is SERVER spec (synced to clients);
rendering toggles are CLIENT spec.

Key server config defaults: `PIPE_CONDUCTANCE 120` mB/t per block of head,
`PUMP_HEAD_PER_RPM 0.25` blocks, `PUMP_FLOW_PER_RPM 1.0` mB/t,
`SUCTION_LIMIT 8` blocks, `MAX_FLOW_PER_ENDPOINT 256` mB/t, `ENABLE_ENGINE
true`.

## 9. Sable integration (`compat/`, gated by `SableMixinPlugin`)

An `IMixinConfigPlugin` applies Sable mixins only when the respective Sable
classes exist (companion → tilted tank rendering; full physics → tank mass).
`SableCompat.getWorldY/getWorldPos` project sub-level positions into world
space — **all engine elevations go through this**, so plumbing on moving
contraptions uses true world heights. Tank mass (`FluidTankMassMixin`,
`ENABLE_DYNAMIC_TANK_MASS`) adds `FLUID_MASS_PER_BUCKET`-scaled weight with an
optional fill-based center-of-gravity shift (`EXPERIMENTAL_TANK_COG`); the
`addBlockMass` Vec3 is a sub-block offset (0–1), NOT absolute.
`FluidTankRendererMixin` (client) renders tilted fluid surfaces with a 20 Hz
wave simulation, viscosity-damped.

## 10. Verification loop

- `./gradlew test` — pure `NetworkSolver` JUnit tests (no Minecraft).
- `./gradlew runGameTestServer` — GameTests on real Create blocks (structure
  templates in `data/pipesnphysics/structure/`): equalization, gravity drain,
  open-end spill, pump-moves-all-fluid (lip regression), head-left on both
  pump sides (wet + idle/dry suction), boost stacking across pumps in series,
  stall/blocked reason reporting, pump-load breakdown (friction-limited via
  lava down a long run; verifies headFactor·friction == rate/cap). Note: some
  templates ship pre-filled tanks — drain before filling a different fluid.
- **Reproduce bugs as GameTests first** — `PipeProbe`/`FlowSolver` are
  directly probeable in them.
- GOTCHAS: Gradle incremental compilation goes stale constantly — run
  `compileJava --rerun` after edits or tests run against old classes.
  `run/config/pipesnphysics-server.toml` persists old config values when code
  defaults change; delete to regenerate. Template-relative coordinates shift
  at placement — locate pumps/tanks by scanning blocks at runtime, and read
  pump FACING at runtime (Create re-orients it once kinetics settle).
- Saved workflow `/review-fluid-engine` runs a multi-lens adversarial engine
  review.

## 11. Emergent behaviors (acceptance criteria — none are special-cased)

- **Water tower / gravity feed**: tall tank feeds consumers below, no pump;
  delivered pressure falls as it drains. ✅ GameTested.
- **Communicating vessels**: tanks settle at equal *surface lines*, not equal
  volumes. ✅ GameTested.
- **Siphon**: primed flow over a crest driven by surface difference; a crest
  more than `SUCTION_LIMIT` above the local head breaks the column (one-shot
  crest gate). Template exists (`pump_with_siphon`).
- **Booster pumps**: head budgets stack across pumps in series (boostAhead
  accumulates). ✅ GameTested.

## 12. Deferred / roadmap

- **Phase-1 charging fronts / travel time** (v1 §5): fluid currently arrives
  instantly at the solved rate. Re-adding visible front advance is the largest
  deferred feature; viscosity already differentiates steady rates.
- **Per-tile static friction head**: conflicts with full equalization while
  pipes are stateless; revisit only with a flow-dependent model.
- **Overpressure / burst** (next release): per-tier `pressureRating`;
  trigger = `ceiling − cellY` exceeding the rating on a WET pipe (display
  heads ignore dead-head EMF by design — use the ceiling field). Rupture can
  reuse the OpenEndedPipe spill path by treating the burst cell as a
  temporary OPEN_END. The budget/anchor data already measures "how far over".
- **Ponder scenes**: 7 scene structures staged in `assets/pipesnphysics/ponder/`
  (pump, siphon, uphill, drops, pressure, friction) but no `PonderPlugin`
  registers them yet; Create's own pump/pipe scenes teach the replaced
  mechanics and need suppressing (ponder-index filter or small mixin).
- **Collisions / BreakEvent** (v1 §6): no incompatible-fluid rupture yet;
  per-tick passes simply treat other fluids' endpoints as walls.
- Traveling-arrows render mode; lake-intake GameTest.
