/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.mesos.runtime.clusterframework;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import com.netflix.fenzo.TaskRequest;
import com.netflix.fenzo.TaskScheduler;
import com.netflix.fenzo.VirtualMachineLease;
import com.netflix.fenzo.functions.Action1;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.mesos.runtime.clusterframework.store.MesosWorkerStore;
import org.apache.flink.mesos.scheduler.ConnectionMonitor;
import org.apache.flink.mesos.scheduler.LaunchCoordinator;
import org.apache.flink.mesos.scheduler.LaunchableTask;
import org.apache.flink.mesos.scheduler.ReconciliationCoordinator;
import org.apache.flink.mesos.scheduler.SchedulerProxyV2;
import org.apache.flink.mesos.scheduler.TaskMonitor;
import org.apache.flink.mesos.scheduler.TaskSchedulerBuilder;
import org.apache.flink.mesos.scheduler.Tasks;
import org.apache.flink.mesos.scheduler.messages.AcceptOffers;
import org.apache.flink.mesos.scheduler.messages.Disconnected;
import org.apache.flink.mesos.scheduler.messages.Error;
import org.apache.flink.mesos.scheduler.messages.ExecutorLost;
import org.apache.flink.mesos.scheduler.messages.FrameworkMessage;
import org.apache.flink.mesos.scheduler.messages.OfferRescinded;
import org.apache.flink.mesos.scheduler.messages.ReRegistered;
import org.apache.flink.mesos.scheduler.messages.Registered;
import org.apache.flink.mesos.scheduler.messages.ResourceOffers;
import org.apache.flink.mesos.scheduler.messages.SlaveLost;
import org.apache.flink.mesos.scheduler.messages.StatusUpdate;
import org.apache.flink.mesos.util.MesosArtifactResolver;
import org.apache.flink.mesos.util.MesosConfiguration;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.clusterframework.ContainerSpecification;
import org.apache.flink.runtime.clusterframework.ContaineredTaskManagerParameters;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.metrics.MetricRegistry;
import org.apache.flink.runtime.resourcemanager.JobLeaderIdService;
import org.apache.flink.runtime.resourcemanager.ResourceManager;
import org.apache.flink.runtime.resourcemanager.ResourceManagerConfiguration;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManager;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.RpcMethod;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.util.Preconditions;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Mesos implementation of the resource manager.
 */
public class MesosResourceManager extends ResourceManager<MesosResourceManagerGateway, RegisteredMesosWorkerNode> {
	protected static final Logger LOG = LoggerFactory.getLogger(MesosResourceManager.class);

	/** The Flink configuration */
	private final Configuration flinkConfig;

	/** The Mesos configuration (master and framework info) */
	private final MesosConfiguration mesosConfig;

	/** The TaskManager container parameters (like container memory size) */
	private final MesosTaskManagerParameters taskManagerParameters;

	/** Container specification for launching a TM */
	private final ContainerSpecification taskManagerContainerSpec;

	/** Resolver for HTTP artifacts */
	private final MesosArtifactResolver artifactResolver;

	/** Persistent storage of allocated containers */
	private final MesosWorkerStore workerStore;

	/** A local actor system for using the helper actors */
	private final ActorSystem actorSystem;

	/** Callback handler for the asynchronous Mesos scheduler */
	private SchedulerProxyV2 schedulerCallbackHandler;

	/** Mesos scheduler driver */
	private SchedulerDriver schedulerDriver;

	/** an adapter to receive messages from Akka actors */
	@VisibleForTesting
	ActorRef selfActor;

	private ActorRef connectionMonitor;

	private ActorRef taskMonitor;

	private ActorRef launchCoordinator;

	private ActorRef reconciliationCoordinator;

	/** planning state related to workers - package private for unit test purposes */
	final Map<ResourceID, MesosWorkerStore.Worker> workersInNew;
	final Map<ResourceID, MesosWorkerStore.Worker> workersInLaunch;
	final Map<ResourceID, MesosWorkerStore.Worker> workersBeingReturned;

