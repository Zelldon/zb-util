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
package io.zeebe.test.util;

import java.util.function.Function;

import io.zeebe.util.buffer.BufferReader;
import org.hamcrest.Matcher;

public class BufferReaderMatch<T extends BufferReader>
{
    protected Function<T, Object> propertyExtractor;
    protected Object expectedValue;
    protected Matcher<?> expectedValueMatcher;

    boolean matches(T reader)
    {
        final Object actualValue = propertyExtractor.apply(reader);
        if (expectedValue != null)
        {
            return expectedValue.equals(actualValue);
        }
        else
        {
            return expectedValueMatcher.matches(actualValue);
        }
    }
}
