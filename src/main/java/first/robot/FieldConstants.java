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
import org.wpilib.util.Alert;
import org.wpilib.util.Alert.Level;

public final class FieldConstants {
  // Blue is the answer that looks right on a bench and is wrong in half of every match, so a
  // missing alliance is said out loud rather than defaulted through.
  private static final Alert ALLIANCE_UNKNOWN =
      new Alert(
          "path-alliance-unknown", "Following a path with no alliance; assuming blue", Level.HIGH);

  private FieldConstants() {}

  public static HolonomicTrajectory forAlliance(HolonomicTrajectory trajectory) {
    return onRed() ? flip(trajectory) : trajectory;
  }

  // The flip is its own inverse, so the same rotation carries a measured pose back into the frame
  // the path was drawn in. A threshold written against the drawn path is wrong on red otherwise,
  // and wrong by never being reached rather than by being reached in the wrong place.
  public static Pose2d asAuthored(Pose2d pose) {
    return onRed() ? flip(pose) : pose;
  }

  // The origin is field centre, where the flip is a 180-degree rotation about it. Under the
  // blue-corner origin the alpha-7 tree still documents, the same flip is a reflection instead,
  // and every line below changes. Vision has to be told the same answer.
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

  private static boolean onRed() {
    var alliance = MatchState.getAlliance();
    ALLIANCE_UNKNOWN.set(alliance.isEmpty());
    return alliance.orElse(Alliance.BLUE) == Alliance.RED;
  }
}
