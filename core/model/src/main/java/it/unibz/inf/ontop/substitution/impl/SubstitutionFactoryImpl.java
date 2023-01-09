package it.unibz.inf.ontop.substitution.impl;

import com.google.common.collect.*;
import com.google.inject.Inject;
import it.unibz.inf.ontop.model.term.ImmutableTerm;
import it.unibz.inf.ontop.model.term.TermFactory;
import it.unibz.inf.ontop.model.term.Variable;
import it.unibz.inf.ontop.substitution.*;
import it.unibz.inf.ontop.utils.CoreUtilsFactory;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import it.unibz.inf.ontop.utils.VariableGenerator;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SubstitutionFactoryImpl implements SubstitutionFactory {

    private final TermFactory termFactory;
    private final CoreUtilsFactory coreUtilsFactory;

    @Inject
    private SubstitutionFactoryImpl(TermFactory termFactory, CoreUtilsFactory coreUtilsFactory) {
        this.termFactory = termFactory;
        this.coreUtilsFactory = coreUtilsFactory;
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> getSubstitution(ImmutableMap<Variable, T> newSubstitutionMap) {
        return new ImmutableSubstitutionImpl<>(newSubstitutionMap, termFactory, this);
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> getSubstitution(Variable k1, T v1) {
        return getSubstitution(ImmutableMap.of(k1, v1));
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> getSubstitution(Variable k1, T v1, Variable k2, T v2) {
        return getSubstitution(ImmutableMap.of(k1, v1, k2, v2));
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> getSubstitution(Variable k1, T v1, Variable k2, T v2, Variable k3, T v3) {
        return getSubstitution(ImmutableMap.of(k1, v1, k2, v2, k3, v3));
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> getSubstitution() {
        return new ImmutableSubstitutionImpl<>(ImmutableMap.of(), termFactory, this);
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> getSubstitution(ImmutableList<Variable> variables, ImmutableList<T> values) {
        if (variables.size() != values.size())
            throw new IllegalArgumentException("lists of different lengths");

        ImmutableMap<Variable, T> map = IntStream.range(0, variables.size())
                .filter(i -> !variables.get(i).equals(values.get(i)))
                .boxed()
                .collect(ImmutableCollectors.toMap(variables::get, values::get));

        return getSubstitution(map);
    }

    @Override
    public ImmutableSubstitution<ImmutableTerm> getNullSubstitution(Stream<Variable> variables) {
        return new ImmutableSubstitutionImpl<>(
                variables.collect(ImmutableCollectors.toMap(v -> v, v -> termFactory.getNullConstant())), termFactory, this);
    }

    @Override
    public Var2VarSubstitution getVar2VarSubstitution(ImmutableMap<Variable, Variable> substitutionMap) {
        return new Var2VarSubstitutionImpl(substitutionMap, termFactory, this);
    }

    @Override
    public InjectiveVar2VarSubstitution getInjectiveVar2VarSubstitution(ImmutableMap<Variable, Variable> substitutionMap) {
        return new InjectiveVar2VarSubstitutionImpl(substitutionMap, termFactory, this);
    }

    @Override
    public InjectiveVar2VarSubstitution getInjectiveVar2VarSubstitution(Stream<Variable> stream, Function<Variable, Variable> transformer) {
        ImmutableMap<Variable, Variable> map = stream.collect(ImmutableCollectors.toMap(v -> v, transformer));
        return new InjectiveVar2VarSubstitutionImpl(map, termFactory, this);
    }

    /**
     * Non-conflicting variable:
     *   - initial variable of the variable set not known by the generator
     *   - or a fresh variable generated by the generator NOT PRESENT in the variable set
     */
    @Override
    public InjectiveVar2VarSubstitution generateNotConflictingRenaming(VariableGenerator variableGenerator,
                                                                       ImmutableSet<Variable> variables) {
        ImmutableMap<Variable, Variable> newMap = variables.stream()
                .map(v -> Maps.immutableEntry(v, generateNonConflictingVariable(v, variableGenerator, variables)))
                .filter(pair -> ! pair.getKey().equals(pair.getValue()))
                .collect(ImmutableCollectors.toMap());

        return getInjectiveVar2VarSubstitution(newMap);
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> replace(ImmutableSubstitution<T> substitution, Variable variable, T newValue) {
        if (!substitution.isDefining(variable))
            throw new IllegalArgumentException("SubstitutionFactory.replace: variable " + variable + " is not defined by " + substitution);

        return new ImmutableSubstitutionImpl<>(Stream.concat(
                        Stream.of(Maps.immutableEntry(variable, newValue)),
                        substitution.getImmutableMap().entrySet().stream()
                                .filter(e -> !e.getKey().equals(variable)))
                .collect(ImmutableCollectors.toMap()),
                termFactory,
                this);
    }

    @Override
    public <T extends ImmutableTerm> ImmutableSubstitution<T> union(ImmutableSubstitution<T> substitution1, ImmutableSubstitution<T> substitution2) {

        if (substitution1.isEmpty())
            return substitution2;

        if (substitution2.isEmpty())
            return substitution1;

        ImmutableMap<Variable, T> map = Stream.of(substitution1, substitution2)
                .map(ProtoSubstitution::getImmutableMap)
                .map(ImmutableMap::entrySet)
                .flatMap(Collection::stream)
                .collect(ImmutableCollectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (val1, val2) -> {
                            if (!val1.equals(val2))
                                throw new IllegalArgumentException("Substitutions " + substitution1 + " and " + substitution2 + " do not agree on one of the variables");
                            return val1;
                        }));

        return getSubstitution(map);
    }

    private Variable generateNonConflictingVariable(Variable v, VariableGenerator variableGenerator,
                                                           ImmutableSet<Variable> variables) {

        Variable proposedVariable = variableGenerator.generateNewVariableIfConflicting(v);
        if (proposedVariable.equals(v)
                // Makes sure that a "fresh" variable does not exist in the variable set
                || (!variables.contains(proposedVariable)))
            return proposedVariable;

		/*
		 * Generates a "really fresh" variable
		 */
        ImmutableSet<Variable> knownVariables = Sets.union(
                variableGenerator.getKnownVariables(),
                variables)
                .immutableCopy();

        VariableGenerator newVariableGenerator = coreUtilsFactory.createVariableGenerator(knownVariables);
        Variable newVariable = newVariableGenerator.generateNewVariableFromVar(v);
        variableGenerator.registerAdditionalVariables(ImmutableSet.of(newVariable));
        return newVariable;
    }
}
