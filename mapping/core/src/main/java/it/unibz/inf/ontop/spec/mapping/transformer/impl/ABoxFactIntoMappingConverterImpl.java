package it.unibz.inf.ontop.spec.mapping.transformer.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import it.unibz.inf.ontop.injection.IntermediateQueryFactory;
import it.unibz.inf.ontop.iq.IQ;
import it.unibz.inf.ontop.iq.node.ValuesNode;
import it.unibz.inf.ontop.model.atom.AtomFactory;
import it.unibz.inf.ontop.model.atom.DistinctVariableOnlyDataAtom;
import it.unibz.inf.ontop.model.atom.RDFAtomPredicate;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.model.type.*;
import it.unibz.inf.ontop.model.vocabulary.RDF;
import it.unibz.inf.ontop.spec.mapping.MappingAssertion;
import it.unibz.inf.ontop.spec.mapping.MappingAssertionIndex;
import it.unibz.inf.ontop.spec.mapping.pp.PPMappingAssertionProvenance;
import it.unibz.inf.ontop.spec.mapping.transformer.FactIntoMappingConverter;
import it.unibz.inf.ontop.spec.ontology.RDFFact;
import it.unibz.inf.ontop.substitution.Substitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.utils.CoreUtilsFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import it.unibz.inf.ontop.utils.VariableGenerator;
import org.apache.commons.rdf.api.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;


