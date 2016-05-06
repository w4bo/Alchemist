package it.unibo.alchemist.model.implementations.reactions;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.math3.random.RandomGenerator;
import org.danilopianini.lang.PrimitiveUtils;

import com.google.common.collect.Lists;

import it.unibo.alchemist.biochemistrydsl.BiochemistrydslBaseVisitor;
import it.unibo.alchemist.biochemistrydsl.BiochemistrydslLexer;
import it.unibo.alchemist.biochemistrydsl.BiochemistrydslParser;
import it.unibo.alchemist.biochemistrydsl.BiochemistrydslParser.BiochemicalReactionRightElemContext;
import it.unibo.alchemist.biochemistrydsl.BiochemistrydslParser.BiomoleculeContext;
import it.unibo.alchemist.model.BiochemistryIncarnation;
import it.unibo.alchemist.model.implementations.actions.AddJunctionInCell;
import it.unibo.alchemist.model.implementations.actions.ChangeBiomolConcentrationInCell;
import it.unibo.alchemist.model.implementations.actions.ChangeBiomolConcentrationInEnv;
import it.unibo.alchemist.model.implementations.actions.ChangeBiomolConcentrationInNeighbor;
import it.unibo.alchemist.model.implementations.actions.RemoveJunctionInCell;
import it.unibo.alchemist.model.implementations.conditions.BiomolPresentInCell;
import it.unibo.alchemist.model.implementations.conditions.BiomolPresentInEnv;
import it.unibo.alchemist.model.implementations.conditions.BiomolPresentInNeighbor;
import it.unibo.alchemist.model.implementations.conditions.JunctionPresentInCell;
import it.unibo.alchemist.model.implementations.molecules.Biomolecule;
import it.unibo.alchemist.model.implementations.molecules.Junction;
import it.unibo.alchemist.model.implementations.nodes.CellNode;
import it.unibo.alchemist.model.implementations.nodes.EnvironmentNode;
import it.unibo.alchemist.model.implementations.times.DoubleTime;
import it.unibo.alchemist.model.interfaces.Action;
import it.unibo.alchemist.model.interfaces.Condition;
import it.unibo.alchemist.model.interfaces.Environment;
import it.unibo.alchemist.model.interfaces.Incarnation;
import it.unibo.alchemist.model.interfaces.Molecule;
import it.unibo.alchemist.model.interfaces.Node;
import it.unibo.alchemist.model.interfaces.Reaction;
import it.unibo.alchemist.model.interfaces.Time;
import it.unibo.alchemist.model.interfaces.TimeDistribution;

/**
 * This class implements a builder for chemical reactions. 
 */
public class BiochemicalReactionBuilder {

    private final String program;
    private final Node<Double> node;
    private final Environment<Double> env;
    private final TimeDistribution<Double> time;
    private final RandomGenerator rand;
    private final BiochemistryIncarnation incarnation;

    /**
     * @param randGen the random generator
     * @param currentIncarnation the current biochemistry incarnation
     * @param time the time distribution
     * @param node the node where this reaction will be created
     * @param environment the environment
     * @param param a string contains a program written in biochemistry DSL
     */
    public BiochemicalReactionBuilder(final RandomGenerator randGen, final BiochemistryIncarnation currentIncarnation, final TimeDistribution<Double> time, final Node<Double> node, final Environment<Double> environment, final String param) {
        this.node = node;
        env = environment;
        program = param;
        this.time = time;
        rand = randGen;
        incarnation = currentIncarnation;
    }

    /**
     * Builds the chemical reaction.
     * @return a builded chemical reaction based on the given program
     */
    public Reaction<Double> build()  {
        final BiochemistrydslLexer lexer = new BiochemistrydslLexer(new ANTLRInputStream(program));
        final BiochemistrydslParser parser = new BiochemistrydslParser(new CommonTokenStream(lexer));
        final ParseTree tree = parser.reaction();
        final BiochemistryDSLVisitor eval = new BiochemistryDSLVisitor(rand, incarnation, time, node, env);
        return eval.visit(tree);
    }

