// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Nanoseconds;
import static org.wpilib.units.Units.Seconds;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.command3.Command;
import org.wpilib.command3.Coroutine;
import org.wpilib.command3.Mechanism;
import org.wpilib.command3.Scheduler;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.system.RobotController;
import org.wpilib.telemetry.MockTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.util.AlertDataJNI;

// The listener and the per-loop flush against a scheduler that is not the process-wide one, which
// is what makes the command timeline assertable with no RobotBase around it.
//
// Standing a v3 command up is also what loads the HAL and what calls privateLookupIn on
// jdk.internal.vm, so a JVM without the --add-opens flags configureTestTasks supplies fails here.
class CommandLogTest {
  private static final String TYPES = "/Commands/Events/Types";
  private static final String COMMANDS = "/Commands/Events/Commands";
  private static final String TIMESTAMPS = "/Commands/Events/Timestamps";
  private static final String DETAILS = "/Commands/Events/Details";
  private static final String SNAPSHOT = "/Commands/Scheduler";
  private static final String DRIVE = "/Commands/Mechanisms/TestMechanism";

  // A body that never ends, so the command under test stays running until something takes it away.
  private static final Consumer<Coroutine> HOLD =
      coroutine -> {
        while (true) {
          coroutine.yield();
        }
      };

  private Scheduler scheduler;
  private MockTelemetryBackend backend;
  private CommandLog commands;
  private long nowNanos;

  @BeforeAll
  static void loadHal() {
    HAL.initialize();
  }

  @AfterAll
  static void clearRegistry() {
    TelemetryRegistry.reset();
  }

  @BeforeEach
  void buildLog() {
    nowNanos = 0;
    // Every scheduler event timestamp reads RobotController.getTime(), whose default source is a
    // JNI call to a clock this test cannot move.
    RobotController.setTimeSource(() -> nowNanos);

    scheduler = Scheduler.createIndependentScheduler();
    backend = new MockTelemetryBackend();
    commands = new CommandLog(new TelemetryTable(backend).getTable("Commands"), scheduler);
  }

  @AfterEach
  void closeLog() {
    RobotController.setTimeSource(RobotController::getMonotonicTime);
    // The alert is keyed on its id for the life of the process, and a second CommandLog on a live
    // one throws rather than replacing it.
    commands.close();
    backend.close();
  }

  @Test
  void aOneShotThatNeverYieldsIsBracketedByScheduledAndCompleted() {
    scheduler.schedule(Command.noRequirements(coroutine -> {}).named("Test.OneShot"));

    loop();

    assertEquals(List.of("Scheduled", "Completed"), types());
    assertEquals(List.of("Test.OneShot", "Test.OneShot"), commandNames());
  }

  @Test
  void aRunningCommandIsSilentAfterTheLoopThatScheduledIt() {
    scheduler.schedule(forever("Test.Forever"));

    loop();
    backend.clear();
    loop();
    loop();

    // Mounted and Yielded fire once per running command per loop. Dropping them is what keeps the
    // timeline to transitions; /Commands/Scheduler is where a running command's steady state is.
    assertEquals(0, writes(TYPES), "a steady-state loop wrote to the event timeline");
  }

  @Test
  void anInterruptedCommandNamesTheCommandThatTookTheMechanism() {
    var mechanism = new TestMechanism();
    scheduler.schedule(held(mechanism, "Test.Held"));
    loop();
    backend.clear();

    scheduler.schedule(held(mechanism, "Test.Interrupter"));
    loop();

    assertEquals(List.of("Scheduled", "Interrupted", "Canceled"), types());
    assertEquals(
        List.of("Test.Interrupter", "Test.Held", "Test.Held"),
        commandNames(),
        "the interrupted command is not the one the event names");
    assertEquals("Test.Interrupter", details().get(1));
  }

