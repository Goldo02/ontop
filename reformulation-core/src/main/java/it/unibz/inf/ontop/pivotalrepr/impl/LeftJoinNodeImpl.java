package it.unibz.inf.ontop.pivotalrepr.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.model.*;
import it.unibz.inf.ontop.model.impl.OBDAVocabulary;
import it.unibz.inf.ontop.owlrefplatform.core.basicoperations.ImmutableSubstitutionImpl;
import it.unibz.inf.ontop.owlrefplatform.core.unfolding.ExpressionEvaluator;
import it.unibz.inf.ontop.pivotalrepr.*;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static it.unibz.inf.ontop.pivotalrepr.NonCommutativeOperatorNode.ArgumentPosition.*;
import static it.unibz.inf.ontop.pivotalrepr.unfolding.ProjectedVariableExtractionTools.extractProjectedVariables;

public class LeftJoinNodeImpl extends JoinLikeNodeImpl implements LeftJoinNode {

    private enum Provenance {
        FROM_ABOVE,
        FROM_LEFT,
        FROM_RIGHT
    }



    private static final String LEFT_JOIN_NODE_STR = "LJ";

    public LeftJoinNodeImpl(Optional<ImmutableExpression> optionalJoinCondition) {
        super(optionalJoinCondition);
    }

