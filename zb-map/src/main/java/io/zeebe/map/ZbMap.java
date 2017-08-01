/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
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
package io.zeebe.map;

import static io.zeebe.map.ZbMapDescriptor.BUCKET_DATA_OFFSET;

import java.lang.reflect.ParameterizedType;
import java.util.concurrent.atomic.AtomicBoolean;

import org.agrona.BitUtil;
import org.agrona.CloseHelper;
import org.slf4j.Logger;

/**
 * Simple map data structure using extensible hashing.
 * Data structure is not threadsafe.
 *
 * The map size should be a power of two. If it is not a power of two the next power
 * of two is used. The max map size is {@link ZbMap#MAX_TABLE_SIZE} as the map stores
 * long addresses and this is the maximum number of entries which can be addressed with map keys
 * generated by {@link KeyHandler#keyHashCode}.
 *
 */
public abstract class ZbMap<K extends KeyHandler, V extends ValueHandler>
{
    private static final int KEY_HANDLER_IDX = 0;
    private static final int VALUE_HANDLER_IDX = 1;

    /**
     * The load factor which is used to determine if the hash table should be increased or overflow should be used.
     */
    private static final float LOAD_FACTOR_OVERFLOW_LIMIT = 0.6F;

    public static final int DEFAULT_TABLE_SIZE = 32;
    public static final int DEFAULT_BLOCK_COUNT = 16;

    private static final String FINALIZER_WARNING = "ZbMap is being garbage collected but is not closed.\n" +
        "This means that the object is being de-referenced but the close() method has not been called.\n" +
        "ZbMap allocates memory off the heap which is not reclaimed unless close() is invoked.\n";

    public static final Logger LOG = Loggers.ZB_MAP_LOGGER;

    /**
     * The maximum table size is 2^27, because it is the last power of two which fits into an integer after multiply with SIZE_OF_LONG (8 bytes).
     * <p>
     * The table size have to be multiplied with SIZE_OF_LONG to calculated the size of the hash table buffer,
     * which have to be allocated to store all addresses. The addresses, which are stored in the hash table buffer, are longs.
     */
    public static final int MAX_TABLE_SIZE = 1 << 27;

    /**
     * The optimal block count regarding to performance and memory usage.
     * Was determined with help of some benchmarks see {@link io.zeebe.map.benchmarks.ZbMapDetermineSizesBenchmark}.
     */
    public static final int OPTIMAL_BLOCK_COUNT = DEFAULT_BLOCK_COUNT;

    /**
     * Deprecated since the hash table can grow dynamic now. It is used only for the start hash table size.
     */
    public static final int OPTIMAL_TABLE_SIZE = DEFAULT_TABLE_SIZE;

    protected final K keyHandler;
    protected final K splitKeyHandler;
    protected final V valueHandler;

    protected final HashTable hashTable;
    protected final BucketArray bucketArray;

    protected int maxTableSize;
    protected int tableSize;
    protected int mask;
    protected final int minBlockCountPerBucket;
    protected double loadFactorOverflowLimit;

    /**
     * The number of times this HashMap has been structurally modified.
     * Structural modifications are those that change the number of mappings in
     * the HashMap or otherwise modify its internal structure (e.g., rehash).
     * This field is used to make iterators fail-fast. (See
     * ConcurrentModificationException).
     */
    protected int modCount;

    private final Block blockHelperInstance = new Block();
    protected final AtomicBoolean isClosed = new AtomicBoolean(false);

    public ZbMap(int maxKeyLength, int maxValueLength)
    {
        this(DEFAULT_TABLE_SIZE, DEFAULT_BLOCK_COUNT, maxKeyLength, maxValueLength);
    }

