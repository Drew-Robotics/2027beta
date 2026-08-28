// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Meters;
import static org.wpilib.units.Units.Radians;
import static org.wpilib.units.Units.Seconds;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.geometry.Translation2d;
import org.wpilib.math.kinematics.ChassisAccelerations;
import org.wpilib.math.kinematics.ChassisVelocities;
import org.wpilib.math.trajectory.HolonomicSample;
import org.wpilib.math.trajectory.HolonomicTrajectory;
import org.wpilib.telemetry.TelemetryTable;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Time;

public final class HolonomicPathFollower implements PathFollower {
  public record Config(
      double xKp,
      double yKp,
      double thetaKp,
      Distance positionTolerance,
      Angle headingTolerance,
      Time timeoutMargin) {}

  private final HolonomicTrajectory trajectory;
  private final Supplier<Pose2d> pose;
  private final DoubleSupplier clockSeconds;
  private final Config config;
  private final TelemetryTable followingLog;
  private final double startSeconds;

  private HolonomicSample setpoint;

  public HolonomicPathFollower(
      HolonomicTrajectory trajectory,
      Supplier<Pose2d> pose,
      DoubleSupplier clockSeconds,
      Config config,
      TelemetryTable followingLog) {
    this.trajectory = trajectory;
    this.pose = pose;
    this.clockSeconds = clockSeconds;
    this.config = config;
    this.followingLog = followingLog;
    startSeconds = clockSeconds.getAsDouble();
    setpoint = trajectory.start();
  }

  private double elapsed() {
    return clockSeconds.getAsDouble() - startSeconds;
  }

  @Override
  public ChassisVelocities next() {
    setpoint = trajectory.sampleAt(elapsed());

    var current = pose.get();
    var error = setpoint.pose.getTranslation().minus(current.getTranslation());
    var headingError = setpoint.pose.getRotation().minus(current.getRotation());
    log(error, headingError);

    return new ChassisVelocities(
        setpoint.velocity.vx + config.xKp() * error.getX(),
        setpoint.velocity.vy + config.yKp() * error.getY(),
        setpoint.velocity.omega + config.thetaKp() * headingError.getRadians());
  }

  // The sample next() left behind, so this has to be read after it within the same iteration.
  public ChassisAccelerations acceleration() {
    return setpoint.acceleration;
  }

  // Not time alone: a robot pinned against a defender runs the clock out where it is standing, and
  // a follower that reports done there hands the next command a robot a metre from where it thinks.
  @Override
  public boolean isDone() {
    var current = pose.get();
    var end = trajectory.end().pose;
    return elapsed() >= trajectory.duration
        && end.getTranslation().getDistance(current.getTranslation())
            <= config.positionTolerance().in(Meters)
        && Math.abs(end.getRotation().minus(current.getRotation()).getRadians())
            <= config.headingTolerance().in(Radians);
  }

  // The clock inside waitUntil starts when waitUntil does, so this is a margin over the path's own
  // duration rather than an absolute deadline.
  public Time timeout() {
    return Seconds.of(trajectory.duration).plus(config.timeoutMargin());
  }

  public Pose2d[] plannedPath() {
    return trajectory.getSamples().stream().map(sample -> sample.pose).toArray(Pose2d[]::new);
  }

  private void log(Translation2d error, Rotation2d headingError) {
    // Rotated into the sample's heading frame, because raw x and y cannot tell "on the path and
    // running late" from "on time and a metre left", and those have different causes.
    var track = error.rotateBy(setpoint.pose.getRotation().unaryMinus());
    followingLog.log("Setpoint", setpoint, HolonomicSample.struct);
    followingLog.log("AlongTrackError", Meters.of(track.getX()));
    followingLog.log("CrossTrackError", Meters.of(track.getY()));
    followingLog.log("HeadingError", headingError.getMeasure());
  }
}
