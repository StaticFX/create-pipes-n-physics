package de.devin.pipesnphysics.engine.graph;

import de.devin.pipesnphysics.PipesNPhysicsConfig;
import de.devin.pipesnphysics.engine.Solution;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caches built {@link Graph}s across ticks, keyed by every coverage cell, so an unchanged network
 * skips the full BFS re-discovery (blockstate, block-entity, and capability probes per cell) that
 * used to run before every solve.
 *
 * Invalidation is dirty-gated: {@link EngineTickHandler#markChanged} evicts around every position it
 * wakes, which carries all evented topology changes for free (break/place via NetworkEditHandler,
 * pump FACING flips, valve angle changes, pipe swaps, hose-pulley priming, PSI docking, chunk-load
 * heals, sub-level connection refreshes). Chunk UNLOADS evict through the chunk index — a cached
 * graph must never outlive its chunks, or the solve would fetch block entities from unloaded
 * positions and force synchronous chunk loads. Tag and server-config reloads flush everything
 * (block tags and the valve-throttle toggle are build-time inputs).
 *
 * Some topology edits fire NO signal at all: a wrench re-routing a pipe, pistons, explosions,
 * Create contraption assembly (which removes blocks with neighbor updates suppressed), a mouth
 * freezing over. Entries therefore EXPIRE: after {@link #ACTIVE_TTL_TICKS} on a network that is
 * moving fluid or holds a running pump — bounding how long a stale edge could route fluid across a
 * silently removed run — and after {@link #IDLE_TTL_TICKS} otherwise, matching the sleep heartbeat
 * a settled network already waits out today. Transfers stay conservation-safe under a stale graph
 * regardless (drain and fill are SIMULATE-checked against re-resolved handlers every apply); the
 * TTL bounds mis-PLACED flow, not loss or duplication.
 *
 * A Sable contraption that merely MOVES does not invalidate: its plot-coordinate topology is
 * motion-invariant and every solve-relevant elevation is re-read fresh each solve, so the driver
 * wakes those networks through {@link EngineTickHandler#markMoved} instead — the cached graph is
 * re-solved, not rebuilt. Only display-only {@code Node.worldY} lags, at most one TTL.
 *
 * A rebuild WRITES THROUGH every coverage key and fully evicts any entry it displaces, so a pipe
 * placed by a schematicannon that bridges two cached networks replaces both with the merged graph
 * instead of leaving three graphs solving the same cells. Server-thread only; cleared on stop.
 */
public final class GraphCache {
    /** Rebuild cadence for a network moving fluid or holding a running pump (the armed recheck cadence). */
    static final int ACTIVE_TTL_TICKS = 4;
    /** Rebuild cadence for a settled network (the idle sleep heartbeat). */
    static final int IDLE_TTL_TICKS = 20;
    /** Age past which an entry belongs to a dead network (see {@link #sweep}) — well past any sleep. */
    static final int ORPHAN_TICKS = 10 * IDLE_TTL_TICKS;

    private static final Map<ResourceKey<Level>, Map<BlockPos, Entry>> CELLS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<Long, Set<Entry>>> CHUNKS = new HashMap<>();
    /** Each stored graph's own entry by IDENTITY — see {@link #entryOf}. */
    private static final Map<Graph, Entry> BY_GRAPH = new IdentityHashMap<>();

    private static final class Entry {
        final Graph graph;
        final long builtAt;
        final Set<Long> chunks;
        boolean activeTtl;
        Solution solution;
        long solvedAt = Long.MIN_VALUE; // MIN_VALUE = never solved

        Entry(Graph graph, long builtAt, Set<Long> chunks) {
            this.graph = graph;
            this.builtAt = builtAt;
            this.chunks = chunks;
        }

        int ttl() {
            return activeTtl ? ACTIVE_TTL_TICKS : IDLE_TTL_TICKS;
        }
    }

    private GraphCache() {}

    private static boolean enabled() {
        return PipesNPhysicsConfig.ENABLE_NETWORK_CACHE.get();
    }

    /** The cached, still-fresh graph covering pos, or null. An expired entry is evicted on sight. */
    public static Graph get(Level level, BlockPos pos, long now) {
        if (!enabled()) return null;
        Entry entry = entryAt(level, pos);
        if (entry == null) return null;
        if (now - entry.builtAt >= entry.ttl()) {
            evict(level.dimension(), entry);
            return null;
        }
        return entry.graph;
    }

    /** Cache a freshly built graph under every coverage cell, fully evicting anything displaced. */
    public static void store(Level level, Graph graph, long now) {
        if (!enabled() || graph.isEmpty()) return;
        Set<Long> chunks = new HashSet<>();
        for (BlockPos cell : graph.coverage()) chunks.add(ChunkPos.asLong(cell));
        Entry entry = new Entry(graph, now, chunks);

        Map<BlockPos, Entry> cells = CELLS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        for (BlockPos cell : graph.coverage()) {
            // A cell two live networks legitimately share — the space block two open mouths face
            // (both halves of a broken run cover the gap), or a per-face side-specific handler —
            // must not DISPLACE: claiming it would evict the other network on every store and both
            // would rebuild every tick. First claimant keeps the key; it only serves existence
            // checks (isCovered) and never graph lookups, since only markChanged marks such cells.
            if (shareable(graph, cell)) {
                cells.putIfAbsent(cell, entry);
                continue;
            }
            Entry displaced = cells.put(cell, entry);
            if (displaced != null && displaced != entry) evict(level.dimension(), displaced);
        }
        Map<Long, Set<Entry>> chunkIndex = CHUNKS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        for (Long chunk : chunks) chunkIndex.computeIfAbsent(chunk, k -> new HashSet<>()).add(entry);
        BY_GRAPH.put(graph, entry);
    }

    /**
     * Record this tick's solve on the graph's entry: the solution (served to goggle probes while
     * fresh) and whether the network is busy or armed, which picks the fast TTL.
     */
    public static void recordSolve(Level level, Graph graph, Solution solution, long now, boolean fast) {
        Entry entry = entryOf(graph);
        if (entry == null) return;
        entry.solution = solution;
        entry.solvedAt = now;
        entry.activeTtl = fast;
    }

    /**
     * How many ticks ago the engine last SOLVED this graph, or -1 if never. Diagnostic only: a
     * network the engine is driving should read 0-4 ticks (the armed cadence) or up to the idle
     * heartbeat, and anything older means it is not being ticked at all — which no gate readout
     * can tell you, because the gates are simply never consulted.
     */
    public static long ticksSinceSolve(Graph graph, long now) {
        Entry entry = entryOf(graph);
        return entry == null || entry.solvedAt == Long.MIN_VALUE ? -1 : now - entry.solvedAt;
    }

    /** The entry's last solution if it belongs to this exact graph and is at most maxAge ticks old. */
    public static Solution recentSolution(Level level, Graph graph, long now, int maxAge) {
        Entry entry = entryOf(graph);
        return entry != null && entry.solution != null && now - entry.solvedAt <= maxAge
                ? entry.solution : null;
    }

    /** Whether pos is a coverage cell of any cached network (regardless of freshness). */
    public static boolean isCovered(Level level, BlockPos pos) {
        return enabled() && entryAt(level, pos) != null;
    }

    /** Evict the network covering pos and the ones covering its six neighbors (a topology edit). */
    public static void invalidateAround(Level level, BlockPos pos) {
        invalidate(level, pos);
        for (Direction dir : Direction.values()) {
            invalidate(level, pos.relative(dir));
        }
    }

    /** Evict the network covering pos, if any. */
    public static void invalidate(Level level, BlockPos pos) {
        Entry entry = entryAt(level, pos);
        if (entry != null) evict(level.dimension(), entry);
    }

    /** Evict every network reaching into this chunk — a cached graph must not outlive its chunks. */
    public static void invalidateChunk(Level level, ChunkPos pos) {
        Map<Long, Set<Entry>> chunkIndex = CHUNKS.get(level.dimension());
        if (chunkIndex == null) return;
        Set<Entry> entries = chunkIndex.get(pos.toLong());
        if (entries == null) return;
        for (Entry entry : List.copyOf(entries)) evict(level.dimension(), entry);
    }

    /**
     * When the graph's entry expires, or {@link Long#MAX_VALUE} without one. The tick handler clamps
     * a network's sleep to this, so the wake that ends a sleep never re-solves a graph OLDER than
     * the sleep itself — a silent edit early in the sleep must not outlive the heartbeat bound.
     * (Clamping, not evicting: a cruising contraption is re-woken by markMoved every tick and hits
     * the sleep branch just as often — eviction there would discard its entry mid-TTL.)
     */
    public static long expiry(Level level, Graph graph) {
        Entry entry = entryOf(graph);
        return entry == null ? Long.MAX_VALUE : entry.builtAt + entry.ttl();
    }

    /**
     * Drop entries no live network can refresh. A network that still exists re-stores its entry at
     * latest one heartbeat after expiry (its pipes keep marking dirty), so anything older than
     * {@link #ORPHAN_TICKS} is a network that died with NO signal — a disassembled Sable contraption
     * (plot chunks never fire vanilla unload events), an exploded run, a contraption-assembly
     * pickup — and would otherwise be retained until chunk unload or server stop. Memory-only:
     * get() already refuses expired entries. Called on a slow cadence by the tick handler.
     */
    public static void sweep(Level level, long now) {
        Map<BlockPos, Entry> cells = CELLS.get(level.dimension());
        if (cells == null || cells.isEmpty()) return;
        Set<Entry> dead = new HashSet<>();
        for (Entry entry : cells.values()) {
            if (now - entry.builtAt >= ORPHAN_TICKS) dead.add(entry);
        }
        for (Entry entry : dead) evict(level.dimension(), entry);
    }

    /** Drop everything — server stop, tag reload, or server-config reload. */
    public static void clear() {
        CELLS.clear();
        CHUNKS.clear();
        BY_GRAPH.clear();
    }

    /**
     * Whether this cell is one two live networks legitimately share — the space block two open
     * mouths face, or a per-face side-specific handler — and is therefore claimed putIfAbsent
     * instead of displacing the other network's entry (see {@link #store}).
     */
    private static boolean shareable(Graph graph, BlockPos cell) {
        Node node = graph.nodeAt(cell);
        return node != null
                && (node.isOpenEnd() || (node.isHandler() && node.accessFace() != null));
    }

    private static Entry entryAt(Level level, BlockPos pos) {
        Map<BlockPos, Entry> cells = CELLS.get(level.dimension());
        return cells == null ? null : cells.get(pos);
    }

    /**
     * The entry that owns this exact {@link Graph} instance, or null once evicted. Resolved by
     * graph IDENTITY, never through a coverage cell: a cell key can legitimately belong to a
     * SIBLING network (the shareable cells {@link #store} claims putIfAbsent — an open-end gap
     * two mouths face, a per-face handler), and coverage iterates in hash order, so a cell-based
     * lookup intermittently missed the graph's own entry — its solves went unrecorded (a busy
     * network kept the IDLE TTL) and its expiry never clamped the sleep.
     */
    private static Entry entryOf(Graph graph) {
        return BY_GRAPH.get(graph);
    }

    private static void evict(ResourceKey<Level> dimension, Entry entry) {
        BY_GRAPH.remove(entry.graph, entry);
        Map<BlockPos, Entry> cells = CELLS.get(dimension);
        if (cells != null) {
            for (BlockPos cell : entry.graph.coverage()) cells.remove(cell, entry);
        }
        Map<Long, Set<Entry>> chunkIndex = CHUNKS.get(dimension);
        if (chunkIndex == null) return;
        for (Long chunk : entry.chunks) {
            Set<Entry> entries = chunkIndex.get(chunk);
            if (entries != null && entries.remove(entry) && entries.isEmpty()) {
                chunkIndex.remove(chunk);
            }
        }
    }
}
