package io.zeebe.hashindex.types;

import static org.agrona.BufferUtil.ARRAY_BASE_OFFSET;

import java.util.Arrays;

import io.zeebe.hashindex.IndexKeyHandler;
import org.agrona.DirectBuffer;
import org.agrona.UnsafeAccess;
import sun.misc.Unsafe;

@SuppressWarnings("restriction")
public class ByteArrayKeyHandler implements IndexKeyHandler
{
    private static final Unsafe UNSAFE = UnsafeAccess.UNSAFE;

    public byte[] theKey;
    public int keyLength;

    public void setKey(byte[] key)
    {
        System.arraycopy(key, 0, this.theKey, 0, key.length);
        if (key.length < this.keyLength)
        {
            Arrays.fill(this.theKey, key.length, this.keyLength, (byte) 0);
        }
    }

    public void setKey(DirectBuffer buffer, int offset, int length)
    {
        buffer.getBytes(offset, this.theKey, 0, length);
        if (length < this.keyLength)
        {
            Arrays.fill(this.theKey, length, this.keyLength, (byte) 0);
        }
    }

    @Override
    public void setKeyLength(int keyLength)
    {
        this.keyLength = keyLength;
        this.theKey = new byte[keyLength];
    }

    @Override
    public int getKeyLength()
    {
        return keyLength;
    }

    @Override
    public int keyHashCode()
    {
        int result = 1;

        for (int i = 0; i < keyLength; i++)
        {
            result = 31 * result + theKey[i];
        }

        return result;
    }

    @Override
    public void readKey(long keyAddr)
    {
        UNSAFE.copyMemory(null, keyAddr, theKey, ARRAY_BASE_OFFSET, keyLength);
    }

    @Override
    public void writeKey(long keyAddr)
    {
        UNSAFE.copyMemory(theKey, ARRAY_BASE_OFFSET, null, keyAddr, keyLength);
    }

    @Override
    public boolean keyEquals(long keyAddr)
    {
        final long thisOffset = ARRAY_BASE_OFFSET;
        final long thatOffset = keyAddr;

        for (int i = 0; i < keyLength; i++)
        {
            if (UNSAFE.getByte(theKey, thisOffset + i) != UNSAFE.getByte(null, thatOffset + i))
            {
                return false;
            }
        }

        return true;
    }

}