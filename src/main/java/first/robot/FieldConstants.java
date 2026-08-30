// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import org.wpilib.driverstation.Alliance;
import org.wpilib.driverstation.MatchState;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.kinematics.ChassisAccelerations;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.trajectory.HolonomicSample;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.tunable.TunableBoolean;
import org.wpilib.tunable.Tunables;

public final class FieldConstants {
  public static final TunableBoolean MIRRORED = Tunables.addBoolean("Mirrored", false);

  // Choreo paths use the 2026 blue-corner frame; this code uses field center.
  private static final double HALF_LENGTH = 16.541 / 2;
  private static final double HALF_WIDTH = 8.0692 / 2;

  private FieldConstants() {}

  public static double fromCornerX(double x) {
    return x - HALF_LENGTH;
  }

  public static double fromCornerY(double y) {
    return y - HALF_WIDTH;
  }

  public static HolonomicTrajectory forCurrentAlliance(HolonomicTrajectory trajectory) {
    return onRed() ? flip(trajectory) : trajectory;
  }

  public static HolonomicTrajectory withDashboardMirror(HolonomicTrajectory trajectory) {
    return MIRRORED.getAsBoolean() ? mirror(trajectory) : trajectory;
  }

  // Convert a measured pose back to the path's frame before comparing it to path thresholds.
  public static Pose2d toAuthoredPathFrame(Pose2d pose) {
    var flipped = onRed() ? flip(pose) : pose;
    return MIRRORED.getAsBoolean() ? mirror(flipped) : flipped;
  }

  // With a center origin, an alliance flip is a 180-degree rotation.
  public static HolonomicTrajectory flip(HolonomicTrajectory trajectory) {
    return new HolonomicTrajectory(
        trajectory.getSamples().stream().map(FieldConstants::flip).toList());
  }

  // transformBy uses the first pose as its origin and does not rotate velocity or acceleration.
  private static HolonomicSample flip(HolonomicSample sample) {
    return new HolonomicSample(
        sample.time,
        flip(sample.pose),
        new ChassisVelocities(-sample.velocity.vx, -sample.velocity.vy, sample.velocity.omega),
        new ChassisAccelerations(
            -sample.acceleration.ax, -sample.acceleration.ay, sample.acceleration.alpha));
  }

  public static Pose2d flip(Pose2d pose) {
    return new Pose2d(-pose.getX(), -pose.getY(), pose.getRotation().rotateBy(Rotation2d.kPi));
  }

  // Reflect across the field's long axis (y = 0).
  public static HolonomicTrajectory mirror(HolonomicTrajectory trajectory) {
    return new HolonomicTrajectory(
        trajectory.getSamples().stream().map(FieldConstants::mirror).toList());
  }

  private static HolonomicSample mirror(HolonomicSample sample) {
    return new HolonomicSample(
        sample.time,
        mirror(sample.pose),
        // A reflection reverses rotation direction.
        new ChassisVelocities(sample.velocity.vx, -sample.velocity.vy, -sample.velocity.omega),
        new ChassisAccelerations(
            sample.acceleration.ax, -sample.acceleration.ay, -sample.acceleration.alpha));
  }

  public static Pose2d mirror(Pose2d pose) {
    return new Pose2d(pose.getX(), -pose.getY(), pose.getRotation().unaryMinus());
  }

  // Default to blue until the Driver Station reports an alliance.
  public static boolean onRed() {
    return MatchState.getAlliance().orElse(Alliance.BLUE) == Alliance.RED;
  }
}
