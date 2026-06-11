export const meta = {
  name: 'review-fluid-engine',
  description: 'Adversarial multi-lens review of the new hydraulic fluid engine',
  phases: [
    { title: 'Review', detail: '4 reviewers with distinct lenses' },
    { title: 'Verify', detail: 'adversarial refutation of each finding' },
  ],
}

const ROOT = '/Users/devin/IdeaProjects/create-pipes-n-physics'
const FILES = [
  'src/main/java/de/devin/pipesnphysics/engine/solve/NetworkSolver.java',
  'src/main/java/de/devin/pipesnphysics/engine/FlowSolver.java',
  'src/main/java/de/devin/pipesnphysics/engine/BoundaryColumn.java',
  'src/main/java/de/devin/pipesnphysics/engine/FluidEngine.java',
  'src/main/java/de/devin/pipesnphysics/engine/EngineTickHandler.java',
  'src/main/java/de/devin/pipesnphysics/engine/Solution.java',
  'src/main/java/de/devin/pipesnphysics/engine/Graph.java',
  'src/main/java/de/devin/pipesnphysics/engine/GraphBuilder.java',
  'src/main/java/de/devin/pipesnphysics/engine/Edge.java',
  'src/main/java/de/devin/pipesnphysics/engine/Node.java',
  'src/main/java/de/devin/pipesnphysics/mixin/GravityFlowMixin.java',
  'src/main/java/de/devin/pipesnphysics/mixin/PumpBlockEntityMixin.java',
  'src/main/java/de/devin/pipesnphysics/mixin/FluidTankAccessor.java',
  'src/main/java/de/devin/pipesnphysics/PipesNPhysicsConfig.java',
  'src/test/java/de/devin/pipesnphysics/engine/solve/NetworkSolverTest.java',
].map(f => ROOT + '/' + f)

const CONTEXT = `
You are reviewing a NEW fluid-physics engine for a Minecraft NeoForge 1.21.1 mod that overrides
Create 6.0.10's pipe transport. Read the design spec at ${ROOT}/CLAUDE.md first, then read ALL of
these files closely:
${FILES.join('\n')}

Architecture: pipes are contracted into a graph (GraphBuilder). Each tick per network,
FlowSolver maps tanks/handlers to hydraulic columns (head = fluid surface height in blocks,
capacitance = mB per block), pipe runs to conductances, pumps to EMF sources with check valves,
then NetworkSolver does ONE implicit-Euler step (unconditionally stable, monotone convergence)
with an active-set loop for one-way and crest (cavitation/siphon) gates. FlowSolver plans
endpoint-to-endpoint transfers; FluidEngine executes them with IFluidHandler simulate-then-execute.
Pipes store no fluid (stateless engine). The mixins cancel Create's own transport and mark
networks dirty each tick; EngineTickHandler dedupes so each network solves exactly once per tick.

HISTORY: the project owner's previous attempts ALL failed with oscillating pipes or infinite
flow cycles. Stability is the #1 acceptance criterion, fluid conservation #2.
`

const FINDINGS_SCHEMA = {
  type: 'object',
  required: ['findings'],
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        required: ['title', 'file', 'detail', 'severity'],
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'number' },
          detail: { type: 'string', description: 'concrete scenario that triggers the bug, step by step' },
          severity: { type: 'string', enum: ['critical', 'major', 'minor'] },
          suggestedFix: { type: 'string' },
        },
      },
    },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  required: ['refuted', 'reasoning'],
  properties: {
    refuted: { type: 'boolean', description: 'true if the finding is NOT a real bug' },
    reasoning: { type: 'string' },
  },
}