    private static final class BiochemistryDSLVisitor extends BiochemistrydslBaseVisitor<Reaction<Double>> {
        // TODO exceptions (parsing exception, class not found, wrong types of arguments...)
        private static final String CONDITIONS_PACKAGE = "it.unibo.alchemist.model.implementations.conditions.";
        private static final String ACTIONS_PACKAGE = "it.unibo.alchemist.model.implementations.actions.";
        private static final String REACTIONS_PACKAGE = "it.unibo.alchemist.model.implementations.reactions.";

        private final RandomGenerator rand;
        private final BiochemistryIncarnation currentInc;
        private final TimeDistribution<Double> time;
        private final Node<Double> node;
        private final Environment<Double> env;
        private final Reaction<Double> reaction;
        private final List<Condition<Double>> conditionList = new ArrayList<>(0);
        private final List<Action<Double>> actionList = new ArrayList<>(0);
        private final Map<Biomolecule, Double> biomolConditionsInCell = new HashMap<>();
        private final Map<Biomolecule, Double> biomolConditionsInNeighbor = new HashMap<>();
        private final List<Junction> junctionList = new ArrayList<>();

        private static void insertInMap(final Map<Biomolecule, Double> map, final Biomolecule mol, final double conc) {
            if (map.containsKey(mol)) {
                final double oldConc = map.get(mol);
                map.put(mol, oldConc + conc);
            } else {
                map.put(mol, conc);
            }
        }

        @SuppressWarnings("unchecked")
        private static <O> O createObject(final BiochemistrydslParser.JavaConstructorContext ctx,
                final String packageName, 
                final BiochemistryIncarnation incarnation, 
                final RandomGenerator rand,
                final Node<Double> node, 
                final TimeDistribution<Double> time, 
                final Environment<Double> env,
                final Reaction<Double> reaction) {
            String className = ctx.javaClass().getText();
            if (!className.contains(".")) {
                className = packageName + className;
            }
            try {
                final Class<O> clazz = (Class<O>) Class.forName(className);
                return instanceClass(ctx.argList(), clazz, incarnation, rand, node, time, env, reaction);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("cannot instance " + className + ", class not found");
            }
        }

        @SuppressWarnings("unchecked")
        private static <O> O instanceClass(final BiochemistrydslParser.ArgListContext ctx, 
                final Class<O> clazz, 
                final BiochemistryIncarnation incarnation, 
                final RandomGenerator rand,
                final Node<Double> node, 
                final TimeDistribution<Double> time, 
                final Environment<Double> env,
                final Reaction<Double> reaction) {
            final List<Object> params = new ArrayList<>(0);
            ctx.arg().forEach(arg -> params.add((arg.decimal() != null) ? Double.parseDouble(arg.decimal().getText()) : arg.LITERAL().getText()));
            final Optional<O> result = Arrays.stream(clazz.getConstructors())
                    .sorted((c1, c2) -> {
                        final int n1 = c1.getParameterCount();
                        final int n2 = c2.getParameterCount();
                        if (n1 == n2) {
                            /*
                             * Sort using types.
                             */
                            final Class<?>[] paramTypes1 = c1.getParameterTypes();
                            final Class<?>[] paramTypes2 = c2.getParameterTypes();
                            for (int i = 0; i < n1; i++) {
                                final Class<?> p1 = paramTypes1[i];
                                final Class<?> p2 = paramTypes2[i];
                                if (!p1.equals(p2)) {
                                    if (Double.class.isAssignableFrom(p1) || double.class.isAssignableFrom(p1)) {
                                        return -1;
                                    }
                                    if (Double.class.isAssignableFrom(p2) || double.class.isAssignableFrom(p2)) {
                                        return 1;
                                    }
                                    if (Long.class.isAssignableFrom(p1) || long.class.isAssignableFrom(p1)) {
                                        return -1;
                                    }
                                    if (Long.class.isAssignableFrom(p2) || long.class.isAssignableFrom(p2)) {
                                        return 1;
                                    }
                                    if (Integer.class.isAssignableFrom(p1) || int.class.isAssignableFrom(p1)) {
                                        return -1;
                                    }
                                    if (Integer.class.isAssignableFrom(p2) || int.class.isAssignableFrom(p2)) {
                                        return 1;
                                    }
                                    //L.trace("Fall back to lexicographic comparison for {} and {}", p1, p2);
                                    if (p1.getSimpleName().equals(p1.getSimpleName())) {
                                        return p1.toString().compareTo(p2.toString());
                                    }
                                    return p1.getSimpleName().compareTo(p2.getSimpleName());
                                }
                            }
                           // L.warn("There are apparently two identical constructors.");
                            return 0;
                        }
                        final int target = params.size();
                        return n1 == target ? -1
                                : n2 == target ? 1
                                : n1 < target ? n2 - n1
                                : n2 < target ? -1
                                : n1 - n2;
                    })
                    .map(c -> (Constructor<O>) c)
                    .map(c -> createBestEffort(c, params, incarnation, rand, env, node, time, reaction))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
                if (result.isPresent()) {
                    return result.get();
                }
                //L.error("Unable to create a {} with {}", clazz.getSimpleName(), params);
                return null;
        }

