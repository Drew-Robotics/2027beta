// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.Volts;

import first.robot.DriveConstants;
import org.junit.jupiter.api.Test;

class DriverInputTest {
  private static final double DEADBAND = DriveConstants.DRIVER_DEADBAND;

  @Test
  void aRestingStickCommandsExactlyNothing() {
    assertEquals(0, Drive.stick(0));
    assertEquals(0, Drive.stick(DEADBAND * 0.99));
    assertEquals(0, Drive.stick(-DEADBAND * 0.99));
  }

  @Test
  void fullTravelIsFullOutputAndTheCurveIsOdd() {
    assertEquals(1, Drive.stick(1), 1e-9);
    assertEquals(-1, Drive.stick(-1), 1e-9);
    assertEquals(-Drive.stick(0.42), Drive.stick(-0.42), 1e-9);
  }

  @Test
  void theStepAtTheDeadbandIsSmallerThanTheDriverCanFeel() {
    // What a linear rescale spends here is the deadband itself, and the whole reason for the
    // curve is that the driver cannot feel where that edge is.
    assertTrue(
        Drive.stick(DEADBAND * 1.001) < 0.01,
        "the curve steps to " + Drive.stick(DEADBAND * 1.001) + " at the deadband");
  }

  @Test
  void theCurveIsSoftBelowTheStraightLineAndConvergesOntoIt() {
    // Soft where a driver is placing the robot, and indistinguishable from linear where they are
    // crossing the field.
    assertTrue(Drive.stick(0.2) < 0.2 / 2, "the curve is not soft at a fifth of travel");
    assertTrue(Drive.stick(0.9) > 0.9 * 0.9, "the curve has not converged by nine tenths");

    double previous = 0;
    for (double axis = DEADBAND; axis <= 1; axis += 0.01) {
      double output = Drive.stick(axis);
      assertTrue(output >= previous, "the curve went backwards at " + axis);
      previous = output;
    }
  }

  @Test
  void theTopWheelSpeedIsTheWholeRail() {
    assertEquals(
        DriveConstants.NOMINAL_VOLTAGE.in(Volts),
        SwerveModule.openLoopVolts(DriveConstants.MAX_VELOCITY.in(MetersPerSecond)),
        1e-9,
        "the fastest the wheel goes is not the whole battery");
    assertEquals(0, SwerveModule.openLoopVolts(0), 1e-9);
  }
}
