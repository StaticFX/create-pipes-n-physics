package de.devin.pipesnphysics.physics;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified output of the network solver.
 *
 * @param pressures pressure at each pipe node
 * @param flowStates flow state (rate, direction) at each active pipe
 * @param activePipes set of nodes with active flow
 * @param burstEvents pipes that exceeded their burst threshold
 * @param breakdowns diagnostic pressure breakdown per node
 * @param sources the pressure sources that contributed to this result
 * @param inboundFaceIndex which face fluid enters each node from
 * @param outboundFaceIndices which faces fluid leaves each node through
 */
public record SolverResult(
        Map<NodeId, Float> pressures,
        Map<NodeId, FlowState> flowStates,
        Set<NodeId> activePipes,
        List<BurstEvent> burstEvents,
        Map<NodeId, PressureBreakdown> breakdowns,
        List<PressureSource> sources,
        Map<NodeId, Integer> inboundFaceIndex,
        Map<NodeId, Set<Integer>> outboundFaceIndices
) {}