  @Test
  void aCancelledCommandIsLoggedWithNoInterrupter() {
    var command = forever("Test.Cancelled");
    scheduler.schedule(command);
    loop();
    backend.clear();

    scheduler.cancel(command);
    loop();

    assertEquals(List.of("Canceled"), types());
    assertEquals(List.of(""), details());
  }

  @Test
  void aCommandThatThrowsAlertsBeforeTheExceptionLeavesTheScheduler() {
    scheduler.schedule(
        Command.noRequirements(
                coroutine -> {
                  throw new IllegalStateException("the mechanism is on fire");
                })
            .named("Test.Throws"));

    // alpha-7's handleCommandException rethrows, so the failure does leave run() and would take
    // the whole robot program with it. The alert is raised on the way out, and the flush that
    // carries the event is Robot's finally rather than this loop's tail.
    assertThrows(IllegalStateException.class, scheduler::run);

    assertTrue(alertText().contains("Test.Throws"), "the alert does not name the command");

    commands.log();

    assertEquals(List.of("Scheduled", "CompletedWithError"), types());
    assertTrue(
        details().get(1).contains("the mechanism is on fire"),
        "the throwable did not reach the log: " + details());
  }

  @Test
  void theMechanismSignalNamesTheRootOfTheRequiringChain() {
    var mechanism = new TestMechanism();
    // The shape Drive.FollowPath has: a command that takes the mechanism and forks a child that
    // takes it too. Both require it, so naming one of them is a choice, and the root is the one
    // that says why the mechanism is busy.
    scheduler.schedule(
        mechanism
            .run(
                coroutine -> {
                  coroutine.fork(held(mechanism, "Test.Child"));
                  HOLD.accept(coroutine);
                })
            .named("Test.Parent"));

    loop();

    assertEquals("Test.Parent", mechanismCommand());
  }

  @Test
  void aMechanismWithNothingRunningReadsEmptyRatherThanStale() {
    var mechanism = new TestMechanism();
    var command = held(mechanism, "Test.Held");
    scheduler.schedule(command);
    loop();
    assertEquals("Test.Held", mechanismCommand());

    scheduler.cancel(command);
    loop();

    // The bug this signal exists to avoid: a slot that stops being written keeps its last value
    // forever, so the log says a command is still running long after it ended.
    assertEquals("", mechanismCommand(), "the mechanism kept the command that had already ended");
  }

  @Test
  void aMechanismIsWrittenEveryLoopOnceItHasBeenSeen() {
    var mechanism = new TestMechanism();
    scheduler.schedule(held(mechanism, "Test.Held"));
    loop();
    backend.clear();

    loop();
    loop();

    assertEquals(2, writes(DRIVE), "the mechanism stopped being written while its command ran");
  }

  @Test
  void aMechanismNobodyHasRequiredIsNotInvented() {
    loop();

    assertEquals(0, writes(DRIVE));
  }

  @Test
  void aLoopWithNoEventsWritesNothingToTheTimeline() {
    loop();

    assertEquals(0, writes(TYPES));
    assertEquals(0, writes(COMMANDS));
    assertEquals(0, writes(TIMESTAMPS));
    assertEquals(0, writes(DETAILS));
  }

  @Test
  void twoIdenticalBatchesAreBothWritten() {
    var oneShot = Command.noRequirements(coroutine -> {}).named("Test.Repeat");

    scheduler.schedule(oneShot);
    loop();
    scheduler.schedule(oneShot);
    loop();

    assertEquals(2, writes(TYPES), "the second batch never reached the backend");
    assertEquals(2, writes(COMMANDS));
    assertEquals(2, writes(DETAILS));
  }