    /**
     * Creates an hash map object.
     *
     * <p>
     * The map can store `X` entries, which is at maximum equal to `tableSize * maxBlockLength`.
     * The maxBlockLength is equal to {@link ZbMapDescriptor#BUCKET_DATA_OFFSET} + (bucketCount * {@link ZbMapDescriptor#getBlockLength(int, int)}))
     * </p>
     *
     * <p>
     * Note: it could happen that some hashes modulo the map size generate the same bucket id, which means
     * some buckets can be filled more than other.
     * The buckets will be tried to splitted in that case. Is the new generated bucket id larger than the table size
     * the table will be dynamically resized (if the next size is smaller then the maximum size). If the
     * current load factor is less then the maximum load factor the bucket will overflow before the table size is increased.
     * To avoid this the implementation of the `KeyHandler` have to provide a good one way function, so the entries
     * have a good distribution.
     * </p>
     *
     * <b>Example:</b>
     * <pre>
     * map with map size 6 and maxBlockLength of 3 * (VAL len + KEY len)
     * KeyTable                Buckets:                                      Overflow
     * [bucket0]      ->     [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ]  -> [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ] -> [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ]
     * [bucket1]      ->     [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ]
     * [bucket2]      ->     [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ]
     * [bucket3]      ->     [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ]
     * [bucket4]      ->     [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ]
     * [bucket5]      ->     [ [KEY | VAL] | [KEY | VAL] | [KEY | VAL] ]
     * </pre>
     *
     * @param initialTableSize is the count of buckets, which should been used initial by the hash map
     * @param minBlockCount the minimal count of blocks which should fit in a bucket
     * @param maxKeyLength the max length of a key
     * @param maxValueLength the max length of a value
     */
    public ZbMap(int initialTableSize, int minBlockCount, int maxKeyLength, int maxValueLength)
    {
        this.keyHandler = createKeyHandlerInstance(maxKeyLength);
        this.splitKeyHandler = createKeyHandlerInstance(maxKeyLength);
        this.valueHandler = createInstance(VALUE_HANDLER_IDX);

        this.maxTableSize = MAX_TABLE_SIZE;
        this.tableSize = ensureTableSizeIsPowerOfTwo(initialTableSize);
        this.mask = this.tableSize - 1;
        this.minBlockCountPerBucket = minBlockCount;
        this.loadFactorOverflowLimit = LOAD_FACTOR_OVERFLOW_LIMIT;

        this.hashTable = new HashTable(this.tableSize);
        this.bucketArray = new BucketArray(minBlockCount, maxKeyLength, maxValueLength);

        init();
    }

    public long getHashTableSize()
    {
        return hashTable.getLength();
    }

    public long size()
    {
        return hashTable.getLength() + bucketArray.getCountOfUsedBytes();
    }

    private int ensureTableSizeIsPowerOfTwo(final int tableSize)
    {
        final int powerOfTwo = BitUtil.findNextPositivePowerOfTwo(tableSize);

        if (powerOfTwo != tableSize)
        {
            LOG.warn("Supplied hash table size {} is not a power of two. Using next power of two {} instead.", tableSize, powerOfTwo);
        }

        if (powerOfTwo > MAX_TABLE_SIZE)
        {
            LOG.warn("Size {} greater then max hash table size. Using max hash table size {} instead.", powerOfTwo, MAX_TABLE_SIZE);
            return MAX_TABLE_SIZE;
        }
        else
        {
            return powerOfTwo;
        }
    }

    private void init()
    {
        final long bucketAddress = this.bucketArray.allocateNewBucket(0, 0);
        for (int idx = 0; idx < tableSize; idx++)
        {
            hashTable.setBucketAddress(idx, bucketAddress);
        }

        modCount = 0;
    }

    public void close()
    {
        if (isClosed.compareAndSet(false, true))
        {
            CloseHelper.quietClose(hashTable);
            CloseHelper.quietClose(bucketArray);
        }
    }

    @Override
    protected void finalize() throws Throwable
    {
        if (!isClosed.get())
        {
            LOG.error(FINALIZER_WARNING);
        }

    }

    public void clear()
    {
        hashTable.clear();
        bucketArray.clear();

        init();
    }