public class ABoxFactIntoMappingConverterImpl implements FactIntoMappingConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ABoxFactIntoMappingConverterImpl.class);

    private final TermFactory termFactory;
    private final IntermediateQueryFactory iqFactory;
    private final SubstitutionFactory substitutionFactory;
    private final VariableGenerator projectedVariableGenerator;
    private final DBTypeFactory dbTypeFactory;

    private final DistinctVariableOnlyDataAtom tripleAtom;
    private final DistinctVariableOnlyDataAtom quadAtom;

    private final IRIConstant RDF_TYPE;

    @Inject
    protected ABoxFactIntoMappingConverterImpl(TermFactory termFactory, IntermediateQueryFactory iqFactory,
                                               SubstitutionFactory substitutionFactory, AtomFactory atomFactory,
                                               CoreUtilsFactory coreUtilsFactory,
                                               TypeFactory typeFactory) {
        this.termFactory = termFactory;
        this.iqFactory = iqFactory;
        this.substitutionFactory = substitutionFactory;
        this.dbTypeFactory = typeFactory.getDBTypeFactory();

        RDF_TYPE = termFactory.getConstantIRI(RDF.TYPE);

        projectedVariableGenerator = coreUtilsFactory.createVariableGenerator(ImmutableSet.of());

        tripleAtom = atomFactory.getDistinctTripleAtom(
                projectedVariableGenerator.generateNewVariable(),
                projectedVariableGenerator.generateNewVariable(),
                projectedVariableGenerator.generateNewVariable());

        quadAtom = atomFactory.getDistinctQuadAtom(
                projectedVariableGenerator.generateNewVariable(),
                projectedVariableGenerator.generateNewVariable(),
                projectedVariableGenerator.generateNewVariable(),
                projectedVariableGenerator.generateNewVariable());
    }

    @Override
    public ImmutableList<MappingAssertion> convert(ImmutableSet<RDFFact> facts) {
        // Group facts by class name or property name (for properties != rdf:type), by isClass, by isQuad.
        ImmutableMap<CustomKey, ImmutableList<RDFFact>> dict = facts.stream()
                .collect(ImmutableCollectors.toMap(
                        fact -> new CustomKey(
                                        fact.getClassOrProperty(),
                                        fact.isClassAssertion(),
                                        fact.getGraph()),
                        ImmutableList::of,
                        (a, b) -> Stream.concat(a.stream(), b.stream()).collect(ImmutableCollectors.toList())));

        ImmutableList<MappingAssertion> assertions = dict.entrySet().stream()
                .map(entry -> new MappingAssertion(
                        getMappingAssertionIndex(entry.getKey()),
                        createIQ(entry.getKey(), entry.getValue()),
                        new ABoxFactProvenance(entry.getValue())))
                .collect(ImmutableCollectors.toList());

        LOGGER.debug("Transformed {} rdfFacts into {} mappingAssertions", facts.size(), assertions.size());

        return assertions;
    }

    private MappingAssertionIndex getMappingAssertionIndex(CustomKey key) {
        RDFAtomPredicate predicate = (RDFAtomPredicate) key.graphOptional.map(g -> quadAtom).orElse(tripleAtom).getPredicate();
        IRI iri = Optional.of(key.classOrProperty)
                .filter(c -> c instanceof IRIConstant)
                .map(c -> (IRIConstant)c)
                .map(IRIConstant::getIRI)
                .orElseThrow(() -> new RuntimeException("TODO: support bnode for classes as mapping assertion index"));
        return key.isClass
                ? MappingAssertionIndex.ofClass(predicate, iri)
                : MappingAssertionIndex.ofProperty(predicate, iri);
    }

    private IQ createIQ(CustomKey key, ImmutableList<RDFFact> facts) {
        return key.isClass
                ? createClassIQ(key, facts)
                : createPropertyIQ(key, facts);
    }

    private IQ createClassIQ(CustomKey key, ImmutableList<RDFFact> facts) {
        final ValuesNode valuesNode;
        final ImmutableFunctionalTerm subject;
        if (containsMultipleSubjectOrObjectTypes(facts, true)) {
            LOGGER.debug("This should only be reached if blank nodes are accepted.");
            valuesNode = createMultiTypedDBValuesNode(key, facts);

            ImmutableList<Variable> orderedVariables = valuesNode.getOrderedVariables();
            subject = termFactory.getRDFFunctionalTerm(orderedVariables.get(0), orderedVariables.get(1));
        }
        else {
            // We've already excluded multiple types
            valuesNode = createSingleTypeDBValuesNode(key, facts);
            subject = getTerm(facts.get(0).getSubject(), valuesNode.getOrderedVariables().get(0));
        }

        return createConstructionIQ(subject, RDF_TYPE, key.classOrProperty, key.graphOptional, valuesNode);
    }

    private IQ createPropertyIQ(CustomKey key, ImmutableList<RDFFact> facts) {
        final ValuesNode valuesNode;
        final ImmutableFunctionalTerm subject;
        final ImmutableFunctionalTerm object;
        if (containsMultipleSubjectOrObjectTypes(facts, true) || containsMultipleSubjectOrObjectTypes(facts, false)) {
            valuesNode = createMultiTypedDBValuesNode(key, facts);

            ImmutableList<Variable> orderedVariables = valuesNode.getOrderedVariables();
            subject = termFactory.getRDFFunctionalTerm(orderedVariables.get(0), orderedVariables.get(2));
            object = termFactory.getRDFFunctionalTerm(orderedVariables.get(1), orderedVariables.get(3));
        }
        else {
            // We've already excluded multiple types
            valuesNode = createSingleTypeDBValuesNode(key, facts);
            subject = getTerm(facts.get(0).getSubject(), valuesNode.getOrderedVariables().get(0));
            object = getTerm(facts.get(0).getObject(), valuesNode.getOrderedVariables().get(1));
        }

        return createConstructionIQ(subject, key.classOrProperty, object, key.graphOptional, valuesNode);
    }

    private ImmutableFunctionalTerm getTerm(RDFConstant constant, Variable variable) {
        RDFTermType subjectType = constant.getType();
        DBTermType subjectDBType = subjectType.getClosestDBType(dbTypeFactory);
        RDFTermTypeConstant subjectRDFTypeConstant = termFactory.getRDFTermTypeConstant(subjectType);
        return termFactory.getRDFFunctionalTerm(
                termFactory.getConversion2RDFLexical(subjectDBType, variable, subjectRDFTypeConstant.getRDFTermType()),
                subjectRDFTypeConstant);
    }


    /**
     * Returns true if the given list of RDFFacts contains multiple types of subject.
     *
     * @return a boolean
     */
    private boolean containsMultipleSubjectOrObjectTypes(ImmutableList<RDFFact> facts, boolean isSubject) {
        RDFFact firstFact = facts.get(0);
        RDFConstant firstTerm = isSubject ? firstFact.getSubject() : firstFact.getObject();
        RDFTermType firstRDFTermType = firstTerm.getType();

        return facts.stream()
                .map(f -> isSubject ? f.getSubject() : f.getObject())
                .anyMatch(c -> !(c.getType().equals(firstRDFTermType)));
    }


    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private IQ createConstructionIQ(ImmutableTerm subject, ImmutableTerm predicate, ImmutableTerm object, Optional<ObjectConstant> graph, ValuesNode valuesNode) {
        DistinctVariableOnlyDataAtom atom = graph.map(g -> quadAtom).orElse(tripleAtom);
        Substitution<?> substitution = substitutionFactory.getSubstitution(atom.getArguments(),
                graph.map(g -> ImmutableList.of(subject, predicate, object, g))
                        .orElseGet(() -> ImmutableList.of(subject, predicate, object)));

        return iqFactory.createIQ(atom,
                iqFactory.createUnaryIQTree(
                        iqFactory.createConstructionNode(substitution.getDomain(), substitution), valuesNode));
    }

    private ValuesNode createSingleTypeDBValuesNode(CustomKey key, ImmutableList<RDFFact> facts) {
        // Two cases, class assertion or not
        return key.isClass

                ? iqFactory.createValuesNode(
                ImmutableList.of(
                        projectedVariableGenerator.generateNewVariable()),
                facts.stream()
                        .map(rdfFact -> ImmutableList.of(
                                extractNaturalDBValue(rdfFact.getSubject())))
                        .collect(ImmutableCollectors.toList()))

                : iqFactory.createValuesNode(
                ImmutableList.of(
                        projectedVariableGenerator.generateNewVariable(),
                        projectedVariableGenerator.generateNewVariable()),
                facts.stream()
                        .map(rdfFact -> ImmutableList.of(
                                extractNaturalDBValue(rdfFact.getSubject()),
                                extractNaturalDBValue(rdfFact.getObject())))
                        .collect(ImmutableCollectors.toList()));
    }

    private ValuesNode createMultiTypedDBValuesNode(CustomKey key, ImmutableList<RDFFact> facts) {
        // Two cases, class assertion or not
        return key.isClass
                ? iqFactory.createValuesNode(
                ImmutableList.of(
                        projectedVariableGenerator.generateNewVariable(),
                        projectedVariableGenerator.generateNewVariable()),
                facts.stream()
                        .map(RDFFact::getSubject)
                        .map(subject -> ImmutableList.<Constant>of(
                                termFactory.getDBStringConstant(subject.getValue()),
                                termFactory.getRDFTermTypeConstant(subject.getType())))
                        .collect(ImmutableCollectors.toList()))

                : iqFactory.createValuesNode(
                ImmutableList.of(
                        projectedVariableGenerator.generateNewVariable(),
                        projectedVariableGenerator.generateNewVariable(),
                        projectedVariableGenerator.generateNewVariable(),
                        projectedVariableGenerator.generateNewVariable()),
                facts.stream()
                        .map(rdfFact -> ImmutableList.<Constant>of(
                                termFactory.getDBStringConstant(rdfFact.getSubject().getValue()),
                                termFactory.getDBStringConstant(rdfFact.getObject().getValue()),
                                termFactory.getRDFTermTypeConstant(rdfFact.getSubject().getType()),
                                termFactory.getRDFTermTypeConstant(rdfFact.getObject().getType())))
                        .collect(ImmutableCollectors.toList()));
    }

    private Constant extractNaturalDBValue(RDFConstant rdfConstant) {
        ImmutableFunctionalTerm functionalTerm = termFactory.getConversionFromRDFLexical2DB(
                termFactory.getDBStringConstant(rdfConstant.getValue()),
                rdfConstant.getType());
        return (Constant) functionalTerm.simplify();
    }

    private static class ABoxFactProvenance implements PPMappingAssertionProvenance {
        private final String provenance;

        private ABoxFactProvenance(ImmutableList<RDFFact> rdfFacts) {
            provenance = rdfFacts.toString();
        }

        @Override
        public String getProvenanceInfo() {
            return provenance;
        }
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static class CustomKey {
        public final ObjectConstant classOrProperty;
        public final boolean isClass;
        public final Optional<ObjectConstant> graphOptional;

        private CustomKey(ObjectConstant classOrProperty, boolean isClass, Optional<ObjectConstant> graphOptional) {
            this.classOrProperty = classOrProperty;
            this.isClass = isClass;
            this.graphOptional = graphOptional;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CustomKey customKey = (CustomKey) o;
            return isClass == customKey.isClass &&
                    Objects.equals(graphOptional, customKey.graphOptional) &&
                    Objects.equals(classOrProperty, customKey.classOrProperty);
        }

        @Override
        public int hashCode() {
            return Objects.hash(classOrProperty, isClass, graphOptional);
        }
    }
}
