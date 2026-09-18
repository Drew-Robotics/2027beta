// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import first.robot.DriveConstants;
import org.junit.jupiter.api.Test;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.SwerveModuleAcceleration;

class SwerveModuleTest {
  private static final double TOLERANCE = 1e-9;

  // A target the sensor would read as 0.75 arrives from getRotations() as -0.25. The seed and the
  // simulation's sensor feed are the two places that difference still matters.
  @Test
  void theSecondHalfTurnComesBackInsideTheSensorsRange() {
    var target = Rotation2d.fromRotations(0.75);

    assertEquals(-0.25, target.getRotations(), TOLERANCE, "Rotation2d changed its range");
    assertEquals(0.75, SwerveModule.toSensorRotations(target, 0), TOLERANCE);
  }

  @Test
  void anOffsetPastTheBoundaryWrapsRatherThanRunningOffTheEnd() {
    assertEquals(
        0.1, SwerveModule.toSensorRotations(Rotation2d.fromRotations(0.4), 0.7), TOLERANCE);
  }

  @Test
  void everyAngleLandsInsideTheSensorsRange() {
    for (double rotations = -1; rotations <= 1; rotations += 0.01) {
      double folded = SwerveModule.toSensorRotations(Rotation2d.fromRotations(rotations), 0.37);
      assertTrue(folded >= 0 && folded < 1, rotations + " rotations folded to " + folded);
    }
  }

  // The analog spans one module rotation over its supply rail, which is what makes a reading in
  // volts a module angle. It seeds the steer encoder rather than closing a loop, so this is a
  // read-back conversion: nothing is written through it.
  @Test
  void theSensorSpansOneModuleRotationOverItsSupplyRail() {
    assertEquals(
        1.0,
        DriveConstants.STEER_SENSOR_SPAN.magnitude() * DriveConstants.STEER_SENSOR_POSITION_FACTOR,
        TOLERANCE);
  }

  // The steer loop closes on the motor's own encoder, and the setpoint is an offset on its count
  // rather than an angle, so the error the SPARK sees is the short way round even though the
  // device wraps nothing.
  @Test
  void theSetpointIsAlwaysTheShortWayRoundFromWhereTheEncoderIs() {
    double quarterTurn = DriveConstants.STEER_REDUCTION / 4;

    assertEquals(
        quarterTurn,
        DriveConstants.steerSetpoint(0, Rotation2d.fromRotations(0.25)),
        TOLERANCE,
        "a quarter turn forwards");
    assertEquals(
        -quarterTurn,
        DriveConstants.steerSetpoint(0, Rotation2d.fromRotations(0.75)),
        TOLERANCE,
        "three quarters forwards is a quarter back");
  }

  // The encoder accumulates, so an offset can carry the setpoint outside any one turn. That is the
  // point: a count that has wound up several turns is still a quarter turn from its neighbour, and
  // there is no boundary in it for the setpoint to run off the end of.
  @Test
  void aWoundUpEncoderStillGetsAnOffsetRatherThanAnAngle() {
    double threeTurns = 3 * DriveConstants.STEER_REDUCTION;

    assertEquals(
        threeTurns + DriveConstants.STEER_REDUCTION / 4,
        DriveConstants.steerSetpoint(threeTurns, Rotation2d.fromRotations(0.25)),
        TOLERANCE);
  }

  @Test
  void theSetpointNeverAsksForMoreThanHalfATurn() {
    for (double rotations = -1; rotations <= 1; rotations += 0.01) {
      double encoder = 7.3;
      double travel =
          DriveConstants.steerSetpoint(encoder, Rotation2d.fromRotations(rotations)) - encoder;
      assertTrue(
          Math.abs(travel) <= DriveConstants.STEER_REDUCTION / 2 + TOLERANCE,
          rotations + " rotations asked for " + travel + " motor rotations of travel");
    }
  }

  // The device closes in native units and a characterisation measures wheel speeds and module
  // angles, so the rescale is a pure scalar in each direction and the round trip has to be exact.
  @Test
  void theOnboardGainsAreTheTunedOnesInTheUnitsTheDeviceUses() {
    var tuned = DriveConstants.REAL_GAINS;
    var onboard = DriveConstants.onboardGains(tuned);

    assertEquals(
        tuned.drive().kP() * DriveConstants.DRIVE_VELOCITY_FACTOR,
        onboard.drive().kP(),
        TOLERANCE,
        "drive kP is duty per RPM");
    assertEquals(
        tuned.drive().kV() * DriveConstants.DRIVE_VELOCITY_FACTOR,
        onboard.drive().kV(),
        TOLERANCE,
        "drive kV is volts per RPM");
    assertEquals(
        tuned.steer().kP() / DriveConstants.STEER_REDUCTION,
        onboard.steer().kP(),
        TOLERANCE,
        "steer kP is duty per motor rotation");
    assertEquals(
        tuned.steer().kD() / DriveConstants.STEER_REDUCTION,
        onboard.steer().kD(),
        TOLERANCE,
        "steer kD is duty-milliseconds per motor rotation");
    // Both kS terms are output volts and dFilter is a filter coefficient, so neither moves.
    assertEquals(tuned.drive().kS(), onboard.drive().kS(), TOLERANCE);
    assertEquals(tuned.steer().kS(), onboard.steer().kS(), TOLERANCE);
    assertEquals(tuned.steer().dFilter(), onboard.steer().dFilter(), TOLERANCE);
  }

  // The kinematics hand back an unsigned magnitude and a direction, so a module driving the other
  // way is braking and its share has to come back negative.
  @Test
  void aWheelDrivenAgainstTheAccelerationBrakesAgainstIt() {
    var acceleration = new SwerveModuleAcceleration(4.0, Rotation2d.ZERO);

    assertEquals(4.0, SwerveModule.accelerationAlong(acceleration, Rotation2d.ZERO), TOLERANCE);
    assertEquals(-4.0, SwerveModule.accelerationAlong(acceleration, Rotation2d.PI), TOLERANCE);
  }

  @Test
  void aWheelTurnedAcrossTheAccelerationTakesNoneOfIt() {
    var acceleration = new SwerveModuleAcceleration(4.0, Rotation2d.ZERO);

    assertEquals(0, SwerveModule.accelerationAlong(acceleration, Rotation2d.CCW_PI_2), TOLERANCE);
  }
}
