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
    // Soft where a driver is placing the robot, and close to linear where they are crossing the
    // field. The bounds are the shape rather than the fit: a curve stiff enough to fail the first
    // one is the flat-zone-with-an-edge the curve exists to replace.
    assertTrue(Drive.stick(0.2) < 0.05, "the curve is not soft at a fifth of travel");
    assertTrue(Drive.stick(0.5) < 0.35, "the curve is not soft at half travel");
    assertTrue(Drive.stick(0.9) > 0.8, "the curve has not converged by nine tenths");

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

  // The stick's forward is -Y, so a shove forwards is getLeftY() = -1.
  private static final double FORWARD = -1;
  private static final boolean RED = true;
  private static final boolean BLUE = false;

  @Test
  void aBlueDriverPushingForwardsDrivesAtTheRedWall() {
    var velocities = Drive.driverVelocities(FORWARD, 0, 0, BLUE);

    assertEquals(DriveConstants.MAX_VELOCITY.in(MetersPerSecond), velocities.vx, 1e-9);
    assertEquals(0, velocities.vy, 1e-9);
  }

  // The field is blue-origin for both alliances, so +x is the red wall whoever is driving and a
  // red driver standing at that wall has to be handed its negative. Getting this wrong drives the
  // robot at the driver, which is what it did.
  @Test
  void aRedDriverPushingForwardsDrivesAwayFromTheRedWall() {
    var velocities = Drive.driverVelocities(FORWARD, 0, 0, RED);

    assertEquals(-DriveConstants.MAX_VELOCITY.in(MetersPerSecond), velocities.vx, 1e-9);
    assertEquals(0, velocities.vy, 1e-9);
  }

  @Test
  void thePerspectiveInvertsBothTranslationAxesTogether() {
    var blue = Drive.driverVelocities(-0.6, -0.4, 0, BLUE);
    var red = Drive.driverVelocities(-0.6, -0.4, 0, RED);

    assertEquals(-blue.vx, red.vx, 1e-9, "forward did not invert");
    assertEquals(-blue.vy, red.vy, 1e-9, "left did not invert");
  }

  // The perspective is a rotation about field centre, and a rotation does not change which way is
  // clockwise. A perspective that negates the spin turns the wrong way on one alliance only,
  // which reads as a gain problem rather than a sign error.
  @Test
  void thePerspectiveLeavesTheSpinAlone() {
    var blue = Drive.driverVelocities(0, 0, -0.7, BLUE);
    var red = Drive.driverVelocities(0, 0, -0.7, RED);

    assertEquals(blue.omega, red.omega, 1e-9);
    assertTrue(blue.omega > 0, "a right stick pushed left did not spin anticlockwise");
  }
}
