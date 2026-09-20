// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.opmode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wpilib.command3.Command;
import org.wpilib.command3.Scheduler;
import org.wpilib.command3.Trigger;

// Tier 1, and a characterisation test rather than a test of anything in src/main: it pins the two
// answers alpha-7's Trigger gives to the same question, because SweepLeftAuto's zone entry is
// correct only for as long as they stay different. Nothing throws if they converge — a routine
// just starts logging a pose it never crossed anything at again.
//
// Standing a v3 command up is what loads the HAL and what calls privateLookupIn on jdk.internal.vm,
// so a JVM without configureTestTasks' --add-opens flags fails here.
class TriggerEdgeTest {
  private static Scheduler scheduler;

  private AtomicBoolean signal;
  private AtomicLong fired;

  @BeforeAll
  static void buildScheduler() {
    scheduler = Scheduler.createIndependentScheduler();
  }

  @BeforeEach
  void resetScheduler() {
    signal = new AtomicBoolean();
    fired = new AtomicLong();
    // The scheduler outlives each test, so what a test bound is dropped here.
    scheduler.cancelAll();
    scheduler.getDefaultEventLoop().clear();
  }

  // A trigger has no previous signal before its first poll, and poll() compares the two for
  // inequality rather than for a low-to-high pair, so an unknown previous state and a high current
  // one read as a rising edge.
  @Test
  void onTrueFiresOnTheFirstPollOfAConditionThatIsAlreadyTrue() {
    signal.set(true);
    new Trigger(scheduler, signal::get).onTrue(countOnce());

    scheduler.run();

    assertEquals(1, fired.get(), "onTrue no longer treats a first-observed high as an edge");
  }

  // risingEdge() asks for the pair: `m_previousSignal == Signal.LOW`, not `!= HIGH`. This is the
  // whole of why the zone entry binds through it.
  @Test
  void aRisingEdgeDoesNotFireOnTheFirstPollOfAConditionThatIsAlreadyTrue() {
    signal.set(true);
    new Trigger(scheduler, signal::get).risingEdge().onTrue(countOnce());

    scheduler.run();
    scheduler.run();

    assertEquals(0, fired.get(), "a trigger built past the line reported a crossing");
  }

  @Test
  void aRisingEdgeFiresOnceOnACrossingAndNotAgainWhileItStaysTrue() {
    signal.set(true);
    new Trigger(scheduler, signal::get).risingEdge().onTrue(countOnce());

    // Behind the line, then across it — the shape SweepLeftAuto's resetPose puts the robot in.
    scheduler.run();
    signal.set(false);
    scheduler.run();
    signal.set(true);
    scheduler.run();

    assertEquals(1, fired.get(), "the crossing did not fire exactly once");

    scheduler.run();
    scheduler.run();

    assertEquals(1, fired.get(), "staying past the line fired again");
  }

  @Test
  void aRisingEdgeFiresOnEveryLaterCrossing() {
    new Trigger(scheduler, signal::get).risingEdge().onTrue(countOnce());

    // One poll behind the line first. Without it the trigger's first observation is the crossing
    // itself, which is the case above and not the one this is about.
    scheduler.run();

    for (int crossing = 0; crossing < 3; crossing++) {
      signal.set(true);
      scheduler.run();
      signal.set(false);
      scheduler.run();
    }

    assertEquals(3, fired.get(), "a crossing after the first one was dropped");
  }

  private Command countOnce() {
    return Command.noRequirements(_ -> fired.incrementAndGet()).named("Test.Count");
  }
}
