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

import io.airlift.slice.SizeOf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The UTF-8 encodings of the non-ASCII members of a character set that holds some non-ASCII
/// characters but not all, as byte tables. The tables are built from the pieces that
/// [Compiler#splitUtf8Range] gives the compiler, so [#memberWidth] accepts exactly the
/// byte sequences that the ordinary engine's byte ranges for the set accept: the encoding of each
/// member, encoded surrogates included when the set holds them, and nothing else. Overlong forms,
/// values above U+10FFFF, bare continuation bytes, and truncated sequences are rejected.
///
/// The tables are a byte trie. A lead byte's entry holds the encoding's width and the node of its
/// first continuation byte. The node of a continuation byte before the last, which only three-
/// and four-byte encodings have, is a block of 64 entries, indexed by that byte's low six bits,
/// each the node of the next byte. The node of the last continuation byte is a mask instead: 64
/// bits, one for each byte that ends a member. Identical blocks and masks are stored once, and
/// block and mask zero reject every byte, so a missing child needs no check.
///
/// A lead byte whose every encoding is a member, such as most leads of a negated class with a
/// few non-ASCII exclusions, has a full entry instead: a negative value holding the range of its
/// second byte, which excludes overlong forms, values above U+10FFFF, and the surrogates when the
/// set does not hold them. Its later bytes may be any continuation bytes, so the reader checks
/// only that and reads no node.
final class Utf8MemberTable
{
    private static final int INSTANCE_SIZE = SizeOf.instanceSize(Utf8MemberTable.class);
    private static final int NODE_ENTRIES = 64;
    static final int WIDTH_SHIFT = 24;
    static final int NODE_MASK = (1 << WIDTH_SHIFT) - 1;
    // Marks a full lead entry, whose low 16 bits are the lowest and highest second byte.
    private static final int FULL_LEAD = 1 << 31;

    // Indexed by the low seven bits of a byte at or above 0x80: zero when no member's encoding
    // starts with it, a full entry when every encoding does, otherwise the encoding's width
    // shifted by WIDTH_SHIFT and its first continuation node. The reader takes the width from
    // the byte itself and masks the entry to its node; the width bits are kept because the
    // measured reader reads entries this way.
    private final int[] leads;
    private final int[] nodes;
    private final long[] lastBytes;

    private Utf8MemberTable(int[] leads, int[] nodes, long[] lastBytes)
    {
        this.leads = leads;
        this.nodes = nodes;
        this.lastBytes = lastBytes;
    }

    /// Returns the table of the non-ASCII members of `set`, which must hold some non-ASCII
    /// characters but not every one.
    static Utf8MemberTable of(CharClass set)
    {
        Builder builder = new Builder(set);
        for (int index = 0; index < set.rangeCount(); index++) {
            RuneRange range = set.range(index);
            if (range.high() >= 0x80) {
                // The compiler splits the class's ranges one by one in the same way; their ASCII
                // pieces are the run's member bytes.
                Compiler.splitUtf8Range(range.low(), range.high(), false, builder);
            }
        }
        return builder.build();
    }

    /// Returns the width of the member whose encoding starts at `cursor`, or 0 when none
    /// does. The byte at `cursor`, which must be before `end`, is at or above 0x80.
    /// Copied as `TrinoScanKernelSupport.memberWidth`; keep the copy in step.
    int memberWidth(byte[] input, int cursor, int end)
    {
        int first = input[cursor];
        int lead = leads[first & 0x7F];
        // The width follows from the lead byte, as every member encoding has its natural width,
        // so the next character's position does not wait on the table load.
        int width = first < (byte) 0xE0 ? 2 : first < (byte) 0xF0 ? 3 : 4;
        if (lead == 0 || width > end - cursor) {
            return 0;
        }
        if (lead < 0) {
            // Every encoding with this lead and a second byte in the entry's range is a member.
            int second = input[cursor + 1] & 0xFF;
            if (second < ((lead >>> 8) & 0xFF) || second > (lead & 0xFF)) {
                return 0;
            }
            for (int position = cursor + 2; position < cursor + width; position++) {
                if ((input[position] & 0xC0) != 0x80) {
                    return 0;
                }
            }
            return width;
        }
        int node = lead & NODE_MASK;
        int last = cursor + width - 1;
        for (int position = cursor + 1; position < last; position++) {
            int value = input[position];
            if ((value & 0xC0) != 0x80) {
                return 0;
            }
            node = nodes[(node << 6) | (value & 0x3F)];
        }
        int value = input[last];
        return (value & 0xC0) == 0x80 && (lastBytes[node] & (1L << value)) != 0 ? width : 0;
    }

