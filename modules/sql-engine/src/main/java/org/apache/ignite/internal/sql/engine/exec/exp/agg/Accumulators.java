/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.sql.engine.exec.exp.agg;

import static org.apache.calcite.sql.type.SqlTypeName.ANY;
import static org.apache.calcite.sql.type.SqlTypeName.BIGINT;
import static org.apache.calcite.sql.type.SqlTypeName.DECIMAL;
import static org.apache.calcite.sql.type.SqlTypeName.DOUBLE;
import static org.apache.calcite.sql.type.SqlTypeName.INTEGER;
import static org.apache.calcite.sql.type.SqlTypeName.VARCHAR;
import static org.apache.ignite.internal.util.ArrayUtils.nullOrEmpty;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.ignite.internal.sql.engine.type.IgniteCustomType;
import org.apache.ignite.internal.sql.engine.type.IgniteTypeFactory;

/**
 * Accumulators.
 * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
 */
public class Accumulators {

    private final IgniteTypeFactory typeFactory;

    /**
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public Accumulators(IgniteTypeFactory typeFactory) {
        this.typeFactory = typeFactory;
    }

    /**
     * AccumulatorFactory.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public Supplier<Accumulator> accumulatorFactory(AggregateCall call) {
        if (!call.isDistinct()) {
            return accumulatorFunctionFactory(call);
        }

        Supplier<Accumulator> fac = accumulatorFunctionFactory(call);

        return () -> new DistinctAccumulator(fac);
    }

    private Supplier<Accumulator> accumulatorFunctionFactory(AggregateCall call) {
        // Update documentation in IgniteCustomType when you add an aggregate
        // that can work for any type out of the box.
        switch (call.getAggregation().getName()) {
            case "COUNT":
                return LongCount.FACTORY;
            case "AVG":
                return avgFactory(call);
            case "SUM":
                return sumFactory(call);
            case "$SUM0":
                return sumEmptyIsZeroFactory(call);
            case "MIN":
                return minMaxFactory(true, call);
            case "MAX":
                return minMaxFactory(false, call);
            case "SINGLE_VALUE":
                return SingleVal.FACTORY;
            case "ANY_VALUE":
                return AnyVal.FACTORY;
            default:
                throw new AssertionError(call.getAggregation().getName());
        }
    }

    private Supplier<Accumulator> avgFactory(AggregateCall call) {
        switch (call.type.getSqlTypeName()) {
            case BIGINT:
            case DECIMAL:
                return DecimalAvg.FACTORY;
            case DOUBLE:
            case REAL:
            case FLOAT:
            case INTEGER:
            default:
                // IgniteCustomType: AVG for a custom type should go here.
                if (call.type.getSqlTypeName() == ANY) {
                    throw unsupportedAggregateFunction(call);
                }
                return DoubleAvg.FACTORY;
        }
    }

    private Supplier<Accumulator> sumFactory(AggregateCall call) {
        switch (call.type.getSqlTypeName()) {
            case BIGINT:
            case DECIMAL:
                return () -> new Sum(new DecimalSumEmptyIsZero());

            case DOUBLE:
            case REAL:
            case FLOAT:
                return () -> new Sum(new DoubleSumEmptyIsZero());

            case TINYINT:
            case SMALLINT:
            case INTEGER:
            default:
                // IgniteCustomType: SUM for a custom type should go here.
                if (call.type.getSqlTypeName() == ANY) {
                    throw unsupportedAggregateFunction(call);
                }
                return () -> new Sum(new LongSumEmptyIsZero());
        }
    }

    private Supplier<Accumulator> sumEmptyIsZeroFactory(AggregateCall call) {
        switch (call.type.getSqlTypeName()) {
            case BIGINT:
            case DECIMAL:
                return DecimalSumEmptyIsZero.FACTORY;

            case DOUBLE:
            case REAL:
            case FLOAT:
                return DoubleSumEmptyIsZero.FACTORY;

            case TINYINT:
            case SMALLINT:
            case INTEGER:
            default:
                // IgniteCustomType: $SUM0 for a custom type should go here.
                if (call.type.getSqlTypeName() == ANY) {
                    throw unsupportedAggregateFunction(call);
                }
                return LongSumEmptyIsZero.FACTORY;
        }
    }

    private Supplier<Accumulator> minMaxFactory(boolean min, AggregateCall call) {
        var type = call.getType();
        var typeName = type.getSqlTypeName();

        switch (typeName) {
            case CHAR:
            case VARCHAR:
                return min ? VarCharMinMax.MIN_FACTORY : VarCharMinMax.MAX_FACTORY;
            default:
                if (type instanceof IgniteCustomType) {
                    return MinMaxAccumulator.newAccumulator(min, typeFactory, type);
                } else if (type.getSqlTypeName() == ANY) {
                    throw unsupportedAggregateFunction(call);
                } else {
                    return MinMaxAccumulator.newAccumulator(min, typeFactory, type);
                }
        }
    }

    /**
     * SingleVal.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    private static class SingleVal extends AnyVal {
        private boolean touched;

        public static final Supplier<Accumulator> FACTORY = SingleVal::new;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            if (touched) {
                throw new IllegalArgumentException("Subquery returned more than 1 value.");
            }

            touched = true;

            super.add(args);
        }

        /** {@inheritDoc} */
        @Override public void apply(Accumulator other) {
            if (((SingleVal) other).touched) {
                if (touched) {
                    throw new IllegalArgumentException("Subquery returned more than 1 value.");
                } else {
                    touched = true;
                }
            }

            super.apply(other);
        }
    }

    /**
     * ANY_VALUE accumulator.
     */
    private static class AnyVal implements Accumulator {
        private Object holder;

        public static final Supplier<Accumulator> FACTORY = AnyVal::new;


        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            assert args.length == 1 : args.length;

            if (holder == null) {
                holder = args[0];
            }
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            if (holder == null) {
                holder = ((AnyVal) other).holder;
            }
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return holder;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(ANY), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createSqlType(ANY);
        }
    }

    /**
     * DecimalAvg.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public static class DecimalAvg implements Accumulator {
        public static final Supplier<Accumulator> FACTORY = DecimalAvg::new;

        private BigDecimal sum = BigDecimal.ZERO;

        private BigDecimal cnt = BigDecimal.ZERO;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            BigDecimal in = (BigDecimal) args[0];

            if (in == null) {
                return;
            }

            sum = sum.add(in);
            cnt = cnt.add(BigDecimal.ONE);
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            DecimalAvg other0 = (DecimalAvg) other;

            sum = sum.add(other0.sum);
            cnt = cnt.add(other0.cnt);
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return cnt.compareTo(BigDecimal.ZERO) == 0 ? null : sum.divide(cnt, MathContext.DECIMAL64);
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(DECIMAL), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(DECIMAL), true);
        }
    }

    /**
     * DoubleAvg.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public static class DoubleAvg implements Accumulator {
        public static final Supplier<Accumulator> FACTORY = DoubleAvg::new;

        private double sum;

        private long cnt;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            Double in = (Double) args[0];

            if (in == null) {
                return;
            }

            sum += in;
            cnt++;
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            DoubleAvg other0 = (DoubleAvg) other;

            sum += other0.sum;
            cnt += other0.cnt;
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return cnt > 0 ? sum / cnt : null;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(DOUBLE), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(DOUBLE), true);
        }
    }

    private static class LongCount implements Accumulator {
        public static final Supplier<Accumulator> FACTORY = LongCount::new;

        private long cnt;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            assert nullOrEmpty(args) || args.length == 1;

            if (nullOrEmpty(args) || args[0] != null) {
                cnt++;
            }
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            LongCount other0 = (LongCount) other;
            cnt += other0.cnt;
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return cnt;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(ANY), false));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createSqlType(BIGINT);
        }
    }

    private static class Sum implements Accumulator {
        private Accumulator acc;

        private boolean empty = true;

        public Sum(Accumulator acc) {
            this.acc = acc;
        }

        /** {@inheritDoc} */
        @Override public void add(Object... args) {
            if (args[0] == null) {
                return;
            }

            empty = false;
            acc.add(args[0]);
        }

        /** {@inheritDoc} */
        @Override public void apply(Accumulator other) {
            Sum other0 = (Sum) other;

            if (other0.empty) {
                return;
            }

            empty = false;
            acc.apply(other0.acc);
        }

        /** {@inheritDoc} */
        @Override public Object end() {
            return empty ? null : acc.end();
        }

        /** {@inheritDoc} */
        @Override public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return acc.argumentTypes(typeFactory);
        }

        /** {@inheritDoc} */
        @Override public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return acc.returnType(typeFactory);
        }
    }

    private static class DoubleSumEmptyIsZero implements Accumulator {
        public static final Supplier<Accumulator> FACTORY = DoubleSumEmptyIsZero::new;

        private double sum;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            Double in = (Double) args[0];

            if (in == null) {
                return;
            }

            sum += in;
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            DoubleSumEmptyIsZero other0 = (DoubleSumEmptyIsZero) other;

            sum += other0.sum;
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return sum;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(DOUBLE), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(DOUBLE), true);
        }
    }

    // not used?
    private static class IntSumEmptyIsZero implements Accumulator {
        public static final Supplier<Accumulator> FACTORY = IntSumEmptyIsZero::new;

        private int sum;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            Integer in = (Integer) args[0];

            if (in == null) {
                return;
            }

            sum += in;
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            IntSumEmptyIsZero other0 = (IntSumEmptyIsZero) other;

            sum += other0.sum;
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return sum;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(INTEGER), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(INTEGER), true);
        }
    }

    private static class LongSumEmptyIsZero implements Accumulator {
        public static final Supplier<Accumulator> FACTORY = LongSumEmptyIsZero::new;

        private long sum;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            Long in = (Long) args[0];

            if (in == null) {
                return;
            }

            sum += in;
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            LongSumEmptyIsZero other0 = (LongSumEmptyIsZero) other;

            sum += other0.sum;
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return sum;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(BIGINT), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(BIGINT), true);
        }
    }

    private static class DecimalSumEmptyIsZero implements Accumulator {
        public static final Supplier<Accumulator> FACTORY = DecimalSumEmptyIsZero::new;

        private BigDecimal sum;

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            BigDecimal in = (BigDecimal) args[0];

            if (in == null) {
                return;
            }

            sum = sum == null ? in : sum.add(in);
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            DecimalSumEmptyIsZero other0 = (DecimalSumEmptyIsZero) other;

            sum = sum == null ? other0.sum : sum.add(other0.sum);
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return sum != null ? sum : BigDecimal.ZERO;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(DECIMAL), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(DECIMAL), true);
        }
    }

    private static final class MinMaxAccumulator implements Accumulator {

        private static final long serialVersionUID = 0;

        private final boolean min;

        private final List<RelDataType> arguments;

        private final RelDataType returnType;

        @SuppressWarnings({"rawtypes"})
        private Comparable val;

        private MinMaxAccumulator(boolean min, RelDataTypeFactory typeFactory, RelDataType relDataType) {
            var nullableType = typeFactory.createTypeWithNullability(relDataType, true);

            this.min = min;
            this.arguments = List.of(nullableType);
            this.returnType = nullableType;
        }

        static Supplier<Accumulator> newAccumulator(boolean min, RelDataTypeFactory typeFactory, RelDataType type) {
            return () -> new MinMaxAccumulator(min, typeFactory, type);
        }

        /** {@inheritDoc} **/
        @Override
        @SuppressWarnings({"rawtypes"})
        public void add(Object... args) {
            Comparable in = (Comparable) args[0];

            doApply(in);
        }

        /** {@inheritDoc} **/
        @Override
        public void apply(Accumulator other) {
            MinMaxAccumulator other0 = (MinMaxAccumulator) other;
            doApply(other0.val);
        }

        /** {@inheritDoc} **/
        @Override
        public Object end() {
            return val;
        }

        /** {@inheritDoc} **/
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return arguments;
        }

        /** {@inheritDoc} **/
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return returnType;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void doApply(Comparable in) {
            if (in == null) {
                return;
            }

            if (val == null) {
                val = in;
            } else {
                var cmp = val.compareTo(in);
                if (min) {
                    val = cmp > 0 ? in : val;
                } else {
                    val = cmp < 0 ? in : val;
                }
            }
        }
    }

    private static class VarCharMinMax implements Accumulator {
        public static final Supplier<Accumulator> MIN_FACTORY = () -> new VarCharMinMax(true);

        public static final Supplier<Accumulator> MAX_FACTORY = () -> new VarCharMinMax(false);

        private final boolean min;

        private CharSequence val;

        private boolean empty = true;

        private VarCharMinMax(boolean min) {
            this.min = min;
        }

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            CharSequence in = (CharSequence) args[0];

            if (in == null) {
                return;
            }

            val = empty ? in : min
                    ? (CharSeqComparator.INSTANCE.compare(val, in) < 0 ? val : in) :
                    (CharSeqComparator.INSTANCE.compare(val, in) < 0 ? in : val);

            empty = false;
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            VarCharMinMax other0 = (VarCharMinMax) other;

            if (other0.empty) {
                return;
            }

            val = empty ? other0.val : min
                    ? (CharSeqComparator.INSTANCE.compare(val, other0.val) < 0 ? val : other0.val) :
                    (CharSeqComparator.INSTANCE.compare(val, other0.val) < 0 ? other0.val : val);

            empty = false;
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            return empty ? null : val;
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return List.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(VARCHAR), true));
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return typeFactory.createTypeWithNullability(typeFactory.createSqlType(VARCHAR), true);
        }

        @SuppressWarnings("ComparatorNotSerializable")
        private static class CharSeqComparator implements Comparator<CharSequence> {
            private static final CharSeqComparator INSTANCE = new CharSeqComparator();

            @Override
            public int compare(CharSequence s1, CharSequence s2) {
                int len = Math.min(s1.length(), s2.length());

                // find the first difference and return
                for (int i = 0; i < len; i += 1) {
                    int cmp = Character.compare(s1.charAt(i), s2.charAt(i));
                    if (cmp != 0) {
                        return cmp;
                    }
                }

                // if there are no differences, then the shorter seq is first
                return Integer.compare(s1.length(), s2.length());
            }
        }
    }

    private static class DistinctAccumulator implements Accumulator {
        private final Accumulator acc;

        private final Set<Object> set = new HashSet<>();

        private DistinctAccumulator(Supplier<Accumulator> accSup) {
            this.acc = accSup.get();
        }

        /** {@inheritDoc} */
        @Override
        public void add(Object... args) {
            Object in = args[0];

            if (in == null) {
                return;
            }

            set.add(in);
        }

        /** {@inheritDoc} */
        @Override
        public void apply(Accumulator other) {
            DistinctAccumulator other0 = (DistinctAccumulator) other;

            set.addAll(other0.set);
        }

        /** {@inheritDoc} */
        @Override
        public Object end() {
            for (Object o : set) {
                acc.add(o);
            }

            return acc.end();
        }

        /** {@inheritDoc} */
        @Override
        public List<RelDataType> argumentTypes(IgniteTypeFactory typeFactory) {
            return acc.argumentTypes(typeFactory);
        }

        /** {@inheritDoc} */
        @Override
        public RelDataType returnType(IgniteTypeFactory typeFactory) {
            return acc.returnType(typeFactory);
        }
    }

    private static AssertionError unsupportedAggregateFunction(AggregateCall call) {
        var functionName = call.getAggregation().getName();
        var typeName = call.getType().getSqlTypeName();
        return new AssertionError(functionName + " is not supported for " + typeName);
    }
}
