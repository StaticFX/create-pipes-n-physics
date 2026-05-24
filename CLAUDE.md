# Fluid Network System — Implementation Spec

> Design document for implementation. The goal is a tick-based fluid simulation over a
> network of connected pipes. This spec is deliberately implementation-oriented: data
> model, per-tick algorithm, and exact rules for **flow direction**, which is the part
> that depends on the fluid.

---

## 1. Vocabulary

- **Pipe** — a single placed cell in the world.
- **Branch / Edge** — a maximal run of pipe cells each having ≤ 2 connections. Pressure and
  flow are uniform along a branch, so the whole run is represented as **one edge**.
- **Node** — anything that is not a plain pass-through: a junction (3–4 connections), a
  source, a sink, a pump, a tank, or a dead-end.
- **Network** — one connected component of the pipe graph. Independent networks never
  interact and are simulated separately.
- **Source / Sink** — boundary nodes that inject or consume fluid.
- **Pump** — a node that supplies a finite **head** `H`: it both drives flow (a term in the
  potential, see §4) and provides the budget that bounds how far it can push (see §4.5).

The simulation runs on the **contracted graph** (nodes + edges), never on the raw pipe
grid. The grid is only used to build and maintain the graph.

---

## 2. Data model

```
FluidType:
    id
    phase           # LIQUID or GAS
    density         # ρ, used for the gravity term and for mixing
    viscosity       # μ, drives BOTH: front-advance speed in Phase 1 (slower when thick)
                    # and steady flow rate in Phase 2 (effective conductance = COND / μ)

Node:
    id
    kind            # JUNCTION | SOURCE | SINK | PUMP | TANK | DEAD_END
    elevation       # y, world height of the node (integer block coord is fine)
    staticPressure  # driving potential contributed AT this node (0 for plain junctions;
                    # for a PUMP/SOURCE this equals its head H; negative for suction at a sink;
                    # for a TANK this is DYNAMIC: ρ·G·(base + fill/area) — its liquid surface
                    # height — recomputed each tick from fill (see §9: towers & equalization)
    head            # PUMP/SOURCE only: head budget H, consumed over distance/lift (see §4.5)
    edges           # incident edge ids

Edge (Branch):
    id
    a, b            # endpoint node ids (UNORDERED — the edge has no inherent direction)
    capacity        # total fluid volume the run can hold
    length          # number of pipe cells (for front advancement / resistance)
    resistance      # total friction of the run = sum of per-cell resistance (pipe-tier
                    # dependent); ≈ R_PER_TILE * length for a single tier
    fluid           # FluidType id, or null if empty
    fill            # 0..capacity, current amount of fluid
    phase           # EMPTY | CHARGING | STALLED | FLOWING | DRAINING (see §5.1)
    frontPos        # CHARGING/STALLED only: how far the fluid front has advanced (0..length)
    # If collisions can happen mid-pipe (see §6), also:
    #   column       # ordered list of (fluidId, amount) fronts along the edge

Network:
    nodes, edges
    dirty           # set true on any topology edit; pressures recomputed next tick
```

---

## 3. Constants / tunables

```
G            # gravity strength (game units, not real-world 9.81 unless you want that)
EPS          # potential deadband; |ΔΦ| <= EPS means "no flow" (prevents jitter)
Q_MAX        # max flow per edge per tick
COND         # base conductance (flow per unit ΔΦ); can be per pipe tier
R_PER_TILE   # friction head consumed per tile of pipe (bounds horizontal reach)
TAPER_MARGIN # head remaining below which flow tapers toward 0 (soft reach limit, no cliff)
PUMP_HEAD    # default head H a pump supplies; flat-ground reach ≈ PUMP_HEAD / R_PER_TILE
FRONT_K      # front-advance coefficient: tiles/tick = FRONT_K * headAtFront / viscosity
HYSTERESIS   # head margin a broken circuit must cross before FLOWING reverts (anti-flap)
```

---

## 4. Direction, reach & the potential model (the core)

### 4.1 The rule

Direction is **never stored on a pipe**. It is recomputed every tick as the sign of a
potential difference. Pipes are omnidirectional.

For each edge `e = (a, b)` carrying fluid `f`, compute a **drive potential** at each
endpoint:

```
Φ(node, f) = node.staticPressure  +  phaseSign(f) * f.density * G * node.elevation

phaseSign(f) = +1   if f.phase == LIQUID    # higher = wants to flow DOWN
             = -1   if f.phase == GAS       # higher = wants to flow UP (buoyancy)
```