	public MesosResourceManager(
			// base class
			RpcService rpcService,
			String resourceManagerEndpointId,
			ResourceID resourceId,
			ResourceManagerConfiguration resourceManagerConfiguration,
			HighAvailabilityServices highAvailabilityServices,
			HeartbeatServices heartbeatServices,
			SlotManager slotManager,
			MetricRegistry metricRegistry,
			JobLeaderIdService jobLeaderIdService,
			FatalErrorHandler fatalErrorHandler,
			// Mesos specifics
			ActorSystem actorSystem,
			Configuration flinkConfig,
			MesosConfiguration mesosConfig,
			MesosWorkerStore workerStore,
			MesosTaskManagerParameters taskManagerParameters,
			ContainerSpecification taskManagerContainerSpec,
			MesosArtifactResolver artifactResolver) {
		super(
			rpcService,
			resourceManagerEndpointId,
			resourceId,
			resourceManagerConfiguration,
			highAvailabilityServices,
			heartbeatServices,
			slotManager,
			metricRegistry,
			jobLeaderIdService,
			fatalErrorHandler);

		this.actorSystem = Preconditions.checkNotNull(actorSystem);

		this.flinkConfig = Preconditions.checkNotNull(flinkConfig);
		this.mesosConfig = Preconditions.checkNotNull(mesosConfig);

		this.workerStore = Preconditions.checkNotNull(workerStore);
		this.artifactResolver = Preconditions.checkNotNull(artifactResolver);

		this.taskManagerParameters = Preconditions.checkNotNull(taskManagerParameters);
		this.taskManagerContainerSpec = Preconditions.checkNotNull(taskManagerContainerSpec);

		this.workersInNew = new HashMap<>(8);
		this.workersInLaunch = new HashMap<>(8);
		this.workersBeingReturned = new HashMap<>(8);
	}

	protected ActorRef createSelfActor() {
		return actorSystem.actorOf(
			AkkaAdapter.createActorProps(getSelf()),"ResourceManager");
	}

	protected ActorRef createConnectionMonitor() {
		return actorSystem.actorOf(
			ConnectionMonitor.createActorProps(ConnectionMonitor.class, flinkConfig),
			"connectionMonitor");
	}

	protected ActorRef createTaskRouter() {
		return actorSystem.actorOf(
			Tasks.createActorProps(Tasks.class, flinkConfig, schedulerDriver, TaskMonitor.class),
			"tasks");
	}

	protected ActorRef createLaunchCoordinator() {
		return actorSystem.actorOf(
			LaunchCoordinator.createActorProps(LaunchCoordinator.class, selfActor, flinkConfig, schedulerDriver, createOptimizer()),
			"launchCoordinator");
	}

	protected ActorRef createReconciliationCoordinator() {
		return actorSystem.actorOf(
			ReconciliationCoordinator.createActorProps(ReconciliationCoordinator.class, flinkConfig, schedulerDriver),
			"reconciliationCoordinator");
	}

	// ------------------------------------------------------------------------
	//  Resource Manager overrides
	// ------------------------------------------------------------------------

