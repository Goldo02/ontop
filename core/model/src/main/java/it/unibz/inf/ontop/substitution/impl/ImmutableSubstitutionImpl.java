package it.unibz.inf.ontop.substitution.impl;

import com.google.common.base.Joiner;
import com.google.common.collect.*;
import it.unibz.inf.ontop.model.term.*;
import it.unibz.inf.ontop.substitution.ImmutableSubstitution;
import it.unibz.inf.ontop.utils.ImmutableCollectors;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Wrapper above an {@code ImmutableMap<Variable, ImmutableTerm>} map.
 */

public class ImmutableSubstitutionImpl<T extends ImmutableTerm> implements ImmutableSubstitution<T> {

    protected final TermFactory termFactory;
    protected final ImmutableMap<Variable, T> map;

    public ImmutableSubstitutionImpl(ImmutableMap<Variable, ? extends T> substitutionMap, TermFactory termFactory) {
        this.termFactory = termFactory;
        this.map = (ImmutableMap<Variable, T>) substitutionMap;

        if (map.entrySet().stream().anyMatch(e -> e.getKey().equals(e.getValue())))
            throw new IllegalArgumentException("Please do not insert entries like t/t in your substitution " +
                    "(for efficiency reasons)\n. Substitution: " + map);
    }


    @Override
    public TermFactory getTermFactory() {
        return termFactory;
    }

    @Override
    public ImmutableSet<Map.Entry<Variable, T>> entrySet() {
        return map.entrySet();
    }

    @Override
    public  boolean isDefining(Variable variable) {
        return map.containsKey(variable);
    }

    @Override
    public  ImmutableSet<Variable> getDomain() {
        return map.keySet();
    }

    @Override
    public boolean rangeAllMatch(Predicate<T> predicate) {
        return map.values().stream().allMatch(predicate);
    }

    @Override
    public boolean rangeAnyMatch(Predicate<T> predicate) {
        return map.values().stream().anyMatch(predicate);
    }

    @Override
    public ImmutableSet<T> getRangeSet() {
        return ImmutableSet.copyOf(map.values());
    }

    @Override
    public ImmutableSet<Variable> getRangeVariables() {
        return map.values().stream()
                .flatMap(ImmutableTerm::getVariableStream)
                .collect(ImmutableCollectors.toSet());
    }

    @Override
    public  T get(Variable variable) {
        return map.get(variable);
    }

    @Override
    public  boolean isEmpty() {
        return map.isEmpty();
    }


    @Override
    public ImmutableTerm applyToVariable(Variable variable) {
        return Optional.<ImmutableTerm>ofNullable(get(variable)).orElse(variable);
    }


    @Override
    public boolean isInjective() {
        return InjectiveVar2VarSubstitutionImpl.isInjective(map);
    }

    @Override
    public ImmutableMap<T, Collection<Variable>> inverseMap() {
       return map.entrySet().stream()
               .collect(ImmutableCollectors.toMultimap(Map.Entry::getValue, Map.Entry::getKey))
               .asMap();
    }

    @Override
    public ImmutableSubstitution<T> restrictDomainTo(Set<Variable> set) {
        return new ImmutableSubstitutionImpl<>(map.entrySet().stream()
                .filter(e -> set.contains(e.getKey()))
                .collect(ImmutableCollectors.toMap()), termFactory);
    }

    @Override
    public ImmutableSubstitution<T> removeFromDomain(Set<Variable> set) {
        return new ImmutableSubstitutionImpl<>(map.entrySet().stream()
                .filter(e -> !set.contains(e.getKey()))
                .collect(ImmutableCollectors.toMap()), termFactory);
    }

    @Override
    public <S extends ImmutableTerm> ImmutableSubstitution<S> restrictRangeTo(Class<? extends S> type) {
        return new ImmutableSubstitutionImpl<>(map.entrySet().stream()
                .filter(e -> type.isInstance(e.getValue()))
                .collect(ImmutableCollectors.toMap(Map.Entry::getKey, e -> type.cast(e.getValue()))), termFactory);
    }

    @Override
    public ImmutableSubstitution<T> restrictRange(Predicate<T> predicate) {
        return new ImmutableSubstitutionImpl<>(map.entrySet().stream()
                .filter(e -> predicate.test(e.getValue()))
                .collect(ImmutableCollectors.toMap(Map.Entry::getKey, Map.Entry::getValue)), termFactory);
    }

    @Override
    public <S extends ImmutableTerm> ImmutableSubstitution<S> castTo(Class<S> type) {
        if (map.entrySet().stream()
                .anyMatch(e -> !type.isInstance(e.getValue())))
            throw new ClassCastException();

        return (ImmutableSubstitution<S>) this;
    }

    @Override
    public <S extends ImmutableTerm> ImmutableSubstitution<S> transform(Function<T, S> function) {
        return new ImmutableSubstitutionImpl<>(map.entrySet().stream()
                .collect(ImmutableCollectors.toMap(Map.Entry::getKey, e -> function.apply(e.getValue()))), termFactory);
    }

    @Override
    public Builder<T> builder() {
        return new BuilderImpl<>(map.entrySet().stream(), termFactory);
    }

