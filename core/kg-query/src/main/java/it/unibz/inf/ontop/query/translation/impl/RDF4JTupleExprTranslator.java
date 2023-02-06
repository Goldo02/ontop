package it.unibz.inf.ontop.query.translation.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import it.unibz.inf.ontop.exception.MinorOntopInternalBugException;
import it.unibz.inf.ontop.exception.OntopInternalBugException;
import it.unibz.inf.ontop.exception.OntopInvalidKGQueryException;
import it.unibz.inf.ontop.exception.OntopUnsupportedKGQueryException;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.impl.QueryNodeRenamer;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.transform.impl.HomogeneousIQTreeVisitingTransformer;
import it.unibz.inf.ontop.model.atom.AtomFactory;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.functionsymbol.FunctionSymbolFactory;
import it.unibz.inf.ontop.model.term.functionsymbol.LangSPARQLFunctionSymbol;
import it.unibz.inf.ontop.model.term.functionsymbol.SPARQLFunctionSymbol;
import it.unibz.inf.ontop.model.type.RDFDatatype;
import it.unibz.inf.ontop.model.type.TermTypeInference;
import it.unibz.inf.ontop.model.type.TypeFactory;
import it.unibz.inf.ontop.model.vocabulary.SPARQL;
import it.unibz.inf.ontop.model.vocabulary.XPathFunction;
import it.unibz.inf.ontop.model.vocabulary.XSD;
import it.unibz.inf.ontop.substitution.ImmutableSubstitution;
import it.unibz.inf.ontop.substitution.InjectiveVar2VarSubstitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.utils.CoreUtilsFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import it.unibz.inf.ontop.utils.VariableGenerator;
import org.apache.commons.rdf.api.RDF;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.*;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class RDF4JTupleExprTranslator {

    private final ImmutableMap<Variable, GroundTerm> externalBindings;
    private final @Nullable Dataset dataset;
    private final boolean treatBNodeAsVariable;

    private final CoreUtilsFactory coreUtilsFactory;
    private final SubstitutionFactory substitutionFactory;
    private final IntermediateQueryFactory iqFactory;
    private final AtomFactory atomFactory;
    private final TermFactory termFactory;
    private final FunctionSymbolFactory functionSymbolFactory;
    private final RDF rdfFactory;
    private final TypeFactory typeFactory;

    public RDF4JTupleExprTranslator(ImmutableMap<Variable, GroundTerm> externalBindings, @Nullable Dataset dataset, boolean treatBNodeAsVariable, CoreUtilsFactory coreUtilsFactory, SubstitutionFactory substitutionFactory, IntermediateQueryFactory iqFactory, AtomFactory atomFactory, TermFactory termFactory, FunctionSymbolFactory functionSymbolFactory, RDF rdfFactory, TypeFactory typeFactory) {
        this.externalBindings = externalBindings;
        this.dataset = dataset;
        this.treatBNodeAsVariable = treatBNodeAsVariable;
        this.coreUtilsFactory = coreUtilsFactory;
        this.substitutionFactory = substitutionFactory;
        this.iqFactory = iqFactory;
        this.atomFactory = atomFactory;
        this.termFactory = termFactory;
        this.functionSymbolFactory = functionSymbolFactory;
        this.rdfFactory = rdfFactory;
        this.typeFactory = typeFactory;
    }

    public IQTree getTree(TupleExpr node) throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException {
        return translate(node).iqTree;
    }

    private TranslationResult translate(TupleExpr node) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        if (node instanceof QueryRoot) {
            return translate(((QueryRoot) node).getArg());
        }

        if (node instanceof StatementPattern) {
            StatementPattern stmt = (StatementPattern)node;
            if (stmt.getScope().equals(StatementPattern.Scope.NAMED_CONTEXTS))
                return translateQuadPattern(stmt);

            return translateTriplePattern((StatementPattern) node);
        }

        if (node instanceof Join)
            return translateJoinLikeNode((Join) node);

        if (node instanceof LeftJoin)
            return translateJoinLikeNode((LeftJoin) node);

        if (node instanceof Difference)
            return translateDifference((Difference) node);

        if (node instanceof Union)
            return translateUnion((Union) node);

        if (node instanceof Filter)
            return translateFilter((Filter) node);

        if (node instanceof Projection)
            return translateProjection((Projection) node);

        if (node instanceof Slice)
            return translateSlice((Slice) node);

        if (node instanceof Distinct)
            return translateDistinctOrReduced(node);

        if (node instanceof Reduced)
            return translateDistinctOrReduced(node);

        if (node instanceof SingletonSet)
            return translateSingletonSet();

        if (node instanceof Group)
            return translateAggregate((Group) node);

        if (node instanceof Extension)
            return translateExtension((Extension) node);

        if (node instanceof BindingSetAssignment)
            return translateBindingSetAssignment((BindingSetAssignment) node);

        if (node instanceof Order)
            return translateOrder((Order) node);

        throw new OntopUnsupportedKGQueryException("Unsupported SPARQL operator: " + node.toString());
    }

    private TranslationResult translateDifference(Difference diff) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult leftTranslation = translate(diff.getLeftArg());
        TranslationResult rightTranslation = translate(diff.getRightArg());

        ImmutableSet<Variable> leftVars = leftTranslation.iqTree.getVariables();
        ImmutableSet<Variable> sharedVars = Sets.intersection(rightTranslation.iqTree.getVariables(), leftVars).immutableCopy();

        if (sharedVars.isEmpty()) {
            return leftTranslation;
        }

        VariableGenerator vGen = coreUtilsFactory.createVariableGenerator(
                Sets.union(leftTranslation.iqTree.getKnownVariables(), rightTranslation.iqTree.getKnownVariables()));

        InjectiveVar2VarSubstitution sub = sharedVars.stream()
                .collect(substitutionFactory.toInjectiveSubstitution(vGen::generateNewVariableFromVar));

        ImmutableExpression ljCond = getLJConditionForDifference(
                sharedVars,
                sub,
                leftTranslation.nullableVariables,
                rightTranslation.nullableVariables
        );
        ImmutableExpression filter = getFilterConditionForDifference(sub);

        NonProjVarRenamings nonProjVarsRenamings = getNonProjVarsRenamings(leftTranslation.iqTree, rightTranslation.iqTree, vGen);

        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        iqFactory.createConstructionNode(leftVars),
                        iqFactory.createUnaryIQTree(
                                iqFactory.createFilterNode(
                                        filter
                                ),
                                iqFactory.createBinaryNonCommutativeIQTree(
                                        iqFactory.createLeftJoinNode(ljCond),
                                        applyInDepthRenaming(leftTranslation.iqTree, nonProjVarsRenamings.left),
                                        applyInDepthRenaming(
                                                rightTranslation.iqTree
                                                        .applyDescendingSubstitutionWithoutOptimizing(sub, vGen),
                                                nonProjVarsRenamings.right)
                                ))),
                leftTranslation.nullableVariables);
    }

    private IQTree applyInDepthRenaming(IQTree tree, InjectiveVar2VarSubstitution renaming) {
        if (renaming.isEmpty())
            return tree;
        QueryNodeRenamer nodeTransformer = new QueryNodeRenamer(iqFactory, renaming, atomFactory, substitutionFactory);
        HomogeneousIQTreeVisitingTransformer iqTransformer = new HomogeneousIQTreeVisitingTransformer(nodeTransformer, iqFactory);
        return iqTransformer.transform(tree);
    }

    private ImmutableExpression getLJConditionForDifference(ImmutableSet<Variable> sharedVars, InjectiveVar2VarSubstitution sub,
                                                            ImmutableSet<Variable> leftNullableVars, ImmutableSet<Variable> rightNullableVars) {

        InjectiveVar2VarSubstitution sharedVarsSub = sub.restrictDomainTo(sharedVars);

        return termFactory.getConjunction(Stream.concat(
                        sharedVarsSub.builder()
                                .toStream((v, t) -> termFactory.getDisjunction(getEqOrNullable(v, t, leftNullableVars, rightNullableVars))),
                        Stream.of(termFactory.getDisjunction(sharedVarsSub.builder()
                                .toStrictEqualities().collect(ImmutableCollectors.toList()))))
                .collect(ImmutableCollectors.toList()));
    }

    private ImmutableList<ImmutableExpression> getEqOrNullable(Variable leftVar, Variable renamedVar, ImmutableSet<Variable> leftNullableVars,
                                                               ImmutableSet<Variable> rightNullableVars) {

        ImmutableExpression equality = termFactory.getStrictEquality(leftVar, renamedVar);

        if (leftNullableVars.contains(leftVar)) {
            return rightNullableVars.contains(leftVar)
                    ? ImmutableList.of(equality, termFactory.getDBIsNull(leftVar), termFactory.getDBIsNull(renamedVar))
                    : ImmutableList.of(equality, termFactory.getDBIsNull(leftVar));
        }
        else {
            return rightNullableVars.contains(leftVar)
                    ? ImmutableList.of(equality, termFactory.getDBIsNull(renamedVar))
                    : ImmutableList.of(equality);
        }
    }


    private ImmutableExpression getFilterConditionForDifference(InjectiveVar2VarSubstitution sub) {
        return termFactory.getConjunction(sub.getRangeSet().stream()
                .map(termFactory::getDBIsNull)
                .collect(ImmutableCollectors.toList()));
    }

    private TranslationResult translateAggregate(Group groupNode) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(groupNode.getArg());

        // Assumption: every variable used in a definition is itself defined either in the subtree of in a previous ExtensionElem
        ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergedVarDefs =
                getGroupVarDefs(groupNode.getGroupElements(), child.iqTree.getVariables());

        if (mergedVarDefs.size() > 1) {
            throw new Sparql2IqConversionException("Unexpected parsed SPARQL query: nested complex projections appear " +
                    "within an RDF4J Group node: " + groupNode);
        }
        AggregationNode an = iqFactory.createAggregationNode(
                groupNode.getGroupBindingNames().stream()
                        .map(termFactory::getVariable)
                        .collect(ImmutableCollectors.toSet()),
                mergedVarDefs.get(0).castTo(ImmutableFunctionalTerm.class)); // only one substitution guaranteed by the if

        UnaryIQTree aggregationTree = iqFactory.createUnaryIQTree(an, child.iqTree);

        ImmutableSet<Variable> nullableVariables = Sets.union(
                        Sets.intersection(an.getGroupingVariables(), child.nullableVariables),
                        an.getSubstitution().restrictRange(t -> t.getFunctionSymbol().isNullable(ImmutableSet.of(0)))
                                .getDomain())
                .immutableCopy();

        return createTranslationResultFromExtendedProjection(an, aggregationTree, nullableVariables);
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> getGroupVarDefs(List<GroupElem> list,
                                                                                ImmutableSet<Variable> childVariables) {
        List<VarDef> result = new ArrayList<>();
        Set<Variable> allowedVars = new HashSet<>(childVariables); // mutable: accumulator

        for (GroupElem elem : list) {
            ImmutableTerm term = getTerm(elem.getOperator(), allowedVars, externalBindings, treatBNodeAsVariable);
            Variable definedVar = termFactory.getVariable(elem.getName());
            allowedVars.add(definedVar);

            result.add(new VarDef(definedVar, term));
        }
        return mergeVarDefs(ImmutableList.copyOf(result));
    }

    private TranslationResult translateOrder(Order node) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(node.getArg());
        ImmutableSet<Variable> variables = child.iqTree.getVariables();

        ImmutableList<OrderByNode.OrderComparator> comparators = node.getElements().stream()
                .map(o -> getOrderComparator(o, variables))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(ImmutableCollectors.toList());

        return comparators.isEmpty()
                ? child
                : createTranslationResult(
                iqFactory.createUnaryIQTree(
                        iqFactory.createOrderByNode(comparators),
                        child.iqTree),
                child.nullableVariables);
    }

    private Optional<OrderByNode.OrderComparator> getOrderComparator(OrderElem oe, ImmutableSet<Variable> variables) {
        ImmutableTerm expr = getTerm(oe.getExpr(), variables, externalBindings, treatBNodeAsVariable);
        if (expr instanceof NonGroundTerm)
            return Optional.of(iqFactory.createOrderComparator((NonGroundTerm) expr, oe.isAscending()));

        return Optional.empty();
    }


    private TranslationResult translateBindingSetAssignment(BindingSetAssignment node) {

        Constant nullConstant = termFactory.getNullConstant();

        ImmutableSet<Variable> allVars = node.getBindingNames().stream()
                .map(termFactory::getVariable)
                .collect(ImmutableCollectors.toSet());

        ImmutableList<ImmutableSubstitution<ImmutableTerm>> substitutions =
                StreamSupport.stream(node.getBindingSets().spliterator(), false)
                        .map(bs -> bs.getBindingNames().stream()
                                .collect(substitutionFactory.toSubstitution(
                                        termFactory::getVariable,
                                        x -> getTermForBinding(x, bs, nullConstant))))
                        .collect(ImmutableCollectors.toList());

        ImmutableSet<Variable> nullableVars = substitutions.get(0).getDomain().stream()
                .filter(v -> substitutions.stream().anyMatch(s -> s.get(v).isNull()))
                .collect(ImmutableCollectors.toSet());

        ImmutableList<IQTree> subtrees = substitutions.stream()
                .map(sub -> iqFactory.createConstructionNode(sub.getDomain(), sub))
                .map(cn -> iqFactory.createUnaryIQTree(cn, iqFactory.createTrueNode()))
                .collect(ImmutableCollectors.toList());

        IQTree tree = subtrees.size() == 1 ?
                subtrees.get(0) :
                iqFactory.createNaryIQTree(iqFactory.createUnionNode(allVars), subtrees);

        // Most of the time
        if (externalBindings.isEmpty())
            return createTranslationResult(tree, nullableVars);

        Sets.SetView<Variable> externallyBoundedVariables = Sets.intersection(allVars, externalBindings.keySet());

        return createTranslationResult(
                applyExternalBindingFilter(tree, externalBindings, externallyBoundedVariables),
                nullableVars);
    }

    private ImmutableTerm getTermForBinding(String x, BindingSet bindingSet, Constant nullConstant) {
        RDF4JValueTranslator translator = getValueTranslator();
        Binding binding = bindingSet.getBinding(x);
        return binding == null
                ? termFactory.getNullConstant() :
                translator.getTermForLiteralOrIri(binding.getValue());
    }

    private TranslationResult translateSingletonSet() {
        return createTranslationResult(iqFactory.createTrueNode(), ImmutableSet.of());
    }

    private TranslationResult translateDistinctOrReduced(TupleExpr genNode) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child;
        if (genNode instanceof Distinct) {
            child = translate(((Distinct) genNode).getArg());
        } else if (genNode instanceof Reduced) {
            child = translate(((Reduced) genNode).getArg());
        } else {
            throw new Sparql2IqConversionException("Unexpected node type for node: " + genNode.toString());
        }
        return createTranslationResult(
                iqFactory.createUnaryIQTree(iqFactory.createDistinctNode(), child.iqTree),
                child.nullableVariables);
    }

    private TranslationResult translateSlice(Slice node) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(node.getArg());

        return createTranslationResult(
                iqFactory.createUnaryIQTree(getSliceNode(node), child.iqTree),
                child.nullableVariables);
    }

    private SliceNode getSliceNode(Slice node) {
        // Assumption: at least the limit or the offset is not -1 (otherwise the rdf4j parser would not generate a slice node)
        long offset = node.getOffset() == -1 ?
                0 :
                node.getOffset();
        return node.getLimit() == -1 ?
                iqFactory.createSliceNode(offset) :
                iqFactory.createSliceNode(
                        offset,
                        node.getLimit()
                );
    }

    private TranslationResult translateFilter(Filter filter) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult child = translate(filter.getArg());
        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        iqFactory.createFilterNode(
                                getFilterExpression(
                                        filter.getCondition(),
                                        child.iqTree.getVariables(),
                                        externalBindings, treatBNodeAsVariable)),
                        child.iqTree
                ),
                child.nullableVariables
        );
    }

    private TranslationResult translateJoinLikeNode(BinaryTupleOperator join) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        if (!(join instanceof Join) && !(join instanceof LeftJoin)) {
            throw new Sparql2IqConversionException("A left or inner join is expected");
        }

        TranslationResult leftTranslation = translate(join.getLeftArg());
        TranslationResult rightTranslation = translate(join.getRightArg());

        ImmutableSet<Variable> nullableVarsUnion = Sets.union(leftTranslation.nullableVariables, rightTranslation.nullableVariables).immutableCopy();

        ImmutableSet<Variable> sharedVars = Sets.intersection(leftTranslation.iqTree.getVariables(), rightTranslation.iqTree.getVariables()).immutableCopy();

        ImmutableSet<Variable> toCoalesce = Sets.intersection(sharedVars, nullableVarsUnion).immutableCopy();

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(leftTranslation.iqTree.getKnownVariables(), rightTranslation.iqTree.getKnownVariables()));

        // May update the variable generator!!
        InjectiveVar2VarSubstitution leftRenamingSubstitution = toCoalesce.stream()
                .collect(substitutionFactory.toInjectiveSubstitution(variableGenerator::generateNewVariableFromVar));

        InjectiveVar2VarSubstitution rightRenamingSubstitution = toCoalesce.stream()
                .collect(substitutionFactory.toInjectiveSubstitution(variableGenerator::generateNewVariableFromVar));

        ImmutableSubstitution<ImmutableTerm> topSubstitution = toCoalesce.stream()
                .collect(substitutionFactory.toSubstitution(
                        v -> termFactory.getImmutableFunctionalTerm(
                                functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.COALESCE, 2),
                                leftRenamingSubstitution.get(v),
                                rightRenamingSubstitution.get(v))));

        Optional<ImmutableExpression> filterExpression;
        if (join instanceof LeftJoin) {
            ImmutableSet<Variable> variables = Sets.union(leftTranslation.iqTree.getVariables(), rightTranslation.iqTree.getVariables()).immutableCopy();
            filterExpression = Optional.ofNullable(((LeftJoin) join).getCondition())
                    .map(c -> topSubstitution.apply(getFilterExpression(c, variables, externalBindings, treatBNodeAsVariable)));
        }
        else {
            filterExpression = Optional.empty();
        }

        Optional<ImmutableExpression> joinCondition = termFactory.getConjunction(filterExpression, toCoalesce.stream()
                .map(v -> generateCompatibleExpression(v, leftRenamingSubstitution, rightRenamingSubstitution)));

        JoinLikeNode joinLikeNode = join instanceof LeftJoin
                ? iqFactory.createLeftJoinNode(joinCondition)
                : iqFactory.createInnerJoinNode(joinCondition);

        ImmutableSet<Variable> newSetOfNullableVars = join instanceof LeftJoin
                ? Sets.union(nullableVarsUnion, Sets.difference(rightTranslation.iqTree.getVariables(), sharedVars)).immutableCopy()
                : Sets.difference(nullableVarsUnion, sharedVars).immutableCopy();

        IQTree joinQuery = buildJoinQuery(
                joinLikeNode,
                leftTranslation.iqTree,
                rightTranslation.iqTree,
                topSubstitution,
                leftRenamingSubstitution,
                rightRenamingSubstitution,
                toCoalesce);

        return createTranslationResult(joinQuery, newSetOfNullableVars);
    }

    private ImmutableExpression generateCompatibleExpression(Variable outputVariable,
                                                             InjectiveVar2VarSubstitution leftChildSubstitution,
                                                             InjectiveVar2VarSubstitution rightChildSubstitution) {

        Variable leftVariable = substitutionFactory.onVariables().apply(leftChildSubstitution, outputVariable);
        Variable rightVariable = substitutionFactory.onVariables().apply(rightChildSubstitution, outputVariable);

        ImmutableExpression equalityCondition = termFactory.getStrictEquality(leftVariable, rightVariable);
        ImmutableExpression isNullExpression = termFactory.getDisjunction(
                termFactory.getDBIsNull(leftVariable), termFactory.getDBIsNull(rightVariable));

        return termFactory.getDisjunction(equalityCondition, isNullExpression);
    }

    private IQTree buildJoinQuery(JoinLikeNode joinNode,
                                  IQTree leftQuery,
                                  IQTree rightQuery,
                                  ImmutableSubstitution<ImmutableTerm> topSubstitution,
                                  InjectiveVar2VarSubstitution leftRenamingSubstitution,
                                  InjectiveVar2VarSubstitution rightRenamingSubstitution,
                                  ImmutableSet<Variable> toCoalesce) {

        ImmutableSet<Variable> projectedVariables = Sets.union(
                Sets.difference(
                        Sets.union(leftQuery.getVariables(), rightQuery.getVariables()),
                        toCoalesce),
                topSubstitution.getDomain()).immutableCopy();

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(leftQuery.getKnownVariables(), rightQuery.getKnownVariables()));

        NonProjVarRenamings nonProjVarsRenaming = getNonProjVarsRenamings(leftQuery, rightQuery, variableGenerator);

        IQTree joinTree = getJoinTree(
                joinNode,
                applyInDepthRenaming(
                        leftQuery.applyDescendingSubstitutionWithoutOptimizing(leftRenamingSubstitution, variableGenerator),
                        nonProjVarsRenaming.left),
                applyInDepthRenaming(
                        rightQuery.applyDescendingSubstitutionWithoutOptimizing(rightRenamingSubstitution, variableGenerator),
                        nonProjVarsRenaming.right));

        return topSubstitution.isEmpty()
                ? joinTree
                : iqFactory.createUnaryIQTree(
                iqFactory.createConstructionNode(projectedVariables, topSubstitution),
                joinTree);
    }

    private IQTree getJoinTree(JoinLikeNode joinNode, IQTree leftTree, IQTree rightTree) {
        if (joinNode instanceof LeftJoinNode) {
            return iqFactory.createBinaryNonCommutativeIQTree((LeftJoinNode) joinNode, leftTree, rightTree);
        }
        if (joinNode instanceof InnerJoinNode) {
            return iqFactory.createNaryIQTree((InnerJoinNode) joinNode, ImmutableList.of(leftTree, rightTree));
        }
        throw new Sparql2IqConversionException("Left or inner join expected");
    }

    private TranslationResult translateProjection(Projection node) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(node.getArg());
        IQTree subQuery = child.iqTree;

        List<ProjectionElem> projectionElems = node.getProjectionElemList().getElements();

        ImmutableMap<Variable, Variable> map = projectionElems.stream()
                .collect(ImmutableCollectors.toMap(
                        pe -> termFactory.getVariable(pe.getName()),
                        pe -> termFactory.getVariable(pe.getProjectionAlias().orElse(pe.getName()))));

        ImmutableSubstitution<Variable> substitution = map.entrySet().stream()
                .collect(substitutionFactory.toSubstitutionSkippingIdentityEntries());

        ImmutableSet<Variable> projectedVars = ImmutableSet.copyOf(map.values());

        if (substitution.isEmpty() && projectedVars.equals(child.iqTree.getVariables())) {
            return child;
        }

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(child.iqTree.getKnownVariables(), projectedVars));

        subQuery = subQuery.applyDescendingSubstitutionWithoutOptimizing(substitution, variableGenerator);
        ImmutableSet<Variable> subQueryVariables = subQuery.getVariables();

        // Substitution for possibly unbound variables
        ImmutableSubstitution<ImmutableTerm> newSubstitution = Sets.difference(projectedVars, subQueryVariables).stream()
                .collect(substitutionFactory.toSubstitution(v -> termFactory.getNullConstant()));

        ConstructionNode projectNode = iqFactory.createConstructionNode(projectedVars, newSubstitution);
        UnaryIQTree constructTree = iqFactory.createUnaryIQTree(projectNode, subQuery);

        ImmutableSet<Variable> nullableVariables = substitutionFactory.onVariables().apply(substitution, child.nullableVariables);

        return createTranslationResultFromExtendedProjection(projectNode, constructTree, nullableVariables);
    }

    /**
     * Looks for new variables introduced by the extended projection node.
     * Applies a filter condition to those externally bounded.
     *
     * The externally bounded coming from the sub-tree are supposed to have already handled.
     *
     */
    private TranslationResult createTranslationResultFromExtendedProjection(ExtendedProjectionNode extendedProjectNode,
                                                                            UnaryIQTree tree,
                                                                            ImmutableSet<Variable> nullableVariables) {
        // Most of the time
        if (externalBindings.isEmpty())
            return createTranslationResult(tree, nullableVariables);

        Sets.SetView<Variable> externallyBoundedVariables = Sets.intersection(
                extendedProjectNode.getSubstitution().getDomain(),
                externalBindings.keySet());

        IQTree iqTree = applyExternalBindingFilter(tree, externalBindings, externallyBoundedVariables);
        return createTranslationResult(iqTree, nullableVariables);
    }

    private TranslationResult translateUnion(Union union) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult leftTranslation = translate(union.getLeftArg());
        TranslationResult rightTranslation = translate(union.getRightArg());

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(leftTranslation.iqTree.getKnownVariables(), rightTranslation.iqTree.getKnownVariables()));

        ImmutableSet<Variable> leftVariables = leftTranslation.iqTree.getVariables();
        ImmutableSet<Variable> rightVariables = rightTranslation.iqTree.getVariables();

        Sets.SetView<Variable> nullOnLeft = Sets.difference(rightVariables, leftVariables);
        Sets.SetView<Variable> nullOnRight = Sets.difference(leftVariables, rightVariables);

        ImmutableSet<Variable> allNullable = Sets.union(
                Sets.union(leftTranslation.nullableVariables, rightTranslation.nullableVariables),
                Sets.union(nullOnLeft, nullOnRight)).immutableCopy();

        ImmutableSet<Variable> rootVariables = Sets.union(leftVariables, rightVariables).immutableCopy();

        ConstructionNode leftCn = iqFactory.createConstructionNode(rootVariables, nullOnLeft.stream()
                .collect(substitutionFactory.toSubstitution(v -> termFactory.getNullConstant())));
        ConstructionNode rightCn = iqFactory.createConstructionNode(rootVariables, nullOnRight.stream()
                .collect(substitutionFactory.toSubstitution(v -> termFactory.getNullConstant())));

        UnionNode unionNode = iqFactory.createUnionNode(rootVariables);

        ConstructionNode rootNode = iqFactory.createConstructionNode(rootVariables);

        NonProjVarRenamings nonProjVarsRenamings = getNonProjVarsRenamings(leftTranslation.iqTree, rightTranslation.iqTree, variableGenerator);

        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        rootNode,
                        iqFactory.createNaryIQTree(
                                unionNode,
                                ImmutableList.of(
                                        applyInDepthRenaming(iqFactory.createUnaryIQTree(leftCn, leftTranslation.iqTree), nonProjVarsRenamings.left),
                                        applyInDepthRenaming(iqFactory.createUnaryIQTree(rightCn, rightTranslation.iqTree), nonProjVarsRenamings.right)))),
                allNullable);
    }

    private TranslationResult translateTriplePattern(StatementPattern triple) {
        VariableOrGroundTerm subject = translateRDF4JVar(triple.getSubjectVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable);
        VariableOrGroundTerm predicate = translateRDF4JVar(triple.getPredicateVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable);
        VariableOrGroundTerm object = translateRDF4JVar(triple.getObjectVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable);

        IQTree subTree;
        if (dataset == null || dataset.getDefaultGraphs().isEmpty() && dataset.getNamedGraphs().isEmpty()) {
            subTree = iqFactory.createIntensionalDataNode(
                    atomFactory.getIntensionalTripleAtom(subject, predicate, object));
        }
        else {
            Set<IRI> defaultGraphs = dataset.getDefaultGraphs();
            int defaultGraphCount = defaultGraphs.size();

            // From SPARQL 1.1 "If there is no FROM clause, but there is one or more FROM NAMED clauses,
            // then the dataset includes an empty graph for the default graph."
            if (defaultGraphCount == 0)
                subTree = iqFactory.createEmptyNode(
                        Stream.of(subject, predicate, object)
                                .filter(t -> t instanceof Variable)
                                .map(t -> (Variable) t)
                                .collect(ImmutableCollectors.toSet()));
                // NB: INSERT blocks cannot have more than 1 default graph. Important for the rest
            else if (defaultGraphCount == 1) {
                IRIConstant graph = termFactory.getConstantIRI(defaultGraphs.iterator().next().stringValue());
                subTree = iqFactory.createIntensionalDataNode(
                        atomFactory.getIntensionalQuadAtom(subject, predicate, object, graph));
            }
            else {
                Variable graph = termFactory.getVariable("g" + UUID.randomUUID());

                IntensionalDataNode quadNode = iqFactory.createIntensionalDataNode(
                        atomFactory.getIntensionalQuadAtom(subject, predicate, object, graph));

                ImmutableExpression graphFilter = termFactory.getDisjunction(defaultGraphs.stream()
                                .map(g -> termFactory.getConstantIRI(g.stringValue()))
                                .map(iriConstant -> termFactory.getStrictEquality(graph, iriConstant)))
                        .orElseThrow(() -> new MinorOntopInternalBugException("The empty case already handled"));

                ImmutableSet<Variable> projectedVariables = Sets.difference(quadNode.getVariables(), ImmutableSet.of(graph)).immutableCopy();

                // Merges the default trees -> removes duplicates
                subTree = iqFactory.createUnaryIQTree(
                        iqFactory.createDistinctNode(),
                        iqFactory.createUnaryIQTree(
                                iqFactory.createConstructionNode(projectedVariables),
                                iqFactory.createUnaryIQTree(iqFactory.createFilterNode(graphFilter), quadNode)));
            }
        }

        // In most cases
        if (externalBindings.isEmpty())
            return createTranslationResult(subTree, ImmutableSet.of());

        Sets.SetView<Variable> externallyBoundedVariables = Sets.intersection(subTree.getVariables(), externalBindings.keySet());
        IQTree iqTree = applyExternalBindingFilter(subTree, externalBindings, externallyBoundedVariables);

        return createTranslationResult(iqTree, ImmutableSet.of());
    }

    private TranslationResult translateQuadPattern(StatementPattern quad) {

        VariableOrGroundTerm graph = translateRDF4JVar(quad.getContextVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable);

        IntensionalDataNode dataNode = iqFactory.createIntensionalDataNode(
                atomFactory.getIntensionalQuadAtom(
                        translateRDF4JVar(quad.getSubjectVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable),
                        translateRDF4JVar(quad.getPredicateVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable),
                        translateRDF4JVar(quad.getObjectVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable),
                        graph));

        IQTree subTree;
        if (dataset == null || dataset.getNamedGraphs().isEmpty()) {
            subTree = dataNode;
        }
        else {
            ImmutableExpression graphFilter = termFactory.getDisjunction(dataset.getNamedGraphs().stream()
                            .map(g -> termFactory.getConstantIRI(g.stringValue()))
                            .map(iriConstant -> termFactory.getStrictEquality(graph, iriConstant)))
                    .orElseThrow(() -> new MinorOntopInternalBugException("The empty case already handled"));

            subTree = iqFactory.createUnaryIQTree(iqFactory.createFilterNode(graphFilter), dataNode);
        }

        // In most cases
        if (externalBindings.isEmpty())
            return createTranslationResult(subTree, ImmutableSet.of());

        Sets.SetView<Variable> externallyBoundedVariables = Sets.intersection(subTree.getVariables(), externalBindings.keySet());
        IQTree iqTree = applyExternalBindingFilter(subTree, externalBindings, externallyBoundedVariables);

        return createTranslationResult(iqTree, ImmutableSet.of());
    }

    private TranslationResult translateExtension(Extension node) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult childTranslation = translate(node.getArg());

        // Warning: an ExtensionElement might reference a variable appearing in a previous ExtensionElement
        // So we may need to nest them

        // Assumption: every variable used in a definition is itself defined either in the subtree of in a previous ExtensionElem
        ImmutableSet<Variable> childVars = childTranslation.iqTree.getVariables();
        ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergedVarDefs =
                getVarDefs(node.getElements(), childVars);

        if (mergedVarDefs.isEmpty()) {
            return childTranslation;
        }

        return translateExtensionElems(mergedVarDefs, childTranslation);
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> getVarDefs(List<ExtensionElem> list,
                                                                           ImmutableSet<Variable> childVars) {
        List<VarDef> result = new ArrayList<>();
        Set<Variable> allowedVars = new HashSet<>(childVars); // mutable: accumulator

        for (ExtensionElem elem : list) {
            if (!(elem.getExpr() instanceof Var && elem.getName().equals(((Var) elem.getExpr()).getName()))) {
                ImmutableTerm term = getTerm(elem.getExpr(), allowedVars, externalBindings, treatBNodeAsVariable);
                Variable definedVar = termFactory.getVariable(elem.getName());
                allowedVars.add(definedVar);

                result.add(new VarDef(definedVar, term));
            }
        }

        ImmutableList<VarDef> varDefs = result.stream()
                .filter(vd -> !childVars.contains(vd.var))
                .collect(ImmutableCollectors.toList());

        return mergeVarDefs(varDefs);
    }

    private TranslationResult translateExtensionElems(ImmutableList<ImmutableSubstitution<ImmutableTerm>> substitutions,
                                                      TranslationResult subquery) {

        TranslationResult result = createTranslationResult(subquery.iqTree, subquery.nullableVariables);

        for (ImmutableSubstitution<ImmutableTerm> substitution : substitutions) {

            ImmutableSet<Variable> nullableVariables = result.nullableVariables;
            ImmutableSet<Variable> newNullableVariables = substitution
                    .restrictRange(t -> t.getVariableStream().anyMatch(nullableVariables::contains))
                    .getDomain();

            ConstructionNode constructionNode = iqFactory.createConstructionNode(
                    Sets.union(result.iqTree.getVariables(), substitution.getDomain()).immutableCopy(),
                    substitution);

            result = createTranslationResultFromExtendedProjection(
                    constructionNode,
                    iqFactory.createUnaryIQTree(constructionNode, result.iqTree),
                    Sets.union(nullableVariables, newNullableVariables).immutableCopy());
        }

        return result;
    }

    private NonProjVarRenamings getNonProjVarsRenamings(IQTree leftQuery, IQTree rightQuery,
                                                        VariableGenerator variableGenerator) {

        ImmutableSet<Variable> leftKnownVars = leftQuery.getKnownVariables();
        ImmutableSet<Variable> rightKnownVars = rightQuery.getKnownVariables();

        ImmutableSet<Variable> leftProjVars = leftQuery.getVariables();
        ImmutableSet<Variable> rightProjVars = rightQuery.getVariables();

        /* Returns two substitutions that respectively rename:
         *  - non-projected variables from the left operand that are also present in the right operand
         *  - non-projected variables from the right operand that are also present in the left operand
         */

        InjectiveVar2VarSubstitution leftSubstitution = Sets.intersection(Sets.difference(leftKnownVars, leftProjVars), rightKnownVars).stream()
                .collect(substitutionFactory.toInjectiveSubstitution(variableGenerator::generateNewVariableFromVar));

        InjectiveVar2VarSubstitution rightSubstitution = Sets.intersection(Sets.difference(rightKnownVars, rightProjVars), leftKnownVars).stream()
                .collect(substitutionFactory.toInjectiveSubstitution(variableGenerator::generateNewVariableFromVar));

        return new NonProjVarRenamings(leftSubstitution, rightSubstitution);
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergeVarDefs(ImmutableList<VarDef> varDefs)  {
        Deque<Map<Variable, ImmutableTerm>> substitutionMapList = new LinkedList<>();
        substitutionMapList.add(new HashMap<>());

        for (VarDef varDef : varDefs) {
            Map<Variable, ImmutableTerm> last = substitutionMapList.getLast();
            if (varDef.term.getVariableStream().anyMatch(last::containsKey)) { // start off a new substitution
                substitutionMapList.addLast(new HashMap<>());
            }
            substitutionMapList.getLast().put(varDef.var, varDef.term);
        }

        return substitutionMapList.stream()
                .map(m -> m.entrySet().stream().collect(substitutionFactory.toSubstitution()))
                .collect(ImmutableCollectors.toList());
    }

    /**
     * @param expr                 expression
     * @param childVariables       the set of variables that can occur in the expression
     * @param externalBindings
     * @param treatBNodeAsVariable
     */

    private ImmutableExpression getFilterExpression(ValueExpr expr, ImmutableSet<Variable> childVariables,
                                                    ImmutableMap<Variable, GroundTerm> externalBindings, boolean treatBNodeAsVariable) {

        ImmutableTerm term = getTerm(expr, childVariables, externalBindings, treatBNodeAsVariable);

        ImmutableTerm xsdBooleanTerm = term.inferType()
                .flatMap(TermTypeInference::getTermType)
                .filter(t -> t instanceof RDFDatatype)
                .filter(t -> ((RDFDatatype) t).isA(XSD.BOOLEAN))
                .isPresent()
                ? term
                : termFactory.getSPARQLEffectiveBooleanValue(term);

        return termFactory.getRDF2DBBooleanFunctionalTerm(xsdBooleanTerm);
    }

    /**
     * @param expr           expression
     * @param knownVariables the set of variables that can occur in the expression
     *                       (the rest will be replaced with NULL)
     * @param externalBindings
     * @return term
     */

    // TODO: QueryModelVisitor
    private ImmutableTerm getTerm(ValueExpr expr, Set<Variable> knownVariables, ImmutableMap<Variable, GroundTerm> externalBindings,
                                  boolean treatBNodeAsVariable) {

        if (expr instanceof Var)
            return translateRDF4JVar((Var) expr, knownVariables, false, externalBindings, treatBNodeAsVariable);
        if (expr instanceof ValueConstant) {
            Value v = ((ValueConstant) expr).getValue();
            return getValueTranslator().getTermForLiteralOrIri(v);
        }
        if (expr instanceof Bound) {
            // BOUND (Sec 17.4.1.1)
            // xsd:boolean  BOUND (variable var)
            Var v = ((Bound) expr).getArg();
            Variable var = termFactory.getVariable(v.getName());
            return knownVariables.contains(var)
                    ? getFunctionalTerm(SPARQL.BOUND, var)
                    : termFactory.getRDFLiteralConstant("false", XSD.BOOLEAN);
        }
        if (expr instanceof UnaryValueOperator) {
            return getTerm((UnaryValueOperator) expr, knownVariables, externalBindings, treatBNodeAsVariable);
        }
        if (expr instanceof BinaryValueOperator) {
            return getTerm((BinaryValueOperator) expr, knownVariables, externalBindings, treatBNodeAsVariable);
        }
        if (expr instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) expr;

            ImmutableList<ImmutableTerm> terms = f.getArgs().stream()
                    .map(a -> getTerm(a, knownVariables, externalBindings, treatBNodeAsVariable))
                    .collect(ImmutableCollectors.toList());

            String functionName = extractFunctionName(f.getURI());

            Optional<SPARQLFunctionSymbol> optionalFunctionSymbol = functionSymbolFactory.getSPARQLFunctionSymbol(
                    functionName, terms.size());

            if (optionalFunctionSymbol.isPresent()) {
                return termFactory.getImmutableFunctionalTerm(optionalFunctionSymbol.get(), terms);
            }
        }
        if (expr instanceof NAryValueOperator) {
            return getTerm((NAryValueOperator) expr, knownVariables, externalBindings, treatBNodeAsVariable);
        }
        if (expr instanceof BNodeGenerator) {
            Optional<ImmutableTerm> term = Optional.ofNullable(((BNodeGenerator) expr).getNodeIdExpr())
                    .map(t -> getTerm(t, knownVariables, externalBindings, treatBNodeAsVariable));

            return term
                    .map(t -> getFunctionalTerm(SPARQL.BNODE, t))
                    .orElseGet(() -> getFunctionalTerm(SPARQL.BNODE));
        }
        if (expr instanceof If) {
            If ifExpr = (If) expr;

            return getFunctionalTerm(SPARQL.IF,
                    convertToXsdBooleanTerm(getTerm(ifExpr.getCondition(), knownVariables, externalBindings, treatBNodeAsVariable)),
                    getTerm(ifExpr.getResult(), knownVariables, externalBindings, treatBNodeAsVariable),
                    getTerm(ifExpr.getAlternative(), knownVariables, externalBindings, treatBNodeAsVariable));
        }
        // other subclasses
        // SubQueryValueOperator
        // ValueExprTripleRef
        throw new RuntimeException(new OntopUnsupportedKGQueryException("The expression " + expr + " is not supported yet!"));
    }

    private ImmutableTerm getTerm(UnaryValueOperator expr, Set<Variable> knownVariables, ImmutableMap<Variable, GroundTerm> externalBindings,
                                  boolean treatBNodeAsVariable) {

        if (expr.getArg() == null) {
            if (expr instanceof Count)  // O-ary count
                return getFunctionalTerm(SPARQL.COUNT);

            throw new RuntimeException(new OntopUnsupportedKGQueryException("The expression " + expr + " is not supported yet!"));
        }

        ImmutableTerm term = getTerm(expr.getArg(), knownVariables, externalBindings, treatBNodeAsVariable);

        if (expr instanceof AbstractAggregateOperator) {
            AbstractAggregateOperator aggExpr = (AbstractAggregateOperator) expr;
            if (aggExpr instanceof Count) { //Unary count
                return getAggregateFunctionalTerm(SPARQL.COUNT, aggExpr.isDistinct(), term);
            }
            if (aggExpr instanceof Avg) {
                return getAggregateFunctionalTerm(SPARQL.AVG, aggExpr.isDistinct(), term);
            }
            if (aggExpr instanceof Sum) {
                return getAggregateFunctionalTerm(SPARQL.SUM, aggExpr.isDistinct(), term);
            }
            if (aggExpr instanceof Min) {
                return getFunctionalTerm(SPARQL.MIN, term);
            }
            if (aggExpr instanceof Max) {
                return getFunctionalTerm(SPARQL.MAX, term);
            }
            if (aggExpr instanceof Sample) {
                return getFunctionalTerm(SPARQL.SAMPLE, term);
            }
            if (aggExpr instanceof GroupConcat) {
                String separator = Optional.ofNullable(((GroupConcat) aggExpr).getSeparator())
                        .map(e -> ((ValueConstant) e).getValue().stringValue())
                        // Default separator
                        .orElse(" ");

                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getSPARQLGroupConcatFunctionSymbol(separator, aggExpr.isDistinct()),
                        term);
            }
            throw new RuntimeException("Unreachable: all subclasses covered");
        }
        if (expr instanceof Not) {
            return getFunctionalTerm(XPathFunction.NOT.getIRIString(), convertToXsdBooleanTerm(term));
        }
        if (expr instanceof IsNumeric) {
            // isNumeric (Sec 17.4.2.4) for checking whether the argument is a numeric value
            return getFunctionalTerm(SPARQL.IS_NUMERIC, term);
        }
        if (expr instanceof IsLiteral) {
            return getFunctionalTerm(SPARQL.IS_LITERAL, term);
        }
        if (expr instanceof IsURI) {
            return getFunctionalTerm(SPARQL.IS_IRI, term);
        }
        if (expr instanceof Str) {
            return getFunctionalTerm(SPARQL.STR, term);
        }
        if (expr instanceof Datatype) {
            return getFunctionalTerm(SPARQL.DATATYPE, term);
        }
        if (expr instanceof IsBNode) {
            return getFunctionalTerm(SPARQL.IS_BLANK, term);
        }
        if (expr instanceof Lang) {
            if (expr.getArg() instanceof Var)
                return getFunctionalTerm(SPARQL.LANG, term);
            throw new RuntimeException(new OntopUnsupportedKGQueryException("A variable or a value is expected in " + expr));
        }
        if (expr instanceof IRIFunction) {
            // IRIFunction: IRI (Sec 17.4.2.8) for constructing IRIs
            Optional<org.apache.commons.rdf.api.IRI> optionalBaseIRI = Optional.ofNullable(((IRIFunction) expr).getBaseURI())
                    .map(rdfFactory::createIRI);

            SPARQLFunctionSymbol functionSymbol = optionalBaseIRI
                    .map(functionSymbolFactory::getIRIFunctionSymbol)
                    .orElseGet(functionSymbolFactory::getIRIFunctionSymbol);

            return termFactory.getImmutableFunctionalTerm(functionSymbol, term);
        }
        // subclasses missing:
        //  - IsResource
        //   - LocalName
        //   - Namespace
        //   - Label
        //   - Like
        throw new RuntimeException(new OntopUnsupportedKGQueryException("The expression " + expr + " is not supported yet!"));
    }

    private ImmutableTerm getTerm(BinaryValueOperator expr, Set<Variable> knownVariables, ImmutableMap<Variable, GroundTerm> externalBindings,
                                  boolean treatBNodeAsVariable) {

        ImmutableTerm term1 = getTerm(expr.getLeftArg(), knownVariables, externalBindings, treatBNodeAsVariable);
        ImmutableTerm term2 = getTerm(expr.getRightArg(), knownVariables, externalBindings, treatBNodeAsVariable);

        if (expr instanceof And) {
            return getFunctionalTerm(SPARQL.LOGICAL_AND, convertToXsdBooleanTerm(term1), convertToXsdBooleanTerm(term2));
        }
        if (expr instanceof Or) {
            return getFunctionalTerm(SPARQL.LOGICAL_OR, convertToXsdBooleanTerm(term1), convertToXsdBooleanTerm(term2));
        }
        if (expr instanceof SameTerm) {
            // sameTerm (Sec 17.4.1.8)
            // Corresponds to the STRICT equality (same lexical value, same type)
            return getFunctionalTerm(SPARQL.SAME_TERM, term1, term2);
        }
        if (expr instanceof Regex) {
            // REGEX (Sec 17.4.3.14)
            // xsd:boolean  REGEX (string literal text, simple literal pattern)
            // xsd:boolean  REGEX (string literal text, simple literal pattern, simple literal flags)
            Regex reg = (Regex) expr;
            return (reg.getFlagsArg() != null)
                    ? getFunctionalTerm(SPARQL.REGEX, term1, term2,
                    getTerm(reg.getFlagsArg(), knownVariables, externalBindings, treatBNodeAsVariable))
                    : getFunctionalTerm(SPARQL.REGEX, term1, term2);
        }
        if (expr instanceof Compare) {
            switch (((Compare) expr).getOperator()) {
                case EQ:
                    return getFunctionalTerm(SPARQL.EQ, term1, term2);
                case LT:
                    return getFunctionalTerm(SPARQL.LESS_THAN, term1, term2);
                case GT:
                    return getFunctionalTerm(SPARQL.GREATER_THAN, term1, term2);
                case NE:
                    return getFunctionalTerm(XPathFunction.NOT.getIRIString(), getFunctionalTerm(SPARQL.EQ, term1, term2));
                case LE:
                    return getFunctionalTerm(XPathFunction.NOT.getIRIString(), getFunctionalTerm(SPARQL.GREATER_THAN, term1, term2));
                case GE:
                    return getFunctionalTerm(XPathFunction.NOT.getIRIString(), getFunctionalTerm(SPARQL.LESS_THAN, term1, term2));
                default:
                    throw new RuntimeException(new OntopUnsupportedKGQueryException("Unsupported operator: " + expr));
            }
        }
        if (expr instanceof MathExpr) {
            return getFunctionalTerm(NumericalOperations.get(((MathExpr) expr).getOperator()), term1, term2);
        }
        /*
         * Restriction: the first argument must be LANG(...) and the second  a constant
         * (for guaranteeing that the langMatches logic is not delegated to the native query)
         */
        if (expr instanceof LangMatches) {
            if (!(term1 instanceof ImmutableFunctionalTerm
                    && ((ImmutableFunctionalTerm) term1).getFunctionSymbol() instanceof LangSPARQLFunctionSymbol)
                    || !(term2 instanceof RDFConstant)) {
                throw new RuntimeException(new OntopUnsupportedKGQueryException("The function langMatches is " +
                        "only supported with lang(..) function for the first argument and a constant for the second"));
            }

            return getFunctionalTerm(SPARQL.LANG_MATCHES, term1, term2);
        }
        throw new RuntimeException("Unreachable: all subclasses covered");
    }

    private ImmutableTerm getTerm(NAryValueOperator expr, Set<Variable> knownVariables, ImmutableMap<Variable, GroundTerm> externalBindings,
                                  boolean treatBNodeAsVariable) {

        ImmutableList<ImmutableTerm> terms = expr.getArguments().stream()
                .map(a -> getTerm(a, knownVariables, externalBindings, treatBNodeAsVariable))
                .collect(ImmutableCollectors.toList());

        if (expr instanceof Coalesce) {
            SPARQLFunctionSymbol functionSymbol = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                    SPARQL.COALESCE, terms.size());
            return termFactory.getImmutableFunctionalTerm(functionSymbol, terms);
        }
        if (expr instanceof ListMemberOperator) {
            if (terms.size() < 2)
                throw new MinorOntopInternalBugException("Was not expecting a ListMemberOperator from RDF4J with less than 2 terms");

            ImmutableTerm firstArgument = terms.get(0);
            return terms.stream()
                    .skip(1)
                    .map(t -> getFunctionalTerm(SPARQL.EQ, firstArgument, t))
                    .reduce((e1, e2) -> getFunctionalTerm(SPARQL.LOGICAL_OR, e1, e2))
                    .orElseThrow(() -> new MinorOntopInternalBugException("Cannot happen because there are at least 2 terms"));
        }
        throw new RuntimeException("Unreachable: all subclasses covered");
    }

    private ImmutableFunctionalTerm getFunctionalTerm(String functionName) {
        return termFactory.getImmutableFunctionalTerm(functionSymbolFactory.getRequiredSPARQLFunctionSymbol(functionName, 0));
    }

    private ImmutableFunctionalTerm getFunctionalTerm(String functionName, ImmutableTerm t) {
        return termFactory.getImmutableFunctionalTerm(functionSymbolFactory.getRequiredSPARQLFunctionSymbol(functionName, 1), t);
    }

    private ImmutableFunctionalTerm getFunctionalTerm(String functionName, ImmutableTerm t1, ImmutableTerm t2) {
        return termFactory.getImmutableFunctionalTerm(functionSymbolFactory.getRequiredSPARQLFunctionSymbol(functionName, 2), t1, t2);
    }

    private ImmutableFunctionalTerm getFunctionalTerm(String functionName, ImmutableTerm t1, ImmutableTerm t2, ImmutableTerm t3) {
        return termFactory.getImmutableFunctionalTerm(functionSymbolFactory.getRequiredSPARQLFunctionSymbol(functionName, 3), t1, t2, t3);
    }

    private ImmutableFunctionalTerm getAggregateFunctionalTerm(String officialName, boolean isDistinct, ImmutableTerm t) {
        return termFactory.getImmutableFunctionalTerm(
                isDistinct
                        ? functionSymbolFactory.getRequiredSPARQLDistinctAggregateFunctionSymbol(officialName, 1)
                        : functionSymbolFactory.getRequiredSPARQLFunctionSymbol(officialName, 1),
                t);
    }

    /**
     * Changes some function names when RDF4J abuses the SPARQL standard (i.e. is too tightly-coupled)
     *
     * The typical example is the YEAR() function which is replaced by RDF4J by fn:year-from-dateTime because
     * the SPARQL 1.1 specification has only consider the case of xsd:dateTime, not xsd:date.
     * Obviously, all the major implementations also support the case of xsd:date and use the fun:year-from-date when
     * appropriated.
     *
     * This method reverses fn:year-from-dateTime into YEAR, as it now maps to a function symbol that accepts
     * both xsd:date and xsd:dateTime.
     *
     */
    private String extractFunctionName(String uri) {

        if (uri.equals(XPathFunction.YEAR_FROM_DATETIME.getIRIString()))
            return SPARQL.YEAR;
        else if (uri.equals(XPathFunction.MONTH_FROM_DATETIME.getIRIString()))
            return SPARQL.MONTH;
        else if (uri.equals(XPathFunction.DAY_FROM_DATETIME.getIRIString()))
            return SPARQL.DAY;
        else
            return uri;
    }


    /**
     * Translates a RDF4J "Var" (which can be a variable or a constant) into a Ontop term.
     */
    private VariableOrGroundTerm translateRDF4JVar(Var v, Set<Variable> subtreeVariables, boolean leafNode,
                                                   ImmutableMap<Variable, GroundTerm> externalBindings,
                                                   boolean treatBNodeAsVariable) {
        // If this "Var" is a constant
        if ((v.hasValue()))
            return getValueTranslator().getTermForLiteralOrIri(v.getValue());
        if (v.isAnonymous() && !treatBNodeAsVariable)
            return termFactory.getConstantBNode(v.getName());

        // Otherwise, this "Var" is a variable
        Variable var = termFactory.getVariable(v.getName());
        // If the subtree is empty, create a variable
        if (leafNode)
            return var;
        // Otherwise, check whether the variable is projected
        return subtreeVariables.contains(var) ?
                var :
                Optional.ofNullable(externalBindings.get(var))
                        .orElseGet(termFactory::getNullConstant);
    }

    private TranslationResult createTranslationResult(IQTree iqTree, ImmutableSet<Variable> nullableVariables)  {
        return new TranslationResult(
                iqTree,
                nullableVariables);
    }

    private IQTree applyExternalBindingFilter(IQTree iqTree, ImmutableMap<Variable, GroundTerm> externalBindings,
                                              Set<Variable> bindingVariablesToFilter) {

        Optional<ImmutableExpression> conjunction = termFactory.getConjunction(
                bindingVariablesToFilter.stream()
                        .map(v -> termFactory.getStrictEquality(v, externalBindings.get(v))));

        // Filter variables according to bindings
        return conjunction
                .map(iqFactory::createFilterNode)
                .<IQTree>map(f -> iqFactory.createUnaryIQTree(f, iqTree))
                .orElse(iqTree);
    }


    private ImmutableTerm convertToXsdBooleanTerm(ImmutableTerm term) {

        return term.inferType()
                .flatMap(TermTypeInference::getTermType)
                .filter(t -> t instanceof RDFDatatype)
                .filter(t -> ((RDFDatatype) t).isA(XSD.BOOLEAN))
                .isPresent() ?
                term :
                termFactory.getSPARQLEffectiveBooleanValue(term);
    }

    private static final ImmutableMap<MathExpr.MathOp, String> NumericalOperations =
            new ImmutableMap.Builder<MathExpr.MathOp, String>()
                    .put(MathExpr.MathOp.PLUS, SPARQL.NUMERIC_ADD)
                    .put(MathExpr.MathOp.MINUS, SPARQL.NUMERIC_SUBTRACT)
                    .put(MathExpr.MathOp.MULTIPLY, SPARQL.NUMERIC_MULTIPLY)
                    .put(MathExpr.MathOp.DIVIDE, SPARQL.NUMERIC_DIVIDE)
                    .build();


    private static class TranslationResult {
        private final IQTree iqTree;
        private final ImmutableSet<Variable> nullableVariables;

        /**
         * Do not call it directly, use createTranslationResult instead
         */
        private TranslationResult(IQTree iqTree, ImmutableSet<Variable> nullableVariables) {
            this.nullableVariables = nullableVariables;
            this.iqTree = iqTree;
        }
    }

    private static class NonProjVarRenamings {
        private final InjectiveVar2VarSubstitution left, right;

        private NonProjVarRenamings(InjectiveVar2VarSubstitution left, InjectiveVar2VarSubstitution right) {
            this.left = left;
            this.right = right;
        }
    }

    private static class Sparql2IqConversionException extends OntopInternalBugException {

        Sparql2IqConversionException(String s) {
            super(s);
        }
    }

    private static class VarDef {
        private final Variable var;
        private final ImmutableTerm term;

        private VarDef(Variable var, ImmutableTerm term) {
            this.var = var;
            this.term = term;
        }
    }

    private RDF4JValueTranslator getValueTranslator() {
        return new RDF4JValueTranslator(termFactory, rdfFactory, typeFactory);
    }

}