  // The test above cannot fail on suppression: MockTelemetryBackend records every call and dedupes
  // nothing, so it is the request itself that has to be asserted. It is the real backends that drop
  // a repeat, and on the DataLog that is logProtobuf choosing update() over append().
  @Test
  void everySignalHereAsksToKeepItsDuplicates() {
    // Repeated command names are the normal case, so suppression would silently swallow a command
    // that was scheduled twice in the same shape. ADR 0005 calls this mandatory for the events.
    assertTrue(keepsDuplicates(TYPES), TYPES);
    assertTrue(keepsDuplicates(COMMANDS), COMMANDS);
    assertTrue(keepsDuplicates(TIMESTAMPS), TIMESTAMPS);
    assertTrue(keepsDuplicates(DETAILS), DETAILS);

    // An unchanging command tree and a log that stopped are otherwise the same stretch of silence.
    assertTrue(keepsDuplicates(SNAPSHOT), SNAPSHOT);
  }

  @Test
  void timestampsCarryTheEventsOwnClockInSeconds() {
    nowNanos = (long) Seconds.of(4).in(Nanoseconds);
    scheduler.schedule(Command.noRequirements(coroutine -> {}).named("Test.Stamped"));

    loop();

    assertArrayEquals(new double[] {4.0, 4.0}, timestamps());
  }

  @Test
  void theSchedulerSnapshotIsWrittenEveryLoop() {
    loop();
    loop();

    assertEquals(2, writes(SNAPSHOT), "the snapshot is not written once a loop");
  }

  @Test
  void anEmptyLoopLeavesTheAlertAlone() {
    loop();

    assertNull(alertText(), "an empty loop raised the command-failed alert");
  }

  // One robot loop: the scheduler runs, then the batch it produced is flushed.
  private void loop() {
    scheduler.run();
    commands.log();
    nowNanos += (long) Constants.LOOP_PERIOD.in(Nanoseconds);
  }

  private static Command forever(String name) {
    return Command.noRequirements(HOLD).named(name);
  }

  private static Command held(Mechanism mechanism, String name) {
    return mechanism.run(HOLD).named(name);
  }

  // A scalar string is wrapped, where a string array is stored as itself.
  private String mechanismCommand() {
    var value = backend.getLastValue(DRIVE, MockTelemetryBackend.LogStringValue.class);
    return value == null ? null : value.value();
  }

  private List<String> types() {
    return strings(TYPES);
  }

  private List<String> commandNames() {
    return strings(COMMANDS);
  }

  private List<String> details() {
    return strings(DETAILS);
  }

  private List<String> strings(String path) {
    var value = backend.getLastValue(path, String[].class);
    return value == null ? List.of() : Arrays.asList(value);
  }

  private double[] timestamps() {
    return backend.getLastValue(TIMESTAMPS, double[].class);
  }

  private boolean keepsDuplicates(String path) {
    return backend.getActions().stream()
        .anyMatch(
            a ->
                path.equals(a.path())
                    && a.value() instanceof MockTelemetryBackend.KeepDuplicateValue keep
                    && keep.value());
  }

  // keepDuplicates() and setProperty() land in the mock's action list under the same path as the
  // samples do, so counting samples means skipping them.
  private int writes(String path) {
    return (int)
        backend.getActions().stream()
            .filter(a -> path.equals(a.path()))
            .filter(
                a ->
                    !(a.value() instanceof MockTelemetryBackend.KeepDuplicateValue
                        || a.value() instanceof MockTelemetryBackend.SetPropertyValue))
            .count();
  }

  // The alert table is process-wide, and an inactive alert is one whose start time is still zero.
  private static String alertText() {
    return Arrays.stream(AlertDataJNI.getAlerts())
        .filter(a -> a.activeStartTime != 0 && "command-failed".equals(a.id))
        .map(a -> a.text)
        .findFirst()
        .orElse(null);
  }

  // Nothing here drives hardware; the mechanism exists so two commands can contend over one
  // requirement, which is the only way an Interrupted event is produced.
  private final class TestMechanism implements Mechanism {
    @Override
    public Scheduler getRegisteredScheduler() {
      return scheduler;
    }

    @Override
    public String getName() {
      return "TestMechanism";
    }
  }
}
