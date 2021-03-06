package it.unibo.alchemist.model.implementations.movestrategies.target;

import it.unibo.alchemist.model.interfaces.MapEnvironment;
import it.unibo.alchemist.model.interfaces.Node;
import it.unibo.alchemist.model.interfaces.Position;
import it.unibo.alchemist.model.interfaces.Reaction;
import it.unibo.alchemist.model.interfaces.movestrategies.TargetSelectionStrategy;
import it.unibo.alchemist.model.interfaces.Route;

/**
 * This strategy follows a {@link Route}.
 * 
 * @param <T>
 */
public class FollowTrace<T> implements TargetSelectionStrategy<T> {

    private static final long serialVersionUID = -446053307821810437L;
    private final MapEnvironment<T> environment;
    private final Node<T> node;
    private final Reaction<T> reaction;

    /**
     * @param env
     *            the environment
     * @param n
     *            the node
     * @param r
     *            the reaction
     */
    public FollowTrace(final MapEnvironment<T> env, final Node<T> n, final Reaction<T> r) {
        environment = env;
        node = n;
        reaction = r;
    }

    @Override
    public final Position getTarget() {
        return environment.getNextPosition(node, reaction.getTau());
    }

}
