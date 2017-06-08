/*-
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

package com.spotify.styx.model.data.deprecated;

import static com.spotify.styx.serialization.Json.OBJECT_MAPPER;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.spotify.styx.model.data.ExecStatus;
import com.spotify.styx.model.data.Execution;
import com.spotify.styx.model.data.Trigger;
import com.spotify.styx.model.deprecated.WorkflowId;
import com.spotify.styx.model.deprecated.WorkflowInstance;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Test;

@Deprecated
public class WorkflowInstanceExecutionDataTest {

  @Test
  public void shouldDeserializeExecutionData() throws Exception {
    String jsonExec00 = executionJson("exec-id-00", "busybox:1.0", "07", "FAILED", Optional.of("Exit code 1"));
    String jsonExec01 = executionJson("exec-id-01", "busybox:1.1", "08", "SUCCESS", Optional.empty());

    String jsonTrigger0 =
        "{"
        + "\"trigger_id\":\"trig-0\","
        + "\"timestamp\":\"" + time("07:55") + "\","
        + "\"complete\":true,"
        + "\"executions\":["
        + jsonExec00 + "," + jsonExec01
        + "]}";

    String jsonExec10 = executionJson("exec-id-10", "busybox:1.2", "09", "FAILED", Optional.of("Exit code 1"));
    String jsonExec11 = executionJson("exec-id-11", "busybox:1.3", "10", "SUCCESS", Optional.empty());

    String jsonTrigger1 =
        "{"
        + "\"trigger_id\":\"trig-1\","
        + "\"timestamp\":\"" + time("09:55") + "\","
        + "\"complete\":false,"
        + "\"executions\":["
        + jsonExec10 + "," + jsonExec11
        + "]}";

    String json =
        "{"
        + "\"workflow_instance\":{"
          + "\"workflow_id\":{"
            + "\"component_id\":\"component1\","
            + "\"endpoint_id\":\"endpoint1\""
          + "},"
          + "\"parameter\":\"2016-08-03T06\""
        + "},"
        + "\"triggers\":["
        + jsonTrigger0 + "," + jsonTrigger1
        + "]}";

    WorkflowInstanceExecutionData
        executionData = OBJECT_MAPPER.readValue(json, WorkflowInstanceExecutionData.class);
    WorkflowInstanceExecutionData
        expected = WorkflowInstanceExecutionData.create(
        WorkflowInstance.create(WorkflowId.create("component1", "endpoint1"), "2016-08-03T06"),
        Arrays.asList(
            Trigger.create(
                "trig-0",
                time("07:55"),
                true,
                Arrays.asList(
                    Execution.create(
                        Optional.of("exec-id-00"),
                        Optional.of("busybox:1.0"),
                        Arrays.asList(
                            ExecStatus.create(Instant.parse("2016-08-03T07:56:03.607Z"), "STARTED", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T07:57:03.607Z"), "RUNNING", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T07:58:03.607Z"), "FAILED", Optional.of("Exit code 1"))
                        )
                    ),
                    Execution.create(
                        Optional.of("exec-id-01"),
                        Optional.of("busybox:1.1"),
                        Arrays.asList(
                            ExecStatus.create(Instant.parse("2016-08-03T08:56:03.607Z"), "STARTED", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T08:57:03.607Z"), "RUNNING", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T08:58:03.607Z"), "SUCCESS", Optional.empty())
                        )
                    )
                )
            ),
            Trigger.create(
                "trig-1",
                time("09:55"),
                false,
                Arrays.asList(
                    Execution.create(
                        Optional.of("exec-id-10"),
                        Optional.of("busybox:1.2"),
                        Arrays.asList(
                            ExecStatus.create(Instant.parse("2016-08-03T09:56:03.607Z"), "STARTED", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T09:57:03.607Z"), "RUNNING", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T09:58:03.607Z"), "FAILED", Optional.of("Exit code 1"))
                        )
                    ),
                    Execution.create(
                        Optional.of("exec-id-11"),
                        Optional.of("busybox:1.3"),
                        Arrays.asList(
                            ExecStatus.create(Instant.parse("2016-08-03T10:56:03.607Z"), "STARTED", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T10:57:03.607Z"), "RUNNING", Optional.empty()),
                            ExecStatus.create(Instant.parse("2016-08-03T10:58:03.607Z"), "SUCCESS", Optional.empty())
                        )
                    )
                )
            )
        )
    );

    assertThat(executionData, is(expected));
  }

  private String statusJson(String time, String status, Optional<String> message) {
    final String baseJson = "{\"timestamp\":\"2016-08-03T" + time + ":03.607Z\", \"status\":\"" + status + "\"";
    return message.map(s -> baseJson + ", \"message\":\"" + s + "\"}").orElseGet(() -> baseJson + "}");
  }

  private String executionJson(String id, String image, String hour, String endStatus, Optional<String> endMessage) {
    String jsonStatus1 = statusJson(hour + ":56", "STARTED", Optional.empty());
    String jsonStatus2 = statusJson(hour + ":57", "RUNNING", Optional.empty());
    String jsonStatus3 = statusJson(hour + ":58", endStatus, endMessage);

    return
        "{"
        + "\"execution_id\":\"" + id + "\","
        + "\"docker_image\":\"" + image + "\","
        + "\"statuses\":["
        + jsonStatus1 + "," + jsonStatus2 + "," + jsonStatus3
        + "]}";
  }

  private static Instant time(String time) {
    return Instant.parse("2016-08-03T" + time + ":03.607Z");
  }
}