    /// Returns whether the encoding of some member starts with `value`, a byte at or above
    /// 0x80.
    boolean isLead(int value)
    {
        return leads[value & 0x7F] != 0;
    }

    // These accessors serve the copy of memberWidth in TrinoScanKernelSupport.

    int[] leads()
    {
        return leads;
    }

    int[] nodes()
    {
        return nodes;
    }

    long[] lastBytes()
    {
        return lastBytes;
    }

    long estimatedRetainedSize()
    {
        return INSTANCE_SIZE + SizeOf.sizeOf(leads) + SizeOf.sizeOf(nodes) + SizeOf.sizeOf(lastBytes);
    }

    /// Builds the tables from the compiler's pieces in a mutable trie, adding each piece's
    /// encodings to the nodes on its paths. UTF-8's shape bounds the trie, however many pieces
    /// the compiler splits the set into: 30 two-byte leads with a mask each, 16 three-byte leads
    /// with at most 992 second bytes among them, and 5 four-byte leads with at most 256 second
    /// bytes among them and 64 third bytes each, so at most 17,406 masks and 277 blocks. A full
    /// lead reads no node, so its encodings are not added. Identical blocks and masks are stored
    /// once when the trie is frozen.
    private static final class Builder
            implements Compiler.Utf8RangePieces
    {
        private final CharClass set;
        // Indexed like leads: the width of the lead byte's encodings, or 0 when none starts with
        // it; its full entry, or 0 when it is not full; and the node of its first continuation
        // byte, which a full lead does not have.
        private final int[] leadWidths = new int[128];
        private final int[] fullLeads = new int[128];
        private final int[] leadNodes = new int[128];
        // The trie's blocks and masks; each block entry holds the node of the next byte. Block and
        // mask zero are never used, so a zero entry is a missing child.
        private int[][] blocks = new int[16][];
        private int blockCount = 1;
        private long[] masks = new long[64];
        private int maskCount = 1;

        private Builder(CharClass set)
        {
            this.set = set;
        }

        @Override
        public void addAllNonAscii()
        {
            throw new IllegalStateException("addAllNonAscii called for a set of only some non-ASCII characters");
        }

        @Override
        public void addAscii(int low, int high, boolean foldCase)
        {
            // The run's member bytes hold the ASCII members.
        }

        @Override
        public void addEncodedRange(byte[] low, byte[] high)
        {
            for (int lead = low[0] & 0xFF; lead <= (high[0] & 0xFF); lead++) {
                int index = lead & 0x7F;
                if (leadWidths[index] == 0) {
                    leadWidths[index] = low.length;
                    fullLeads[index] = fullLead(set, lead);
                    if (fullLeads[index] == 0) {
                        leadNodes[index] = newNode(low.length - 1);
                    }
                }
                else if (leadWidths[index] != low.length) {
                    throw new IllegalStateException(String.format(Locale.ROOT, "UTF-8 lead byte 0x%02X with widths %d and %d", lead, leadWidths[index], low.length));
                }
                if (fullLeads[index] == 0) {
                    add(leadNodes[index], low, high, 1);
                }
            }
        }

        /// Adds the continuation bytes from `offset` of every encoding between `low` and
        /// `high` to `node`, which reads the byte at `offset`.
        private void add(int node, byte[] low, byte[] high, int offset)
        {
            int first = low[offset] & 0x3F;
            int last = high[offset] & 0x3F;
            if (offset == low.length - 1) {
                masks[node] |= (-1L << first) & (-1L >>> (63 - last));
                return;
            }
            int[] block = blocks[node];
            for (int entry = first; entry <= last; entry++) {
                if (block[entry] == 0) {
                    block[entry] = newNode(low.length - 1 - offset);
                }
                add(block[entry], low, high, offset + 1);
            }
        }

        /// Returns a new empty node that reads the remaining `continuationBytes` of an
        /// encoding: a mask for the last one, otherwise a block.
        private int newNode(int continuationBytes)
        {
            if (continuationBytes == 1) {
                if (maskCount == masks.length) {
                    masks = Arrays.copyOf(masks, masks.length * 2);
                }
                return maskCount++;
            }
            if (blockCount == blocks.length) {
                blocks = Arrays.copyOf(blocks, blocks.length * 2);
            }
            blocks[blockCount] = new int[NODE_ENTRIES];
            return blockCount++;
        }

        private Utf8MemberTable build()
        {
            // The table numbers a node once its children are frozen, walking from the leads in
            // ascending order.
            Nodes table = new Nodes();
            int[] leads = new int[128];
            for (int index = 0; index < leads.length; index++) {
                if (leadWidths[index] != 0) {
                    int full = fullLeads[index];
                    leads[index] = full != 0 ? full : (leadWidths[index] << WIDTH_SHIFT) | freeze(table, leadNodes[index], leadWidths[index] - 1);
                }
            }
            return new Utf8MemberTable(leads, table.nodes(), table.lastBytes());
        }

        /// Returns the full entry of `lead` when `set` holds every character whose
        /// encoding starts with it, or 0. For lead ED, which also starts the encoded surrogates,
        /// the set must hold all of them or none, and its entry accepts them only when it holds
        /// them, as the compiler's pieces do.
        private static int fullLead(CharClass set, int lead)
        {
            int low;
            int high;
            int lowestSecond = 0x80;
            int highestSecond = 0xBF;
            if (lead < 0xE0) {
                low = (lead & 0x1F) << 6;
                high = low | 0x3F;
            }
            else if (lead < 0xF0) {
                low = (lead & 0x0F) << 12;
                high = low | 0xFFF;
                if (lead == 0xE0) {
                    low = 0x800;
                    lowestSecond = 0xA0;
                }
                else if (lead == 0xED && !containsAll(set, 0xD800, 0xDFFF)) {
                    if (containsAny(set, 0xD800, 0xDFFF)) {
                        return 0;
                    }
                    high = 0xD7FF;
                    highestSecond = 0x9F;
                }
            }
            else {
                low = (lead & 0x07) << 18;
                high = Math.min(low | 0x3FFFF, Regexp.RUNEMAX);
                if (lead == 0xF0) {
                    low = 0x10000;
                    lowestSecond = 0x90;
                }
                else if (lead == 0xF4) {
                    highestSecond = 0x8F;
                }
            }
            return containsAll(set, low, high) ? FULL_LEAD | (lowestSecond << 8) | highestSecond : 0;
        }

        private static boolean containsAll(CharClass set, int low, int high)
        {
            int next = low;
            for (int index = 0; index < set.rangeCount() && next <= high; index++) {
                RuneRange range = set.range(index);
                if (range.low() > next) {
                    return false;
                }
                next = Math.max(next, range.high() + 1);
            }
            return next > high;
        }

        private static boolean containsAny(CharClass set, int low, int high)
        {
            for (int index = 0; index < set.rangeCount(); index++) {
                RuneRange range = set.range(index);
                if (range.low() <= high && range.high() >= low) {
                    return true;
                }
            }
            return false;
        }

        /// Returns the index in `table` of `node`, which reads the remaining
        /// `continuationBytes` of an encoding: a mask index for the last one, otherwise a
        /// block index. Trie nodes are not shared, so each is frozen once.
        private int freeze(Nodes table, int node, int continuationBytes)
        {
            if (continuationBytes == 1) {
                return table.internMask(masks[node]);
            }
            int[] children = blocks[node];
            int[] block = new int[NODE_ENTRIES];
            for (int entry = 0; entry < NODE_ENTRIES; entry++) {
                if (children[entry] != 0) {
                    block[entry] = freeze(table, children[entry], continuationBytes - 1);
                }
            }
            return table.internBlock(block);
        }
    }