Then:

```
ΔΦ = Φ(a, f) - Φ(b, f)

if  ΔΦ >  EPS:   flow a -> b
if  ΔΦ < -EPS:   flow b -> a
else:            no flow this tick
```

**The fluid only affects direction through `phaseSign(f)` and `f.density`.** The flow
loop itself does not branch on phase — it just compares two numbers. This is the whole
answer to "direction depends on the fluid."

If the edge is empty (`fluid == null`), there is no gravity term; direction comes purely
from `staticPressure` differences (e.g. a pump priming an empty line), or the edge is idle.

### 4.2 Why potential, not static pressure (common trap)

Static hydrostatic pressure is *highest at the bottom* of a column and is balanced at
rest, so "flow from high pressure to low pressure" makes liquids flow **uphill** — wrong.
What drives flow is potential energy: fluid moves toward the lower-energy configuration.
That is why the gravity term is `+ρ·g·y` for liquids (high water has high potential, flows
down) and `−ρ·g·y` for gases (low gas has high potential, rises). Use Φ, not raw pressure.

### 4.3 Worked examples

Vertical pipe. Node **Top** at `y=10`, node **Bottom** at `y=0`, no pumps
(`staticPressure = 0` on both), `G=1`.

- **Water** (LIQUID, ρ=1): `Φ(Top)=+10`, `Φ(Bottom)=0` → `ΔΦ(Top→Bottom)=+10 > 0` →
  flow **Top → Bottom**. Water falls. ✅
- **Gas** (ρ=1): `Φ(Top)=−10`, `Φ(Bottom)=0` → `ΔΦ(Top→Bottom)=−10 < 0` →
  flow **Bottom → Top**. Gas rises. ✅
- **Water with a pump** at Top adding `staticPressure=+50`: `Φ(Top)=60`, `Φ(Bottom)=0` →
  even stronger Top→Bottom. A pump at Bottom adding `+50` gives `Φ(Bottom)=50`,
  `Φ(Top)=10` → flow reverses to **Bottom → Top**: the pump beats gravity. ✅

### 4.4 Mixed networks

A node may touch edges carrying different fluids. Do **not** try to define one global
pressure per node. Always evaluate Φ **per edge using that edge's own fluid**, as above.
This keeps direction well-defined even when two fluids share a junction (and is exactly
where collisions are detected — see §6).

### 4.5 Reach: the head budget (bounding pump distance)

A pump supplies a finite **head** `H`. Moving fluid through the network *spends* head:
friction spends a little per tile of length, gravity spends a lot per tile of lift, and
drops *refund* it. Reach is bounded because once the budget is gone there is nothing left
to drive flow. **Friction and gravity are the same currency** — that is the whole model.

Head cost to traverse edge `e` from `a` to `b` (the flow direction) carrying fluid `f`:

```
cost(e, a→b, f) =  R_PER_TILE * e.length                                  # friction (always a loss)
                +  phaseSign(f) * f.density * G * (b.elevation - a.elevation)   # gravity (loss or refund)
```

- **Horizontal** (Δy = 0): cost = friction only → `R_PER_TILE * length`. This is what bounds
  a pump pushing across a flat run: reach ≈ `H / R_PER_TILE` tiles.
- **Uphill liquid** (b higher): gravity term positive → extra cost (lifting eats the budget).
- **Downhill liquid**: gravity term negative → refund; gravity can drive flow with no pump at all.
- **Gas**: `phaseSign` flips, so for gas "up is free, down costs" — it wants to rise.

Propagate remaining head outward from each pump/source over the contracted graph (a small
relaxation pass, cheap, only re-run when the network is `dirty`):

```
headAt[pump.output] = pump.head                   # H
relax over edges in flow direction:
    surplus(e) = headAt[a] - cost(e, a→b, f)       # head that survives the WHOLE edge
    headAt[b]  = max(headAt[b], surplus(e))
an edge reaches its far end only if surplus(e) > 0
```

Head is resolved **only at nodes**, and an edge is **atomic**: it either carries head across
its full length or it does not. Gate flow on `surplus` — the head left *after* paying the
edge's entire friction + lift cost — and **taper** near the limit instead of cliff-stopping:

```
reachFactor(e) = clamp(surplus(e) / TAPER_MARGIN, 0, 1)   # 1 with plenty of head, →0 at the edge of reach
```

This keeps flow **uniform along every edge** (conservation of mass — see §5): a run shares one
rate set by whether head survives its full length; there is never a live front and a dead tail
inside a single edge. What declines along a run is head/pressure, not flow rate. If a run is
longer than the budget, its whole flow tapers toward zero uniformly.