    private K createKeyHandlerInstance(int maxKeyLength)
    {
        final K keyHandler = createInstance(KEY_HANDLER_IDX);
        keyHandler.setKeyLength(maxKeyLength);
        return keyHandler;
    }

    @SuppressWarnings("unchecked")
    private <T> T createInstance(int map)
    {
        Class<T> tClass = null;
        try
        {
            tClass = (Class<T>) ((ParameterizedType) this.getClass()
                                                            .getGenericSuperclass()).getActualTypeArguments()[map];
            return tClass.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e)
        {
            throw new RuntimeException("Could not instantiate " + tClass, e);
        }
    }

    protected void setMaxTableSize(int maxTableSize)
    {
        this.maxTableSize = ensureTableSizeIsPowerOfTwo(maxTableSize);
    }

    public void setLoadFactorOverflowLimit(double loadFactorOverflowLimit)
    {
        this.loadFactorOverflowLimit = loadFactorOverflowLimit;
    }

    public int bucketCount()
    {
        return bucketArray.getBucketCount();
    }

    protected boolean put()
    {
        final int keyHashCode = keyHandler.keyHashCode();
        int bucketId = keyHashCode & mask;

        boolean isUpdated = false;
        boolean isPut = false;
        boolean scanForKey = true;

        while (!isPut && !isUpdated)
        {
            long bucketAddress = hashTable.getBucketAddress(bucketId);

            if (scanForKey)
            {
                final Block block = findBlockInBucket(bucketAddress);
                final boolean blockWasFound = block.wasFound();
                if (blockWasFound)
                {
                    bucketAddress = block.getBucketAddress();
                    final int blockOffset = block.getBlockOffset();
                    bucketArray.updateValue(valueHandler, bucketAddress, blockOffset);
                    modCount += 1;
                    isUpdated = true;
                }
                scanForKey = blockWasFound;
            }
            else
            {
                isPut = bucketArray.addBlock(bucketAddress, keyHandler, valueHandler);

                if (!isPut)
                {
                    splitBucket(bucketAddress);
                    bucketId = keyHashCode & mask;
                }

                modCount += 1;
            }
        }
        return isUpdated;
    }

    protected boolean remove()
    {
        final Block block = findBlock();
        final boolean wasFound = block.wasFound();
        if (wasFound)
        {
            final long bucketAddress = block.getBucketAddress();
            final int blockOffset = block.getBlockOffset();
            bucketArray.readValue(valueHandler, bucketAddress, blockOffset);
            bucketArray.removeBlock(bucketAddress, blockOffset);

            modCount += 1;
        }
        return wasFound;
    }

    protected boolean get()
    {
        final Block block = findBlock();
        final boolean wasFound = block.wasFound();
        if (wasFound)
        {
            bucketArray.readValue(valueHandler, block.getBucketAddress(), block.getBlockOffset());
        }
        return wasFound;
    }

    private Block findBlock()
    {
        final int keyHashCode = keyHandler.keyHashCode();
        final int bucketId = keyHashCode & mask;
        final long bucketAddress = hashTable.getBucketAddress(bucketId);
        return findBlockInBucket(bucketAddress);
    }

    private Block findBlockInBucket(long bucketAddress)
    {
        final Block foundBlock = blockHelperInstance;
        foundBlock.reset();
        boolean keyFound = false;

        do
        {
            final int bucketFillCount = bucketArray.getBucketFillCount(bucketAddress);
            int blockOffset = bucketArray.getFirstBlockOffset(bucketAddress);
            int blocksVisited = 0;

            while (!keyFound && blocksVisited < bucketFillCount)
            {
                keyFound = bucketArray.keyEquals(keyHandler, bucketAddress, blockOffset);

                if (keyFound)
                {
                    foundBlock.set(bucketAddress, blockOffset);
                }

                blockOffset += bucketArray.getBlockLength(bucketAddress, blockOffset);
                blocksVisited++;
            }

            bucketAddress = bucketArray.getBucketOverflowPointer(bucketAddress);
        } while (!keyFound && bucketAddress > 0);
        return foundBlock;
    }

