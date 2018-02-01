package com.hubspot.singularity.mesos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.apache.mesos.v1.Protos;
import org.apache.mesos.v1.Protos.AgentID;
import org.apache.mesos.v1.Protos.ExecutorID;
import org.apache.mesos.v1.Protos.InverseOffer;
import org.apache.mesos.v1.Protos.MasterInfo;
import org.apache.mesos.v1.Protos.Offer;
import org.apache.mesos.v1.Protos.OfferID;
import org.apache.mesos.v1.Protos.TaskID;
import org.apache.mesos.v1.Protos.TaskStatus;
import org.apache.mesos.v1.scheduler.Protos.Event;
import org.apache.mesos.v1.scheduler.Protos.Event.Failure;
import org.apache.mesos.v1.scheduler.Protos.Event.Message;
import org.apache.mesos.v1.scheduler.Protos.Event.Subscribed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.RequestCleanupType;
import com.hubspot.singularity.SingularityAbort;
import com.hubspot.singularity.SingularityAbort.AbortReason;
import com.hubspot.singularity.SingularityAction;
import com.hubspot.singularity.SingularityKilledTaskIdRecord;
import com.hubspot.singularity.SingularityMainModule;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskDestroyFrameworkMessage;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.TaskCleanupType;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DisasterManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.transcoders.Transcoder;
import com.hubspot.singularity.helpers.MesosProtosUtils;
import com.hubspot.singularity.helpers.MesosUtils;
import com.hubspot.singularity.mesos.SingularitySlaveAndRackManager.CheckResult;
import com.hubspot.singularity.scheduler.SingularityLeaderCacheCoordinator;
import com.hubspot.singularity.sentry.SingularityExceptionNotifier;

import io.netty.handler.codec.PrematureChannelClosureException;