        private static <O> Optional<O> createBestEffort(
                final Constructor<O> constructor,
                final List<?> params,
                final Incarnation<?> incarnation,
                final RandomGenerator rand,
                final Environment<?> env,
                final Node<?> node,
                final TimeDistribution<?> timedist,
                final Reaction<?> reaction) {
            final Deque<?> paramsLeft = Lists.newLinkedList(params);
            final Object[] actualArgs = Arrays.stream(constructor.getParameterTypes()).map(expectedClass -> {
                if (Incarnation.class.isAssignableFrom(expectedClass)) {
                    return incarnation;
                }
                if (RandomGenerator.class.isAssignableFrom(expectedClass)) {
                    return rand;
                }
                if (Environment.class.isAssignableFrom(expectedClass)) {
                    return env;
                }
                if (Node.class.isAssignableFrom(expectedClass)) {
                    return node;
                }
                if (TimeDistribution.class.isAssignableFrom(expectedClass)) {
                    return timedist;
                }
                if (Reaction.class.isAssignableFrom(expectedClass)) {
                    return reaction;
                }
                while (!paramsLeft.isEmpty()) {
                    Object param = paramsLeft.pop();
                    if (param == null) {
                        return null;
                    }
                    if (expectedClass.isAssignableFrom(param.getClass())) {
                        return param;
                    }
                    if (PrimitiveUtils.classIsNumber(expectedClass) && param instanceof Number) {
                        final Optional<Number> attempt = optional2Optional(PrimitiveUtils.castIfNeeded(expectedClass, (Number) param));
                        if (attempt.isPresent()) {
                            return attempt.get();
                        }
                    }
                    if (PrimitiveUtils.classIsNumber(expectedClass) && param instanceof String) {
                        try {
                            final double d = Double.parseDouble((String) param);
                            final Optional<Number> attempt = optional2Optional(PrimitiveUtils.castIfNeeded(expectedClass, d));
                            if (attempt.isPresent()) {
                                return attempt.get();
                            }
                        } catch (final NumberFormatException e) {
                            return null;
                        }
                    }
                    if ((Boolean.class.isAssignableFrom(expectedClass) || boolean.class.isAssignableFrom(expectedClass)) && param instanceof String) {
                        try {
                            return Boolean.parseBoolean((String) param);
                        } catch (final NumberFormatException e) {
                            return null;
                        }
                    }
                    if (CharSequence.class.isAssignableFrom(expectedClass)) {
                        return param.toString();
                    }
                    if (Time.class.isAssignableFrom(expectedClass)) {
                        if (param instanceof Number) {
                            return new DoubleTime(((Number) param).doubleValue());
                        }
                        //L.warn("Created a time zero, due to non-numeric parameter {}", param);
                        return new DoubleTime();
                    }
                    if (Molecule.class.isAssignableFrom(expectedClass) && param instanceof String) {
                        return incarnation.createMolecule(param.toString());
                    }
                }
                return null;
            }).toArray();
            try {
                final O result = constructor.newInstance(actualArgs);
                //L.debug("{} produced {} with arguments {}", constructor, result, actualArgs);
                return Optional.of(result);
            } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException e) {
                //L.debug("No luck with {} and arguments {}", constructor, actualArgs);
            }
            return Optional.empty();
        }

