package de.devin.pipesnphysics.physics;

/**
 * A directed edge between two pipe nodes, with pre-resolved physical properties.
 * Elevation angle and world-Y values are baked in at graph construction time
 * so the physics solver never needs to call back into Minecraft.
 */
public record PipeEdge(
        NodeId from,
        NodeId to,
        float elevationAngleDegrees,
        double fromWorldY,
        double toWorldY,
        int faceIndex
) {}
