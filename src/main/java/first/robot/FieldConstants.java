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
import org.wpilib.util.Alert;
import org.wpilib.util.Alert.Level;

public final class FieldConstants {
  // False is the path exactly as it was drawn.
  public static final TunableBoolean MIRRORED = Tunables.addBoolean("Mirrored", false);

  // Blue is the answer that looks right on a bench and is wrong in half of every match, so a
  // missing alliance is said out loud rather than defaulted through.
  private static final Alert ALLIANCE_UNKNOWN =
      new Alert(
          "path-alliance-unknown", "Following a path with no alliance; assuming blue", Level.HIGH);

  // Choreo draws in the blue-alliance-corner frame and WPILib publishes its tag layouts in it; the
  // robot works in field centre, which is where 2027 is going. The dimensions are the 2026 field's
  // because that is the field the paths were drawn against, and both halves go to zero when the
  // origin moves.
  private static final double HALF_LENGTH = 16.541 / 2;
  private static final double HALF_WIDTH = 8.0692 / 2;

  private FieldConstants() {}

  public static double fromCornerX(double x) {
    return x - HALF_LENGTH;
  }

  public static double fromCornerY(double y) {
    return y - HALF_WIDTH;
  }

  public static HolonomicTrajectory forAlliance(HolonomicTrajectory trajectory) {
    return onRed() ? flip(trajectory) : trajectory;
  }

  public static HolonomicTrajectory forSide(HolonomicTrajectory trajectory) {
    return MIRRORED.getAsBoolean() ? mirror(trajectory) : trajectory;
  }

  // Each transform is its own inverse and the two commute, so applying whichever are in force
  // again carries a measured pose back into the frame the path was drawn in. A threshold written
  // against the drawn path is wrong otherwise, and wrong by never being reached rather than by
  // being reached in the wrong place.
  public static Pose2d asAuthored(Pose2d pose) {
    var unflipped = onRed() ? flip(pose) : pose;
    return MIRRORED.getAsBoolean() ? mirror(unflipped) : unflipped;
  }

  // The origin is field centre, where the flip is a 180-degree rotation about it. Anything drawn
  // against a corner comes through fromCornerX and fromCornerY first; vision is told the same
  // answer through Field.setOrigin.
  public static HolonomicTrajectory flip(HolonomicTrajectory trajectory) {
    return new HolonomicTrajectory(
        trajectory.getSamples().stream().map(FieldConstants::flip).toList());
  }

  // Not HolonomicTrajectory.transformBy: it is rigid about the trajectory's own first pose rather
  // than about the origin, and it carries the velocities and accelerations through unrotated.
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

  // A reflection across the field's long axis, which the centre origin puts at y = 0.
  public static HolonomicTrajectory mirror(HolonomicTrajectory trajectory) {
    return new HolonomicTrajectory(
        trajectory.getSamples().stream().map(FieldConstants::mirror).toList());
  }

  private static HolonomicSample mirror(HolonomicSample sample) {
    return new HolonomicSample(
        sample.time,
        mirror(sample.pose),
        // A reflection reverses handedness, so the spins invert — the sign the flip, being a
        // rotation, deliberately leaves alone.
        new ChassisVelocities(sample.velocity.vx, -sample.velocity.vy, -sample.velocity.omega),
        new ChassisAccelerations(
            sample.acceleration.ax, -sample.acceleration.ay, -sample.acceleration.alpha));
  }

  public static Pose2d mirror(Pose2d pose) {
    return new Pose2d(pose.getX(), -pose.getY(), pose.getRotation().unaryMinus());
  }

  private static boolean onRed() {
    var alliance = MatchState.getAlliance();
    ALLIANCE_UNKNOWN.set(alliance.isEmpty());
    return alliance.orElse(Alliance.BLUE) == Alliance.RED;
  }
}