        private static <T> Optional<T> optional2Optional(final java8.util.Optional<T> in) {
            if (in.isPresent()) {
                return Optional.of(in.get());
            }
            return Optional.empty();
        }

        private static Junction createJunction(final BiochemistrydslParser.JunctionContext ctx) {
            final Map<Biomolecule, Double> currentNodeMolecules = new HashMap<>();
            final Map<Biomolecule, Double> neighborNodeMolecules = new HashMap<>();
            for (final BiomoleculeContext b : ctx.junctionLeft().biomolecule()) {
                insertInMap(currentNodeMolecules, createBiomolecule(b), createConcentration(b));
            }
            for (final BiomoleculeContext b : ctx.junctionRight().biomolecule()) {
                insertInMap(neighborNodeMolecules, createBiomolecule(b), createConcentration(b));
            }
            return new Junction(ctx.getText(), currentNodeMolecules, neighborNodeMolecules); // TODO the junction name is like junctionA-B. Define the junction name.
        }

        private static Biomolecule createBiomolecule(final BiomoleculeContext ctx) {
            return new Biomolecule(ctx.name.getText());
        }

        // create the concentration of a biomolecule from a biomolecular context
        private static double createConcentration(final BiomoleculeContext ctx) {
            return (ctx.concentration() == null) ? 1.0 : Double.parseDouble(ctx.concentration().POSDOUBLE().getText());
        }

        private BiochemistryDSLVisitor(final RandomGenerator rand, final BiochemistryIncarnation incarnation, final TimeDistribution<Double> timeDistribution, final Node<Double> currentNode, final Environment<Double> environment) {
            this.rand = rand;
            currentInc = incarnation;
            time = timeDistribution;
            this.node = currentNode;
            env = environment;
            reaction = new ChemicalReaction<>(node, time);
        }

        @Override 
        public Reaction<Double> visitBiochemicalReaction(final BiochemistrydslParser.BiochemicalReactionContext ctx) { 
            visit(ctx.biochemicalReactionLeft());
            visit(ctx.biochemicalReactionRight());
            if (ctx.customConditions() != null) {
                visit(ctx.customConditions());
            }
            if (ctx.customReactionType() != null) {
                visit(ctx.customReactionType());
            }
            reaction.setConditions(conditionList);
            reaction.setActions(actionList);
            System.out.println("* visit biochemical reaction");
            return reaction;
        }

        @Override
        public Reaction<Double> visitCreateJunction(final BiochemistrydslParser.CreateJunctionContext ctx) { 
            visit(ctx.createJunctionLeft());
            visit(ctx.createJunctionRight());
            if (ctx.customConditions() != null) {
                visit(ctx.customConditions());
            }
            if (ctx.customReactionType() != null) {
                visit(ctx.customReactionType());
            }
            reaction.setConditions(conditionList);
            reaction.setActions(actionList);
            System.out.println("* visit create junction");
            return reaction;
        }

