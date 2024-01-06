package de.ddm.actors.profiling;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import de.ddm.actors.patterns.LargeMessageProxy;
import de.ddm.serialization.AkkaSerializable;
import de.ddm.singletons.InputConfigurationSingleton;
import de.ddm.singletons.SystemConfigurationSingleton;
import de.ddm.structures.InclusionDependency;
import de.ddm.structures.TaskArray;
import de.ddm.structures.ColID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;


import java.io.File;
import java.util.*;

public class DependencyMiner extends AbstractBehavior<DependencyMiner.Message> {

	////////////////////
	// Actor Messages //
	////////////////////

	public interface Message extends AkkaSerializable, LargeMessageProxy.LargeMessage {
	}

	@NoArgsConstructor
	public static class StartMessage implements Message {
		private static final long serialVersionUID = -1963913294517850454L;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class HeaderMessage implements Message {
		private static final long serialVersionUID = -5322425954432915838L;
		int id;
		String[] header;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class BatchMessage implements Message {
		private static final long serialVersionUID = 4591192372652568030L;
		int id;
		List<String[]> batch;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RegistrationMessage implements Message {
		private static final long serialVersionUID = -4025238529984914107L;
		ActorRef<DependencyWorker.Message> dependencyWorker;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CompletionMessage implements Message {
		private static final long serialVersionUID = -7642425159675583598L;
		ActorRef<DependencyWorker.Message> dependencyWorker;
		TaskArray.Task task;
		boolean isDependency;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RequestDataMessage implements Message {
		private static final long serialVersionUID = 868083729453247423L;
		ActorRef<LargeMessageProxy.Message> dependencyWorkerReceiverProxy;
		ColID left;
	}

	public static class ShutdownMessage implements Message {
		private static final long serialVersionIUD = 7516129288777469221L;
	}

	////////////////////////
	// Actor Construction //
	////////////////////////

	public static final String DEFAULT_NAME = "dependencyMiner";

	public static final ServiceKey<DependencyMiner.Message> dependencyMinerService = ServiceKey.create(DependencyMiner.Message.class, DEFAULT_NAME + "Service");

	public static Behavior<Message> create() {
		return Behaviors.setup(DependencyMiner::new);
	}

	private DependencyMiner(ActorContext<Message> context) {
		super(context);
		this.discoverNaryDependencies = SystemConfigurationSingleton.get().isHardMode();
		this.inputFiles = InputConfigurationSingleton.get().getInputFiles();
		this.headerLines = new String[this.inputFiles.length][];

		this.inputReaders = new ArrayList<>(inputFiles.length);
		for (int id = 0; id < this.inputFiles.length; id++)
			this.inputReaders.add(context.spawn(InputReader.create(id, this.inputFiles[id]), InputReader.DEFAULT_NAME + "_" + id));
		this.resultCollector = context.spawn(ResultCollector.create(), ResultCollector.DEFAULT_NAME);
		this.largeMessageProxy = this.getContext().spawn(LargeMessageProxy.create(this.getContext().getSelf().unsafeUpcast()), LargeMessageProxy.DEFAULT_NAME);

		this.dependencyWorkers = new ArrayList<>();

		context.getSystem().receptionist().tell(Receptionist.register(dependencyMinerService, context.getSelf()));
	}

	/////////////////
	// Actor State //
	/////////////////

	private long startTime;

	private final boolean discoverNaryDependencies;
	private final File[] inputFiles;
	private final String[][] headerLines;

	private final List<ActorRef<InputReader.Message>> inputReaders;
	private final ActorRef<ResultCollector.Message> resultCollector;
	private final ActorRef<LargeMessageProxy.Message> largeMessageProxy;

	private final List<ActorRef<DependencyWorker.Message>> dependencyWorkers;

	private Map<Integer, Map<Integer, TreeSet<String>>> fileIdToColToDataMap = new HashMap<>();

	private final Map<ColID, TreeSet<String>> colToDataMap = new HashMap<>();

	private TaskArray.Task[] tasks = TaskArray.generateTaskArray(colToDataMap);

	private final Map<ActorRef<DependencyWorker.Message>, ColID> dependencyWorkerToDataMap = new HashMap<>();

	private boolean isDataReadingComplete = false;

	private final List<Integer> dependencies = new ArrayList<>();

	private int finishedFiles = 0;

	private int tasksFinished = 0;

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive<Message> createReceive() {
		return newReceiveBuilder()
				.onMessage(StartMessage.class, this::handle)
				.onMessage(BatchMessage.class, this::handle)
				.onMessage(HeaderMessage.class, this::handle)
				.onMessage(RequestDataMessage.class, this::handle)
				.onMessage(RegistrationMessage.class, this::handle)
				.onMessage(CompletionMessage.class, this::handle)
				.onMessage(ShutdownMessage.class, this::handle)
				.onSignal(Terminated.class, this::handle)
				.build();
	}

	private Behavior<Message> handle(StartMessage message) {
		for (ActorRef<InputReader.Message> inputReader : this.inputReaders)
			inputReader.tell(new InputReader.ReadHeaderMessage(this.getContext().getSelf()));
		for (ActorRef<InputReader.Message> inputReader : this.inputReaders)
			inputReader.tell(new InputReader.ReadBatchMessage(this.getContext().getSelf()));
		this.startTime = System.currentTimeMillis();
		return this;
	}

	private Behavior<Message> handle(HeaderMessage message) {
		this.headerLines[message.getId()] = message.getHeader();
		return this;
	}

	private Behavior<Message> handle(BatchMessage message) {
		// if message is not empty
		if (!message.getBatch().isEmpty()) {
			this.getContext().getLog().info("received data from file: " + message.id);
			int numOfColumns = message.batch.get(0).length;
			int numOfRows = message.batch.size();
			for (int i = 0; i < numOfColumns; i++){


				Set<String> column = new TreeSet<>();
				//add contents of each row to this tree set
				for (int j = 0; j < numOfRows; j++){
					column.add(message.batch.get(j)[i]);
				}
				fileIdToColToDataMap.computeIfAbsent(message.id, k -> new HashMap<>())
						.computeIfAbsent(i, k -> new TreeSet<>())
						.addAll(column);
			}
			this.inputReaders.get(message.getId()).tell(new InputReader.ReadBatchMessage(this.getContext().getSelf()));
		}
		else {
			this.getContext().getLog().info("File {} complete", message.id);
			for (Map.Entry<Integer, Map<Integer, TreeSet<String>>> outerEntry : fileIdToColToDataMap.entrySet()) {
				Integer outerKey = outerEntry.getKey();
				Map<Integer, TreeSet<String>> innerMap = outerEntry.getValue();

				for (Map.Entry<Integer, TreeSet<String>> innerEntry : innerMap.entrySet()) {
					Integer innerKey = innerEntry.getKey();
					TreeSet<String> value = innerEntry.getValue();
					colToDataMap.put(new ColID(outerKey,innerKey), new TreeSet<>(value));


				}
			}
			tasks = TaskArray.generateTaskArray(colToDataMap);

		}
		if (message.getBatch().isEmpty()) {
			isDataReadingComplete();
		}
		if (isDataReadingComplete){
			this.getContext().getLog().info("Data Reading Completed");
			assignTasksToAllWorkers();
		}
		return this;
	}
	private void isDataReadingComplete(){
		finishedFiles++;
		if (finishedFiles == inputFiles.length){
			isDataReadingComplete = true;
			fileIdToColToDataMap = null;

		}
	}


	private Behavior<Message> handle(RegistrationMessage message) {
		ActorRef<DependencyWorker.Message> dependencyWorker = message.getDependencyWorker();
		if (!this.dependencyWorkers.contains(dependencyWorker)) {
			this.dependencyWorkers.add(dependencyWorker);
			this.getContext().watch(dependencyWorker);
			// The worker should get some work ... let me send her something before I figure out what I actually want from her.
			// I probably need to idle the worker for a while, if I do not have work for it right now ... (see master/worker pattern)
			if (isDataReadingComplete) {
				assignTask(dependencyWorker);
			}
		}
		return this;
	}

	private Behavior<Message> handle(CompletionMessage message) {
		ActorRef<DependencyWorker.Message> dependencyWorker = message.getDependencyWorker();
		for (TaskArray.Task task : tasks) {
			if (task.equals(message.task)) {
				task.setChecked(true);
				break;
			}
		}
		if (this.headerLines[0] != null) {
			if (message.isDependency){
				this.getContext().getLog().info("SWAG" + message.task);

				for (TaskArray.Task task : tasks) {
					if (task.equals(message.task)) {
						task.setContainsDependency(true);
						break;
					}
				}
				if (!dependencies.contains(message.task.id)){
					dependencies.add(message.task.id);
					List<InclusionDependency> inds = getInclusionDependencies(message);
					this.resultCollector.tell(new ResultCollector.ResultMessage(inds));
				}

			}
		}
		tasksFinished++;
		if (allTasksCompleted()) {
			end();
		}
		assignTask(dependencyWorker);

		return this;
	}

	private List<InclusionDependency> getInclusionDependencies(CompletionMessage message) {
		ColID dependent = message.task.right;
		ColID referenced = message.task.left;
		File dependentFile = this.inputFiles[dependent.getFile()];
		File referencedFile = this.inputFiles[referenced.getFile()];
		String[] dependentAttributes = {this.headerLines[dependent.getFile()][dependent.getColumn()]};
		String[] referencedAttributes = {this.headerLines[referenced.getFile()][referenced.getColumn()]};
		InclusionDependency ind = new InclusionDependency(dependentFile, dependentAttributes, referencedFile, referencedAttributes);
		List<InclusionDependency> inds = new ArrayList<>(1);
		inds.add(ind);
		return inds;
	}


	private Behavior<Message> handle(RequestDataMessage message) {

		LargeMessageProxy.LargeMessage dataMessage = new DependencyWorker.DataMessage(this.largeMessageProxy, message.left, colToDataMap.get(message.left));
		this.largeMessageProxy.tell(new LargeMessageProxy.SendMessage(dataMessage, message.dependencyWorkerReceiverProxy));

		return this;
	}

	private boolean assignTask(ActorRef<DependencyWorker.Message> dependencyWorker) {
		if (!isDataReadingComplete) {
			this.getContext().getLog().info("Data reading is not complete.");
			return false;
		}

		ColID oldColumn = this.dependencyWorkerToDataMap.get(dependencyWorker);
		if (oldColumn != null && hasMoreTasksForSameCol(this.tasks, oldColumn)) {
			DependencyWorker.TaskMessage taskMessage = nextTaskByReferencedFile(this.tasks, oldColumn);
			if (taskMessage != null) {
				dependencyWorker.tell(taskMessage);
				return true;
			}
		} else if (hasMoreWork(this.tasks)) {
			ColID newColumn = nextReferencedColumnId(this.tasks);
			if (newColumn == null) {
				newColumn = stealWork(this.tasks);
			}
			this.dependencyWorkerToDataMap.put(dependencyWorker, newColumn);
			DependencyWorker.TaskMessage taskMessage = nextTaskByReferencedFile(this.tasks, newColumn);
			if (taskMessage != null) {
				dependencyWorker.tell(taskMessage);
				return true;
			}
		}

		return false;
	}


	public boolean hasMoreTasksForSameCol(TaskArray.Task[] tasks, ColID columnId) {
		for (TaskArray.Task task : tasks) {
			if (task.left.equals(columnId) && !task.started) {
				return true;
			}
		}
		return false;
	}

	public boolean hasMoreWork(TaskArray.Task[] tasks){
		for (TaskArray.Task task : tasks) {
			if (!task.checked)
				return true;
		}
		this.getContext().getLog().info("has no more work");
		return false;
	}

	public DependencyWorker.TaskMessage nextTaskByReferencedFile(TaskArray.Task[] tasks, ColID oldColumn){
		for (TaskArray.Task task : tasks) {
			if (task.left == oldColumn && !task.started){
				task.setStarted(true);
				return new DependencyWorker.TaskMessage(this.largeMessageProxy, task);
			}

		}
		return null;
	}

	public ColID nextReferencedColumnId(TaskArray.Task[] tasks) {
		for (TaskArray.Task task : tasks) {
			if (!task.started)
				return task.left;
		}
		return null;
	}
	private ColID stealWork(TaskArray.Task[] tasks){
		for (TaskArray.Task task : tasks) {
			if (!task.checked)
				return task.left;
		}
		return null;
	}

	private void assignTasksToAllWorkers() {
		for (ActorRef<DependencyWorker.Message> worker : dependencyWorkers) {
			assignTask(worker);
		}
	}

	private boolean allTasksCompleted() {
		this.getContext().getLog().info("all tasks completed");
		return tasksFinished == tasks.length;
	}

	private void end() {
		this.getContext().getLog().info("end called");
		this.resultCollector.tell(new ResultCollector.FinalizeMessage());
		long discoveryTime = System.currentTimeMillis() - this.startTime;
		this.getContext().getLog().info("Finished mining within {} ms!", discoveryTime);
	}

	private Behavior<Message> handle(Terminated signal) {
		ActorRef<DependencyWorker.Message> dependencyWorker = signal.getRef().unsafeUpcast();
		this.dependencyWorkers.remove(dependencyWorker);
		return this;
	}

	private Behavior<Message> handle(ShutdownMessage message){
		this.getContext().getLog().info(String.valueOf(tasksFinished));
		return Behaviors.stopped();
	}
}