	/**
	 * Starts the Mesos-specifics.
	 */
	@Override
	protected void initialize() throws ResourceManagerException {
		// start the worker store
		try {
			workerStore.start();
		}
		catch(Exception e) {
			throw new ResourceManagerException("Unable to initialize the worker store.", e);
		}

		// create the scheduler driver to communicate with Mesos
		schedulerCallbackHandler = new SchedulerProxyV2(getSelf());

		// register with Mesos
		// TODO : defer connection until RM acquires leadership

		Protos.FrameworkInfo.Builder frameworkInfo = mesosConfig.frameworkInfo()
			.clone()
			.setCheckpoint(true);
		try {
			Option<Protos.FrameworkID> frameworkID = workerStore.getFrameworkID();
			if (frameworkID.isEmpty()) {
				LOG.info("Registering as new framework.");
			} else {
				LOG.info("Recovery scenario: re-registering using framework ID {}.", frameworkID.get().getValue());
				frameworkInfo.setId(frameworkID.get());
			}
		}
		catch(Exception e) {
			throw new ResourceManagerException("Unable to recover the framework ID.", e);
		}

		MesosConfiguration initializedMesosConfig = mesosConfig.withFrameworkInfo(frameworkInfo);
		MesosConfiguration.logMesosConfig(LOG, initializedMesosConfig);
		schedulerDriver = initializedMesosConfig.createDriver(schedulerCallbackHandler, false);

		// create supporting actors
		selfActor = createSelfActor();
		connectionMonitor = createConnectionMonitor();
		launchCoordinator = createLaunchCoordinator();
		reconciliationCoordinator = createReconciliationCoordinator();
		taskMonitor = createTaskRouter();

		// recover state
		try {
			recoverWorkers();
		}
		catch(Exception e) {
			throw new ResourceManagerException("Unable to recover Mesos worker state.", e);
		}

		// begin scheduling
		connectionMonitor.tell(new ConnectionMonitor.Start(), selfActor);
		schedulerDriver.start();

		LOG.info("Mesos resource manager initialized.");
	}

	/**
	 * Recover framework/worker information persisted by a prior incarnation of the RM.
	 */
	private void recoverWorkers() throws Exception {
		// if this resource manager is recovering from failure,
		// then some worker tasks are most likely still alive and we can re-obtain them
		final List<MesosWorkerStore.Worker> tasksFromPreviousAttempts = workerStore.recoverWorkers();

		assert(workersInNew.isEmpty());
		assert(workersInLaunch.isEmpty());
		assert(workersBeingReturned.isEmpty());

		if (!tasksFromPreviousAttempts.isEmpty()) {
			LOG.info("Retrieved {} TaskManagers from previous attempt", tasksFromPreviousAttempts.size());

			List<Tuple2<TaskRequest,String>> toAssign = new ArrayList<>(tasksFromPreviousAttempts.size());

			for (final MesosWorkerStore.Worker worker : tasksFromPreviousAttempts) {
				LaunchableMesosWorker launchable = createLaunchableMesosWorker(worker.taskID(), worker.profile());

				switch(worker.state()) {
					case New:
						// remove new workers because allocation requests are transient
						workerStore.removeWorker(worker.taskID());
						break;
					case Launched:
						workersInLaunch.put(extractResourceID(worker.taskID()), worker);
						toAssign.add(new Tuple2<>(launchable.taskRequest(), worker.hostname().get()));
						break;
					case Released:
						workersBeingReturned.put(extractResourceID(worker.taskID()), worker);
						break;
				}
				taskMonitor.tell(new TaskMonitor.TaskGoalStateUpdated(extractGoalState(worker)), selfActor);
			}

			// tell the launch coordinator about prior assignments
			if(toAssign.size() >= 1) {
				launchCoordinator.tell(new LaunchCoordinator.Assign(toAssign), selfActor);
			}
		}
	}

	@Override
	protected void shutDownApplication(ApplicationStatus finalStatus, String optionalDiagnostics) {
		LOG.info("Shutting down and unregistering as a Mesos framework.");
		try {
			// unregister the framework, which implicitly removes all tasks.
			schedulerDriver.stop(false);
		}
		catch(Exception ex) {
			LOG.warn("unable to unregister the framework", ex);
		}

		try {
			workerStore.stop(true);
		}
		catch(Exception ex) {
			LOG.warn("unable to stop the worker state store", ex);
		}

		LOG.info("Shutdown completed.");
	}

