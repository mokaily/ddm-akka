package de.ddm.actors.profiling;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import de.ddm.structures.ColID;
import de.ddm.structures.TaskArray;
import de.ddm.actors.patterns.LargeMessageProxy;
import de.ddm.serialization.AkkaSerializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.*;

public class DependencyWorker extends AbstractBehavior<DependencyWorker.Message> {

	////////////////////
	// Actor Messages //
	////////////////////

	public interface Message extends AkkaSerializable {
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ReceptionistListingMessage implements Message {
		private static final long serialVersionUID = -5246338806092216222L;
		Receptionist.Listing listing;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class TaskMessage implements Message {
		private static final long serialVersionUID = -4667745204456518160L;
		ActorRef<LargeMessageProxy.Message> dependencyMinerLargeMessageProxy;
		TaskArray.Task task;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class DataMessage implements Message, LargeMessageProxy.LargeMessage {
		private static final long serialVersionUID = -5667745204456518160L;
		ActorRef<LargeMessageProxy.Message> dependencyMinerLargeMessageProxy;
		ColID col;
		Set<String> data;
	}

	public static class ShutdownMessage implements Message {
		private static final long serialVersionUID = -7516129288777469221L;
	}

	////////////////////////
	// Actor Construction //
	////////////////////////

	public static final String DEFAULT_NAME = "dependencyWorker";

	public static Behavior<Message> create() {
		return Behaviors.setup(DependencyWorker::new);
	}

	private DependencyWorker(ActorContext<Message> context) {
		super(context);

		final ActorRef<Receptionist.Listing> listingResponseAdapter = context.messageAdapter(Receptionist.Listing.class, ReceptionistListingMessage::new);
		context.getSystem().receptionist().tell(Receptionist.subscribe(DependencyMiner.dependencyMinerService, listingResponseAdapter));

		this.largeMessageProxy = this.getContext().spawn(LargeMessageProxy.create(this.getContext().getSelf().unsafeUpcast()), LargeMessageProxy.DEFAULT_NAME);
	}

	/////////////////
	// Actor State //
	/////////////////

	private final ActorRef<LargeMessageProxy.Message> largeMessageProxy;

	private final Map<ColID, Set<String>> myData = new HashMap<>();
	private final Map<TaskArray.Task, Set<ColID>> pendingTasks = new HashMap<>();


	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive<Message> createReceive() {
		return newReceiveBuilder()
				.onMessage(ReceptionistListingMessage.class, this::handle)
				.onMessage(TaskMessage.class, this::handle)
				.onMessage(DataMessage.class, this::handle)
				.onMessage(ShutdownMessage.class, this::handle)
				.build();
	}

	private Behavior<Message> handle(ReceptionistListingMessage message) {
		Set<ActorRef<DependencyMiner.Message>> dependencyMiners = message.getListing().getServiceInstances(DependencyMiner.dependencyMinerService);
		for (ActorRef<DependencyMiner.Message> dependencyMiner : dependencyMiners)
			dependencyMiner.tell(new DependencyMiner.RegistrationMessage(this.getContext().getSelf()));
		return this;
	}

	private Behavior<Message> handle(TaskMessage message) {
		this.getContext().getLog().info("looking for work");
		Set<ColID> requiredData = new HashSet<>();
		boolean doIHaveTheLeftData = this.myData.containsKey(message.task.left);
		boolean doIHaveTheRightData = this.myData.containsKey(message.task.right);

		if (!doIHaveTheLeftData) {
			requiredData.add(message.task.left);
			requestTaskData(message, message.task.left);
		}

		if (!doIHaveTheRightData) {
			requiredData.add(message.task.right);
			requestTaskData(message, message.task.right);
		}

		if (!requiredData.isEmpty()) {
			pendingTasks.put(message.task, requiredData);
		} else {
			// Process the task immediately if all data is available
			processTask(message.task, message.dependencyMinerLargeMessageProxy);
		}
		return this;
	}



	private Behavior<Message> handle(DataMessage message) {
		myData.put(message.col, message.data);
		pendingTasks.entrySet().removeIf(entry -> {
			entry.getValue().remove(message.col);
			if (entry.getValue().isEmpty()) {
				processTask(entry.getKey(),message.dependencyMinerLargeMessageProxy);
				return true;
			}
			return false;
		});

		return this;
	}
	private Behavior<Message> handle(ShutdownMessage message) {
		getContext().getLog().info(this.pendingTasks.toString());
		return Behaviors.stopped();
	}
	private void processTask(TaskArray.Task task,ActorRef<LargeMessageProxy.Message> dependencyMinerLargeMessageProxy) {
		boolean dependencyExists = checkDependency(task);
		getContext().getLog().info("Dependency check for task " + task + ": " + dependencyExists);
		LargeMessageProxy.LargeMessage completionMessage = new DependencyMiner.CompletionMessage(this.getContext().getSelf(), task, dependencyExists);
		this.largeMessageProxy.tell(new LargeMessageProxy.SendMessage(completionMessage, dependencyMinerLargeMessageProxy));
	}
	private boolean checkDependency(TaskArray.Task task) {
		Set<String> leftData = myData.get(task.left);
		Set<String> rightData = myData.get(task.right);
		return leftData != null && rightData != null && leftData.containsAll(rightData);
	}

	private void requestTaskData(TaskMessage message, ColID col) {
		LargeMessageProxy.LargeMessage requestDataMessage = new DependencyMiner.RequestDataMessage(this.largeMessageProxy, col);
		this.largeMessageProxy.tell(new LargeMessageProxy.SendMessage(requestDataMessage, message.getDependencyMinerLargeMessageProxy()));
	}

}