// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Microseconds;
import static org.wpilib.units.Units.Milliseconds;

import first.robot.Constants;
import first.robot.DriveConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.command3.Command;
import org.wpilib.command3.Scheduler;
import org.wpilib.command3.SchedulerEvent;
import org.wpilib.command3.Trigger;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.internal.UnitTelemetry;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.system.RobotController;
import org.wpilib.telemetry.MockTelemetryBackend;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.Measure;
import org.wpilib.units.measure.Time;

// The real drive mechanism against a Scheduler that is not the process-wide one, which is the whole
// of what the injection buys: a mechanism is testable with no RobotBase around it.
//
// Standing a v3 command up is also what loads the HAL and what calls privateLookupIn on
// jdk.internal.vm, so a JVM without the --add-opens block in build.gradle fails here.
class DriveSchedulingTest {
  private static final Time STEP = Constants.LOOP_PERIOD;
  private static final Time HOLD = Milliseconds.of(100);
  private static final Time BUDGET = Milliseconds.of(500);

  // A SPARK registers its CAN id for the life of the process and refuses a second instance on it,
  // so the mechanism is built once for the class and the scheduler it was handed lives as long as
  // it does. Every test starts from the reset below rather than from a fresh pair.
  private static Scheduler scheduler;
  private static Drive drive;
  private static MockTelemetryBackend backend;
  private static final List<SchedulerEvent> EVENTS = new ArrayList<>();

  private Time now;

  @BeforeAll
  static void buildDrive() {
    // The SPARKs and the Pigeon2 below register simulated devices, which the HAL has to exist for.
    HAL.initialize();

    scheduler = Scheduler.createIndependentScheduler();
    scheduler.addEventListener(EVENTS::add);

    // RobotBase's constructor is what registers this handler, and this test does not build one.
    // Without it a Measure lands as its toString() and the unit property is never set.
    TelemetryRegistry.registerTypeHandler(
        Measure.class, (table, name, value) -> UnitTelemetry.log(table, name, value));
    backend = new MockTelemetryBackend();
    var log = new TelemetryTable(backend);

    drive =
        new Drive(
            DriveConstants.DRIVE,
            // Nothing here follows a path, and a lookup that answered would hide it if something
            // started to.
            name -> null,
            Pose2d::new,
            log.getTable("Drive"),
            log.getTable("Auto"),
            log.getTable("Sim"),
            scheduler);
  }

  @AfterAll
  static void closeBackend() {
    TelemetryRegistry.reset();
    backend.close();
  }

  @BeforeEach
  void resetScheduler() {
    now = Milliseconds.zero();
    // Coroutine.wait and every scheduler event timestamp read RobotController.getTime(), whose
    // default source is a JNI call to a clock this test cannot move.
    RobotController.setTimeSource(() -> (long) now.in(Microseconds));
    EVENTS.clear();
    // The scheduler outlives each test, so what a test scheduled or bound is dropped here. A
    // default command is the one thing this cannot drop — there is no call that unregisters one —
    // so the test below that registers Drive.Idle leaves it running for the rest of the class. It
    // is LOWEST_PRIORITY, which is why every other test still gets a drive it can command.
    scheduler.cancelAll();
    scheduler.getDefaultEventLoop().clear();
  }

  @AfterEach
  void restoreClock() {
    RobotController.setTimeSource(RobotController::getMonotonicTime);
  }

  @Test
  void oneCommandRunsToCompletion() {
    var lastRunAt = new AtomicReference<>(Milliseconds.zero());
    var command =
        drive
            // The velocity supplier is read once per loop of the command body, so stamping it is
            // how the body's last run is observed from outside the mechanism.
            .driveRobotRelative(
                () -> {
                  lastRunAt.set(RobotController.getMeasureTime());
                  return new ChassisVelocities();
                })
            .withTimeout(HOLD);

    scheduler.schedule(command);
    var finishedAt = advanceUntilDone(command);

    assertTrue(completed(command), "the command did not complete: " + EVENTS);
    assertTrue(
        finishedAt.gte(HOLD),
        "the command completed after " + finishedAt + ", before its " + HOLD + " hold elapsed");
    // The hold can only end on a cycle boundary, so one step of overshoot is the floor.
    assertTrue(
        finishedAt.lte(HOLD.plus(STEP.times(2))),
        "the command completed after " + finishedAt + ", well past its " + HOLD + " hold");
    assertTrue(
        lastRunAt.get().gte(HOLD.minus(STEP)),
        "the command body stopped running at " + lastRunAt.get());
  }

  @Test
  void theMechanismRegistersAgainstTheInjectedScheduler() {
    // setDefaultCommand and getRunningCommands are the two Mechanism methods that route through
    // getRegisteredScheduler(). Drop Drive's override and the registration lands on the singleton.
    drive.setDefaultCommand(drive.idle());
    advance();

    assertEquals(
        1, drive.getRunningCommands().size(), "the default command never reached a scheduler");
    assertEquals(1, scheduler.getRunningCommands().size(), "it did not reach ours");
    assertTrue(
        Scheduler.getDefault().getRunningCommands().isEmpty(),
        "it reached the process-wide scheduler");
  }

  // The one assertion in this project written in cycles rather than in time. An edge is a
  // statement about scheduler cycles at any loop rate, and rewriting it as a duration would make
  // it wrong.
  @Test
  void anEdgeTriggerIsHighForExactlyOneCycleSoWhileTrueCancelsImmediately() {
    var edgeCommand = drive.driveRobotRelative(ChassisVelocities::new);
    // Requirement-free, so the two bindings are not contending over the drive and the cancellation
    // below can only be the edge's doing.
    var levelCommand = holdWhileScheduled();
    var pressed = new AtomicBoolean();
    var button = new Trigger(scheduler, pressed::get);
    button.onTrue(levelCommand);
    button.risingEdge().whileTrue(edgeCommand);

    advance();
    pressed.set(true);
    advance();

    assertTrue(
        scheduler.isScheduledOrRunning(edgeCommand), "the rising edge never started its command");

    advance();

    assertFalse(
        scheduler.isScheduledOrRunning(edgeCommand),
        "the edge was still high a cycle after it rose, so whileTrue would be safe on it");
    assertTrue(
        scheduler.isScheduledOrRunning(levelCommand),
        "the signal itself went low, so the cancellation was not the edge's doing");
  }

  private static Command holdWhileScheduled() {
    return Command.noRequirements(
            coroutine -> {
              while (true) {
                coroutine.yield();
              }
            })
        .named("Test.HoldWhileScheduled");
  }

  private void advance() {
    now = now.plus(STEP);
    scheduler.run();
  }

  private Time advanceUntilDone(Command command) {
    while (scheduler.isScheduledOrRunning(command) && now.lte(BUDGET)) {
      advance();
    }
    return now;
  }

  private static boolean completed(Command command) {
    return EVENTS.stream()
        .anyMatch(e -> e instanceof SchedulerEvent.Completed c && c.command().equals(command));
  }
}