	@Override
	public void startNewWorker(ResourceProfile resourceProfile) {
		LOG.info("Starting a new worker.");
		try {
			// generate new workers into persistent state and launch associated actors
			MesosWorkerStore.Worker worker = MesosWorkerStore.Worker.newWorker(workerStore.newTaskID(), resourceProfile);
			workerStore.putWorker(worker);
			workersInNew.put(extractResourceID(worker.taskID()), worker);

			LaunchableMesosWorker launchable = createLaunchableMesosWorker(worker.taskID(), resourceProfile);

			LOG.info("Scheduling Mesos task {} with ({} MB, {} cpus).",
				launchable.taskID().getValue(), launchable.taskRequest().getMemory(), launchable.taskRequest().getCPUs());

			// tell the task monitor about the new plans
			taskMonitor.tell(new TaskMonitor.TaskGoalStateUpdated(extractGoalState(worker)), selfActor);

			// tell the launch coordinator to launch the new tasks
			launchCoordinator.tell(new LaunchCoordinator.Launch(Collections.singletonList((LaunchableTask) launchable)), selfActor);
		}
		catch(Exception ex) {
			onFatalErrorAsync(new ResourceManagerException("Unable to request new workers.", ex));
		}
	}

	@Override
	public void stopWorker(InstanceID instanceId) {
		// TODO implement worker release
	}

	/**
	 * Callback when a worker was started.
	 *
	 * @param resourceID The worker resource id (as provided by the TaskExecutor)
	 */
	@Override
	protected RegisteredMesosWorkerNode workerStarted(ResourceID resourceID) {

		// note: this may occur more than once for a given worker.
		MesosWorkerStore.Worker inLaunch = workersInLaunch.get(resourceID);
		if (inLaunch != null) {
			return new RegisteredMesosWorkerNode(inLaunch);
		} else {
			// the worker is unrecognized or was already released
			// return null to indicate that TaskExecutor registration should be declined
			return null;
		}
	}

	// ------------------------------------------------------------------------
	//  RPC methods
	// ------------------------------------------------------------------------

	@RpcMethod
	public void registered(Registered message) {
		connectionMonitor.tell(message, selfActor);
		try {
			workerStore.setFrameworkID(Option.apply(message.frameworkId()));
		}
		catch(Exception ex) {
			onFatalError(new ResourceManagerException("Unable to store the assigned framework ID.", ex));
			return;
		}

		launchCoordinator.tell(message, selfActor);
		reconciliationCoordinator.tell(message, selfActor);
		taskMonitor.tell(message, selfActor);
	}

	/**
	 * Called when reconnected to Mesos following a failover event.
	 */
	@RpcMethod
	public void reregistered(ReRegistered message) {
		connectionMonitor.tell(message, selfActor);
		launchCoordinator.tell(message, selfActor);
		reconciliationCoordinator.tell(message, selfActor);
		taskMonitor.tell(message, selfActor);
	}

	/**
	 * Called when disconnected from Mesos.
	 */
	@RpcMethod
	public void disconnected(Disconnected message) {
		connectionMonitor.tell(message, selfActor);
		launchCoordinator.tell(message, selfActor);
		reconciliationCoordinator.tell(message, selfActor);
		taskMonitor.tell(message, selfActor);
	}

	/**
	 * Called when resource offers are made to the framework.
	 */
	@RpcMethod
	public void resourceOffers(ResourceOffers message) {
		launchCoordinator.tell(message, selfActor);
	}

	/**
	 * Called when resource offers are rescinded.
	 */
	@RpcMethod
	public void offerRescinded(OfferRescinded message) {
		launchCoordinator.tell(message, selfActor);
	}

