// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package first.robot;

import static org.wpilib.units.Units.Seconds;

import org.wpilib.math.estimator.SwerveDrivePoseEstimator3d;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Transform3d;
import org.wpilib.math.kinematics.SwerveDriveKinematics;
import org.wpilib.math.kinematics.SwerveModulePosition;
import org.wpilib.math.linalg.Matrix;
import org.wpilib.math.linalg.VecBuilder;
import org.wpilib.math.numbers.N1;
import org.wpilib.math.numbers.N4;
import org.wpilib.system.Timer;
import org.wpilib.telemetry.TelemetryTable;

public final class PoseEstimator {
  // Never read: visionUpdate sets the vision deviations on every call, before the estimator uses
  // them. An infinite sigma is a gain of exactly zero, which is what a measurement that somehow
  // arrived without one deserves.
  private static final Matrix<N4, N1> UNSET_VISION_STD_DEVS =
      VecBuilder.fill(
          Double.POSITIVE_INFINITY,
          Double.POSITIVE_INFINITY,
          Double.POSITIVE_INFINITY,
          Double.POSITIVE_INFINITY);

  private final SwerveDrivePoseEstimator3d fused;
  private final SwerveDrivePoseEstimator3d odometryOnly;
  private final TelemetryTable odometryLog;
  private final TelemetryTable visionLog;

  public PoseEstimator(
      SwerveDriveKinematics kinematics,
      Rotation3d gyroHeading,
      SwerveModulePosition[] modulePositions,
      TelemetryTable log) {
    odometryLog = log.getTable("Odometry");
    visionLog = log.getTable("Vision");
    fused = estimator(kinematics, gyroHeading, modulePositions);
    odometryOnly = estimator(kinematics, gyroHeading, modulePositions);
  }

  private static SwerveDrivePoseEstimator3d estimator(
      SwerveDriveKinematics kinematics,
      Rotation3d gyroHeading,
      SwerveModulePosition[] modulePositions) {
    return new SwerveDrivePoseEstimator3d(
        kinematics,
        gyroHeading,
        modulePositions,
        Pose3d.kZero,
        DriveConstants.STATE_STD_DEVS,
        UNSET_VISION_STD_DEVS);
  }

  public void odometryUpdate(Rotation3d gyroHeading, SwerveModulePosition... modulePositions) {
    // Stamped here rather than left to update(), which keys its buffer on a clock whose time base
    // something else is allowed to replace. Vision hands us instants on this one, and a buffer
    // keyed on a different clock drops every measurement without a word. One read, so the two
    // estimators are also keyed identically.
    double now = Timer.getMonotonicTimestamp();
    fused.updateWithTime(now, gyroHeading, modulePositions);
    odometryOnly.updateWithTime(now, gyroHeading, modulePositions);
  }

  public void visionUpdate(Pose3d measurement, double timestamp, Matrix<N4, N1> stdDevs) {
    // Logged before the measurement lands, so the residual is the disagreement the estimator was
    // handed rather than what the Kalman gain left of it.
    visionLog.log("Measurement", measurement, Pose3d.struct);
    fused
        .sampleAt(timestamp)
        .ifPresent(at -> visionLog.log("Residual", measurement.minus(at), Transform3d.struct));
    visionLog.log("Age", Seconds.of(Timer.getMonotonicTimestamp() - timestamp));
    visionLog.log("StdDevs", stdDevs.getData());

    fused.addVisionMeasurement(measurement, timestamp, stdDevs);
  }

  public void resetPose(Pose2d pose) {
    // Both, always. Reset one and the gap between them stops being wheel error and becomes wheel
    // error plus this offset, with nothing in the log to say which.
    // Drive's yaw-rate history is deliberately not among them: yaw rate is frame-independent, and
    // clearing it here would reject every vision frame for the whole history from autonomousInit.
    var widened = new Pose3d(pose);
    fused.resetPose(widened);
    odometryOnly.resetPose(widened);
  }

  public Pose2d getEstimatedPose() {
    return fused.getEstimatedPosition().toPose2d();
  }

  public Pose3d getEstimatedPose3d() {
    return fused.getEstimatedPosition();
  }

  public Pose3d getOdometryOnlyPose() {
    return odometryOnly.getEstimatedPosition();
  }

  public void log() {
    odometryLog.log("EstimatedPose", getEstimatedPose3d(), Pose3d.struct);
    odometryLog.log("OdometryOnlyPose", getOdometryOnlyPose(), Pose3d.struct);
  }
}
