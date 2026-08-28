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

  // A target the sensor would read as 0.75 arrives from getRotations() as -0.25, which is not
  // merely wrong: it is outside the configured wrapping range, so wrapping cannot rescue it.
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
  void everyAngleLandsInsideTheConfiguredWrappingRange() {
    for (double rotations = -1; rotations <= 1; rotations += 0.01) {
      double folded = SwerveModule.toSensorRotations(Rotation2d.fromRotations(rotations), 0.37);
      assertTrue(folded >= 0 && folded < 1, rotations + " rotations folded to " + folded);
    }
  }

  // The steer loop closes on the SPARK against this sensor, so the conversion factor is fixed
  // rather than the ratiometric division a robot-side read would do.
  @Test
  void theSensorSpansOneModuleRotationOverItsSupplyRail() {
    assertEquals(
        1.0,
        DriveConstants.STEER_SENSOR_SPAN.magnitude() * DriveConstants.STEER_POSITION_FACTOR,
        TOLERANCE);
  }

  // The kinematics hand back an unsigned magnitude and a direction, so a module driving the other
  // way is braking and its share has to come back negative.
  @Test
  void aWheelDrivenAgainstTheAccelerationBrakesAgainstIt() {
    var acceleration = new SwerveModuleAcceleration(4.0, Rotation2d.kZero);

    assertEquals(4.0, SwerveModule.accelerationAlong(acceleration, Rotation2d.kZero), TOLERANCE);
    assertEquals(-4.0, SwerveModule.accelerationAlong(acceleration, Rotation2d.kPi), TOLERANCE);
  }

  @Test
  void aWheelTurnedAcrossTheAccelerationTakesNoneOfIt() {
    var acceleration = new SwerveModuleAcceleration(4.0, Rotation2d.kZero);

    assertEquals(0, SwerveModule.accelerationAlong(acceleration, Rotation2d.kCCW_Pi_2), TOLERANCE);
  }
}