A run just past the limit trickles and visibly struggles before dying — the player's cue to
add a **booster pump**. A booster is just another PUMP node: it splits the edge into two
reachable edges and refreshes the budget, so "place a pump roughly every `H / R_PER_TILE`
tiles on flat ground" *emerges* from the model rather than being hard-coded. (You may still
draw the reach highlight ending at a specific cell mid-run — that is a per-cell presentation
calc and does not change the edge-level simulation.)

**Worked example (out of reach).** Pump head gives a flat reach of 20 tiles
(`H = 20 * R_PER_TILE`). A single 30-tile horizontal edge costs `30 * R_PER_TILE = 1.5H`, so
`surplus = H − 1.5H = −0.5H < 0` → `reachFactor = 0` → **the entire edge carries zero flow**,
not "20 flowing then 10 stale." It is one edge with one rate, and that rate is zero. To move
fluid the full 30, place a booster around tile ~15 (before the limit, where surplus is still
healthy — not at 20, where the taper leaves it trickling): edge A (0→15) flows, the booster
refreshes `H`, edge B (15→30) flows. Two short edges, each with its own uniform rate.

### 4.6 Feel & legibility (required, not polish)

Bounded reach is only good gameplay if the player can *see* the constraint. For this design
(plumbing-as-engineering) these are mandatory:

- **Show pump range** — on placement/hover, highlight reachable vs starved pipe, accounting
  for the lifts on the actual route.
- **Show the budget** — pump tooltip: head supplied vs head consumed by the connected run, so
  the player knows how close to the limit they are.
- **Taper, never cliff** (§4.5) — the last reachable stretch slows visibly; it must never snap
  from working to dead with no warning. The invisible cliff is the thing that feels like a bug.
- **Keep reach generous** — `H / R_PER_TILE` on the order of tens of tiles, so boosters are a
  scaling decision, not a per-screen tax.

**Design intent:** the puzzle lives in elevation. Downhill is free distance; lifting is what
costs pumps. Reward players who read the terrain — gravity-fed mains from a high reservoir,
pumps reserved for the climbs.

---

## 5. Simulation: the two-phase model & per-tick loop

Each edge moves through a small state machine. **Phase 1 (CHARGING)** is fluid physically
travelling: a front advances through the pipe, speed set by viscosity and remaining head, and
the pipe fills behind it. **Phase 2 (FLOWING)** is steady through-flow once a complete
source→sink circuit exists. The split gives two things for free: fluid that visibly takes time
to travel (slow for thick fluids), and the "fills what it can reach, then sits" behaviour for
runs that never reach a sink — without ever needing a partly-flowing edge in steady state.

### 5.1 Edge lifecycle

```
EMPTY      no fluid, nothing feeding it
CHARGING   front advancing; fill grows with frontPos; through-flow ≈ 0 (Phase 1)
STALLED    front stopped (head exhausted, no sink reached); static partial fill; flow = 0
FLOWING    full and part of a complete circuit; steady uniform through-flow (Phase 2)
DRAINING   circuit lost / pump off; fluid receding or depressurising
```

Transitions:

```
EMPTY    -> CHARGING   upstream pressure begins feeding the edge (pump, or an upstream front arrives)
CHARGING -> FLOWING    front reaches a valid accepting sink -> the whole primed path goes FLOWING
CHARGING -> STALLED    front speed hits 0 (headAtFront <= 0) before any sink is reached
STALLED  -> CHARGING   more head becomes available (booster placed, upstream pressure rises)
FLOWING  -> DRAINING   circuit breaks: sink removed/full, pump off, or pipe cut
                       (require the deficit to exceed HYSTERESIS before reverting — anti-flap)
any      -> EMPTY      fully drained
```

The front position only exists while an edge is CHARGING/STALLED — that is the *only* place the
contraction is relaxed, and it is bounded to the advancing frontier. The instant an edge goes
FLOWING it is atomic again (just `fill` + one rate).

### 5.2 Per-tick loop

Run once per network per tick:

```
1. If network.dirty: rebuild graph; recompute node head/staticPressure and the reach
   field headAt[] (§4.5); clear dirty.

2. For each edge e with fluid f, branch on e.phase:

   CHARGING:
       # head left at the moving front (elevation interpolated along the edge)
       headAtFront = headAt[upstream(e)]
                     - R_PER_TILE * e.frontPos
                     - phaseSign(f) * f.density * G * (elevAt(e, frontPos) - elev(upstream(e)))
       v = max(0, FRONT_K * headAtFront / f.viscosity)        # tiles this tick
       e.frontPos += v * dt
       e.fill      = (e.frontPos / e.length) * e.capacity
       if e.frontPos >= e.length:
           if downstream(e) is/links to an accepting sink: prime the path -> set e.phase = FLOWING
           else: spawn CHARGING fronts into the downstream edges at the junction
       elif v == 0:
           e.phase = STALLED

   FLOWING:
       compute ΔΦ (§4.1); if |ΔΦ| <= EPS: flow = 0
       Q  = clamp((COND / f.viscosity) * |ΔΦ|, 0, Q_MAX)      # viscosity sets steady rate
       Q *= fullnessFactor(e)                                 # stability feedback (required)
       Q *= reachFactor(e)                                    # head-budget gate + taper (§4.5)
       Q  = clamp(Q, 0, min(upstream.fill, downstream.freeSpace))   # conservation (required)
       advance fluid by Q * dt
       if circuit broken by more than HYSTERESIS: e.phase = DRAINING (or CHARGING)

   STALLED:
       if headAtFront would now be > 0 (e.g. a booster was added): e.phase = CHARGING

   EMPTY / DRAINING: idle, or recede fill; return to EMPTY when drained.

3. Resolve nodes (conserve volume):
       sum inflows/outflows; distribute to outgoing edges by demand;
       run COLLISION CHECK (see §6).

4. Boundary nodes: SOURCE injects up to its rate; SINK consumes up to its rate.
```

Two rules in the FLOWING branch are non-negotiable: `fullnessFactor` (pull proportional to how
full the source side is) is the negative feedback that lets flow settle instead of slamming
on/off, and the `min(upstream.fill, downstream.freeSpace)` clamp conserves fluid — that clamp,
not friction, is what actually prevents runaway "infinite" flow.

Note the front naturally **decelerates** as it advances (headAtFront shrinks with distance and
lift) and stalls exactly at the reach limit — the Phase-1 speed curve and the §4.5 reach bound
are the same physics, so you get the visible "running out of steam" effect for free.

---

## 6. Collisions & breakage

Each edge carries one fluid id (or a column of fronts). A collision is two **incompatible**
fluids meeting. **Decide the granularity first — it changes the data model:**

- **Junction-only collisions (simpler, recommended first):** an edge holds a single fluid
  as a unit (`fluid` + `fill`, no column). A collision is detected in step 4 when two edges
  try to push different fluids into the same node. Emit a `BreakEvent(node or edge)`.
- **Anywhere-in-pipe collisions:** an edge is a `column` of ordered `(fluidId, amount)`
  fronts that advance per tick. When an advancing front meets a different fluid inside the
  same edge, emit a `BreakEvent(edge, position)`. More expensive; only do this if mid-pipe
  collisions are a real gameplay feature.

`BreakEvent` is consumed by the world layer to actually rupture pipes / spill fluid.

---

## 7. Topology maintenance

Topology changes only on player edits, not per tick. Maintain the contracted graph
incrementally:

- **Place / connect a pipe:** extend an edge, split an edge at a new junction, or merge two
  networks. Track network membership with union-find. Re-walk only the cells around the
  edit. Mark affected network `dirty`.
- **Remove a pipe:** may split an edge (trivial) or split a whole network in two. Do a
  bounded flood-fill from the two cut ends, scoped to the affected network only, to decide
  connectivity and re-derive components if separated. Mark affected network(s) `dirty`.

Do **not** rediscover topology during the flow loop, and do **not** recompute the whole
graph every tick — only on edits.

---

## 8. Open decisions for the implementer

**Decided:** bounded reach via the head-budget model (§4.5), plumbing-as-engineering. This
means elevation routing and pump placement are intended gameplay, and the §4.6 legibility
items are required, not optional. Fluid **takes time to travel** (Phase 1 front advance, §5),
with speed driven by viscosity — so transport latency is a deliberate gameplay quantity.

Still open:
1. **Collision granularity** (§6) — junction-only vs mid-pipe. Pick before coding the edge
   model; everything downstream depends on it. **Note:** siphons (§9) require the mid-pipe
   option — an edge must know whether its crest is liquid-full or has a gas break.
2. **Friction model** — flow-independent (`R_PER_TILE * length`; predictable, legible reach —
   start here) vs flow-dependent (`R_PER_TILE * length * Q`; more realistic but couples reach
   to throughput and is harder to balance).
