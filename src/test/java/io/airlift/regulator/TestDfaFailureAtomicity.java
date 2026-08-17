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

import io.airlift.slice.Slice;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.airlift.regulator.Dfa.DfaInstance.Kind.FIRST_MATCH;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.EXCLUSIVE_ADMISSION;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.EXCLUSIVE_TEARDOWN;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.SELF_LOOP_ARRAY_GROWTH;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.STATE_CACHE_INSERTION;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.STATE_DATA_ARRAY_GROWTH;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.STATE_REFERENCE_ARRAY_GROWTH;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.STATE_REFERENCE_ROW_ALLOCATION;
import static io.airlift.regulator.Dfa.DfaInstance.TestingFailurePoint.TRANSITION_ARRAY_GROWTH;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDfaFailureAtomicity
{
    @Test
    public void testFailedExclusiveAdmissionReopensReaders()
            throws Exception
    {
        Fixture fixture = simpleFixture();
        long cacheVersion = fixture.dfa().cacheVersion();
        fixture.dfa().failNextForTesting(EXCLUSIVE_ADMISSION);

        assertThatThrownBy(() -> fixture.dfa().beginSearch(true))
                .isInstanceOf(Error.class);

        assertThat(fixture.dfa().cacheVersion()).isEqualTo(cacheVersion);
        assertThat(fixture.dfa().hasPendingCacheMutationForTesting()).isFalse();
        assertThat(fixture.dfa().cacheMutationLockedForTesting()).isFalse();
        assertReusable(fixture);
    }

    @Test
    public void testFailedExclusiveTeardownReopensReaders()
            throws Exception
    {
        Fixture fixture = simpleFixture();
        long cacheVersion = fixture.dfa().cacheVersion();
        fixture.dfa().beginSearch(true);
        fixture.dfa().failNextForTesting(EXCLUSIVE_TEARDOWN);

        assertThatThrownBy(() -> fixture.dfa().endSearch(true, null))
                .isInstanceOf(Error.class);

        assertThat(fixture.dfa().cacheVersion()).isEqualTo(cacheVersion + 1);
        assertThat(fixture.dfa().hasPendingCacheMutationForTesting()).isFalse();
        assertThat(fixture.dfa().cacheMutationLockedForTesting()).isFalse();
        assertReusable(fixture);
    }

    @Test
    public void testExclusiveVersionAdvancesOnlyAfterAdmission()
    {
        Fixture fixture = simpleFixture();
        long initialVersion = fixture.dfa().cacheVersion();

        fixture.dfa().beginSearch(true);
        fixture.dfa().cancelExclusiveSearch();
        assertThat(fixture.dfa().cacheVersion()).isEqualTo(initialVersion);

        fixture.dfa().beginSearch(true);
        fixture.dfa().endSearch(true, null);
        assertThat(fixture.dfa().cacheVersion()).isEqualTo(initialVersion + 1);
        assertThat(fixture.dfa().hasPendingCacheMutationForTesting()).isFalse();
        assertThat(fixture.dfa().cacheMutationLockedForTesting()).isFalse();
    }

    @Test
    public void testFailedStateArrayGrowthIsAtomic()
            throws Exception
    {
        for (Dfa.DfaInstance.TestingFailurePoint failurePoint : new Dfa.DfaInstance.TestingFailurePoint[] {
                TRANSITION_ARRAY_GROWTH,
                STATE_DATA_ARRAY_GROWTH,
                STATE_REFERENCE_ARRAY_GROWTH,
                SELF_LOOP_ARRAY_GROWTH,
        }) {
            assertStateAllocationFailureIsAtomic(failurePoint);
        }
    }

    @Test
    public void testFailedStateReferenceRowAllocationIsAtomic()
            throws Exception
    {
        assertStateAllocationFailureIsAtomic(STATE_REFERENCE_ROW_ALLOCATION);
    }

    @Test
    public void testFailedStateCacheInsertionIsAtomic()
            throws Exception
    {
        assertStateAllocationFailureIsAtomic(STATE_CACHE_INSERTION);
        assertWarmCacheInsertionFailureIsAtomic();
    }

    private static void assertStateAllocationFailureIsAtomic(Dfa.DfaInstance.TestingFailurePoint failurePoint)
            throws Exception
    {
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(4L << 20));
        Slice input = wrappedBuffer(stateExplosionInput(16 * 1024));
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(FIRST_MATCH);
        dfa.failNextForTesting(failurePoint);

        assertThatThrownBy(() -> pattern.matcher(input).find())
                .as(failurePoint.name())
                .isInstanceOf(Error.class);

        assertThat(dfa.testingSnapshotBeforeFailure())
                .as(failurePoint.name())
                .isNotNull()
                .isEqualTo(dfa.failureAtomicitySnapshot());
        assertThat(dfa.hasPendingCacheMutationForTesting()).isFalse();
        assertThat(dfa.cacheMutationLockedForTesting()).isFalse();

        assertCompletes(() -> assertThat(pattern.matcher(input).find()).isTrue());
        assertCompletes(dfa::resetCacheExternal);
    }

    private static void assertWarmCacheInsertionFailureIsAtomic()
            throws Exception
    {
        Re2 pattern = Re2.compile(
                utf8Slice("[a-q][^u-z]{80}x"),
                Re2.Options.latin1().setMaxMemory(4L << 20));
        Slice warmupInput = wrappedBuffer(stateExplosionInput(1_538));
        Slice input = wrappedBuffer(stateExplosionInput(16 * 1024));
        pattern.matcher(warmupInput).find();
        Dfa.DfaInstance dfa = pattern.forwardProgramForDiagnostics().getCachedDfa(FIRST_MATCH);
        Dfa.FailureAtomicitySnapshot warmed = dfa.failureAtomicitySnapshot();
        assertThat(warmed.cacheEntries()).isEqualTo((warmed.cacheTableCapacity() * 3) / 4);
        dfa.failNextForTesting(STATE_CACHE_INSERTION);

        assertThatThrownBy(() -> pattern.matcher(input).find())
                .isInstanceOf(Error.class);

        assertThat(dfa.testingSnapshotBeforeFailure())
                .isNotNull()
                .isEqualTo(dfa.failureAtomicitySnapshot());
        assertCompletes(() -> assertThat(pattern.matcher(input).find()).isTrue());
    }

    @Test
    public void testReusableCheckDoesNotWaitForBlockedWorkerCleanup()
    {
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> assertCompletes(release::await, 1, TimeUnit.NANOSECONDS))
                    .isInstanceOf(TimeoutException.class);
        }
        finally {
            release.countDown();
        }
    }

    private static Fixture simpleFixture()
    {
        Prog program = Re2BenchmarkRunner.compileProg("a.*z");
        program.setDfaMemory(64L << 20);
        Slice input = utf8Slice("abcdez");
        Dfa.DfaInstance dfa = program.getCachedDfa(FIRST_MATCH);
        return new Fixture(program, dfa, input);
    }

    private static void assertReusable(Fixture fixture)
            throws Exception
    {
        assertCompletes(() -> assertThat(Dfa.search(
                fixture.program(),
                fixture.input(),
                false,
                Prog.MatchKind.FIRST_MATCH,
                true)).isEqualTo(fixture.input().length()));
        assertCompletes(fixture.dfa()::resetCacheExternal);
    }

    private static void assertCompletes(CheckedRunnable runnable)
            throws Exception
    {
        assertCompletes(runnable, 10, TimeUnit.SECONDS);
    }

    private static void assertCompletes(CheckedRunnable runnable, long timeout, TimeUnit unit)
            throws Exception
    {
        FutureTask<Void> task = new FutureTask<>(() -> {
            runnable.run();
            return null;
        });
        // A broken lock may ignore interruption. Do not wait indefinitely for executor cleanup.
        Thread.ofPlatform().daemon().start(task);
        try {
            task.get(timeout, unit);
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
        finally {
            task.cancel(false);
        }
    }

    private static byte[] stateExplosionInput(int length)
    {
        Random random = new Random(42);
        byte[] alphabet = "abcdefghijklmnopqrst \n,.;0123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] input = new byte[length];
        for (int index = 0; index < input.length; index++) {
            input[index] = index % 997 == 996 ? (byte) 'x' : alphabet[random.nextInt(alphabet.length)];
        }
        return input;
    }

    private record Fixture(Prog program, Dfa.DfaInstance dfa, Slice input) {}

    @FunctionalInterface
    private interface CheckedRunnable
    {
        void run()
                throws Exception;
    }
}
