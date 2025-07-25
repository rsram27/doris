// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include <cstdint>
#include <cstring>

// IWYU pragma: no_include <bits/std_abs.h>
#include <dlfcn.h>

#include <cmath>
#include <string>
#include <type_traits>

#include "common/status.h"
#include "util/debug/leak_annotations.h"
#include "vec/columns/column.h"
#include "vec/columns/column_string.h"
#include "vec/columns/column_vector.h"
#include "vec/core/types.h"
#include "vec/data_types/data_type_string.h"
#include "vec/data_types/number_traits.h"
#include "vec/functions/function_binary_arithmetic.h"
#include "vec/functions/function_const.h"
#include "vec/functions/function_math_log.h"
#include "vec/functions/function_math_unary.h"
#include "vec/functions/function_math_unary_alway_nullable.h"
#include "vec/functions/function_totype.h"
#include "vec/functions/function_unary_arithmetic.h"
#include "vec/functions/simple_function_factory.h"
#include "vec/utils/stringop_substring.h"

namespace doris::vectorized {

struct LnImpl;
struct Log10Impl;
struct Log2Impl;

struct AcosName {
    static constexpr auto name = "acos";
    // https://dev.mysql.com/doc/refman/8.4/en/mathematical-functions.html#function_acos
    static constexpr bool is_invalid_input(Float64 x) { return x < -1 || x > 1; }
};
using FunctionAcos =
        FunctionMathUnaryAlwayNullable<UnaryFunctionPlainAlwayNullable<AcosName, std::acos>>;

struct AcoshName {
    static constexpr auto name = "acosh";
    static constexpr bool is_invalid_input(Float64 x) { return x < 1; }
};
using FunctionAcosh =
        FunctionMathUnaryAlwayNullable<UnaryFunctionPlainAlwayNullable<AcoshName, std::acosh>>;

struct AsinName {
    static constexpr auto name = "asin";
    // https://dev.mysql.com/doc/refman/8.4/en/mathematical-functions.html#function_asin
    static constexpr bool is_invalid_input(Float64 x) { return x < -1 || x > 1; }
};
using FunctionAsin =
        FunctionMathUnaryAlwayNullable<UnaryFunctionPlainAlwayNullable<AsinName, std::asin>>;

struct AsinhName {
    static constexpr auto name = "asinh";
};
using FunctionAsinh = FunctionMathUnary<UnaryFunctionPlain<AsinhName, std::asinh>>;

struct AtanName {
    static constexpr auto name = "atan";
};
using FunctionAtan = FunctionMathUnary<UnaryFunctionPlain<AtanName, std::atan>>;

struct AtanhName {
    static constexpr auto name = "atanh";
    static constexpr bool is_invalid_input(Float64 x) { return x <= -1 || x >= 1; }
};
using FunctionAtanh =
        FunctionMathUnaryAlwayNullable<UnaryFunctionPlainAlwayNullable<AtanhName, std::atanh>>;

template <PrimitiveType AType, PrimitiveType BType>
struct Atan2Impl {
    using A = typename PrimitiveTypeTraits<AType>::ColumnItemType;
    using B = typename PrimitiveTypeTraits<BType>::ColumnItemType;
    static constexpr PrimitiveType ResultType = TYPE_DOUBLE;
    static const constexpr bool allow_decimal = false;

    template <PrimitiveType type>
    static inline double apply(A a, B b) {
        return std::atan2((double)a, (double)b);
    }
};
struct Atan2Name {
    static constexpr auto name = "atan2";
};
using FunctionAtan2 = FunctionBinaryArithmetic<Atan2Impl, Atan2Name, false>;

struct CosName {
    static constexpr auto name = "cos";
};
using FunctionCos = FunctionMathUnary<UnaryFunctionPlain<CosName, std::cos>>;

struct CoshName {
    static constexpr auto name = "cosh";
};
using FunctionCosh = FunctionMathUnary<UnaryFunctionPlain<CoshName, std::cosh>>;

struct EImpl {
    static constexpr auto name = "e";
    static constexpr double value = 2.7182818284590452353602874713526624977572470;
};
using FunctionE = FunctionMathConstFloat64<EImpl>;

struct PiImpl {
    static constexpr auto name = "pi";
    static constexpr double value = 3.1415926535897932384626433832795028841971693;
};
using FunctionPi = FunctionMathConstFloat64<PiImpl>;

struct ExpName {
    static constexpr auto name = "exp";
};
using FunctionExp = FunctionMathUnary<UnaryFunctionPlain<ExpName, std::exp>>;

struct LogName {
    static constexpr auto name = "log";
};

template <PrimitiveType AType, PrimitiveType BType>
struct LogImpl {
    using A = typename PrimitiveTypeTraits<AType>::CppNativeType;
    using B = typename PrimitiveTypeTraits<BType>::CppNativeType;
    static constexpr PrimitiveType ResultType = TYPE_DOUBLE;
    using Traits = NumberTraits::BinaryOperatorTraits<AType, BType>;

