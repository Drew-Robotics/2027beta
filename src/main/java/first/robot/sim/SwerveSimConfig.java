// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.system.DCMotor;
import org.wpilib.units.measure.Current;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.MomentOfInertia;

public record SwerveSimConfig(
    Translation2d frontLeft,
    Translation2d frontRight,
    Translation2d backLeft,
    Translation2d backRight,
    Distance wheelRadius,
    Axis drive,
    Axis steer) {

  public record Axis(
      DCMotor motor, double reduction, MomentOfInertia inertia, Current currentLimit) {}

  // The order every array in this package is indexed by.
  public Translation2d[] moduleLocations() {
    return new Translation2d[] {frontLeft, frontRight, backLeft, backRight};
  }
}
