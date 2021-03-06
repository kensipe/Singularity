package com.hubspot.singularity;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.hubspot.singularity.SingularityRequestHistory.RequestHistoryType;
import com.hubspot.singularity.api.SingularityDeleteRequestRequest;
import com.hubspot.singularity.api.SingularityRunNowRequest;
import com.hubspot.singularity.api.SingularityScaleRequest;
import com.hubspot.singularity.config.HistoryPurgingConfiguration;
import com.hubspot.singularity.data.MetadataManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.history.HistoryManager;
import com.hubspot.singularity.data.history.SingularityHistoryPurger;
import com.hubspot.singularity.data.history.SingularityRequestHistoryPersister;
import com.hubspot.singularity.data.history.SingularityTaskHistoryPersister;
import com.hubspot.singularity.data.history.TaskHistoryHelper;
import com.hubspot.singularity.data.transcoders.Transcoder;
import com.hubspot.singularity.mesos.SingularitySchedulerLock;
import com.hubspot.singularity.scheduler.SingularitySchedulerTestBase;
import java.io.IOException;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.mesos.v1.Protos;
import org.apache.mesos.v1.Protos.TaskState;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SingularityHistoryTest extends SingularitySchedulerTestBase {
  @Inject
  protected Provider<Jdbi> dbiProvider;

  @Inject
  protected HistoryManager historyManager;

  @Inject
  protected MetadataManager metadataManager;

  @Inject
  protected SingularityTaskHistoryPersister taskHistoryPersister;

  @Inject
  protected SingularityRequestHistoryPersister requestHistoryPersister;

  @Inject
  protected SingularityTestAuthenticator testAuthenticator;

  @Inject
  protected TaskHistoryHelper taskHistoryHelper;

  @Inject
  private Transcoder<SingularityTaskHistory> taskHistoryTranscoder;

  @Inject
  private Transcoder<SingularityDeployHistory> deployHistoryTranscoder;

  @Inject
  protected ObjectMapper objectMapper;

  @Inject
  protected SingularitySchedulerLock lock;

  public SingularityHistoryTest() {
    super(true);
  }

  private SingularityTaskHistory buildTask(long launchTime) {
    SingularityTask task = prepTask(request, firstDeploy, launchTime, 1);

    return new SingularityTaskHistory(
      Collections.singletonList(
        new SingularityTaskHistoryUpdate(
          task.getTaskId(),
          launchTime,
          ExtendedTaskState.TASK_LAUNCHED,
          Optional.empty(),
          Optional.empty()
        )
      ),
      Optional.<String>empty(),
      Optional.<String>empty(),
      null,
      task,
      null,
      null,
      null
    );
  }

  private void saveTasks(int num, long launchTime) {
    for (int i = 0; i < num; i++) {
      SingularityTaskHistory taskHistory = buildTask(launchTime + i);

      historyManager.saveTaskHistory(taskHistory);
    }
  }

  private List<SingularityTaskIdHistory> getTaskHistoryForRequest(
    String requestId,
    int start,
    int limit
  ) {
    return historyManager.getTaskIdHistory(
      Optional.of(requestId),
      Optional.<String>empty(),
      Optional.<String>empty(),
      Optional.<String>empty(),
      Optional.<ExtendedTaskState>empty(),
      Optional.<Long>empty(),
      Optional.<Long>empty(),
      Optional.<Long>empty(),
      Optional.<Long>empty(),
      Optional.<OrderDirection>empty(),
      Optional.of(start),
      limit
    );
  }

  @Test
  public void testHistoryDoesntHaveActiveTasks() {
    initRequest();
    initFirstDeploy();

    SingularityTask taskOne = launchTask(
      request,
      firstDeploy,
      1L,
      10L,
      1,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskTwo = launchTask(
      request,
      firstDeploy,
      2l,
      10L,
      2,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskThree = launchTask(
      request,
      firstDeploy,
      3l,
      10L,
      3,
      TaskState.TASK_RUNNING,
      true
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        10
      ),
      0
    );
  }

  @Test
  public void historyUpdaterTest() {
    initRequest();
    initFirstDeploy();

    HistoryPurgingConfiguration historyPurgingConfiguration = new HistoryPurgingConfiguration();
    historyPurgingConfiguration.setEnabled(true);
    historyPurgingConfiguration.setDeleteTaskHistoryBytesAfterDays(1);

    SingularityTaskHistory taskHistory = buildTask(
      System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)
    );

    historyManager.saveTaskHistory(taskHistory);

    Assertions.assertTrue(
      historyManager
        .getTaskHistory(taskHistory.getTask().getTaskId().getId())
        .get()
        .getTask() !=
      null
    );

    Assertions.assertEquals(1, getTaskHistoryForRequest(requestId, 0, 100).size());

    SingularityHistoryPurger purger = new SingularityHistoryPurger(
      historyPurgingConfiguration,
      historyManager,
      taskManager,
      deployManager,
      requestManager,
      metadataManager,
      lock
    );

    purger.runActionOnPoll();

    Assertions.assertEquals(1, getTaskHistoryForRequest(requestId, 0, 100).size());

    Assertions.assertTrue(
      !historyManager
        .getTaskHistory(taskHistory.getTask().getTaskId().getId())
        .isPresent()
    );
  }

  @Test
  public void historyPurgerTest() {
    initRequest();
    initFirstDeploy();

    saveTasks(3, System.currentTimeMillis());

    Assertions.assertEquals(3, getTaskHistoryForRequest(requestId, 0, 10).size());

    HistoryPurgingConfiguration historyPurgingConfiguration = new HistoryPurgingConfiguration();
    historyPurgingConfiguration.setEnabled(true);
    historyPurgingConfiguration.setDeleteTaskHistoryAfterDays(10);

    SingularityHistoryPurger purger = new SingularityHistoryPurger(
      historyPurgingConfiguration,
      historyManager,
      taskManager,
      deployManager,
      requestManager,
      metadataManager,
      lock
    );

    purger.runActionOnPoll();

    Assertions.assertEquals(3, getTaskHistoryForRequest(requestId, 0, 10).size());

    historyPurgingConfiguration.setDeleteTaskHistoryAfterTasksPerRequest(1);

    purger.runActionOnPoll();

    Assertions.assertEquals(1, getTaskHistoryForRequest(requestId, 0, 10).size());

    historyPurgingConfiguration.setDeleteTaskHistoryAfterTasksPerRequest(25);
    historyPurgingConfiguration.setDeleteTaskHistoryAfterDays(100);

    purger.runActionOnPoll();

    Assertions.assertEquals(1, getTaskHistoryForRequest(requestId, 0, 10).size());

    saveTasks(10, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(200));

    purger.runActionOnPoll();

    Assertions.assertEquals(1, getTaskHistoryForRequest(requestId, 0, 10).size());
  }

  @Test
  public void testRunId() {
    initScheduledRequest();
    initFirstDeploy();

    String runId = "my-run-id";

    SingularityPendingRequestParent parent = requestResource.scheduleImmediately(
      singularityUser,
      requestId,
      new SingularityRunNowRequestBuilder().setRunId(runId).build()
    );

    Assertions.assertEquals(runId, parent.getPendingRequest().getRunId().get());

    scheduler.drainPendingQueue();

    resourceOffers();

    Assertions.assertEquals(
      runId,
      taskManager
        .getActiveTasks()
        .get(0)
        .getTaskRequest()
        .getPendingTask()
        .getRunId()
        .get()
    );

    SingularityTaskId taskId = taskManager.getActiveTaskIds().get(0);

    statusUpdate(taskManager.getTask(taskId).get(), TaskState.TASK_FINISHED);

    taskHistoryPersister.runActionOnPoll();

    Assertions.assertEquals(
      runId,
      historyManager
        .getTaskHistory(taskId.getId())
        .get()
        .getTask()
        .getTaskRequest()
        .getPendingTask()
        .getRunId()
        .get()
    );
    Assertions.assertEquals(
      runId,
      getTaskHistoryForRequest(requestId, 0, 10).get(0).getRunId().get()
    );

    parent =
      requestResource.scheduleImmediately(
        singularityUser,
        requestId,
        ((SingularityRunNowRequest) null)
      );

    Assertions.assertTrue(parent.getPendingRequest().getRunId().isPresent());
  }

  @Test
  public void testPersisterRaceCondition() {
    final TaskManager taskManagerSpy = spy(taskManager);
    final TaskHistoryHelper taskHistoryHelperWithMockedTaskManager = new TaskHistoryHelper(
      taskManagerSpy,
      historyManager,
      requestManager,
      configuration
    );

    initScheduledRequest();
    initFirstDeploy();

    requestResource.scheduleImmediately(singularityUser, requestId, null);

    scheduler.drainPendingQueue();

    resourceOffers();

    final SingularityTaskId taskId = taskManager.getActiveTaskIds().get(0);

    statusUpdate(
      taskManager.getTask(taskId).get(),
      Protos.TaskState.TASK_FINISHED,
      Optional.of(System.currentTimeMillis())
    );

    // persist inactive task(s)
    taskHistoryPersister.runActionOnPoll();

    // mimic the persister race condition by overriding the inactive task IDs in ZK to the persisted task ID
    doReturn(Arrays.asList(taskId))
      .when(taskManagerSpy)
      .getInactiveTaskIdsForRequest(eq(requestId));

    // assert that the history works, but more importantly, that we don't NPE
    Assertions.assertEquals(
      1,
      taskHistoryHelperWithMockedTaskManager
        .getBlendedHistory(new SingularityTaskHistoryQuery(requestId), 0, 5)
        .size()
    );
  }

  @Test
  public void testTaskSearchByRequest() {
    initOnDemandRequest();
    initFirstDeploy();

    SingularityTask taskOne = launchTask(
      request,
      firstDeploy,
      10000L,
      10L,
      1,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskThree = launchTask(
      request,
      firstDeploy,
      20000l,
      10L,
      2,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskFive = launchTask(
      request,
      firstDeploy,
      30000l,
      10L,
      3,
      TaskState.TASK_RUNNING,
      true
    );

    requestId = "test-request-2";

    initOnDemandRequest();
    initFirstDeploy();

    SingularityTask taskTwo = launchTask(
      request,
      firstDeploy,
      15000L,
      10L,
      1,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskFour = launchTask(
      request,
      firstDeploy,
      25000l,
      10L,
      2,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskSix = launchTask(
      request,
      firstDeploy,
      35000l,
      10L,
      3,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskSeven = launchTask(
      request,
      firstDeploy,
      70000l,
      10L,
      7,
      TaskState.TASK_RUNNING,
      true
    );

    statusUpdate(taskOne, TaskState.TASK_FAILED);
    statusUpdate(taskTwo, TaskState.TASK_FINISHED);
    statusUpdate(taskSix, TaskState.TASK_KILLED);
    statusUpdate(taskFour, TaskState.TASK_LOST);

    taskHistoryPersister.runActionOnPoll();

    statusUpdate(taskThree, TaskState.TASK_FAILED);
    statusUpdate(taskFive, TaskState.TASK_FINISHED);
    statusUpdate(taskSeven, TaskState.TASK_KILLED);

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      3,
      taskSeven,
      taskFive,
      taskThree
    );

    taskHistoryPersister.runActionOnPoll();

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.of(70000L),
          Optional.of(20000L),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      3,
      taskSix,
      taskFour,
      taskFive
    );
  }

  @Test
  public void testTaskSearchQueryInZkOnly() {
    initOnDemandRequest();
    initFirstDeploy();

    SingularityTask taskOne = launchTask(
      request,
      firstDeploy,
      1L,
      10L,
      1,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskTwo = launchTask(
      request,
      firstDeploy,
      2L,
      10L,
      2,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskThree = launchTask(
      request,
      firstDeploy,
      3L,
      10L,
      3,
      TaskState.TASK_RUNNING,
      true
    );

    SingularityDeployMarker marker = initSecondDeploy();
    finishDeploy(marker, secondDeploy);

    SingularityTask taskFour = launchTask(
      request,
      secondDeploy,
      4L,
      10L,
      4,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskFive = launchTask(
      request,
      secondDeploy,
      5L,
      10L,
      5,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskSix = launchTask(
      request,
      secondDeploy,
      6L,
      10L,
      6,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskSeven = launchTask(
      request,
      secondDeploy,
      7L,
      10L,
      7,
      TaskState.TASK_RUNNING,
      true
    );

    statusUpdate(taskOne, TaskState.TASK_FAILED, Optional.of(20000L));
    statusUpdate(taskTwo, TaskState.TASK_FINISHED, Optional.of(21000L));
    statusUpdate(taskSix, TaskState.TASK_KILLED, Optional.of(22000L));
    statusUpdate(taskFour, TaskState.TASK_LOST, Optional.of(23000L));

    statusUpdate(taskThree, TaskState.TASK_FAILED, Optional.of(24000L));
    statusUpdate(taskFive, TaskState.TASK_FINISHED, Optional.of(25000L));
    statusUpdate(taskSeven, TaskState.TASK_KILLED, Optional.of(26000L));

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      3,
      taskSeven,
      taskFive,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.of(secondDeployId),
          Optional.<String>empty(),
          Optional.of("host4"),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      1,
      taskFour
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.of(firstDeployId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.of(ExtendedTaskState.TASK_FAILED),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      2,
      taskOne,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.of(ExtendedTaskState.TASK_LOST),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      1,
      taskFour
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.of(7L),
          Optional.of(2L),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      3,
      taskSix,
      taskFour,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.of(7L),
          Optional.of(2L),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        1,
        3
      ),
      3,
      taskFour,
      taskThree,
      taskFive
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(26000L),
          Optional.of(21000L),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      3,
      taskSix,
      taskFour,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(26000L),
          Optional.of(21000L),
          Optional.of(OrderDirection.ASC)
        ),
        1,
        3
      ),
      3,
      taskFour,
      taskThree,
      taskFive
    );
  }

  @Test
  public void testTaskSearchQueryBlended() {
    initOnDemandRequest();
    initFirstDeploy();

    SingularityTask taskOne = launchTask(
      request,
      firstDeploy,
      10000L,
      10L,
      1,
      TaskState.TASK_RUNNING,
      true,
      Optional.of("test-run-id-1"),
      Optional.empty()
    );
    SingularityTask taskTwo = launchTask(
      request,
      firstDeploy,
      20000L,
      10L,
      2,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskThree = launchTask(
      request,
      firstDeploy,
      30000L,
      10L,
      3,
      TaskState.TASK_RUNNING,
      true
    );

    SingularityDeployMarker marker = initSecondDeploy();
    finishDeploy(marker, secondDeploy);

    SingularityTask taskFour = launchTask(
      request,
      secondDeploy,
      40000L,
      10L,
      4,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskFive = launchTask(
      request,
      secondDeploy,
      50000L,
      10L,
      5,
      TaskState.TASK_RUNNING,
      true,
      Optional.of("test-run-id-5"),
      Optional.empty()
    );
    SingularityTask taskSix = launchTask(
      request,
      secondDeploy,
      60000L,
      10L,
      6,
      TaskState.TASK_RUNNING,
      true
    );
    SingularityTask taskSeven = launchTask(
      request,
      secondDeploy,
      70000L,
      10L,
      7,
      TaskState.TASK_RUNNING,
      true
    );

    statusUpdate(taskOne, TaskState.TASK_FAILED, Optional.of(80000L));
    statusUpdate(taskTwo, TaskState.TASK_FINISHED, Optional.of(90000L));
    statusUpdate(taskSix, TaskState.TASK_KILLED, Optional.of(100000L));
    statusUpdate(taskFour, TaskState.TASK_LOST, Optional.of(110000L));

    taskHistoryPersister.runActionOnPoll();

    statusUpdate(taskThree, TaskState.TASK_FAILED, Optional.of(120000L));
    statusUpdate(taskFive, TaskState.TASK_FINISHED, Optional.of(130000L));
    statusUpdate(taskSeven, TaskState.TASK_KILLED, Optional.of(140000L));

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.of(firstDeployId),
          Optional.<String>empty(),
          Optional.of("host1"),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      1,
      taskOne
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.of(firstDeployId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.of(ExtendedTaskState.TASK_FAILED),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      2,
      taskOne,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.of(ExtendedTaskState.TASK_LOST),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      1,
      taskFour
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.of(70000L),
          Optional.of(20000L),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.DESC)
        ),
        0,
        3
      ),
      3,
      taskFive,
      taskThree,
      taskFour
    );

    taskHistoryPersister.runActionOnPoll();

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      3,
      taskSeven,
      taskFive,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        2,
        1
      ),
      1,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.of(secondDeployId),
          Optional.<String>empty(),
          Optional.of("host4"),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      1,
      taskFour
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.of(firstDeployId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.of(ExtendedTaskState.TASK_FAILED),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      2,
      taskOne,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.of(ExtendedTaskState.TASK_LOST),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<OrderDirection>empty()
        ),
        0,
        3
      ),
      1,
      taskFour
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.of(70000L),
          Optional.of(20000L),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      3,
      taskSix,
      taskFour,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.of(70000L),
          Optional.of(20000L),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        1,
        3
      ),
      3,
      taskFour,
      taskThree,
      taskFive
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(140000L),
          Optional.of(90000L),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        3
      ),
      3,
      taskSix,
      taskFour,
      taskThree
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(140000L),
          Optional.of(90000L),
          Optional.of(OrderDirection.ASC)
        ),
        1,
        3
      ),
      3,
      taskFour,
      taskThree,
      taskFive
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.of("test-run-id-1"),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        1
      ),
      1,
      taskOne
    );

    match(
      taskHistoryHelper.getBlendedHistory(
        new SingularityTaskHistoryQuery(
          Optional.of(requestId),
          Optional.<String>empty(),
          Optional.of("test-run-id-5"),
          Optional.<String>empty(),
          Optional.<ExtendedTaskState>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.<Long>empty(),
          Optional.of(OrderDirection.ASC)
        ),
        0,
        1
      ),
      1,
      taskFive
    );
  }

  private void match(
    List<SingularityTaskIdHistory> history,
    int num,
    SingularityTask... tasks
  ) {
    Assertions.assertEquals(num, history.size());

    for (int i = 0; i < tasks.length; i++) {
      SingularityTaskIdHistory idHistory = history.get(i);
      SingularityTask task = tasks[i];

      Assertions.assertEquals(idHistory.getTaskId(), task.getTaskId());
    }
  }

  @Test
  public void testMessage() {
    initRequest();

    String msg = null;
    for (int i = 0; i < 300; i++) {
      msg = msg + i;
    }

    requestResource.scale(
      requestId,
      new SingularityScaleRequest(
        Optional.of(2),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(msg),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
      ),
      singularityUser
    );
    requestResource.deleteRequest(
      requestId,
      Optional.of(
        new SingularityDeleteRequestRequest(
          Optional.of("a msg"),
          Optional.empty(),
          Optional.empty()
        )
      ),
      singularityUser
    );

    cleaner.drainCleanupQueue();

    requestHistoryPersister.runActionOnPoll();

    List<SingularityRequestHistory> history = historyManager.getRequestHistory(
      requestId,
      Optional.of(OrderDirection.DESC),
      0,
      100
    );

    Assertions.assertEquals(4, history.size());

    for (SingularityRequestHistory historyItem : history) {
      if (historyItem.getEventType() == RequestHistoryType.DELETED) {
        Assertions.assertEquals("a msg", historyItem.getMessage().get());
      } else if (historyItem.getEventType() == RequestHistoryType.SCALED) {
        Assertions.assertEquals(280, historyItem.getMessage().get().length());
      } else if (historyItem.getEventType() == RequestHistoryType.DELETING) {
        Assertions.assertEquals("a msg", historyItem.getMessage().get());
      } else {
        Assertions.assertTrue(!historyItem.getMessage().isPresent());
      }
    }
  }

  @Test
  public void testDuplicatePersist() {
    initRequest();
    requestResource.scale(
      requestId,
      new SingularityScaleRequest(
        Optional.of(2),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of("msg"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
      ),
      singularityUser
    );
    requestHistoryPersister.runActionOnPoll();
    SingularityRequestHistory history = historyManager
      .getRequestHistory(requestId, Optional.of(OrderDirection.DESC), 0, 100)
      .get(0);
    historyManager.saveRequestHistoryUpdate(history);
    // Should not throw exception
    historyManager.saveRequestHistoryUpdate(history);
  }

  @Test
  public void testMigrateToJson() throws IOException {
    try (Handle handle = dbiProvider.get().open()) {
      initRequest();
      initFirstDeploy();
      SingularityRequest request = new SingularityRequestBuilder(
        "test",
        RequestType.ON_DEMAND
      )
      .build();
      SingularityRequestHistory requestHistory = new SingularityRequestHistory(
        System.currentTimeMillis(),
        Optional.empty(),
        RequestHistoryType.CREATED,
        request,
        Optional.empty()
      );
      handle
        .createUpdate(
          "INSERT INTO requestHistory (requestId, request, createdAt, requestState, user, message) VALUES (:requestId, :request, :createdAt, :requestState, :user, :message)"
        )
        .bind("requestId", request.getId())
        .bind("request", objectMapper.writeValueAsBytes(request))
        .bind("createdAt", new Date(requestHistory.getCreatedAt()))
        .bind("requestState", requestHistory.getEventType())
        .bindNull("user", Types.VARCHAR)
        .bindNull("message", Types.VARCHAR)
        .defineNamedBindings()
        .execute();

      SingularityDeployHistory deployHistory = new SingularityDeployHistory(
        Optional.empty(),
        new SingularityDeployMarker(
          "test",
          "testd",
          System.currentTimeMillis(),
          Optional.empty(),
          Optional.empty()
        ),
        Optional.of(new SingularityDeployBuilder("test", "testd").build()),
        Optional.empty()
      );
      handle
        .createUpdate(
          "INSERT INTO deployHistory (requestId, deployId, createdAt, user, message, deployStateAt, deployState, bytes) VALUES (:requestId, :deployId, :createdAt, :user, :message, :deployStateAt, :deployState, :bytes)"
        )
        .bind("requestId", "test")
        .bind("deployId", "testd")
        .bind("createdAt", new Date(deployHistory.getDeployMarker().getTimestamp()))
        .bindNull("user", Types.VARCHAR)
        .bindNull("message", Types.VARCHAR)
        .bind("deployStateAt", new Date(deployHistory.getDeployMarker().getTimestamp()))
        .bind("deployState", DeployState.WAITING.toString())
        .bind("bytes", deployHistoryTranscoder.toBytes(deployHistory))
        .defineNamedBindings()
        .execute();

      SingularityTaskHistory taskHistory = buildTask(System.currentTimeMillis());
      handle
        .createUpdate(
          "INSERT INTO taskHistory (requestId, taskId, bytes, updatedAt, lastTaskStatus, runId, deployId, host, startedAt, purged) VALUES (:requestId, :taskId, :bytes, :updatedAt, :lastTaskStatus, :runId, :deployId, :host, :startedAt, false)"
        )
        .bind("requestId", taskHistory.getTask().getTaskId().getRequestId())
        .bind("taskId", taskHistory.getTask().getTaskId().getId())
        .bind("bytes", taskHistoryTranscoder.toBytes(taskHistory))
        .bind("updatedAt", new Date(System.currentTimeMillis()))
        .bind("lastTaskStatus", ExtendedTaskState.TASK_LAUNCHED.toString())
        .bindNull("runId", Types.VARCHAR)
        .bind("deployId", taskHistory.getTask().getTaskId().getDeployId())
        .bind("host", taskHistory.getTask().getHostname())
        .bind("startedAt", new Date(System.currentTimeMillis()))
        .bind("purged", false)
        .defineNamedBindings()
        .execute();

      configuration.setSqlFallBackToBytesFields(true);
      SingularityRequestHistory requestHistoryBefore = historyManager
        .getRequestHistory(request.getId(), Optional.empty(), 0, 1)
        .get(0);
      Assertions.assertNotNull(requestHistoryBefore);
      Assertions.assertEquals(
        requestHistory.getRequest(),
        requestHistoryBefore.getRequest()
      );

      SingularityDeployHistory deployHistoryBefore = historyManager
        .getDeployHistory("test", "testd")
        .get();
      Assertions.assertEquals(
        deployHistory.getDeploy().get(),
        deployHistoryBefore.getDeploy().get()
      );

      SingularityTaskHistory taskHistoryBefore = historyManager
        .getTaskHistory(taskHistory.getTask().getTaskId().getId())
        .get();
      Assertions.assertEquals(taskHistory, taskHistoryBefore);

      historyManager.startHistoryBackfill(1).join();
      configuration.setSqlFallBackToBytesFields(false);

      SingularityRequestHistory requestHistoryAfter = historyManager
        .getRequestHistory(request.getId(), Optional.empty(), 0, 1)
        .get(0);
      Assertions.assertNotNull(requestHistoryAfter);
      Assertions.assertEquals(
        requestHistory.getRequest(),
        requestHistoryAfter.getRequest()
      );

      SingularityDeployHistory deployHistoryAfter = historyManager
        .getDeployHistory("test", "testd")
        .get();
      Assertions.assertEquals(deployHistory, deployHistoryAfter);

      SingularityTaskHistory taskHistoryAfter = historyManager
        .getTaskHistory(taskHistory.getTask().getTaskId().getId())
        .get();
      Assertions.assertEquals(taskHistory, taskHistoryAfter);
    } finally {
      configuration.setSqlFallBackToBytesFields(false);
    }
  }
}
