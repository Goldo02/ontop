package it.unibz.inf.ontop.query.translation.impl;

import com.google.common.collect.*;
import com.google.inject.Inject;
import it.unibz.inf.ontop.model.atom.AtomPredicate;
import it.unibz.inf.ontop.model.atom.DataAtom;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import it.unibz.inf.ontop.query.translation.InsertClauseNormalizer;
import it.unibz.inf.ontop.query.translation.RDF4JQueryTranslator;
import it.unibz.inf.ontop.exception.MinorOntopInternalBugException;
import it.unibz.inf.ontop.exception.OntopInternalBugException;
import it.unibz.inf.ontop.exception.OntopInvalidKGQueryException;
import it.unibz.inf.ontop.exception.OntopUnsupportedKGQueryException;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.IQTree;
import it.unibz.inf.ontop.iq.UnaryIQTree;
import it.unibz.inf.ontop.iq.impl.QueryNodeRenamer;
import it.unibz.inf.ontop.iq.node.*;
import it.unibz.inf.ontop.iq.transform.impl.HomogeneousIQTreeVisitingTransformer;
import it.unibz.inf.ontop.model.atom.AtomFactory;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.term.functionsymbol.FunctionSymbol;
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
import it.unibz.inf.ontop.substitution.SubstitutionApplicatorVariable;
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
import org.eclipse.rdf4j.query.algebra.Count;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class RDF4JQueryTranslatorImpl implements RDF4JQueryTranslator {

    private final CoreUtilsFactory coreUtilsFactory;
    private final TermFactory termFactory;
    private final SubstitutionFactory substitutionFactory;
    private final TypeFactory typeFactory;
    private final IntermediateQueryFactory iqFactory;
    private final AtomFactory atomFactory;
    private final RDF rdfFactory;
    private final FunctionSymbolFactory functionSymbolFactory;
    private final InsertClauseNormalizer insertClauseNormalizer;

    private static final Logger LOGGER = LoggerFactory.getLogger(RDF4JQueryTranslatorImpl.class);
    private static final boolean IS_DEBUG_ENABLED = LOGGER.isDebugEnabled();

    @Inject
    public RDF4JQueryTranslatorImpl(CoreUtilsFactory coreUtilsFactory, TermFactory termFactory, SubstitutionFactory substitutionFactory,
                                    TypeFactory typeFactory, IntermediateQueryFactory iqFactory, AtomFactory atomFactory, RDF rdfFactory,
                                    FunctionSymbolFactory functionSymbolFactory,
                                    InsertClauseNormalizer insertClauseNormalizer) {
        this.coreUtilsFactory = coreUtilsFactory;
        this.termFactory = termFactory;
        this.substitutionFactory = substitutionFactory;
        this.typeFactory = typeFactory;
        this.iqFactory = iqFactory;
        this.atomFactory = atomFactory;
        this.rdfFactory = rdfFactory;
        this.functionSymbolFactory = functionSymbolFactory;
        this.insertClauseNormalizer = insertClauseNormalizer;
    }

    @Override
    public IQ translateQuery(ParsedQuery pq, BindingSet bindings) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        if (IS_DEBUG_ENABLED)
            LOGGER.debug("Parsed query:\n{}", pq);

        ImmutableMap<Variable, GroundTerm> externalBindings = convertExternalBindings(bindings);

        IQTree tree = translate(pq.getTupleExpr(), externalBindings, pq.getDataset(), true).iqTree;

        ImmutableSet<Variable> vars = tree.getVariables();

        // Assumption: the binding names in the parsed query are in the desired order
        ImmutableList<Variable> projectedVars = pq.getTupleExpr().getBindingNames().stream()
                .map(termFactory::getVariable)
                // filter out the extra bindings generated by the rdf4j API for constants
                .filter(vars::contains)
                .collect(ImmutableCollectors.toList());
        if (IS_DEBUG_ENABLED)
            LOGGER.debug("IQTree (before normalization):\n{}", tree);

        return iqFactory.createIQ(
                atomFactory.getDistinctVariableOnlyDataAtom(
                        atomFactory.getRDFAnswerPredicate(projectedVars.size()),
                        projectedVars
                ),
                tree
        ).normalizeForOptimization();
    }

    protected ImmutableMap<Variable, GroundTerm> convertExternalBindings(BindingSet bindings) {
        return bindings.getBindingNames().stream()
                .collect(ImmutableCollectors.toMap(
                        termFactory::getVariable,
                        n -> getTermForLiteralOrIri(bindings.getValue(n))));
    }

    @Override
    public IQ translateAskQuery(ParsedQuery pq, BindingSet bindings) throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException {

        if (IS_DEBUG_ENABLED)
            LOGGER.debug("Parsed query:\n{}", pq);

        ImmutableMap<Variable, GroundTerm> externalBindings = convertExternalBindings(bindings);

        IQTree tree = translate(pq.getTupleExpr(), externalBindings, pq.getDataset(), true).iqTree;

        if (IS_DEBUG_ENABLED)
            LOGGER.debug("IQTree (before normalization):\n{}", tree);
        return iqFactory.createIQ(
                atomFactory.getDistinctVariableOnlyDataAtom(
                        atomFactory.getRDFAnswerPredicate(0),
                        ImmutableList.of()
                ),
                projectOutAllVars(tree)
        ).normalizeForOptimization();
    }

    @Override
    public ImmutableSet<IQ> translateInsertOperation(ParsedUpdate parsedUpdate) throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException {
        ImmutableSet.Builder<IQ> iqsBuilder = ImmutableSet.builder();

        Map<UpdateExpr, Dataset> datasetMapping = parsedUpdate.getDatasetMapping();

        for (UpdateExpr expr: parsedUpdate.getUpdateExprs()) {
            if (expr instanceof Modify) {
                iqsBuilder.addAll(translateInsertExpression((Modify) expr, datasetMapping.get(expr)));
            }
            else
                throw new OntopUnsupportedKGQueryException("Unsupported update: " + expr);
        }
        return iqsBuilder.build();
    }

    protected ImmutableSet<IQ> translateInsertExpression(Modify expression, @Nullable Dataset dataset) throws OntopUnsupportedKGQueryException, OntopInvalidKGQueryException {
        if (expression.getDeleteExpr() != null)
            throw new OntopUnsupportedKGQueryException("DELETE clauses are not supported");
        if (expression.getInsertExpr() == null)
            throw new OntopInvalidKGQueryException("Was expecting an INSERT clause");

        IQTree whereTree = expression.getWhereExpr() == null
                ? iqFactory.createTrueNode()
                : translate(expression.getWhereExpr(), ImmutableMap.of(), dataset, true).iqTree;

        @Nullable Dataset insertDataset;
        if (dataset != null) {
            SimpleDataset newDataset = new SimpleDataset();
            IRI defaultInsertGraph = dataset.getDefaultInsertGraph();
            if (defaultInsertGraph != null)
                newDataset.addDefaultGraph(defaultInsertGraph);
            insertDataset = newDataset;
        }
        else
            insertDataset = null;

        IQTree insertTree = translate(expression.getInsertExpr(), ImmutableMap.of(), insertDataset, false).iqTree;

        ImmutableSet.Builder<IQ> iqsBuilder = ImmutableSet.builder();
        ImmutableSet<IntensionalDataNode> dataNodes = extractIntensionalDataNodesFromHead(insertTree);


        InsertClauseNormalizer.Result normalization = insertClauseNormalizer.normalize(dataNodes, whereTree);
        IQTree normalizedSubTree = normalization.getConstructionNode()
                .map(c -> (IQTree) iqFactory.createUnaryIQTree(c, whereTree))
                .orElse(whereTree);

        // NB: when there is no default graph
        if (normalizedSubTree.isDeclaredAsEmpty())
            return ImmutableSet.of();

        for (IntensionalDataNode dataNode : dataNodes) {
            iqsBuilder.add(createInsertIQ(dataNode, normalization.getBNodeVariableMap(), normalizedSubTree));
        }
        return iqsBuilder.build();
    }

    private ImmutableSet<IntensionalDataNode> extractIntensionalDataNodesFromHead(IQTree insertTree) throws OntopInvalidKGQueryException {
        if (insertTree instanceof IntensionalDataNode)
            return ImmutableSet.of((IntensionalDataNode) insertTree);

        QueryNode rootNode = insertTree.getRootNode();
        if ((rootNode instanceof InnerJoinNode) && !((InnerJoinNode) rootNode).getOptionalFilterCondition().isPresent()) {

            ImmutableSet.Builder<IntensionalDataNode> setBuilder = ImmutableSet.builder();
            for (IQTree child : insertTree.getChildren()) {
                setBuilder.addAll(extractIntensionalDataNodesFromHead(child));
            }
            return setBuilder.build();
        }

        throw new OntopInvalidKGQueryException("Invalid INSERT clause");
    }

    private IQ createInsertIQ(IntensionalDataNode dataNode, ImmutableMap<BNode, Variable> bnodeVariableMap, IQTree subTree) {

        DataAtom<AtomPredicate> dataNodeAtom = dataNode.getProjectionAtom();

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(subTree.getKnownVariables(), dataNode.getKnownVariables()));

        ImmutableList<VariableOrGroundTerm> normalizedArguments = dataNodeAtom.getArguments().stream()
                .map(a -> (a instanceof BNode)
                        ? Optional.ofNullable(bnodeVariableMap.get(a))
                        .orElseThrow(() -> new MinorOntopInternalBugException(
                                "BNodes from the INSERT clause should be replaced internally by variables generated out of templates"))
                        : a)
                .collect(ImmutableCollectors.toList());

        ImmutableMap<Variable, VariableOrGroundTerm> map = normalizedArguments.stream()
                .map(t -> Maps.immutableEntry(t instanceof Variable ? (Variable)t : variableGenerator.generateNewVariable(), t))
                .distinct()
                .collect(ImmutableCollectors.toMap());

        DistinctVariableOnlyDataAtom projectionAtom = atomFactory.getDistinctVariableOnlyDataAtom(dataNodeAtom.getPredicate(),
                ImmutableList.copyOf(map.keySet()));

        ConstructionNode constructionNode = iqFactory.createConstructionNode(
                map.keySet(),
                substitutionFactory.getSubstitutionRemoveIdentityEntries(map.entrySet(), Map.Entry::getKey, Map.Entry::getValue));

        IQ newIQ = iqFactory.createIQ(
                projectionAtom,
                iqFactory.createUnaryIQTree(constructionNode, subTree));

        return newIQ.normalizeForOptimization();
    }

    private IQTree projectOutAllVars(IQTree tree) {
        if (tree.getRootNode() instanceof QueryModifierNode) {
            return iqFactory.createUnaryIQTree(
                    (UnaryOperatorNode) tree.getRootNode(),
                    projectOutAllVars(((UnaryIQTree) tree).getChild())
            );
        }
        return iqFactory.createUnaryIQTree(
                iqFactory.createConstructionNode(
                        ImmutableSet.of()
                ),
                tree
        );
    }

    private TranslationResult translate(TupleExpr node, ImmutableMap<Variable, GroundTerm> externalBindings,
                                        @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        if (node instanceof QueryRoot) {
            return translate(((QueryRoot) node).getArg(), externalBindings, dataset, treatBNodeAsVariable);
        }

        if (node instanceof StatementPattern){
            StatementPattern stmt = (StatementPattern)node;
            if (stmt.getScope().equals(StatementPattern.Scope.NAMED_CONTEXTS))
                return translateQuadPattern(stmt, externalBindings, dataset, treatBNodeAsVariable);

            return translateTriplePattern((StatementPattern) node, externalBindings, dataset, treatBNodeAsVariable);
        }

        if (node instanceof Join)
            return translateJoinLikeNode((Join) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof LeftJoin)
            return translateJoinLikeNode((LeftJoin) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Difference)
            return translateDifference((Difference) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Union)
            return translateUnion((Union) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Filter)
            return translateFilter((Filter) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Projection)
            return translateProjection((Projection) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Slice)
            return translateSlice((Slice) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Distinct)
            return translateDistinctOrReduced(node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Reduced)
            return translateDistinctOrReduced(node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof SingletonSet)
            return translateSingletonSet(externalBindings, treatBNodeAsVariable);

        if (node instanceof Group)
            return translateAggregate((Group) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof Extension)
            return translateExtension((Extension) node, externalBindings, dataset, treatBNodeAsVariable);

        if (node instanceof BindingSetAssignment)
            return translateBindingSetAssignment((BindingSetAssignment) node, externalBindings);

        if (node instanceof Order)
            return translateOrder((Order) node, externalBindings, dataset, treatBNodeAsVariable);

        throw new OntopUnsupportedKGQueryException("Unsupported SPARQL operator: " + node.toString());
    }

    private TranslationResult translateDifference(Difference diff, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                  @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult leftTranslation = translate(diff.getLeftArg(), externalBindings, dataset, treatBNodeAsVariable);
        TranslationResult rightTranslation = translate(diff.getRightArg(), externalBindings, dataset, treatBNodeAsVariable);

        ImmutableSet<Variable> leftVars = leftTranslation.iqTree.getVariables();
        ImmutableSet<Variable> sharedVars = Sets.intersection(rightTranslation.iqTree.getVariables(), leftVars).immutableCopy();

        if (sharedVars.isEmpty()) {
            return leftTranslation;
        }

        VariableGenerator vGen = coreUtilsFactory.createVariableGenerator(
                Sets.union(leftTranslation.iqTree.getKnownVariables(), rightTranslation.iqTree.getKnownVariables()));

        InjectiveVar2VarSubstitution sub = substitutionFactory.getInjectiveFreshVar2VarSubstitution(sharedVars.stream(), vGen);

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
        QueryNodeRenamer nodeTransformer = new QueryNodeRenamer(iqFactory, renaming, atomFactory);
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

    private TranslationResult translateAggregate(Group groupNode, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                 @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(groupNode.getArg(), externalBindings, dataset, treatBNodeAsVariable);
        AggregationNode an = getAggregationNode(groupNode, child.iqTree.getVariables(), externalBindings, treatBNodeAsVariable);

        UnaryIQTree aggregationTree = iqFactory.createUnaryIQTree(
                an,
                child.iqTree
        );
        ImmutableSet<Variable> nullableVariables = getAggregateOutputNullableVars(
                an,
                child.nullableVariables
        );

        return createTranslationResultFromExtendedProjection(an, aggregationTree, nullableVariables, externalBindings);
    }

    private ImmutableSet<Variable> getAggregateOutputNullableVars(AggregationNode an, ImmutableSet<Variable> childNullableVars) {
        return Sets.union(
                        Sets.intersection(an.getGroupingVariables(), childNullableVars),
                        an.getSubstitution().restrictRange(t -> t.getFunctionSymbol().isNullable(ImmutableSet.of(0)))
                                .getDomain())
                .immutableCopy();
    }

    private AggregationNode getAggregationNode(Group groupNode, ImmutableSet<Variable> childVariables,
                                               ImmutableMap<Variable, GroundTerm> externalBindings, boolean treatBNodeAsVariable) {

        // Assumption: every variable used in a definition is itself defined either in the subtree of in a previous ExtensionElem
        ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergedVarDefs =
                getGroupVarDefs(groupNode.getGroupElements(), childVariables, externalBindings, treatBNodeAsVariable);

        if (mergedVarDefs.size() > 1) {
            throw new Sparql2IqConversionException("Unexpected parsed SPARQL query: nested complex projections appear " +
                    "within an RDF4J Group node: " + groupNode);
        }
        return iqFactory.createAggregationNode(
                groupNode.getGroupBindingNames().stream()
                        .map(termFactory::getVariable)
                        .collect(ImmutableCollectors.toSet()),
                mergedVarDefs.get(0).castTo(ImmutableFunctionalTerm.class) // the only substitution
        );
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> getGroupVarDefs(List<GroupElem> list,
                                                                                ImmutableSet<Variable> childVariables,
                                                                                ImmutableMap<Variable, GroundTerm> externalBindings,
                                                                                boolean treatBNodeAsVariable) {
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

    private TranslationResult translateOrder(Order node, ImmutableMap<Variable, GroundTerm> externalBindings,
                                             @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(node.getArg(), externalBindings, dataset, treatBNodeAsVariable);
        ImmutableSet<Variable> variables = child.iqTree.getVariables();

        ImmutableList<OrderByNode.OrderComparator> comparators = node.getElements().stream()
                .map(o -> getOrderComparator(o, variables, externalBindings, treatBNodeAsVariable))
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

    private Optional<OrderByNode.OrderComparator> getOrderComparator(OrderElem oe, ImmutableSet<Variable> variables,
                                                                     ImmutableMap<Variable, GroundTerm> externalBindings,
                                                                     boolean treatBNodeAsVariable) {
        ImmutableTerm expr = getTerm(oe.getExpr(), variables, externalBindings, treatBNodeAsVariable);
        if (expr instanceof NonGroundTerm)
            return Optional.of(iqFactory.createOrderComparator((NonGroundTerm) expr, oe.isAscending()));

        return Optional.empty();
    }


    private TranslationResult translateBindingSetAssignment(BindingSetAssignment node, ImmutableMap<Variable, GroundTerm> externalBindings) {

        Constant nullConstant = termFactory.getNullConstant();

        ImmutableSet<Variable> allVars = node.getBindingNames().stream()
                .map(termFactory::getVariable)
                .collect(ImmutableCollectors.toSet());

        ImmutableList<ImmutableSubstitution<ImmutableTerm>> substitutions =
                StreamSupport.stream(node.getBindingSets().spliterator(), false)
                        .map(bs -> substitutionFactory.getSubstitution(
                                bs.getBindingNames(),
                                termFactory::getVariable,
                                x -> getTermForBinding(x, bs, nullConstant)))
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
        Binding binding = bindingSet.getBinding(x);
        return binding == null
                ? nullConstant :
                getTermForLiteralOrIri(binding.getValue());
    }

    private TranslationResult translateSingletonSet(ImmutableMap<Variable, GroundTerm> externalBindings, boolean treatBNodeAsVariable) {
        return createTranslationResult(
                iqFactory.createTrueNode(),
                ImmutableSet.of()
        );
    }

    private TranslationResult translateDistinctOrReduced(TupleExpr genNode, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                         @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child;
        if (genNode instanceof Distinct) {
            child = translate(((Distinct) genNode).getArg(), externalBindings, dataset, treatBNodeAsVariable);
        } else if (genNode instanceof Reduced) {
            child = translate(((Reduced) genNode).getArg(), externalBindings, dataset, treatBNodeAsVariable);
        } else {
            throw new Sparql2IqConversionException("Unexpected node type for node: " + genNode.toString());
        }
        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        iqFactory.createDistinctNode(),
                        child.iqTree
                ),
                child.nullableVariables
        );
    }

    private TranslationResult translateSlice(Slice node, ImmutableMap<Variable, GroundTerm> externalBindings, Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(node.getArg(), externalBindings, dataset, treatBNodeAsVariable);

        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        getSliceNode(node),
                        child.iqTree
                ),
                child.nullableVariables
        );
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

    private TranslationResult translateFilter(Filter filter, ImmutableMap<Variable, GroundTerm> externalBindings,
                                              @Nullable Dataset dataset, boolean treatBNodeAsVariable)
            throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult child = translate(filter.getArg(), externalBindings, dataset, treatBNodeAsVariable);
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

    private TranslationResult translateJoinLikeNode(BinaryTupleOperator join, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                    @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        if (!(join instanceof Join) && !(join instanceof LeftJoin)) {
            throw new Sparql2IqConversionException("A left or inner join is expected");
        }

        TranslationResult leftTranslation = translate(join.getLeftArg(), externalBindings, dataset, treatBNodeAsVariable);
        TranslationResult rightTranslation = translate(join.getRightArg(), externalBindings, dataset, treatBNodeAsVariable);

        ImmutableSet<Variable> nullableVarsUnion = Sets.union(leftTranslation.nullableVariables, rightTranslation.nullableVariables).immutableCopy();

        ImmutableSet<Variable> sharedVars = Sets.intersection(leftTranslation.iqTree.getVariables(), rightTranslation.iqTree.getVariables()).immutableCopy();

        ImmutableSet<Variable> toCoalesce = Sets.intersection(sharedVars, nullableVarsUnion).immutableCopy();

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(leftTranslation.iqTree.getKnownVariables(), rightTranslation.iqTree.getKnownVariables()));

        // May update the variable generator!!
        InjectiveVar2VarSubstitution leftRenamingSubstitution =
                substitutionFactory.getInjectiveFreshVar2VarSubstitution(toCoalesce.stream(), variableGenerator);

        InjectiveVar2VarSubstitution rightRenamingSubstitution =
                substitutionFactory.getInjectiveFreshVar2VarSubstitution(toCoalesce.stream(), variableGenerator);

        ImmutableSubstitution<ImmutableTerm> topSubstitution = substitutionFactory.getSubstitution(
                toCoalesce,
                v -> v,
                v -> termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.COALESCE, 2),
                        leftRenamingSubstitution.get(v),
                        rightRenamingSubstitution.get(v)));

        Optional<ImmutableExpression> filterExpression = join instanceof LeftJoin
                ? getLeftJoinFilter(
                        (LeftJoin) join,
                        topSubstitution,
                        Sets.union(leftTranslation.iqTree.getVariables(), rightTranslation.iqTree.getVariables()).immutableCopy(),
                        externalBindings,
                        treatBNodeAsVariable)
                : Optional.empty();

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

    private Optional<ImmutableExpression> getLeftJoinFilter(LeftJoin join, ImmutableSubstitution<ImmutableTerm> topSubstitution, ImmutableSet<Variable> variables,
                                                            ImmutableMap<Variable, GroundTerm> externalBindings,
                                                            boolean treatBNodeAsVariable) {
        return join.getCondition() != null ?
                Optional.of(
                        topSubstitution.applyToBooleanExpression(
                                getFilterExpression(join.getCondition(), variables, externalBindings, treatBNodeAsVariable))) :
                Optional.empty();
    }

    private ImmutableExpression generateCompatibleExpression(Variable outputVariable,
                                                             InjectiveVar2VarSubstitution leftChildSubstitution,
                                                             InjectiveVar2VarSubstitution rightChildSubstitution) {

        Variable leftVariable = SubstitutionApplicatorVariable.apply(leftChildSubstitution, outputVariable);
        Variable rightVariable = SubstitutionApplicatorVariable.apply(rightChildSubstitution, outputVariable);

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

    private TranslationResult translateProjection(Projection node, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                  @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult child = translate(node.getArg(), externalBindings, dataset, treatBNodeAsVariable);
        IQTree subQuery = child.iqTree;

        List<ProjectionElem> projectionElems = node.getProjectionElemList().getElements();

        ImmutableMap<Variable, Variable> map = projectionElems.stream()
                .collect(ImmutableCollectors.toMap(
                        pe -> termFactory.getVariable(pe.getName()),
                        pe -> termFactory.getVariable(pe.getProjectionAlias().orElse(pe.getName()))));

        ImmutableSubstitution<Variable> substitution = substitutionFactory.getSubstitutionRemoveIdentityEntries(
                        map.entrySet(), Map.Entry::getKey, Map.Entry::getValue);

        ImmutableSet<Variable> projectedVars = ImmutableSet.copyOf(map.values());

        if (substitution.isEmpty() && projectedVars.equals(child.iqTree.getVariables())) {
            return child;
        }

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(child.iqTree.getKnownVariables(), projectedVars));

        subQuery = subQuery.applyDescendingSubstitutionWithoutOptimizing(substitution, variableGenerator);
        ImmutableSet<Variable> subQueryVariables = subQuery.getVariables();

        // Substitution for possibly unbound variables
        ImmutableSubstitution<ImmutableTerm> newSubstitution = substitutionFactory.getNullSubstitution(
                Sets.difference(projectedVars, subQueryVariables));

        ConstructionNode projectNode = iqFactory.createConstructionNode(projectedVars, newSubstitution);
        UnaryIQTree constructTree = iqFactory.createUnaryIQTree(projectNode, subQuery);

        ImmutableSet<Variable> nullableVariables = SubstitutionApplicatorVariable.apply(substitution, child.nullableVariables);

        return createTranslationResultFromExtendedProjection(projectNode, constructTree, nullableVariables, externalBindings);
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
                                                                            ImmutableSet<Variable> nullableVariables,
                                                                            ImmutableMap<Variable, GroundTerm> externalBindings) {
        // Most of the time
        if (externalBindings.isEmpty())
            return createTranslationResult(tree, nullableVariables);

        Sets.SetView<Variable> externallyBoundedVariables = Sets.intersection(
                extendedProjectNode.getSubstitution().getDomain(),
                externalBindings.keySet());

        IQTree iqTree = applyExternalBindingFilter(tree, externalBindings, externallyBoundedVariables);
        return createTranslationResult(iqTree, nullableVariables);
    }

    private TranslationResult translateUnion(Union union, ImmutableMap<Variable, GroundTerm> externalBindings,
                                             @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {
        TranslationResult leftTranslation = translate(union.getLeftArg(), externalBindings, dataset, treatBNodeAsVariable);
        TranslationResult rightTranslation = translate(union.getRightArg(), externalBindings, dataset, treatBNodeAsVariable);

        IQTree leftQuery = leftTranslation.iqTree;
        IQTree rightQuery = rightTranslation.iqTree;

        VariableGenerator variableGenerator = coreUtilsFactory.createVariableGenerator(
                Sets.union(leftQuery.getKnownVariables(), rightQuery.getKnownVariables()));

        ImmutableSet<Variable> leftVariables = leftQuery.getVariables();
        ImmutableSet<Variable> rightVariables = rightQuery.getVariables();

        Sets.SetView<Variable> nullOnLeft = Sets.difference(rightVariables, leftVariables);
        Sets.SetView<Variable> nullOnRight = Sets.difference(leftVariables, rightVariables);

        ImmutableSet<Variable> allNullable = Sets.union(
                Sets.union(leftTranslation.nullableVariables, rightTranslation.nullableVariables),
                Sets.union(nullOnLeft, nullOnRight)).immutableCopy();

        ImmutableSet<Variable> rootVariables = Sets.union(leftVariables, rightVariables).immutableCopy();

        ConstructionNode leftCn = iqFactory.createConstructionNode(rootVariables, substitutionFactory.getNullSubstitution(nullOnLeft));
        ConstructionNode rightCn = iqFactory.createConstructionNode(rootVariables, substitutionFactory.getNullSubstitution(nullOnRight));

        UnionNode unionNode = iqFactory.createUnionNode(rootVariables);

        ConstructionNode rootNode = iqFactory.createConstructionNode(rootVariables);

        NonProjVarRenamings nonProjVarsRenamings = getNonProjVarsRenamings(leftQuery, rightQuery, variableGenerator);

        return createTranslationResult(
                iqFactory.createUnaryIQTree(
                        rootNode,
                        iqFactory.createNaryIQTree(
                                unionNode,
                                ImmutableList.of(
                                        applyInDepthRenaming(iqFactory.createUnaryIQTree(leftCn, leftQuery), nonProjVarsRenamings.left),
                                        applyInDepthRenaming(iqFactory.createUnaryIQTree(rightCn, rightQuery), nonProjVarsRenamings.right)))),
                allNullable);
    }

    private TranslationResult translateTriplePattern(StatementPattern triple, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                     @Nullable Dataset dataset, boolean treatBNodeAsVariable) {
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

                ImmutableSet<Variable> projectedVariables = quadNode.getVariables().stream()
                        .filter(v -> !v.equals(graph))
                        .collect(ImmutableCollectors.toSet());

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

    private TranslationResult translateQuadPattern(StatementPattern quad, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                   @Nullable Dataset dataset, boolean treatBNodeAsVariable) {

        VariableOrGroundTerm graph = translateRDF4JVar(quad.getContextVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable);

        IntensionalDataNode dataNode = iqFactory.createIntensionalDataNode(
                atomFactory.getIntensionalQuadAtom(
                        translateRDF4JVar(quad.getSubjectVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable),
                        translateRDF4JVar(quad.getPredicateVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable),
                        translateRDF4JVar(quad.getObjectVar(), ImmutableSet.of(), true, externalBindings, treatBNodeAsVariable),
                        graph
                ));

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

    private TranslationResult translateExtension(Extension node, ImmutableMap<Variable, GroundTerm> externalBindings,
                                                 @Nullable Dataset dataset, boolean treatBNodeAsVariable) throws OntopInvalidKGQueryException, OntopUnsupportedKGQueryException {

        TranslationResult childTranslation = translate(node.getArg(), externalBindings, dataset, treatBNodeAsVariable);

        // Warning: an ExtensionElement might reference a variable appearing in a previous ExtensionElement
        // So we may need to nest them

        // Assumption: every variable used in a definition is itself defined either in the subtree of in a previous ExtensionElem
        ImmutableSet<Variable> childVars = childTranslation.iqTree.getVariables();
        ImmutableList<ImmutableSubstitution<ImmutableTerm>> mergedVarDefs =
                getVarDefs(node.getElements(), childVars, externalBindings, treatBNodeAsVariable);

        if (mergedVarDefs.isEmpty()) {
            return childTranslation;
        }

        return translateExtensionElems(mergedVarDefs, childTranslation, externalBindings);
    }

    private ImmutableList<ImmutableSubstitution<ImmutableTerm>> getVarDefs(List<ExtensionElem> list,
                                                                           ImmutableSet<Variable> childVars,
                                                                           ImmutableMap<Variable, GroundTerm> externalBindings,
                                                                           boolean treatBNodeAsVariable) {
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
                                                      TranslationResult subquery,
                                                      ImmutableMap<Variable, GroundTerm> externalBindings) {

        TranslationResult result = createTranslationResult(subquery.iqTree, subquery.nullableVariables);

        for (ImmutableSubstitution<ImmutableTerm> substitution : substitutions) {

            ImmutableSet<Variable> newNullableVariables = getNewNullableVars(substitution, result.nullableVariables);

            ConstructionNode constructionNode = iqFactory.createConstructionNode(
                    Sets.union(result.iqTree.getVariables(), substitution.getDomain()).immutableCopy(),
                    substitution);

            result = createTranslationResultFromExtendedProjection(
                    constructionNode,
                    iqFactory.createUnaryIQTree(constructionNode, result.iqTree),
                    Sets.union(result.nullableVariables, newNullableVariables).immutableCopy(),
                    externalBindings);
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

        InjectiveVar2VarSubstitution leftSubstitution = substitutionFactory.getInjectiveFreshVar2VarSubstitution(
                Sets.intersection(Sets.difference(leftKnownVars, leftProjVars), rightKnownVars).stream(),
                variableGenerator);

        InjectiveVar2VarSubstitution rightSubstitution = substitutionFactory.getInjectiveFreshVar2VarSubstitution(
                Sets.intersection(Sets.difference(rightKnownVars, rightProjVars), leftKnownVars).stream(),
                variableGenerator);

        return new NonProjVarRenamings(leftSubstitution, rightSubstitution);
    }

    private ImmutableSet<Variable> getNewNullableVars(ImmutableSubstitution<ImmutableTerm> sub, ImmutableSet<Variable> nullableVariables) {
        return sub.restrictRange(t -> t.getVariableStream().anyMatch(nullableVariables::contains))
                .getDomain();
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
                .map(m -> substitutionFactory.getSubstitutionFromStream(m.entrySet().stream(), Map.Entry::getKey, Map.Entry::getValue))
                .collect(ImmutableCollectors.toList());
    }

    private GroundTerm getTermForLiteralOrIri(Value v) {

        if (v instanceof Literal) {
            try {
                return getTermForLiteral((Literal) v);
            } catch (OntopUnsupportedKGQueryException e) {
                throw new RuntimeException(e);
            }
        }
        if (v instanceof IRI)
            return getTermForIri((IRI) v);

        throw new RuntimeException(new OntopUnsupportedKGQueryException("The value " + v + " is not supported yet!"));
    }

    private GroundTerm getTermForLiteral(Literal literal) throws OntopUnsupportedKGQueryException {
        IRI typeURI = literal.getDatatype();
        String value = literal.getLabel();
        Optional<String> lang = literal.getLanguage();

        if (lang.isPresent()) {
            return termFactory.getRDFLiteralConstant(value, lang.get());

        } else {
            RDFDatatype type;
            /*
             * default data type is xsd:string
             */
            if (typeURI == null) {
                type = typeFactory.getXsdStringDatatype();
            } else {
                type = typeFactory.getDatatype(rdfFactory.createIRI(typeURI.stringValue()));
            }

            if (type == null)
                // ROMAN (27 June 2016): type1 in open-eq-05 test would not be supported in OWL
                // the actual value is LOST here
                return termFactory.getConstantIRI(rdfFactory.createIRI(typeURI.stringValue()));
            // old strict version:
            // throw new RuntimeException("Unsupported datatype: " + typeURI);

            // BC-march-19: it seems that SPARQL does not forbid invalid lexical forms
            //     (e.g. when interpreted as an EBV, they evaluate to false)
            // However, it is unclear in which cases it would be interesting to offer a (partial) robustness to
            // such errors coming from the input query
            // check if the value is (lexically) correct for the specified datatype
            if (!XMLDatatypeUtil.isValidValue(value, typeURI))
                throw new OntopUnsupportedKGQueryException(
                        String.format("Invalid lexical forms are not accepted. Found for %s: %s", type, value));

            return termFactory.getRDFLiteralConstant(value, type);
        }
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

    private ImmutableTerm getTerm(ValueExpr expr, Set<Variable> knownVariables, ImmutableMap<Variable, GroundTerm> externalBindings,
                                  boolean treatBNodeAsVariable) {

        // PrimaryExpression ::= BrackettedExpression | BuiltInCall | iriOrFunction |
        //                          RDFLiteral | NumericLiteral | BooleanLiteral | Var
        // iriOrFunction ::= iri ArgList?

        if (expr instanceof Var)
            return translateRDF4JVar((Var) expr, knownVariables, false, externalBindings, treatBNodeAsVariable);
        if (expr instanceof ValueConstant) {
            Value v = ((ValueConstant) expr).getValue();
            return getTermForLiteralOrIri(v);
        }
        if (expr instanceof Bound) {
            // BOUND (Sec 17.4.1.1)
            // xsd:boolean  BOUND (variable var)
            Var v = ((Bound) expr).getArg();
            Variable var = termFactory.getVariable(v.getName());
            return knownVariables.contains(var) ?
                    termFactory.getImmutableFunctionalTerm(
                            functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                                    SPARQL.BOUND,
                                    1
                            ),
                            var
                    ) :
                    termFactory.getRDFLiteralConstant("false", XSD.BOOLEAN);
        }
        if (expr instanceof UnaryValueOperator) {
            // O-ary count
            if (expr instanceof Count && ((Count) expr).getArg() == null) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                                SPARQL.COUNT,
                                0
                        ));
            }

            ImmutableTerm term = getTerm(((UnaryValueOperator) expr).getArg(), knownVariables, externalBindings, treatBNodeAsVariable);

            //Unary count
            if (expr instanceof Count) {
                return termFactory.getImmutableFunctionalTerm(
                        getSPARQLAggregateFunctionSymbol(
                                SPARQL.COUNT,
                                1,
                                ((Count)expr).isDistinct()
                        ),
                        term);
            }
            if (expr instanceof Avg) {
                return termFactory.getImmutableFunctionalTerm(
                        getSPARQLAggregateFunctionSymbol(
                                SPARQL.AVG,
                                1,
                                ((Avg)expr).isDistinct()
                        ),
                        term
                );
            }
            if (expr instanceof Sum) {
                return termFactory.getImmutableFunctionalTerm(
                        getSPARQLAggregateFunctionSymbol(
                                SPARQL.SUM,
                                1,
                                ((Sum) expr).isDistinct()
                        ),
                        term
                );
            }
            if (expr instanceof Min) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                                SPARQL.MIN,
                                1
                        ),
                        term
                );
            }
            if (expr instanceof Max) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                                SPARQL.MAX,
                                1
                        ),
                        term
                );
            }
            if (expr instanceof Sample) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                                SPARQL.SAMPLE,
                                1
                        ),
                        term
                );
            }
            if (expr instanceof GroupConcat) {
                String separator = Optional.ofNullable(((GroupConcat) expr).getSeparator())
                        .map(e -> ((ValueConstant) e).getValue().stringValue())
                        // Default separator
                        .orElse(" ");

                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getSPARQLGroupConcatFunctionSymbol(
                                separator,
                                ((GroupConcat) expr).isDistinct()
                        ),
                        term
                );
            }
            if (expr instanceof Not) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(XPathFunction.NOT.getIRIString(), 1),
                        convertToXsdBooleanTerm(term));
            }
            if (expr instanceof IsNumeric) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.IS_NUMERIC, 1),
                        term);
            }
            if (expr instanceof IsLiteral) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.IS_LITERAL, 1),
                        term);
            }
            if (expr instanceof IsURI) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.IS_IRI, 1),
                        term);
            }
            if (expr instanceof Str) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.STR, 1),
                        term);
            }
            if (expr instanceof Datatype) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.DATATYPE, 1),
                        term);
            }
            if (expr instanceof IsBNode) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.IS_BLANK, 1),
                        term);
            }
            if (expr instanceof Lang) {
                ValueExpr arg = ((UnaryValueOperator) expr).getArg();
                if (arg instanceof Var)
                    return termFactory.getImmutableFunctionalTerm(
                            functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LANG, 1),
                            term);
                throw new RuntimeException(new OntopUnsupportedKGQueryException("A variable or a value is expected in " + expr));
            }
            // other subclasses
            // IRIFunction: IRI (Sec 17.4.2.8) for constructing IRIs
            // IsNumeric:  isNumeric (Sec 17.4.2.4) for checking whether the argument is a numeric value
            // AggregateOperatorBase: Avg, Min, Max, etc.
            // Like:  ??
            // IsResource: ??
            // LocalName: ??
            // Namespace: ??
            // Label: ??
        }
        if (expr instanceof BinaryValueOperator) {
            BinaryValueOperator bexpr = (BinaryValueOperator) expr;
            ImmutableTerm term1 = getTerm(bexpr.getLeftArg(), knownVariables, externalBindings, treatBNodeAsVariable);
            ImmutableTerm term2 = getTerm(bexpr.getRightArg(), knownVariables, externalBindings, treatBNodeAsVariable);

            if (expr instanceof And) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LOGICAL_AND, 2),
                        convertToXsdBooleanTerm(term1), convertToXsdBooleanTerm(term2));
            }
            if (expr instanceof Or) {
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LOGICAL_OR, 2),
                        convertToXsdBooleanTerm(term1), convertToXsdBooleanTerm(term2));
            }
            if (expr instanceof SameTerm) {
                // sameTerm (Sec 17.4.1.8)
                // Corresponds to the STRICT equality (same lexical value, same type)
                return termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.SAME_TERM, 2),
                        term1, term2);
            }
            if (expr instanceof Regex) {
                // REGEX (Sec 17.4.3.14)
                // xsd:boolean  REGEX (string literal text, simple literal pattern)
                // xsd:boolean  REGEX (string literal text, simple literal pattern, simple literal flags)
                Regex reg = (Regex) expr;
                return (reg.getFlagsArg() != null)
                        ? termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.REGEX, 3),
                        term1, term2,
                        getTerm(reg.getFlagsArg(), knownVariables, externalBindings, treatBNodeAsVariable))
                        : termFactory.getImmutableFunctionalTerm(
                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.REGEX, 2),
                        term1, term2);
            }
            if (expr instanceof Compare) {
                final SPARQLFunctionSymbol p;

                switch (((Compare) expr).getOperator()) {
                    case NE:
                        return termFactory.getImmutableFunctionalTerm(
                                functionSymbolFactory.getRequiredSPARQLFunctionSymbol(XPathFunction.NOT.getIRIString(), 1),
                                termFactory.getImmutableFunctionalTerm(
                                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.EQ, 2),
                                        term1, term2));
                    case EQ:
                        p = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.EQ, 2);
                        break;
                    case LT:
                        p = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LESS_THAN, 2);
                        break;
                    case LE:
                        return termFactory.getImmutableFunctionalTerm(
                                functionSymbolFactory.getRequiredSPARQLFunctionSymbol(XPathFunction.NOT.getIRIString(), 1),
                                termFactory.getImmutableFunctionalTerm(
                                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.GREATER_THAN, 2),
                                        term1, term2));
                    case GE:
                        return termFactory.getImmutableFunctionalTerm(
                                functionSymbolFactory.getRequiredSPARQLFunctionSymbol(XPathFunction.NOT.getIRIString(), 1),
                                termFactory.getImmutableFunctionalTerm(
                                        functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LESS_THAN, 2),
                                        term1, term2));
                    case GT:
                        p = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.GREATER_THAN, 2);
                        break;
                    default:
                        throw new RuntimeException(new OntopUnsupportedKGQueryException("Unsupported operator: " + expr));
                }
                return termFactory.getImmutableFunctionalTerm(p, term1, term2);
            }
            if (expr instanceof MathExpr) {
                SPARQLFunctionSymbol f = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                        NumericalOperations.get(((MathExpr) expr).getOperator()), 2);
                return termFactory.getImmutableFunctionalTerm(f, term1, term2);
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
                            "only supported with lang(..) function for the first argument and a constant for the second")
                    );
                }

                SPARQLFunctionSymbol langMatchesFunctionSymbol = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LANG_MATCHES, 2);

                return termFactory.getImmutableFunctionalTerm(langMatchesFunctionSymbol, term1, term2);
            }
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
            NAryValueOperator op = (NAryValueOperator) expr;

            ImmutableList<ImmutableTerm> terms = op.getArguments().stream()
                    .map(a -> getTerm(a, knownVariables, externalBindings, treatBNodeAsVariable))
                    .collect(ImmutableCollectors.toList());

            if (expr instanceof Coalesce) {
                SPARQLFunctionSymbol functionSymbol = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                        SPARQL.COALESCE, terms.size());
                return termFactory.getImmutableFunctionalTerm(functionSymbol, terms);
            }
            //Others: ListMemberOperator
        }
        if (expr instanceof BNodeGenerator) {
            Optional<ImmutableTerm> term = Optional.ofNullable(((BNodeGenerator) expr).getNodeIdExpr())
                    .map(t -> getTerm(t, knownVariables, externalBindings, treatBNodeAsVariable));

            SPARQLFunctionSymbol functionSymbol = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                    SPARQL.BNODE, term.isPresent() ? 1 : 0);
            return term
                    .map(t -> termFactory.getImmutableFunctionalTerm(functionSymbol, t))
                    .orElseGet(() -> termFactory.getImmutableFunctionalTerm(functionSymbol));
        }
        if (expr instanceof IRIFunction) {
            ImmutableTerm argument = getTerm(((IRIFunction) expr).getArg(), knownVariables, externalBindings, treatBNodeAsVariable);
            Optional<org.apache.commons.rdf.api.IRI> optionalBaseIRI = Optional.ofNullable(((IRIFunction) expr).getBaseURI())
                    .map(rdfFactory::createIRI);

            SPARQLFunctionSymbol functionSymbol = optionalBaseIRI
                    .map(functionSymbolFactory::getIRIFunctionSymbol)
                    .orElseGet(functionSymbolFactory::getIRIFunctionSymbol);

            return termFactory.getImmutableFunctionalTerm(functionSymbol, argument);
        }
        if (expr instanceof If) {
            If ifExpr = (If) expr;

            SPARQLFunctionSymbol functionSymbol = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(
                    SPARQL.IF, 3);

            return termFactory.getImmutableFunctionalTerm(
                    functionSymbol,
                    convertToXsdBooleanTerm(getTerm(ifExpr.getCondition(), knownVariables, externalBindings, treatBNodeAsVariable)),
                    getTerm(ifExpr.getResult(), knownVariables, externalBindings, treatBNodeAsVariable),
                    getTerm(ifExpr.getAlternative(), knownVariables, externalBindings, treatBNodeAsVariable));
        }
        if (expr instanceof ListMemberOperator) {
            ListMemberOperator listMemberOperator = (ListMemberOperator) expr;
            List<ValueExpr> arguments = listMemberOperator.getArguments();
            if (arguments.size() < 2)
                throw new MinorOntopInternalBugException("Was not expecting a ListMemberOperator from RDF4J with less than 2 args");

            ImmutableList<ImmutableTerm> argTerms = arguments.stream()
                    .map(a -> getTerm(a, knownVariables, externalBindings, treatBNodeAsVariable))
                    .collect(ImmutableCollectors.toList());
            ImmutableTerm firstArgument = argTerms.get(0);

            SPARQLFunctionSymbol eq = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.EQ, 2);
            SPARQLFunctionSymbol or = functionSymbolFactory.getRequiredSPARQLFunctionSymbol(SPARQL.LOGICAL_OR, 2);

            return argTerms.stream()
                    .skip(1)
                    .map(t -> termFactory.getImmutableFunctionalTerm(eq, firstArgument, t))
                    .reduce((e1, e2) -> termFactory.getImmutableFunctionalTerm(or, e1, e2))
                    .orElseThrow(() -> new MinorOntopInternalBugException("Cannot happen because of the check above"));
        }
        // other subclasses
        // SubQueryValueOperator
        throw new RuntimeException(new OntopUnsupportedKGQueryException("The expression " + expr + " is not supported yet!"));
    }

    private FunctionSymbol getSPARQLAggregateFunctionSymbol(String officialName, int arity, boolean isDistinct) {
        return isDistinct
                ? functionSymbolFactory.getRequiredSPARQLDistinctAggregateFunctionSymbol(officialName, arity)
                : functionSymbolFactory.getRequiredSPARQLFunctionSymbol(officialName, arity);
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
            return getTermForLiteralOrIri(v.getValue());
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

    /**
     * @param v URI object
     * @return term (URI template)
     */
    private GroundTerm getTermForIri(IRI v) {
        return termFactory.getConstantIRI(rdfFactory.createIRI(v.stringValue()));
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
}
