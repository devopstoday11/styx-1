/*
 * -\-\-
 * Spotify Styx Common
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
package com.spotify.styx.model;

import com.google.auto.value.AutoValue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Value representing an execution status change
 */
@AutoValue
public abstract class ExecStatus {

  @JsonProperty("timestamp")
  public abstract Instant timestamp();

  @JsonProperty("status")
  public abstract String status();

  @JsonCreator
  public static ExecStatus create(
      @JsonProperty("timestamp") Instant timestamp,
      @JsonProperty("status") String status
  ) {
    return new AutoValue_ExecStatus(timestamp, status);
  }
}
