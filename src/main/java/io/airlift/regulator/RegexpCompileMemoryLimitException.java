/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.regulator;

import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/// Thrown when compilation exceeds the configured regular-expression memory budget.
///
/// The exception reports, where compilation can prove one, a lower bound on the memory budget
/// the pattern needs to compile: [#requiredMaxMemoryBytes()] is a budget below which the pattern
/// compiles by no route it can take, though it may need more. It is empty when no bound beyond
/// the rejected budget is proven, and when no budget compiles the pattern, which the message then
/// says.
public final class RegexpCompileMemoryLimitException
        extends RegexpCompileException
{
    // No bound on the required budget is known.
    private static final long UNKNOWN = 0;
    // No budget compiles the pattern.
    private static final long NO_BUDGET = -1;
    private static final long KIB = 1 << 10;
    private static final long MIB = 1 << 20;

    private final long maxMemoryBytes;
    private final long requiredMaxMemoryBytes;
    // Measures what the failed check needs, where that costs more work than the check, so only a
    // rejected compile measures it; null otherwise.
    private final transient Supplier<RegexpCompileMemoryLimitException> measurement;

    RegexpCompileMemoryLimitException(long maxMemoryBytes)
    {
        this(maxMemoryBytes, UNKNOWN, null);
    }

    /// `requiredMaxMemoryBytes` is either a positive bound, more than `maxMemoryBytes`, below
    /// which no budget passes the check that failed, or [#NO_BUDGET] where no budget passes it.
    RegexpCompileMemoryLimitException(long maxMemoryBytes, long requiredMaxMemoryBytes)
    {
        this(maxMemoryBytes, requiredMaxMemoryBytes, null);
    }

    /// What the failed check needs is measured by `measurement` when [#measured()] asks for it.
    RegexpCompileMemoryLimitException(long maxMemoryBytes, Supplier<RegexpCompileMemoryLimitException> measurement)
    {
        this(maxMemoryBytes, UNKNOWN, requireNonNull(measurement, "measurement is null"));
    }

    private RegexpCompileMemoryLimitException(long maxMemoryBytes, long requiredMaxMemoryBytes, Supplier<RegexpCompileMemoryLimitException> measurement)
    {
        // The message is formatted only when asked for, since a compile that recovers from the
        // rejection by another route never reads it.
        super(null);
        this.maxMemoryBytes = maxMemoryBytes;
        this.requiredMaxMemoryBytes = requiredMaxMemoryBytes;
        this.measurement = measurement;
    }

    /// Returns the exception for a check that no budget passes.
    static RegexpCompileMemoryLimitException fitsNoBudget(long maxMemoryBytes)
    {
        return new RegexpCompileMemoryLimitException(maxMemoryBytes, NO_BUDGET);
    }

    @Override
    public String getMessage()
    {
        return buildMessage(maxMemoryBytes, requiredMaxMemoryBytes);
    }

    public long maxMemoryBytes()
    {
        return maxMemoryBytes;
    }

    /// Returns a lower bound, in bytes, on the memory budget the pattern needs to compile: no
    /// smaller budget compiles it by any route it can take. A larger budget may still be needed,
    /// since a later step of compilation can need more than the step that failed, and the DFAs
    /// may need more to search at full speed. It is empty when no bound beyond the rejected
    /// budget is proven, or when no budget compiles the pattern.
    public OptionalLong requiredMaxMemoryBytes()
    {
        return requiredMaxMemoryBytes > 0 ? OptionalLong.of(requiredMaxMemoryBytes) : OptionalLong.empty();
    }

    /// Returns the exception for the same failed check that reports what it needs, measuring that
    /// where the check left it to be measured on demand.
    RegexpCompileMemoryLimitException measured()
    {
        return measurement == null ? this : measurement.get();
    }

    /// Returns the rejection with the memory `memory` gives added to what a check measured on
    /// demand needs, for a step after the check that takes that memory from the same budget. A
    /// measurement that proves no bound, or that no budget passes, is kept as it is, and a
    /// rejection measured at once is returned unchanged.
    RegexpCompileMemoryLimitException addToMeasurement(LongSupplier memory)
    {
        requireNonNull(memory, "memory is null");
        if (measurement == null) {
            return this;
        }
        Supplier<RegexpCompileMemoryLimitException> measure = measurement;
        RegexpCompileMemoryLimitException result = new RegexpCompileMemoryLimitException(maxMemoryBytes, () -> {
            RegexpCompileMemoryLimitException measured = measure.get();
            if (measured.requiredMaxMemoryBytes <= 0) {
                return measured;
            }
            // Both are below the largest program's memory, far from overflowing.
            return new RegexpCompileMemoryLimitException(measured.maxMemoryBytes, measured.requiredMaxMemoryBytes + memory.getAsLong());
        });
        result.setStackTrace(getStackTrace());
        return result;
    }

    /// Whether the failed check reported a lower bound on the budget that passes it; one that
    /// no budget passes reports [#fitsNoBudget()] instead.
    boolean hasRequiredMaxMemory()
    {
        return requiredMaxMemoryBytes > 0;
    }

    /// Whether no budget passes the failed check.
    boolean fitsNoBudget()
    {
        return requiredMaxMemoryBytes == NO_BUDGET;
    }

    /// Returns the positive bound below which no budget passes the failed check. Call it only
    /// where [#hasRequiredMaxMemory()].
    long requiredMaxMemory()
    {
        return requiredMaxMemoryBytes;
    }

    private static String buildMessage(long maxMemoryBytes, long requiredMaxMemoryBytes)
    {
        String message = maxMemoryBytes > 0
                ? "regular expression compilation exceeded the " + maxMemoryBytes + " byte memory limit"
                : "regular expression compilation exceeded the memory limit";
        if (requiredMaxMemoryBytes == NO_BUDGET) {
            return message + "; the pattern is too large to compile at any memory budget";
        }
        if (requiredMaxMemoryBytes == UNKNOWN) {
            return message;
        }
        return message + "; the pattern needs a larger memory budget, at least " + formatBudget(requiredMaxMemoryBytes);
    }

    // Rounds up to the unit shown, so the figure is never below the bound: in bytes below 1 KiB,
    // to KiB while that is below 1024 KiB, and to MiB from there.
    static String formatBudget(long bytes)
    {
        if (bytes < KIB) {
            return bytes + (bytes == 1 ? " byte" : " bytes");
        }
        long kib = Math.ceilDiv(bytes, KIB);
        if (kib < 1024) {
            return kib + " KiB";
        }
        return Math.ceilDiv(bytes, MIB) + " MiB";
    }
}