    static const constexpr bool allow_decimal = false;
    static constexpr double EPSILON = 1e-9;

    template <PrimitiveType Result = ResultType>
    static void apply(const typename Traits::ArrayA& a, B b,
                      typename PrimitiveTypeTraits<Result>::ColumnType::Container& c,
                      typename Traits::ArrayNull& null_map) {
        size_t size = c.size();
        UInt8 is_null = b <= 0;
        memset(null_map.data(), is_null, size);

        if (!is_null) {
            for (size_t i = 0; i < size; i++) {
                if (a[i] <= 0 || std::fabs(a[i] - 1.0) < EPSILON) {
                    null_map[i] = 1;
                } else {
                    c[i] = static_cast<Float64>(std::log(static_cast<Float64>(b)) /
                                                std::log(static_cast<Float64>(a[i])));
                }
            }
        }
    }

    template <PrimitiveType Result>
    static inline typename PrimitiveTypeTraits<Result>::CppNativeType apply(A a, B b,
                                                                            UInt8& is_null) {
        is_null = a <= 0 || b <= 0 || std::fabs(a - 1.0) < EPSILON;
        return static_cast<Float64>(std::log(static_cast<Float64>(b)) /
                                    std::log(static_cast<Float64>(a)));
    }
};
using FunctionLog = FunctionBinaryArithmetic<LogImpl, LogName, true>;

template <typename A>
struct SignImpl {
    static constexpr PrimitiveType ResultType = TYPE_TINYINT;
    static inline UInt8 apply(A a) {
        if constexpr (IsDecimalNumber<A> || std::is_floating_point_v<A>)
            return static_cast<UInt8>(a < A(0) ? -1 : a == A(0) ? 0 : 1);
        else if constexpr (std::is_signed_v<A>)
            return static_cast<UInt8>(a < 0 ? -1 : a == 0 ? 0 : 1);
        else if constexpr (std::is_unsigned_v<A>)
            return static_cast<UInt8>(a == 0 ? 0 : 1);
    }
};

struct NameSign {
    static constexpr auto name = "sign";
};
using FunctionSign = FunctionUnaryArithmetic<SignImpl, NameSign>;

template <typename A>
struct AbsImpl {
    static constexpr PrimitiveType ResultType = NumberTraits::ResultOfAbs<A>::Type;