@Singleton
public class SingularityMesosSchedulerImpl extends SingularityMesosScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityMesosScheduler.class);
  private static final String SCHEDULER_API_URL_FORMAT = "http://%s/api/v1/scheduler";

  private final SingularityExceptionNotifier exceptionNotifier;

  private final SingularityStartup startup;
  private final SingularityAbort abort;
  private final SingularityLeaderCacheCoordinator leaderCacheCoordinator;
  private final SingularityMesosFrameworkMessageHandler messageHandler;
  private final SingularitySlaveAndRackManager slaveAndRackManager;
  private final DisasterManager disasterManager;
  private final OfferCache offerCache;
  private final SingularityMesosOfferScheduler offerScheduler;
  private final SingularityMesosStatusUpdateHandler statusUpdateHandler;
  private final SingularityMesosSchedulerClient mesosSchedulerClient;
  private final boolean offerCacheEnabled;
  private final boolean delayWhenStatusUpdateDeltaTooLarge;
  private final long delayWhenDeltaOverMs;
  private final AtomicLong statusUpdateDeltaAvg;
  private final SingularityConfiguration configuration;
  private final TaskManager taskManager;
  private final Transcoder<SingularityTaskDestroyFrameworkMessage> transcoder;
  private final SingularitySchedulerLock lock;

  private volatile SchedulerState state;
  private Optional<Long> lastOfferTimestamp = Optional.absent();

  private final AtomicReference<MasterInfo> masterInfo = new AtomicReference<>();
  private final List<TaskStatus> queuedUpdates;

  @Inject
  SingularityMesosSchedulerImpl(SingularitySchedulerLock lock,
                                SingularityExceptionNotifier exceptionNotifier,
                                SingularityStartup startup,
                                SingularityLeaderCacheCoordinator leaderCacheCoordinator,
                                SingularityAbort abort,
                                SingularityMesosFrameworkMessageHandler messageHandler,
                                SingularitySlaveAndRackManager slaveAndRackManager,
                                OfferCache offerCache,
                                SingularityMesosOfferScheduler offerScheduler,
                                SingularityMesosStatusUpdateHandler statusUpdateHandler,
                                SingularityMesosSchedulerClient mesosSchedulerClient,
                                DisasterManager disasterManager,
                                SingularityConfiguration configuration,
                                TaskManager taskManager,
                                Transcoder<SingularityTaskDestroyFrameworkMessage> transcoder,
                                @Named(SingularityMainModule.STATUS_UPDATE_DELTA_30S_AVERAGE) AtomicLong statusUpdateDeltaAvg) {
    this.exceptionNotifier = exceptionNotifier;
    this.startup = startup;
    this.abort = abort;
    this.messageHandler = messageHandler;
    this.slaveAndRackManager = slaveAndRackManager;
    this.disasterManager = disasterManager;
    this.offerCache = offerCache;
    this.offerScheduler = offerScheduler;
    this.statusUpdateHandler = statusUpdateHandler;
    this.mesosSchedulerClient = mesosSchedulerClient;
    this.offerCacheEnabled = configuration.isCacheOffers();
    this.delayWhenStatusUpdateDeltaTooLarge = configuration.isDelayOfferProcessingForLargeStatusUpdateDelta();
    this.delayWhenDeltaOverMs = configuration.getDelayPollersWhenDeltaOverMs();
    this.statusUpdateDeltaAvg = statusUpdateDeltaAvg;
    this.taskManager = taskManager;
    this.transcoder = transcoder;
    this.leaderCacheCoordinator = leaderCacheCoordinator;
    this.queuedUpdates = Lists.newArrayList();
    this.lock = lock;
    this.state = SchedulerState.NOT_STARTED;
    this.configuration = configuration;
  }

  @Override
  public void subscribed(Subscribed subscribed) {
    callWithStateLock(() -> {
      Preconditions.checkState(state == SchedulerState.NOT_STARTED, "Asked to startup - but in invalid state: %s", state.name());

      leaderCacheCoordinator.activateLeaderCache();
      MasterInfo newMasterInfo = subscribed.getMasterInfo();
      masterInfo.set(newMasterInfo);
      startup.startup(newMasterInfo);
      state = SchedulerState.SUBSCRIBED;
      queuedUpdates.forEach(this::handleStatusUpdateAsync);
    }, "subscribed", false);
  }

  @Timed
  @Override
  public void resourceOffers(List<Offer> offers) {
    if (!isRunning()) {
      LOG.info("Scheduler is in state {}, declining {} offer(s)", state.name(), offers.size());
      mesosSchedulerClient.decline(offers.stream().map(Offer::getId).collect(Collectors.toList()));
      return;
    }
    callWithOffersLock(() -> {
      final long start = System.currentTimeMillis();
      lastOfferTimestamp = Optional.of(start);
      LOG.info("Received {} offer(s)", offers.size());
      boolean delclineImmediately = false;
      if (disasterManager.isDisabled(SingularityAction.PROCESS_OFFERS)) {
        LOG.info("Processing offers is currently disabled, declining {} offers", offers.size());
        delclineImmediately = true;
      }
      if (delayWhenStatusUpdateDeltaTooLarge && statusUpdateDeltaAvg.get() > delayWhenDeltaOverMs) {
        LOG.info("Status update delta is too large ({}), declining offers while status updates catch up", statusUpdateDeltaAvg.get());
        delclineImmediately = true;
      }

      if (delclineImmediately) {
        mesosSchedulerClient.decline(offers.stream().map(Offer::getId).collect(Collectors.toList()));
        return;
      }

      if (offerCacheEnabled) {
        if (disasterManager.isDisabled(SingularityAction.CACHE_OFFERS)) {
          offerCache.disableOfferCache();
        } else {
          offerCache.enableOfferCache();
        }
      }

      List<Offer> offersToCheck = new ArrayList<>(offers);

      for (Offer offer : offers) {
        String rolesInfo = MesosUtils.getRoles(offer).toString();
        LOG.debug("Received offer ID {} with roles {} from {} ({}) for {} cpu(s), {} memory, {} ports, and {} disk", offer.getId().getValue(), rolesInfo, offer.getHostname(), offer.getAgentId().getValue(), MesosUtils.getNumCpus(offer), MesosUtils.getMemory(offer),
            MesosUtils.getNumPorts(offer), MesosUtils.getDisk(offer));

        CheckResult checkResult = slaveAndRackManager.checkOffer(offer);
        if (checkResult == CheckResult.NOT_ACCEPTING_TASKS) {
          mesosSchedulerClient.decline(Collections.singletonList(offer.getId()));
          offersToCheck.remove(offer);
          LOG.debug("Will decline offer {}, slave {} is not currently in a state to launch tasks", offer.getId().getValue(), offer.getHostname());
        }
      }

      final Set<OfferID> acceptedOffers = Sets.newHashSetWithExpectedSize(offersToCheck.size());

      try {
        List<SingularityOfferHolder> offerHolders = offerScheduler.checkOffers(offersToCheck);

        for (SingularityOfferHolder offerHolder : offerHolders) {
          if (!offerHolder.getAcceptedTasks().isEmpty()) {
            List<Offer> leftoverOffers = offerHolder.launchTasksAndGetUnusedOffers(mesosSchedulerClient);

            leftoverOffers.forEach((o) -> {
              offerCache.cacheOffer(start, o);
            });

            List<Offer> offersAcceptedFromSlave = offerHolder.getOffers();
            offersAcceptedFromSlave.removeAll(leftoverOffers);
            acceptedOffers.addAll(offersAcceptedFromSlave.stream().map(Offer::getId).collect(Collectors.toList()));
          } else {
            offerHolder.getOffers().forEach((o) -> offerCache.cacheOffer(start, o));
          }
        }
      } catch (Throwable t) {
        LOG.error("Received fatal error while handling offers - will decline all available offers", t);

        mesosSchedulerClient.decline(offersToCheck.stream()
            .filter((o) -> !acceptedOffers.contains(o.getId()))
            .map(Offer::getId)
            .collect(Collectors.toList()));

        throw t;
      }

      LOG.info("Finished handling {} new offer(s) ({}), {} accepted, {} declined/cached", offers.size(), JavaUtils.duration(start), acceptedOffers.size(),
          offers.size() - acceptedOffers.size());
    }, "offers");
  }

  @Override
  public void inverseOffers(List<InverseOffer> offers) {
    LOG.debug("Singularity is currently not able to handle inverse offers events");
  }

  @Override
  public void rescind(OfferID offerId) {
    callWithOffersLock(() -> offerCache.rescindOffer(offerId), "rescind");
  }

  @Override
  public void rescindInverseOffer(OfferID offerId) {
    LOG.debug("Singularity is currently not able to handle inverse offers events");
  }

  @Override
  public void statusUpdate(TaskStatus status) {
    if (!isRunning()) {
      LOG.info("Scheduler is in state {}, queueing an update {} - {} queued updates so far", state.name(), status, queuedUpdates.size());
      queuedUpdates.add(status);
      return;
    }
    handleStatusUpdateAsync(status);
  }

  @Override
  public void message(Message message) {
    ExecutorID executorID = message.getExecutorId();
    AgentID slaveId = message.getAgentId();
    byte[] data = message.getData().toByteArray();
    LOG.info("Framework message from executor {} on slave {} with {} bytes of data", executorID, slaveId, data.length);
    messageHandler.handleMessage(executorID, slaveId, data);
  }

  @Override
  public void failure(Failure failure) {
    if (failure.hasExecutorId()) {
      LOG.warn("Lost an executor {} on slave {} with status {}", failure.getExecutorId(), failure.getAgentId(), failure.getStatus());
    } else {
      slaveLost(failure.getAgentId());
    }
  }

  @Override
  public void error(String message) {
    callWithStateLock(() -> {
      LOG.error("Aborting due to error: {}", message);
      notifyStopping();
      abort.abort(AbortReason.MESOS_ERROR, Optional.absent());
    }, "error", true);
  }

  @Override
  public void heartbeat(Event event) {
    LOG.debug("Heartbeat from mesos");
  }

  @Override
  public void onUncaughtException(Throwable t) {
    callWithStateLock(() -> {
      if (t instanceof PrematureChannelClosureException) {
        LOG.error("Lost connection to the mesos master, aborting", t);
        notifyStopping();
        abort.abort(AbortReason.LOST_MESOS_CONNECTION, Optional.absent());
      } else {
        LOG.error("Aborting due to error: {}", t.getMessage(), t);
        notifyStopping();
        abort.abort(AbortReason.MESOS_ERROR, Optional.absent());
      }
    }, "errorUncaughtException", true);
  }

  @Override
  public void onConnectException(Throwable t) {
    callWithStateLock(() -> {
      LOG.error("Unable to connect to mesos master {}", t.getMessage(), t);
      notifyStopping();
      abort.abort(AbortReason.MESOS_ERROR, Optional.absent());
    }, "errorConnectException", false);
  }

  @Override
  public long getEventBufferSize() {
    return configuration.getMesosConfiguration().getRxEventBufferSize();
  }

  public void start() throws Exception {
    // If more than one host is provided choose at random, we will be redirected if the host is not the master
    List<String> masters = Arrays.asList(configuration.getMesosConfiguration().getMaster().split(","));
    String masterUrl = String.format(SCHEDULER_API_URL_FORMAT, masters.get(new Random().nextInt(masters.size())));
    mesosSchedulerClient.subscribe(masterUrl, this);
  }

  private void callWithOffersLock(Runnable function, String name) {
    if (!isRunning()) {
      LOG.info("Ignoring {} because scheduler isn't running ({})", name, state);
      return;
    }
    final long start = lock.lockOffers(name);
    try {
      function.run();
    } catch (Throwable t) {
      LOG.error("Scheduler threw an uncaught exception - exiting", t);
      exceptionNotifier.notify(String.format("Scheduler threw an uncaught exception (%s)", t.getMessage()), t);
      notifyStopping();
      abort.abort(AbortReason.UNRECOVERABLE_ERROR, Optional.of(t));
    } finally {
      lock.unlockOffers(name, start);
    }
  }

  private void callWithStateLock(Runnable function, String name, boolean ignoreIfNotRunning) {
    if (ignoreIfNotRunning && !isRunning()) {
      LOG.info("Ignoring {} because scheduler isn't running ({})", name, state);
      return;
    }
    final long start = lock.lockState(name);
    try {
      function.run();
    } catch (Throwable t) {
      LOG.error("Scheduler threw an uncaught exception - exiting", t);
      exceptionNotifier.notify(String.format("Scheduler threw an uncaught exception (%s)", t.getMessage()), t);
      notifyStopping();
      abort.abort(AbortReason.UNRECOVERABLE_ERROR, Optional.of(t));
    } finally {
      lock.unlockState(name, start);
    }
  }

  public void notifyStopping() {
    LOG.info("Scheduler is moving to stopped, current state: {}", state);

    state = SchedulerState.STOPPED;

    leaderCacheCoordinator.stopLeaderCache();
    mesosSchedulerClient.close();

    LOG.info("Scheduler now in state: {}", state);
  }

  public boolean isRunning() {
    return state == SchedulerState.SUBSCRIBED;
  }

  public void setSubscribed() {
    callWithStateLock(() -> {
      state = SchedulerState.SUBSCRIBED;
      return;
    }, "setSubscribed", false);
  }

  public Optional<MasterInfo> getMaster() {
    return Optional.fromNullable(masterInfo.get());
  }

  public void slaveLost(Protos.AgentID slaveId) {
    LOG.warn("Lost a slave {}", slaveId);
    slaveAndRackManager.slaveLost(slaveId);
  }

  public Optional<Long> getLastOfferTimestamp() {
    return lastOfferTimestamp;
  }

  public void killAndRecord(SingularityTaskId taskId, Optional<RequestCleanupType> requestCleanupType, Optional<TaskCleanupType> taskCleanupType, Optional<Long> originalTimestamp, Optional<Integer> retries, Optional<String> user) {
    Preconditions.checkState(isRunning());

    Optional<TaskCleanupType> maybeCleanupFromRequestAndTask = getTaskCleanupType(requestCleanupType, taskCleanupType);

    if (maybeCleanupFromRequestAndTask.isPresent() && (maybeCleanupFromRequestAndTask.get() == TaskCleanupType.USER_REQUESTED_DESTROY || maybeCleanupFromRequestAndTask.get() == TaskCleanupType.REQUEST_DELETING)) {
      Optional<SingularityTask> task = taskManager.getTask(taskId);
      if (task.isPresent()) {
        if (task.get().getTaskRequest().getDeploy().getCustomExecutorCmd().isPresent()) {
          byte[] messageBytes = transcoder.toBytes(new SingularityTaskDestroyFrameworkMessage(taskId, user));
          mesosSchedulerClient.frameworkMessage(
              MesosProtosUtils.toExecutorId(task.get().getMesosTask().getExecutor().getExecutorId()),
              MesosProtosUtils.toAgentId(task.get().getMesosTask().getAgentId()),
              messageBytes
          );
        } else {
          LOG.warn("Not using custom executor, will not send framework message to destroy task");
        }
      } else {
        String message = String.format("No task data available to build kill task framework message for task %s", taskId);
        exceptionNotifier.notify(message);
        LOG.error(message);
      }
    }
    mesosSchedulerClient.kill(TaskID.newBuilder().setValue(taskId.toString()).build());

    taskManager.saveKilledRecord(new SingularityKilledTaskIdRecord(taskId, System.currentTimeMillis(), originalTimestamp.or(System.currentTimeMillis()), requestCleanupType, taskCleanupType, retries.or(-1) + 1));
  }

  private Optional<TaskCleanupType> getTaskCleanupType(Optional<RequestCleanupType> requestCleanupType, Optional<TaskCleanupType> taskCleanupType) {
    if (taskCleanupType.isPresent()) {
      return taskCleanupType;
    } else {
      if (requestCleanupType.isPresent()) {
        return requestCleanupType.get().getTaskCleanupType();
      }
      return Optional.absent();
    }
  }

  public SchedulerState getState() {
    return state;
  }

  private void handleStatusUpdateAsync(TaskStatus status) {
    long start = System.currentTimeMillis();
    statusUpdateHandler.processStatusUpdateAsync(status)
        .whenCompleteAsync((result, throwable) -> {
          if (throwable != null) {
            exceptionNotifier.notify(String.format("Scheduler threw an uncaught exception (%s)", throwable.getMessage()), throwable);
            notifyStopping();
            abort.abort(AbortReason.UNRECOVERABLE_ERROR, Optional.of(throwable));
          }
          if (status.hasUuid()) {
            mesosSchedulerClient.acknowledge(status.getAgentId(), status.getTaskId(), status.getUuid());
          }
          LOG.debug("Handled status update for {} in {}", status.getTaskId().getValue(), JavaUtils.duration(start));
        });
  }
}
