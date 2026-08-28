// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import org.wpilib.math.kinematics.ChassisVelocities;

// next() returns FIELD-relative velocities. No type and no name says so, and a follower that
// returns robot-relative ones compiles, runs, and tracks a straight path perfectly right up to the
// moment the robot rotates.
public interface PathFollower {
  ChassisVelocities next();

  boolean isDone();
}