    static inline typename PrimitiveTypeTraits<ResultType>::ColumnItemType apply(A a) {
        if constexpr (IsDecimalNumber<A>)
            return a < A(0) ? A(-a) : a;
        else if constexpr (std::is_integral_v<A> && std::is_signed_v<A>)
            return a < A(0) ? static_cast<typename PrimitiveTypeTraits<ResultType>::ColumnItemType>(
                                      ~a) +
                                      1
                            : a;
        else if constexpr (std::is_integral_v<A> && std::is_unsigned_v<A>)
            return static_cast<typename PrimitiveTypeTraits<ResultType>::ColumnItemType>(a);
        else if constexpr (std::is_floating_point_v<A>)
            return static_cast<typename PrimitiveTypeTraits<ResultType>::ColumnItemType>(
                    std::abs(a));
    }
};

struct NameAbs {
    static constexpr auto name = "abs";
};

template <typename A>
struct ResultOfUnaryFunc;

template <>
struct ResultOfUnaryFunc<UInt8> {
    static constexpr PrimitiveType ResultType = TYPE_BOOLEAN;
};

template <>
struct ResultOfUnaryFunc<Int8> {
    static constexpr PrimitiveType ResultType = TYPE_TINYINT;
};

template <>
struct ResultOfUnaryFunc<Int16> {
    static constexpr PrimitiveType ResultType = TYPE_SMALLINT;
};

template <>
struct ResultOfUnaryFunc<Int32> {
    static constexpr PrimitiveType ResultType = TYPE_INT;
};

template <>
struct ResultOfUnaryFunc<Int64> {
    static constexpr PrimitiveType ResultType = TYPE_BIGINT;
};

template <>
struct ResultOfUnaryFunc<Int128> {
    static constexpr PrimitiveType ResultType = TYPE_LARGEINT;
};

template <>
struct ResultOfUnaryFunc<Decimal32> {
    static constexpr PrimitiveType ResultType = TYPE_DECIMAL32;
};

template <>
struct ResultOfUnaryFunc<Decimal64> {
    static constexpr PrimitiveType ResultType = TYPE_DECIMAL64;
};

template <>
struct ResultOfUnaryFunc<Decimal128V3> {
    static constexpr PrimitiveType ResultType = TYPE_DECIMAL128I;
};

template <>
struct ResultOfUnaryFunc<Decimal128V2> {
    static constexpr PrimitiveType ResultType = TYPE_DECIMALV2;
};

template <>
struct ResultOfUnaryFunc<Decimal256> {
    static constexpr PrimitiveType ResultType = TYPE_DECIMAL256;
};

template <>
struct ResultOfUnaryFunc<float> {
    static constexpr PrimitiveType ResultType = TYPE_FLOAT;
};

template <>
struct ResultOfUnaryFunc<double> {
    static constexpr PrimitiveType ResultType = TYPE_DOUBLE;
};

using FunctionAbs = FunctionUnaryArithmetic<AbsImpl, NameAbs>;

template <typename A>
struct NegativeImpl {
    static constexpr PrimitiveType ResultType = ResultOfUnaryFunc<A>::ResultType;

    static inline typename PrimitiveTypeTraits<ResultType>::ColumnItemType apply(A a) { return -a; }
};

struct NameNegative {
    static constexpr auto name = "negative";
};

using FunctionNegative = FunctionUnaryArithmetic<NegativeImpl, NameNegative>;

template <typename A>
struct PositiveImpl {
    static constexpr PrimitiveType ResultType = ResultOfUnaryFunc<A>::ResultType;

    static inline typename PrimitiveTypeTraits<ResultType>::ColumnItemType apply(A a) {
        return static_cast<typename PrimitiveTypeTraits<ResultType>::ColumnItemType>(a);
    }
};

struct NamePositive {
    static constexpr auto name = "positive";
};

using FunctionPositive = FunctionUnaryArithmetic<PositiveImpl, NamePositive>;

struct UnaryFunctionPlainSin {
    using Type = DataTypeFloat64;
    static constexpr auto name = "sin";
    using FuncType = double (*)(double);

    static FuncType get_sin_func() {
#ifndef BE_TEST
        void* handle = dlopen("libm.so.6", RTLD_LAZY);
        if (handle) {
            if (auto sin_func = (double (*)(double))dlsym(handle, "sin"); sin_func) {
                return sin_func;
            }
            dlclose(handle);
        }
#endif
        return std::sin;
    }

    static void execute(const double* src, double* dst) {
        static auto sin_func = get_sin_func();
        *dst = sin_func(*src);
    }
};

using FunctionSin = FunctionMathUnary<UnaryFunctionPlainSin>;

struct SinhName {
    static constexpr auto name = "sinh";
};
using FunctionSinh = FunctionMathUnary<UnaryFunctionPlain<SinhName, std::sinh>>;

struct SqrtName {
    static constexpr auto name = "sqrt";
    // https://dev.mysql.com/doc/refman/8.4/en/mathematical-functions.html#function_sqrt
    static constexpr bool is_invalid_input(Float64 x) { return x < 0; }
};
using FunctionSqrt =
        FunctionMathUnaryAlwayNullable<UnaryFunctionPlainAlwayNullable<SqrtName, std::sqrt>>;

struct CbrtName {
    static constexpr auto name = "cbrt";
};
using FunctionCbrt = FunctionMathUnary<UnaryFunctionPlain<CbrtName, std::cbrt>>;

struct TanName {
    static constexpr auto name = "tan";
};
using FunctionTan = FunctionMathUnary<UnaryFunctionPlain<TanName, std::tan>>;

struct TanhName {
    static constexpr auto name = "tanh";
};
using FunctionTanh = FunctionMathUnary<UnaryFunctionPlain<TanhName, std::tanh>>;

struct CotName {
    static constexpr auto name = "cot";
};
double cot(double x) {
    return 1.0 / std::tan(x);
}
using FunctionCot = FunctionMathUnary<UnaryFunctionPlain<CotName, cot>>;

struct SecName {
    static constexpr auto name = "sec";
};
double sec(double x) {
    return 1.0 / std::cos(x);
}
using FunctionSec = FunctionMathUnary<UnaryFunctionPlain<SecName, sec>>;

struct CosecName {
    static constexpr auto name = "cosec";
};
double cosec(double x) {
    return 1.0 / std::sin(x);
}
using FunctionCosec = FunctionMathUnary<UnaryFunctionPlain<CosecName, cosec>>;

template <typename A>
struct RadiansImpl {
    static constexpr PrimitiveType ResultType = ResultOfUnaryFunc<A>::ResultType;

