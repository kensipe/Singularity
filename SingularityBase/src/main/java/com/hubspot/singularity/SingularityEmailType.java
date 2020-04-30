package com.hubspot.singularity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema
public enum SingularityEmailType {
  TASK_LOST("#D9534F"),
  TASK_KILLED("#777777"),
  TASK_FINISHED_SCHEDULED("#5CB85C"),
  TASK_FINISHED_LONG_RUNNING("#5CB85C"),
  TASK_FINISHED_ON_DEMAND("#5CB85C"),
  TASK_FINISHED_RUN_ONCE("#5CB85C"),
  TASK_FAILED("#D9534F"),
  TASK_SCHEDULED_OVERDUE_TO_FINISH("#D9534F"),
  TASK_KILLED_DECOMISSIONED("#777777"),
  TASK_KILLED_UNHEALTHY("#D9534F"),
  REQUEST_IN_COOLDOWN("#D9534F"),
  REPLACEMENT_TASKS_FAILING("#D9534F"),
  SINGULARITY_ABORTING("#D9534F"),
  REQUEST_REMOVED("#777777"),
  REQUEST_PAUSED("#5BC0DE"),
  REQUEST_UNPAUSED("#5BC0DE"),
  REQUEST_SCALED("#5BC0DE"),
  TASK_FAILED_DECOMISSIONED("#D9534F"),
  DISASTER_DETECTED("#D9534F");

  private final String color;

  SingularityEmailType(String color) {
    this.color = color;
  }

  public String getColor() {
    return this.color;
  }
}
