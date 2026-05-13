package de.devin.pipesnphysics.physics;

/**
 * A fluid handler (tank, basin, etc.) adjacent to a pipe in the network.
 * The handler is the block that stores fluid; the pipe is the network node
 * that connects to it.
 */
public record NetworkEndpoint(
        NodeId handlerNode,
        NodeId pipeNode,
        int faceIndex,
        double handlerWorldY,
        double pipeWorldY
) {
    /** True if the handler is physically above its connected pipe. */
    public boolean isHandlerAbovePipe() {
        return handlerWorldY > pipeWorldY;
    }
}
