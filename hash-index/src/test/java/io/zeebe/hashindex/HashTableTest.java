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
package io.zeebe.hashindex;

import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 *
 */
public class HashTableTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Test
    public void shouldResizeHashTable()
    {
        // given
        final HashTable hashTable = new HashTable(1);
        hashTable.setBlockAddress(0, 167);

        // when
        hashTable.resize(2);

        // then
        assertThat(hashTable.getLength()).isEqualTo(2 * SIZE_OF_LONG);
        assertThat(hashTable.getBlockAddress(0)).isEqualTo(167);
    }

    @Test
    public void shouldNotCreateToLargeHashTable()
    {
        // expect
        expectedException.expect(ArithmeticException.class);
        expectedException.expectMessage("integer overflow");

        // when
        new HashTable(1 << 60);
    }

    @Test
    public void shouldNotResizeToLargeHashTable()
    {
        // given
        final HashTable hashTable = new HashTable(1);

        // expect
        expectedException.expect(ArithmeticException.class);
        expectedException.expectMessage("integer overflow");

        // when
        hashTable.resize(1 << 60);
    }

}