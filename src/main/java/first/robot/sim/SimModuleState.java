// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot.sim;

import org.wpilib.math.geometry.Rotation2d;

public record SimModuleState(
    double wheelPositionRad, double wheelVelocityRadPerSec, Rotation2d azimuth, boolean slipping) {}