    /**
     * splits a block performing the map update and relocation and compaction of blocks.
     */
    private void splitBucket(long filledBucketAddress)
    {
        final int filledBucketId = bucketArray.getBucketId(filledBucketAddress);
        final int bucketDepth = bucketArray.getBucketDepth(filledBucketAddress);

        // calculate new ids and depths
        final int newBucketId = 1 << bucketDepth | filledBucketId;
        final int newBucketDepth = bucketDepth + 1;

        if (newBucketId < tableSize)
        {
            createNewBucket(filledBucketAddress, bucketDepth, newBucketId, newBucketDepth);
        }
        else
        {
            final float loadFactor = bucketArray.getLoadFactor();
            if (loadFactor < loadFactorOverflowLimit)
            {
                bucketArray.overflow(filledBucketAddress);
            }
            else
            {
                final int newTableSize = tableSize << 1;
                if (newTableSize <= maxTableSize)
                {
                    tableSize = newTableSize;
                    mask = tableSize - 1;
                    hashTable.resize(tableSize);
                    createNewBucket(filledBucketAddress, bucketDepth, newBucketId, newBucketDepth);
                }
                else
                {
                    throw new RuntimeException("ZbMap is full. Cannot resize the hash table to size: " + newTableSize +
                                                   ", reached max table size of " + maxTableSize);

                }
            }
        }
    }

    private void createNewBucket(long filledBucketAddress, int bucketDepth, int newBucketId, int newBucketDepth)
    {
        // update filled block depth
        bucketArray.setBucketDepth(filledBucketAddress, newBucketDepth);

        // create new bucket
        final long newBucketAddress = bucketArray.allocateNewBucket(newBucketId, newBucketDepth);

        // distribute entries into correct blocks
        distributeEntries(filledBucketAddress, newBucketAddress, bucketDepth);

        // update map
        final int mapDiff = 1 << newBucketDepth;
        for (int i = newBucketId; i < tableSize; i += mapDiff)
        {
            hashTable.setBucketAddress(i, newBucketAddress);
        }
    }

    private void distributeEntries(long filledBucketAddress, long newBucketAddress, int bucketDepth)
    {
        do
        {
            final int bucketFillCount = bucketArray.getBucketFillCount(filledBucketAddress);
            final int splitMask = 1 << bucketDepth;

            int blockOffset = BUCKET_DATA_OFFSET;
            int blocksVisited = 0;

            while (blocksVisited < bucketFillCount)
            {
                final int blockLength = bucketArray.getBlockLength(filledBucketAddress, blockOffset);

                bucketArray.readKey(splitKeyHandler, filledBucketAddress, blockOffset);
                final int keyHashCode = splitKeyHandler.keyHashCode();

                if ((keyHashCode & splitMask) == splitMask)
                {
                    bucketArray.relocateBlock(filledBucketAddress, blockOffset, newBucketAddress);
                }
                else
                {
                    blockOffset += blockLength;
                }

                blocksVisited++;
            }
            filledBucketAddress = bucketArray.getBucketOverflowPointer(filledBucketAddress);
        } while (filledBucketAddress != 0);
    }

    public BucketArray getBucketArray()
    {
        return bucketArray;
    }

    public HashTable getHashTable()
    {
        return hashTable;
    }

    private static class Block
    {
        private long bucketAddress;
        private int blockOffset;

        public void reset()
        {
            bucketAddress = -1;
            blockOffset = -1;
        }

        public boolean wasFound()
        {
            return bucketAddress != -1 && blockOffset != -1;
        }

        public void set(long bucketAddress, int blockOffset)
        {
            this.bucketAddress = bucketAddress;
            this.blockOffset = blockOffset;
        }

        public long getBucketAddress()
        {
            return bucketAddress;
        }

        public int getBlockOffset()
        {
            return blockOffset;
        }
    }
}