3. **Units for G / density / R_PER_TILE / head** — pick game-feel numbers; they only need to
   be internally consistent in the head currency, not physically real.
4. **What counts as "incompatible"** for collisions — different fluid ids, or a
   compatibility table allowing some mixing.
5. **Override vs coexist** (§10) — **leaning override** (surgical Mixin: cancel Create's fluid
   *movement* only, run our engine over all pipes), accepted as a fluid-overhaul mod. Tax:
   Mixin targets track Create versions/loader and can conflict with other fluid/perf mods.
   Coexist (own pipe tier at `IFluidHandler` endpoints) remains the lower-risk fallback.
6. **MC version + loader** (e.g. 1.20.1 Forge vs 1.21.x NeoForge) — sets the capability API,
   mixin targets, and which Create version you track.

---

## 9. Emergent behaviors (acceptance tests)

These should **fall out of the model**, not be special-cased. Each is also a good integration
test — a small build with a known correct outcome.

**Water tower / gravity feed.** A tall TANK contributes head from its surface elevation
(`ρ·G·(base + fill/area)`, see §2). Place it above consumers and it feeds them with no pump,
because their Φ is lower. *Expected:* flow without a pump; delivered pressure falls as the tank
drains. Requires dynamic tank head.

**Tank fill equalization (communicating vessels).** Connect two tanks low. Flow runs from the
higher *surface* to the lower (`Φ` from surface height) and stops when surfaces level — via the
EPS deadband. *Expected:* equal surface heights, NOT equal volumes; tanks with different base
elevation/footprint settle at the same water line with different amounts. Requires dynamic tank
head + `fullnessFactor` for a smooth settle.

**Siphon.** Source surface above an outlet, with the pipe cresting *above* the source between
them. *Expected (primed):* steady flow over the crest, driven by `source surface − outlet
height`; the crest height cancels because gravity in the §4.5 `cost` is signed (up costs, down
refunds). *Expected (priming from rest):* the Phase-1 front climbs the crest only if it has
enough head — a too-high crest stalls and never primes (the engine's analog of the real
cavitation height limit). **Requires the mid-pipe column model (§6):** a gas pocket at the
crest must break the liquid continuity and stop the siphon. Junction-only collisions cannot
represent this. Verify: siphon runs while primed; stops when the source drops below the crest
or air breaks the column; never flows when the outlet is above the source surface.

---

## 10. Minecraft / Create integration

This model is intended to replace or augment Create's fluid network. The abstract entities map
cleanly onto Create / Forge·NeoForge:

| Spec entity | Create / MC |
| --- | --- |
| Node | `BlockPos` of a pump, tank/basin, spout, item drain, portable interface, or pipe junction; `elevation = pos.getY()` |
| Edge | a run of Create fluid pipe between nodes; contract by walking pipe connections (the BeltChainComputer chain-walk, reused) |
| Pump head `H` | derived from rotational speed (Create scales throughput with RPM); reversed rotation flips the sign of the pump's potential term; tie stress/SU cost to delivered head |
| Tank/basin head | `ρ·G·(pos.getY() + fillFraction·height)` read from the `IFluidHandler` fill level (§9) — enables towers/equalization/siphons, which Create itself does not do |
| FluidType ρ, μ | assigned per Forge fluid via datapack/config with defaults; Create's honey & chocolate are natural high-μ test fluids |
| Endpoints | must speak `IFluidHandler` so basins, spouts, drains, tanks, and other mods keep interoperating |

Conceptually this **generalizes what Create already approximates**: its fixed ~15-block pump
range becomes emergent reach (§4.5); its startup propagation delay becomes Phase 1 charging
(§5); its RPM-based throughput becomes pump head. What Create lacks — gravity, head, siphons,
towers — is what this adds (§9). Note Create pipes do not store fluid (they move it between
inventories), so per-edge `fill` is a new concept layered on top.

**Platform constraints the engine spec does not cover:**

- **Tick budget.** 20 TPS, ~50 ms shared server-wide. Tick only `dirty`/active networks; the
  contracted graph is mandatory. Keep topology rebuilds (on every pipe place/break) incremental
  and amortized.
- **Chunk loading.** Networks span chunks; parts unload and stop ticking. Freeze unloaded
  portions (persist their state), resume on reload, and never simulate into unloaded chunks.
  Design this in from the start — it is the classic Minecraft fluid-mod hazard.
