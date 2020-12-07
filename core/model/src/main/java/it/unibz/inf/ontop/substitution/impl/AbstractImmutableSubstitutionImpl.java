package it.unibz.inf.ontop.substitution.impl;

import java.util.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.exception.ConversionException;
import it.unibz.inf.ontop.iq.node.VariableNullability;
import it.unibz.inf.ontop.model.atom.*;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.substitution.ImmutableSubstitution;
import it.unibz.inf.ontop.substitution.SubstitutionFactory;
import it.unibz.inf.ontop.substitution.Var2VarSubstitution;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import java.util.*;
import java.util.stream.Stream;

/**
 * Common abstract class for ImmutableSubstitutionImpl and Var2VarSubstitutionImpl
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public abstract class AbstractImmutableSubstitutionImpl<T  extends ImmutableTerm>
        extends AbstractProtoSubstitution<T> implements ImmutableSubstitution<T> {

    final AtomFactory atomFactory;
    final SubstitutionFactory substitutionFactory;

    protected AbstractImmutableSubstitutionImpl(AtomFactory atomFactory, TermFactory termFactory,
                                                SubstitutionFactory substitutionFactory) {
        super(termFactory);
        this.atomFactory = atomFactory;
        this.substitutionFactory = substitutionFactory;
    }


    @Override
    public <P extends AtomPredicate> DataAtom<P> applyToDataAtom(DataAtom<P> atom) throws ConversionException {
        ImmutableList<? extends ImmutableTerm> newArguments = apply(atom.getArguments());

        if (!newArguments.stream().allMatch(t -> t instanceof VariableOrGroundTerm)) {
            throw new ConversionException("The substitution applied to a DataAtom has " +
                    " produced some non-VariableOrGroundTerm arguments " + newArguments);
        }

        return atomFactory.getDataAtom(atom.getPredicate(), (ImmutableList<VariableOrGroundTerm>) newArguments);
    }

    @Override
    public ImmutableMap<Integer, ? extends VariableOrGroundTerm> applyToArgumentMap(ImmutableMap<Integer, ? extends VariableOrGroundTerm> argumentMap)
            throws ConversionException {
        ImmutableMap<Integer, ? extends ImmutableTerm> newArgumentMap = argumentMap.entrySet().stream()
                .collect(ImmutableCollectors.toMap(
                        Map.Entry::getKey,
                        e -> apply(e.getValue())));

        if (!newArgumentMap.values().stream().allMatch(t -> t instanceof VariableOrGroundTerm)) {
            throw new ConversionException("The substitution applied to an argument map has " +
                    " produced some non-VariableOrGroundTerm arguments " + newArgumentMap);
        }
        return (ImmutableMap<Integer, ? extends VariableOrGroundTerm>) newArgumentMap;
    }


    /**
     *" "this o f"
     *
     * Equivalent to the function x {@code ->} this.apply(f.apply(x))
     *
     * Follows the formal definition of a the composition of two substitutions.
     *
     */
    @Override
    public ImmutableSubstitution<ImmutableTerm> composeWith(ImmutableSubstitution<? extends ImmutableTerm> f) {
        if (isEmpty()) {
            return (ImmutableSubstitution<ImmutableTerm>)f;
        }
        if (f.isEmpty()) {
            return (ImmutableSubstitution<ImmutableTerm>)this;
        }

        Map<Variable, ImmutableTerm> substitutionMap = new HashMap<>();

        /*
         * For all variables in the domain of f
         */

        for (Map.Entry<Variable, ? extends ImmutableTerm> gEntry :  f.getImmutableMap().entrySet()) {
            substitutionMap.put(gEntry.getKey(), apply(gEntry.getValue()));
        }

        /*
         * For the other variables (in the local domain but not in f)
         */
        for (Map.Entry<Variable, ? extends ImmutableTerm> localEntry :  getImmutableMap().entrySet()) {
            Variable localVariable = localEntry.getKey();

            if (substitutionMap.containsKey(localVariable))
                continue;

            substitutionMap.put(localVariable, localEntry.getValue());
        }

        return substitutionFactory.getSubstitution(
                substitutionMap.entrySet().stream()
                        // Clean out entries like t/t
                        .filter(entry -> !entry.getKey().equals(entry.getValue()))
                        .collect(ImmutableCollectors.toMap()));
    }

    @Override
    public ImmutableSubstitution<T> composeWith2(ImmutableSubstitution<? extends T> g) {
        return (ImmutableSubstitution<T>) composeWith(g);
    }

    private static class NotASubstitutionException extends RuntimeException {};

    protected Optional<ImmutableMap<Variable, T>> computeUnionMap(ImmutableSubstitution<T> otherSubstitution) {
        try {
            ImmutableMap<Variable, T> map = Stream.concat(
                        this.getImmutableMap().entrySet().stream(),
                        otherSubstitution.getImmutableMap().entrySet().stream())
                    .collect(ImmutableCollectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (v1, v2) -> {
                                if (!v1.equals(v2))
                                    throw new NotASubstitutionException();
                                return v1;
                            }));
            return Optional.of(map);
        }
        catch (NotASubstitutionException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ImmutableSubstitution<T>> union(ImmutableSubstitution<T> otherSubstitution) {
        if (otherSubstitution.isEmpty())
            return Optional.of(this);
        if (isEmpty())
            return Optional.of(otherSubstitution);

        return computeUnionMap(otherSubstitution)
                .map(substitutionFactory::getSubstitution);
    }

    @Override
    public Optional<ImmutableSubstitution<? extends ImmutableTerm>> unionHeterogeneous(
            ImmutableSubstitution<? extends ImmutableTerm> otherSubstitution) {
        if (otherSubstitution.isEmpty())
            return Optional.of(this);
        else if(isEmpty())
            return Optional.of(otherSubstitution);

        ImmutableMap<Variable, T> localMap = getImmutableMap();
        ImmutableSet<? extends Map.Entry<Variable, ? extends ImmutableTerm>> otherEntrySet = otherSubstitution.getImmutableMap().entrySet();

        /*
         * Checks for multiple entries of the same variable
         */
        if (otherEntrySet.stream()
                .filter(e -> localMap.containsKey(e.getKey()))
                .anyMatch(e -> !localMap.get(e.getKey()).equals(e.getValue()))) {
            return Optional.empty();
        }
        else {
            ImmutableMap<Variable, ? extends ImmutableTerm> newMap = Stream.concat(localMap.entrySet().stream(),
                    otherEntrySet.stream())
                    .distinct()
                    .collect(ImmutableCollectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue));
            return Optional.of(substitutionFactory.getSubstitution(newMap));
        }
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ImmutableSubstitution) {
            return getImmutableMap().equals(((ImmutableSubstitution) other).getImmutableMap());
        }
        return false;
    }

    protected abstract ImmutableSubstitution<T> constructNewSubstitution(ImmutableMap<Variable, T> map);

    @Override
    public ImmutableSubstitution<T> orientate(ImmutableList<Variable> priorityVariables) {
        if (priorityVariables.isEmpty() || isEmpty()) {
            return this;
        }

        ImmutableMap<Variable, T> localMap = getImmutableMap();
        ImmutableSet<Variable> domain = getDomain();

        if (localMap.values().stream()
                .flatMap(ImmutableTerm::getVariableStream)
                .anyMatch(domain::contains)) {
            throw new UnsupportedOperationException("The orientate() method requires the domain and the range to be disjoint");
        }

        ImmutableMap<Variable, Variable> renamingMap = localMap.entrySet().stream()
                // Will produce some results only if T is compatible with Variable
                .filter(e -> e.getValue() instanceof Variable)
                .filter(e -> {
                    int replacedVariableIndex = priorityVariables.indexOf(e.getKey());
                    int targetVariableIndex = priorityVariables.indexOf(e.getValue());
                    return replacedVariableIndex >= 0 && ((targetVariableIndex < 0)
                            || (replacedVariableIndex < targetVariableIndex));
                })
                .collect(ImmutableCollectors.toMap(
                        e -> (Variable) e.getValue(),
                        Map.Entry::getKey,
                        (v1, v2) -> priorityVariables.indexOf(v1) <= priorityVariables.indexOf(v2) ? v1 : v2
                ));



        /**
         * Applies the renaming
         */
        if (renamingMap.isEmpty()) {
            return this;
        }
        else {
            Var2VarSubstitution renamingSubstitution = substitutionFactory.getVar2VarSubstitution(renamingMap);

            ImmutableMap<Variable, T> orientedMap = Stream.concat(
                    localMap.entrySet().stream()
                            /**
                             * Removes entries that will be reversed
                             */
                            .filter(e -> !Optional.ofNullable(renamingMap.get(e.getValue()))
                                    .filter(newValue -> newValue.equals(e.getKey()))
                                    .isPresent()),
                    renamingMap.entrySet().stream()
                            .map(e -> (Map.Entry<Variable, T>) e))
                    .collect(ImmutableCollectors.toMap(
                            Map.Entry::getKey,
                            e -> renamingSubstitution.applyToTerm(e.getValue())
                    ));

            return constructNewSubstitution(orientedMap);
        }
    }

    @Override
    public ImmutableSubstitution<T> reduceDomainToIntersectionWith(ImmutableSet<Variable> restrictingDomain) {
        if (restrictingDomain.containsAll(getDomain()))
            return this;
        return substitutionFactory.getSubstitution(
                this.getImmutableMap().entrySet().stream()
                        .filter(e -> restrictingDomain.contains(e.getKey()))
                        .collect(ImmutableCollectors.toMap()));
    }

    @Override
    public ImmutableSubstitution<ImmutableTerm> simplifyValues(VariableNullability variableNullability) {
        return simplifyValues(Optional.of(variableNullability));
    }

    @Override
    public ImmutableSubstitution<ImmutableTerm> simplifyValues() {
        return simplifyValues(Optional.empty());
    }

    private ImmutableSubstitution<ImmutableTerm> simplifyValues(Optional<VariableNullability> variableNullability) {
        return substitutionFactory.getSubstitution(getImmutableMap().entrySet().stream()
                .collect(ImmutableCollectors.toMap(
                        Map.Entry::getKey,
                        e -> variableNullability
                                .map(n -> e.getValue().simplify(n))
                                .orElseGet(() -> e.getValue().simplify()))));
    }


    @Override
    public <S extends ImmutableTerm> ImmutableSubstitution<S> getFragment(Class<S> type) {
        ImmutableMap<Variable, S> newMap = getImmutableMap().entrySet().stream()
                .filter(e -> type.isInstance(e.getValue()))
                .collect(ImmutableCollectors.toMap(
                        Map.Entry::getKey,
                        e -> type.cast(e.getValue())));

        return substitutionFactory.getSubstitution(newMap);
    }
}