    protected static class BuilderImpl<B extends ImmutableTerm> implements Builder<B> {
        protected final Stream<Map.Entry<Variable, B>> stream;
        protected final TermFactory termFactory;

        BuilderImpl(Stream<Map.Entry<Variable, B>> stream, TermFactory termFactory) {
            this.stream = stream;
            this.termFactory = termFactory;
        }
        @Override
        public ImmutableSubstitution<B> build() {
            return new ImmutableSubstitutionImpl<>(stream.collect(ImmutableCollectors.toMap()), termFactory);
        }

        @Override
        public <S extends ImmutableTerm> ImmutableSubstitution<S> build(Class<S> type) {
            ImmutableMap<Variable, B> map = stream.collect(ImmutableCollectors.toMap());
            if (map.entrySet().stream()
                    .anyMatch(e -> !type.isInstance(e.getValue())))
                throw new ClassCastException();

            return new ImmutableSubstitutionImpl<>((ImmutableMap<Variable, S>)map, termFactory);
        }

        protected <S extends ImmutableTerm> Builder<S> create(Stream<Map.Entry<Variable, S>> stream) {
            return new BuilderImpl<>(stream, termFactory);
        }

        private Builder<B> restrictDomain(Predicate<Variable> predicate) {
            return create(stream.filter(e -> predicate.test(e.getKey())));
        }

        @Override
        public Builder<B> restrictDomainTo(Set<Variable> set) {
            return restrictDomain(set::contains);
        }

        @Override
        public Builder<B> removeFromDomain(Set<Variable> set) {
            return restrictDomain(v -> !set.contains(v));
        }

        @Override
        public Builder<B> restrict(BiPredicate<Variable, B> predicate) {
            return create(stream.filter(e -> predicate.test(e.getKey(), e.getValue())));
        }

        @Override
        public Builder<B> restrictRange(Predicate<B> predicate) {
            return create(stream.filter(e -> predicate.test(e.getValue())));
        }

        @Override
        public <S extends ImmutableTerm> Builder<S> restrictRangeTo(Class<? extends S> type) {
            return create(stream
                    .filter(e -> type.isInstance(e.getValue()))
                    .map(e -> Maps.immutableEntry(e.getKey(), type.cast(e.getValue()))));
        }

        @Override
        public <S extends ImmutableTerm> Builder<S> transform(BiFunction<Variable, B, S> function) {
            return create(stream.map(e -> Maps.immutableEntry(e.getKey(), function.apply(e.getKey(), e.getValue()))));
        }

        @Override
        public <S extends ImmutableTerm> Builder<S> transform(Function<B, S> function) {
            return create(stream.map(e -> Maps.immutableEntry(e.getKey(), function.apply(e.getValue()))));
        }

        @Override
        public <U> Builder<B> transformOrRetain(Function<Variable, U> lookup, BiFunction<B, U, B> function) {
            return create(stream.map(e -> Maps.immutableEntry(
                    e.getKey(),
                    Optional.ofNullable(lookup.apply(e.getKey()))
                            .map(u -> function.apply(e.getValue(), u))
                            .orElse(e.getValue()))));
        }

        @Override
        public <U, S extends ImmutableTerm> Builder<S> transformOrRemove(Function<Variable, U> lookup, Function<U, S> function) {
            return create(stream
                    .map(e -> Optional.ofNullable(lookup.apply(e.getKey()))
                            .map(function)
                            .map(r -> Maps.immutableEntry(e.getKey(), r)))
                    .filter(Optional::isPresent)
                    .map(Optional::get));
        }

        @Override
        public <U> Builder<B> flatTransform(Function<Variable, U> lookup, Function<U, ImmutableSubstitution<B>> function) {
            return create(stream
                    .flatMap(e -> Optional.ofNullable(lookup.apply(e.getKey()))
                            .map(function)
                            .map(s -> s.entrySet().stream())
                            .orElseGet(() -> Stream.of(e))));
        }

        @Override
        public Stream<ImmutableExpression> toStrictEqualities() {
            return toStream(termFactory::getStrictEquality);
        }

        @Override
        public <S> Stream<S> toStream(BiFunction<Variable, B, S> transformer) {
            return stream.map(e -> transformer.apply(e.getKey(), e.getValue()));
        }

        @Override
        public <S> ImmutableMap<Variable, S> toMap(BiFunction<Variable, B, S> transformer) {
            return stream.collect(ImmutableCollectors.toMap(Map.Entry::getKey, e -> transformer.apply(e.getKey(), e.getValue())));
        }

        @Override
        public <S> ImmutableMap<Variable, S> toMapWithoutOptional(BiFunction<Variable, B, Optional<S>> transformer) {
            return stream
                    .map(e -> transformer.apply(e.getKey(), e.getValue())
                            .map(v -> Maps.immutableEntry(e.getKey(), v)))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(ImmutableCollectors.toMap());
        }
    }


    /**
     * NB: to be used only by the factory
     *
     * @return
     */
    ImmutableMap<Variable, T> getImmutableMap() {
        return map;
    }

    @Override
    public String toString() {
        return Joiner.on(", ").withKeyValueSeparator("/").join(map);
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof ImmutableSubstitutionImpl) {
            return map.equals(((ImmutableSubstitutionImpl<?>) other).map);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }
}