    static inline typename PrimitiveTypeTraits<ResultType>::ColumnItemType apply(A a) {
        return static_cast<typename PrimitiveTypeTraits<ResultType>::ColumnItemType>(
                a * PiImpl::value / 180.0);
    }
};

struct NameRadians {
    static constexpr auto name = "radians";
};

using FunctionRadians = FunctionUnaryArithmetic<RadiansImpl, NameRadians>;

template <typename A>
struct DegreesImpl {
    static constexpr PrimitiveType ResultType = ResultOfUnaryFunc<A>::ResultType;

    static inline typename PrimitiveTypeTraits<ResultType>::ColumnItemType apply(A a) {
        return static_cast<typename PrimitiveTypeTraits<ResultType>::ColumnItemType>(a * 180.0 /
                                                                                     PiImpl::value);
    }
};

struct NameDegrees {
    static constexpr auto name = "degrees";
};

using FunctionDegrees = FunctionUnaryArithmetic<DegreesImpl, NameDegrees>;

struct NameBin {
    static constexpr auto name = "bin";
};
struct BinImpl {
    using ReturnType = DataTypeString;
    static constexpr auto PrimitiveTypeImpl = PrimitiveType::TYPE_BIGINT;
    using Type = Int64;
    using ReturnColumnType = ColumnString;

    static std::string bin_impl(Int64 value) {
        uint64_t n = static_cast<uint64_t>(value);
        const size_t max_bits = sizeof(uint64_t) * 8;
        char result[max_bits];
        uint32_t index = max_bits;
        do {
            result[--index] = '0' + (n & 1);
        } while (n >>= 1);
        return std::string(result + index, max_bits - index);
    }

    static Status vector(const ColumnInt64::Container& data, ColumnString::Chars& res_data,
                         ColumnString::Offsets& res_offsets) {
        res_offsets.resize(data.size());
        size_t input_size = res_offsets.size();

        for (size_t i = 0; i < input_size; ++i) {
            StringOP::push_value_string(bin_impl(data[i]), i, res_data, res_offsets);
        }
        return Status::OK();
    }
};

using FunctionBin = FunctionUnaryToType<BinImpl, NameBin>;

template <PrimitiveType AType, PrimitiveType BType>
struct PowImpl {
    using A = typename PrimitiveTypeTraits<AType>::ColumnItemType;
    using B = typename PrimitiveTypeTraits<BType>::ColumnItemType;
    static constexpr PrimitiveType ResultType = TYPE_DOUBLE;
    static const constexpr bool allow_decimal = false;

    template <PrimitiveType type>
    static inline double apply(A a, B b) {
        /// Next everywhere, static_cast - so that there is no wrong result in expressions of the form Int64 c = UInt32(a) * Int32(-1).
        return std::pow((double)a, (double)b);
    }
};
struct PowName {
    static constexpr auto name = "pow";
};
using FunctionPow = FunctionBinaryArithmetic<PowImpl, PowName, false>;

class FunctionNormalCdf : public IFunction {
public:
    static constexpr auto name = "normal_cdf";

    String get_name() const override { return name; }

    static FunctionPtr create() { return std::make_shared<FunctionNormalCdf>(); }

    DataTypePtr get_return_type_impl(const DataTypes& arguments) const override {
        return make_nullable(std::make_shared<DataTypeFloat64>());
    }

    DataTypes get_variadic_argument_types_impl() const override {
        return {std::make_shared<DataTypeFloat64>(), std::make_shared<DataTypeFloat64>(),
                std::make_shared<DataTypeFloat64>()};
    }
    size_t get_number_of_arguments() const override { return 3; }

