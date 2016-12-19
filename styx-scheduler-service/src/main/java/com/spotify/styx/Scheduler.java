/*-
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx;

import static java.util.Collections.emptySet;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingByConcurrent;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import com.google.auto.value.AutoValue;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.Resource;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.state.RunState;
import com.spotify.styx.state.RunState.State;
import com.spotify.styx.state.StateManager;
import com.spotify.styx.state.TimeoutConfig;
import com.spotify.styx.storage.Storage;
import com.spotify.styx.util.Time;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for making decisions on how to make further progress on states in
 * the {@link StateManager}. The general operation is such that each time the {@link #tick()}
 * method is called, the scheduler will inspect all the active states in {@link StateManager} and
 * determine if any of them should receive new events.
 *
 * <p>Currently the scheduler only cares about states that are in the {@link State#QUEUED} state or
 * have timed out according to the {@link TimeoutConfig}).
 *
 * <p>For all Queued states that are eligible for execution, the scheduler will determine which
 * ones to dequeue, while ensuring that the {@link Resource}s associated with each respective
 * {@link Workflow} is not exceeded. It will try to dequeue workflow instances fairly by globally
 * randomizing the dequeue order on each {@link #tick()}.
 */
public class Scheduler {

  private static final Logger LOG = LoggerFactory.getLogger(Scheduler.class);

  private final Time time;
  private final TimeoutConfig ttls;
  private final StateManager stateManager;
  private final WorkflowCache workflowCache;
  private final Storage storage;

  public Scheduler(Time time, TimeoutConfig ttls, StateManager stateManager,
                   WorkflowCache workflowCache, Storage storage) {
    this.time = Objects.requireNonNull(time);
    this.ttls = Objects.requireNonNull(ttls);
    this.stateManager = Objects.requireNonNull(stateManager);
    this.workflowCache = Objects.requireNonNull(workflowCache);
    this.storage = Objects.requireNonNull(storage);
  }

  void tick() {
    final Map<String, Resource> resources;
    try {
      resources = storage.resources().stream().collect(toMap(Resource::id, identity()));
    } catch (IOException e) {
      LOG.warn("Failed to get resource limits", e);
      return;
    }

    final List<InstanceState> activeStates = stateManager.activeStates().entrySet().stream()
        .map(entry -> InstanceState.create(entry.getKey(), entry.getValue()))
        .collect(toList());

    final Set<WorkflowInstance> timedOutInstances =
        activeStates.parallelStream()
            .filter(entry -> hasTimedOut(entry.runState()))
            .map(InstanceState::workflowInstance)
            .collect(toSet());

    final Map<WorkflowId, Set<String>> workflowResourceReferences =
        activeStates.parallelStream()
            .map(InstanceState::workflowInstance)
            .map(WorkflowInstance::workflowId)
            .distinct()
            .collect(toMap(workflowId -> workflowId, this::workflowResources));

    final Map<String, Long> currentResourceUsage =
        activeStates.parallelStream()
            .filter(entry -> !timedOutInstances.contains(entry.workflowInstance()))
            .filter(entry -> entry.runState().state() != State.QUEUED)
            .flatMap(this::pairWithResources)
            .collect(groupingByConcurrent(
                ResourceWithInstance::resource,
                ConcurrentHashMap::new,
                counting()));

    final List<InstanceState> eligibleInstances =
        activeStates.parallelStream()
            .filter(entry -> !timedOutInstances.contains(entry.workflowInstance()))
            .filter(entry -> shouldExecute(entry.runState()))
            .collect(toCollection(Lists::newArrayList));
    Collections.shuffle(eligibleInstances);

    final Consumer<InstanceState> limitAndDequeue = (instance) -> {
      final Set<String> resourceRefs =
          workflowResourceReferences.getOrDefault(instance.workflowInstance().workflowId(),
              emptySet());

      if (resourceRefs.isEmpty()) {
        sendDequeue(instance);
      } else {
        final boolean canRun = resourceRefs.stream()
            .allMatch(resourceId -> {
              if (!resources.containsKey(resourceId)) {
                // todo: send info event, referencing unknown resource
                return false;
              }

              final Resource resource = resources.get(resourceId);
              final long usage = currentResourceUsage.getOrDefault(resourceId, 0L);
              return usage < resource.concurrency();
            });

        if (canRun) {
          resourceRefs.forEach(id -> currentResourceUsage.computeIfAbsent(id, id_ -> 0L));
          resourceRefs.forEach(id -> currentResourceUsage.compute(id, (id_, l) -> l + 1));
          sendDequeue(instance);
        } else {
          // todo: send info event, could not run because resource limit reached
        }
      }
    };

    timedOutInstances.forEach(this::sendTimeout);
    eligibleInstances.forEach(limitAndDequeue);
  }

  private Stream<ResourceWithInstance> pairWithResources(InstanceState instanceState) {
    final WorkflowId workflowId = instanceState.workflowInstance().workflowId();
    final Optional<Workflow> workflowOpt = workflowCache.workflow(workflowId);
    if (!workflowOpt.isPresent()) {
      return Stream.empty();
    }

    final List<String> referencedResources = workflowOpt.get().schedule().resources();

    return referencedResources.stream()
        .map(resource -> ResourceWithInstance.create(resource, instanceState));
  }

  private Set<String> workflowResources(WorkflowId workflowId) {
    final Optional<Workflow> workflowOpt = workflowCache.workflow(workflowId);
    if (!workflowOpt.isPresent()) {
      return emptySet();
    }

    return Sets.newHashSet(workflowOpt.get().schedule().resources());
  }

  private boolean shouldExecute(RunState runState) {
    if (runState.state() != State.QUEUED) {
      return false;
    }

    final Instant now = time.get();
    final Instant deadline = Instant
        .ofEpochMilli(runState.timestamp())
        .plusMillis(runState.data().retryDelayMillis().orElse(0L));

    return !deadline.isAfter(now);
  }

  private void sendDequeue(InstanceState instanceState) {
    final WorkflowInstance workflowInstance = instanceState.workflowInstance();
    final RunState state = instanceState.runState();

    if (state.data().tries() == 0) {
      LOG.info("Triggering {}", workflowInstance.toKey());
    } else {
      LOG.info("{} executing retry #{}", workflowInstance.toKey(), state.data().tries());
    }
    stateManager.receiveIgnoreClosed(Event.dequeue(workflowInstance));
  }

  private boolean hasTimedOut(RunState runState) {
    if (runState.state().isTerminal()) {
      return false;
    }

    final Instant now = time.get();
    final Instant deadline = Instant
        .ofEpochMilli(runState.timestamp())
        .plus(ttls.ttlOf(runState.state()));

    return !deadline.isAfter(now);
  }

  private void sendTimeout(WorkflowInstance workflowInstance) {
    LOG.info("Found stale state, issuing timeout for {}", workflowInstance);
    stateManager.receiveIgnoreClosed(Event.timeout(workflowInstance));
  }

  @AutoValue
  abstract static class InstanceState {
    abstract WorkflowInstance workflowInstance();
    abstract RunState runState();

    static InstanceState create(WorkflowInstance workflowInstance, RunState runState) {
      return new AutoValue_Scheduler_InstanceState(workflowInstance, runState);
    }
  }

  @AutoValue
  abstract static class ResourceWithInstance {
    abstract String resource();
    abstract InstanceState instanceState();

    static ResourceWithInstance create(String resource, InstanceState instanceState) {
      return new AutoValue_Scheduler_ResourceWithInstance(resource, instanceState);
    }
  }
}