const LENSES = [
  {
    key: 'oscillation',
    prompt: `${CONTEXT}
LENS: OSCILLATION & INFINITE CYCLES. Hunt for any remaining path to sustained oscillation or
runaway flow ACROSS ticks (the solver is provably stable within one tick — the danger is the
world loop): integer rounding ping-pong between tanks near equilibrium; lip-gate (canDrawFrom)
flapping when a surface hovers at an opening; check-valve or crest active-set decisions
alternating tick to tick; the claimedEmpties set flip-flopping which fluid claims a tank between
ticks; transfers planned from stale state then applied; interactions between two fluid groups
sharing junctions; EngineTickHandler coverage misses letting one network tick twice; pump EMF
read from live RPM while Create flips the pump blockstate. For each candidate, trace the exact
tick-by-tick cycle and state WHY it sustains rather than damps. Only report findings where you
can name the sustaining mechanism. Report at most your 6 strongest findings.`,
  },
  {
    key: 'conservation',
    prompt: `${CONTEXT}
LENS: FLUID CONSERVATION. Hunt for duplication or destruction: planning vs apply divergence
(plan computed from snapshot, world changed); the same source drained by transfers from two
fluid groups in one tick; multiblock tank merged columns double-counting capacity or fill;
rounding source give vs sink take; a transfer pair where drain EXECUTE returns less than
SIMULATE promised; creative/infinite tanks; handlers with multiple internal tanks of different
fluids; the greedy pair matcher exceeding what the solver said a column should give/take.
Trace exact mB amounts through one tick. Report at most your 6 strongest findings.`,
  },
  {
    key: 'api',
    prompt: `${CONTEXT}
LENS: CREATE / NEOFORGE API CORRECTNESS. Check against Create 6.0.10 and NeoForge 21.1.219
(jar available at ~/.gradle/caches/modules-2/files-2.1/com.simibubi.create/create-1.21.1/6.0.10-280/*/create-1.21.1-6.0.10-280-slim.jar — use javap to verify signatures and behavior):
FluidTankBlockEntity.getControllerBE() nullability and timing (unloaded controller, mid-assembly);
the FluidTankAccessor mixin cast in BoundaryColumn; pump FACING/speed conventions (does positive
speed push toward FACING in 6.0.10? does the blockstate flip on reverse?); getCapability with
null side vs the side Create exposes; FluidStack copyWithAmount/isSameFluidSameComponents usage;
KineticBlockEntity.getSpeed() client/server; config access timing (server config values read
during early ticks or before load); mixin targets still valid; basin/spout/drain behaviors as
1-block columns; getFluidInTank returning views that must not be mutated. Report at most your 6
strongest findings.`,
  },
  {
    key: 'spec',
    prompt: `${CONTEXT}
LENS: SPEC COMPLIANCE & INTEGRATION GAPS. Compare CLAUDE.md's required behaviors against the
implementation, focusing on things that would make the mod feel broken in-game: water tower /
gravity feed; tank equalization (equal surfaces, not volumes); pump head emergent reach and
taper-not-cliff; reversed pump flips direction; siphon behavior vs the crest gate; what happens
at chunk borders (graph truncation mid-network — can a truncated graph misroute fluid?); dead-end
pipes; open-ended pipes (intentionally not endpoints — verify nothing breaks); the /pipegraph
overlay consistency with the new Solution; Smart pipes / valves being captured but their
semantics dropped (deferral list, spec 10.1); en_us.json or registered strings now stale.
Report at most your 6 strongest findings. Do NOT report deferred Phase-1 charging/travel-time
as missing — it is explicitly out of scope for this iteration.`,
  },
]

phase('Review')
const results = await pipeline(
  LENSES,
  lens => agent(lens.prompt, { label: `review:${lens.key}`, phase: 'Review', schema: FINDINGS_SCHEMA }),
  (review, lens) => {
    if (!review || !review.findings.length) return []
    return parallel(review.findings.map(f => () =>
      parallel(['trace-the-code', 'runtime-behavior'].map(angle => () =>
        agent(`${CONTEXT}
A reviewer claims this bug exists. Your job is to REFUTE it if you can — read the actual code
and check whether the claimed scenario really happens. Be skeptical: reviewers often miss a
guard, a clamp, or an early return. Angle: ${angle === 'trace-the-code' ? 'line-by-line code trace of the claimed path' : 'simulate the runtime scenario tick by tick with concrete numbers'}.

CLAIM (lens ${lens.key}): ${f.title}
File: ${f.file}${f.line ? ' line ~' + f.line : ''}
Severity claimed: ${f.severity}
Scenario: ${f.detail}

If the scenario truly occurs as described, refuted=false. If a guard prevents it, the API
behaves differently, or the effect is cosmetic/negligible, refuted=true with the exact reason.`,
          { label: `verify:${lens.key}:${f.title.slice(0, 30)}`, phase: 'Verify', schema: VERDICT_SCHEMA })))
        .then(verdicts => {
          const live = verdicts.filter(Boolean)
          const upheld = live.filter(v => !v.refuted).length
          return { ...f, lens: lens.key, upheld, of: live.length, reasons: live.map(v => v.reasoning) }
        })
    ))
  }
)

const all = results.filter(Boolean).flat().filter(Boolean)
const confirmed = all.filter(f => f.upheld === f.of && f.of > 0)
const contested = all.filter(f => f.upheld > 0 && f.upheld < f.of)
log(`${all.length} findings, ${confirmed.length} confirmed, ${contested.length} contested`)
return { confirmed, contested, refuted: all.filter(f => f.upheld === 0).map(f => f.title) }