        @Override
        public Reaction<Double> visitJunctionReaction(final BiochemistrydslParser.JunctionReactionContext ctx) { 
            visit(ctx.junctionReactionLeft());
            visit(ctx.junctionReactionRight());
            if (ctx.customConditions() != null) {
                visit(ctx.customConditions());
            }
            if (ctx.customReactionType() != null) {
                visit(ctx.customReactionType());
            }
            junctionList.forEach((j -> actionList.add(new RemoveJunctionInCell(j, node))));
            reaction.setConditions(conditionList);
            reaction.setActions(actionList);
            System.out.println("* visit junction reaction");
            return reaction;
        }

        @Override
        public Reaction<Double> visitBiochemicalReactionLeftInCellContext(final BiochemistrydslParser.BiochemicalReactionLeftInCellContextContext ctx) {
            for (final BiomoleculeContext b : ctx.biomolecule()) {
                final Biomolecule biomol = createBiomolecule(b);
                final double concentration = createConcentration(b);
                insertInMap(biomolConditionsInCell, biomol, concentration);
                conditionList.add(new BiomolPresentInCell(biomol, concentration, (CellNode) node));
                actionList.add(new ChangeBiomolConcentrationInCell(biomol, -concentration, (CellNode) node));
                System.out.println("* add biomol present in cell condition - biomol is: " + biomol + " - concentration is: " + concentration);
            }
            return reaction;
        }

        @Override
        public Reaction<Double> visitBiochemicalReactionLeftInNeighborContext(final BiochemistrydslParser.BiochemicalReactionLeftInNeighborContextContext ctx) {
            for (final BiomoleculeContext b : ctx.biomolecule()) {
                final Biomolecule biomol = createBiomolecule(b);
                final double concentration = createConcentration(b);
                insertInMap(biomolConditionsInNeighbor, biomol, concentration);
                conditionList.add(new BiomolPresentInNeighbor(biomol, concentration, node, env));
                actionList.add(new ChangeBiomolConcentrationInNeighbor(biomol, -concentration, (CellNode) node, env));
                System.out.println("* add biomol present in neighbor condition - biomol is: " + biomol + " - concentration is: " + concentration);
            }
            return reaction;
        }

        @Override
        public Reaction<Double> visitBiochemicalReactionLeftInEnvContext(final BiochemistrydslParser.BiochemicalReactionLeftInEnvContextContext ctx) {
            for (final BiomoleculeContext b : ctx.biomolecule()) {
                final Biomolecule biomol = createBiomolecule(b);
                final double concentration = createConcentration(b);
                conditionList.add(new BiomolPresentInEnv(biomol, concentration, (EnvironmentNode) node, env)); // TODO environment node is fake
                actionList.add(new ChangeBiomolConcentrationInEnv(biomol, -concentration, node, env));
                System.out.println("* add biomol present in env condition - biomol is: " + biomol + " - concentration is: " + concentration);
            }
            return reaction;
        }

        @Override
        public Reaction<Double> visitBiochemicalReactionRightInCellContext(final BiochemistrydslParser.BiochemicalReactionRightInCellContextContext ctx) {
            for (final BiochemicalReactionRightElemContext re : ctx.biochemicalReactionRightElem()) {
                if (re.biomolecule() != null) {
                    final Biomolecule biomol = createBiomolecule(re.biomolecule());
                    final double concentration = createConcentration(re.biomolecule());
                    actionList.add(new ChangeBiomolConcentrationInCell(biomol, concentration, (CellNode) node));
                    System.out.println("* add change biomol conc in cell action - biomol is: " + biomol + " - concentration is: " + concentration);
                } else if (re.javaConstructor() != null) {
                    // TODO I don't know (reflection I guess)
                    System.out.println("* custom action (to do)");
                    actionList.add(createObject(re.javaConstructor(), ACTIONS_PACKAGE, currentInc, rand, node, time, env, reaction));
                }
            }
            return reaction;
        }

