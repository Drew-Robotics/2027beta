// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OnboardLoopSimTest {
  private static final double SUB_STEP = 0.001;

  @Test
  void aWrappedPositionLoopTakesTheShortWayRound() {
    var loop = OnboardLoopSim.position(8, 0, 0, 0, 1);
    loop.setSetpoint(0.05);

    // 0.95 to 0.05 is a tenth of a turn forwards, not nine tenths of one backwards.
    assertEquals(8 * 0.1, loop.calculate(0.95, SUB_STEP), 1e-9);
  }

  @Test
  void aVelocityLoopAtItsSetpointStillWritesItsFeedforward() {
    var loop = OnboardLoopSim.velocity(0.1, 0.15, 2.0);
    loop.setSetpoint(3.0);

    assertEquals(0.15 + 2.0 * 3.0, loop.calculate(3.0, SUB_STEP), 1e-9);
  }

  @Test
  void theFirstCalculateHasNoDerivativeKick() {
    var loop = OnboardLoopSim.position(0, 1, 0, 0, 1);
    loop.setSetpoint(0.25);

    assertEquals(0, loop.calculate(0, SUB_STEP), 1e-9);
  }

  @Test
  void theDerivativeFilterCarriesTheFractionItIsGiven() {
    var loop = OnboardLoopSim.position(0, 1, 0.5, 0, 1);
    loop.setSetpoint(0.25);
    loop.calculate(0.25, SUB_STEP);

    // The error steps to a quarter turn over a millisecond, so the raw derivative is 250 a
    // second and a filter of one half passes half of it.
    assertEquals(0.5 * 250, loop.calculate(0, SUB_STEP), 1e-9);
  }
}
