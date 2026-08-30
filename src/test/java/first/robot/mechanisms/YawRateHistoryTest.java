// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.Seconds;

import first.robot.Constants;
import first.robot.DriveConstants;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import org.wpilib.math.interpolation.TimeInterpolatableBuffer;

class YawRateHistoryTest {
  private static final double LOOP = Constants.LOOP_PERIOD.in(Seconds);
  private static final double HISTORY = DriveConstants.YAW_RATE_HISTORY.in(Seconds);

  @Test
  void anEmptyHistoryAnswersNothingRatherThanZero() {
    var history = TimeInterpolatableBuffer.createDoubleBuffer(HISTORY);

    assertFalse(
        Drive.maxAbsYawRate(history, 0, 1).isPresent(),
        "an empty history answered a rate, which a fail-closed gate would accept");
  }

  @Test
  void aWindowOlderThanTheHistoryAnswersNothingRatherThanTheOldestSample() {
    var history = fill(0, HISTORY * 2, t -> 1.0);

    assertFalse(
        Drive.maxAbsYawRate(history, 0, HISTORY / 2).isPresent(),
        "a window the buffer no longer covers answered a rate");
  }

  @Test
  void theAnswerIsTheLargestMagnitudeInTheWindowAndNotTheOneAtItsEnd() {
    // A spike a third of a second in, and the robot stationary again by the time the frame lands.
    var history = fill(0, 1, t -> t > 0.3 && t < 0.32 ? -4.0 : 0.1);

    var max = Drive.maxAbsYawRate(history, 0, 1);

    assertTrue(max.isPresent(), "a full window answered nothing");
    assertEquals(4.0, max.get().in(RadiansPerSecond), 1e-9, "the spike was averaged away");
  }

  @Test
  void aWindowThatMissesTheSpikeAnswersTheQuietRate() {
    var history = fill(0, 1, t -> t > 0.3 && t < 0.32 ? -4.0 : 0.1);

    var max = Drive.maxAbsYawRate(history, 0.5, 1);

    assertTrue(max.isPresent(), "a covered window answered nothing");
    assertEquals(0.1, max.get().in(RadiansPerSecond), 1e-9, "the window did not bound the search");
  }

  @Test
  void theWindowIncludesBothOfItsEndpoints() {
    // Short enough that nothing is evicted, so this test turns on the window and not the history.
    var history = fill(0, 0.5, t -> t < LOOP / 2 ? 3.0 : 0.5);

    assertEquals(
        3.0,
        Drive.maxAbsYawRate(history, 0, 0).orElseThrow().in(RadiansPerSecond),
        1e-9,
        "a window of one instant missed the sample sitting on it");
  }

  // The estimator drops odometry older than its own 1.5 s, so a gate that ran dry sooner would
  // reject frames the estimator would have accepted, and nobody would find it for a season.
  @Test
  void theHistoryReachesBackAsFarAsTheEstimatorsOwnBuffer() {
    // Samples out to t = 2, so "now" is 2 and the estimator's window opens at 0.5.
    var history = fill(0, 2, t -> 1.0);

    assertTrue(
        Drive.maxAbsYawRate(history, 0.55, 0.6).isPresent(),
        "a frame 1.4 s old has no history to gate on, and the estimator would still have taken it");
  }

  private static TimeInterpolatableBuffer<Double> fill(
      double start, double end, DoubleUnaryOperator rate) {
    var history = TimeInterpolatableBuffer.createDoubleBuffer(HISTORY);
    for (double t = start; t <= end + 1e-9; t += LOOP) {
      history.addSample(t, rate.applyAsDouble(t));
    }
    return history;
  }
}