    @Override
    public void acceptVisitor(QueryNodeVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public LeftJoinNode clone() {
        return new LeftJoinNodeImpl(getOptionalFilterCondition());
    }

    @Override
    public LeftJoinNode acceptNodeTransformer(HomogeneousQueryNodeTransformer transformer) throws QueryNodeTransformationException {
        return transformer.transform(this);
    }

    @Override
    public LeftJoinNode changeOptionalFilterCondition(Optional<ImmutableExpression> newOptionalFilterCondition) {
        return new LeftJoinNodeImpl(newOptionalFilterCondition);
    }

    @Override
    public SubstitutionResults<LeftJoinNode> applyAscendingSubstitution(
            ImmutableSubstitution<? extends ImmutableTerm> substitution,
            QueryNode childNode, IntermediateQuery query) {
        return  isFromRightBranch(childNode, query)
                ? applyAscendingSubstitutionFromRight(substitution, query)
                : applyAscendingSubstitutionFromLeft(substitution, query);
    }

    private SubstitutionResults<LeftJoinNode> applyAscendingSubstitutionFromRight(
            ImmutableSubstitution<? extends ImmutableTerm> substitution, IntermediateQuery query) {
        QueryNode leftChild = query.getChild(this, LEFT)
                .orElseThrow(() -> new IllegalStateException("No left child for the LJ"));
        ImmutableSet<Variable> leftVariables = extractProjectedVariables(query, leftChild);

        /**
         * New substitution: only concerns variables specific to the right
         */
        ImmutableMap<Variable, ImmutableTerm> newSubstitutionMap = substitution.getImmutableMap().entrySet().stream()
                .filter(e -> !leftVariables.contains(e.getKey()))
                .map(e -> (Map.Entry<Variable, ImmutableTerm>)e)
                .collect(ImmutableCollectors.toMap());
        ImmutableSubstitution<ImmutableTerm> newSubstitution = new ImmutableSubstitutionImpl<>(newSubstitutionMap);

        /**
         * Updates the joining conditions (may add new equalities)
         * and propagates the new substitution if the conditions still holds.
         *
         */
        return computeAndEvaluateNewCondition(substitution, query, leftVariables)
                .map(ev -> applyEvaluation(query, ev, newSubstitution, Optional.of(leftVariables), Provenance.FROM_RIGHT))
                .orElseGet(() -> new SubstitutionResultsImpl<>(this, newSubstitution));
    }

    private SubstitutionResults<LeftJoinNode> applyAscendingSubstitutionFromLeft(
            ImmutableSubstitution<? extends ImmutableTerm> substitution, IntermediateQuery query) {
        QueryNode rightChild = query.getChild(this, RIGHT)
                .orElseThrow(() -> new IllegalStateException("No right child for the LJ"));
        ImmutableSet<Variable> rightVariables = extractProjectedVariables(query, rightChild);

        /**
         * Updates the joining conditions (may add new equalities)
         * and propagates the same substitution if the conditions still holds.
         *
         */
        return computeAndEvaluateNewCondition(substitution, query, rightVariables)
                .map(ev -> applyEvaluation(query, ev, substitution, Optional.of(rightVariables), Provenance.FROM_LEFT))
                .orElseGet(() -> new SubstitutionResultsImpl<>(this, substitution));
    }


    @Override
    public SubstitutionResults<LeftJoinNode> applyDescendingSubstitution(
            ImmutableSubstitution<? extends ImmutableTerm> substitution, IntermediateQuery query) {

        return getOptionalFilterCondition()
                .map(cond -> transformBooleanExpression(query, substitution, cond))
                .map(ev -> applyEvaluation(query, ev, substitution, Optional.empty(), Provenance.FROM_ABOVE))
                .orElseGet(() -> new SubstitutionResultsImpl<>(
                        SubstitutionResults.LocalAction.NO_CHANGE,
                        Optional.of(substitution)));
    }

    private SubstitutionResults<LeftJoinNode> applyEvaluation(IntermediateQuery query, ExpressionEvaluator.Evaluation evaluation,
                                                              ImmutableSubstitution<? extends ImmutableTerm> substitution,
                                                              Optional<ImmutableSet<Variable>> optionalVariablesFromOppositeSide,
                                                              Provenance provenance) {
        /**
         * Joining condition does not hold: replace the LJ by its left child.
         */
        if (evaluation.isFalse()) {

            ImmutableSubstitution<? extends ImmutableTerm> newSubstitution;
            switch(provenance) {
                case FROM_LEFT:
                    newSubstitution = removeRightChildSubstitutionFromLeft(query, substitution,
                            optionalVariablesFromOppositeSide);
                    break;
                case FROM_RIGHT:
                    newSubstitution = removeRightChildSubstitutionFromRight(query, substitution,
                            optionalVariablesFromOppositeSide);
                    break;
                default:
                    newSubstitution = substitution;
                    break;
            }

            return new SubstitutionResultsImpl<>(newSubstitution, Optional.of(LEFT));
        }
        else {
            LeftJoinNode newNode = changeOptionalFilterCondition(evaluation.getOptionalExpression());
            return new SubstitutionResultsImpl<>(newNode, substitution);
        }
    }

    private ImmutableSubstitution<ImmutableTerm> removeRightChildSubstitutionFromLeft(
            IntermediateQuery query, ImmutableSubstitution<? extends ImmutableTerm> substitution,
            Optional<ImmutableSet<Variable>> optionalRightVariables) {

        ImmutableSet<Variable> leftVariables = extractProjectedVariables(query, query.getChild(this, LEFT)
                .orElseThrow(() -> new IllegalStateException("Missing left child ")));
        ImmutableSet<Variable> rightVariables = getChildProjectedVariables(query, optionalRightVariables, RIGHT);

        ImmutableMap<Variable, ? extends ImmutableTerm> substitutionMap = substitution.getImmutableMap();

        ImmutableSet<Variable> newlyNullVariables = rightVariables.stream()
                .filter(v -> !leftVariables.contains(v))
                .filter(v -> !substitutionMap.containsKey(v))
                .collect(ImmutableCollectors.toSet());

        Stream<Map.Entry<Variable, ImmutableTerm>> nullEntries = newlyNullVariables.stream()
                .map(v -> new SimpleEntry<>(v, OBDAVocabulary.NULL));

        Stream<Map.Entry<Variable, ImmutableTerm>> alreadyExistingEntries = substitution.getImmutableMap().entrySet().stream()
                .map(e -> (Map.Entry<Variable, ImmutableTerm>)e);

        return new ImmutableSubstitutionImpl<>(
                Stream.concat(nullEntries, alreadyExistingEntries)
                        .collect(ImmutableCollectors.toMap()));


    }

    private ImmutableSubstitution<ImmutableTerm> removeRightChildSubstitutionFromRight(
            IntermediateQuery query, ImmutableSubstitution<? extends ImmutableTerm> substitution,
            Optional<ImmutableSet<Variable>> optionalLeftVariables) {

        ImmutableSet<Variable> leftVariables = getChildProjectedVariables(query, optionalLeftVariables, LEFT);
        ImmutableSet<Variable> rightVariables = extractProjectedVariables(query, query.getChild(this, RIGHT)
                                .orElseThrow(() -> new IllegalStateException("Missing right child ")));

        ImmutableSet<Variable> newlyNullVariables = rightVariables.stream()
                .filter(v -> !leftVariables.contains(v))
                .collect(ImmutableCollectors.toSet());

        Stream<Map.Entry<Variable, ImmutableTerm>> nullEntries = newlyNullVariables.stream()
                .map(v -> new SimpleEntry<>(v, OBDAVocabulary.NULL));

        Stream<Map.Entry<Variable, ImmutableTerm>> otherEntries = substitution.getImmutableMap().entrySet().stream()
                .filter(e -> !newlyNullVariables.contains(e.getKey()))
                .map(e -> (Map.Entry<Variable, ImmutableTerm>)e);

        return new ImmutableSubstitutionImpl<>(
                Stream.concat(nullEntries, otherEntries)
                        .collect(ImmutableCollectors.toMap()));
    }

    private ImmutableSet<Variable> getChildProjectedVariables(IntermediateQuery query,
                                                              Optional<ImmutableSet<Variable>> optionalChildVariables,
                                                              ArgumentPosition position) {
        return optionalChildVariables
                .orElseGet(() -> extractProjectedVariables(query,
                        query.getChild(this, position)
                                .orElseThrow(() -> new IllegalStateException("Missing child "))));
    }


    @Override
    public boolean isSyntacticallyEquivalentTo(QueryNode node) {
        return (node instanceof LeftJoinNode)
                && ((LeftJoinNode) node).getOptionalFilterCondition().equals(this.getOptionalFilterCondition());
    }

    @Override
    public NodeTransformationProposal acceptNodeTransformer(HeterogeneousQueryNodeTransformer transformer) {
        return transformer.transform(this);
    }

    @Override
    public String toString() {
        return LEFT_JOIN_NODE_STR + getOptionalFilterString();
    }

    /**
     * TODO: explain
     *
     * TODO: move it to the NonCommutativeOperatorNodeImpl when the latter will be created.
     */
    protected boolean isFromRightBranch(QueryNode childNode, IntermediateQuery query) {
        Optional<ArgumentPosition> optionalPosition = query.getOptionalPosition(this, childNode);
        if (optionalPosition.isPresent()) {
            switch(optionalPosition.get()) {
                case LEFT:
                    return false;
                case RIGHT:
                    return true;
                default:
                    throw new RuntimeException("Unexpected position: " + optionalPosition.get());
            }
        }
        else {
            throw new RuntimeException("Inconsistent tree: no argument position after " + this);
        }
    }
}
