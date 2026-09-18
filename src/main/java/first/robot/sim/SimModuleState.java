// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import org.wpilib.math.geometry.Rotation2d;

// The applied volts are what reached the plant after the current limit, not what was commanded.
public record SimModuleState(
    double wheelPositionRad,
    double wheelVelocityRadPerSec,
    // Unwrapped. The steer loop closes on the motor's own encoder, which accumulates, so a model
    // of it needs the azimuth the module has actually turned through rather than where it points.
    double azimuthRad,
    double azimuthRadPerSec,
    boolean slipping,
    double driveAppliedVolts,
    double steerAppliedVolts) {

  public Rotation2d azimuth() {
    return new Rotation2d(azimuthRad);
  }
}
