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

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.rules.ExternalResource;

/**
 * Saves you some {@link After} methods by closing {@link AutoCloseable}
 * implementations after the test in LIFO fashion.
 *
 * @author Lindhauer
 */
public class AutoCloseableRule extends ExternalResource
{

    List<AutoCloseable> thingsToClose = new ArrayList<>();

    public void manage(AutoCloseable closeable)
    {
        thingsToClose.add(closeable);
    }

    @Override
    protected void after()
    {
        final int size = thingsToClose.size();
        for (int i = size - 1; i >= 0; i--)
        {
            try
            {
                thingsToClose.remove(i).close();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

}