    Status execute_impl(FunctionContext* context, Block& block, const ColumnNumbers& arguments,
                        uint32_t result, size_t input_rows_count) const override {
        auto result_column = ColumnFloat64::create(input_rows_count);
        auto result_null_map_column = ColumnUInt8::create(input_rows_count, 0);

        auto& result_data = result_column->get_data();
        NullMap& result_null_map =
                assert_cast<ColumnUInt8*>(result_null_map_column.get())->get_data();

        ColumnPtr argument_columns[3];
        bool col_const[3];
        size_t argument_size = arguments.size();
        for (int i = 0; i < argument_size; ++i) {
            argument_columns[i] = block.get_by_position(arguments[i]).column;
            col_const[i] = is_column_const(*argument_columns[i]);
            if (col_const[i]) {
                argument_columns[i] =
                        static_cast<const ColumnConst&>(*argument_columns[i]).get_data_column_ptr();
            }
        }

        auto* mean_col = assert_cast<const ColumnFloat64*>(argument_columns[0].get());
        auto* sd_col = assert_cast<const ColumnFloat64*>(argument_columns[1].get());
        auto* value_col = assert_cast<const ColumnFloat64*>(argument_columns[2].get());

        result_column->reserve(input_rows_count);
        for (size_t i = 0; i < input_rows_count; ++i) {
            double mean = mean_col->get_element(index_check_const(i, col_const[0]));
            double sd = sd_col->get_element(index_check_const(i, col_const[1]));
            double v = value_col->get_element(index_check_const(i, col_const[2]));

            if (!check_argument(sd)) [[unlikely]] {
                result_null_map[i] = true;
                continue;
            }
            result_data[i] = calculate_cell(mean, sd, v);
        }

        block.get_by_position(result).column =
                ColumnNullable::create(std::move(result_column), std::move(result_null_map_column));
        return Status::OK();
    }

    static bool check_argument(double sd) { return sd > 0; }
    static double calculate_cell(double mean, double sd, double v) {
#ifdef __APPLE__
        const double sqrt2 = std::sqrt(2);
#else
        constexpr double sqrt2 = std::numbers::sqrt2;
#endif

        return 0.5 * (std::erf((v - mean) / (sd * sqrt2)) + 1);
    }
};

// TODO: Now math may cause one thread compile time too long, because the function in math
// so mush. Split it to speed up compile time in the future
void register_function_math(SimpleFunctionFactory& factory) {
    factory.register_function<FunctionAcos>();
    factory.register_function<FunctionAcosh>();
    factory.register_function<FunctionAsin>();
    factory.register_function<FunctionAsinh>();
    factory.register_function<FunctionAtan>();
    factory.register_function<FunctionAtanh>();
    factory.register_function<FunctionAtan2>();
    factory.register_function<FunctionCos>();
    factory.register_function<FunctionCosh>();
    factory.register_function<FunctionE>();
    factory.register_alias("ln", "dlog1");
    factory.register_function<FunctionLog>();
    factory.register_function<FunctionMathLog<ImplLn>>();
    factory.register_function<FunctionMathLog<ImplLog2>>();
    factory.register_function<FunctionMathLog<ImplLog10>>();
    factory.register_alias("log10", "dlog10");
    factory.register_function<FunctionPi>();
    factory.register_function<FunctionSign>();
    factory.register_function<FunctionAbs>();
    factory.register_function<FunctionNegative>();
    factory.register_function<FunctionPositive>();
    factory.register_function<FunctionSin>();
    factory.register_function<FunctionSinh>();
    factory.register_function<FunctionSqrt>();
    factory.register_alias("sqrt", "dsqrt");
    factory.register_function<FunctionCbrt>();
    factory.register_function<FunctionTan>();
    factory.register_function<FunctionTanh>();
    factory.register_function<FunctionCot>();
    factory.register_function<FunctionSec>();
    factory.register_function<FunctionCosec>();
    factory.register_function<FunctionPow>();
    factory.register_alias("pow", "power");
    factory.register_alias("pow", "dpow");
    factory.register_alias("pow", "fpow");
    factory.register_function<FunctionExp>();
    factory.register_alias("exp", "dexp");
    factory.register_function<FunctionRadians>();
    factory.register_function<FunctionDegrees>();
    factory.register_function<FunctionBin>();
    factory.register_function<FunctionNormalCdf>();
}
} // namespace doris::vectorized