        @Override
        public Reaction<Double> visitBiochemicalReactionRightInNeighborContext(final BiochemistrydslParser.BiochemicalReactionRightInNeighborContextContext ctx) {
            for (final BiochemicalReactionRightElemContext re : ctx.biochemicalReactionRightElem()) {
                if (re.biomolecule() != null) {
                    final Biomolecule biomol = createBiomolecule(re.biomolecule());
                    final double concentration = createConcentration(re.biomolecule());
                    actionList.add(new ChangeBiomolConcentrationInNeighbor(biomol, concentration, node, env));
                    System.out.println("* add change biomol conc in neighbor action - biomol is: " + biomol + " - concentration is: " + concentration);
                } else if (re.javaConstructor() != null) {
                    // TODO I don't know (reflection I guess)
                    System.out.println("* custom action (to do)");
                }
            }
            return reaction;
        }

        @Override
        public Reaction<Double> visitBiochemicalReactionRightInEnvContext(final BiochemistrydslParser.BiochemicalReactionRightInEnvContextContext ctx) {
            for (final BiochemicalReactionRightElemContext re : ctx.biochemicalReactionRightElem()) {
                if (re.biomolecule() != null) {
                    final Biomolecule biomol = createBiomolecule(re.biomolecule());
                    final double concentration = createConcentration(re.biomolecule());
                    actionList.add(new ChangeBiomolConcentrationInEnv(biomol, concentration, node, env)); // TODO fake environment node
                    System.out.println("* add change biomol conc in env action - biomol is: " + biomol + " - concentration is: " + concentration);
                } else if (re.javaConstructor() != null) {
                    // TODO I don't know (reflection I guess)
                    System.out.println("* custom action (to do)");
                }
            }
            return reaction;
        }

        @Override
        public Reaction<Double> visitCreateJunctionJunction(final BiochemistrydslParser.CreateJunctionJunctionContext ctx) {
            final Junction j = createJunction(ctx.junction());
            j.getMoleculesInCurrentNode().forEach((k, v) -> {
                if (!biomolConditionsInCell.containsKey(k) || biomolConditionsInCell.get(k) < v) {
                    throw new RuntimeException("the junction require " + v + " biomolecule(s) " + k + " in cell, but are not presents in conditions.");
                }
            });
            j.getMoleculesInNeighborNode().forEach((k, v) -> {
                if (!biomolConditionsInNeighbor.containsKey(k) || biomolConditionsInNeighbor.get(k) < v) {
                    throw new RuntimeException("the junction require " +  v + " biomolecule(s) " + k + " in cell, but are not presents in conditions.");
                }
            });
            actionList.add(new AddJunctionInCell(j, node));
            System.out.println("* add junction in cell action");
            return reaction;
        }

        @Override
        public Reaction<Double> visitJunctionReactionJunctionCondition(final BiochemistrydslParser.JunctionReactionJunctionConditionContext ctx) {
            final Junction j = createJunction(ctx.junction());
            junctionList.add(j);
            conditionList.add(new JunctionPresentInCell(j, node));
            System.out.println("* add junction preent in cell condition");
            return reaction;
        }

        @Override
        public Reaction<Double> visitJunctionReactionJunction(final BiochemistrydslParser.JunctionReactionJunctionContext ctx) {
            final Junction j = createJunction(ctx.junction());
            if (!junctionList.remove(j)) { // the junction is not present in the list, witch means that this junction is undefined (e.g. [junction A-B] --> [junction C-D]
                throw new RuntimeException("The junction " + j + " is not present in conditions.");
            }
            return reaction;
        }

        @Override 
        public Reaction<Double> visitCustomCondition(final BiochemistrydslParser.CustomConditionContext ctx) {
            conditionList.add(createObject(ctx.javaConstructor(), CONDITIONS_PACKAGE, currentInc, rand, node, time, env, reaction));
            return reaction;
        }
    }
}