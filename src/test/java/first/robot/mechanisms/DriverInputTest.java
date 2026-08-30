// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wpilib.units.Units.MetersPerSecond;
import static org.wpilib.units.Units.RadiansPerSecond;
import static org.wpilib.units.Units.Volts;

import first.robot.DriveConstants;
import org.junit.jupiter.api.Test;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.kinematics.SwerveDriveKinematics;

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
  // Translation and rotation share one wheel budget, and each stick alone is defined to spend all
  // of it -- MAX_ANGULAR_VELOCITY is the spin that puts the corner modules at MAX_VELOCITY. So
  // full stick on both asks for twice what the modules have, and desaturation halves both. This
  // is the whole of why the robot will not translate and spin at once at full deflection.
  @Test
  void fullTranslationAndFullRotationAtOnceAskForTwiceTheWheelsThereAre() {
    var kinematics =
        new SwerveDriveKinematics(
            DriveConstants.DRIVE.frontLeft().location(),
            DriveConstants.DRIVE.frontRight().location(),
            DriveConstants.DRIVE.backLeft().location(),
            DriveConstants.DRIVE.backRight().location());
    double max = DriveConstants.MAX_VELOCITY.in(MetersPerSecond);

    var both =
        kinematics.toSwerveModuleVelocities(
            new ChassisVelocities(
                max, 0, DriveConstants.MAX_ANGULAR_VELOCITY.in(RadiansPerSecond)));

    double fastest = 0;
    for (var state : both) {
      fastest = Math.max(fastest, Math.abs(state.velocity));
    }

    assertTrue(
        fastest > 1.8 * max,
        "a full translation and a full spin only asked for " + fastest / max + " of a wheel");
  }

  // And why the driver's stick is not allowed to ask for it. A turn a second leaves most of a
  // translation available while spinning; the geometric maximum leaves none.
  @Test
  void fullStickOnBothLeavesMostOfATranslationAfterDesaturation() {
    var kinematics =
        new SwerveDriveKinematics(
            DriveConstants.DRIVE.frontLeft().location(),
            DriveConstants.DRIVE.frontRight().location(),
            DriveConstants.DRIVE.backLeft().location(),
            DriveConstants.DRIVE.backRight().location());
    double max = DriveConstants.MAX_VELOCITY.in(MetersPerSecond);

    var asked =
        new ChassisVelocities(
            max, 0, DriveConstants.DRIVER_MAX_ANGULAR_VELOCITY.in(RadiansPerSecond));
    var states =
        SwerveDriveKinematics.desaturateWheelVelocities(
            kinematics.toSwerveModuleVelocities(asked), max);
    var delivered = kinematics.toChassisVelocities(states);

    double scale = Math.hypot(delivered.vx, delivered.vy) / max;
    assertTrue(scale > 0.7, "a full translation and a full driver spin scaled back to " + scale);
    assertTrue(
        DriveConstants.DRIVER_MAX_ANGULAR_VELOCITY.lt(DriveConstants.MAX_ANGULAR_VELOCITY),
        "the driver's stick is entitled to the whole wheel budget again");
  }

  @Test
  void thePerspectiveLeavesTheSpinAlone() {
    var blue = Drive.driverVelocities(0, 0, -0.7, BLUE);
    var red = Drive.driverVelocities(0, 0, -0.7, RED);

    assertEquals(blue.omega, red.omega, 1e-9);
    assertTrue(blue.omega > 0, "a right stick pushed left did not spin anticlockwise");
  }
}