- **Persistence (NBT).** Per-block fluid amount is authoritative and saved; rebuild the graph by
  re-walking pipes on load. Persist phase/frontPos, or reload conservatively as idle/STALLED and
  let the sim re-settle.
- **Server-authoritative.** Run the sim server-side only; sync fluid level + flow direction +
  front position to clients for rendering, reusing Create's windowed-pipe fluid rendering.

See §8.5 for the override-vs-coexist decision, which shapes the whole module structure.

### 10.1 Override architecture (surgical cancel)

If overriding (the current leaning), keep it narrow — replace the *engine*, not the blocks:

- **Cancel only fluid movement.** Suppress Create's per-tick fluid transfer (its transport
  behaviour) and the network propagation that feeds it, via a cancellable `@Inject` at a stable
  high-level point — not `@Overwrite` (most fragile, most conflict-prone). Drive all transfer
  yourself from one server-tick pass over the contracted networks.
- **Keep Create's blocks intact** — connection/shape logic, capabilities, and rendering all stay.
  You swap the engine under the pipes, you do not reimplement the pipes.
- **Pipe-ness is a predicate.** Treat "is this a pipe node/edge" as a predicate over block type;
  default it to anything Create treats as a pipe, so Create's pipes *and* addon pipes that ride
  on Create's transport are captured automatically. This is the main payoff of overriding.
- **Deferral list for smart components.** Capturing everything also captures components with
  semantics the physics engine does not model — Create's Smart Fluid Pipes and Fluid Valves,
  addons' filtered/one-way/priority pipes. Either reimplement these in-engine or exclude them
  from capture; otherwise they silently degrade to dumb conduits.
- **Do NOT cancel capabilities or pump kinetics.** Endpoints interoperate via `IFluidHandler`
  (read/write exactly as Create does, or machines stop receiving fluid). Keep the pump block,
  its rotation, and stress cost — only reinterpret its RPM/direction as head and sign.

### 10.2 Pipe tiers (additive)

With pipe-ness a predicate, new pipe tiers slot into the same engine. A tier is
`(resistance, capacity, pressureRating)`:

- `resistance` → per-tier `R_PER_TILE` (low-friction trunk lines vs cheap thin pipe).
- `capacity` → buffer/throughput feel.
- `pressureRating` → ties into §6: exceeding it **bursts** the pipe (a BreakEvent). Gives tiers
  meaning and a failure mode — a cheap pipe over-pumped or at a tower base will rupture.

Your tiers, Create's pipes, and addon pipes all share one network and one engine.

---

## 11. Configuration & feature flags

Everything is toggleable so users can disable anything that breaks. There are **two layers**,
because they load at different times:

**Layer 1 — Mixin-application gating (load time).** Mixins apply at class load, *before*
Forge/NeoForge `ModConfigSpec` is read, so the master "disable the Create override" switch
cannot be a normal runtime flag. Gate it in an `IMixinConfigPlugin.shouldApplyMixin(...)` that
reads an early, simple source (its own file or a system property). Disabled → the cancel mixin
never applies → Create's fluid logic runs untouched. (§10.1's cancel-only design is what makes
this a clean, stateless revert.)

**Layer 2 — Runtime feature flags (`ModConfigSpec`).** Everything that isn't a mixin — pressure
model, siphons, viscosity travel, tank-surface head, bursting, per-tier behavior — is ordinary
config read at load/tick. Put all sim-affecting flags in **SERVER / world** config, never
client (or multiplayer desyncs); only rendering toggles are client-side.

**Highest-value flag: capture exclusion.** The override's real failure mode is "mod X's pipes
misbehave," not total breakage. So the most useful lever is a per-mod / per-block **denylist**
that drops blocks from the capture predicate (§10.1) — they fall back to Create's handling while
your features keep working everywhere else. Ship known-incompatible combos pre-denylisted.

**Respect the dependency graph.** Foundational vs leaf:
- *Foundational:* the pressure/gravity model. Off → just Create with your pipe blocks.
- *Leaves (require foundational):* siphons, towers, equalization, viscosity travel, bursting.

Disabling a foundational flag must no-op its dependents, not leave half-states. Do not expose
physically incoherent combinations (e.g. siphons-on / gravity-off).

**Caveats.**
- Apply engine toggles on world (re)load, not live: switching engines mid-world can strand
  fluid that existed only in the per-edge `fill` (Create stores fluid in endpoints, not pipes).
  On switch, flush pipe-resident fluid back into endpoints or void it.
- Every boolean doubles the nominal test surface. Keep flags coarse, and document which
  combinations are actually tested rather than implying all 2^n work.