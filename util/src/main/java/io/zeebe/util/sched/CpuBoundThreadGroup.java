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
package io.zeebe.util.sched;

import io.zeebe.util.sched.ActorScheduler.ActorSchedulerBuilder;

/**
 * Thread group for the non-blocking, CPU bound, tasks.
 */
public class CpuBoundThreadGroup extends ActorThreadGroup
{
    public CpuBoundThreadGroup(ActorSchedulerBuilder builder)
    {
        super("zb-actors", builder.getCpuBoundActorThreadCount(), builder.getPriorityQuotas().length, builder);
    }

    @Override
    protected TaskScheduler createTaskScheduler(MultiLevelWorkstealingGroup tasks, ActorSchedulerBuilder builder)
    {
        return new PriorityScheduler(tasks::getNextTask, builder.getPriorityQuotas());
    }

    @Override
    protected int getLevel(ActorTask actorTask)
    {
        return actorTask.getPriority();
    }
}