	/**
	 * Accept offers as advised by the launch coordinator.
	 *
	 * Acceptance is routed through the RM to update the persistent state before
	 * forwarding the message to Mesos.
	 */
	@RpcMethod
	public void acceptOffers(AcceptOffers msg) {
		try {
			List<TaskMonitor.TaskGoalStateUpdated> toMonitor = new ArrayList<>(msg.operations().size());

			// transition the persistent state of some tasks to Launched
			for (Protos.Offer.Operation op : msg.operations()) {
				if (op.getType() == Protos.Offer.Operation.Type.LAUNCH) {
					for (Protos.TaskInfo info : op.getLaunch().getTaskInfosList()) {
						MesosWorkerStore.Worker worker = workersInNew.remove(extractResourceID(info.getTaskId()));
						assert (worker != null);

						worker = worker.launchWorker(info.getSlaveId(), msg.hostname());
						workerStore.putWorker(worker);
						workersInLaunch.put(extractResourceID(worker.taskID()), worker);

						LOG.info("Launching Mesos task {} on host {}.",
							worker.taskID().getValue(), worker.hostname().get());

						toMonitor.add(new TaskMonitor.TaskGoalStateUpdated(extractGoalState(worker)));
					}
				}
			}

			// tell the task monitor about the new plans
			for (TaskMonitor.TaskGoalStateUpdated update : toMonitor) {
				taskMonitor.tell(update, selfActor);
			}

			// send the acceptance message to Mesos
			schedulerDriver.acceptOffers(msg.offerIds(), msg.operations(), msg.filters());
		}
		catch(Exception ex) {
			onFatalError(new ResourceManagerException("unable to accept offers", ex));
		}
	}

	/**
	 * Handles a task status update from Mesos.
	 */
	@RpcMethod
	public void statusUpdate(StatusUpdate message) {
		taskMonitor.tell(message, selfActor);
		reconciliationCoordinator.tell(message, selfActor);
		schedulerDriver.acknowledgeStatusUpdate(message.status());
	}

	/**
	 * Handles a reconciliation request from a task monitor.
	 */
	@RpcMethod
	public void reconcile(ReconciliationCoordinator.Reconcile message) {
		// forward to the reconciliation coordinator
		reconciliationCoordinator.tell(message, selfActor);
	}

	/**
	 * Handles a termination notification from a task monitor.
	 */
	@RpcMethod
	public void taskTerminated(TaskMonitor.TaskTerminated message) {
		Protos.TaskID taskID = message.taskID();
		Protos.TaskStatus status = message.status();

		// note: this callback occurs for failed containers and for released containers alike
		final ResourceID id = extractResourceID(taskID);

		boolean existed;
		try {
			existed = workerStore.removeWorker(taskID);
		}
		catch(Exception ex) {
			onFatalError(new ResourceManagerException("unable to remove worker", ex));
			return;
		}

		if(!existed) {
			LOG.info("Received a termination notice for an unrecognized worker: {}", id);
			return;
		}

		// check if this is a failed task or a released task
		assert(!workersInNew.containsKey(id));
		if (workersBeingReturned.remove(id) != null) {
			// regular finished worker that we released
			LOG.info("Worker {} finished successfully with message: {}",
				id, status.getMessage());
		} else {
			// failed worker, either at startup, or running
			final MesosWorkerStore.Worker launched = workersInLaunch.remove(id);
			assert(launched != null);
			LOG.info("Worker {} failed with status: {}, reason: {}, message: {}.",
				id, status.getState(), status.getReason(), status.getMessage());

			// TODO : launch a replacement worker?
		}

		closeTaskManagerConnection(id, new Exception(status.getMessage()));
	}

	@RpcMethod
	public void frameworkMessage(FrameworkMessage message) {}

	@RpcMethod
	public void slaveLost(SlaveLost message) {}

	@RpcMethod
	public void executorLost(ExecutorLost message) {}

	/**
	 * Called when an error is reported by the scheduler callback.
	 */
	@RpcMethod
	public void error(Error message) {
		onFatalError(new ResourceManagerException("Connection to Mesos failed", new Exception(message.message())));
	}

	// ------------------------------------------------------------------------
	//  Utilities
	// ------------------------------------------------------------------------

