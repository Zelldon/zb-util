package io.zeebe.hashindex;

import static io.zeebe.hashindex.HashIndexDescriptor.BLOCK_DATA_OFFSET;
import static io.zeebe.hashindex.HashIndexDescriptor.RECORD_KEY_OFFSET;

import io.zeebe.hashindex.types.ByteArrayKeyHandler;
import io.zeebe.hashindex.types.LongValueHandler;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;

public class Bytes2LongHashIndex extends HashIndex<ByteArrayKeyHandler, LongValueHandler>
{
    public Bytes2LongHashIndex(
            final int indexSize,
            final int blockLength,
            final int keyLength)
    {
        super(ByteArrayKeyHandler.class, LongValueHandler.class, indexSize, blockSize(blockLength, keyLength), keyLength);
    }

    private static int blockSize(int records, int keyLength)
    {
        return BLOCK_DATA_OFFSET + (records * (RECORD_KEY_OFFSET + keyLength + BitUtil.SIZE_OF_LONG));
    }

    public long get(byte[] key, long missingValue)
    {
        checkKeyLength(key.length);

        keyHandler.setKey(key);
        valueHandler.theValue = missingValue;
        get();
        return valueHandler.theValue;
    }

    public long get(DirectBuffer buffer, int offset, int length, long missingValue)
    {
        checkKeyLength(length);

        keyHandler.setKey(buffer, offset, length);
        valueHandler.theValue = missingValue;
        get();
        return valueHandler.theValue;
    }

    public boolean put(byte[] key, long value)
    {
        checkKeyLength(key.length);

        keyHandler.setKey(key);
        valueHandler.theValue = value;
        return put();
    }

    public boolean put(DirectBuffer buffer, int offset, int length, long value)
    {
        checkKeyLength(length);

        keyHandler.setKey(buffer, offset, length);
        valueHandler.theValue = value;
        return put();
    }

    public long remove(byte[] key, long missingValue)
    {
        checkKeyLength(key.length);

        keyHandler.setKey(key);
        valueHandler.theValue = missingValue;
        remove();
        return valueHandler.theValue;
    }

    public long remove(DirectBuffer buffer, int offset, int length, long missingValue)
    {
        checkKeyLength(length);

        keyHandler.setKey(buffer, offset, length);
        valueHandler.theValue = missingValue;
        remove();
        return valueHandler.theValue;
    }

    protected void checkKeyLength(int providedKeyLength)
    {
        if (providedKeyLength > keyLength)
        {
            throw new IllegalArgumentException("Illegal byte array length: expected at most " + keyLength + ", got " + providedKeyLength);
        }
    }
}