// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.Microseconds;
import static org.wpilib.units.Units.Milliseconds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.command3.Command;
import org.wpilib.command3.Mechanism;
import org.wpilib.command3.Scheduler;
import org.wpilib.command3.SchedulerEvent;
import org.wpilib.command3.Trigger;
import org.wpilib.system.RobotController;
import org.wpilib.system.Timer;
import org.wpilib.units.measure.Time;

class InjectedSchedulerTest {
  private static final Time STEP = Constants.LOOP_PERIOD;
  private static final Time HOLD = Milliseconds.of(100);
  private static final Time BUDGET = Milliseconds.of(500);

  private Scheduler scheduler;
  private List<SchedulerEvent> events;
  private Time now;

  @BeforeEach
  void buildScheduler() {
    now = Milliseconds.zero();
    // Coroutine.wait and every scheduler event timestamp read RobotController.getTime(), whose
    // default source is a JNI call to a clock this test cannot move.
    RobotController.setTimeSource(() -> (long) now.in(Microseconds));
    scheduler = Scheduler.createIndependentScheduler();
    events = new ArrayList<>();
    scheduler.addEventListener(events::add);
  }

  @AfterEach
  void restoreClock() {
    RobotController.setTimeSource(RobotController::getMonotonicTime);
  }

  @Test
  void oneCommandRunsToCompletion() {
    var stopwatch = new Stopwatch(scheduler);
    var command = stopwatch.holdFor(HOLD);

    scheduler.schedule(command);
    var finishedAt = advanceUntilDone(command);

    assertTrue(completed(command), "the command did not complete: " + events);
    assertTrue(
        finishedAt.gte(HOLD),
        "the command completed after " + finishedAt + ", before its " + HOLD + " hold elapsed");
    // The hold can only end on a cycle boundary, so one step of overshoot is the floor.
    assertTrue(
        finishedAt.lte(HOLD.plus(STEP.times(2))),
        "the command completed after " + finishedAt + ", well past its " + HOLD + " hold");
    assertTrue(
        stopwatch.lastRunAt.gte(HOLD.minus(STEP)),
        "the command body stopped running at " + stopwatch.lastRunAt);
  }

  @Test
  void theMechanismRegistersAgainstTheInjectedScheduler() {
    var stopwatch = new Stopwatch(scheduler);

    // setDefaultCommand and getRunningCommands are the two Mechanism methods that route through
    // getRegisteredScheduler(). Drop the override and the registration lands on the singleton.
    stopwatch.setDefaultCommand(stopwatch.holdFor(HOLD));
    advance();

    assertEquals(
        1, stopwatch.getRunningCommands().size(), "the default command never reached a scheduler");
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
    var edgeBound = new Stopwatch(scheduler);
    var levelBound = new Stopwatch(scheduler);
    var edgeCommand = edgeBound.holdFor(HOLD);
    var levelCommand = levelBound.holdFor(HOLD);
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

  private boolean completed(Command command) {
    return events.stream()
        .anyMatch(e -> e instanceof SchedulerEvent.Completed c && c.command().equals(command));
  }

  private static final class Stopwatch implements Mechanism {
    private final Scheduler scheduler;
    private Time lastRunAt = Milliseconds.zero();

    Stopwatch(Scheduler scheduler) {
      this.scheduler = scheduler;
    }

    @Override
    public Scheduler getRegisteredScheduler() {
      return scheduler;
    }

    Command holdFor(Time duration) {
      return run(coroutine -> {
            var held = Timer.createStarted();
            while (!held.hasElapsed(duration)) {
              lastRunAt = RobotController.getMeasureTime();
              coroutine.yield();
            }
          })
          .named("Stopwatch.HoldFor[" + duration.in(Milliseconds) + "ms]");
    }
  }
}