	/**
	 * Creates a launchable task for Fenzo to process.
	 */
	private LaunchableMesosWorker createLaunchableMesosWorker(Protos.TaskID taskID, ResourceProfile resourceProfile) {

		// create the specific TM parameters from the resource profile and some defaults
		MesosTaskManagerParameters params = new MesosTaskManagerParameters(
			resourceProfile.getCpuCores() < 1.0 ? taskManagerParameters.cpus() : resourceProfile.getCpuCores(),
			taskManagerParameters.containerType(),
			taskManagerParameters.containerImageName(),
			new ContaineredTaskManagerParameters(
				resourceProfile.getMemoryInMB() < 0 ? taskManagerParameters.containeredParameters().taskManagerTotalMemoryMB() : resourceProfile.getMemoryInMB(),
				resourceProfile.getHeapMemoryInMB(),
				resourceProfile.getDirectMemoryInMB(),
				1,
				new HashMap<>(taskManagerParameters.containeredParameters().taskManagerEnv())),
			taskManagerParameters.containerVolumes(),
			taskManagerParameters.constraints(),
			taskManagerParameters.bootstrapCommand(),
			taskManagerParameters.getTaskManagerHostname()
		);

		LaunchableMesosWorker launchable =
			new LaunchableMesosWorker(
				artifactResolver,
				params,
				taskManagerContainerSpec,
				taskID,
				mesosConfig);

		return launchable;
	}

	/**
	 * Extracts a unique ResourceID from the Mesos task.
	 *
	 * @param taskId the Mesos TaskID
	 * @return The ResourceID for the container
	 */
	static ResourceID extractResourceID(Protos.TaskID taskId) {
		return new ResourceID(taskId.getValue());
	}

	/**
	 * Extracts the Mesos task goal state from the worker information.
	 *
	 * @param worker the persistent worker information.
	 * @return goal state information for the {@Link TaskMonitor}.
	 */
	static TaskMonitor.TaskGoalState extractGoalState(MesosWorkerStore.Worker worker) {
		switch(worker.state()) {
			case New: return new TaskMonitor.New(worker.taskID());
			case Launched: return new TaskMonitor.Launched(worker.taskID(), worker.slaveID().get());
			case Released: return new TaskMonitor.Released(worker.taskID(), worker.slaveID().get());
			default: throw new IllegalArgumentException("unsupported worker state");
		}
	}

	/**
	 * Creates the Fenzo optimizer (builder).
	 * The builder is an indirection to facilitate unit testing of the Launch Coordinator.
	 */
	private static TaskSchedulerBuilder createOptimizer() {
		return new TaskSchedulerBuilder() {
			TaskScheduler.Builder builder = new TaskScheduler.Builder();

			@Override
			public TaskSchedulerBuilder withLeaseRejectAction(Action1<VirtualMachineLease> action) {
				builder.withLeaseRejectAction(action);
				return this;
			}

			@Override
			public TaskScheduler build() {
				return builder.build();
			}
		};
	}

	/**
	 * Adapts incoming Akka messages as RPC calls to the resource manager.
	 */
	static class AkkaAdapter extends UntypedActor {
		private final MesosResourceManagerGateway gateway;
		AkkaAdapter(MesosResourceManagerGateway gateway) {
			this.gateway = gateway;
		}
		@Override
		public void onReceive(Object message) throws Exception {
			if (message instanceof ReconciliationCoordinator.Reconcile) {
				gateway.reconcile((ReconciliationCoordinator.Reconcile) message);
			} else if (message instanceof TaskMonitor.TaskTerminated) {
				gateway.taskTerminated((TaskMonitor.TaskTerminated) message);
			} else if (message instanceof AcceptOffers) {
				gateway.acceptOffers((AcceptOffers) message);
			} else {
				MesosResourceManager.LOG.error("unrecognized message: " + message);
			}
		}

		public static Props createActorProps(MesosResourceManagerGateway gateway) {
			return Props.create(AkkaAdapter.class, gateway);
		}
	}
}