    /// Blocks and masks, each stored once. Block and mask zero reject every byte.
    private static final class Nodes
    {
        private final List<int[]> blocks = new ArrayList<>();
        private final Map<Block, Integer> blockIndexes = new HashMap<>();
        private final List<Long> masks = new ArrayList<>();
        private final Map<Long, Integer> maskIndexes = new HashMap<>();

        private Nodes()
        {
            internBlock(new int[NODE_ENTRIES]);
            internMask(0);
        }

        private int internBlock(int[] block)
        {
            return blockIndexes.computeIfAbsent(new Block(block), _ -> {
                blocks.add(block);
                return blocks.size() - 1;
            });
        }

        private int internMask(long mask)
        {
            return maskIndexes.computeIfAbsent(mask, _ -> {
                masks.add(mask);
                return masks.size() - 1;
            });
        }

        private int[] nodes()
        {
            int[] nodes = new int[blocks.size() * NODE_ENTRIES];
            for (int block = 0; block < blocks.size(); block++) {
                System.arraycopy(blocks.get(block), 0, nodes, block * NODE_ENTRIES, NODE_ENTRIES);
            }
            return nodes;
        }

        private long[] lastBytes()
        {
            return masks.stream().mapToLong(Long::longValue).toArray();
        }
    }

    // A stored block is never changed, so its entries can key it.
    private record Block(int[] entries)
    {
        @Override
        public boolean equals(Object object)
        {
            return object instanceof Block other && Arrays.equals(entries, other.entries);
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode(entries);
        }
    }
}
